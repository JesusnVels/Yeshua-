/*
 * LunarTune (2026)
 * © cognitiveshadows03 — github.com/cognitiveshadows03
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package dev.yeshua.player.utils

import dev.yeshua.player.constants.AudioQuality
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.response.PlayerResponse
import timber.log.Timber
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * FLOSS fallback resolver backed by a user-configured Navidrome server.
 *
 * Navidrome (GPL-3.0) is a self-hosted music server exposing a
 * Subsonic-compatible REST API. Unlike Piped/Invidious there is no public
 * instance that can resolve YouTube video IDs — Navidrome serves the user's
 * own library and requires authentication — so this resolver only runs when
 * the user has configured a server (URL + username + password) in
 * Settings → Stream sources. When unconfigured it fails fast with no
 * network traffic. Servers that fail are skipped for the rest of the
 * session. Resolved URLs are mapped onto [YTPlayerUtils.PlaybackData] so
 * the regular ExoPlayer stack consumes them unchanged.
 *
 * Note: the Subsonic API has no YouTube-ID lookup; the track is located via
 * the server's `search3` endpoint using the video ID as the query. A match
 * is only expected when the user's library metadata references it.
 */
internal object NavidromeStreamResolver {
    private const val TAG = "NavidromeFallback"
    private const val SERVER_TIMEOUT_MS = 15_000L
    private const val DEFAULT_STREAM_EXPIRY_SECONDS = 21_000
    private const val SUBSONIC_API_VERSION = "1.16.1"
    private const val CLIENT_NAME = "yeshua"

    data class Config(
        val serverUrl: String,
        val username: String,
        val password: String,
    )

    private val deadServers: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

