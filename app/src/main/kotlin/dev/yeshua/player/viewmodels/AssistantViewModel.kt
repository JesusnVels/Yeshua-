/*
 * LunarTune (2026)
 * © cognitiveshadows03 — github.com/cognitiveshadows03
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package dev.yeshua.player.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yeshua.player.constants.AssistantEnabledKey
import dev.yeshua.player.db.MusicDatabase
import dev.yeshua.player.extensions.toMediaItem
import dev.yeshua.player.lyrics.LyricsHelper
import dev.yeshua.player.models.MediaMetadata
import dev.yeshua.player.models.toMediaMetadata
import dev.yeshua.player.playback.queues.ListQueue
import dev.yeshua.player.utils.AssistantKnowledge
import dev.yeshua.player.utils.AssistantRepository
import dev.yeshua.player.utils.NetworkConnectivityObserver
import dev.yeshua.player.utils.PreferenceStore
import dev.yeshua.player.utils.reportException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.SongItem
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.inject.Inject

/** One chat bubble. */
data class AssistantMessage(
    val isUser: Boolean,
    val text: String,
)

/** A playback command for the UI layer to execute via PlayerConnection. */
sealed interface AssistantPlaybackCommand {
    data class Play(
        val title: String,
        val queue: ListQueue,
    ) : AssistantPlaybackCommand

    data class Queue(
        val title: String,
        val items: List<androidx.media3.common.MediaItem>,
    ) : AssistantPlaybackCommand
}

