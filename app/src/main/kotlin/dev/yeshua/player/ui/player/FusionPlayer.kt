/*
 * LunarTune (2026)
 * © cognitiveshadows03 — github.com/cognitiveshadows03
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package dev.yeshua.player.ui.player

/*
 * Fusion player design (PlayerDesignStyle.FUSION).
 * "YouTube Music and Spotify had a baby, but unique to Yeshua":
 *  - Spotify: full-bleed artwork atmosphere, large centered art card,
 *    big circular play button, time-labeled progress slider.
 *  - YouTube Music: swipe-up expansion, action row, Up Next preview.
 *  - Yeshua: dynamic accent colors from artwork, canvas visuals.
 */

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.navigation.NavController
import androidx.palette.graphics.Palette
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import dev.yeshua.player.R
import dev.yeshua.player.constants.ShowFusionUpNextKey
import dev.yeshua.player.constants.SliderStyle
import dev.yeshua.player.extensions.metadata
import dev.yeshua.player.extensions.togglePlayPause
import dev.yeshua.player.extensions.toggleRepeatMode
import dev.yeshua.player.models.MediaMetadata
import dev.yeshua.player.playback.PlayerConnection
import dev.yeshua.player.ui.component.BottomSheetPageState
import dev.yeshua.player.ui.component.BottomSheetState
import dev.yeshua.player.ui.component.MenuState
import dev.yeshua.player.ui.menu.PlayerMenu
import dev.yeshua.player.ui.theme.PlayerColorExtractor
import dev.yeshua.player.ui.utils.ShowMediaInfo
import dev.yeshua.player.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

