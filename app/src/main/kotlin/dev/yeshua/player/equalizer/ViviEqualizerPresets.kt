/*
 * LunarTune (2026)
 * © cognitiveshadows03 — github.com/cognitiveshadows03
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package dev.yeshua.player.equalizer

/**
 * 10-band millibel curves tuned for Yeshua.
 * Centers: 31, 62, 125, 250, 500, 1k, 2k, 4k, 8k, 16k Hz.
 */
object ViviEqualizerPresets {
    val chips: List<Pair<String, String>> =
        listOf(
            "aether_wide"    to "Aether Wide",
            "velvet_warm"    to "Velvet Warm",
            "spotlight"      to "Spotlight",
            "clarity"        to "Clarity",
            "cinematic"      to "Cinematic",
            "arena"          to "Arena",
            "midnight_bass"  to "Midnight Bass",
            "acoustic_space" to "Acoustic Space",
        )

    fun levelsMb(id: String): List<Int>? =
        when (id) {
            // Wide, airy stage — lifted extremes, open top end.
            "aether_wide"    -> listOf(350, 280, 180, 80, 40, 120, 220, 360, 450, 380)
            // Warm body — bass + low-mids, soft air.
            "velvet_warm"    -> listOf(480, 400, 280, 180, 220, 160, 80, 140, 220, 120)
            // Vocal pocket — cut rumble/air, boost presence.
            "spotlight"      -> listOf(-450, -180, 80, 260, 380, 280, 220, 80, -120, -380)
            // Gentle bass, rising detail toward treble — great for all-day listening.
            "clarity"        -> listOf(280, 180, 80, 0, 40, 120, 220, 340, 460, 520)
            // LFE shelf + dialogue + surround highs — suits soundtracks and scores.
            "cinematic"      -> listOf(520, 400, 220, 20, 140, 200, 280, 400, 520, 480)
            // Punch + footsteps/directional cues in upper mids and highs.
            "arena"          -> listOf(320, 420, 260, 20, 80, 180, 380, 560, 500, 340)
            // Sub + deep bass heavy, silky highs rolled off — late night headphone listening.
            "midnight_bass"  -> listOf(600, 520, 380, 160, -80, -120, -60, 80, 180, 120)
            // Flat lows, natural mids, air in the highs — acoustic and live recordings.
            "acoustic_space" -> listOf(-100, 0, 80, 160, 220, 200, 180, 280, 380, 440)
            else -> null
        }
}
