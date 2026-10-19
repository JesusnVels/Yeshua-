/*
 * LunarTune (2026)
 * © cognitiveshadows03 — github.com/cognitiveshadows03
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package dev.yeshua.player.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.HorizontalCenteredHeroCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size
import dev.yeshua.player.ui.utils.displayArtworkUrl
import kotlinx.coroutines.CoroutineScope
import dev.yeshua.player.R
import dev.yeshua.player.constants.ListItemHeight
import dev.yeshua.player.constants.ListThumbnailSize
import dev.yeshua.player.constants.QuickPicksDisplayMode
import dev.yeshua.player.constants.ThumbnailCornerRadius
import dev.yeshua.player.db.entities.Album
import dev.yeshua.player.db.entities.Artist
import dev.yeshua.player.db.entities.Playlist
import dev.yeshua.player.db.entities.Song
import dev.yeshua.player.extensions.toMediaItem
import dev.yeshua.player.extensions.togglePlayPause
import moe.rukamori.archivetune.innertube.models.AlbumItem
import moe.rukamori.archivetune.innertube.models.ArtistItem
import moe.rukamori.archivetune.innertube.models.PlaylistItem
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.YTItem
import moe.rukamori.archivetune.innertube.pages.HomePage
import dev.yeshua.player.models.MediaMetadata
import dev.yeshua.player.models.SimilarRecommendation
import dev.yeshua.player.models.toMediaMetadata
import dev.yeshua.player.playback.PlayerConnection
import dev.yeshua.player.playback.queues.ListQueue
import dev.yeshua.player.playback.queues.YouTubeQueue
import dev.yeshua.player.ui.component.MenuState
import dev.yeshua.player.ui.component.SongListItem
import dev.yeshua.player.ui.component.YouTubeGridItem
import dev.yeshua.player.ui.component.YouTubeListItem
import dev.yeshua.player.ui.theme.YeshuaDisplayFontFamily
import dev.yeshua.player.ui.menu.SongMenu
import dev.yeshua.player.ui.menu.YouTubeAlbumMenu
import dev.yeshua.player.ui.menu.YouTubeArtistMenu
import dev.yeshua.player.ui.menu.YouTubePlaylistMenu
import dev.yeshua.player.ui.menu.YouTubeSongMenu
import kotlin.math.min
import dev.yeshua.player.ui.utils.SnapLayoutInfoProvider as buildSnapLayoutInfoProvider

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeCategoryChips(
    chips: List<HomePage.Chip>,
    selectedChip: HomePage.Chip?,
    onChipSelected: (HomePage.Chip) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 68.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        chips.forEach { chip ->
            val selected = chip == selectedChip
            FilterChip(
                selected = selected,
                onClick = { onChipSelected(chip) },
                label = {
                    Text(
                        text = chip.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingIcon =
                    if (selected) {
                        {
                            Icon(
                                painter = painterResource(R.drawable.done),
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize),
                            )
                        }
                    } else {
                        null
                    },
                shapes = FilterChipDefaults.shapes(),
                colors =
                    FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.78f),
                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.94f),
                        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                border = null,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    dimAlpha: Float = 0f,
    thumbnail: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onPlayAll: (() -> Unit)? = null,
) {
    val shadowBlurPx = with(LocalDensity.current) { 8.dp.toPx() }
    // scrim is black in both themes; derive the shadow polarity from the text
    // itself so light mode gets a light shadow behind dark glyphs.
    val shadowColor =
        if (MaterialTheme.colorScheme.onSurface.luminance() > 0.5f) Color.Black else Color.White
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        thumbnail?.invoke()
        Column(
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.weight(1f),
        ) {
            label?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = title,
                style =
                    MaterialTheme.typography.titleLargeEmphasized.copy(
                        // Yeshua identity: headers speak in the fixed display
                        // typeface and carry the palette's tertiary tint.
                        fontFamily = YeshuaDisplayFontFamily,
                        // Legibility over artwork with no backdrop blob: a
                        // shadow that hugs the glyphs and fades with the art.
                        shadow =
                            if (dimAlpha > 0.02f) {
                                Shadow(
                                    color = shadowColor.copy(alpha = 0.72f * dimAlpha),
                                    blurRadius = shadowBlurPx,
                                )
                            } else {
                                null
                            },
                    ),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onPlayAll != null) {
            FilledTonalIconButton(
                onClick = onPlayAll,
                colors =
                    IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    ),
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.play),
                    contentDescription = stringResource(R.string.play_all),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (onClick != null) {
            Icon(
                painter = painterResource(R.drawable.arrow_forward),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalFoundationApi::class,
)
@Composable
fun QuickPicksSection(
    quickPicks: List<Song>,
    mediaMetadata: MediaMetadata?,
    isPlaying: Boolean,
    displayMode: QuickPicksDisplayMode,
    navController: NavController,
    playerConnection: PlayerConnection,
    menuState: MenuState,
    haptic: HapticFeedback,
    modifier: Modifier = Modifier,
) {
    val distinctQuickPicks = remember(quickPicks) { quickPicks.distinctBy { it.id } }

    when (displayMode) {
        QuickPicksDisplayMode.CARD -> {
            BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
                val heroHeight =
                    when {
                        maxWidth >= 840.dp -> 380.dp
                        maxWidth >= 600.dp -> 356.dp
                        else -> 332.dp
                    }
                val heroMaxWidth =
                    (maxWidth - 48.dp)
                        .coerceAtLeast(232.dp)
                        .coerceAtMost(440.dp)
                val density = LocalDensity.current
                val requestWidthPx = with(density) { heroMaxWidth.roundToPx().coerceAtLeast(1) }
                val requestHeightPx = with(density) { heroHeight.roundToPx().coerceAtLeast(1) }

                HorizontalCenteredHeroCarousel(
                    state = rememberCarouselState { distinctQuickPicks.size },
                    maxItemWidth = heroMaxWidth,
                    itemSpacing = 10.dp,
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(heroHeight),
                ) { index ->
                    val song = distinctQuickPicks[index]
                    val isActive = song.id == mediaMetadata?.id
                    val context = LocalContext.current
                    val artworkUrl =
                        remember(song.song.thumbnailUrl, requestWidthPx, requestHeightPx) {
                            song.song.thumbnailUrl?.displayArtworkUrl(
                                width = requestWidthPx,
                                height = requestHeightPx,
                            )
                        }
                    val imageRequest =
                        remember(artworkUrl, requestWidthPx, requestHeightPx) {
                            ImageRequest
                                .Builder(context)
                                .data(artworkUrl)
                                .size(Size(requestWidthPx, requestHeightPx))
                                .crossfade(true)
                                .build()
                        }

                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .maskClip(MaterialTheme.shapes.extraLarge)
                                .border(
                                    BorderStroke(
                                        1.dp,
                                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f),
                                    ),
                                    MaterialTheme.shapes.extraLarge,
                                ).focusable()
                                .combinedClickable(
                                    onClick = {
                                        if (isActive) {
                                            playerConnection.player.togglePlayPause()
                                        } else {
                                            playerConnection.playQueue(
                                                if (song.song.isLocal) {
                                                    ListQueue(items = listOf(song.toMediaItem()))
                                                } else {
                                                    YouTubeQueue.radio(song.toMediaMetadata())
                                                },
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
                        AsyncImage(
                            model = imageRequest,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )

                        Box(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            0f to Color.Transparent,
                                            0.48f to Color.Black.copy(alpha = 0.08f),
                                            1f to Color.Black.copy(alpha = 0.84f),
                                        ),
                                    ),
                        )

                        if (isActive && isPlaying) {
                            Surface(
                                color = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                shape = CircleShape,
                                tonalElevation = 2.dp,
                                modifier =
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(14.dp)
                                        .size(36.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        painter = painterResource(R.drawable.volume_up),
                                        contentDescription = null,
                                        modifier = Modifier.size(19.dp),
                                    )
                                }
                            }
                        }

                        Column(
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            modifier =
                                Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(20.dp),
                        ) {
                            Text(
                                text = song.song.title,
                                style = MaterialTheme.typography.titleLargeEmphasized,
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = song.artists.joinToString { it.name },
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White.copy(alpha = 0.78f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        QuickPicksDisplayMode.LIST -> {
            BoxWithConstraints(
                modifier = modifier.fillMaxWidth(),
            ) {
                val widthFactor = if (maxWidth * 0.475f >= 320.dp) 0.475f else 0.9f
                val itemWidth = maxWidth * widthFactor
                val lazyGridState = rememberLazyGridState()
                val snapLayoutInfoProvider =
                    remember(lazyGridState, widthFactor) {
                        buildSnapLayoutInfoProvider(
                            lazyGridState = lazyGridState,
                            positionInLayout = { layoutSize, itemSize ->
                                layoutSize * widthFactor / 2f - itemSize / 2f
                            },
                        )
                    }
                LazyHorizontalGrid(
                    state = lazyGridState,
                    rows = GridCells.Fixed(4),
                    flingBehavior = rememberSnapFlingBehavior(snapLayoutInfoProvider),
                    contentPadding =
                        WindowInsets.systemBars
                            .only(WindowInsetsSides.Horizontal)
                            .asPaddingValues(),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(ListItemHeight * 4),
                ) {
                    items(
                        items = distinctQuickPicks,
                        key = { it.id },
                        contentType = { "quick_pick_song" },
                    ) { song ->
                        SongListItem(
                            song = song,
                            showInLibraryIcon = true,
                            isActive = song.id == mediaMetadata?.id,
                            isPlaying = isPlaying,
                            isSwipeable = false,
                            trailingContent = {
                                IconButton(
                                    onClick = {
                                        menuState.show {
                                            SongMenu(
                                                originalSong = song,
                                                navController = navController,
                                                onDismiss = menuState::dismiss,
                                            )
                                        }
                                    },
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.more_vert),
                                        contentDescription = null,
                                    )
                                }
                            },
                            modifier =
                                Modifier
                                    .width(itemWidth)
                                    .combinedClickable(
                                        onClick = {
                                            if (song.id == mediaMetadata?.id) {
                                                playerConnection.player.togglePlayPause()
                                            } else {
                                                playerConnection.playQueue(
                                                    if (song.song.isLocal) {
                                                        ListQueue(items = listOf(song.toMediaItem()))
                                                    } else {
                                                        YouTubeQueue.radio(song.toMediaMetadata())
                                                    },
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
                        )
                    }
                }
            }
        }
    }
}

/**
 * Account Playlists section - horizontal row of YouTube playlists
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AccountPlaylistsSection(
    accountPlaylists: List<PlaylistItem>,
    mediaMetadata: MediaMetadata?,
    isPlaying: Boolean,
    navController: NavController,
    playerConnection: PlayerConnection,
    menuState: MenuState,
    haptic: HapticFeedback,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    val distinctPlaylists = remember(accountPlaylists) { accountPlaylists.distinctBy { it.id } }

    LazyRow(
        contentPadding =
            WindowInsets.systemBars
                .only(WindowInsetsSides.Horizontal)
                .asPaddingValues(),
        modifier = modifier,
    ) {
        items(
            items = distinctPlaylists,
            key = { it.id },
            contentType = { "account_playlist" },
        ) { item ->
            YouTubeGridItemWrapper(
                item = item,
                mediaMetadata = mediaMetadata,
                isPlaying = isPlaying,
                navController = navController,
                playerConnection = playerConnection,
                menuState = menuState,
                haptic = haptic,
                scope = scope,
            )
        }
    }
}

/**
 * Similar Recommendations section
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SimilarRecommendationsSection(
    recommendation: SimilarRecommendation,
    mediaMetadata: MediaMetadata?,
    isPlaying: Boolean,
    navController: NavController,
    playerConnection: PlayerConnection,
    menuState: MenuState,
    haptic: HapticFeedback,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        contentPadding =
            WindowInsets.systemBars
                .only(WindowInsetsSides.Horizontal)
                .asPaddingValues(),
        modifier = modifier,
    ) {
        items(
            items = recommendation.items,
            key = { it.id },
            contentType = { item -> item::class },
        ) { item ->
            YouTubeGridItemWrapper(
                item = item,
                mediaMetadata = mediaMetadata,
                isPlaying = isPlaying,
                navController = navController,
                playerConnection = playerConnection,
                menuState = menuState,
                haptic = haptic,
                scope = scope,
            )
        }
    }
}

/**
 * HomePage Section - a single section from YouTube home page
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomePageSectionContent(
    section: HomePage.Section,
    mediaMetadata: MediaMetadata?,
    isPlaying: Boolean,
    navController: NavController,
    playerConnection: PlayerConnection,
    menuState: MenuState,
    haptic: HapticFeedback,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        contentPadding =
            WindowInsets.systemBars
                .only(WindowInsetsSides.Horizontal)
                .asPaddingValues(),
        modifier = modifier,
    ) {
        items(
            items = section.items,
            key = { it.id },
            contentType = { item -> item::class },
        ) { item ->
            YouTubeGridItemWrapper(
                item = item,
                mediaMetadata = mediaMetadata,
                isPlaying = isPlaying,
                navController = navController,
                playerConnection = playerConnection,
                menuState = menuState,
                haptic = haptic,
                scope = scope,
            )
        }
    }
}

// ============== Helper Composables ==============

/**
 * Wrapper for YouTube grid items with click handling
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun YouTubeGridItemWrapper(
    item: YTItem,
    mediaMetadata: MediaMetadata?,
    isPlaying: Boolean,
    navController: NavController,
    playerConnection: PlayerConnection,
    menuState: MenuState,
    haptic: HapticFeedback,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    YouTubeGridItem(
        item = item,
        isActive = item.id in listOf(mediaMetadata?.album?.id, mediaMetadata?.id),
        isPlaying = isPlaying,
        coroutineScope = scope,
        modifier =
            modifier
                .focusable()
                .combinedClickable(
                    onClick = {
                        when (item) {
                            is SongItem -> {
                                playerConnection.playQueue(
                                    YouTubeQueue.radio(item.toMediaMetadata()),
                                )
                            }

                            is AlbumItem -> {
                                navController.navigate("album/${item.id}")
                            }

                            is ArtistItem -> {
                                navController.navigate("artist/${item.id}")
                            }

                            is PlaylistItem -> {
                                navController.navigate("online_playlist/${item.id}")
                            }
                        }
                    },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuState.show {
                            when (item) {
                                is SongItem -> {
                                    YouTubeSongMenu(
                                        song = item,
                                        navController = navController,
                                        onDismiss = menuState::dismiss,
                                    )
                                }

                                is AlbumItem -> {
                                    YouTubeAlbumMenu(
                                        albumItem = item,
                                        navController = navController,
                                        onDismiss = menuState::dismiss,
                                    )
                                }

                                is ArtistItem -> {
                                    YouTubeArtistMenu(
                                        artist = item,
                                        onDismiss = menuState::dismiss,
                                    )
                                }

                                is PlaylistItem -> {
                                    YouTubePlaylistMenu(
                                        playlist = item,
                                        coroutineScope = scope,
                                        onDismiss = menuState::dismiss,
                                    )
                                }
                            }
                        }
                    },
                ),
    )
}

/**
 * Account playlist navigation title with image
 */