@Composable
fun FusionPlayer(
    mediaMetadata: MediaMetadata,
    queueTitle: String?,
    playbackState: Int,
    isPlaying: Boolean,
    isLoading: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    currentSongLiked: Boolean,
    sliderPosition: Long?,
    position: Long,
    duration: Long,
    playerConnection: PlayerConnection,
    navController: NavController,
    state: BottomSheetState,
    menuState: MenuState,
    bottomSheetPageState: BottomSheetPageState,
    canvasPrimaryUrl: String?,
    canvasFallbackUrl: String?,
    onSliderValueChange: (Long) -> Unit,
    onSliderValueChangeFinished: () -> Unit,
    onCollapse: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenLyrics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val artworkUrl = mediaMetadata.thumbnailUrl
    val hasCanvas = !canvasPrimaryUrl.isNullOrBlank() || !canvasFallbackUrl.isNullOrBlank()

    // --- Dynamic accent color from artwork ---
    var extractedColors by remember { mutableStateOf<List<Color>>(emptyList()) }
    LaunchedEffect(artworkUrl) {
        val url = artworkUrl ?: return@LaunchedEffect
        val fallbackColor = 0xFF121212.toInt()
        runCatching {
            val request =
                ImageRequest.Builder(context)
                    .data(url)
                    .size(PlayerColorExtractor.Config.IMAGE_SIZE, PlayerColorExtractor.Config.IMAGE_SIZE)
                    .allowHardware(false)
                    .build()
            val result = withContext(Dispatchers.IO) { context.imageLoader.execute(request) }
            val bitmap = result.image?.toBitmap() ?: return@runCatching
            val palette =
                withContext(Dispatchers.Default) {
                    Palette.from(bitmap)
                        .maximumColorCount(PlayerColorExtractor.Config.MAX_COLOR_COUNT)
                        .resizeBitmapArea(PlayerColorExtractor.Config.BITMAP_AREA)
                        .generate()
                }
            extractedColors =
                PlayerColorExtractor.extractGradientColors(
                    palette = palette,
                    fallbackColor = fallbackColor,
                )
        }.onFailure { Timber.tag("FusionPlayer").w(it, "Artwork color extraction failed") }
    }
    val themePrimary = MaterialTheme.colorScheme.primary
    val accentTarget = extractedColors.firstOrNull() ?: themePrimary
    val accentColor by animateColorAsState(
        targetValue = accentTarget,
        animationSpec = tween(800),
        label = "fusionAccent",
    )

    // Theme-aware foreground colors (no hardcoded white/black).
    val isDark = isSystemInDarkTheme()
    val foreground = MaterialTheme.colorScheme.onSurface
    val secondaryForeground = MaterialTheme.colorScheme.onSurfaceVariant
    val scrimTop = if (isDark) Color.Black.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.35f)
    val scrimBottom = if (isDark) Color.Black.copy(alpha = 0.75f) else Color.White.copy(alpha = 0.55f)

    // Queue data for Up Next strip.
    val queueWindows by playerConnection.queueWindows.collectAsState()
    val currentWindowIndex by playerConnection.currentWindowIndex.collectAsState()
    val (showUpNext) = rememberPreference(ShowFusionUpNextKey, true)
    val upNextWindows =
        remember(queueWindows, currentWindowIndex) {
            queueWindows.drop(currentWindowIndex + 1).take(3)
        }

    val shuffleModeEnabled by playerConnection.shuffleModeEnabled.collectAsState()
    val repeatMode by playerConnection.repeatMode.collectAsState()

    val onMenuClick = {
        menuState.show {
            PlayerMenu(
                mediaMetadata = mediaMetadata,
                navController = navController,
                playerBottomSheetState = state,
                onShowDetailsDialog = {
                    bottomSheetPageState.show {
                        ShowMediaInfo(mediaMetadata.id)
                    }
                },
                onDismiss = menuState::dismiss,
            )
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 1. Full-bleed blurred artwork background + legibility scrim.
        BlurredArtwork(
            model = artworkUrl,
            radius = 48.dp,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(scrimTop, Color.Transparent, scrimBottom),
                        ),
                    ),
        )

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(
                        top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                        bottom = 16.dp,
                    ),
        ) {
            // 2. Top bar: collapse | context label | overflow.
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onCollapse) {
                    Icon(
                        painter = painterResource(R.drawable.expand_more),
                        contentDescription = stringResource(R.string.action_collapse),
                        tint = foreground,
                    )
                }
                Text(
                    text = queueTitle?.takeIf { it.isNotBlank() } ?: stringResource(R.string.now_playing),
                    style = MaterialTheme.typography.labelLarge,
                    color = secondaryForeground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onMenuClick) {
                    Icon(
                        painter = painterResource(R.drawable.more_vert),
                        contentDescription = stringResource(R.string.more_options),
                        tint = foreground,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // 3. Artwork card (72% width, canvas if available).
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                val cardSize = maxWidth * 0.72f
                Box(
                    modifier =
                        Modifier
                            .size(cardSize)
                            .shadow(24.dp, RoundedCornerShape(16.dp), clip = false)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    if (hasCanvas) {
                        CanvasArtworkPlayer(
                            primaryUrl = canvasPrimaryUrl,
                            fallbackUrl = canvasFallbackUrl,
                            isPlaying = isPlaying,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        AsyncImage(
                            model = artworkUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // 4. Title + artist (marquee on overflow).
            Text(
                text = mediaMetadata.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = foreground,
                maxLines = 1,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp)
                        .basicMarquee(),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = mediaMetadata.artists.joinToString { it.name },
                style = MaterialTheme.typography.bodyLarge,
                color = secondaryForeground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp)
                        .basicMarquee(),
            )

            Spacer(Modifier.height(12.dp))

            // 5. Progress slider (Spotify arrangement: elapsed left, total right).
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatDuration(sliderPosition ?: position),
                    style = MaterialTheme.typography.labelSmall,
                    color = secondaryForeground,
                )
                Box(modifier = Modifier.weight(1f)) {
                    PlayerSlider(
                        sliderStyle = SliderStyle.Standard,
                        sliderPosition = sliderPosition,
                        position = position,
                        duration = duration,
                        isPlaying = isPlaying,
                        textButtonColor = accentColor,
                        onValueChange = onSliderValueChange,
                        onValueChangeFinished = onSliderValueChangeFinished,
                    )
                }
                Text(
                    text = formatDuration(duration),
                    style = MaterialTheme.typography.labelSmall,
                    color = secondaryForeground,
                )
            }

            Spacer(Modifier.height(8.dp))

            // 6. Main controls: shuffle | prev | PLAY | next | repeat.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { playerConnection.player.shuffleModeEnabled = !shuffleModeEnabled },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.shuffle),
                        contentDescription = stringResource(R.string.action_shuffle),
                        tint = if (shuffleModeEnabled) accentColor else secondaryForeground,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = { playerConnection.seekToPrevious() },
                    enabled = canSkipPrevious,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.skip_previous),
                        contentDescription = stringResource(R.string.action_previous),
                        tint = foreground,
                        modifier = Modifier.size(40.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                FilledIconButton(
                    onClick = {
                        if (playbackState == Player.STATE_ENDED) {
                            playerConnection.player.seekTo(0, 0)
                            playerConnection.player.playWhenReady = true
                        } else {
                            playerConnection.player.togglePlayPause()
                        }
                    },
                    colors =
                        IconButtonDefaults.filledIconButtonColors(
                            containerColor = accentColor,
                            contentColor = Color.White,
                        ),
                    modifier = Modifier.size(72.dp),
                    shape = CircleShape,
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(32.dp),
                        )
                    } else {
                        Icon(
                            painter =
                                painterResource(
                                    if (isPlaying) R.drawable.pause else R.drawable.play,
                                ),
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                IconButton(
                    onClick = { playerConnection.seekToNext() },
                    enabled = canSkipNext,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.skip_next),
                        contentDescription = stringResource(R.string.next),
                        tint = foreground,
                        modifier = Modifier.size(40.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                val repeatTint =
                    if (repeatMode != Player.REPEAT_MODE_OFF) accentColor else secondaryForeground
                IconButton(
                    onClick = { playerConnection.player.toggleRepeatMode() },
                ) {
                    Icon(
                        painter =
                            painterResource(
                                if (repeatMode == Player.REPEAT_MODE_ONE) R.drawable.repeat_one
                                else R.drawable.repeat,
                            ),
                        contentDescription =
                            stringResource(
                                if (repeatMode == Player.REPEAT_MODE_ONE) R.string.action_repeat_one
                                else R.string.action_repeat,
                            ),
                        tint = repeatTint,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // 7. Action row (YT Music style).
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { playerConnection.toggleLike() }) {
                    Icon(
                        painter =
                            painterResource(
                                if (currentSongLiked) R.drawable.favorite else R.drawable.favorite_border,
                            ),
                        contentDescription = stringResource(R.string.action_like),
                        tint = if (currentSongLiked) accentColor else secondaryForeground,
                        modifier = Modifier.size(26.dp),
                    )
                }
                IconButton(onClick = onMenuClick) {
                    Icon(
                        painter = painterResource(R.drawable.playlist_add),
                        contentDescription = stringResource(R.string.add_to_playlist),
                        tint = secondaryForeground,
                        modifier = Modifier.size(26.dp),
                    )
                }
                IconButton(onClick = onOpenQueue) {
                    Icon(
                        painter = painterResource(R.drawable.queue_music),
                        contentDescription = stringResource(R.string.queue),
                        tint = secondaryForeground,
                        modifier = Modifier.size(26.dp),
                    )
                }
                IconButton(onClick = onOpenLyrics) {
                    Icon(
                        painter = painterResource(R.drawable.lyrics),
                        contentDescription = stringResource(R.string.lyrics),
                        tint = secondaryForeground,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // 8. Up Next strip.
            if (showUpNext && upNextWindows.isNotEmpty()) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.up_next),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = foreground,
                    )
                    Text(
                        text = stringResource(R.string.queue),
                        style = MaterialTheme.typography.labelMedium,
                        color = accentColor,
                        modifier = Modifier.clickable(onClick = onOpenQueue),
                    )
                }
                Spacer(Modifier.height(8.dp))
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(horizontal = 24.dp),
                ) {
                    items(
                        items = upNextWindows,
                        key = { it.uid.hashCode() },
                    ) { window ->
                        val itemMetadata = window.mediaItem.metadata
                        Column(
                            modifier =
                                Modifier
                                    .width(96.dp)
                                    .clickable(onClick = onOpenQueue),
                        ) {
                            AsyncImage(
                                model = itemMetadata?.thumbnailUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier =
                                    Modifier
                                        .size(96.dp)
                                        .clip(RoundedCornerShape(12.dp)),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = itemMetadata?.title.orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                color = foreground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = itemMetadata?.artists.orEmpty().joinToString { it.name },
                                style = MaterialTheme.typography.labelSmall,
                                color = secondaryForeground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))
        }
    }
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0L || ms == C.TIME_UNSET) return "0:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
