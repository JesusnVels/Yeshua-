/*
 * LunarTune (2026)
 * © cognitiveshadows03 — github.com/cognitiveshadows03
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Momentum queue auto-injection adapted from Echo (GPL-3.0).
 */

package dev.yeshua.player.playback

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.media3.common.MediaItem
import dev.yeshua.player.constants.EchoBrainEnabledKey
import dev.yeshua.player.db.MusicDatabase
import dev.yeshua.player.db.entities.Song
import dev.yeshua.player.extensions.toMediaItem
import dev.yeshua.player.models.toMediaMetadata
import dev.yeshua.player.utils.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Echo Brain v1 — momentum queue auto-injection.
 *
 * When the playback queue runs low ([LOW_QUEUE_THRESHOLD] items or fewer
 * remaining), the brain appends songs that match recent listening momentum so
 * playback keeps flowing without a gap.
 *
 * Scoring (fully on-device, no new dependencies):
 *  (a) Candidate pool comes from the app's existing related-songs cache
 *      (the same data HomeRepository's `validRecommendationsFor` pipeline
 *      feeds; cached per played song in the `related_song_map` table).
 *      Seeds are the most recently played songs, with a recency-decayed
 *      weight; candidates nearer the top of a seed's related list score higher.
 *  (b) Weighted by recent play stats: candidates sharing artists with the
 *      recent listening window get a momentum bonus.
 *
 * Exclusions: the current song, anything already in the queue, anything played
 * in the recent window, and local files (related data is YouTube-only).
 *
 * Guarded by [EchoBrainEnabledKey] (default ON). The caller arms/re-arms
 * injection via the thresholds so one low-queue episode injects once.
 */
class EchoBrain(
    private val database: MusicDatabase,
    private val dataStore: DataStore<Preferences>,
) {
    suspend fun collectMomentumInjections(
        queueMediaIds: Set<String>,
        currentMediaId: String?,
        maxInjections: Int = MAX_INJECTIONS,
    ): List<MediaItem> =
        withContext(Dispatchers.IO) {
            if (!dataStore.get(EchoBrainEnabledKey, true)) return@withContext emptyList()

            // (b) Recent listening momentum: most recent plays become seeds.
            val recentEvents = database.events(limit = RECENT_EVENT_SEED_LIMIT, offset = 0)
            if (recentEvents.isEmpty()) return@withContext emptyList()
            val seeds = recentEvents.map { it.event.songId }.distinct().take(MAX_SEEDS)
            val recentIds = recentEvents.map { it.event.songId }.toSet()
            val momentumArtists =
                recentEvents
                    .mapNotNull { it.song }
                    .flatMap { song -> song.artists.map { artist -> artist.name } }
                    .toSet()

            // (a) Candidate pool from the on-device related-songs cache.
            val scored = mutableMapOf<String, ScoredCandidate>()
            seeds.forEachIndexed { seedIndex, seedId ->
                val seedWeight = 1f / (1 + seedIndex)
                val related =
                    runCatching { database.getRelatedSongs(seedId).first() }
                        .getOrDefault(emptyList())
                related.forEachIndexed { relatedIndex, song ->
                    val id = song.id
                    if (id == currentMediaId || id in queueMediaIds || id in recentIds) return@forEachIndexed
                    if (song.song.isLocal) return@forEachIndexed
                    var score = seedWeight * (1f / (1 + relatedIndex))
                    val sharedArtists = song.artists.count { artist -> artist.name in momentumArtists }
                    score += MOMENTUM_ARTIST_BONUS * seedWeight * sharedArtists
                    val existing = scored[id]
                    if (existing == null || score > existing.score) {
                        scored[id] = ScoredCandidate(song, score)
                    }
                }
            }

            scored.values
                .sortedByDescending { it.score }
                .take(maxInjections)
                .map { it.song.toMediaMetadata().toMediaItem() }
                .also { injected ->
                    Timber.tag(TAG).d("Echo Brain collected %d momentum injections", injected.size)
                }
        }

    private data class ScoredCandidate(
        val song: Song,
        val score: Float,
    )

    companion object {
        private const val TAG = "EchoBrain"

        /** Inject when this many items (or fewer) remain in the queue. */
        const val LOW_QUEUE_THRESHOLD = 2

        /** Re-arm injection once the queue grows past this many remaining items. */
        const val REARM_THRESHOLD = 6

        private const val RECENT_EVENT_SEED_LIMIT = 20
        private const val MAX_SEEDS = 5
        private const val MAX_INJECTIONS = 8
        private const val MOMENTUM_ARTIST_BONUS = 0.35f
    }
}
