/*
 * LunarTune (2026)
 * © cognitiveshadows03 — github.com/cognitiveshadows03
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package dev.yeshua.player.utils

import android.util.Base64
import dev.yeshua.player.constants.AudioQuality
import dev.yeshua.player.constants.TidalAccessTokenKey
import dev.yeshua.player.constants.TidalCountryCodeKey
import dev.yeshua.player.constants.TidalRefreshTokenKey
import dev.yeshua.player.constants.TidalUserIdKey
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.response.PlayerResponse
import timber.log.Timber

/**
 * Lossless resolver backed by the user's OWN linked Tidal account
 * (see [TidalAuth] for the device-code linking flow). Tidal's catalog is
 * FLAC end to end: HI_RES (up to 24-bit/192 kHz) for HiFi-tier accounts,
 * LOSSLESS (16-bit/44.1 kHz) below that, so a match here is a genuine
 * quality upgrade, on par with Monochrome and ahead of the lossy public
 * mirrors in the fallback chains.
 *
 * Auth model: this resolver OWNS NO preference writes during playback.
 * Reads go through [PreferenceStore] (the same synchronous snapshot
 * PerSongSettings/ChartsApi use). On a 401 it refreshes the access token
 * once via the stored refresh token, keeps the fresh pair in memory so
 * the retried request (and the rest of the session) uses it immediately,
 * and hands the new pair to [onTokensRefreshed] — MusicService assigns
 * that callback at startup and persists via its own DataStore handle,
 * mirroring how Navidrome's config is owned by its callers, not by the
 * resolver. When no token is stored (account not linked) the resolver
 * fails fast and silently so chains fall through with no network traffic.
 *
 * Like Navidrome there is no YouTube-ID lookup: tracks are matched by
 * title/artist search against the linked account's country catalog.
 * Resolved URLs are mapped onto [YTPlayerUtils.PlaybackData] so the
 * regular ExoPlayer stack consumes them unchanged.
 */
internal object TidalStreamResolver {
    /**
     * Dedicated cache key prefix for Tidal streams resolved at the
     * data-spec level (per-song lossless upgrades), mirroring
     * Monochrome/InternetArchive so Tidal bytes never mix with YouTube
     * Music spans in the Media3 caches for the same song.
     */
    const val CACHE_KEY_PREFIX = "tidal:"

    fun cacheKeyFor(songId: String): String = CACHE_KEY_PREFIX + songId

    private const val TAG = "TidalResolver"
    private const val RESOLVE_TIMEOUT_MS = 15_000L
    private const val SEARCH_LIMIT = 5
    private const val MIN_MATCH_SCORE = 0.6
    private const val TITLE_WEIGHT = 0.6
    private const val ARTIST_WEIGHT = 0.4
    private const val QUALITY_HI_RES = "HI_RES"
    private const val QUALITY_LOSSLESS = "LOSSLESS"
    private const val BTS_MANIFEST_MIME = "application/vnd.tidal.bts"
    // Tidal's own signed stream URLs live ~1h; stay well inside that.
    private const val TIDAL_STREAM_EXPIRY_SECONDS = 3_000

    /** Snapshot of the linked account's auth state, read from DataStore. */
    data class AuthState(
        val accessToken: String,
        val refreshToken: String,
        val countryCode: String,
        val userId: Long,
    )

    /**
     * Fresh tokens produced by an in-playback refresh. Assigned by
     * MusicService (see TIDAL_WIRING.md); may be null in contexts where
     * nothing persists them — the in-memory copy below still keeps the
     * current session working.
     */
    @Volatile
    var onTokensRefreshed: ((AuthState) -> Unit)? = null

    @Volatile
    private var refreshedState: AuthState? = null

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
    private data class TidalArtist(
        val id: Long = 0,
        val name: String? = null,
    )

    @Serializable
    private data class TidalTrackItem(
        val id: Long = 0,
        val title: String? = null,
        val duration: Int? = null,
        val artist: TidalArtist? = null,
        val artists: List<TidalArtist> = emptyList(),
        val audioQuality: String? = null,
        val streamReady: Boolean? = null,
        val allowStreaming: Boolean? = null,
    )

