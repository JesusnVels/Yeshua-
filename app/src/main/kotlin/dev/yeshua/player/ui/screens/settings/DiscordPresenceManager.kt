/*
 * Yeshua (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package dev.yeshua.player.ui.screens.settings

import android.content.Context
import dev.yeshua.player.db.entities.Song

/**
 * Discord integration was removed from Yeshua (the Discord application is not
 * accessible to the maintainer). This is a no-op stub that preserves the API
 * surface used by [dev.yeshua.player.playback.MusicService] so the presence
 * pipeline compiles while performing no Discord activity.
 */
object DiscordPresenceManager {
    suspend fun clearNow(
        context: Context,
token: <redacted>
    ): Boolean = false

    fun start(
        context: Context,
token: <redacted>
    ) {
        // No-op: Discord support removed.
    }

    suspend fun updateNow(
        context: Context,
token: <redacted>
        song: Song?,
        positionMs: Long,
        isPaused: Boolean,
        isMusicVideo: Boolean = false,
    ): Boolean = false

    fun setOnTransportInvalidated(listener: ((String) -> Unit)?) {
        // No-op: Discord support removed.
    }

    fun stop(clearActivity: Boolean = true) {
        // No-op: Discord support removed.
    }

    fun isRunning(): Boolean = false
}
