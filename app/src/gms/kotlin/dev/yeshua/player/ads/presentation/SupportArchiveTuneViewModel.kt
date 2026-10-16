/*
 * LunarTune (2026)
 * © cognitiveshadows03 — github.com/cognitiveshadows03
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package dev.yeshua.player.ads.presentation

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import dev.yeshua.player.ads.domain.OpenSupportPageUseCase
import dev.yeshua.player.ads.domain.SupportPageOpenResult
import javax.inject.Inject

internal sealed interface SupportYeshuaScreenState {
    @Immutable
    data object Loading : SupportYeshuaScreenState

    @Immutable
    data object Success : SupportYeshuaScreenState

    @Immutable
    data object Empty : SupportYeshuaScreenState

    @Immutable
    data class Error(
        val reason: SupportYeshuaError,
    ) : SupportYeshuaScreenState
}

internal enum class SupportYeshuaError {
    PageUnavailable,
}

internal enum class SupportYeshuaUiEvent {
    OpenFailed,
}

@HiltViewModel
internal class SupportYeshuaViewModel
    @Inject
    constructor(
        private val openSupportPage: OpenSupportPageUseCase,
    ) : ViewModel() {
        private val _screenState =
            MutableStateFlow<SupportYeshuaScreenState>(SupportYeshuaScreenState.Success)
        val screenState: StateFlow<SupportYeshuaScreenState> = _screenState.asStateFlow()

        private val eventChannel = Channel<SupportYeshuaUiEvent>(Channel.BUFFERED)
        val events = eventChannel.receiveAsFlow()

        fun onSupportYeshuaClick() {
            if (_screenState.value is SupportYeshuaScreenState.Loading) return
            _screenState.value = SupportYeshuaScreenState.Loading
            when (openSupportPage()) {
                SupportPageOpenResult.Opened -> {
                    _screenState.value = SupportYeshuaScreenState.Success
                }

                SupportPageOpenResult.Unavailable -> {
                    _screenState.value =
                        SupportYeshuaScreenState.Error(SupportYeshuaError.PageUnavailable)
                    eventChannel.trySend(SupportYeshuaUiEvent.OpenFailed)
                }
            }
        }
    }