    @Serializable
    private data class TidalSearchResponse(
        val items: List<TidalTrackItem> = emptyList(),
        val totalNumberOfItems: Int = 0,
    )

    @Serializable
    private data class PlaybackInfoResponse(
        val trackId: Long = 0,
        val audioQuality: String? = null,
        val bitDepth: Int? = null,
        val sampleRate: Int? = null,
        val manifestMimeType: String? = null,
        val manifest: String? = null,
    )

    /** Decoded BTS (base64 JSON) stream manifest. */
    @Serializable
    private data class BtsManifest(
        val mimeType: String? = null,
        val codecs: String? = null,
        val encryptionType: String? = null,
        val urls: List<String> = emptyList(),
    )

    private class UnauthorizedException : Exception("Tidal access token rejected (401)")

    /**
     * Current auth state, or null when no Tidal account is linked.
     * A token pair refreshed earlier in this session wins over the
     * persisted (pre-refresh) pair while the same account stays linked.
     */
    fun currentAuthState(): AuthState? {
        val persisted = readPersistedAuthState()
        if (persisted == null) {
            refreshedState = null
            return null
        }
        val refreshed = refreshedState
        return if (refreshed != null && refreshed.userId == persisted.userId) {
            refreshed
        } else {
            persisted
        }
    }

    /** Drop the in-memory refreshed tokens (called on sign-out). */
    fun clearInMemoryAuth() {
        refreshedState = null
    }

    private fun readPersistedAuthState(): AuthState? {
        val accessToken =
            PreferenceStore.get(TidalAccessTokenKey)?.takeIf { it.isNotBlank() } ?: return null
        val refreshToken =
            PreferenceStore.get(TidalRefreshTokenKey)?.takeIf { it.isNotBlank() } ?: return null
        val countryCode =
            PreferenceStore.get(TidalCountryCodeKey)?.takeIf { it.isNotBlank() } ?: "US"
        val userId = PreferenceStore.get(TidalUserIdKey) ?: 0L
        return AuthState(
            accessToken = accessToken,
            refreshToken = refreshToken,
            countryCode = countryCode,
            userId = userId,
        )
    }

    suspend fun resolve(
        videoId: String,
        audioQuality: AudioQuality,
        title: String?,
        artist: String?,
    ): Result<YTPlayerUtils.PlaybackData> =
        runCatching {
            val queryTitle = title?.trim().orEmpty()
            if (queryTitle.isBlank()) {
                throw IllegalStateException("Tidal needs a track title; skipping")
            }
            val auth =
                currentAuthState()
                    ?: throw IllegalStateException("Tidal account not linked; skipping")
            withContext(Dispatchers.IO) {
                withTimeout(RESOLVE_TIMEOUT_MS) {
                    resolveFromTidal(
                        videoId = videoId,
                        title = queryTitle,
                        artist = artist?.trim()?.takeIf { it.isNotBlank() },
                        auth = auth,
                    )
                }
            }
        }

