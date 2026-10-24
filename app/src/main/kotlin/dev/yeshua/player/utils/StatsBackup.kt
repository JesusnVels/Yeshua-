/*
 * LunarTune (2026)
 * © cognitiveshadows03 — github.com/cognitiveshadows03
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package dev.yeshua.player.utils

import android.content.Context
import android.net.Uri
import dev.yeshua.player.db.MusicDatabase
import dev.yeshua.player.db.entities.ArtistEntity
import dev.yeshua.player.db.entities.Event
import dev.yeshua.player.db.entities.PlayCountEntity
import dev.yeshua.player.db.entities.Song
import dev.yeshua.player.db.entities.SongArtistMap
import dev.yeshua.player.db.entities.SongEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Result of a stats backup restore.
 *
 * @param eventsAdded plays that were not yet recorded and have been inserted.
 * @param eventsSkipped plays that were already recorded (or whose song is
 * unknown), left untouched so a restore never duplicates history.
 * @param playCountsWritten play-count rows created, or raised to the backup's
 * count where the backup held the larger value.
 */
data class StatsBackupResult(
    val eventsAdded: Int,
    val eventsSkipped: Int,
    val playCountsWritten: Int,
)

/**
 * Exports and restores the user's listening statistics — history events and
 * per-month play counts — as a small portable archive: a ZIP holding a single
 * `stats.json` document.
 *
 * Unlike the full database backup in settings, this archive carries ONLY
 * statistics, so it can be restored onto any install (including a fresh one
 * after a reinstall) without touching the rest of the library. Songs a play
 * references are described by id/title/artist/duration, which is enough to
 * recreate minimal song rows on a fresh install; events and play counts always
 * reference that song list.
 *
 * Restores follow the same discipline as [TempoHistoryImporter]: plays are
 * recorded in chunks of [CHUNK_SIZE] inside single transactions, events are
 * de-duplicated on (songId, timestamp) against the timestamps already stored,
 * and play counts only ever move up (max of existing and backup), so restoring
 * the same backup twice changes nothing the second time.
 */
object StatsBackup {
    /** Events recorded per transaction during a restore. */
    private const val CHUNK_SIZE = 100

    /** Events read per page during an export. */
    private const val PAGE_SIZE = 500

    private const val ENTRY_NAME = "stats.json"
    private const val FORMAT_VERSION = 1

    private data class BackupSong(
        val id: String,
        val title: String,
        val artist: String,
        val duration: Int,
    )

    private data class BackupPlay(
        val songId: String,
        val timestamp: Long,
        val playTime: Long,
    )

    private data class BackupPlayCount(
        val songId: String,
        val year: Int,
        val month: Int,
        val count: Int,
    )

