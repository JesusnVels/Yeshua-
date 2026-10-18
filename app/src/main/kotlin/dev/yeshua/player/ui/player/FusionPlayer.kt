/*
 * LunarTune (2026)
 * © cognitiveshadows03 — github.com/cognitiveshadows03
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package dev.yeshua.player.ui.player

/*
 * Fusion player (PlayerDesignStyle.FUSION).
 *
 * Design language:
 *   Spotify   — full-bleed artwork atmosphere, dominant accent color from palette,
 *               elapsed/total timestamp flanking the seek bar, large circular play button.
 *   YT Music  — "Playing from" context chip, Up Next peek strip, action row icons.
 *   Yeshua    — artwork breathes while playing, colored glow halo around the card,
 *               accent tint bleeds into the scrim, per-action labels in the action row,
 *               canvas visuals, smooth cross-song color transitions.
 */

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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

    // ── Dynamic palette extraction ──────────────────────────────────────────
    var extractedColors by remember { mutableStateOf<List<Color>>(emptyList()) }
    LaunchedEffect(artworkUrl) {
        val url = artworkUrl ?: return@LaunchedEffect
        runCatching {
            val request = ImageRequest.Builder(context)
                .data(url)
                .size(PlayerColorExtractor.Config.IMAGE_SIZE, PlayerColorExtractor.Config.IMAGE_SIZE)
                .allowHardware(false)
                .build()
            val result = withContext(Dispatchers.IO) { context.imageLoader.execute(request) }
            val bitmap = result.image?.toBitmap() ?: return@runCatching
            val palette = withContext(Dispatchers.Default) {
                Palette.from(bitmap)
                    .maximumColorCount(PlayerColorExtractor.Config.MAX_COLOR_COUNT)
                    .resizeBitmapArea(PlayerColorExtractor.Config.BITMAP_AREA)
                    .generate()
            }
            extractedColors = PlayerColorExtractor.extractGradientColors(
                palette = palette,
                fallbackColor = 0xFF121212.toInt(),
            )
        }.onFailure { Timber.tag("FusionPlayer").w(it, "Artwork color extraction failed") }
    }

    val themePrimary = MaterialTheme.colorScheme.primary
    val accentTarget = extractedColors.firstOrNull() ?: themePrimary
    val accentColor by animateColorAsState(
        targetValue = accentTarget,
        animationSpec = tween(900),
        label = "fusionAccent",
    )
    // Muted accent for large surface tints — keeps legibility.
    val accentTint by animateColorAsState(
        targetValue = (extractedColors.firstOrNull() ?: themePrimary).copy(alpha = 0.14f),
        animationSpec = tween(900),
        label = "fusionAccentTint",
    )

    val isDark = isSystemInDarkTheme()
    val foreground = MaterialTheme.colorScheme.onSurface
    val secondaryForeground = MaterialTheme.colorScheme.onSurfaceVariant
    val scrimTop = if (isDark) Color.Black.copy(alpha = 0.48f) else Color.White.copy(alpha = 0.38f)
    val scrimBottom = if (isDark) Color.Black.copy(alpha = 0.80f) else Color.White.copy(alpha = 0.62f)

    // ── Artwork scale: breathes while playing ───────────────────────────────
    val artScale by animateFloatAsState(
        targetValue = if (isPlaying) 1.0f else 0.93f,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "artScale",
    )

    // ── Play button outer ring pulse ────────────────────────────────────────
    val infiniteTransition = rememberInfiniteTransition(label = "fusionPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.55f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulseAlpha",
    )
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulseScale",
    )

    // ── Queue / shuffle / repeat ────────────────────────────────────────────
    val queueWindows by playerConnection.queueWindows.collectAsState()
    val currentWindowIndex by playerConnection.currentWindowIndex.collectAsState()
    val (showUpNext) = rememberPreference(ShowFusionUpNextKey, true)
    val upNextWindows = remember(queueWindows, currentWindowIndex) {
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
                    bottomSheetPageState.show { ShowMediaInfo(mediaMetadata.id) }
                },
                onDismiss = menuState::dismiss,
            )
        }
    }

    Box(modifier = modifier.fillMaxSize()) {

        // ── Layer 1: blurred artwork backdrop ──────────────────────────────
        BlurredArtwork(
            model = artworkUrl,
            radius = 52.dp,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )

        // ── Layer 2: scrim (top fade + accent tint + bottom fade) ──────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to scrimTop,
                            0.35f to Color.Transparent,
                            0.60f to accentTint,
                            1.00f to scrimBottom,
                        ),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                    bottom = 16.dp,
                ),
        ) {

            // ── Top bar ────────────────────────────────────────────────────
            Row(
                modifier = Modifier
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
                // "Playing from" context label (YTM pattern)
                val contextLabel = queueTitle?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.now_playing)
                Text(
                    text = contextLabel,
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

            Spacer(Modifier.height(4.dp))

            // ── Artwork card with glow halo ────────────────────────────────
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                val cardSize = maxWidth * 0.72f

                Box(
                    modifier = Modifier.size(cardSize + 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    // Colored glow layer (API 12+, blurred solid rect behind the card)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Box(
                            modifier = Modifier
                                .size(cardSize * 0.88f)
                                .blur(40.dp)
                                .background(
                                    color = accentColor.copy(alpha = 0.72f),
                                    shape = RoundedCornerShape(20.dp),
                                ),
                        )
                    }

                    // Artwork card with breathing scale
                    Box(
                        modifier = Modifier
                            .size(cardSize)
                            .graphicsLayer {
                                scaleX = artScale
                                scaleY = artScale
                            }
                            .shadow(20.dp, RoundedCornerShape(16.dp), clip = false)
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
            }

            Spacer(Modifier.height(16.dp))

            // ── Title + artist + album chip ────────────────────────────────
            Text(
                text = mediaMetadata.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = foreground,
                maxLines = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .basicMarquee(),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = mediaMetadata.artists.joinToString { it.name },
                style = MaterialTheme.typography.bodyLarge,
                color = secondaryForeground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .basicMarquee(),
            )
            // Album chip (YTM-style pill, shown when album name is available)
            val albumName = mediaMetadata.album?.title
            if (!albumName.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Surface(
                    modifier = Modifier.padding(horizontal = 32.dp),
                    shape = RoundedCornerShape(50),
                    color = accentColor.copy(alpha = 0.18f),
                ) {
                    Text(
                        text = albumName,
                        style = MaterialTheme.typography.labelMedium,
                        color = foreground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // ── Seek bar (Spotify: elapsed | slider | total) ───────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = fusionFormatDuration(sliderPosition ?: position),
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
                    text = fusionFormatDuration(duration),
                    style = MaterialTheme.typography.labelSmall,
                    color = secondaryForeground,
                )
            }

            Spacer(Modifier.height(4.dp))

            // ── Transport controls ─────────────────────────────────────────
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

                // Play button with outer pulse ring
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(88.dp),
                ) {
                    // Pulse ring (only while playing)
                    if (isPlaying) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .graphicsLayer {
                                    scaleX = pulseScale
                                    scaleY = pulseScale
                                    alpha = pulseAlpha
                                }
                                .background(
                                    color = accentColor.copy(alpha = 0.45f),
                                    shape = CircleShape,
                                ),
                        )
                    }
                    FilledIconButton(
                        onClick = {
                            if (playbackState == Player.STATE_ENDED) {
                                playerConnection.player.seekTo(0, 0)
                                playerConnection.player.playWhenReady = true
                            } else {
                                playerConnection.player.togglePlayPause()
                            }
                        },
                        colors = IconButtonDefaults.filledIconButtonColors(
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
                                painter = painterResource(
                                    if (isPlaying) R.drawable.pause else R.drawable.play,
                                ),
                                contentDescription = null,
                                modifier = Modifier.size(36.dp),
                            )
                        }
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
                IconButton(onClick = { playerConnection.player.toggleRepeatMode() }) {
                    Icon(
                        painter = painterResource(
                            if (repeatMode == Player.REPEAT_MODE_ONE) R.drawable.repeat_one
                            else R.drawable.repeat,
                        ),
                        contentDescription = stringResource(
                            if (repeatMode == Player.REPEAT_MODE_ONE) R.string.action_repeat_one
                            else R.string.action_repeat,
                        ),
                        tint = if (repeatMode != Player.REPEAT_MODE_OFF) accentColor else secondaryForeground,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── Action row with labels (YTM-style but labelled) ───────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FusionActionButton(
                    iconRes = if (currentSongLiked) R.drawable.favorite else R.drawable.favorite_border,
                    label = stringResource(R.string.action_like),
                    tint = if (currentSongLiked) accentColor else secondaryForeground,
                    onClick = { playerConnection.toggleLike() },
                )
                FusionActionButton(
                    iconRes = R.drawable.playlist_add,
                    label = stringResource(R.string.add_to_playlist),
                    tint = secondaryForeground,
                    onClick = onMenuClick,
                )
                FusionActionButton(
                    iconRes = R.drawable.queue_music,
                    label = stringResource(R.string.queue),
                    tint = secondaryForeground,
                    onClick = onOpenQueue,
                )
                FusionActionButton(
                    iconRes = R.drawable.lyrics,
                    label = stringResource(R.string.lyrics),
                    tint = secondaryForeground,
                    onClick = onOpenLyrics,
                )
            }

            Spacer(Modifier.height(8.dp))

            // ── Up Next strip ──────────────────────────────────────────────
            if (showUpNext && upNextWindows.isNotEmpty()) {
                Row(
                    modifier = Modifier
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
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(horizontal = 24.dp),
                ) {
                    items(
                        items = upNextWindows,
                        key = { it.uid.hashCode() },
                    ) { window ->
                        val itemMeta = window.mediaItem.metadata
                        Surface(
                            modifier = Modifier
                                .width(108.dp)
                                .clickable(onClick = onOpenQueue),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                            tonalElevation = 2.dp,
                        ) {
                            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                                AsyncImage(
                                    model = itemMeta?.thumbnailUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(108.dp)
                                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = itemMeta?.title.orEmpty(),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = foreground,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                )
                                Text(
                                    text = itemMeta?.artists.orEmpty().joinToString { it.name },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = secondaryForeground,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))
        }
    }
}

// ── Reusable labelled icon button for the action row ──────────────────────────
@Composable
private fun FusionActionButton(
    iconRes: Int,
    label: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(26.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            fontSize = 10.sp,
        )
    }
}

private fun fusionFormatDuration(ms: Long): String {
    if (ms <= 0L || ms == C.TIME_UNSET) return "0:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
