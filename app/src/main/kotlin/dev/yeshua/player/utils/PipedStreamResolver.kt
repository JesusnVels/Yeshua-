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
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.response.PlayerResponse
import timber.log.Timber
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * FLOSS fallback resolver backed by the Piped API.
 *
 * Piped (GPL-3.0) exposes YouTube stream URLs over plain HTTP+JSON with no
 * API key and no proprietary SDK. Multiple free public instances are tried
 * in order; instances that fail are skipped for the rest of the session.
 * Resolved URLs are mapped onto [YTPlayerUtils.PlaybackData] so the regular
 * ExoPlayer stack consumes them unchanged.
 */
internal object PipedStreamResolver {
    private const val TAG = "PipedFallback"
    private const val INSTANCE_TIMEOUT_MS = 15_000L
    private const val DEFAULT_STREAM_EXPIRY_SECONDS = 21_000

    private val instances =
        listOf(
            "https://pipedapi.kavin.rocks",
            "https://pipedapi.reallyaweso.me",
            "https://pipedapi.adminforge.de",
            "https://api.piped.private.coffee",
        )

    private val deadInstances: MutableSet<String> =
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
                requestTimeoutMillis = INSTANCE_TIMEOUT_MS
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = INSTANCE_TIMEOUT_MS
            }
        }
    }

    @Serializable
    private data class PipedAudioStream(
        val url: String? = null,
        val bitrate: Int? = null,
        val codec: String? = null,
        val mimeType: String? = null,
    )

    @Serializable
    private data class PipedStreamsResponse(
        val title: String? = null,
        val uploaderName: String? = null,
        val duration: Double? = null,
        val audioStreams: List<PipedAudioStream> = emptyList(),
    )

    suspend fun resolve(
        videoId: String,
        audioQuality: AudioQuality,
    ): Result<YTPlayerUtils.PlaybackData> =
        runCatching {
            withContext(Dispatchers.IO) {
                val liveInstances = instances.filterNot { it in deadInstances }
                if (liveInstances.isEmpty()) {
                    throw IllegalStateException("All Piped instances are marked dead this session")
                }
                var lastError: Throwable? = null
                for (instance in liveInstances) {
                    val result =
                        runCatching {
                            withTimeout(INSTANCE_TIMEOUT_MS) {
                                resolveFromInstance(instance, videoId, audioQuality)
                            }
                        }
                    if (result.isSuccess) return@withContext result.getOrThrow()
                    lastError = result.exceptionOrNull()
                    deadInstances.add(instance)
                    Timber.tag(TAG).w(
                        lastError,
                        "Piped instance %s failed for %s, trying next",
                        instance,
                        videoId,
                    )
                }
                throw lastError ?: IllegalStateException("Piped resolution failed for $videoId")
            }
        }

    private suspend fun resolveFromInstance(
        instance: String,
        videoId: String,
        audioQuality: AudioQuality,
    ): YTPlayerUtils.PlaybackData {
        val response: PipedStreamsResponse =
            httpClient.get("$instance/streams/$videoId").body()
        val stream = selectAudioStream(response.audioStreams, audioQuality, videoId)
        val streamUrl =
            stream.url?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Piped returned an empty stream URL for $videoId")
        Timber.tag(TAG).i(
            "Piped resolved %s via %s: bitrate=%s codec=%s",
            videoId,
            instance,
            stream.bitrate,
            stream.codec,
        )
        return YTPlayerUtils.PlaybackData(
            audioConfig = null,
            videoDetails =
                PlayerResponse.VideoDetails(
                    videoId = videoId,
                    title = response.title,
                    author = response.uploaderName,
                    lengthSeconds =
                        response.duration?.takeIf { it > 0 }?.toLong()?.toString(),
                ),
            playbackTracking = null,
            format = stream.toInnerTubeFormat(streamUrl),
            streamUrl = streamUrl,
            streamExpiresInSeconds = DEFAULT_STREAM_EXPIRY_SECONDS,
            authFingerprint = YouTube.currentPlaybackAuthState().fingerprint,
        )
    }

    private fun selectAudioStream(
        streams: List<PipedAudioStream>,
        audioQuality: AudioQuality,
        videoId: String,
    ): PipedAudioStream {
        val usable = streams.filter { !it.url.isNullOrBlank() }
        if (usable.isEmpty()) {
            throw IllegalStateException("Piped returned no usable audio streams for $videoId")
        }
        val pool = usable.filter { (it.bitrate ?: 0) > 0 }.ifEmpty { usable }
        return when (audioQuality) {
            AudioQuality.LOW -> pool.minByOrNull { it.bitrate ?: 0 } ?: usable.first()
            else -> pool.maxByOrNull { it.bitrate ?: 0 } ?: usable.first()
        }
    }

    private fun PipedAudioStream.toInnerTubeFormat(streamUrl: String): PlayerResponse.StreamingData.Format {
        val bitrate = bitrate?.takeIf { it > 0 } ?: 0
        val mime = mimeType?.takeIf { it.isNotBlank() } ?: "audio/mp4"
        val codecSuffix = codec?.takeIf { it.isNotBlank() }?.let { "; codecs=\"$it\"" }.orEmpty()
        return PlayerResponse.StreamingData.Format(
            itag = -1,
            url = streamUrl,
            mimeType = "$mime$codecSuffix",
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
