package com.github.kr328.clash.design.component

import android.content.Context
import android.graphics.Color
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.util.getPixels
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.resolveThemedResourceId

class ProxyViewConfig(val context: Context, var proxyLine: Int) {
    /** Persisted UI theme used because proxy cards are rendered directly onto a Canvas. */
    private val uiStore = UiStore(context)

    /** Whether proxy canvas colors must use remote/custom-background semantic tokens. */
    private val useCustomTheme = uiStore.remoteThemeKey.isNotEmpty() ||
        uiStore.customBackgroundImagePath.isNotEmpty()

    /** Surface color including the same opacity used by native themed cards. */
    private val colorSurface = if (useCustomTheme) {
        val configuredSurface = uiStore.remoteThemeSurfaceColor.takeUnless { it == 0 }
            ?: context.resolveThemedColor(com.google.android.material.R.attr.colorSurface)
        val opacityPermille = if (uiStore.customBackgroundImagePath.isNotEmpty()) {
            uiStore.customBackgroundSurfaceOpacityPermille
        } else {
            uiStore.remoteThemeSurfaceOpacityPermille
        }
        Color.argb(
            (opacityPermille.coerceIn(0, 1000) * 255 / 1000),
            Color.red(configuredSurface),
            Color.green(configuredSurface),
            Color.blue(configuredSurface),
        )
    } else {
        context.resolveThemedColor(com.google.android.material.R.attr.colorSurface)
    }

    val clickableBackground =
        context.resolveThemedResourceId(android.R.attr.selectableItemBackground)

    val selectedBackground = uiStore.remoteThemePrimaryColor
        .takeIf { useCustomTheme && it != 0 }
        ?: context.resolveThemedColor(com.google.android.material.R.attr.colorPrimary)
    val selectedControl = if (useCustomTheme) {
        readableProxyCardForeground(selectedBackground)
    } else {
        context.resolveThemedColor(com.google.android.material.R.attr.colorOnPrimary)
    }

    val unselectedControl = uiStore.remoteThemeOnSurfaceColor
        .takeIf { useCustomTheme && it != 0 }
        ?: context.resolveThemedColor(com.google.android.material.R.attr.colorOnSurface)
    val unselectedBackground: Int
        get() = if (proxyLine==1) Color.TRANSPARENT else colorSurface

    val layoutPadding = context.getPixels(R.dimen.proxy_layout_padding).toFloat()
    val contentPadding
        get() = if (proxyLine==2) context.getPixels(R.dimen.proxy_content_padding).toFloat() else context.getPixels(R.dimen.proxy_content_padding_grid3).toFloat()
    val textMargin
        get() = if (proxyLine==2) context.getPixels(R.dimen.proxy_text_margin).toFloat() else context.getPixels(R.dimen.proxy_text_margin_grid3).toFloat()
    val textSize
        get() = if (proxyLine==2) context.getPixels(R.dimen.proxy_text_size).toFloat() else context.getPixels(R.dimen.proxy_text_size_grid3).toFloat()

    val shadow = Color.argb(
        0x15,
        Color.red(Color.DKGRAY),
        Color.green(Color.DKGRAY),
        Color.blue(Color.DKGRAY),
    )

    val cardRadius = context.getPixels(R.dimen.proxy_card_radius).toFloat()
    var cardOffset = context.getPixels(R.dimen.proxy_card_offset).toFloat()

    /** Returns a high-contrast proxy label color for a remotely supplied selected-card color. */
    private fun readableProxyCardForeground(background: Int): Int {
        val luminance = 0.2126 * Color.red(background) / 255.0 +
            0.7152 * Color.green(background) / 255.0 +
            0.0722 * Color.blue(background) / 255.0
        return if (luminance > 0.52) Color.BLACK else Color.WHITE
    }
}
