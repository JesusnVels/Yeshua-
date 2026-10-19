/*
 * LunarTune (2026)
 * © cognitiveshadows03 — github.com/cognitiveshadows03
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Wrong-match reporting flow adapted from Stash (GPL-3.0).
 */

package dev.yeshua.player.ui.menu

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.yeshua.player.LocalDatabase
import dev.yeshua.player.R
import dev.yeshua.player.db.entities.SetVideoIdEntity
import dev.yeshua.player.db.entities.Song
import dev.yeshua.player.playlistimport.ImportReplacementSearcher
import dev.yeshua.player.ui.component.SongListItem

/**
 * Lets the user flag a wrong song-to-YouTube-video match and pick the correct
 * video from alternative search results for "<title> <artist>".
 *
 * The pick is stored in the `set_video_id` table, which
 * [dev.yeshua.player.utils.YTPlayerUtils.playerResponseForPlayback] consults
 * before resolving a stream, so the correction applies to every future play.
 */
@Composable
fun WrongMatchPickerDialog(
    originalSong: Song,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val coroutineScope = rememberCoroutineScope()
    val searcher = remember { ImportReplacementSearcher() }

    var isSearching by remember(originalSong.id) { mutableStateOf(true) }
    var candidates by remember(originalSong.id) { mutableStateOf(emptyList<Song>()) }
    var selected by remember(originalSong.id) { mutableStateOf<Song?>(null) }
    var existingOverride by remember(originalSong.id) { mutableStateOf<SetVideoIdEntity?>(null) }

    LaunchedEffect(originalSong.id) {
        val loaded =
            withContext(Dispatchers.IO) {
                val override = database.getSetVideoId(originalSong.id)
                val results = searcher.searchYouTube(buildWrongMatchQuery(originalSong))
                override to results
            }
        existingOverride = loaded.first
        candidates = loaded.second
        isSearching = false
    }

    fun saveOverride(pickedVideoId: String?) {
        coroutineScope.launch {
            withContext(Dispatchers.IO) {
                if (pickedVideoId == null) {
                    database.deleteSetVideoId(originalSong.id)
                } else {
                    database.insert(
                        SetVideoIdEntity(
                            videoId = originalSong.id,
                            setVideoId = pickedVideoId,
                        ),
                    )
                }
            }
            val message =
                if (pickedVideoId == null) {
                    context.getString(R.string.wrong_match_reset_done)
                } else {
                    context.getString(R.string.wrong_match_saved)
                }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(R.string.wrong_match_title),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = buildWrongMatchQuery(originalSong),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(12.dp))

                if (existingOverride != null) {
                    ListItem(
                        headlineContent = { Text(text = stringResource(R.string.wrong_match_current_override)) },
                        supportingContent = {
                            Text(
                                text = existingOverride?.setVideoId.orEmpty(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingContent = {
                            Icon(
                                painter = painterResource(R.drawable.warning),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                            )
                        },
                        trailingContent = {
                            TextButton(onClick = { saveOverride(null) }) {
                                Text(text = stringResource(R.string.wrong_match_reset))
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                }

                when {
                    isSearching -> {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(160.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(32.dp))
                                Text(
                                    text = stringResource(R.string.wrong_match_searching),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    candidates.isEmpty() -> {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(120.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.wrong_match_no_results),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    else -> {
                        LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                            items(candidates, key = { it.id }) { candidate ->
                                SongListItem(
                                    song = candidate,
                                    isSelected = selected?.id == candidate.id,
                                    showDownloadIcon = false,
                                    trailingContent = {
                                        RadioButton(
                                            selected = selected?.id == candidate.id,
                                            onClick = {
                                                selected = if (selected?.id == candidate.id) null else candidate
                                            },
                                        )
                                    },
                                    modifier =
                                        Modifier.clickable {
                                            selected = if (selected?.id == candidate.id) null else candidate
                                        },
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(text = stringResource(android.R.string.cancel))
                    }
                    Button(
                        onClick = {
                            val picked = selected ?: return@Button
                            saveOverride(picked.id)
                        },
                        enabled = selected != null && !isSearching,
                    ) {
                        Text(text = stringResource(R.string.wrong_match_use_this))
                    }
                }
            }
        }
    }
}

private fun buildWrongMatchQuery(song: Song): String {
    val title = song.song.title.trim()
    val artists = song.artists.joinToString(" ") { it.name.trim() }.trim()
    return if (artists.isEmpty()) title else "$title $artists"
}
