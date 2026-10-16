/*
 * LunarTune (2026)
 * © cognitiveshadows03 — github.com/cognitiveshadows03
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package dev.yeshua.player.widget

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import dev.yeshua.player.db.MusicDatabase
import dev.yeshua.player.db.entities.Artist
import dev.yeshua.player.db.entities.LibraryTopMixEntity
import dev.yeshua.player.db.entities.ListeningTotals
import dev.yeshua.player.db.entities.Song
import dev.yeshua.player.db.entities.SongWithStats
import java.time.Duration
import javax.inject.Inject

internal class WidgetInsightsRepository
    @Inject
    constructor(
        private val database: MusicDatabase,
    ) {
        suspend fun load(nowMs: Long): WidgetInsightsData =
            withContext(Dispatchers.IO) {
                val fromMs = nowMs - Duration.ofDays(30).toMillis()
                WidgetInsightsData(
                    recentSongs = database.recentSongs(limit = 4).first(),
                    totals = database.listeningTotals(fromTimestamp = fromMs, toTimestamp = nowMs).first(),
                    topSongs = database.mostPlayedSongsStats(fromTimeStamp = fromMs, limit = 4, toTimeStamp = nowMs).first(),
                    recommendations = database.quickPicks(now = nowMs).first().take(6),
                    topArtists = database.mostPlayedArtists(fromTimeStamp = fromMs, limit = 6, toTimeStamp = nowMs).first(),
                    topMixes = database.libraryTopMixes(limit = 4).first(),
                )
            }
    }

internal data class WidgetInsightsData(
    val recentSongs: List<Song>,
    val totals: ListeningTotals,
    val topSongs: List<SongWithStats>,
    val recommendations: List<Song>,
    val topArtists: List<Artist>,
    val topMixes: List<LibraryTopMixEntity>,
)
