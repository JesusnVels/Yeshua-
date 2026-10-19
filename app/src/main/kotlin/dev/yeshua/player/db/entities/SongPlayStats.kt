/*
 * LunarTune (2026)
 * © cognitiveshadows03 — github.com/cognitiveshadows03
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package dev.yeshua.player.db.entities

import androidx.compose.runtime.Immutable

/** Play count for a single song inside a time window (projection, not a table). */
@Immutable
data class SongPlayCount(
    val songId: String,
    val playCount: Int,
)

/** Most recent play timestamp (epoch millis) for a single song (projection, not a table). */
@Immutable
data class SongLastPlayed(
    val songId: String,
    val lastPlayed: Long,
)
