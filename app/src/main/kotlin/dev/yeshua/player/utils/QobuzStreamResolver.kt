/*
 * LunarTune (2026)
 * © cognitiveshadows03 — github.com/cognitiveshadows03
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package dev.yeshua.player.utils

import dev.yeshua.player.constants.AudioQuality
import dev.yeshua.player.constants.QobuzAppIdKey
import dev.yeshua.player.constants.QobuzAppSecretKey
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
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
import java.security.MessageDigest

/**
 * Fallback resolver backed by the user's own Qobuz account.
 *
 * Qobuz has no public partner API available to this app, so this talks to
 * the same web-player API that play.qobuz.com uses (the approach of the
 * open-source qobuz-dl / streamrip clients): requests carry the web
 * player's `app_id` in the `X-App-Id` header, the signed-in user's
 * `user_auth_token` in the `X-User-Auth-Token` header, and
 * `track/getFileUrl` requests are MD5-signed with the web player's
 * signing secret.
 *
 * The `app_id`/`secret` pair below is extracted from the current
 * play.qobuz.com JS bundle (bundle 8.3.0-b029, extracted 2026-10-21; the
 * production secret is the bundle's "berlin" timezone seed, decoded with
 * the bundle's own seed scheme). Qobuz rotates these keys occasionally:
 * the two sibling timezone secrets are kept as fallbacks that are only
 * tried when the API reports an invalid request signature, and the user
 * can override both values in Settings → Stream sources (the
 * QobuzAppIdKey / QobuzAppSecretKey preferences) so a rotation can be
 * fixed without an app update. The bundled pair was verified live: a
 * `track/getFileUrl` probe signed with it passes the API's signature
 * check (the API then asks for user authentication), while the sibling
 * secrets and a bogus secret are rejected as invalid signatures.
 *
 * Login uses `user/login` with the password sent as its MD5 hex; only
 * the returned `user_auth_token` is ever persisted (by the settings
 * screen) — the password itself is never stored.
 *
 * Quality format ids: 5 = MP3 320, 6 = FLAC 16-bit/44.1kHz,
 * 7 = FLAC 24-bit ≤96kHz, 27 = FLAC Hi-Res >96kHz. Resolution tries the
 * highest the account allows and steps down on entitlement errors.
 *
 * This source needs an account, so it fails fast and silently when no
 * token is stored, and it is deliberately NOT part of the no-login
 * playback fallback chain in [YTPlayerUtils.playerResponseForPlayback].
 * Resolved URLs are mapped onto [YTPlayerUtils.PlaybackData] so the
 * regular ExoPlayer stack consumes them unchanged.
 */
internal object QobuzStreamResolver {
    /**
     * Dedicated cache key prefix for Qobuz streams resolved at the
     * data-spec level (per-song lossless upgrades), mirroring
     * MonochromeStreamResolver's scheme so Qobuz bytes never mix with
     * YouTube Music spans in the Media3 caches for the same song.
     */
    const val CACHE_KEY_PREFIX = "qobuz:"

    fun cacheKeyFor(songId: String): String = CACHE_KEY_PREFIX + songId

    private const val TAG = "QobuzFallback"
    private const val RESOLVE_TIMEOUT_MS = 15_000L
    // getFileUrl URLs are signature-bound and short-lived; keep the
    // advertised lifetime conservative so caches never hand out a dead URL.
    private const val STREAM_EXPIRY_SECONDS = 600
    private const val API_BASE = "https://www.qobuz.com/api.json/0.2/"
    private const val MIN_MATCH_SCORE = 0.6
    private const val TITLE_WEIGHT = 0.6
    private const val ARTIST_WEIGHT = 0.4
    private const val SEARCH_LIMIT = 10