    private val httpClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) {
                requestTimeoutMillis = SERVER_TIMEOUT_MS
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = SERVER_TIMEOUT_MS
            }
        }
    }

    @Serializable
    private data class SubsonicError(
        val code: Int? = null,
        val message: String? = null,
    )

    @Serializable
    private data class SubsonicSong(
        val id: String? = null,
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val duration: Int? = null,
        val bitRate: Int? = null,
        val suffix: String? = null,
        val contentType: String? = null,
    )

    @Serializable
    private data class SubsonicSearchResult3(
        val song: List<SubsonicSong> = emptyList(),
    )

    @Serializable
    private data class SubsonicResponse(
        val status: String? = null,
        val error: SubsonicError? = null,
        val searchResult3: SubsonicSearchResult3? = null,
    )

    @Serializable
    private data class SubsonicEnvelope(
        @SerialName("subsonic-response") val response: SubsonicResponse? = null,
    )

    suspend fun resolve(
        videoId: String,
        audioQuality: AudioQuality,
        config: Config?,
    ): Result<YTPlayerUtils.PlaybackData> =
        runCatching {
            val cfg = config?.takeIf {
                it.serverUrl.isNotBlank() && it.username.isNotBlank() && it.password.isNotBlank()
            } ?: throw IllegalStateException("Navidrome is not configured; skipping")
            withContext(Dispatchers.IO) {
                val server = cfg.serverUrl.trim().trimEnd('/')
                if (server.isBlank()) {
                    throw IllegalStateException("Navidrome server URL is not valid")
                }
                if (server in deadServers) {
                    throw IllegalStateException("Navidrome server marked dead this session")
                }
                try {
                    withTimeout(SERVER_TIMEOUT_MS) {
                        resolveFromServer(server, cfg, videoId, audioQuality)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (e: Exception) {
                    deadServers.add(server)
                    Timber.tag(TAG).w(e, "Navidrome server %s failed for %s", server, videoId)
                    throw e
                }
            }
        }

    private suspend fun resolveFromServer(
        server: String,
        config: Config,
        videoId: String,
        audioQuality: AudioQuality,
    ): YTPlayerUtils.PlaybackData {
        val ping: SubsonicEnvelope =
            httpClient.get("$server/rest/ping") {
                authParams(config.username, config.password).forEach { (k, v) -> parameter(k, v) }
            }.body()
        if (ping.response?.status != "ok") {
            throw IllegalStateException(
                "Navidrome ping failed: ${ping.response?.error?.message ?: "unknown error"}",
            )
        }
        val search: SubsonicEnvelope =
            httpClient.get("$server/rest/search3") {
                authParams(config.username, config.password).forEach { (k, v) -> parameter(k, v) }
                parameter("query", videoId)
                parameter("songCount", 10)
            }.body()
        if (search.response?.status != "ok") {
            throw IllegalStateException(
                "Navidrome search failed: ${search.response?.error?.message ?: "unknown error"}",
            )
        }
        val songs = search.response?.searchResult3?.song.orEmpty().filter { !it.id.isNullOrBlank() }
        if (songs.isEmpty()) {
            throw IllegalStateException("Navidrome found no library match for $videoId")
        }
        val pool = songs.filter { (it.bitRate ?: 0) > 0 }.ifEmpty { songs }
        val song =
            when (audioQuality) {
                AudioQuality.LOW -> pool.minByOrNull { it.bitRate ?: 0 } ?: songs.first()
                else -> pool.maxByOrNull { it.bitRate ?: 0 } ?: songs.first()
            }
        val songId = song.id ?: throw IllegalStateException("Navidrome match has no id")
        val streamAuth = authParams(config.username, config.password)
        val streamUrl =
            buildString {
                append("$server/rest/stream?id=${songId.encodeUrl()}")
                streamAuth.forEach { (k, v) -> append("&$k=${v.encodeUrl()}") }
            }
        Timber.tag(TAG).i(
            "Navidrome resolved %s via %s: title=%s bitrate=%s",
            videoId,
            server,
            song.title,
            song.bitRate,
        )
        return YTPlayerUtils.PlaybackData(
            audioConfig = null,
            videoDetails =
                PlayerResponse.VideoDetails(
                    videoId = videoId,
                    title = song.title,
                    author = song.artist,
                    lengthSeconds = song.duration?.takeIf { it > 0 }?.toString(),
                ),
            playbackTracking = null,
            format = song.toInnerTubeFormat(streamUrl),
            streamUrl = streamUrl,
            streamExpiresInSeconds = DEFAULT_STREAM_EXPIRY_SECONDS,
            authFingerprint = YouTube.currentPlaybackAuthState().fingerprint,
        )
    }

    private fun authParams(
        username: String,
        password: String,
    ): Map<String, String> {
        val salt = UUID.randomUUID().toString().replace("-", "")
        val token = md5Hex(password + salt)
        return mapOf(
            "u" to username,
            "t" to token,
            "s" to salt,
            "v" to SUBSONIC_API_VERSION,
            "c" to CLIENT_NAME,
            "f" to "json",
        )
    }

    private fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun String.encodeUrl(): String = URLEncoder.encode(this, Charsets.UTF_8.name())

    private fun SubsonicSong.toInnerTubeFormat(streamUrl: String): PlayerResponse.StreamingData.Format {
        val bitrate = (bitRate?.takeIf { it > 0 } ?: 0) * 1000
        val mime =
            contentType?.takeIf { it.isNotBlank() }
                ?: when (suffix?.lowercase()) {
                    "mp3" -> "audio/mpeg"
                    "ogg", "oga" -> "audio/ogg"
                    "opus" -> "audio/opus"
                    "flac" -> "audio/flac"
                    "m4a" -> "audio/mp4"
                    "wav" -> "audio/wav"
                    else -> "audio/mpeg"
                }
        return PlayerResponse.StreamingData.Format(
            itag = -1,
            url = streamUrl,
            mimeType = mime,
            bitrate = bitrate,
            width = null,
            height = null,
            contentLength = null,
            quality = if (bitrate >= 128_000) "medium" else "tiny",
            fps = null,
            qualityLabel = null,
            averageBitrate = bitrate.takeIf { it > 0 },
            audioQuality =
                when {
                    bitrate >= 128_000 -> "AUDIO_QUALITY_HIGH"
                    bitrate >= 64_000 -> "AUDIO_QUALITY_MEDIUM"
                    bitrate > 0 -> "AUDIO_QUALITY_LOW"
                    else -> null
                },
            approxDurationMs = null,
            audioSampleRate = null,
            audioChannels = null,
            loudnessDb = null,
            lastModified = null,
            signatureCipher = null,
            cipher = null,
        )
    }
}