@Composable
fun AccountPlaylistsTitle(
    accountName: String,
    accountImageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HomeSectionHeader(
        label = stringResource(R.string.your_youtube_playlists),
        title = accountName.ifBlank { stringResource(R.string.account) },
        thumbnail = {
            if (accountImageUrl != null) {
                val context = LocalContext.current
                val avatarSizePx =
                    with(LocalDensity.current) {
                        ListThumbnailSize.roundToPx().coerceAtLeast(1)
                    }
                val imageRequest =
                    remember(accountImageUrl, avatarSizePx) {
                        ImageRequest
                            .Builder(context)
                            .data(accountImageUrl)
                            .size(Size(avatarSizePx, avatarSizePx))
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .diskCacheKey(accountImageUrl)
                            .crossfade(true)
                            .build()
                    }
                AsyncImage(
                    model = imageRequest,
                    placeholder = painterResource(id = R.drawable.person),
                    error = painterResource(id = R.drawable.person),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier
                            .size(ListThumbnailSize)
                            .clip(CircleShape),
                )
            } else {
                Icon(
                    painter = painterResource(id = R.drawable.person),
                    contentDescription = null,
                    modifier = Modifier.size(ListThumbnailSize),
                )
            }
        },
        onClick = onClick,
        modifier = modifier,
    )
}

