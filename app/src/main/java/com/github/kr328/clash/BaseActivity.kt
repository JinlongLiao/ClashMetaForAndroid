package com.github.kr328.clash

import android.app.ActivityManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.github.kr328.clash.common.compat.isAllowForceDarkCompat
import com.github.kr328.clash.common.compat.isLightNavigationBarCompat
import com.github.kr328.clash.common.compat.isLightStatusBarsCompat
import com.github.kr328.clash.common.compat.isSystemBarsTranslucentCompat
import com.github.kr328.clash.core.bridge.ClashException
import com.github.kr328.clash.design.Design
import com.github.kr328.clash.design.model.DarkMode
import com.github.kr328.clash.design.model.ThemePalette
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.view.LargeActionLabel
import com.github.kr328.clash.design.view.ActivityBarLayout
import com.github.kr328.clash.design.ui.DayNight
import com.github.kr328.clash.design.util.resolveThemedBoolean
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.remote.Broadcasts
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.util.ActivityResultLifecycle
import com.github.kr328.clash.util.ApplicationObserver
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import com.github.kr328.clash.design.R

abstract class BaseActivity<D : Design<*>> : AppCompatActivity(),
    CoroutineScope by MainScope(),
    Broadcasts.Observer {
    
    protected val uiStore by lazy { UiStore(this) }
    protected val events = Channel<Event>(Channel.UNLIMITED)
    protected var activityStarted: Boolean = false
    protected val clashRunning: Boolean
        get() = Remote.broadcasts.clashRunning
    protected var design: D? = null
        set(value) {
            field = value
            if (value != null) {
                setContentView(value.root)
                applyRemoteThemeTokens(value.root)
            } else {
                setContentView(View(this))
            }
        }

    private var defer: suspend () -> Unit = {}
    private var deferRunning = false
    private val nextRequestKey = AtomicInteger(0)
    private var dayNight: DayNight = DayNight.Day

    protected abstract suspend fun main()

    fun defer(operation: suspend () -> Unit) {
        this.defer = operation
    }

    /**
     * Applies persisted remote theme-hub semantic colors to native views.
     *
     * Remote CSS is never executed. Layout, typography and touch geometry remain controlled by
     * the application; only validated ARGB tokens are projected onto page surfaces, cards,
     * content and buttons. Missing tokens retain the reviewed local theme values.
     */
    private fun applyRemoteThemeTokens(
        root: View,
        previewImageOpacityPermille: Int? = null,
        previewSurfaceOpacityPermille: Int? = null,
    ) {
        val customBackgroundPath = uiStore.customBackgroundImagePath
        val hasRemoteTheme = uiStore.remoteThemeKey.isNotEmpty()
        if (!hasRemoteTheme && customBackgroundPath.isEmpty()) {
            return
        }
        val configuredBackground = uiStore.remoteThemeBackgroundColor.takeIf { hasRemoteTheme && it != 0 }
            ?: resolveThemedColor(android.R.attr.colorBackground)
        val configuredSurface = uiStore.remoteThemeSurfaceColor.takeIf { hasRemoteTheme && it != 0 }
            ?: resolveThemedColor(com.google.android.material.R.attr.colorSurface)
        // Online themes normally expose one palette rather than separate light/dark tokens. When
        // the application is in dark mode, convert only light backgrounds and surfaces to a dark
        // tonal base before applying transparency, so a translucent card never remains white.
        val background = adaptThemeBaseColorForDayNight(configuredBackground, dayNight)
        val surface = adaptThemeBaseColorForDayNight(configuredSurface, dayNight)
        val primary = uiStore.remoteThemePrimaryColor.takeIf { hasRemoteTheme && it != 0 }
            ?: resolveThemedColor(com.google.android.material.R.attr.colorPrimary)
        val onSurface = if (surface != configuredSurface) {
            readableForeground(surface)
        } else {
            uiStore.remoteThemeOnSurfaceColor.takeIf { hasRemoteTheme && it != 0 }
                ?: readableForeground(surface)
        }
        val outline = uiStore.remoteThemeOutlineColor.takeIf { hasRemoteTheme && it != 0 }
            ?: blendColors(onSurface, surface, 0.22f)
        val visibleOutline = blendColors(outline, surface, 0.20f)
        val surfaceOpacityPermille = if (customBackgroundPath.isNotEmpty()) {
            previewSurfaceOpacityPermille ?: uiStore.customBackgroundSurfaceOpacityPermille
        } else {
            uiStore.remoteThemeSurfaceOpacityPermille
        }
        val surfaceOpacity = (surfaceOpacityPermille / 1000f).coerceIn(0f, 1f)
        val gradientColors = if (hasRemoteTheme) {
            uiStore.remoteThemeAccentGradient
                .split(',')
                .mapNotNull { value -> value.toIntOrNull() }
                .toIntArray()
        } else {
            intArrayOf()
        }

        // A user-selected image deliberately overrides the current online theme background while
        // retaining that theme's colors and gradient accents.
        val backgroundFile = File(
            customBackgroundPath.ifEmpty {
                uiStore.remoteThemeBackgroundImagePath.takeIf { hasRemoteTheme }.orEmpty()
            },
        )
        val backgroundBitmap = backgroundFile.takeIf { it.isFile }
            ?.let { file -> BitmapFactory.decodeFile(file.absolutePath) }
        if (backgroundBitmap == null) {
            root.setBackgroundColor(background)
        } else {
            val imageOpacity = if (customBackgroundPath.isNotEmpty()) {
                (previewImageOpacityPermille ?: uiStore.customBackgroundImageOpacityPermille) / 1000f
            } else {
                1f
            }
            root.background = ThemeBackgroundDrawable(backgroundBitmap, background, imageOpacity)
        }
        applyRemoteThemeTokens(
            root,
            surface,
            primary,
            onSurface,
            visibleOutline,
            surfaceOpacity,
            gradientColors,
            false,
        )
        window.statusBarColor = background
        window.navigationBarColor = background
    }

    /**
     * Applies temporary custom-background opacity values to the currently displayed real page.
     * Values are not persisted; callers own restoring or saving them when their editor closes.
     */
    protected fun previewCustomBackgroundOpacities(
        imageOpacityPermille: Int,
        surfaceOpacityPermille: Int,
    ) {
        design?.root?.let { root ->
            applyRemoteThemeTokens(root, imageOpacityPermille, surfaceOpacityPermille)
        }
    }

    /** Reapplies persisted theme tokens after data binding mutates themed view properties. */
    protected fun reapplyCurrentThemeTokens() {
        design?.root?.let(::applyRemoteThemeTokens)
    }

    /** Applies semantic colors recursively while preserving primary-surface contrast. */
    private fun applyRemoteThemeTokens(
        view: View,
        surface: Int,
        primary: Int,
        onSurface: Int,
        outline: Int,
        surfaceOpacity: Float,
        gradientColors: IntArray,
        insidePrimarySurface: Boolean,
    ) {
        val isPrimarySurface = insidePrimarySurface || view.tag == "theme_primary_surface"
        val themedSurfaceColor = withOpacity(surface, surfaceOpacity)
        if (view is MaterialCardView) {
            // Every card uses the exact same surface composition. Primary surfaces are expressed
            // through their content and outline, avoiding visually different opacity at one value.
            view.setCardBackgroundColor(themedSurfaceColor)
            view.cardElevation = 0f
            view.strokeColor = if (isPrimarySurface) primary else outline
            view.strokeWidth = resources.displayMetrics.density.toInt().coerceAtLeast(1)
        }
        if (view is TextView) {
            view.setTextColor(onSurface)
            if (view.tag == "theme_accent_text" && gradientColors.size >= 2) {
                val textWidth = view.paint.measureText(view.text.toString()).coerceAtLeast(1f)
                view.paint.shader = LinearGradient(
                    0f,
                    0f,
                    textWidth,
                    0f,
                    gradientColors,
                    null,
                    Shader.TileMode.CLAMP,
                )
                view.invalidate()
            } else {
                view.paint.shader = null
            }
        }
        if (view is LargeActionLabel) {
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = resources.displayMetrics.density * 16f
                setColor(themedSurfaceColor)
                setStroke(resources.displayMetrics.density.toInt().coerceAtLeast(1), outline)
            }
        }
        if (view.tag == "theme_surface_container") {
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = resources.displayMetrics.density * 18f
                setColor(themedSurfaceColor)
                setStroke(resources.displayMetrics.density.toInt().coerceAtLeast(1), outline)
            }
        }
        if (view is ActivityBarLayout) {
            view.alpha = 1f
            view.setBackgroundColor(themedSurfaceColor)
        }
        if (view is SwitchMaterial) {
            val checkedState = intArrayOf(android.R.attr.state_checked)
            val uncheckedState = intArrayOf(-android.R.attr.state_checked)
            view.thumbTintList = ColorStateList(
                arrayOf(checkedState, uncheckedState),
                intArrayOf(readableForeground(primary), onSurface),
            )
            view.trackTintList = ColorStateList(
                arrayOf(checkedState, uncheckedState),
                intArrayOf(primary, withOpacity(outline, 0.45f)),
            )
        }
        if (view is ImageView || view.id == R.id.icon_view) {
            val iconColor = primary
            view.backgroundTintList = ColorStateList.valueOf(iconColor)
            if (view is ImageView) {
                view.imageTintList = ColorStateList.valueOf(iconColor)
            }
        }
        if (view is MaterialButton && !isPrimarySurface) {
            val checkedState = intArrayOf(android.R.attr.state_checked)
            val uncheckedState = intArrayOf(-android.R.attr.state_checked)
            val belongsToThemeModeGroup = view.parent is MaterialButtonToggleGroup
            view.backgroundTintList = if (belongsToThemeModeGroup) {
                ColorStateList(
                    arrayOf(checkedState, uncheckedState),
                    intArrayOf(withOpacity(primary, 0.28f), themedSurfaceColor),
                )
            } else {
                ColorStateList.valueOf(themedSurfaceColor)
            }
            view.strokeColor = if (belongsToThemeModeGroup) {
                ColorStateList(
                    arrayOf(checkedState, uncheckedState),
                    intArrayOf(primary, outline),
                )
            } else {
                ColorStateList.valueOf(primary)
            }
            view.strokeWidth = resources.displayMetrics.density.toInt().coerceAtLeast(1)
            view.setTextColor(
                if (belongsToThemeModeGroup) {
                    ColorStateList(
                        arrayOf(checkedState, uncheckedState),
                        intArrayOf(primary, onSurface),
                    )
                } else {
                    ColorStateList.valueOf(onSurface)
                },
            )
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                applyRemoteThemeTokens(
                    view.getChildAt(index), surface, primary, onSurface, outline,
                    surfaceOpacity, gradientColors, isPrimarySurface,
                )
            }
        }
    }

    /** Returns black or white according to WCAG relative luminance contrast. */
    private fun readableForeground(background: Int): Int {
        val luminance = 0.2126 * Color.red(background) / 255.0 +
            0.7152 * Color.green(background) / 255.0 +
            0.0722 * Color.blue(background) / 255.0
        return if (luminance > 0.52) Color.BLACK else Color.WHITE
    }

    /**
     * Adapts a single-palette theme base color to the effective application appearance.
     *
     * Dark colors are preserved because they may be intentional theme tokens. Only colors whose
     * luminance is visibly light are remapped in night mode, keeping their hue while lowering the
     * luminance enough for translucent cards and page backgrounds to remain genuinely dark.
     */
    private fun adaptThemeBaseColorForDayNight(color: Int, effectiveDayNight: DayNight): Int {
        if (effectiveDayNight != DayNight.Night || colorLuminance(color) <= 0.35) {
            return color
        }
        return blendColors(color, Color.BLACK, 0.18f)
    }

    /** Returns the relative visual luminance used to classify theme base colors. */
    private fun colorLuminance(color: Int): Double {
        return 0.2126 * Color.red(color) / 255.0 +
            0.7152 * Color.green(color) / 255.0 +
            0.0722 * Color.blue(color) / 255.0
    }

    /** Blends a foreground token over a background token using a bounded opacity. */
    private fun blendColors(foreground: Int, background: Int, opacity: Float): Int {
        val alpha = opacity.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(foreground) * alpha + Color.red(background) * (1f - alpha)).toInt(),
            (Color.green(foreground) * alpha + Color.green(background) * (1f - alpha)).toInt(),
            (Color.blue(foreground) * alpha + Color.blue(background) * (1f - alpha)).toInt(),
        )
    }

    /** Replaces a color's alpha with the bounded opacity requested by the remote theme. */
    private fun withOpacity(color: Int, opacity: Float): Int {
        return Color.argb(
            (opacity.coerceIn(0f, 1f) * 255).toInt(),
            Color.red(color),
            Color.green(color),
            Color.blue(color),
        )
    }

    suspend fun <I, O> startActivityForResult(
        contracts: ActivityResultContract<I, O>,
        input: I,
    ): O = withContext(Dispatchers.Main) {
        val requestKey = nextRequestKey.getAndIncrement().toString()

        ActivityResultLifecycle().use { lifecycle, start ->
            suspendCoroutine { c ->
                activityResultRegistry.register(requestKey, lifecycle, contracts) {
                    c.resume(it)
                }.apply { start() }.launch(input)
            }
        }
    }

    suspend fun setContentDesign(design: D) {
        suspendCoroutine<Unit> {
            window.decorView.post {
                this.design = design
                it.resume(Unit)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyDayNight()

        // Apply excludeFromRecents setting to all app tasks.
        checkNotNull(getSystemService<ActivityManager>()).appTasks.forEach { task ->
            task.setExcludeFromRecents(uiStore.hideFromRecents)
        }

        launch {
            main()
        }
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        Remote.broadcasts.addObserver(this)
        events.trySend(Event.ActivityStart)
    }

    override fun onStop() {
        super.onStop()
        activityStarted = false
        Remote.broadcasts.removeObserver(this)
        events.trySend(Event.ActivityStop)
    }

    override fun onDestroy() {
        design?.cancel()
        cancel()
        super.onDestroy()
    }

    override fun finish() {
        if (deferRunning) return
        deferRunning = true

        launch {
            try {
                defer()
            } finally {
                withContext(NonCancellable) {
                    super.finish()
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (queryDayNight(newConfig) != dayNight) {
            ApplicationObserver.createdActivities.forEach {
                it.recreate()
            }
        }
    }

    open fun shouldDisplayHomeAsUpEnabled(): Boolean {
        return true
    }

    override fun onSupportNavigateUp(): Boolean {
        this.onBackPressed()
        return true
    }

    override fun onProfileChanged() {
        events.trySend(Event.ProfileChanged)
    }

    override fun onProfileUpdateCompleted(uuid: UUID?) {
        events.trySend(Event.ProfileUpdateCompleted)
    }

    override fun onProfileUpdateFailed(uuid: UUID?, reason: String?) {
        events.trySend(Event.ProfileUpdateFailed)
    }

    override fun onProfileLoaded() {
        events.trySend(Event.ProfileLoaded)
    }

    override fun onServiceRecreated() {
        events.trySend(Event.ServiceRecreated)
    }

    override fun onStarted() {
        events.trySend(Event.ClashStart)
    }

    override fun onStopped(cause: String?) {
        events.trySend(Event.ClashStop)

        if (cause != null && activityStarted) {
            launch {
                design?.showExceptionToast(ClashException(cause))
            }
        }
    }

    private fun queryDayNight(config: Configuration = resources.configuration): DayNight {
        return when (uiStore.darkMode) {
            DarkMode.Auto -> if (config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) DayNight.Night else DayNight.Day
            DarkMode.ForceLight -> DayNight.Day
            DarkMode.ForceDark -> DayNight.Night
        }
    }

    private fun applyDayNight(config: Configuration = resources.configuration) {
        val dayNight = queryDayNight(config)
        when (dayNight) {
            DayNight.Night -> theme.applyStyle(R.style.AppThemeDark, true)
            DayNight.Day -> theme.applyStyle(R.style.AppThemeLight, true)
        }
        theme.applyStyle(resolveThemePaletteStyle(dayNight), true)

        window.isAllowForceDarkCompat = false
        window.isSystemBarsTranslucentCompat = true
        
        window.statusBarColor = resolveThemedColor(android.R.attr.statusBarColor)
        window.navigationBarColor = resolveThemedColor(android.R.attr.navigationBarColor)

        if (Build.VERSION.SDK_INT >= 23) {
            window.isLightStatusBarsCompat = resolveThemedBoolean(android.R.attr.windowLightStatusBar)
        }

        if (Build.VERSION.SDK_INT >= 27) {
            window.isLightNavigationBarCompat = resolveThemedBoolean(android.R.attr.windowLightNavigationBar)
        }

        this.dayNight = dayNight
    }

    /**
     * Resolves the persisted palette to a day/night-specific native theme overlay.
     *
     * @param dayNight effective appearance mode after applying the system preference.
     * @return style resource covering activities and application-owned dialog theme attributes.
     */
    private fun resolveThemePaletteStyle(dayNight: DayNight): Int {
        return when (uiStore.themePalette) {
            ThemePalette.Blue -> if (dayNight == DayNight.Night) {
                R.style.ThemeOverlay_Palette_Blue_Dark
            } else {
                R.style.ThemeOverlay_Palette_Blue_Light
            }
            ThemePalette.Indigo -> if (dayNight == DayNight.Night) {
                R.style.ThemeOverlay_Palette_Indigo_Dark
            } else {
                R.style.ThemeOverlay_Palette_Indigo_Light
            }
            ThemePalette.Purple -> if (dayNight == DayNight.Night) {
                R.style.ThemeOverlay_Palette_Purple_Dark
            } else {
                R.style.ThemeOverlay_Palette_Purple_Light
            }
            ThemePalette.Teal -> if (dayNight == DayNight.Night) {
                R.style.ThemeOverlay_Palette_Teal_Dark
            } else {
                R.style.ThemeOverlay_Palette_Teal_Light
            }
            ThemePalette.OrangeRed -> if (dayNight == DayNight.Night) {
                R.style.ThemeOverlay_Palette_OrangeRed_Dark
            } else {
                R.style.ThemeOverlay_Palette_OrangeRed_Light
            }
        }
    }

    enum class Event {
        ServiceRecreated,
        ActivityStart,
        ActivityStop,
        ClashStop,
        ClashStart,
        ProfileLoaded,
        ProfileChanged,
        ProfileUpdateCompleted,
        ProfileUpdateFailed,
    }
}
