package com.github.kr328.clash.design.model

/**
 * Safe Android projection of one desktop theme-hub CSS file.
 *
 * Android never executes the CSS. Only explicitly supported semantic color declarations are
 * retained, while [key] remains the stable cache and preference identifier.
 *
 * @property key cached CSS file name.
 * @property label display name read from the first CSS comment.
 * @property primaryColor primary action color, encoded as ARGB, when declared by the theme.
 * @property secondaryColor secondary accent color, encoded as ARGB, when declared by the theme.
 * @property backgroundColor page background color, encoded as ARGB, when declared by the theme.
 * @property surfaceColor card or panel color, encoded as ARGB, when declared by the theme.
 * @property onSurfaceColor content color drawn on surfaces, encoded as ARGB, when declared.
 * @property outlineColor divider or outline color, encoded as ARGB, when declared by the theme.
 * @property backgroundImageUrl HTTPS background image referenced by the theme, when present.
 * @property accentGradientColors concrete colors from the theme's branding gradient.
 * @property surfaceOpacity card opacity in the range 0..1, when declared by the theme.
 */
data class RemoteTheme(
    val key: String,
    val label: String,
    val primaryColor: Int?,
    val secondaryColor: Int?,
    val backgroundColor: Int?,
    val surfaceColor: Int?,
    val onSurfaceColor: Int?,
    val outlineColor: Int?,
    val backgroundImageUrl: String?,
    val accentGradientColors: List<Int>,
    val surfaceOpacity: Float?,
)