/**
 * Similar recommendations navigation title
 */
@Composable
fun SimilarRecommendationsTitle(
    recommendation: SimilarRecommendation,
    navController: NavController,
    modifier: Modifier = Modifier,
) {
    HomeSectionHeader(
        label = stringResource(R.string.similar_to),
        title = recommendation.title.title,
        thumbnail =
            recommendation.title.thumbnailUrl?.let { thumbnailUrl ->
                {
                    val shape =
                        if (recommendation.title is Artist) {
                            CircleShape
                        } else {
                            RoundedCornerShape(ThumbnailCornerRadius)
                        }
                    AsyncImage(
                        model = thumbnailUrl,
                        contentDescription = null,
                        modifier =
                            Modifier
                                .size(ListThumbnailSize)
                                .clip(shape),
                    )
                }
            },
        onClick = {
            when (recommendation.title) {
                is Song -> {
                    navController.navigate("album/${recommendation.title.album!!.id}")
                }

                is Album -> {
                    navController.navigate("album/${recommendation.title.id}")
                }

                is Artist -> {
                    navController.navigate("artist/${recommendation.title.id}")
                }

                is Playlist -> {}
            }
        },
        modifier = modifier,
    )
}

/**
 * HomePage section navigation title
 */
@Composable
fun HomePageSectionTitle(
    section: HomePage.Section,
    navController: NavController,
    modifier: Modifier = Modifier,
) {
    HomeSectionHeader(
        title = section.title,
        label = section.label,
        thumbnail =
            section.thumbnail?.let { thumbnailUrl ->
                {
                    val shape =
                        if (section.endpoint?.isArtistEndpoint == true) {
                            CircleShape
                        } else {
                            RoundedCornerShape(ThumbnailCornerRadius)
                        }
                    AsyncImage(
                        model = thumbnailUrl,
                        contentDescription = null,
                        modifier =
                            Modifier
                                .size(ListThumbnailSize)
                                .clip(shape),
                    )
                }
            },
        onClick =
            section.endpoint?.browseId?.let { browseId ->
                {
                    if (browseId == "FEmusic_moods_and_genres") {
                        navController.navigate(Screens.MoodAndGenres.route)
                    } else {
                        navController.navigate("browse/$browseId")
                    }
                }
            },
        modifier = modifier,
    )
}