    private suspend fun resolveFromTidal(
        videoId: String,
        title: String,
        artist: String?,
        auth: AuthState,
    ): YTPlayerUtils.PlaybackData {
        val track =
            callWithAuthRetry(auth) { active ->
                searchBestMatch(active, title, artist)
            } ?: throw IllegalStateException("Tidal found no confident match for \"$title\"")

        // HI_RES first; accounts without HiFi entitlement get an error
        // from playbackinfo, in which case LOSSLESS is the ceiling.
        val playback =
            try {
                callWithAuthRetry(auth) { active ->
                    fetchPlayback(active, track.id, QUALITY_HI_RES)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: UnauthorizedException) {
                throw e
            } catch (hiResError: Exception) {
                Timber.tag(TAG).d(hiResError, "Tidal HI_RES playbackinfo failed; trying LOSSLESS")
                callWithAuthRetry(auth) { active ->
                    fetchPlayback(active, track.id, QUALITY_LOSSLESS)
                }
            }

        val durationSeconds = track.duration?.takeIf { it > 0 }
        Timber.tag(TAG).i(
            "Tidal resolved %s via track %d: \"%s\" (%s)",
            videoId,
            track.id,
            track.title,
            playback.qualityLabel,
        )
        return YTPlayerUtils.PlaybackData(
            audioConfig = null,
            videoDetails =
                PlayerResponse.VideoDetails(
                    videoId = videoId,
                    title = track.title ?: title,
                    author = track.artistNames() ?: artist,
                    lengthSeconds = durationSeconds?.toString(),
                ),
            playbackTracking = null,
            format = track.toInnerTubeFormat(playback, durationSeconds),
            streamUrl = playback.streamUrl,
            streamExpiresInSeconds = TIDAL_STREAM_EXPIRY_SECONDS,
            authFingerprint = YouTube.currentPlaybackAuthState().fingerprint,
        )
    }

    // region Search

    private suspend fun searchBestMatch(
        auth: AuthState,
        title: String,
        artist: String?,
    ): TidalTrackItem? {
        val query = listOfNotNull(title, artist).joinToString(" ").trim()
        val response =
            httpClient.get("${TidalAuth.API_BASE}/search/tracks") {
                header(HttpHeaders.Authorization, "Bearer ${auth.accessToken}")
                header(HttpHeaders.Accept, "application/json")
                parameter("query", query)
                parameter("limit", SEARCH_LIMIT)
                parameter("countryCode", auth.countryCode)
            }
        if (response.status == HttpStatusCode.Unauthorized) throw UnauthorizedException()
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Tidal search failed (${response.status.value})")
        }
        val body = response.body<TidalSearchResponse>()
        val candidates =
            body.items.filter { track ->
                track.id > 0 &&
                    !track.title.isNullOrBlank() &&
                    track.streamReady != false &&
                    track.allowStreaming != false
            }
        return candidates
            .map { track -> track to matchScore(track, title, artist) }
            .maxByOrNull { (_, score) -> score }
            ?.takeIf { (_, score) -> score >= MIN_MATCH_SCORE }
            ?.first
    }

    private fun matchScore(
        track: TidalTrackItem,
        title: String,
        artist: String?,
    ): Double {
        val titleScore = FuzzyMatcher.jaroWinkler(track.title.orEmpty(), title)
        val candidateArtist = track.artistNames()
        if (artist == null || candidateArtist == null) return titleScore
        val artistScore = FuzzyMatcher.jaroWinkler(candidateArtist, artist)
        return (titleScore * TITLE_WEIGHT) + (artistScore * ARTIST_WEIGHT)
    }

    private fun TidalTrackItem.artistNames(): String? =
        artists
            .mapNotNull { it.name?.takeIf { name -> name.isNotBlank() } }
            .joinToString(", ")
            .takeIf { it.isNotBlank() }
            ?: artist?.name?.takeIf { it.isNotBlank() }

    // endregion

    // region Playback info / BTS manifest

    private data class TidalPlayback(
        val streamUrl: String,
        val qualityLabel: String,
        val sampleRate: Int?,
        val bitDepth: Int?,
        val bitrateBps: Int,
    )

