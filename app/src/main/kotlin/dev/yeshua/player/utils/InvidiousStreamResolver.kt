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
 * FLOSS fallback resolver backed by the Invidious API.
 *
 * Invidious (AGPL-3.0) is used here purely as a network API — plain HTTP+JSON,
 * no code linked — so it integrates like any web service. Multiple free
 * public instances are tried in order; instances that fail are skipped for
 * the rest of the session. Resolved URLs are mapped onto
 * [YTPlayerUtils.PlaybackData] so the regular ExoPlayer stack consumes them
 * unchanged.
 */
internal object InvidiousStreamResolver {
    private const val TAG = "InvidiousFallback"
    private const val INSTANCE_TIMEOUT_MS = 15_000L
    private const val DEFAULT_STREAM_EXPIRY_SECONDS = 21_000

    private val instances =
        listOf(
            "https://inv.invidious.woof.computer",
            "https://invidious.nerdvpn.de",
            "https://iv.melmac.space",
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
    private data class InvidiousAdaptiveFormat(
        val type: String? = null,
        val url: String? = null,
        val bitrate: Int? = null,
    )

    @Serializable
    private data class InvidiousVideoResponse(
        val title: String? = null,
        val author: String? = null,
        val lengthSeconds: Int? = null,
        val adaptiveFormats: List<InvidiousAdaptiveFormat> = emptyList(),
    )

    suspend fun resolve(
        videoId: String,
        audioQuality: AudioQuality,
    ): Result<YTPlayerUtils.PlaybackData> =
        runCatching {
            withContext(Dispatchers.IO) {
                val liveInstances = instances.filterNot { it in deadInstances }
                if (liveInstances.isEmpty()) {
                    throw IllegalStateException("All Invidious instances are marked dead this session")
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
                        "Invidious instance %s failed for %s, trying next",
                        instance,
                        videoId,
                    )
                }
                throw lastError ?: IllegalStateException("Invidious resolution failed for $videoId")
            }
        }

    private suspend fun resolveFromInstance(
        instance: String,
        videoId: String,
        audioQuality: AudioQuality,
    ): YTPlayerUtils.PlaybackData {
        val response: InvidiousVideoResponse =
            httpClient.get("$instance/api/v1/videos/$videoId").body()
        val audioOnly =
            response.adaptiveFormats.filter {
                it.type?.startsWith("audio/", ignoreCase = true) == true && !it.url.isNullOrBlank()
            }
        if (audioOnly.isEmpty()) {
            throw IllegalStateException("Invidious returned no audio formats for $videoId")
        }
        val pool = audioOnly.filter { (it.bitrate ?: 0) > 0 }.ifEmpty { audioOnly }
        val stream =
            when (audioQuality) {
                AudioQuality.LOW -> pool.minByOrNull { it.bitrate ?: 0 } ?: audioOnly.first()
                else -> pool.maxByOrNull { it.bitrate ?: 0 } ?: audioOnly.first()
            }
        val streamUrl =
            stream.url?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Invidious returned an empty stream URL for $videoId")
        Timber.tag(TAG).i(
            "Invidious resolved %s via %s: bitrate=%s type=%s",
            videoId,
            instance,
            stream.bitrate,
            stream.type,
        )
        return YTPlayerUtils.PlaybackData(
            audioConfig = null,
            videoDetails =
                PlayerResponse.VideoDetails(
                    videoId = videoId,
                    title = response.title,
                    author = response.author,
                    lengthSeconds =
                        response.lengthSeconds?.takeIf { it > 0 }?.toString(),
                ),
            playbackTracking = null,
            format = stream.toInnerTubeFormat(streamUrl),
            streamUrl = streamUrl,
            streamExpiresInSeconds = DEFAULT_STREAM_EXPIRY_SECONDS,
            authFingerprint = YouTube.currentPlaybackAuthState().fingerprint,
        )
    }

    private fun InvidiousAdaptiveFormat.toInnerTubeFormat(streamUrl: String): PlayerResponse.StreamingData.Format {
        val bitrate = bitrate?.takeIf { it > 0 } ?: 0
        val mime = type?.takeIf { it.isNotBlank() } ?: "audio/mp4"
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
