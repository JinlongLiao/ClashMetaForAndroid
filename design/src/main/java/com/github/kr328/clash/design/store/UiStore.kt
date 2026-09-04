package com.github.kr328.clash.design.store

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.github.kr328.clash.common.store.Store
import com.github.kr328.clash.common.store.asStoreProvider
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.design.model.AppInfoSort
import com.github.kr328.clash.design.model.DarkMode
import com.github.kr328.clash.design.model.ThemePalette

class UiStore(context: Context) {
    private val store = Store(
        context
            .getSharedPreferences(PREFERENCE_NAME, Context.MODE_PRIVATE)
            .asStoreProvider()
    )

    var enableVpn: Boolean by store.boolean(
        key = "enable_vpn",
        defaultValue = true
    )

    var darkMode: DarkMode by store.enum(
        key = "dark_mode",
        defaultValue = DarkMode.Auto,
        values = DarkMode.values()
    )

    /** Native color palette applied independently from the light/dark appearance mode. */
    var themePalette: ThemePalette by store.enum(
        key = "theme_palette",
        defaultValue = ThemePalette.Blue,
        values = ThemePalette.values(),
    )

    /** File key of the selected desktop theme-hub entry, or empty for a built-in palette. */
    var remoteThemeKey: String by store.string(
        key = "remote_theme_key",
        defaultValue = "",
    )

    /** Selected remote theme primary ARGB color, or zero when no concrete token was provided. */
    var remoteThemePrimaryColor: Int by store.int("remote_theme_primary_color", 0)

    /** Selected remote theme secondary ARGB color, or zero when unavailable. */
    var remoteThemeSecondaryColor: Int by store.int("remote_theme_secondary_color", 0)

    /** Selected remote theme page background ARGB color, or zero when unavailable. */
    var remoteThemeBackgroundColor: Int by store.int("remote_theme_background_color", 0)

    /** Selected remote theme surface ARGB color, or zero when unavailable. */
    var remoteThemeSurfaceColor: Int by store.int("remote_theme_surface_color", 0)

    /** Selected remote theme surface-content ARGB color, or zero when unavailable. */
    var remoteThemeOnSurfaceColor: Int by store.int("remote_theme_on_surface_color", 0)

    /** Selected remote theme outline ARGB color, or zero when unavailable. */
    var remoteThemeOutlineColor: Int by store.int("remote_theme_outline_color", 0)

    /** Cached local file containing the selected theme's remote background image. */
    var remoteThemeBackgroundImagePath: String by store.string("remote_theme_background_image_path", "")

    /** Comma-separated ARGB colors used for branding gradients, or empty when unavailable. */
    var remoteThemeAccentGradient: String by store.string("remote_theme_accent_gradient", "")

    /** Surface opacity multiplied by 1000, allowing persistence without a floating-point delegate. */
    var remoteThemeSurfaceOpacityPermille: Int by store.int("remote_theme_surface_opacity_permille", 1000)

    /** Application-private copy of the background image selected by the user. */
    var customBackgroundImagePath: String by store.string("custom_background_image_path", "")

    /** Opacity of cards drawn above a user-selected background, multiplied by 1000. */
    var customBackgroundSurfaceOpacityPermille: Int by store.int(
        "custom_background_surface_opacity_permille",
        820,
    )

    /** Opacity of the user-selected background image, multiplied by 1000. */
    var customBackgroundImageOpacityPermille: Int by store.int(
        "custom_background_image_opacity_permille",
        1000,
    )

    /** BCP 47 language tag selected in the app; an empty value follows the system locale. */
    var applicationLanguageTag: String by store.string(
        key = "application_language_tag",
        defaultValue = ""
    )

    /** Optional newline-separated latency targets in `name|https://url` form. */
    var networkDetectionTargets: String by store.string(
        key = "network_detection_targets",
        defaultValue = ""
    )

    var hideAppIcon: Boolean by store.boolean(
        key = "hide_app_icon",
        defaultValue = context.packageManager.getComponentEnabledSetting(context.mainActivityAlias)
            .let { state ->
                state != PackageManager.COMPONENT_ENABLED_STATE_ENABLED &&
                        state != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            },
    )

    var hideFromRecents: Boolean by store.boolean(
        key = "hide_from_recents",
        defaultValue = false,
    )

    var proxyExcludeNotSelectable by store.boolean(
        key = "proxy_exclude_not_selectable",
        defaultValue = false,
    )

    var proxyLine: Int by store.int(
        key = "proxy_line",
        defaultValue = 2
    )

    var proxySort: ProxySort by store.enum(
        key = "proxy_sort",
        defaultValue = ProxySort.Default,
        values = ProxySort.values()
    )

    var proxyLastGroup: String by store.string(
        key = "proxy_last_group",
        defaultValue = ""
    )

    var accessControlSort: AppInfoSort by store.enum(
        key = "access_control_sort",
        defaultValue = AppInfoSort.Label,
        values = AppInfoSort.values(),
    )

    var accessControlReverse: Boolean by store.boolean(
        key = "access_control_reverse",
        defaultValue = false
    )

    var accessControlSystemApp: Boolean by store.boolean(
        key = "access_control_system_app",
        defaultValue = false,
    )

    companion object {
        private const val PREFERENCE_NAME = "ui"

        val Context.mainActivityAlias: ComponentName
            get() = ComponentName(this, "com.github.kr328.clash.MainActivityAlias")
    }
}