    private suspend fun fetchPlayback(
        auth: AuthState,
        trackId: Long,
        audioQuality: String,
    ): TidalPlayback {
        val response =
            httpClient.get("${TidalAuth.API_BASE}/tracks/$trackId/playbackinfo") {
                header(HttpHeaders.Authorization, "Bearer ${auth.accessToken}")
                header(HttpHeaders.Accept, "application/json")
                parameter("audioquality", audioQuality)
                parameter("playbackmode", "STREAM")
                parameter("assetpresentation", "FULL")
                parameter("countryCode", auth.countryCode)
            }
        if (response.status == HttpStatusCode.Unauthorized) throw UnauthorizedException()
        if (!response.status.isSuccess()) {
            throw IllegalStateException(
                "Tidal playbackinfo ($audioQuality) failed (${response.status.value})",
            )
        }
        val info = response.body<PlaybackInfoResponse>()
        val manifestRaw =
            info.manifest?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Tidal playbackinfo returned no manifest")
        if (info.manifestMimeType != null && info.manifestMimeType != BTS_MANIFEST_MIME) {
            // DASH manifests need segmented playback, which the app's
            // direct-URL stack can't consume — fail so the chain moves on.
            throw IllegalStateException(
                "Tidal returned a ${info.manifestMimeType} manifest; only direct BTS streams are supported",
            )
        }
        val manifestJson =
            try {
                String(Base64.decode(manifestRaw, Base64.DEFAULT), Charsets.UTF_8)
            } catch (e: IllegalArgumentException) {
                throw IllegalStateException("Tidal BTS manifest was not valid base64", e)
            }
        val manifest = json.decodeFromString<BtsManifest>(manifestJson)
        val streamUrl =
            manifest.urls.firstOrNull { it.isNotBlank() }
                ?: throw IllegalStateException("Tidal BTS manifest contained no stream URL")
        if (!manifest.encryptionType.isNullOrBlank() && manifest.encryptionType != "NONE") {
            throw IllegalStateException(
                "Tidal stream is encrypted (${manifest.encryptionType}); cannot play",
            )
        }
        val qualityLabel = info.audioQuality ?: audioQuality
        return TidalPlayback(
            streamUrl = streamUrl,
            qualityLabel = qualityLabel,
            sampleRate = info.sampleRate,
            bitDepth = info.bitDepth,
            bitrateBps = estimateBitrateBps(info.sampleRate, info.bitDepth),
        )
    }

    /**
     * The BTS manifest carries no bitrate; derive it from sample rate ×
     * bit depth × channels when Tidal reports them, else use the FLAC
     * 16-bit/44.1 kHz floor (1,411,200 bps) — every Tidal FLAC is at
     * least that. Marked as an estimate by design, like the Internet
     * Archive resolver's fallbacks.
     */
    private fun estimateBitrateBps(
        sampleRate: Int?,
        bitDepth: Int?,
    ): Int {
        if (sampleRate != null && sampleRate > 0 && bitDepth != null && bitDepth > 0) {
            return sampleRate * bitDepth * 2
        }
        return 1_411_200
    }

    private fun TidalTrackItem.toInnerTubeFormat(
        playback: TidalPlayback,
        durationSeconds: Int?,
    ): PlayerResponse.StreamingData.Format =
        PlayerResponse.StreamingData.Format(
            itag = -1,
            url = playback.streamUrl,
            mimeType = "audio/flac",
            bitrate = playback.bitrateBps,
            width = null,
            height = null,
            contentLength = null,
            quality = "high",
            fps = null,
            qualityLabel = null,
            averageBitrate = playback.bitrateBps,
            audioQuality = "AUDIO_QUALITY_HIGH",
            approxDurationMs = durationSeconds?.let { (it * 1000L).toString() },
            audioSampleRate = playback.sampleRate,
            audioChannels = 2,
            loudnessDb = null,
            lastModified = null,
            signatureCipher = null,
            cipher = null,
        )

    // endregion

    // region Auth retry / refresh

    /**
     * Runs [block] with [auth]; on a 401, refreshes the token pair once
     * and retries with the fresh state. The fresh pair is kept in memory
     * and reported through [onTokensRefreshed] for persistence.
     */
    private suspend fun <T> callWithAuthRetry(
        auth: AuthState,
        block: suspend (AuthState) -> T,
    ): T {
        try {
            return block(auth)
        } catch (unauthorized: UnauthorizedException) {
            val refreshed = refreshAuth(auth) ?: throw unauthorized
            return block(refreshed)
        }
    }

    private suspend fun refreshAuth(auth: AuthState): AuthState? {
        val tokens =
            TidalAuth.refreshTokens(auth.refreshToken).getOrElse { error ->
                if (error is CancellationException) throw error
                Timber.tag(TAG).w(error, "Tidal token refresh failed")
                return null
            }
        val refreshed =
            auth.copy(
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken?.takeIf { it.isNotBlank() } ?: auth.refreshToken,
                userId = tokens.userId ?: auth.userId,
            )
        refreshedState = refreshed
        runCatching { onTokensRefreshed?.invoke(refreshed) }
            .onFailure { Timber.tag(TAG).w(it, "Tidal token persistence callback failed") }
        Timber.tag(TAG).d("Tidal access token refreshed for user %d", refreshed.userId)
        return refreshed
    }

    // endregion
}
