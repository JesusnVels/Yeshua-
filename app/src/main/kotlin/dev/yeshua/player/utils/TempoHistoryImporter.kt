/*
 * LunarTune (2026)
 * © cognitiveshadows03 — github.com/cognitiveshadows03
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package dev.yeshua.player.utils

import android.content.Context
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import dev.yeshua.player.db.MusicDatabase
import dev.yeshua.player.db.entities.Event
import dev.yeshua.player.db.entities.PlayCountEntity
import dev.yeshua.player.models.toMediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.SongItem
import java.io.InputStream
import java.io.InputStreamReader
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Result of a Tempo backup import.
 *
 * @param importedCount plays recorded into history.
 * @param matchedLocalCount imported plays matched to a song already in the library.
 * @param matchedSearchCount imported plays matched via YouTube search.
 * @param skippedCount plays skipped (below the completion threshold or already recorded).
 * @param failedCount plays that could not be matched to any song.
 */
data class TempoImportResult(
    val importedCount: Int,
    val matchedLocalCount: Int,
    val matchedSearchCount: Int,
    val skippedCount: Int,
    val failedCount: Int,
)

/**
 * Imports listening history from a Tempo (github.com/avinaxhroy/Tempo) backup ZIP.
 *
 * Tempo keeps everything on-device and exposes no API, so the import works from the
 * backup file the user exports in Tempo's settings: a ZIP containing `data.json`
 * with `tracks` and `listeningEvents` arrays.
 *
 * Matching runs in three tiers per distinct track:
 * 1. Local library match on normalized title + artist (fast, offline).
 * 2. YouTube search (`"<title> <artist>"`, top song result) when [searchMissing] is
 *    true. Each distinct track is searched once, with a small delay between searches.
 * 3. Anything still unmatched is counted in [TempoImportResult.failedCount].
 */
object TempoHistoryImporter {
    /** Plays below this completion % are treated as skips (matches Tempo's own threshold). */
    private const val MIN_COMPLETION_PERCENT = 30

    /** Politeness delay between YouTube searches. */
    private const val SEARCH_DELAY_MS = 500L