    // Web-player keys extracted from the play.qobuz.com bundle (see the
    // class doc). BUNDLED_SECRET is the currently-signing production
    // secret; FALLBACK_SECRETS are the bundle's other timezone seeds,
    // tried only when the API rejects a signature (key rotation).
    private const val BUNDLED_APP_ID = "798273057"
    private const val BUNDLED_SECRET = "abb21364945c0583309667d13ca3d93a"
    private val FALLBACK_SECRETS =
        listOf(
            "806331c3b0b641da923b890aed01d04a",
            "f69a7734686cb9427629378a4b7ac381",
        )

    @Volatile
    private var apiUnreachable = false

    @Volatile
    private var workingSecret: String? = null

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

    class QobuzApiException(
        val httpStatus: Int,
        val serverMessage: String,
    ) : Exception("Qobuz API error ($httpStatus): $serverMessage")

    data class LoginResult(
        val userAuthToken: String,
        val email: String?,
        val displayName: String?,
        val subscriptionLabel: String?,
    )

    @Serializable
    private data class QobuzErrorBody(
        val code: Int? = null,
        val status: String? = null,
        val message: String? = null,
    )

    @Serializable
    private data class QobuzPerformer(
        val name: String? = null,
    )

    @Serializable
    private data class QobuzTrackItem(
        val id: Long? = null,
        val title: String? = null,
        val version: String? = null,
        val duration: Int? = null,
        val performer: QobuzPerformer? = null,
        val streamable: Boolean? = null,
    )

    @Serializable
    private data class QobuzTracksPage(
        val items: List<QobuzTrackItem> = emptyList(),
    )

    @Serializable
    private data class QobuzCatalogSearchResponse(
        val tracks: QobuzTracksPage? = null,
    )

    @Serializable
    private data class QobuzFileUrlResponse(
        val url: String? = null,
        @SerialName("format_id") val formatId: Int? = null,
        @SerialName("mime_type") val mimeType: String? = null,
        @SerialName("sampling_rate") val samplingRate: Double? = null,
        @SerialName("bit_depth") val bitDepth: Int? = null,
        val duration: Int? = null,
    )

    @Serializable
    private data class QobuzCredentialParameters(
        @SerialName("short_label") val shortLabel: String? = null,
    )

    @Serializable
    private data class QobuzCredential(
        val parameters: QobuzCredentialParameters? = null,
    )

    @Serializable
    private data class QobuzUser(
        val email: String? = null,
        val firstname: String? = null,
        val lastname: String? = null,
        val credential: QobuzCredential? = null,
    )

    @Serializable
    private data class QobuzLoginResponse(
        @SerialName("user_auth_token") val userAuthToken: String? = null,
        val user: QobuzUser? = null,
    )

    private fun effectiveAppId(): String =
        PreferenceStore.get(QobuzAppIdKey)?.trim()?.takeIf { it.isNotBlank() } ?: BUNDLED_APP_ID

    /**
     * Secrets to try, best first: a user override replaces the bundled
     * set entirely (it is meant to be a matched pair with the overridden
     * app id); otherwise the session-proven secret, then the bundled
     * production secret, then the rotation fallbacks.
     */
    private fun candidateSecrets(): List<String> {
        PreferenceStore.get(QobuzAppSecretKey)?.trim()?.takeIf { it.isNotBlank() }?.let {
            return listOf(it)
        }
        return listOfNotNull(workingSecret, BUNDLED_SECRET, *FALLBACK_SECRETS.toTypedArray()).distinct()
    }

    suspend fun resolve(
        videoId: String,
        audioQuality: AudioQuality,
        title: String?,
        artist: String?,
        authToken: String?,
    ): Result<YTPlayerUtils.PlaybackData> =
        runCatching {
            val token = authToken?.trim()?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Qobuz is not signed in; skipping")
            val queryTitle = title?.trim().orEmpty()
            if (queryTitle.isBlank()) {
                throw IllegalStateException("Qobuz needs a track title; skipping")
            }
            if (apiUnreachable) {
                throw IllegalStateException("Qobuz API marked unreachable this session")
            }
            withContext(Dispatchers.IO) {
                withTimeout(RESOLVE_TIMEOUT_MS) {
                    resolveFromQobuz(
                        videoId = videoId,
                        audioQuality = audioQuality,
                        title = queryTitle,
                        artist = artist?.trim()?.takeIf { it.isNotBlank() },
                        authToken = token,
                    )
                }
            }
        }

