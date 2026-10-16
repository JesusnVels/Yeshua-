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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import dev.yeshua.player.constants.AutoPlaylistSongSortDescendingKey
import dev.yeshua.player.constants.AutoPlaylistSongSortType
import dev.yeshua.player.constants.AutoPlaylistSongSortTypeKey
import dev.yeshua.player.constants.HideExplicitKey
import dev.yeshua.player.constants.HideVideoKey
import dev.yeshua.player.constants.SongSortType
import dev.yeshua.player.db.MusicDatabase
import dev.yeshua.player.extensions.filterExplicit
import dev.yeshua.player.extensions.toEnum
import dev.yeshua.player.utils.SyncUtils
import dev.yeshua.player.utils.dataStore
import dev.yeshua.player.utils.get
import dev.yeshua.player.utils.reportException
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AutoPlaylistViewModel
    @Inject
    constructor(
        @ApplicationContext context: Context,
        database: MusicDatabase,
        savedStateHandle: SavedStateHandle,
        private val syncUtils: SyncUtils,
    ) : ViewModel() {
        val playlist = savedStateHandle.get<String>("playlist")!!

        private val _isRefreshing = MutableStateFlow(false)
        val isRefreshing = _isRefreshing.asStateFlow()

        private fun AutoPlaylistSongSortType.toSongSortType(): SongSortType =
            when (this) {
                AutoPlaylistSongSortType.CREATE_DATE -> SongSortType.CREATE_DATE
                AutoPlaylistSongSortType.NAME -> SongSortType.NAME
                AutoPlaylistSongSortType.ARTIST -> SongSortType.ARTIST
                AutoPlaylistSongSortType.PLAY_TIME -> SongSortType.PLAY_TIME
            }

        @OptIn(ExperimentalCoroutinesApi::class)
        val likedSongs =
            context.dataStore.data
                .map {
                    Triple(
                        it[AutoPlaylistSongSortTypeKey].toEnum(AutoPlaylistSongSortType.CREATE_DATE) to (
                            it[AutoPlaylistSongSortDescendingKey]
                                ?: true
                        ),
                        it[HideExplicitKey] ?: false,
                        it[HideVideoKey] ?: false,
                    )
                }.distinctUntilChanged()
                .flatMapLatest { (sortDesc, hideExplicit, hideVideo) ->
                    val (sortType, descending) = sortDesc
                    val songSortType = sortType.toSongSortType()
                    when (playlist) {
                        "liked" -> {
                            database.likedSongs(songSortType, descending, hideVideo).map { it.filterExplicit(hideExplicit) }
                        }

                        else -> {
                            MutableStateFlow(emptyList())
                        }
                    }
                }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

        fun refresh() {
            if (_isRefreshing.value) return
            viewModelScope.launch(Dispatchers.IO) {
                _isRefreshing.value = true
                try {
                    when (playlist) {
                        "liked" -> syncUtils.syncLikedSongs()
                        else -> Unit
                    }
                } catch (e: Exception) {
                    reportException(e)
                } finally {
                    _isRefreshing.value = false
                }
            }
        }

        fun syncLikedSongs() {
            refresh()
        }
    }