/**
 * Quick picks that came from YouTube Music (home shelf or recommendation seeds).
 *
 * Uses the same card/list layouts as the local quick picks so the "Quick picks display
 * mode" preference applies to online picks too.
 */
@OptIn(
    ExperimentalFoundationApi::class,
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
)
@Composable
fun RemoteQuickPicksSection(
    section: HomePage.Section,
    mediaMetadata: MediaMetadata?,
    isPlaying: Boolean,
    displayMode: QuickPicksDisplayMode,
    navController: NavController,
    playerConnection: PlayerConnection,
    menuState: MenuState,
    haptic: HapticFeedback,
    modifier: Modifier = Modifier,
) {
    val songs =
        remember(section.items) {
            section.items.filterIsInstance<SongItem>().distinctBy(SongItem::id)
        }

    when (displayMode) {
        QuickPicksDisplayMode.CARD -> {
            BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
                val heroHeight =
                    when {
                        maxWidth >= 840.dp -> 380.dp
                        maxWidth >= 600.dp -> 356.dp
                        else -> 332.dp
                    }
                val heroMaxWidth =
                    (maxWidth - 48.dp)
                        .coerceAtLeast(232.dp)
                        .coerceAtMost(440.dp)
                val density = LocalDensity.current
                val requestWidthPx = with(density) { heroMaxWidth.roundToPx().coerceAtLeast(1) }
                val requestHeightPx = with(density) { heroHeight.roundToPx().coerceAtLeast(1) }

                HorizontalCenteredHeroCarousel(
                    state = rememberCarouselState { songs.size },
                    maxItemWidth = heroMaxWidth,
                    itemSpacing = 10.dp,
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(heroHeight),
                ) { index ->
                    val song = songs[index]
                    val isActive = song.id == mediaMetadata?.id
                    val context = LocalContext.current
                    val imageRequest =
                        remember(song.thumbnail, requestWidthPx, requestHeightPx) {
                            ImageRequest
                                .Builder(context)
                                .data(song.thumbnail)
                                .size(Size(requestWidthPx, requestHeightPx))
                                .crossfade(true)
                                .build()
                        }

                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .maskClip(MaterialTheme.shapes.extraLarge)
                                .border(
                                    BorderStroke(
                                        1.dp,
                                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f),
                                    ),
                                    MaterialTheme.shapes.extraLarge,
                                ).focusable()
                                .combinedClickable(
                                    onClick = {
                                        if (isActive) {
                                            playerConnection.player.togglePlayPause()
                                        } else {
                                            playerConnection.playQueue(
                                                YouTubeQueue.radio(song.toMediaMetadata()),
                                            )
                                        }
                                    },
                                    onLongClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        menuState.show {
                                            YouTubeSongMenu(
                                                song = song,
                                                navController = navController,
                                                onDismiss = menuState::dismiss,
                                            )
                                        }
                                    },
                                ),
                    ) {
                        AsyncImage(
                            model = imageRequest,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )

                        Box(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            0f to Color.Transparent,
                                            0.48f to Color.Black.copy(alpha = 0.08f),
                                            1f to Color.Black.copy(alpha = 0.84f),
                                        ),
                                    ),
                        )

                        if (isActive && isPlaying) {
                            Surface(
                                color = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                shape = CircleShape,
                                tonalElevation = 2.dp,
                                modifier =
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(14.dp)
                                        .size(36.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        painter = painterResource(R.drawable.volume_up),
                                        contentDescription = null,
                                        modifier = Modifier.size(19.dp),
                                    )
                                }
                            }
                        }

                        Column(
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            modifier =
                                Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(20.dp),
                        ) {
                            Text(
                                text = song.title,
                                style = MaterialTheme.typography.titleLargeEmphasized,
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = song.artists.joinToString { artist -> artist.name },
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White.copy(alpha = 0.78f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        QuickPicksDisplayMode.LIST -> {
            BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
                val widthFactor = if (maxWidth * 0.475f >= 320.dp) 0.475f else 0.9f
                val itemWidth = maxWidth * widthFactor
                val lazyGridState = rememberLazyGridState()
                val snapLayoutInfoProvider =
                    remember(lazyGridState, widthFactor) {
                        buildSnapLayoutInfoProvider(
                            lazyGridState = lazyGridState,
                            positionInLayout = { layoutSize, itemSize ->
                                layoutSize * widthFactor / 2f - itemSize / 2f
                            },
                        )
                    }
                LazyHorizontalGrid(
                    state = lazyGridState,
                    rows = GridCells.Fixed(4),
                    flingBehavior = rememberSnapFlingBehavior(snapLayoutInfoProvider),
                    contentPadding =
                        WindowInsets.systemBars
                            .only(WindowInsetsSides.Horizontal)
                            .asPaddingValues(),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(ListItemHeight * 4),
                ) {
                    items(
                        items = songs,
                        key = SongItem::id,
                        contentType = { "remote_quick_pick_song" },
                    ) { song ->
                        YouTubeListItem(
                            item = song,
                            isActive = song.id == mediaMetadata?.id,
                            isPlaying = isPlaying,
                            isSwipeable = false,
                            trailingContent = {
                                IconButton(
                                    onClick = {
                                        menuState.show {
                                            YouTubeSongMenu(
                                                song = song,
                                                navController = navController,
                                                onDismiss = menuState::dismiss,
                                            )
                                        }
                                    },
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.more_vert),
                                        contentDescription = null,
                                    )
                                }
                            },
                            modifier =
                                Modifier
                                    .width(itemWidth)
                                    .combinedClickable(
                                        onClick = {
                                            if (song.id == mediaMetadata?.id) {
                                                playerConnection.player.togglePlayPause()
                                            } else {
                                                playerConnection.playQueue(
                                                    YouTubeQueue.radio(song.toMediaMetadata()),
                                                )
                                            }
                                        },
                                        onLongClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            menuState.show {
                                                YouTubeSongMenu(
                                                    song = song,
                                                    navController = navController,
                                                    onDismiss = menuState::dismiss,
                                                )
                                            }
                                        },
                                    ),
                        )
                    }
                }
            }
        }
    }
}
