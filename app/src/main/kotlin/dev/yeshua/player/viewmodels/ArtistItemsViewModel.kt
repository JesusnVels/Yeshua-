/*
 * LunarTune (2026)
 * © cognitiveshadows03 — github.com/cognitiveshadows03
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package dev.yeshua.player.viewmodels

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import dev.yeshua.player.constants.HideExplicitKey
import dev.yeshua.player.constants.HideVideoKey
import dev.yeshua.player.db.MusicDatabase
import dev.yeshua.player.extensions.filterBlockedArtists
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.BrowseEndpoint
import moe.rukamori.archivetune.innertube.models.filterExplicit
import moe.rukamori.archivetune.innertube.models.filterVideo
import moe.rukamori.archivetune.innertube.pages.ArtistItemsPageLayout
import dev.yeshua.player.models.ItemsPage
import dev.yeshua.player.utils.dataStore
import dev.yeshua.player.utils.get
import dev.yeshua.player.utils.reportException
import javax.inject.Inject

@HiltViewModel
class ArtistItemsViewModel
    @Inject
    constructor(
        @ApplicationContext val context: Context,
        private val database: MusicDatabase,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val browseId = savedStateHandle.get<String>("browseId")!!
        private val params =
            savedStateHandle
                .get<String>("params")
                ?.takeUnless { it.isBlank() || it == "null" }

        val title = MutableStateFlow("")
        val itemsPage = MutableStateFlow<ItemsPage?>(null)
        val itemsLayout = MutableStateFlow(ArtistItemsPageLayout.LIST)

        init {
            viewModelScope.launch {
                YouTube
                    .artistItems(
                        BrowseEndpoint(
                            browseId = browseId,
                            params = params,
                        ),
                    ).onSuccess { artistItemsPage ->
                        title.value = artistItemsPage.title
                        itemsLayout.value = artistItemsPage.layout
                        itemsPage.value =
                            ItemsPage(
                                items =
                                    artistItemsPage.items
                                        .distinctBy { it.id }
                                        .filterExplicit(context.dataStore.get(HideExplicitKey, false))
                                        .filterVideo(context.dataStore.get(HideVideoKey, false))
                                        .filterBlockedArtists(database.getBlockedArtistIds().toSet()),
                                continuation = artistItemsPage.continuation,
                            )
                    }.onFailure {
                        reportException(it)
                    }
            }
        }

        fun loadMore() {
            viewModelScope.launch {
                val oldItemsPage = itemsPage.value ?: return@launch
                val continuation = oldItemsPage.continuation ?: return@launch
                YouTube
                    .artistItemsContinuation(continuation)
                    .onSuccess { artistItemsContinuationPage ->
                        itemsPage.update {
                            ItemsPage(
                                items =
                                    (oldItemsPage.items + artistItemsContinuationPage.items)
                                        .distinctBy { it.id }
                                        .filterExplicit(context.dataStore.get(HideExplicitKey, false))
                                        .filterVideo(context.dataStore.get(HideVideoKey, false))
                                        .filterBlockedArtists(database.getBlockedArtistIds().toSet()),
                                continuation = artistItemsContinuationPage.continuation,
                            )
                        }
                    }.onFailure {
                        reportException(it)
                    }
            }
        }
    }