    /**
     * Writes all history events and play counts to [uri] as a stats backup ZIP.
     *
     * @return the number of plays (events) written to the backup.
     */
    suspend fun exportStats(
        context: Context,
        database: MusicDatabase,
        uri: Uri,
    ): Int =
        withContext(Dispatchers.IO) {
            val eventsJson = JSONArray()
            val songJsonById = LinkedHashMap<String, JSONObject>()
            var totalEvents = 0

            var offset = 0
            while (true) {
                val page = database.events(PAGE_SIZE, offset)
                if (page.isEmpty()) break
                for (item in page) {
                    val event = item.event
                    eventsJson.put(
                        JSONObject().apply {
                            put("songId", event.songId)
                            put(
                                "timestamp",
                                event.timestamp
                                    .atZone(ZoneOffset.UTC)
                                    .toInstant()
                                    .toEpochMilli(),
                            )
                            put("playTime", event.playTime)
                        },
                    )
                    if (event.songId !in songJsonById) {
                        songJsonById[event.songId] = songToJson(item.song)
                    }
                    totalEvents++
                }
                if (page.size < PAGE_SIZE) break
                offset += PAGE_SIZE
            }

            val playCounts = readAllPlayCounts(database)
            val playCountsJson = JSONArray()
            for (playCount in playCounts) {
                playCountsJson.put(
                    JSONObject().apply {
                        put("songId", playCount.songId)
                        put("year", playCount.year)
                        put("month", playCount.month)
                        put("count", playCount.count)
                    },
                )
                // Songs referenced only by a play count still get a song entry,
                // so a restore can recreate their rows.
                if (playCount.songId !in songJsonById) {
                    database.getSongById(playCount.songId)?.let { song ->
                        songJsonById[playCount.songId] = songToJson(song)
                    }
                }
            }

            val root =
                JSONObject().apply {
                    put("version", FORMAT_VERSION)
                    put("exportedAt", System.currentTimeMillis())
                    put("songs", JSONArray(songJsonById.values))
                    put("events", eventsJson)
                    put("playCounts", playCountsJson)
                }

            val output =
                context.contentResolver.openOutputStream(uri, "wt")
                    ?: throw IllegalStateException("Failed to open backup destination")
            output.buffered().use { buffered ->
                ZipOutputStream(buffered).use { zip ->
                    zip.putNextEntry(ZipEntry(ENTRY_NAME))
                    zip.write(root.toString().toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
            totalEvents
        }

    /**
     * Restores a stats backup previously written by [exportStats].
     *
     * Songs referenced by the backup are recreated first when missing; events
     * and play counts that reference a song which neither the backup nor the
     * current database knows are skipped.
     */
    suspend fun importStats(
        context: Context,
        database: MusicDatabase,
        uri: Uri,
    ): StatsBackupResult =
        withContext(Dispatchers.IO) {
            val root = readBackupJson(context, uri)
            val songs = parseSongs(root.optJSONArray("songs"))
            val plays = parsePlays(root.optJSONArray("events"))
            val playCounts = parsePlayCounts(root.optJSONArray("playCounts"))
            if (root.optInt("version", 0) < FORMAT_VERSION && plays.isEmpty() && playCounts.isEmpty()) {
                throw IllegalArgumentException("Not a Yeshua stats backup")
            }

            // --- Ensure every referenced song row exists ----------------------
            // Mirrors the MediaMetadata insert convention: an artist is matched
            // by name first and only created (with a generated local id) when
            // unknown. Artist links are only added for rows created here, so an
            // existing song keeps its own artists untouched.
            val knownSongIds = HashSet<String>()
            database.withTransaction {
                for (song in songs) {
                    if (database.getSongById(song.id) != null) {
                        knownSongIds += song.id
                        continue
                    }
                    val inserted =
                        database.insert(
                            SongEntity(
                                id = song.id,
                                title = song.title,
                                duration = song.duration,
                            ),
                        )
                    knownSongIds += song.id
                    if (inserted == -1L || song.artist.isBlank()) continue
                    song.artist
                        .split(", ")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .forEachIndexed { index, name ->
                            val artistId =
                                database.artistByName(name)?.id
                                    ?: ArtistEntity.generateArtistId()
                            database.insert(ArtistEntity(id = artistId, name = name))
                            database.insert(
                                SongArtistMap(
                                    songId = song.id,
                                    artistId = artistId,
                                    position = index,
                                ),
                            )
                        }
                }
                // Plays may reference songs that are not in the backup's song
                // list; accept the ones the database already knows.
                val referencedIds =
                    plays.map { it.songId } + playCounts.map { it.songId }
                for (songId in referencedIds.distinct()) {
                    if (songId !in knownSongIds && database.getSongById(songId) != null) {
                        knownSongIds += songId
                    }
                }
            }

            // --- Record events, chunked, de-duplicated on (songId, timestamp)
            val existingTimestamps = HashMap<String, MutableSet<Long>>()
            var eventsAdded = 0
            var eventsSkipped = 0
            for (chunk in plays.chunked(CHUNK_SIZE)) {
                for (songId in chunk.map { it.songId }.distinct()) {
                    if (songId !in existingTimestamps) {
                        existingTimestamps[songId] =
                            database.eventTimestamps(songId).toMutableSet()
                    }
                }
                database.withTransaction {
                    for (play in chunk) {
                        if (play.songId !in knownSongIds) {
                            eventsSkipped++
                            continue
                        }
                        if (!existingTimestamps.getValue(play.songId).add(play.timestamp)) {
                            eventsSkipped++
                            continue
                        }
                        val instant = Instant.ofEpochMilli(play.timestamp)
                        database.insert(
                            Event(
                                songId = play.songId,
                                timestamp =
                                    LocalDateTime.ofInstant(instant, ZoneOffset.UTC),
                                playTime = play.playTime,
                            ),
                        )
                        database.incrementTotalPlayTime(play.songId, play.playTime)
                        eventsAdded++
                    }
                }
            }

            // --- Upsert play counts (insert, or raise to the backup's count) --
            val existingCounts = HashMap<Triple<String, Int, Int>, Int>()
            for (playCount in readAllPlayCounts(database)) {
                existingCounts[Triple(playCount.songId, playCount.year, playCount.month)] =
                    playCount.count
            }
            var playCountsWritten = 0
            database.withTransaction {
                for (playCount in playCounts) {
                    if (playCount.songId !in knownSongIds) continue
                    val key = Triple(playCount.songId, playCount.year, playCount.month)
                    val existing = existingCounts[key]
                    when {
                        existing == null -> {
                            database.insert(
                                PlayCountEntity(
                                    song = playCount.songId,
                                    year = playCount.year,
                                    month = playCount.month,
                                    count = playCount.count,
                                ),
                            )
                            existingCounts[key] = playCount.count
                            playCountsWritten++
                        }

                        playCount.count > existing -> {
                            // The DAO has no "set play count" update, so the
                            // raise goes through the open helper (still inside
                            // this transaction, so it commits or rolls back
                            // with the rest of the chunk).
                            database.openHelper.writableDatabase.execSQL(
                                "UPDATE playCount SET count = ? WHERE song = ? AND year = ? AND month = ?",
                                arrayOf<Any>(
                                    playCount.count,
                                    playCount.songId,
                                    playCount.year,
                                    playCount.month,
                                ),
                            )
                            existingCounts[key] = playCount.count
                            playCountsWritten++
                        }
                    }
                }
            }

            StatsBackupResult(
                eventsAdded = eventsAdded,
                eventsSkipped = eventsSkipped,
                playCountsWritten = playCountsWritten,
            )
        }

    // -------------------------------------------------------------------------
    // Reading / parsing
    // -------------------------------------------------------------------------

    private fun songToJson(song: Song): JSONObject =
        JSONObject().apply {
            put("id", song.song.id)
            put("title", song.song.title)
            put("artist", song.artists.joinToString(", ") { it.name })
            put("duration", song.song.duration)
        }

    /**
     * All play-count rows, verbatim. The DAO exposes per-song/per-month lookups
     * only, so the full table is read through the open helper.
     */
    private fun readAllPlayCounts(database: MusicDatabase): List<BackupPlayCount> {
        val result = ArrayList<BackupPlayCount>()
        database.openHelper.readableDatabase
            .query("SELECT song, year, month, count FROM playCount")
            .use { cursor ->
                while (cursor.moveToNext()) {
                    result.add(
                        BackupPlayCount(
                            songId = cursor.getString(0),
                            year = cursor.getInt(1),
                            month = cursor.getInt(2),
                            count = cursor.getInt(3),
                        ),
                    )
                }
            }
        return result
    }

    private fun readBackupJson(
        context: Context,
        uri: Uri,
    ): JSONObject {
        val raw =
            context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("Cannot open stats backup file")
        raw.buffered().use { buffered ->
            ZipInputStream(buffered).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name =
                        entry.name
                            .substringAfterLast('/')
                            .substringAfterLast('\\')
                    if (!entry.isDirectory && name.equals(ENTRY_NAME, ignoreCase = true)) {
                        return JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        throw IllegalArgumentException("No $ENTRY_NAME found in stats backup")
    }

    private fun parseSongs(array: JSONArray?): List<BackupSong> {
        if (array == null) return emptyList()
        val result = ArrayList<BackupSong>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (id.isBlank()) continue
            result.add(
                BackupSong(
                    id = id,
                    title = item.optString("title"),
                    artist = item.optString("artist"),
                    duration = item.optInt("duration", -1),
                ),
            )
        }
        return result
    }

    private fun parsePlays(array: JSONArray?): List<BackupPlay> {
        if (array == null) return emptyList()
        val result = ArrayList<BackupPlay>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val songId = item.optString("songId")
            if (songId.isBlank() || !item.has("timestamp")) continue
            result.add(
                BackupPlay(
                    songId = songId,
                    timestamp = item.optLong("timestamp"),
                    playTime = item.optLong("playTime"),
                ),
            )
        }
        return result
    }

    private fun parsePlayCounts(array: JSONArray?): List<BackupPlayCount> {
        if (array == null) return emptyList()
        val result = ArrayList<BackupPlayCount>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val songId = item.optString("songId")
            if (songId.isBlank()) continue
            result.add(
                BackupPlayCount(
                    songId = songId,
                    year = item.optInt("year", -1),
                    month = item.optInt("month", -1),
                    count = item.optInt("count", 0),
                ),
            )
        }
        return result
    }
}