    private suspend fun resolveFromQobuz(
        videoId: String,
        audioQuality: AudioQuality,
        title: String,
        artist: String?,
        authToken: String,
    ): YTPlayerUtils.PlaybackData {
        val query = listOfNotNull(title, artist).joinToString(" ").trim()
        val searchText =
            apiGetText(
                endpoint = "catalog/search",
                params = mapOf("query" to query, "limit" to SEARCH_LIMIT.toString()),
                authToken = authToken,
            )
        val search =
            runCatching { json.decodeFromString<QobuzCatalogSearchResponse>(searchText) }
                .getOrElse { throw IllegalStateException("Qobuz search returned an unreadable response") }
        val candidates =
            search.tracks?.items.orEmpty().filter { track ->
                track.id != null && !track.title.isNullOrBlank() && track.streamable != false
            }
        if (candidates.isEmpty()) {
            throw IllegalStateException("Qobuz found no match for \"$title\"")
        }
        val winner =
            candidates
                .map { track -> track to matchScore(track, title, artist) }
                .maxByOrNull { (_, score) -> score }
                ?.takeIf { (_, score) -> score >= MIN_MATCH_SCORE }
                ?.first
                ?: throw IllegalStateException("Qobuz found no confident match for \"$title\"")
        val trackId = winner.id ?: throw IllegalStateException("Qobuz match has no track id")

        val formatIds =
            if (audioQuality == AudioQuality.LOW) {
                listOf(5)
            } else {
                listOf(27, 7, 6, 5)
            }
        var lastError: Throwable? = null
        for (formatId in formatIds) {
            val file =
                try {
                    getFileUrl(trackId = trackId, formatId = formatId, authToken = authToken)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (e: Exception) {
                    lastError = e
                    Timber.tag(TAG).w(
                        e,
                        "Qobuz getFileUrl failed for track %d at format %d; stepping down",
                        trackId,
                        formatId,
                    )
                    continue
                }
            val streamUrl = file.url?.takeIf { it.isNotBlank() }
            if (streamUrl == null) {
                // No URL in the response means the account is not entitled
                // to this format (or the track is not streamable in it).
                lastError = IllegalStateException("Qobuz returned no stream URL at format $formatId")
                continue
            }
            val resolvedFormatId = file.formatId ?: formatId
            val durationSeconds = file.duration ?: winner.duration
            Timber.tag(TAG).i(
                "Qobuz resolved %s via track %d at format %d (%s)",
                videoId,
                trackId,
                resolvedFormatId,
                file.mimeType,
            )
            return YTPlayerUtils.PlaybackData(
                audioConfig = null,
                videoDetails =
                    PlayerResponse.VideoDetails(
                        videoId = videoId,
                        title = winner.title ?: title,
                        author = winner.performer?.name?.takeIf { it.isNotBlank() } ?: artist,
                        lengthSeconds = durationSeconds?.takeIf { it > 0 }?.toString(),
                    ),
                playbackTracking = null,
                format =
                    toInnerTubeFormat(
                        streamUrl = streamUrl,
                        formatId = resolvedFormatId,
                        mimeType = file.mimeType,
                        samplingRateKhz = file.samplingRate,
                        bitDepth = file.bitDepth,
                        durationSeconds = durationSeconds,
                    ),
                streamUrl = streamUrl,
                streamExpiresInSeconds = STREAM_EXPIRY_SECONDS,
                authFingerprint = YouTube.currentPlaybackAuthState().fingerprint,
            )
        }
        throw lastError ?: IllegalStateException("Qobuz could not resolve a stream for \"$title\"")
    }

    /**
     * Signed `track/getFileUrl`. The signature is the web player's MD5
     * scheme: md5("trackgetFileUrl" + each param as key+value in
     * alphabetical key order + request_ts + secret). A 400 "Invalid
     * Request Signature" answer moves on to the next candidate secret
     * (key rotation); any other outcome stops with that secret.
     */
    private suspend fun getFileUrl(
        trackId: Long,
        formatId: Int,
        authToken: String,
    ): QobuzFileUrlResponse {
        var lastError: Throwable? = null
        for (secret in candidateSecrets()) {
            val requestTs = (System.currentTimeMillis() / 1000).toString()
            val signature =
                md5Hex(
                    "trackgetFileUrl" +
                        "format_id" + formatId +
                        "intent" + "stream" +
                        "track_id" + trackId +
                        requestTs +
                        secret,
                )
            try {
                val text =
                    apiGetText(
                        endpoint = "track/getFileUrl",
                        params =
                            mapOf(
                                "track_id" to trackId.toString(),
                                "format_id" to formatId.toString(),
                                "intent" to "stream",
                                "request_ts" to requestTs,
                                "request_sig" to signature,
                            ),
                        authToken = authToken,
                    )
                workingSecret = secret
                return runCatching { json.decodeFromString<QobuzFileUrlResponse>(text) }
                    .getOrElse { throw IllegalStateException("Qobuz getFileUrl returned an unreadable response") }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: QobuzApiException) {
                lastError = e
                if (e.httpStatus == 400 && e.serverMessage.contains("Invalid Request Signature", ignoreCase = true)) {
                    Timber.tag(TAG).w("Qobuz rejected a signing secret (rotation?); trying the next one")
                    continue
                }
                throw e
            }
        }
        throw lastError ?: IllegalStateException("Qobuz getFileUrl failed")
    }

    /**
     * Signs in with the user's own Qobuz credentials and returns just the
     * `user_auth_token` — the only value the app persists.
     */
    suspend fun login(
        email: String,
        password: String,
    ): Result<String> = loginWithProfile(email, password).map { it.userAuthToken }

    /**
     * Full login: the token plus the profile bits the settings screen
     * shows (email, display name, subscription label). The password is
     * sent as its MD5 hex per the web-player scheme and is never stored.
     */
    suspend fun loginWithProfile(
        email: String,
        password: String,
    ): Result<LoginResult> =
        runCatching {
            val trimmedEmail = email.trim()
            if (trimmedEmail.isBlank() || password.isEmpty()) {
                throw IllegalStateException("Enter your Qobuz email and password")
            }
            withContext(Dispatchers.IO) {
                withTimeout(RESOLVE_TIMEOUT_MS) {
                    val text =
                        apiGetText(
                            endpoint = "user/login",
                            params =
                                mapOf(
                                    "email" to trimmedEmail,
                                    "password" to md5Hex(password),
                                    "app_id" to effectiveAppId(),
                                ),
                            authToken = null,
                        )
                    val response =
                        runCatching { json.decodeFromString<QobuzLoginResponse>(text) }
                            .getOrElse {
                                throw IllegalStateException("Qobuz login returned an unreadable response")
                            }
                    val token = response.userAuthToken?.takeIf { it.isNotBlank() }
                        ?: throw IllegalStateException("Qobuz login did not return an auth token")
                    val user = response.user
                    val displayName =
                        listOfNotNull(user?.firstname, user?.lastname)
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                            .joinToString(" ")
                            .takeIf { it.isNotBlank() }
                    LoginResult(
                        userAuthToken = token,
                        email = user?.email?.takeIf { it.isNotBlank() } ?: trimmedEmail,
                        displayName = displayName,
                        subscriptionLabel = user?.credential?.parameters?.shortLabel?.takeIf { it.isNotBlank() },
                    )
                }
            }
        }

    /**
     * Cheap authenticated probe the settings UI can use to confirm a
     * stored token still works: a one-result catalog search. Returns
     * false on any failure (expired token, rotated keys, no network).
     */
    suspend fun validateToken(authToken: String): Boolean {
        val token = authToken.trim().takeIf { it.isNotBlank() } ?: return false
        return runCatching {
            withContext(Dispatchers.IO) {
                withTimeout(RESOLVE_TIMEOUT_MS) {
                    apiGetText(
                        endpoint = "catalog/search",
                        params = mapOf("query" to "a", "limit" to "1"),
                        authToken = token,
                    )
                }
            }
            true
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            false
        }
    }

    /**
     * One GET against the web-player API. Returns the body text on HTTP
     * 200; throws [QobuzApiException] carrying the server's own error
     * message otherwise, so the settings screen can surface it verbatim.
     * Network-level failures mark the API unreachable for the session,
     * mirroring the other resolvers.
     */
    private suspend fun apiGetText(
        endpoint: String,
        params: Map<String, String>,
        authToken: String?,
    ): String {
        val response: HttpResponse =
            try {
                httpClient.get(API_BASE + endpoint) {
                    header("X-App-Id", effectiveAppId())
                    if (!authToken.isNullOrBlank()) {
                        header("X-User-Auth-Token", authToken)
                    }
                    params.forEach { (key, value) -> parameter(key, value) }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                apiUnreachable = true
                Timber.tag(TAG).w(e, "Qobuz API request failed for %s; marking unreachable", endpoint)
                throw e
            }
        val text = response.bodyAsText()
        if (response.status != HttpStatusCode.OK) {
            val serverMessage =
                runCatching { json.decodeFromString<QobuzErrorBody>(text).message }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: text.take(200).ifBlank { response.status.description }
            throw QobuzApiException(response.status.value, serverMessage)
        }
        return text
    }

    private fun matchScore(
        track: QobuzTrackItem,
        title: String,
        artist: String?,
    ): Double {
        val titleScore = FuzzyMatcher.jaroWinkler(track.title.orEmpty(), title)
        val candidateArtist = track.performer?.name?.takeIf { it.isNotBlank() }
        if (artist == null || candidateArtist == null) return titleScore
        val artistScore = FuzzyMatcher.jaroWinkler(candidateArtist, artist)
        return (titleScore * TITLE_WEIGHT) + (artistScore * ARTIST_WEIGHT)
    }

    private fun toInnerTubeFormat(
        streamUrl: String,
        formatId: Int,
        mimeType: String?,
        samplingRateKhz: Double?,
        bitDepth: Int?,
        durationSeconds: Int?,
    ): PlayerResponse.StreamingData.Format {
        val isFlac = formatId != 5
        val mime = mimeType?.takeIf { it.isNotBlank() } ?: if (isFlac) "audio/flac" else "audio/mpeg"
        val bitrateBps =
            if (isFlac) {
                // FLAC bitrate is not reported directly; estimate from the
                // PCM rate (sample rate × bit depth × stereo channels).
                val sampleRateHz = ((samplingRateKhz ?: 44.1) * 1000).toInt()
                sampleRateHz * (bitDepth ?: 16) * 2
            } else {
                320_000
            }
        return PlayerResponse.StreamingData.Format(
            itag = -1,
            url = streamUrl,
            mimeType = mime,
            bitrate = bitrateBps,
            width = null,
            height = null,
            contentLength = null,
            quality = "medium",
            fps = null,
            qualityLabel = null,
            averageBitrate = bitrateBps,
            audioQuality = "AUDIO_QUALITY_HIGH",
            approxDurationMs = durationSeconds?.takeIf { it > 0 }?.let { (it * 1000L).toString() },
            audioSampleRate = samplingRateKhz?.let { (it * 1000).toInt() },
            audioChannels = 2,
            loudnessDb = null,
            lastModified = null,
            signatureCipher = null,
            cipher = null,
        )
    }

    private fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
