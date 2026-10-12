/*
 * Yeshua (2026) — formerly LunarTune
 * © cognitiveshadows03 — github.com/cognitiveshadows03
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * YtMusicColorScheme.kt
 *
 * Enhanced with built-in theme presets, hand-tuned dark/light palettes, 
 * and runtime custom hex color generation.
 */

package dev.citali.lunartune.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// ────────────────────────────────────────────────────────────────────────────
// Preset definitions & Dynamic Custom Theme Support
// ────────────────────────────────────────────────────────────────────────────

data class ThemePreset(
    val id: String,
    val label: String,
    val seedColor: Color,
    val pureBlack: Boolean = false,
) {
    companion object {
        val YouTubeMusic = ThemePreset(
            id = "youtube_music",
            label = "YouTube Music",
            seedColor = Color(0xFFFF0033),
            pureBlack = true,
        )
        val LunarDefault = ThemePreset(
            id = "lunar_default",
            label = "Lunar (Default)",
            seedColor = DefaultThemeColor,
        )
        val Midnight = ThemePreset(
            id = "midnight",
            label = "Midnight Blue",
            seedColor = Color(0xFF1A73E8),
            pureBlack = true,
        )
        val Ocean = ThemePreset(
            id = "ocean",
            label = "Ocean Teal",
            seedColor = Color(0xFF00897B),
        )
        val Sakura = ThemePreset(
            id = "sakura",
            label = "Sakura Pink",
            seedColor = Color(0xFFE91E8C),
        )
        val Ember = ThemePreset(
            id = "ember",
            label = "Ember Orange",
            seedColor = Color(0xFFFF6D00),
        )

        val all: List<ThemePreset> = listOf(
            YouTubeMusic,
            LunarDefault,
            Midnight,
            Ocean,
            Sakura,
            Ember,
        )

        /**
         * Resolves presets safely, supporting custom runtime hex overrides.
         */
        fun resolve(id: String, customHex: String? = null, customColor: Color? = null): ThemePreset {
            if (id.startsWith("custom_") && customHex != null && customColor != null) {
                return ThemePreset(
                    id = id,
                    label = "Custom ($customHex)",
                    seedColor = customColor,
                    pureBlack = true,
                )
            }
            return all.firstOrNull { it.id == id } ?: LunarDefault
        }
    }
}

/**
 * Helper to construct a dynamic theme preset on the fly from custom hex inputs.
 */
fun createCustomThemePreset(customHex: String, seedColor: Color): ThemePreset {
    return ThemePreset(
        id = "custom_$customHex",
        label = "Custom ($customHex)",
        seedColor = seedColor,
        pureBlack = true
    )
}

// ────────────────────────────────────────────────────────────────────────────
// YouTube Music hand-tuned dark scheme
// Mirrors the actual YTM Android app palette.
// ────────────────────────────────────────────────────────────────────────────

val YtMusicDarkColorScheme: ColorScheme = darkColorScheme(
    primary            = Color(0xFFFF4558),
    onPrimary          = Color(0xFFFFFFFF),
    primaryContainer   = Color(0xFF5C0011),
    onPrimaryContainer = Color(0xFFFFDAD9),

    secondary          = Color(0xFFE4BABB),
    onSecondary        = Color(0xFF44191B),
    secondaryContainer = Color(0xFF5D2F30),
    onSecondaryContainer = Color(0xFFFFD7D7),

    tertiary           = Color(0xFFE5C18B),
    onTertiary         = Color(0xFF3E2D04),
    tertiaryContainer  = Color(0xFF574319),
    onTertiaryContainer = Color(0xFFFFDEA5),

    background         = Color(0xFF030303),   // YTM signature near-black
    onBackground       = Color(0xFFF0F0F0),

    surface            = Color(0xFF0F0F0F),
    onSurface          = Color(0xFFF0F0F0),
    surfaceVariant     = Color(0xFF1A1A1A),
    onSurfaceVariant   = Color(0xFFB0B0B0),

    surfaceContainerLowest  = Color(0xFF030303),
    surfaceContainerLow     = Color(0xFF0F0F0F),
    surfaceContainer        = Color(0xFF181818),
    surfaceContainerHigh    = Color(0xFF212121),
    surfaceContainerHighest = Color(0xFF2C2C2C),

    outline         = Color(0xFF3C3C3C),
    outlineVariant  = Color(0xFF2A2A2A),
    error           = Color(0xFFFFB4AB),
    onError         = Color(0xFF690005),
    errorContainer  = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

val YtMusicLightColorScheme: ColorScheme = lightColorScheme(
    primary            = Color(0xFFB81020),
    onPrimary          = Color(0xFFFFFFFF),
    primaryContainer   = Color(0xFFFFDAD9),
    onPrimaryContainer = Color(0xFF410006),

    secondary          = Color(0xFF775657),
    onSecondary        = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFD7D7),
    onSecondaryContainer = Color(0xFF2D1516),

    background         = Color(0xFFFFFBFF),
    onBackground       = Color(0xFF201A1A),

    surface            = Color(0xFFFFFBFF),
    onSurface          = Color(0xFF201A1A),
    surfaceVariant     = Color(0xFFF5DDDD),
    onSurfaceVariant   = Color(0xFF534343),
)
