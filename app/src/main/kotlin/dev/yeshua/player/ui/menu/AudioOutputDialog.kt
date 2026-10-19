/*
 * LunarTune (2026)
 * © cognitiveshadows03 — github.com/cognitiveshadows03
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package dev.yeshua.player.ui.menu

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
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
 * Per-app audio output picker.
 *
 * Uses the framework [AudioManager] output-device APIs rather than
 * androidx.mediarouter (which is only packaged in the gms flavor), so this
 * works on every build with no new dependencies. [AudioManager.setPreferredDevice]
 * routes only this app's audio, leaving other apps untouched.
 */
@Composable
fun AudioOutputDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val audioManager =
        remember(context) {
            context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var devices by remember { mutableStateOf(listOutputDevices(audioManager)) }
    var selectedDeviceId by
        remember {
            mutableStateOf(currentPreferredDeviceId(audioManager))
        }

    DisposableEffect(audioManager) {
        val callback =
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
                    devices = listOutputDevices(audioManager)
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
                    devices = listOutputDevices(audioManager)
                    if (selectedDeviceId != null && devices.none { it.id == selectedDeviceId }) {
                        selectedDeviceId = null
                    }
                }
            }
        audioManager.registerAudioDeviceCallback(callback, mainHandler)
        onDispose { audioManager.unregisterAudioDeviceCallback(callback) }
    }

    fun select(device: AudioDeviceInfo?) {
        audioManager.setPreferredDevice(device)
        selectedDeviceId = device?.id
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
                        AudioOutputRow(
                            title = stringResource(R.string.audio_output_system_default),
                            subtitle = null,
                            iconRes = R.drawable.volume_up,
                            selected = selectedDeviceId == null,
                            onClick = { select(null) },
                        )
                    }
                    items(devices, key = { it.id }) { device ->
                        val fallbackName = stringResource(R.string.audio_device_other)
                        AudioOutputRow(
                            title = deviceDisplayName(device, fallbackName),
                            subtitle = stringResource(deviceTypeLabelRes(device.type)),
                            iconRes = deviceIconRes(device.type),
                            selected = selectedDeviceId == device.id,
                            onClick = { select(device) },
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

private fun listOutputDevices(audioManager: AudioManager): List<AudioDeviceInfo> =
    audioManager
        .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .filter { it.type in OUTPUT_DEVICE_TYPES }
        .sortedBy { OUTPUT_DEVICE_TYPES.indexOf(it.type) }

private fun currentPreferredDeviceId(audioManager: AudioManager): Int? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        runCatching {
            audioManager.getPreferredDeviceForStrategy(AudioManager.STRATEGY_MEDIA)?.id
        }.getOrNull()
    } else {
        null
    }

private fun deviceDisplayName(
    device: AudioDeviceInfo,
    fallbackName: String,
): String {
    val productName = device.productName?.toString()?.trim().orEmpty()
    return productName.ifBlank { fallbackName }
}

private fun deviceTypeLabelRes(type: Int): Int =
    when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> R.string.audio_device_speaker
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        -> R.string.audio_device_bluetooth
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        -> R.string.audio_device_wired
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        -> R.string.audio_device_usb
        else -> R.string.audio_device_other
    }

private fun deviceIconRes(type: Int): Int =
    when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        -> R.drawable.bluetooth
        else -> R.drawable.volume_up
    }

private val OUTPUT_DEVICE_TYPES =
    listOf(
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_DOCK,
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
    )
