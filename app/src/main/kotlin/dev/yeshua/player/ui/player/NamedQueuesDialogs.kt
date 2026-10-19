/*
 * LunarTune (2026)
 * © cognitiveshadows03 — github.com/cognitiveshadows03
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Named queue save/load UI adapted from RiPlay (GPL-3.0).
 */

package dev.yeshua.player.ui.player

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.yeshua.player.LocalDatabase
import dev.yeshua.player.LocalPlayerConnection
import dev.yeshua.player.R
import dev.yeshua.player.db.entities.NamedQueueEntity
import dev.yeshua.player.db.entities.toMediaMetadata
import dev.yeshua.player.db.entities.toNamedQueueItemEntity
import dev.yeshua.player.extensions.toMediaItem
import dev.yeshua.player.models.MediaMetadata
import dev.yeshua.player.playback.queues.ListQueue
import dev.yeshua.player.ui.component.ListDialog
import dev.yeshua.player.ui.component.TextFieldDialog

/** Compact save/load entry row shown above the queue list. */
@Composable
fun NamedQueueToolbarRow(
    canSave: Boolean,
    onSaveQueue: () -> Unit,
    onOpenSavedQueues: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalButton(
            onClick = onSaveQueue,
            enabled = canSave,
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                painter = painterResource(R.drawable.queue_music),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.save_queue),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        OutlinedButton(
            onClick = onOpenSavedQueues,
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                painter = painterResource(R.drawable.bookmark),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.saved_queues),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** Persists the supplied items under a user-chosen name. */
@Composable
fun SaveNamedQueueDialog(
    initialName: String?,
    itemsProvider: () -> List<MediaMetadata>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val coroutineScope = rememberCoroutineScope()

    TextFieldDialog(
        icon = {
            Icon(
                painter = painterResource(R.drawable.queue_music),
                contentDescription = null,
            )
        },
        title = { Text(text = stringResource(R.string.save_queue)) },
        placeholder = { Text(text = stringResource(R.string.queue_name)) },
        initialTextFieldValue = TextFieldValue(initialName.orEmpty()),
        isInputValid = { it.trim().isNotEmpty() && itemsProvider().isNotEmpty() },
        onDismiss = onDismiss,
        onDone = { rawName ->
            val name = rawName.trim()
            val items = itemsProvider()
            if (name.isEmpty() || items.isEmpty()) return@TextFieldDialog
            coroutineScope.launch(Dispatchers.IO) {
                val queue = NamedQueueEntity(name = name)
                database.withTransaction {
                    insertNamedQueue(queue)
                    insertNamedQueueItems(
                        items.mapIndexed { index, metadata ->
                            metadata.toNamedQueueItemEntity(queue.id, index)
                        },
                    )
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, R.string.queue_saved, Toast.LENGTH_SHORT).show()
                    onDismiss()
                }
            }
        },
    )
}

/** Lists saved named queues with load / rename / delete actions. */
@Composable
fun NamedQueuesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val coroutineScope = rememberCoroutineScope()
    val queues by database.namedQueues().collectAsState(initial = emptyList())

    var renamingQueue by remember { mutableStateOf<NamedQueueEntity?>(null) }
    var itemCounts by remember { mutableStateOf(emptyMap<String, Int>()) }

    LaunchedEffect(queues) {
        itemCounts =
            withContext(Dispatchers.IO) {
                queues.associate { it.id to database.namedQueueItemCount(it.id) }
            }
    }

    renamingQueue?.let { queue ->
        RenameNamedQueueDialog(
            queue = queue,
            onDismiss = { renamingQueue = null },
        )
    }

    ListDialog(onDismiss = onDismiss) {
        item {
            ListItem(
                headlineContent = { Text(text = stringResource(R.string.saved_queues)) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        if (queues.isEmpty()) {
            item {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.no_saved_queues),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(queues, key = { it.id }) { queue ->
            ListItem(
                headlineContent = {
                    Text(
                        text = queue.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = {
                    val count = itemCounts[queue.id]
                    if (count != null) {
                        Text(text = pluralStringResource(R.plurals.n_song, count, count))
                    }
                },
                leadingContent = {
                    Icon(
                        painter = painterResource(R.drawable.queue_music),
                        contentDescription = null,
                    )
                },
                trailingContent = {
                    Row {
                        IconButton(onClick = { renamingQueue = queue }) {
                            Icon(
                                painter = painterResource(R.drawable.edit),
                                contentDescription = stringResource(R.string.rename_queue),
                            )
                        }
                        IconButton(
                            onClick = {
                                coroutineScope.launch(Dispatchers.IO) {
                                    database.deleteNamedQueue(queue.id)
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, R.string.queue_deleted, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.delete),
                                contentDescription = stringResource(R.string.delete),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
                modifier =
                    Modifier.clickable {
                        coroutineScope.launch(Dispatchers.IO) {
                            val mediaItems =
                                database
                                    .namedQueueItems(queue.id)
                                    .map { it.toMediaMetadata().toMediaItem() }
                            withContext(Dispatchers.Main) {
                                onDismiss()
                                if (mediaItems.isNotEmpty()) {
                                    playerConnection.playQueue(
                                        ListQueue(
                                            title = queue.name,
                                            items = mediaItems,
                                        ),
                                    )
                                    Toast.makeText(context, R.string.queue_loaded, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}

@Composable
private fun RenameNamedQueueDialog(
    queue: NamedQueueEntity,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val coroutineScope = rememberCoroutineScope()

    TextFieldDialog(
        icon = {
            Icon(
                painter = painterResource(R.drawable.edit),
                contentDescription = null,
            )
        },
        title = { Text(text = stringResource(R.string.rename_queue)) },
        placeholder = { Text(text = stringResource(R.string.queue_name)) },
        initialTextFieldValue = TextFieldValue(queue.name),
        isInputValid = { it.trim().isNotEmpty() },
        onDismiss = onDismiss,
        onDone = { rawName ->
            val name = rawName.trim()
            if (name.isEmpty()) return@TextFieldDialog
            coroutineScope.launch(Dispatchers.IO) {
                database.renameNamedQueue(queue.id, name)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, R.string.queue_renamed, Toast.LENGTH_SHORT).show()
                    onDismiss()
                }
            }
        },
    )
}
