/*
 * LunarTune (2026)
 * © cognitiveshadows03 — github.com/cognitiveshadows03
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package dev.yeshua.player.utils

import dev.yeshua.player.constants.AudioQuality

data class AuthScopedCacheValue(
    val url: String,
    val expiresAtMs: Long,
    val authFingerprint: String,
    /**
     * The quality the URL was resolved at. Playback reuse must compare this
     * against the quality effective for the new load: a URL resolved at LOW
     * must never satisfy a HIGH request after the user switches quality (and
     * vice versa). Null marks an entry written before quality tagging
     * existed — playback treats it as a mismatch and re-resolves.
     */
    val audioQuality: AudioQuality? = null,
    /**
     * Cache-key prefix of the source that produced the URL
     * (e.g. [QobuzStreamResolver.CACHE_KEY_PREFIX]), or null when the URL
     * came from regular YouTube Music resolution. Playback URLs cached by
     * MusicService are always YouTube resolutions, so reuse expects null.
     */
    val sourceKey: String? = null,
) {
    fun isValidFor(
        authFingerprint: String,
        nowMs: Long = System.currentTimeMillis(),
        minimumRemainingMs: Long = 0L,
    ): Boolean = this.authFingerprint == authFingerprint && expiresAtMs > nowMs + minimumRemainingMs

    /** True when this entry was resolved for exactly the given playback context. */
    fun matchesContext(
        audioQuality: AudioQuality,
        sourceKey: String?,
    ): Boolean = this.audioQuality == audioQuality && this.sourceKey == sourceKey
}
