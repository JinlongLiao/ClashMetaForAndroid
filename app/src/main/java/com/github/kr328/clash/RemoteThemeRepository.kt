package com.github.kr328.clash

import android.content.Context
import android.graphics.BitmapFactory
import android.content.res.Configuration
import com.github.kr328.clash.design.model.RemoteTheme
import com.github.kr328.clash.design.model.ThemePalette
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Downloads and caches desktop theme-hub CSS using a deliberately narrow parser.
 *
 * CSS is stored only as source data and is never evaluated by Android. Refresh accepts flat
 * `.css` entries, rejects traversal paths and bounds the archive, entry count and extracted size.
 * A failed refresh leaves the last complete cache untouched so theme selection keeps working
 * offline.
 */
class RemoteThemeRepository private constructor(
    private val context: Context?,
    @Suppress("UNUSED_PARAMETER") parserOnly: Boolean,
) {
    /** Creates the production repository backed by application-private storage. */
    constructor(context: Context) : this(context.applicationContext, false)

    /** Creates a parser-only repository for local unit tests. */
    internal constructor() : this(null, true)
    /** Returns all valid themes from the last successful cache refresh. */
    fun queryCachedThemes(): List<RemoteTheme> {
        return themeDirectory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.extension.equals(CSS_EXTENSION, ignoreCase = true) }
            .mapNotNull { file ->
                runCatching { parseRemoteTheme(file.name, file.readText(), isDarkAppearance()) }.getOrNull()
            }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
            .toList()
    }

    /**
     * Replaces the cache after a complete, validated download.
     *
     * @return parsed themes made available by the new cache.
     * @throws Exception when transport, archive validation or parsing fails.
     */
    fun refreshThemes(): List<RemoteTheme> {
        val connection = (URL(THEME_ARCHIVE_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            instanceFollowRedirects = true
            requestMethod = "GET"
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Theme hub returned HTTP ${connection.responseCode}")
            }
            if (connection.contentLengthLong > MAX_ARCHIVE_BYTES) {
                throw IllegalStateException("Theme archive exceeds $MAX_ARCHIVE_BYTES bytes")
            }

            val extracted = LinkedHashMap<String, ByteArray>()
            var archiveBytes = 0L
            var totalExtractedBytes = 0L
            ZipInputStream(BufferedInputStream(connection.inputStream)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) {
                        continue
                    }
                    if (++archiveBytes > MAX_ENTRY_COUNT) {
                        throw IllegalStateException("Theme archive contains too many entries")
                    }
                    val key = validateEntryName(entry.name)
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(BUFFER_SIZE)
                    var entryBytes = 0L
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) {
                            break
                        }
                        entryBytes += count
                        totalExtractedBytes += count
                        if (entryBytes > MAX_THEME_BYTES || totalExtractedBytes > MAX_EXTRACTED_BYTES) {
                            throw IllegalStateException("Theme archive exceeds extraction limits")
                        }
                        output.write(buffer, 0, count)
                    }
                    extracted[key] = output.toByteArray()
                }
            }
            if (extracted.isEmpty()) {
                throw IllegalStateException("Theme archive contains no CSS themes")
            }

            val parsed = extracted.map { (key, bytes) ->
                parseRemoteTheme(key, bytes.toString(Charsets.UTF_8), isDarkAppearance())
            }
            replaceCache(extracted)
            return parsed.sortedBy { it.label.lowercase(Locale.getDefault()) }
        } finally {
            connection.disconnect()
        }
    }

    /** Maps a remote primary color to the nearest stable native palette. */
    fun resolveNativePalette(theme: RemoteTheme): ThemePalette {
        val primary = theme.primaryColor ?: theme.secondaryColor ?: return ThemePalette.Blue
        val hsv = FloatArray(3)
        colorToHsv(primary, hsv)
        val secondaryHsv = FloatArray(3)
        theme.secondaryColor?.let { color -> colorToHsv(color, secondaryHsv) }
        val hue = hsv[0]
        return when {
            hue >= 350f || hue < 55f -> ThemePalette.OrangeRed
            hue < 175f -> ThemePalette.Teal
            hue < 235f && secondaryHsv[0] in 245f..315f -> ThemePalette.Indigo
            hue < 235f -> ThemePalette.Blue
            hue < 275f -> ThemePalette.Indigo
            else -> ThemePalette.Purple
        }
    }

    /** Parses only semantic CSS custom properties and ignores selectors and executable syntax. */
    internal fun parseRemoteTheme(key: String, css: String, darkAppearance: Boolean = false): RemoteTheme {
        val label = FIRST_COMMENT.find(css)?.groupValues?.get(1)?.trim().orEmpty().ifEmpty { key }
        // Desktop themes commonly declare light and dark selectors in the same file. Android
        // must discard the inactive branch before applying normal CSS last-declaration precedence.
        val activeCss = if (darkAppearance) {
            LIGHT_APPEARANCE_BLOCK.replace(css, "")
        } else {
            DARK_APPEARANCE_BLOCK.replace(css, "")
        }
        val declarations = DECLARATION.findAll(activeCss).associate { match ->
            match.groupValues[1].lowercase(Locale.ROOT) to match.groupValues[2].trim()
        }
        val fallbackColor = CSS_HEX_COLOR.find(css)?.value?.let(::parseHexColor)
        return RemoteTheme(
            key = key,
            label = label,
            primaryColor = declarations.findColor(PRIMARY_KEYS) ?: fallbackColor,
            secondaryColor = declarations.findColor(SECONDARY_KEYS),
            backgroundColor = declarations.findColor(BACKGROUND_KEYS),
            surfaceColor = declarations.findColor(SURFACE_KEYS),
            onSurfaceColor = declarations.findColor(ON_SURFACE_KEYS),
            outlineColor = declarations.findColor(OUTLINE_KEYS),
            backgroundImageUrl = declarations.resolveUrl("--custom-background-image"),
            accentGradientColors = findBrandingGradientColors(declarations),
            surfaceOpacity = declarations["--custom-content1-opacity"]?.toFloatOrNull()?.coerceIn(0f, 1f),
        )
    }

    /** Returns whether the repository context currently renders with Android night resources. */
    private fun isDarkAppearance(): Boolean {
        val mode = requireNotNull(context).resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    /** Downloads and validates a selected theme background into application-private storage. */
    fun cacheThemeBackground(theme: RemoteTheme): String? {
        val source = theme.backgroundImageUrl ?: return null
        val url = URL(source)
        require(url.protocol.equals("https", ignoreCase = true)) { "Theme background must use HTTPS" }
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = READ_TIMEOUT_MILLIS
        connection.instanceFollowRedirects = true
        try {
            connection.connect()
            require(connection.responseCode in 200..299) {
                "Theme background returned HTTP ${connection.responseCode}"
            }
            require(connection.contentLengthLong <= MAX_BACKGROUND_IMAGE_BYTES) {
                "Theme background is too large"
            }
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) {
                        break
                    }
                    total += count
                    require(total <= MAX_BACKGROUND_IMAGE_BYTES) { "Theme background is too large" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            require(BitmapFactory.decodeByteArray(bytes, 0, bytes.size) != null) {
                "Theme background is not a supported image"
            }
            val directory = File(requireNotNull(context).filesDir, "theme-backgrounds").apply { mkdirs() }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(source.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
            return File(directory, "$digest.image").apply { writeBytes(bytes) }.absolutePath
        } finally {
            connection.disconnect()
        }
    }

    /** Resolves a CSS url(...) token through bounded custom-property references. */
    private fun Map<String, String>.resolveUrl(key: String): String? {
        val value = this[key] ?: return null
        val reference = CSS_VARIABLE.matchEntire(value.substringBefore("!important").trim())
        val resolved = if (reference == null) value else this[reference.groupValues[1].lowercase(Locale.ROOT)]
        return resolved?.let { CSS_URL.find(it)?.groupValues?.get(2) }
    }

    /** Extracts a stable branding gradient without executing arbitrary CSS. */
    private fun findBrandingGradientColors(declarations: Map<String, String>): List<Int> {
        val value = GRADIENT_KEYS.asSequence()
            .mapNotNull { key -> declarations[key] }
            .firstOrNull { candidate -> candidate.contains("linear-gradient", ignoreCase = true) }
            ?: declarations.values.firstOrNull { candidate -> candidate.contains("linear-gradient", ignoreCase = true) }
            ?: return emptyList()
        return CSS_HEX_COLOR.findAll(value).mapNotNull { match -> parseHexColor(match.value) }.distinct().toList()
    }

    /** Validates a ZIP entry and returns its flat cache name. */
    internal fun validateEntryName(name: String): String {
        val normalized = name.replace('\\', '/')
        if (normalized.contains('/') || normalized.contains("..") ||
            !normalized.endsWith(".$CSS_EXTENSION", ignoreCase = true)
        ) {
            throw IllegalStateException("Unsafe theme archive entry: $name")
        }
        return normalized
    }

    /** Replaces cached CSS only after the entire downloaded archive passes validation. */
    private fun replaceCache(themes: Map<String, ByteArray>) {
        val filesDirectory = requireNotNull(context).filesDir
        val staging = File(filesDirectory, "theme-hub-next").apply {
            deleteRecursively()
            mkdirs()
        }
        themes.forEach { (key, value) -> File(staging, key).writeBytes(value) }
        val backup = File(filesDirectory, "theme-hub-previous").apply { deleteRecursively() }
        if (themeDirectory.exists() && !themeDirectory.renameTo(backup)) {
            staging.deleteRecursively()
            throw IllegalStateException("Unable to preserve the previous theme cache")
        }
        if (!staging.renameTo(themeDirectory)) {
            backup.renameTo(themeDirectory)
            staging.deleteRecursively()
            throw IllegalStateException("Unable to commit the downloaded theme cache")
        }
        backup.deleteRecursively()
    }

    /** Reads the first supported, directly encoded CSS color from a property set. */
    private fun Map<String, String>.findColor(keys: Set<String>): Int? {
        return keys.asSequence().mapNotNull { key -> resolveColor(key, emptySet()) }.firstOrNull()
    }

    /** Resolves bounded CSS variable references without evaluating general CSS expressions. */
    private fun Map<String, String>.resolveColor(key: String, visited: Set<String>): Int? {
        if (key in visited || visited.size >= MAX_VARIABLE_DEPTH) {
            return null
        }
        val value = this[key] ?: return null
        val reference = CSS_VARIABLE.matchEntire(value.substringBefore("!important").trim())
        return if (reference == null) {
            parseColor(value)
        } else {
            resolveColor(reference.groupValues[1].lowercase(Locale.ROOT), visited + key)
        }
    }

    /** Accepts concrete hex, RGB and HSL colors; all other CSS expressions are ignored. */
    private fun parseColor(value: String): Int? {
        val candidate = value.substringBefore("!important").trim()
        return runCatching {
            when {
                candidate.startsWith('#') -> parseHexColor(candidate)
                RGB_COLOR.matches(candidate) -> {
                    val values = RGB_COMPONENT.findAll(candidate).map { it.value.toInt().coerceIn(0, 255) }.toList()
                    if (values.size < 3) {
                        null
                    } else {
                        argb(values[0], values[1], values[2])
                    }
                }
                HSL_COLOR.matches(candidate) -> {
                    val values = DECIMAL_COMPONENT.findAll(candidate).map { it.value.toFloat() }.toList()
                    if (values.size < 3) {
                        null
                    } else {
                        hslToColor(values[0], values[1], values[2])
                    }
                }
                else -> null
            }
        }.getOrNull()
    }

    /** Persistent cache used for offline theme selection. */
    private val themeDirectory: File
        get() = File(requireNotNull(context).filesDir, "theme-hub")

    /** Parses six- or eight-digit concrete CSS hex colors without Android runtime helpers. */
    private fun parseHexColor(value: String): Int? {
        val hex = value.removePrefix("#")
        return when (hex.length) {
            6 -> (0xFF000000L or hex.toLong(16)).toInt()
            8 -> hex.toLong(16).toInt()
            else -> null
        }
    }

    /** Packs opaque RGB components into an Android-compatible ARGB integer. */
    private fun argb(red: Int, green: Int, blue: Int): Int {
        return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }

    /** Converts a concrete HSL triplet into an opaque ARGB color. */
    private fun hslToColor(hue: Float, saturationPercent: Float, lightnessPercent: Float): Int {
        val h = ((hue % 360f) + 360f) % 360f / 360f
        val s = saturationPercent.coerceIn(0f, 100f) / 100f
        val l = lightnessPercent.coerceIn(0f, 100f) / 100f
        val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q
        fun channel(offset: Float): Int {
            var t = h + offset
            if (t < 0f) t += 1f
            if (t > 1f) t -= 1f
            val value = when {
                t < 1f / 6f -> p + (q - p) * 6f * t
                t < 1f / 2f -> q
                t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
                else -> p
            }
            return (value * 255f).toInt().coerceIn(0, 255)
        }
        return argb(channel(1f / 3f), channel(0f), channel(-1f / 3f))
    }

    /** Converts ARGB to the hue component used for stable native-palette projection. */
    private fun colorToHsv(color: Int, hsv: FloatArray) {
        val red = ((color shr 16) and 0xFF) / 255f
        val green = ((color shr 8) and 0xFF) / 255f
        val blue = (color and 0xFF) / 255f
        val max = maxOf(red, green, blue)
        val min = minOf(red, green, blue)
        val delta = max - min
        val hue = when {
            delta == 0f -> 0f
            max == red -> 60f * (((green - blue) / delta) % 6f)
            max == green -> 60f * ((blue - red) / delta + 2f)
            else -> 60f * ((red - green) / delta + 4f)
        }
        hsv[0] = if (hue < 0f) hue + 360f else hue
        hsv[1] = if (max == 0f) 0f else delta / max
        hsv[2] = max
    }

    companion object {
        private const val THEME_ARCHIVE_URL =
            "https://github.com/mihomo-party-org/theme-hub/releases/download/latest/themes.zip"
        private const val CSS_EXTENSION = "css"
        private const val CONNECT_TIMEOUT_MILLIS = 15_000
        private const val READ_TIMEOUT_MILLIS = 30_000
        private const val BUFFER_SIZE = 8 * 1024
        private const val MAX_ARCHIVE_BYTES = 8L * 1024L * 1024L
        private const val MAX_EXTRACTED_BYTES = 4L * 1024L * 1024L
        // The official anime.css currently embeds an image and is about 1.7 MiB.
        private const val MAX_THEME_BYTES = 2L * 1024L * 1024L
        private const val MAX_ENTRY_COUNT = 128L
        private const val MAX_VARIABLE_DEPTH = 8
        private const val MAX_BACKGROUND_IMAGE_BYTES = 12L * 1024L * 1024L
        private val FIRST_COMMENT = Regex("^\\s*/\\*([^\\r\\n*]*)\\*/")
        private val LIGHT_APPEARANCE_BLOCK = Regex(
            "(?:\\.light|\\[data-theme=[\"']light[\"']\\])[^\\x7B\\x7D]*\\x7B[^\\x7B\\x7D]*\\x7D",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val DARK_APPEARANCE_BLOCK = Regex(
            "(?:\\.dark|\\[data-theme=[\"']dark[\"']\\])[^\\x7B\\x7D]*\\x7B[^\\x7B\\x7D]*\\x7D",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val DECLARATION = Regex("(--[a-zA-Z0-9_-]+)\\s*:\\s*([^;}{]+)")
        private val RGB_COLOR = Regex("rgba?\\s*\\([^)]*\\)", RegexOption.IGNORE_CASE)
        private val RGB_COMPONENT = Regex("\\d+")
        // HeroUI themes in the official hub sometimes omit '%' from saturation/lightness.
        private val HSL_COLOR = Regex("(?:hsl\\s*\\()?[-.\\d]+\\s+[-.\\d]+%?\\s+[-.\\d]+%?\\)?", RegexOption.IGNORE_CASE)
        private val DECIMAL_COMPONENT = Regex("[-.\\d]+")
        private val CSS_HEX_COLOR = Regex("#[0-9a-fA-F]{6}(?:[0-9a-fA-F]{2})?")
        private val CSS_VARIABLE = Regex("var\\((--[a-zA-Z0-9_-]+)\\)")
        private val CSS_URL = Regex("url\\((['\"]?)(https://[^)'\"]+)\\1\\)", RegexOption.IGNORE_CASE)
        private val GRADIENT_KEYS = listOf(
            "--gemini-color-logo-gradient",
            "--gemini-branding-text-gradient",
            "--gemini-color-primary-button-gradient",
        )
        private val PRIMARY_KEYS = setOf("--primary", "--primary-color", "--color-primary", "--accent-color", "--heroui-primary")
        private val SECONDARY_KEYS = setOf("--secondary", "--secondary-color", "--color-secondary", "--heroui-secondary")
        private val BACKGROUND_KEYS = setOf("--background", "--background-color", "--body-bg", "--color-background", "--heroui-background", "--custom-main-background")
        private val SURFACE_KEYS = setOf("--surface", "--surface-color", "--card-color", "--card-bg", "--heroui-content1", "--custom-side-background")
        // HeroUI's generic foreground token may intentionally be muted. Prefer the concrete
        // content foreground so native cards retain the readable body-text contrast.
        private val ON_SURFACE_KEYS = setOf(
            "--on-surface",
            "--text-color",
            "--foreground",
            "--heroui-content1-foreground",
            "--heroui-foreground",
        )
        private val OUTLINE_KEYS = setOf("--outline", "--border-color", "--divider-color", "--heroui-divider")
    }
}