    suspend fun importTempoBackup(
        zipUri: Uri,
        context: Context,
        database: MusicDatabase,
        searchMissing: Boolean = true,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): TempoImportResult =
        withContext(Dispatchers.IO) {
            val (tracks, plays) = parseBackup(context, zipUri)
            if (plays.isEmpty()) {
                return@withContext TempoImportResult(0, 0, 0, 0, 0)
            }

            // Drop skips and de-duplicate within the import itself.
            val seen = HashSet<String>()
            var skippedCount = 0
            val meaningful =
                plays.filter { play ->
                    when {
                        play.completion < MIN_COMPLETION_PERCENT -> {
                            skippedCount++
                            false
                        }

                        !seen.add("${play.trackId}\u0001${play.timestamp}") -> {
                            skippedCount++
                            false
                        }

                        else -> true
                    }
                }

            val libraryIndex = buildLibraryIndex(database)

            // Resolve each distinct track to a Yeshua song id, tier 1 (local) first.
            val distinctTrackIds = meaningful.map { it.trackId }.distinct()
            val trackToSongId = HashMap<Long, String>()
            for (trackId in distinctTrackIds) {
                val track = tracks[trackId] ?: continue
                findLocalSong(libraryIndex, track.title, track.artist)?.let { songId ->
                    trackToSongId[trackId] = songId
                }
            }

            // Tier 2: YouTube search for whatever is still unmatched.
            val unmatched = distinctTrackIds.filter { it !in trackToSongId }
            if (searchMissing && unmatched.isNotEmpty()) {
                val searchable = unmatched.mapNotNull { id -> tracks[id]?.let { id to it } }
                searchable.forEachIndexed { index, (trackId, track) ->
                    onProgress(index, searchable.size)
                    searchYouTubeForTrack(database, track.title, track.artist)?.let { songId ->
                        trackToSongId[trackId] = songId
                    }
                    delay(SEARCH_DELAY_MS)
                }
                onProgress(searchable.size, searchable.size)
            }

            // Record every resolvable play. Tiers are re-derived per play for the counts.
            var importedCount = 0
            var matchedLocalCount = 0
            var matchedSearchCount = 0
            var failedCount = 0
            // Tier attribution for the result counts: a song id counts as "local" when the
            // track matched without needing a search (the match is deterministic).
            val localSongIds = HashSet<String>()
            for ((trackId, songId) in trackToSongId) {
                val track = tracks[trackId] ?: continue
                if (findLocalSong(libraryIndex, track.title, track.artist) == songId) {
                    localSongIds.add(songId)
                }
            }

            val existingTimestamps = HashMap<String, MutableSet<Long>>()

            suspend fun alreadyRecorded(
                songId: String,
                timestamp: Long,
            ): Boolean {
                var known = existingTimestamps[songId]
                if (known == null) {
                    known = database.eventTimestamps(songId).toMutableSet()
                    existingTimestamps[songId] = known
                }
                return !known.add(timestamp)
            }

            database.withTransaction {
                for (play in meaningful) {
                    val songId = trackToSongId[play.trackId]
                    if (songId == null) {
                        failedCount++
                        continue
                    }
                    if (alreadyRecorded(songId, play.timestamp)) {
                        skippedCount++
                        continue
                    }
                    val instant = Instant.ofEpochMilli(play.timestamp)
                    database.insert(
                        Event(
                            songId = songId,
                            timestamp = LocalDateTime.ofInstant(instant, ZoneOffset.UTC),
                            playTime = play.playDuration,
                        ),
                    )
                    database.incrementTotalPlayTime(songId, play.playDuration)
                    val dated = instant.atOffset(ZoneOffset.UTC)
                    database.insert(PlayCountEntity(songId, dated.year, dated.monthValue, 0))
                    database.incrementPlayCount(songId, dated.year, dated.monthValue)
                    importedCount++
                    if (songId in localSongIds) matchedLocalCount++ else matchedSearchCount++
                }
            }

            TempoImportResult(
                importedCount = importedCount,
                matchedLocalCount = matchedLocalCount,
                matchedSearchCount = matchedSearchCount,
                skippedCount = skippedCount,
                failedCount = failedCount,
            )
        }

    // -------------------------------------------------------------------------
    // Backup parsing (streaming; listeningEvents can be very large)
    // -------------------------------------------------------------------------

    private data class TempoTrack(val title: String, val artist: String)

    private data class TempoPlay(
        val trackId: Long,
        val timestamp: Long,
        val playDuration: Long,
        val completion: Int,
    )

    private fun parseBackup(
        context: Context,
        zipUri: Uri,
    ): Pair<Map<Long, TempoTrack>, List<TempoPlay>> {
        val raw =
            context.contentResolver.openInputStream(zipUri)
                ?: throw IllegalArgumentException("Cannot open backup file")
        raw.buffered().use { buffered ->
            ZipInputStream(buffered).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name =
                        entry.name
                            .substringAfterLast('/')
                            .substringAfterLast('\\')
                    if (!entry.isDirectory && name.equals("data.json", ignoreCase = true)) {
                        return parseDataJson(zip)
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        throw IllegalArgumentException("No data.json found in backup")
    }

    private fun parseDataJson(stream: InputStream): Pair<Map<Long, TempoTrack>, List<TempoPlay>> {
        val tracks = HashMap<Long, TempoTrack>()
        val plays = ArrayList<TempoPlay>()
        val reader = JsonReader(InputStreamReader(stream, Charsets.UTF_8))
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "tracks" -> {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        readTempoTrack(reader)?.let { (id, track) -> tracks[id] = track }
                    }
                    reader.endArray()
                }

                "listeningEvents" -> {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        readTempoPlay(reader)?.let { plays.add(it) }
                    }
                    reader.endArray()
                }

                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return tracks to plays
    }

    private fun readTempoTrack(reader: JsonReader): Pair<Long, TempoTrack>? {
        var id: Long? = null
        var title: String? = null
        var artist: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> id = reader.nextLongOrNull()
                "title" -> title = reader.nextStringOrNull()
                "artist" -> artist = reader.nextStringOrNull()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        if (id == null || title.isNullOrBlank() || artist.isNullOrBlank()) return null
        return id to TempoTrack(title, artist)
    }

