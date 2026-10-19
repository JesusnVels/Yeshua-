/*
 * LunarTune (2026)
 * © cognitiveshadows03 — github.com/cognitiveshadows03
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package dev.yeshua.player.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import dev.yeshua.player.db.MusicDatabase
import dev.yeshua.player.db.entities.Album
import dev.yeshua.player.db.entities.Artist
import dev.yeshua.player.db.entities.LocalItem
import dev.yeshua.player.db.entities.Playlist
import dev.yeshua.player.db.entities.Song
import dev.yeshua.player.utils.FuzzyMatcher
import javax.inject.Inject
import kotlin.math.ln

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LocalSearchViewModel
    @Inject
    constructor(
        database: MusicDatabase,
    ) : ViewModel() {
        val query = MutableStateFlow("")
        val filter = MutableStateFlow(LocalFilter.ALL)

        val result =
            combine(query, filter) { query, filter ->
                query to filter
            }.flatMapLatest { (query, filter) ->
                if (query.isEmpty()) {
                    flowOf(LocalSearchResult("", filter, emptyMap()))
                } else {
                    when (filter) {
                        LocalFilter.ALL -> {
                            combine(
                                songSearchWithFuzzy(database, query, PREVIEW_SIZE, PREVIEW_SIZE),
                                database.searchAlbums(query, PREVIEW_SIZE),
                                database.searchArtists(query, PREVIEW_SIZE),
                                database.searchPlaylists(query, PREVIEW_SIZE),
                            ) { songs, albums, artists, playlists ->
                                songs.filter { song -> song.artists.none { it.blockedAt != null } } +
                                    albums.filter { album -> album.artists.none { it.blockedAt != null } } +
                                    artists.filter { artist -> artist.artist.blockedAt == null } +
                                    playlists
                            }
                        }

                        LocalFilter.SONG -> {
                            songSearchWithFuzzy(database, query, Int.MAX_VALUE, FUZZY_SUPPLEMENT_LIMIT)
                        }

                        LocalFilter.ALBUM -> {
                            database.searchAlbums(query).map { albums ->
                                albums.filter { album -> album.artists.none { it.blockedAt != null } }
                            }
                        }

                        LocalFilter.ARTIST -> {
                            database.searchArtists(query).map { artists ->
                                artists.filter { artist -> artist.artist.blockedAt == null }
                            }
                        }

                        LocalFilter.PLAYLIST -> {
                            database.searchPlaylists(query)
                        }
                    }.map { list ->
                        LocalSearchResult(
                            query = query,
                            filter = filter,
                            map =
                                list.groupBy {
                                    when (it) {
                                        is Song -> LocalFilter.SONG
                                        is Album -> LocalFilter.ALBUM
                                        is Artist -> LocalFilter.ARTIST
                                        is Playlist -> LocalFilter.PLAYLIST
                                    }
                                },
                        )
                    }
                }
            }.stateIn(
                viewModelScope,
                SharingStarted.Lazily,
                LocalSearchResult("", filter.value, emptyMap()),
            )

        companion object {
            const val PREVIEW_SIZE = 3
        }
    }

enum class LocalFilter {
    ALL,
    SONG,
    ALBUM,
    ARTIST,
    PLAYLIST,
}

private const val FUZZY_MIN_QUERY_LENGTH = 3
private const val FUZZY_EXACT_SUFFICIENT_COUNT = 10
private const val FUZZY_SUPPLEMENT_LIMIT = 15
private const val FUZZY_MIN_SCORE = 0.45
private const val FUZZY_SCORE_WEIGHT = 0.8
private const val PLAY_COUNT_SCORE_WEIGHT = 0.2

/**
 * Exact LIKE matches first (cheap fast path), then typo-tolerant fuzzy matches ranked
 * by [FuzzyMatcher] score weighted by play count. The fuzzy pass over the full library
 * only runs when the exact results are thin, so normal typing stays on the fast query.
 */
private fun songSearchWithFuzzy(
    database: MusicDatabase,
    query: String,
    previewSize: Int,
    fuzzyLimit: Int,
): Flow<List<Song>> =
    database.searchSongs(query, previewSize).flatMapLatest { exactSongs ->
        val exact = exactSongs.filter { song -> song.artists.none { it.blockedAt != null } }
        if (query.trim().length < FUZZY_MIN_QUERY_LENGTH || exact.size >= FUZZY_EXACT_SUFFICIENT_COUNT) {
            flowOf(exact)
        } else {
            database.songsByNameAsc().map { pool ->
                exact + fuzzySupplement(query, exact, pool, fuzzyLimit)
            }
        }
    }

private fun fuzzySupplement(
    query: String,
    exact: List<Song>,
    pool: List<Song>,
    limit: Int,
): List<Song> {
    val exactIds = exact.mapTo(mutableSetOf()) { it.id }
    val maxPlayTime = pool.maxOfOrNull { it.song.totalPlayTime }?.coerceAtLeast(1L) ?: 1L
    val logMax = ln(1.0 + maxPlayTime.toDouble())
    return pool
        .asSequence()
        .filter { it.id !in exactIds && it.artists.none { artist -> artist.blockedAt != null } }
        .map { song ->
            val fuzzyScore = FuzzyMatcher.songScore(song, query, emptyList())
            val playWeight = ln(1.0 + song.song.totalPlayTime.toDouble()) / logMax
            song to (fuzzyScore * FUZZY_SCORE_WEIGHT + playWeight * PLAY_COUNT_SCORE_WEIGHT)
        }.filter { (_, score) -> score >= FUZZY_MIN_SCORE }
        .sortedByDescending { (_, score) -> score }
        .take(limit)
        .map { (song, _) -> song }
        .toList()
}

data class LocalSearchResult(
    val query: String,
    val filter: LocalFilter,
    val map: Map<LocalFilter, List<LocalItem>>,
)
