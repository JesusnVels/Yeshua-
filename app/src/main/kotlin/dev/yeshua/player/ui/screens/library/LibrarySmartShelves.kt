/*
 * LunarTune (2026)
 * © cognitiveshadows03 — github.com/cognitiveshadows03
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package dev.yeshua.player.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import dev.yeshua.player.LocalDatabase
import dev.yeshua.player.LocalPlayerConnection
import dev.yeshua.player.R
import dev.yeshua.player.db.entities.Song
import dev.yeshua.player.extensions.toMediaItem
import dev.yeshua.player.playback.queues.ListQueue
import dev.yeshua.player.ui.theme.YeshuaDisplayFontFamily
import dev.yeshua.player.ui.utils.displayArtworkUrl
import java.util.concurrent.TimeUnit

/**
 * Smart shelves pinned above the library filter tabs, computed entirely from the
 * existing stats tables — zero new data collection:
 * - "On repeat": most-played songs of the last 30 days.
 * - "Forgotten": songs with no plays in the last 90 days (least-recently played first).
 * - "New discoveries": songs first played in the last 14 days (most recent first).
 *
 * Hidden entirely when the library has no listening history.
 */
@Composable
fun LibrarySmartShelves(
    navController: NavController,
    modifier: Modifier = Modifier,
) {
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current
    val now = remember { System.currentTimeMillis() }

    val onRepeat by
        database
            .mostPlayedSongs(
                fromTimeStamp = now - TimeUnit.DAYS.toMillis(30),
                limit = 10,
            ).collectAsStateWithLifecycle(initialValue = emptyList())
    val forgotten by
        database
            .forgottenShelfSongs(
                beforeTimeStamp = now - TimeUnit.DAYS.toMillis(90),
                limit = 10,
            ).collectAsStateWithLifecycle(initialValue = emptyList())
    val discoveries by
        database
            .newDiscoveryShelfSongs(
                fromTimeStamp = now - TimeUnit.DAYS.toMillis(14),
                limit = 10,
            ).collectAsStateWithLifecycle(initialValue = emptyList())

    if (onRepeat.isEmpty() && forgotten.isEmpty() && discoveries.isEmpty()) return

    fun playShelf(
        songs: List<Song>,
        startIndex: Int,
    ) {
        if (songs.isEmpty()) return
        playerConnection?.playQueue(
            ListQueue(
                title = "",
                items = songs.map { it.toMediaItem() },
                startIndex = startIndex.coerceIn(0, songs.size - 1),
            ),
        )
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (onRepeat.isNotEmpty()) {
            SmartShelfRow(
                title = stringResource(R.string.library_shelf_on_repeat),
                songs = onRepeat,
                onSongClick = { index -> playShelf(onRepeat, index) },
            )
            Spacer(Modifier.height(4.dp))
        }
        if (forgotten.isNotEmpty()) {
            SmartShelfRow(
                title = stringResource(R.string.library_shelf_forgotten),
                songs = forgotten,
                onSongClick = { index -> playShelf(forgotten, index) },
            )
            Spacer(Modifier.height(4.dp))
        }
        if (discoveries.isNotEmpty()) {
            SmartShelfRow(
                title = stringResource(R.string.library_shelf_discoveries),
                songs = discoveries,
                onSongClick = { index -> playShelf(discoveries, index) },
            )
        }
    }
}

@Composable
private fun SmartShelfRow(
    title: String,
    songs: List<Song>,
    onSongClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = YeshuaDisplayFontFamily,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.tertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            itemsIndexed(
                items = songs,
                key = { _, song -> "shelf_${song.id}" },
            ) { index, song ->
                Column(
                    modifier =
                        Modifier
                            .width(112.dp)
                            .clickable { onSongClick(index) },
                ) {
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
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        minLines = 2,
                    )
                    Text(
                        text = song.artists.joinToString { it.name },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