    private fun readTempoPlay(reader: JsonReader): TempoPlay? {
        var trackId: Long? = null
        var timestamp: Long? = null
        var playDuration = 0L
        var completion = 100
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "track_id", "trackId" -> trackId = reader.nextLongOrNull()
                "timestamp" -> timestamp = reader.nextLongOrNull()
                "playDuration" -> playDuration = reader.nextLongOrNull() ?: 0L
                "completionPercentage" -> completion = reader.nextLongOrNull()?.toInt() ?: 100
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        if (trackId == null || timestamp == null) return null
        return TempoPlay(trackId, timestamp, playDuration, completion)
    }

    private fun JsonReader.nextStringOrNull(): String? =
        if (peek() == JsonToken.STRING) nextString() else {
            skipValue()
            null
        }

    private fun JsonReader.nextLongOrNull(): Long? =
        when (peek()) {
            JsonToken.NUMBER -> nextLong()
            JsonToken.STRING -> nextString().toLongOrNull()
            else -> {
                skipValue()
                null
            }
        }

    // -------------------------------------------------------------------------
    // Tier 1: local library matching
    // -------------------------------------------------------------------------

    private suspend fun buildLibraryIndex(database: MusicDatabase): Map<String, String> {
        val index = HashMap<String, String>()
        for (song in database.importSongCandidates().first()) {
            val songId = song.song.id
            val titles = titleVariants(song.song.title)
            val artists = song.artists.flatMap { artistVariants(it.name) }.distinct()
            for (title in titles) {
                for (artist in artists) {
                    index.putIfAbsent("$title\u0001$artist", songId)
                }
            }
        }
        return index
    }

    private fun findLocalSong(
        index: Map<String, String>,
        title: String,
        artist: String,
    ): String? {
        for (titleVariant in titleVariants(title)) {
            for (artistVariant in artistVariants(artist)) {
                index["$titleVariant\u0001$artistVariant"]?.let { return it }
            }
        }
        return null
    }

    /**
     * Normalization used on both sides of the match: lowercase, collapsed whitespace,
     * and YouTube's " - Topic" artist suffix removed.
     */
    private fun normalize(text: String): String =
        text
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
            .trim()
            .removeSuffix(" - topic")

    private fun titleVariants(title: String): List<String> {
        val base = normalize(title)
        if (base.isEmpty()) return emptyList()
        // "Song (Official Video)" -> "Song"
        val deParenthesized = base.replace(Regex("\\s*\\([^)]*\\)\\s*$"), "").trim()
        return if (deParenthesized.isNotEmpty() && deParenthesized != base) {
            listOf(base, deParenthesized)
        } else {
            listOf(base)
        }
    }

    private fun artistVariants(artist: String): List<String> {
        val base = normalize(artist)
        if (base.isEmpty()) return emptyList()
        val parts =
            base
                .split(Regex("\\s+feat\\.?\\s+|\\s+ft\\.?\\s+|\\s+featuring\\s+|\\s*[,&]\\s*|\\s+x\\s+|\\s+vs\\.?\\s+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != base }
        return listOf(base) + parts
    }

    // -------------------------------------------------------------------------
    // Tier 2: YouTube search fallback
    // -------------------------------------------------------------------------

    /**
     * Searches YouTube for "<title> <artist>", takes the top song result, and ensures a
     * song row exists for it (inLibrary stays null, so the library UI is unaffected).
     * Returns the song id, or null when nothing usable was found.
     */
    private suspend fun searchYouTubeForTrack(
        database: MusicDatabase,
        title: String,
        artist: String,
    ): String? =
        try {
            val items =
                YouTube
                    .search("$title $artist", YouTube.SearchFilter.FILTER_SONG)
                    .getOrNull()
                    ?.items
                    ?: return null
            val songItem = items.filterIsInstance<SongItem>().firstOrNull() ?: return null
            if (database.getSongById(songItem.id) == null) {
                database.insert(songItem.toMediaMetadata())
            }
            songItem.id
        } catch (_: Exception) {
            null
        }
}
