/*
 * LunarTune (2026)
 * © cognitiveshadows03 — github.com/cognitiveshadows03
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * UnifiedLoginHub.kt — single destination for all music-client auth.
 * Route: "music_login_hub"
 * Wire into NavigationBuilder.kt: composable("music_login_hub") { UnifiedLoginHub(navController) }
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package dev.yeshua.player.ui.screens.login

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.yeshua.player.LocalPlayerAwareWindowInsets
import dev.yeshua.player.R
import dev.yeshua.player.constants.AccountNameKey
import dev.yeshua.player.constants.InnerTubeCookieKey
import dev.yeshua.player.constants.LastFMUsernameKey
import dev.yeshua.player.constants.ListenBrainzEnabledKey
import dev.yeshua.player.constants.ListenBrainzTokenKey
import dev.yeshua.player.ui.component.IconButton
import dev.yeshua.player.ui.utils.appBarScrollBehavior
import dev.yeshua.player.ui.utils.backToMain
import dev.yeshua.player.utils.rememberPreference
import moe.rukamori.archivetune.innertube.utils.hasYouTubeLoginCookie

// ────────────────────────────────────────────────────────────────────────────
// Data model
// ────────────────────────────────────────────────────────────────────────────

private data class LoginCard(
    val id: String,
    val label: String,
    val subtitle: String,
    val icon: ImageVector,
    val accentColor: Color,
    val connected: Boolean,
    val connectedLabel: String,
    val route: String,
)

// ────────────────────────────────────────────────────────────────────────────
// Screen
// ────────────────────────────────────────────────────────────────────────────

@Composable
fun UnifiedLoginHub(navController: NavController) {
    val scrollBehavior = appBarScrollBehavior()

    // Observe each service's auth state from DataStore preferences
    val (innerTubeCookie) = rememberPreference(InnerTubeCookieKey, "")
    val (accountName) = rememberPreference(AccountNameKey, "")
    val (lastFmUsername) = rememberPreference(LastFMUsernameKey, "")
    val (listenBrainzToken) = rememberPreference(ListenBrainzTokenKey, "")
    val (listenBrainzEnabled) = rememberPreference(ListenBrainzEnabledKey, false)

    val isYouTubeConnected = remember(innerTubeCookie) { hasYouTubeLoginCookie(innerTubeCookie) }
    val isLastFmConnected = remember(lastFmUsername) { lastFmUsername.isNotBlank() }
    val isListenBrainzConnected = remember(listenBrainzToken, listenBrainzEnabled) {
        listenBrainzEnabled && listenBrainzToken.isNotBlank()
    }

    // Build card list — order: primary streaming source first, scrobblers below
    val cards = remember(
        isYouTubeConnected, accountName,
        isLastFmConnected, lastFmUsername,
        isListenBrainzConnected,
    ) {
        listOf(
            LoginCard(
                id = "youtube",
                label = "YouTube Music",
                subtitle = if (isYouTubeConnected && accountName.isNotBlank())
                    accountName else "Stream your YouTube Music library",
                icon = Icons.Rounded.MusicNote,
                accentColor = Color(0xFFFF0033),
                connected = isYouTubeConnected,
                connectedLabel = "Connected",
                route = "account",
            ),
            LoginCard(
                id = "spotify",
                label = "Spotify",
                subtitle = "Import playlists & browse your Spotify library",
                icon = Icons.Rounded.AccountCircle,
                accentColor = Color(0xFF1DB954),
                connected = false, // Spotify auth lives in SpotifyAccountViewModel
                connectedLabel = "Connected",
                route = "settings/integration",
            ),
            LoginCard(
                id = "lastfm",
                label = "Last.fm",
                subtitle = if (isLastFmConnected) lastFmUsername else "Scrobble your plays to Last.fm",
                icon = Icons.Rounded.Person,
                accentColor = Color(0xFFD51007),
                connected = isLastFmConnected,
                connectedLabel = if (isLastFmConnected) lastFmUsername else "Not connected",
                route = "settings/lastfm",
            ),
            LoginCard(
                id = "listenbrainz",
                label = "ListenBrainz",
                subtitle = if (isListenBrainzConnected)
                    "Scrobbling active" else "Open-source scrobbling via ListenBrainz",
                icon = Icons.Rounded.MusicNote,
                accentColor = Color(0xFFEB743B),
                connected = isListenBrainzConnected,
                connectedLabel = "Active",
                route = "settings/integration",
            ),
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Music Accounts") },
                navigationIcon = {
                    IconButton(
                        onClick = navController::navigateUp,
                        onLongClick = navController::backToMain,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_back),
                            contentDescription = stringResource(R.string.back_button_desc),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { paddingValues ->
        LazyColumn(
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding() + 8.dp,
                bottom = paddingValues.calculateBottomPadding() +
                    LocalPlayerAwareWindowInsets.current
                        .only(WindowInsetsSides.Bottom)
                        .getBottom(androidx.compose.ui.unit.Density(1f)).dp + 16.dp,
                start = 16.dp,
                end = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(
                    WindowInsets.systemBars.only(WindowInsetsSides.Horizontal),
                ),
        ) {
            item {
                Text(
                    text = "Connect your services to unlock your full music library, " +
                        "scrobbling, and social features.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
                Spacer(Modifier.height(4.dp))
            }

            items(cards, key = { it.id }) { card ->
                ServiceLoginCard(
                    card = card,
                    onNavigate = { navController.navigate(card.route) },
                )
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Individual service card
// ────────────────────────────────────────────────────────────────────────────

@Composable
private fun ServiceLoginCard(
    card: LoginCard,
    onNavigate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Accent icon badge
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(card.accentColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = card.icon,
                    contentDescription = null,
                    tint = card.accentColor,
                    modifier = Modifier.size(26.dp),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = card.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = card.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (card.connected) {
                        FilterChip(
                            selected = true,
                            onClick = {},
                            label = {
                                Text(
                                    text = card.connectedLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = card.accentColor.copy(alpha = 0.15f),
                                selectedLabelColor = card.accentColor,
                                selectedLeadingIconColor = card.accentColor,
                            ),
                        )
                    }
                }
            }

            // Action button
            if (card.connected) {
                OutlinedButton(
                    onClick = onNavigate,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text("Manage", style = MaterialTheme.typography.labelMedium)
                }
            } else {
                Button(
                    onClick = onNavigate,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = card.accentColor,
                        contentColor = Color.White,
                    ),
                ) {
                    Text("Connect", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