@HiltViewModel
class AssistantViewModel
    @Inject
    constructor(
        private val database: MusicDatabase,
        private val lyricsHelper: LyricsHelper,
        private val networkConnectivity: NetworkConnectivityObserver,
    ) : ViewModel() {
        private val _messages = MutableStateFlow<List<AssistantMessage>>(emptyList())
        val messages: StateFlow<List<AssistantMessage>> = _messages.asStateFlow()

        private val _isLoading = MutableStateFlow(false)
        val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

        private val _pendingCommand = MutableStateFlow<AssistantPlaybackCommand?>(null)
        val pendingCommand: StateFlow<AssistantPlaybackCommand?> = _pendingCommand.asStateFlow()

        fun onCommandHandled() {
            _pendingCommand.value = null
        }

        fun clearChat() {
            _messages.value = emptyList()
        }

        private fun appendAssistant(text: String) {
            _messages.update { it + AssistantMessage(isUser = false, text = text) }
        }

        fun send(
            userText: String,
            nowPlaying: MediaMetadata?,
        ) {
            val text = userText.trim()
            if (text.isEmpty() || _isLoading.value) return
            _messages.update { it + AssistantMessage(isUser = true, text = text) }
            _isLoading.value = true

            viewModelScope.launch(Dispatchers.IO) {
                try {
                    handleTurn(text, nowPlaying)
                } catch (e: Exception) {
                    reportException(e)
                    appendAssistant("Something went wrong on my end. Try again in a moment.")
                } finally {
                    _isLoading.value = false
                }
            }
        }

        private suspend fun handleTurn(
            text: String,
            nowPlaying: MediaMetadata?,
        ) {
            // 1. Local help answers work even offline.
            AssistantKnowledge.findLocalAnswer(text)?.let { answer ->
                appendAssistant(answer)
                return
            }

            // 2. Toggle + connectivity gates.
            if (PreferenceStore.get(AssistantEnabledKey) == false) {
                appendAssistant("The assistant is turned off. You can re-enable it from Settings.")
                return
            }
            val online =
                try {
                    networkConnectivity.isCurrentlyConnected()
                } catch (_: Exception) {
                    true
                }
            if (!online) {
                appendAssistant("I'm offline right now — I need a connection to think. Your music still plays fine without me.")
                return
            }

            // 3. Build the personalized context (library + stats aggregates only, summarized).
            val contextBlock = buildContextBlock(nowPlaying)

            val history =
                _messages.value
                    .takeLast(8)
                    .map { m ->
                        AssistantRepository.ChatMessage(
                            role = if (m.isUser) "user" else "assistant",
                            content = m.text,
                        )
                    }

            val system =
                "You are Yeshua's built-in music assistant, running free inside the Yeshua Android music player. " +
                    "Be helpful, concise, and a little playful. " +
                    AssistantKnowledge.capabilitySummary() +
                    "\n\n$contextBlock"

            val reply =
                AssistantRepository
                    .chat(listOf(AssistantRepository.ChatMessage("system", system)) + history)
                    .getOrElse {
                        appendAssistant("I couldn't reach my brain just now. Check your connection and try again.")
                        return
                    }

            // 4. Parse an optional trailing JSON action block.
            val (displayText, action) = splitActionBlock(reply)
            if (action != null) {
                executeAction(action, displayText.ifBlank { null }, nowPlaying)
            } else {
                appendAssistant(displayText.ifBlank { "Got it." })
            }
        }

        /** Summarized library/stats context. Never includes account, device, or location data. */
        private suspend fun buildContextBlock(nowPlaying: MediaMetadata?): String {
            val monthAgo =
                LocalDateTime.now().minusDays(30).toInstant(ZoneOffset.UTC).toEpochMilli()
            val topSongs =
                runCatching {
                    database.mostPlayedSongs(fromTimeStamp = monthAgo, limit = 25).first()
                }.getOrDefault(emptyList())
            val topArtists =
                runCatching {
                    database.mostPlayedArtists(fromTimeStamp = monthAgo, limit = 15).first()
                }.getOrDefault(emptyList())
            val forgotten =
                runCatching {
                    database.forgottenFavorites().first()
                }.getOrDefault(emptyList())

            fun songLine(s: dev.yeshua.player.db.entities.Song): String =
                "${s.title} — ${s.artists.joinToString(", ") { it.name }}"

            return buildString {
                appendLine("User's listening summary (last 30 days):")
                if (topSongs.isNotEmpty()) {
                    appendLine("Top songs:")
                    topSongs.forEach { appendLine("- ${songLine(it)}") }
                }
                if (topArtists.isNotEmpty()) {
                    appendLine("Top artists: ${topArtists.joinToString(", ") { it.artist.name }}")
                }
                if (forgotten.isNotEmpty()) {
                    appendLine("Haven't heard in a while:")
                    forgotten.take(15).forEach { appendLine("- ${songLine(it)}") }
                }
                appendLine(
                    "Now playing: " +
                        (nowPlaying?.let { "${it.title} — ${it.artists.joinToString(", ") { a -> a.name }}" } ?: "nothing"),
                )
            }
        }

        private fun splitActionBlock(reply: String): Pair<String, JSONObject?> {
            var text = reply.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val lines = text.lines()
            val last = lines.lastOrNull()?.trim().orEmpty()
            if (last.startsWith("{") && last.endsWith("}") && last.contains("\"action\"")) {
                return try {
                    val action = JSONObject(last)
                    Pair(lines.dropLast(1).joinToString("\n").trim(), action)
                } catch (_: Exception) {
                    Pair(text, null)
                }
            }
            return Pair(text, null)
        }

        private suspend fun executeAction(
            action: JSONObject,
            displayText: String?,
            nowPlaying: MediaMetadata?,
        ) {
            when (action.optString("action")) {
                "play" -> {
                    val query = action.optString("query").ifBlank { null } ?: return
                    val item = searchTopSong(query) ?: run {
                        appendAssistant((displayText?.plus("\n\n") ?: "") + "I couldn't find \"$query\". Try a different spelling?")
                        return
                    }
                    val mediaItem = withContext(Dispatchers.IO) { item.toMediaItem() }
                    _pendingCommand.value =
                        AssistantPlaybackCommand.Play(
                            title = "${item.title} — ${item.artists.joinToString { it.name }}",
                            queue = ListQueue(title = item.title, items = listOf(mediaItem)),
                        )
                    appendAssistant(displayText ?: "Playing ${item.title}.")
                }

                "queue" -> {
                    val query = action.optString("query").ifBlank { null } ?: return
                    val item = searchTopSong(query) ?: run {
                        appendAssistant((displayText?.plus("\n\n") ?: "") + "I couldn't find \"$query\".")
                        return
                    }
                    val mediaItem = withContext(Dispatchers.IO) { item.toMediaItem() }
                    _pendingCommand.value =
                        AssistantPlaybackCommand.Queue(
                            title = "${item.title} — ${item.artists.joinToString { it.name }}",
                            items = listOf(mediaItem),
                        )
                    appendAssistant(displayText ?: "Queued ${item.title}.")
                }

                "play_most_played" -> {
                    val monthAgo =
                        LocalDateTime.now().minusDays(30).toInstant(ZoneOffset.UTC).toEpochMilli()
                    val songs =
                        runCatching {
                            database.mostPlayedSongs(fromTimeStamp = monthAgo, limit = 50).first()
                        }.getOrDefault(emptyList())
                    if (songs.isEmpty()) {
                        appendAssistant("You don't have enough listening history yet for that — play some music first and I'll learn your taste.")
                        return
                    }
                    val items = songs.map { it.toMediaItem() }
                    _pendingCommand.value =
                        AssistantPlaybackCommand.Play(
                            title = "Your most played",
                            queue = ListQueue(title = "Most played", items = items),
                        )
                    appendAssistant(displayText ?: "Playing your most-played songs from the last 30 days.")
                }

                "play_forgotten" -> {
                    val songs =
                        runCatching {
                            database.forgottenFavorites().first()
                        }.getOrDefault(emptyList()).take(50)
                    if (songs.isEmpty()) {
                        appendAssistant("Nothing's gone stale in your library — impressive.")
                        return
                    }
                    val items = songs.map { it.toMediaItem() }
                    _pendingCommand.value =
                        AssistantPlaybackCommand.Play(
                            title = "Forgotten favorites",
                            queue = ListQueue(title = "Forgotten favorites", items = items),
                        )
                    appendAssistant(displayText ?: "Digging up ${songs.size} songs you haven't heard in a while.")
                }

                "lyrics" -> {
                    val query = action.optString("query").ifBlank { null }
                    val metadata: MediaMetadata? =
                        if (query == null) {
                            nowPlaying
                        } else {
                            searchTopSong(query)?.toMediaMetadata()
                        }
                    if (metadata == null) {
                        appendAssistant("Nothing's playing right now — tell me a song name and I'll fetch its lyrics.")
                        return
                    }
                    val lyrics =
                        runCatching { lyricsHelper.getLyrics(metadata) }
                            .getOrDefault("")
                            .take(1500)
                    if (lyrics.isBlank() || lyrics == dev.yeshua.player.db.entities.LyricsEntity.LYRICS_NOT_FOUND) {
                        appendAssistant("I couldn't find lyrics for ${metadata.title}.")
                    } else {
                        appendAssistant(
                            (displayText?.plus("\n\n") ?: "") +
                                "Lyrics for ${metadata.title}:\n\n$lyrics",
                        )
                    }
                }

                "identify" -> {
                    val snippet = action.optString("snippet").ifBlank { null } ?: return
                    val results = searchSongs(snippet, 3)
                    if (results.isEmpty()) {
                        appendAssistant("I couldn't place those lyrics. Give me a few more words?")
                    } else {
                        val top = results.first()
                        val others =
                            results.drop(1).joinToString("\n") { "• ${it.title} — ${it.artists.joinToString { a -> a.name }}" }
                        val answer =
                            buildString {
                                if (!displayText.isNullOrBlank()) appendLine(displayText).appendLine()
                                append("That sounds like ${top.title} by ${top.artists.joinToString { it.name }}.")
                                if (others.isNotBlank()) append("\n\nAlso possible:\n$others")
                                append("\n\nWant me to play it?")
                            }
                        appendAssistant(answer)
                    }
                }

                "help" -> {
                    val topic = action.optString("topic").ifBlank { action.optString("query") }
                    val answer = AssistantKnowledge.findLocalAnswer(topic.ifBlank { "help" })
                    appendAssistant(answer ?: "Ask me about player designs, sleep timer, stats, downloads, lyrics, or anything else in the app.")
                }

                else -> appendAssistant(displayText ?: "Got it.")
            }
        }

        private suspend fun searchTopSong(query: String): SongItem? = searchSongs(query, 1).firstOrNull()

        private suspend fun searchSongs(
            query: String,
            limit: Int,
        ): List<SongItem> =
            runCatching {
                YouTube
                    .search(query, YouTube.SearchFilter.FILTER_SONG)
                    .getOrNull()
                    ?.items
                    ?.filterIsInstance<SongItem>()
                    ?.take(limit)
                    .orEmpty()
            }.getOrDefault(emptyList())
    }
