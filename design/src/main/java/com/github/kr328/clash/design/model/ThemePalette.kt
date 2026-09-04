package com.github.kr328.clash.design.model

/**
 * Native application color palettes corresponding to the desktop theme selection concept.
 *
 * Desktop CSS theme files cannot be safely applied to Android views. Each value therefore maps
 * to a reviewed Android theme overlay while remaining independent from [DarkMode].
 */
enum class ThemePalette {
    /** Familiar high-contrast default blue. */
    Blue,
    /** Deeper indigo used by the current redesigned interface. */
    Indigo,
    /** Purple accent for a more expressive appearance. */
    Purple,
    /** Teal accent balancing clarity and lower visual intensity. */
    Teal,
    /** Warm orange-red accent with strong action emphasis. */
    OrangeRed,
}
