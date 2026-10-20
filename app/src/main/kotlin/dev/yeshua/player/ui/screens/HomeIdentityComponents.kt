/*
 * LunarTune (2026)
 * © cognitiveshadows03 — github.com/cognitiveshadows03
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package dev.yeshua.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import dev.yeshua.player.LocalDatabase
import dev.yeshua.player.R
import dev.yeshua.player.db.entities.Album
import dev.yeshua.player.db.entities.Artist
import dev.yeshua.player.db.entities.LocalItem
import dev.yeshua.player.db.entities.Playlist
import dev.yeshua.player.db.entities.Song
import dev.yeshua.player.extensions.toMediaItem
import dev.yeshua.player.extensions.togglePlayPause
import dev.yeshua.player.playback.queues.ListQueue
import dev.yeshua.player.ui.menu.SongMenu
import dev.yeshua.player.ui.theme.YeshuaDisplayFontFamily
import dev.yeshua.player.ui.utils.displayArtworkUrl
import java.time.LocalTime

// ---------------------------------------------------------------------------
// Home context strip: time of day × dominant recent artist, over an artwork
// mosaic built from recent thumbnails. Replaces the static greeting with
// something memory-aware.
// ---------------------------------------------------------------------------

private data class ContextStripData(
    val dominantArtist: String?,
    val thumbnails: List<String?>,
)

@Composable
fun HomeContextStrip(modifier: Modifier = Modifier) {
    val database = LocalDatabase.current
    val contextData by produceState<ContextStripData?>(initialValue = null, database) {
        value =
            withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                val artist = database.dominantArtistSince(now - 7L * 24 * 60 * 60 * 1000)
                val thumbs =
                    runCatching { database.recentSongs(6).first().map { it.song.thumbnailUrl } }
                        .getOrDefault(emptyList())
                ContextStripData(artist, thumbs)
            }
    }

    val hour = remember { LocalTime.now().hour }
    val greetingRes =
        remember(hour) {
            when (hour) {
                in 5..11 -> R.string.home_greeting_morning
                in 12..16 -> R.string.home_greeting_afternoon
                in 17..21 -> R.string.home_greeting_evening
                else -> R.string.home_greeting_night
            }
        }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
    ) {
        Box {
            // Artwork mosaic backdrop.
            val thumbs = contextData?.thumbnails.orEmpty().take(6)
            if (thumbs.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier =
                        Modifier
                            .matchParentSize()
                            .alpha(0.28f),
                ) {
                    thumbs.forEach { url ->
                        AsyncImage(
                            model = url,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(8.dp)),
                        )
                    }
                }
                Box(
                    modifier =
                        Modifier
                            .matchParentSize()
                            .background(
                                Brush.horizontalGradient(
                                    0f to MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.55f),
                                    0.55f to MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.85f),
                                    1f to MaterialTheme.colorScheme.surfaceContainerLow,
                                ),
                            ),
                )
            }
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            ) {
                Text(
                    text = stringResource(greetingRes),
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = YeshuaDisplayFontFamily,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val artist = contextData?.dominantArtist
                if (artist != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.home_context_dominant, artist),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Speed Dial as circular chips. Same tap targets as the grid tiles, minus the
// pager: songs play, everything else navigates.
// ---------------------------------------------------------------------------

@Composable
fun SpeedDialChipsSection(
    speedDialItems: List<LocalItem>,
    mediaMetadata: dev.yeshua.player.models.MediaMetadata?,
    isPlaying: Boolean,
    navController: NavController,
    playerConnection: dev.yeshua.player.playback.PlayerConnection,
    menuState: dev.yeshua.player.ui.component.MenuState,
    haptic: HapticFeedback,
    scope: kotlinx.coroutines.CoroutineScope,
    modifier: Modifier = Modifier,
) {
    val distinctItems =
        remember(speedDialItems) {
            speedDialItems
                .distinctBy {
                    when (it) {
                        is Song -> "song_${it.id}"
                        is Album -> "album_${it.id}"
                        is Artist -> "artist_${it.id}"
                        is Playlist -> "playlist_${it.id}"
                    }
                }.take(20)
        }
    val speedDialSongs = remember(distinctItems) { distinctItems.filterIsInstance<Song>() }

    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        items(
            items = distinctItems,
            key = {
                when (it) {
                    is Song -> "song_${it.id}"
                    is Album -> "album_${it.id}"
                    is Artist -> "artist_${it.id}"
                    is Playlist -> "playlist_${it.id}"
                }
            },
        ) { item ->
            val isActive = item is Song && item.id == mediaMetadata?.id
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier =
                    Modifier
                        .width(76.dp)
                        .combinedClickable(
                            onClick = {
                                when (item) {
                                    is Song -> {
                                        if (isActive) {
                                            playerConnection.player.togglePlayPause()
                                        } else {
                                            val startIndex =
                                                speedDialSongs.indexOfFirst { it.id == item.id }
                                                    .takeIf { it >= 0 } ?: 0
                                            playerConnection.playQueue(
                                                ListQueue(
                                                    title = "",
                                                    items = speedDialSongs.map { it.toMediaItem() },
                                                    startIndex = startIndex,
                                                ),
                                            )
                                        }
                                    }

                                    is Album -> navController.navigate("album/${item.id}")
                                    is Artist -> navController.navigate("artist/${item.id}")
                                    is Playlist -> navController.navigate("local_playlist/${item.id}")
                                }
                            },
                            onLongClick = {
                                if (item is Song) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    menuState.show {
                                        SongMenu(
                                            originalSong = item,
                                            navController = navController,
                                            onDismiss = menuState::dismiss,
                                        )
                                    }
                                }
                            },
                        ),
            ) {
                Box(contentAlignment = Alignment.BottomEnd) {
                    AsyncImage(
                        model = item.thumbnailUrl?.displayArtworkUrl(256, 256),
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier =
                            Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                    if (isActive) {
                        Box(
                            modifier =
                                Modifier
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.tertiary),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.labelMedium,
                    color =
                        if (isActive) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Keep Listening as progress-ring cards. There is no per-song resume position in
// the schema, so the ring visualizes listening intensity: the song's recent play
// count relative to the hottest song in the section.
// ---------------------------------------------------------------------------

@Composable
fun KeepListeningRingSection(
    keepListening: List<LocalItem>,
    mediaMetadata: dev.yeshua.player.models.MediaMetadata?,
    isPlaying: Boolean,
    navController: NavController,
    playerConnection: dev.yeshua.player.playback.PlayerConnection,
    menuState: dev.yeshua.player.ui.component.MenuState,
    haptic: HapticFeedback,
    scope: kotlinx.coroutines.CoroutineScope,
    modifier: Modifier = Modifier,
) {
    val database = LocalDatabase.current
    val songIds = remember(keepListening) { keepListening.filterIsInstance<Song>().map { it.id } }
    val playCounts by produceState<Map<String, Int>>(initialValue = emptyMap(), songIds) {
        value =
            withContext(Dispatchers.IO) {
                if (songIds.isEmpty()) {
                    emptyMap()
                } else {
                    val from = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
                    database.playCountsForSongs(songIds, from).associate { it.songId to it.playCount }
                }
            }
    }
    val maxCount = remember(playCounts) { (playCounts.values.maxOrNull() ?: 0).coerceAtLeast(1) }

    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        items(
            items = keepListening,
            key = {
                when (it) {
                    is Song -> "song_${it.id}"
                    is Album -> "album_${it.id}"
                    is Artist -> "artist_${it.id}"
                    is Playlist -> "playlist_${it.id}"
                }
            },
        ) { item ->
            val isActive = item is Song && item.id == mediaMetadata?.id
            val intensity =
                if (item is Song) {
                    (playCounts[item.id] ?: 0).toFloat() / maxCount
                } else {
                    0f
                }
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 1.dp,
                modifier =
                    Modifier
                        .width(148.dp)
                        .clickable {
                            when (item) {
                                is Song -> {
                                    if (isActive) {
                                        playerConnection.player.togglePlayPause()
                                    } else {
                                        playerConnection.playQueue(
                                            ListQueue(
                                                title = "",
                                                items = listOf(item.toMediaItem()),
                                            ),
                                        )
                                    }
                                }

                                is Album -> navController.navigate("album/${item.id}")
                                is Artist -> navController.navigate("artist/${item.id}")
                                is Playlist -> navController.navigate("local_playlist/${item.id}")
                            }
                        },
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        AsyncImage(
                            model = item.thumbnailUrl?.displayArtworkUrl(256, 256),
                            contentDescription = item.title,
                            contentScale = ContentScale.Crop,
                            modifier =
                                Modifier
                                    .size(104.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                        )
                        if (item is Song && intensity > 0f) {
                            CircularProgressIndicator(
                                progress = { intensity.coerceIn(0.04f, 1f) },
                                strokeWidth = 4.dp,
                                color = MaterialTheme.colorScheme.tertiary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(116.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.labelLarge,
                        color =
                            if (isActive) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item is Song) {
                        val plays = playCounts[item.id] ?: 0
                        if (plays > 0) {
                            Text(
                                text = stringResource(R.string.home_keep_listening_plays, plays),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Forgotten Favorites as "rediscover" cards, stamped with the last-played date.
// ---------------------------------------------------------------------------

private fun formatLastPlayed(
    resources: android.content.res.Resources,
    lastPlayedMillis: Long,
    nowMillis: Long,
): String {
    val days = ((nowMillis - lastPlayedMillis) / (24 * 60 * 60 * 1000)).coerceAtLeast(0)
    return when {
        days < 1 -> resources.getString(R.string.time_today)
        days == 1L -> resources.getString(R.string.time_yesterday)
        days < 7 -> resources.getString(R.string.time_days_ago, days)
        days < 30 -> resources.getString(R.string.time_weeks_ago, days / 7)
        days < 365 -> resources.getString(R.string.time_months_ago, days / 30)
        else -> resources.getString(R.string.time_years_ago, days / 365)
    }
}

@Composable
fun ForgottenRediscoverSection(
    forgottenFavorites: List<Song>,
    mediaMetadata: dev.yeshua.player.models.MediaMetadata?,
    isPlaying: Boolean,
    navController: NavController,
    playerConnection: dev.yeshua.player.playback.PlayerConnection,
    menuState: dev.yeshua.player.ui.component.MenuState,
    haptic: HapticFeedback,
    modifier: Modifier = Modifier,
) {
    val database = LocalDatabase.current
    val songIds = remember(forgottenFavorites) { forgottenFavorites.map { it.id } }
    val lastPlayedBySong by produceState<Map<String, Long>>(initialValue = emptyMap(), songIds) {
        value =
            withContext(Dispatchers.IO) {
                if (songIds.isEmpty()) {
                    emptyMap()
                } else {
                    database.lastPlayedForSongs(songIds).associate { it.songId to it.lastPlayed }
                }
            }
    }
    val nowMillis = remember { System.currentTimeMillis() }
    val resources = LocalContext.current.resources
    val distinctSongs = remember(forgottenFavorites) { forgottenFavorites.distinctBy { it.id }.take(20) }

    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        items(
            items = distinctSongs,
            key = { it.id },
        ) { song ->
            val isActive = song.id == mediaMetadata?.id
            val lastPlayed = lastPlayedBySong[song.id]
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 1.dp,
                modifier =
                    Modifier
                        .width(168.dp)
                        .combinedClickable(
                            onClick = {
                                if (isActive) {
                                    playerConnection.player.togglePlayPause()
                                } else {
                                    playerConnection.playQueue(
                                        ListQueue(items = listOf(song.toMediaItem())),
                                    )
                                }
                            },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                menuState.show {
                                    SongMenu(
                                        originalSong = song,
                                        navController = navController,
                                        onDismiss = menuState::dismiss,
                                    )
                                }
                            },
                        ),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    AsyncImage(
                        model = song.song.thumbnailUrl?.displayArtworkUrl(256, 256),
                        contentDescription = song.title,
                        contentScale = ContentScale.Crop,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.labelLarge,
                        color =
                            if (isActive) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = song.artists.joinToString { it.name },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                    ) {
                        Text(
                            text =
                                if (lastPlayed != null) {
                                    stringResource(
                                        R.string.home_rediscover_last_played,
                                        formatLastPlayed(resources, lastPlayed, nowMillis),
                                    )
                                } else {
                                    stringResource(R.string.home_rediscover_buried)
                                },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
