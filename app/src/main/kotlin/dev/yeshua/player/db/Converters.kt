/*
 * LunarTune (2026)
 * © cognitiveshadows03 — github.com/cognitiveshadows03
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package dev.yeshua.player.db

import androidx.room.TypeConverter
import dev.yeshua.player.db.entities.PlaylistSyncMode
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class Converters {
    @TypeConverter
    fun fromTimestamp(value: Long?): LocalDateTime? =
        if (value != null) {
            LocalDateTime.ofInstant(Instant.ofEpochMilli(value), ZoneOffset.UTC)
        } else {
            null
        }

    @TypeConverter
    fun dateToTimestamp(date: LocalDateTime?): Long? = date?.atZone(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()

    @TypeConverter
    fun playlistSyncModeToString(value: PlaylistSyncMode?): String? = value?.name

    @TypeConverter
    fun stringToPlaylistSyncMode(value: String?): PlaylistSyncMode? =
        value?.let { runCatching { PlaylistSyncMode.valueOf(it) }.getOrNull() }
}
