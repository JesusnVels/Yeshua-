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
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.yeshua.player.db.MusicDatabase
import dev.yeshua.player.db.entities.Event
import dev.yeshua.player.db.entities.PlayCountEntity
import dev.yeshua.player.models.toMediaMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.SongItem
import java.io.InputStream
import java.io.InputStreamReader
import java.security.MessageDigest
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
 * Durable progress checkpoint for an in-flight Tempo import, persisted in the
 * app DataStore (single string value, rewritten atomically at chunk boundaries).
 *
 * A checkpoint is only ever stored for an UNFINISHED run: completion removes it.
 * It is keyed to one backup archive via [archiveHash] (a digest of the parsed
 * data.json content), so re-picking a different backup never resumes into it.
 *
 * [skippedCount] counts only processing-stage skips (plays already recorded);
 * parse-stage skips (below-threshold / in-file duplicates) are recomputed
 * deterministically on every run and added by the importer.
 *
 * [searchedTrackResults] holds the tier-2 (YouTube search) outcome per Tempo
 * track id — the song id on a hit, null on a miss — so a resumed run never
 * repeats a search. Tier-1 (local) outcomes are NOT stored: they are cheap
 * and recomputed from the in-memory library index on every run.
 */
data class TempoImportCheckpoint(
    val archiveHash: String,
    val totalPlays: Int,
    val processedPlays: Int,
    val importedCount: Int,
    val matchedLocalCount: Int,
    val matchedSearchCount: Int,
    val skippedCount: Int,
    val failedCount: Int,
    val searchedTrackResults: Map<Long, String?>,
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
 *    true. Each distinct track is searched once.
 * 3. Anything still unmatched is counted in [TempoImportResult.failedCount].
 *
 * Plays are processed in chunks of [CHUNK_SIZE]: each chunk resolves its tracks
 * (tier 2 runs with bounded parallelism), records its plays in a single Room
 * transaction, then persists a [TempoImportCheckpoint]. If the run is interrupted
 * (worker stopped, process killed, app force-closed), re-importing the SAME
 * archive resumes after the last committed chunk instead of starting over; the
 * per-play "already recorded" check keeps replays duplicate-free.
 */
object TempoHistoryImporter {
    /** Plays below this completion % are treated as skips (matches Tempo's own threshold). */
    private const val MIN_COMPLETION_PERCENT = 30

    /** Politeness delay after each YouTube search, per concurrency slot. */
    private const val SEARCH_DELAY_MS = 500L

    /** How many YouTube searches may be in flight at once in tier 2. */
    private const val SEARCH_CONCURRENCY = 4

    /** Plays processed (and checkpointed) per chunk. */
    private const val CHUNK_SIZE = 100

    private val CHECKPOINT_KEY = stringPreferencesKey("tempoImportCheckpoint")

    suspend fun importTempoBackup(
        zipUri: Uri,
        context: Context,
        database: MusicDatabase,
        searchMissing: Boolean = true,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): TempoImportResult =
        withContext(Dispatchers.IO) {
            val (tracks, plays, archiveHash) = parseBackup(context, zipUri)
            if (plays.isEmpty()) {
                return@withContext TempoImportResult(0, 0, 0, 0, 0)
            }

            // Drop skips and de-duplicate within the import itself. This list is
            // built deterministically from the file order, so a resumed run sees
            // exactly the same sequence of plays.
            val seen = HashSet<String>()
            var parseSkippedCount = 0
            val meaningful =
                plays.filter { play ->
                    when {
                        play.completion < MIN_COMPLETION_PERCENT -> {
                            parseSkippedCount++
                            false
                        }

                        !seen.add("${play.trackId}\u0001${play.timestamp}") -> {
                            parseSkippedCount++
                            false
                        }

                        else -> true
                    }
                }

            val total = meaningful.size

            // Resume state: only for the same archive, and only if the previous
            // run stopped before finishing. Anything else starts fresh (the
            // stored checkpoint, if any, is replaced at the first chunk write).
            val stored = readCheckpoint(context)
            val resume =
                stored?.takeIf {
                    it.archiveHash == archiveHash &&
                        it.totalPlays == total &&
                        it.processedPlays in 1 until total
                }
            var processed = resume?.processedPlays ?: 0
            var importedCount = resume?.importedCount ?: 0
            var matchedLocalCount = resume?.matchedLocalCount ?: 0
            var matchedSearchCount = resume?.matchedSearchCount ?: 0
            var processingSkippedCount = resume?.skippedCount ?: 0
            var failedCount = resume?.failedCount ?: 0
            val searchedTrackResults =
                HashMap<Long, String?>(resume?.searchedTrackResults ?: emptyMap())

            onProgress(processed, total)

            // Tier-1 lookup, loaded once for the whole run.
            val libraryIndex = buildLibraryIndex(database)

            // Per-run resolution state, filled chunk by chunk.
            val trackToSongId = HashMap<Long, String>()
            val trackIsLocal = HashMap<Long, Boolean>()
            // Idempotency: existing event timestamps per resolved song, loaded
            // once per song (lazily at the chunk that first needs it) and then
            // kept current in memory as this run records plays.
            val existingTimestamps = HashMap<String, MutableSet<Long>>()

            fun checkpoint(): TempoImportCheckpoint =
                TempoImportCheckpoint(
                    archiveHash = archiveHash,
                    totalPlays = total,
                    processedPlays = processed,
                    importedCount = importedCount,
                    matchedLocalCount = matchedLocalCount,
                    matchedSearchCount = matchedSearchCount,
                    skippedCount = processingSkippedCount,
                    failedCount = failedCount,
                    searchedTrackResults = HashMap(searchedTrackResults),
                )

            var cursor = processed
            while (cursor < total) {
                val chunk = meaningful.subList(cursor, minOf(cursor + CHUNK_SIZE, total))

                // --- Resolve this chunk's distinct tracks -------------------
                val toSearch = ArrayList<Pair<Long, TempoTrack>>()
                for (trackId in chunk.map { it.trackId }.distinct()) {
                    if (trackId in trackToSongId) continue
                    val track = tracks[trackId] ?: continue
                    val localId = findLocalSong(libraryIndex, track.title, track.artist)
                    when {
                        localId != null -> {
                            trackToSongId[trackId] = localId
                            trackIsLocal[trackId] = true
                        }

                        trackId in searchedTrackResults -> {
                            searchedTrackResults[trackId]?.let { songId ->
                                trackToSongId[trackId] = songId
                                // Same attribution rule as a fresh run: a search
                                // hit counts as "local" if it is what the local
                                // tier resolves to as well.
                                trackIsLocal[trackId] =
                                    findLocalSong(libraryIndex, track.title, track.artist) == songId
                            }
                        }

                        searchMissing -> toSearch.add(trackId to track)
                    }
                }

                // --- Tier 2: YouTube search, bounded parallelism ------------
                if (toSearch.isNotEmpty()) {
                    val results = searchTracks(database, toSearch)
                    for ((trackId, songId) in results) {
                        searchedTrackResults[trackId] = songId
                        if (songId != null) {
                            val track = tracks.getValue(trackId)
                            trackToSongId[trackId] = songId
                            trackIsLocal[trackId] =
                                findLocalSong(libraryIndex, track.title, track.artist) == songId
                        }
                    }
                }

                // --- Load existing timestamps for this chunk's songs once ---
                for (songId in chunk.mapNotNull { trackToSongId[it.trackId] }.distinct()) {
                    if (songId !in existingTimestamps) {
                        existingTimestamps[songId] =
                            database.eventTimestamps(songId).toMutableSet()
                    }
                }

                // --- Record the chunk in a single transaction ---------------
                database.withTransaction {
                    for (play in chunk) {
                        val songId = trackToSongId[play.trackId]
                        if (songId == null) {
                            failedCount++
                            continue
                        }
                        if (!existingTimestamps.getValue(songId).add(play.timestamp)) {
                            processingSkippedCount++
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
                        if (trackIsLocal[play.trackId] == true) {
                            matchedLocalCount++
                        } else {
                            matchedSearchCount++
                        }
                    }
                }

                // --- Checkpoint AFTER the chunk commits ----------------------
                processed += chunk.size
                cursor = processed
                if (processed < total) {
                    writeCheckpoint(context, checkpoint())
                }
                onProgress(processed, total)
            }

            // Run finished: the checkpoint has served its purpose.
            clearCheckpoint(context)

            TempoImportResult(
                importedCount = importedCount,
                matchedLocalCount = matchedLocalCount,
                matchedSearchCount = matchedSearchCount,
                skippedCount = parseSkippedCount + processingSkippedCount,
                failedCount = failedCount,
            )
        }

    // -------------------------------------------------------------------------
    // Durable progress checkpoint (DataStore)
    // -------------------------------------------------------------------------

    /** The stored checkpoint for an unfinished import, or null. */
    suspend fun readCheckpoint(context: Context): TempoImportCheckpoint? =
        try {
            val raw = context.dataStore.data.first()[CHECKPOINT_KEY] ?: return null
            decodeCheckpoint(raw)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }

    private suspend fun writeCheckpoint(
        context: Context,
        checkpoint: TempoImportCheckpoint,
    ) {
        try {
            context.dataStore.edit { prefs ->
                prefs[CHECKPOINT_KEY] = encodeCheckpoint(checkpoint)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // A failed checkpoint write only costs resume granularity; the
            // import itself (and its DB-backed idempotency) is unaffected.
        }
    }

    private suspend fun clearCheckpoint(context: Context) {
        try {
            context.dataStore.edit { prefs ->
                prefs.remove(CHECKPOINT_KEY)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
        }
    }

    // Encoding: a header line of pipe-separated counters, then one line per
    // searched track ("<trackId>\t<songId>", songId empty on a search miss).
    private fun encodeCheckpoint(checkpoint: TempoImportCheckpoint): String =
        buildString {
            append("v1|")
            append(checkpoint.archiveHash).append('|')
            append(checkpoint.totalPlays).append('|')
            append(checkpoint.processedPlays).append('|')
            append(checkpoint.importedCount).append('|')
            append(checkpoint.matchedLocalCount).append('|')
            append(checkpoint.matchedSearchCount).append('|')
            append(checkpoint.skippedCount).append('|')
            append(checkpoint.failedCount)
            for ((trackId, songId) in checkpoint.searchedTrackResults) {
                append('\n').append(trackId).append('\t').append(songId ?: "")
            }
        }

    private fun decodeCheckpoint(raw: String): TempoImportCheckpoint? =
        runCatching {
            val lines = raw.split('\n')
            val header = lines.first().split('|')
            require(header.size == 9 && header[0] == "v1")
            val searched = HashMap<Long, String?>()
            for (line in lines.drop(1)) {
                if (line.isEmpty()) continue
                val sep = line.indexOf('\t')
                require(sep > 0)
                val trackId = line.substring(0, sep).toLong()
                val songId = line.substring(sep + 1)
                searched[trackId] = songId.ifEmpty { null }
            }
            TempoImportCheckpoint(
                archiveHash = header[1],
                totalPlays = header[2].toInt(),
                processedPlays = header[3].toInt(),
                importedCount = header[4].toInt(),
                matchedLocalCount = header[5].toInt(),
                matchedSearchCount = header[6].toInt(),
                skippedCount = header[7].toInt(),
                failedCount = header[8].toInt(),
                searchedTrackResults = searched,
            )
        }.getOrNull()

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

    private data class ParsedBackup(
        val tracks: Map<Long, TempoTrack>,
        val plays: List<TempoPlay>,
        /** Digest of the parsed data.json content; identifies the archive. */
        val archiveHash: String,
    )

    private fun parseBackup(
        context: Context,
        zipUri: Uri,
    ): ParsedBackup {
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

    private fun parseDataJson(stream: InputStream): ParsedBackup {
        val tracks = HashMap<Long, TempoTrack>()
        val plays = ArrayList<TempoPlay>()
        val digest = MessageDigest.getInstance("SHA-256")
        val reader = JsonReader(InputStreamReader(stream, Charsets.UTF_8))
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "tracks" -> {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        readTempoTrack(reader)?.let { (id, track) ->
                            tracks[id] = track
                            digest.update("T$id|${track.title}|${track.artist};".toByteArray(Charsets.UTF_8))
                        }
                    }
                    reader.endArray()
                }

                "listeningEvents" -> {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        readTempoPlay(reader)?.let { play ->
                            plays.add(play)
                            digest.update(
                                "P${play.trackId}|${play.timestamp}|${play.playDuration}|${play.completion};"
                                    .toByteArray(Charsets.UTF_8),
                            )
                        }
                    }
                    reader.endArray()
                }

                else -> reader.skipValue()
            }
        }
        reader.endObject()
        val archiveHash = digest.digest().joinToString("") { "%02x".format(it) }
        return ParsedBackup(tracks, plays, archiveHash)
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
     * Runs tier-2 searches for [tracks] with at most [SEARCH_CONCURRENCY] in
     * flight (each slot keeps the [SEARCH_DELAY_MS] politeness pause after its
     * search). Returns track id -> song id (null = no match) for every input
     * track; play processing reads the map, so counts stay in file order and
     * deterministic regardless of search completion order.
     */
    private suspend fun searchTracks(
        database: MusicDatabase,
        tracks: List<Pair<Long, TempoTrack>>,
    ): Map<Long, String?> =
        coroutineScope {
            val semaphore = Semaphore(SEARCH_CONCURRENCY)
            tracks
                .map { (trackId, track) ->
                    async {
                        semaphore.withPermit {
                            val songId = searchYouTubeForTrack(database, track.title, track.artist)
                            delay(SEARCH_DELAY_MS)
                            trackId to songId
                        }
                    }
                }
                .awaitAll()
                .toMap()
        }

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
