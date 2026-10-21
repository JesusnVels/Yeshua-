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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.response.PlayerResponse
import timber.log.Timber

/**
 * Fallback resolver backed by the Audius discovery API.
 *
 * Audius is a free, public music catalog: the discovery API needs no API
 * key, no account and no self-hosted server. Tracks are found with the
 * search endpoint using the song's title (and artist when known) and the
 * best fuzzy match wins.
 *
 * NOTE: Audius streams are 320 kbps MP3 — high-quality LOSSY, not
 * lossless. This resolver is a coverage fallback for songs the lossless
 * and YouTube-ID sources miss, never an audio-quality upgrade.
 * Resolved URLs are mapped onto [YTPlayerUtils.PlaybackData] so the
 * regular ExoPlayer stack consumes them unchanged.
 */
internal object AudiusStreamResolver {
    private const val TAG = "AudiusFallback"
    private const val RESOLVE_TIMEOUT_MS = 15_000L
    private const val DEFAULT_STREAM_EXPIRY_SECONDS = 21_000
    private const val API_BASE = "https://discoveryprovider.audius.co/v1"
    private const val APP_NAME = "Yeshua"
    private const val AUDIUS_BITRATE_BPS = 320_000
    private const val MIN_MATCH_SCORE = 0.6
    private const val TITLE_WEIGHT = 0.6
    private const val ARTIST_WEIGHT = 0.4

    @Volatile
    private var hostUnreachable = false

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
                requestTimeoutMillis = RESOLVE_TIMEOUT_MS
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = RESOLVE_TIMEOUT_MS
            }
        }
    }

    @Serializable
    private data class AudiusUser(
        val id: String? = null,
        val handle: String? = null,
        val name: String? = null,
    )

    @Serializable
    private data class AudiusTrack(
        val id: String? = null,
        val title: String? = null,
        val duration: Int? = null,
        val user: AudiusUser? = null,
        val is_available: Boolean? = null,
        val is_delete: Boolean? = null,
    )

    @Serializable
    private data class AudiusSearchResponse(
        val data: List<AudiusTrack> = emptyList(),
    )

    suspend fun resolve(
        videoId: String,
        audioQuality: AudioQuality,
        title: String?,
        artist: String?,
    ): Result<YTPlayerUtils.PlaybackData> =
        runCatching {
            val queryTitle = title?.trim().orEmpty()
            if (queryTitle.isBlank()) {
                throw IllegalStateException("Audius needs a track title; skipping")
            }
            if (hostUnreachable) {
                throw IllegalStateException("Audius discovery API marked unreachable this session")
            }
            withContext(Dispatchers.IO) {
                withTimeout(RESOLVE_TIMEOUT_MS) {
                    resolveFromAudius(videoId, queryTitle, artist?.trim()?.takeIf { it.isNotBlank() })
                }
            }
        }

    private suspend fun resolveFromAudius(
        videoId: String,
        title: String,
        artist: String?,
    ): YTPlayerUtils.PlaybackData {
        val query =
            listOfNotNull(title, artist)
                .joinToString(" ")
                .trim()
        val response: AudiusSearchResponse =
            try {
                httpClient.get("$API_BASE/tracks/search") {
                    parameter("query", query)
                    parameter("app_name", APP_NAME)
                }.body()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                hostUnreachable = true
                Timber.tag(TAG).w(e, "Audius search failed; marking host unreachable")
                throw e
            }

        val candidates =
            response.data.filter { track ->
                !track.id.isNullOrBlank() &&
                    !track.title.isNullOrBlank() &&
                    track.is_available != false &&
                    track.is_delete != true
            }
        if (candidates.isEmpty()) {
            throw IllegalStateException("Audius found no match for \"$title\"")
        }

        val winner =
            candidates
                .map { track -> track to matchScore(track, title, artist) }
                .maxByOrNull { (_, score) -> score }
                ?.takeIf { (_, score) -> score >= MIN_MATCH_SCORE }
                ?.first
                ?: throw IllegalStateException("Audius found no confident match for \"$title\"")

        // The stream endpoint 302-redirects to the track's 320k MP3 on a
        // content node; the player/download stack follows redirects.
        val streamUrl = "$API_BASE/tracks/${winner.id}/stream?app_name=$APP_NAME"
        val durationSeconds = winner.duration?.takeIf { it > 0 }
        Timber.tag(TAG).i(
            "Audius resolved %s via track %s: \"%s\" by %s",
            videoId,
            winner.id,
            winner.title,
            winner.user?.name ?: winner.user?.handle,
        )
        return YTPlayerUtils.PlaybackData(
            audioConfig = null,
            videoDetails =
                PlayerResponse.VideoDetails(
                    videoId = videoId,
                    title = winner.title ?: title,
                    author = winner.user?.name?.takeIf { it.isNotBlank() } ?: artist,
                    lengthSeconds = durationSeconds?.toString(),
                ),
            playbackTracking = null,
            format = winner.toInnerTubeFormat(streamUrl, durationSeconds),
            streamUrl = streamUrl,
            streamExpiresInSeconds = DEFAULT_STREAM_EXPIRY_SECONDS,
            authFingerprint = YouTube.currentPlaybackAuthState().fingerprint,
        )
    }

    private fun matchScore(
        track: AudiusTrack,
        title: String,
        artist: String?,
    ): Double {
        val titleScore = FuzzyMatcher.jaroWinkler(track.title.orEmpty(), title)
        val candidateArtist =
            track.user?.name?.takeIf { it.isNotBlank() }
                ?: track.user?.handle?.takeIf { it.isNotBlank() }
        if (artist == null || candidateArtist == null) return titleScore
        val artistScore = FuzzyMatcher.jaroWinkler(candidateArtist, artist)
        return (titleScore * TITLE_WEIGHT) + (artistScore * ARTIST_WEIGHT)
    }

    private fun AudiusTrack.toInnerTubeFormat(
        streamUrl: String,
        durationSeconds: Int?,
    ): PlayerResponse.StreamingData.Format =
        PlayerResponse.StreamingData.Format(
            itag = -1,
            url = streamUrl,
            mimeType = "audio/mpeg",
            bitrate = AUDIUS_BITRATE_BPS,
            width = null,
            height = null,
            contentLength = null,
            quality = "medium",
            fps = null,
            qualityLabel = null,
            averageBitrate = AUDIUS_BITRATE_BPS,
            audioQuality = "AUDIO_QUALITY_HIGH",
            approxDurationMs = durationSeconds?.let { (it * 1000L).toString() },
            audioSampleRate = null,
            audioChannels = null,
            loudnessDb = null,
            lastModified = null,
            signatureCipher = null,
            cipher = null,
        )
}
