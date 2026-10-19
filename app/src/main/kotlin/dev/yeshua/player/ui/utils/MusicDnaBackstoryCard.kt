/*
 * LunarTune (2026)
 * © cognitiveshadows03 — github.com/cognitiveshadows03
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package dev.yeshua.player.ui.utils

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dagger.hilt.android.EntryPointAccessors
import dev.yeshua.player.R
import dev.yeshua.player.di.MusicDnaEntryPoint
import dev.yeshua.player.musicdna.MusicDnaBackstory

/**
 * Music DNA section: a short artist/track backstory card. Hidden entirely when
 * there is nothing cached and the lookup fails (e.g. offline).
 */
@Composable
fun MusicDnaBackstoryCard(
    artistName: String,
    trackTitle: String,
    modifier: Modifier = Modifier,
) {
    if (artistName.isBlank() && trackTitle.isBlank()) return

    val context = LocalContext.current
    val repository =
        remember(context) {
            EntryPointAccessors
                .fromApplication(context.applicationContext, MusicDnaEntryPoint::class.java)
                .musicDnaRepository()
        }
    val backstory by produceState<MusicDnaBackstory?>(initialValue = null, artistName, trackTitle) {
        value = runCatching { repository.backstory(artistName, trackTitle) }.getOrNull()
    }
    val resolved = backstory ?: return

    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.info),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.music_dna),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Text(
                text = resolved.text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = stringResource(R.string.music_dna_source, resolved.source),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}
