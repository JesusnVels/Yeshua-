/*
 * LunarTune (2026)
 * © cognitiveshadows03 — github.com/cognitiveshadows03
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * In-player audio output picker. Uses the public framework MediaRouter2 APIs
 * (API 30+), so it works on every build flavor with no new dependencies.
 */

package dev.yeshua.player.ui.menu

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRouter2
import android.media.MediaRoute2Info
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.yeshua.player.R

/**
 * Per-app audio output picker (requires API 30+; the caller gates this).
 *
 * Lists the system media routes via [MediaRouter2] and transfers this app's
 * playback to the chosen one, leaving other apps untouched.
 */
@Composable
fun AudioOutputDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val router = remember(context) { MediaRouter2.getInstance(context) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var routes by remember { mutableStateOf(listOutputRoutes(router)) }
    // The route the user last picked in this dialog; null = system default.
    var selectedRouteId by remember { mutableStateOf<String?>(null) }

    DisposableEffect(router) {
        val audioManager =
            context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val deviceCallback =
            object : AudioDeviceCallback() {
                private fun refresh() {
                    routes = listOutputRoutes(router)
                }

                override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
                    refresh()
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
                    refresh()
                }
            }
        audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
        onDispose { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
    }

    fun select(route: MediaRoute2Info?) {
        val target =
            route ?: routes.firstOrNull { it.type == MediaRoute2Info.TYPE_BUILTIN_SPEAKER }
                ?: routes.firstOrNull()
        if (target != null) {
            runCatching { router.transferTo(target) }
            selectedRouteId = target.id
        }
        onDismiss()
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
                    text = stringResource(R.string.audio_output),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.audio_output_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    item {
                        val defaultRoute =
                            routes.firstOrNull {
                                it.type == MediaRoute2Info.TYPE_BUILTIN_SPEAKER
                            }
                        AudioOutputRow(
                            title = stringResource(R.string.audio_output_system_default),
                            subtitle = null,
                            iconRes = R.drawable.volume_up,
                            selected =
                                selectedRouteId == null ||
                                    selectedRouteId == defaultRoute?.id,
                            onClick = { select(null) },
                        )
                    }
                    items(routes, key = { it.id }) { route ->
                        val fallbackName = stringResource(R.string.audio_device_other)
                        AudioOutputRow(
                            title = route.name?.toString()?.trim().orEmpty()
                                .ifBlank { fallbackName },
                            subtitle = stringResource(routeTypeLabelRes(route.type)),
                            iconRes = routeIconRes(route.type),
                            selected = selectedRouteId == route.id,
                            onClick = { select(route) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioOutputRow(
    title: String,
    subtitle: String?,
    iconRes: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = subtitle?.let { { Text(text = it) } },
        leadingContent = {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
            )
        },
        trailingContent = {
            RadioButton(
                selected = selected,
                onClick = onClick,
            )
        },
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

private fun listOutputRoutes(router: MediaRouter2): List<MediaRoute2Info> =
    router.routes
        .filter { it.type in OUTPUT_ROUTE_TYPES }
        .sortedBy { OUTPUT_ROUTE_TYPES.indexOf(it.type) }

private fun routeTypeLabelRes(type: Int): Int =
    when (type) {
        MediaRoute2Info.TYPE_BUILTIN_SPEAKER -> R.string.audio_device_speaker
        MediaRoute2Info.TYPE_BLUETOOTH_A2DP -> R.string.audio_device_bluetooth
        MediaRoute2Info.TYPE_WIRED_HEADSET,
        MediaRoute2Info.TYPE_WIRED_HEADPHONES,
        -> R.string.audio_device_wired
        MediaRoute2Info.TYPE_USB_DEVICE,
        MediaRoute2Info.TYPE_USB_HEADSET,
        -> R.string.audio_device_usb
        else -> R.string.audio_device_other
    }

private fun routeIconRes(type: Int): Int =
    when (type) {
        MediaRoute2Info.TYPE_BLUETOOTH_A2DP -> R.drawable.bluetooth
        else -> R.drawable.volume_up
    }

private val OUTPUT_ROUTE_TYPES =
    listOf(
        MediaRoute2Info.TYPE_BUILTIN_SPEAKER,
        MediaRoute2Info.TYPE_WIRED_HEADSET,
        MediaRoute2Info.TYPE_WIRED_HEADPHONES,
        MediaRoute2Info.TYPE_BLUETOOTH_A2DP,
        MediaRoute2Info.TYPE_USB_DEVICE,
        MediaRoute2Info.TYPE_USB_HEADSET,
        MediaRoute2Info.TYPE_HDMI,
        MediaRoute2Info.TYPE_DOCK,
    )
