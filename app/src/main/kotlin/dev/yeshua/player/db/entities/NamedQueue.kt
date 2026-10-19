/*
 * LunarTune (2026)
 * © cognitiveshadows03 — github.com/cognitiveshadows03
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Named, typed, restart-surviving queue containers adapted from RiPlay (GPL-3.0).
 * These sit alongside the existing anonymous quick-restore queue
 * (PERSISTENT_QUEUE_FILE in MusicService), which is left untouched.
 */

package dev.yeshua.player.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.yeshua.player.models.MediaMetadata
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.commons.lang3.RandomStringUtils
import java.time.LocalDateTime

@Entity(tableName = "named_queue")
data class NamedQueueEntity(
    @PrimaryKey val id: String = generateNamedQueueId(),
    val name: String,
    val createdAt: LocalDateTime = LocalDateTime.now(),
) {
    companion object {
        fun generateNamedQueueId() = "NQ" + RandomStringUtils.insecure().next(12, true, false)
    }
}

@Entity(
    tableName = "named_queue_item",
    foreignKeys = [
        ForeignKey(
            entity = NamedQueueEntity::class,
            parentColumns = ["id"],
            childColumns = ["queueId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["queueId"])],
)
data class NamedQueueItemEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val queueId: String,
    val position: Int,
    val mediaId: String,
    val title: String,
    val artistsJson: String,
    val duration: Int,
    val thumbnailUrl: String?,
    val albumId: String?,
    val albumTitle: String?,
    val setVideoId: String?,
    val isMusicVideo: Boolean,
    val explicit: Boolean,
)

@Serializable
private data class StoredArtist(
    val id: String?,
    val name: String,
    val thumbnailUrl: String?,
)

private val namedQueueJson = Json { ignoreUnknownKeys = true }

fun MediaMetadata.toNamedQueueItemEntity(
    queueId: String,
    position: Int,
): NamedQueueItemEntity =
    NamedQueueItemEntity(
        queueId = queueId,
        position = position,
        mediaId = id,
        title = title,
        artistsJson =
            namedQueueJson.encodeToString(
                artists.map { artist ->
                    StoredArtist(
                        id = artist.id,
                        name = artist.name,
                        thumbnailUrl = artist.thumbnailUrl,
                    )
                },
            ),
        duration = duration,
        thumbnailUrl = thumbnailUrl,
        albumId = album?.id,
        albumTitle = album?.title,
        setVideoId = setVideoId,
        isMusicVideo = isMusicVideo,
        explicit = explicit,
    )

fun NamedQueueItemEntity.toMediaMetadata(): MediaMetadata {
    val artists =
        runCatching {
            namedQueueJson.decodeFromString<List<StoredArtist>>(artistsJson)
        }.getOrDefault(emptyList())
            .map { stored ->
                MediaMetadata.Artist(
                    id = stored.id,
                    name = stored.name,
                    thumbnailUrl = stored.thumbnailUrl,
                )
            }
    return MediaMetadata(
        id = mediaId,
        title = title,
        artists = artists,
        duration = duration,
        thumbnailUrl = thumbnailUrl,
        album =
            albumId?.let { id ->
                MediaMetadata.Album(id = id, title = albumTitle.orEmpty())
            },
        setVideoId = setVideoId,
        explicit = explicit,
        isMusicVideo = isMusicVideo,
    )
}
