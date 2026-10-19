/*
 * LunarTune (2026)
 * © cognitiveshadows03 — github.com/cognitiveshadows03
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Per-playlist sync behavior adapted from Stash (GPL-3.0).
 */

package dev.yeshua.player.db.entities

/**
 * How a synced playlist reconciles its local contents with the remote source.
 */
enum class PlaylistSyncMode {
    /** Mirror the source: remove tracks missing remotely, add new ones. */
    REFRESH,

    /** Add new remote tracks, never remove local ones. */
    ACCUMULATE,
}
