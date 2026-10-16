/*
 * LunarTune (2026)
 * © cognitiveshadows03 — github.com/cognitiveshadows03
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package dev.yeshua.player.playlistimport

import dev.yeshua.player.db.entities.PlaylistSongMap

internal fun PlaylistSongMap.replaceSongSource(replacementSongId: String): PlaylistSongMap {
    require(replacementSongId.isNotBlank()) { "Replacement song ID must not be blank" }
    return copy(
        songId = replacementSongId,
        setVideoId = null,
    )
}
