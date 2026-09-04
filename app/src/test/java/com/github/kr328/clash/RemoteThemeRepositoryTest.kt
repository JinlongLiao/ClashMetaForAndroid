package com.github.kr328.clash

import com.github.kr328.clash.design.model.ThemePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Verifies the safe desktop-CSS projection used by the Android theme center. */
class RemoteThemeRepositoryTest {
    /** Parser-only repository; no Android context or filesystem access is used by these tests. */
    private val repository = RemoteThemeRepository()

    /** Gemini resolves chained CSS variables and retains its first-comment display name. */
    @Test
    fun parseGeminiThemeSemanticTokens() {
        val theme = repository.parseRemoteTheme(
            "gemini.css",
            """/* Gemini */
                :root {
                  --gemini-color-gemini-cyan-hsl: 209 100% 65%;
                  --heroui-primary: var(--gemini-color-gemini-cyan-hsl) !important;
                  --heroui-secondary: 270 66.67% 47.06% !important;
                  --heroui-background: 0 0% 100% !important;
                  --heroui-content1: #f0f3f8;
                  --heroui-content1-foreground: #11191c;
                  --heroui-foreground: #202124;
                  --heroui-divider: #d2d8e4;
                }
            """.trimIndent(),
        )

        assertEquals("Gemini", theme.label)
        assertNotNull(theme.primaryColor)
        assertNotNull(theme.secondaryColor)
        assertNotNull(theme.backgroundColor)
        assertNotNull(theme.surfaceColor)
        assertEquals(0xFF11191C.toInt(), theme.onSurfaceColor)
        assertNotNull(theme.outlineColor)
        assertEquals(ThemePalette.Indigo, repository.resolveNativePalette(theme))
    }

    /** Summer remains distinct from Gemini while using the official HSL-style declarations. */
    @Test
    fun parseSummerThemeSemanticTokens() {
        val gemini = repository.parseRemoteTheme(
            "gemini.css",
            "/* Gemini */ :root { --heroui-primary: 209 100% 65%; --heroui-secondary: 270 66% 47%; }",
        )
        val summer = repository.parseRemoteTheme(
            "summer.css",
            "/* 夏日晴空（by Strivy） */ .light { --heroui-primary: 210 80% 55% !important; --heroui-secondary: 100 55% 42% !important; }",
        )

        assertEquals("夏日晴空（by Strivy）", summer.label)
        assertEquals(ThemePalette.Blue, repository.resolveNativePalette(summer))
        assertNotEquals(repository.resolveNativePalette(gemini), repository.resolveNativePalette(summer))
    }

    /** Light and dark declarations from one CSS file must never overwrite each other. */
    @Test
    fun parseOnlyActiveAppearanceBranch() {
        val css = """
            /* Appearance */
            .light, [data-theme="light"] { --heroui-foreground: 210 50% 10%; }
            .dark, [data-theme="dark"] { --heroui-foreground: 210 20% 95%; }
        """.trimIndent()

        val light = repository.parseRemoteTheme("appearance.css", css, darkAppearance = false)
        val dark = repository.parseRemoteTheme("appearance.css", css, darkAppearance = true)

        assertNotEquals(light.onSurfaceColor, dark.onSurfaceColor)
        assertEquals(0xFF0C1926.toInt(), light.onSurfaceColor)
    }

    /** Archive names must be flat CSS files so traversal and non-theme payloads are rejected. */
    @Test
    fun rejectUnsafeArchiveEntryNames() {
        assertEquals("safe.css", repository.validateEntryName("safe.css"))
        listOf("../escape.css", "folder/theme.css", "theme.js", "theme.css/extra").forEach { name ->
            runCatching { repository.validateEntryName(name) }
                .onSuccess { throw AssertionError("Expected unsafe entry rejection for $name") }
        }
    }
}
