/*
 * LunarTune (2026)
 * © cognitiveshadows03 — github.com/cognitiveshadows03
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package dev.yeshua.player.utils

import android.content.Context
import dev.yeshua.player.constants.AudioQuality
import dev.yeshua.player.constants.DeezerAccountNameKey
import dev.yeshua.player.constants.DeezerArlKey
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.response.PlayerResponse
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Account-backed resolver for the user's OWN Deezer account.
 *
 * Deezer's official API only serves 30-second previews to non-partner
 * apps, so — like every working third-party client (deemix and friends) —
 * this uses the session the user's own login creates:
 *
 * 1. The user signs in to deezer.com inside a WebView (see
 *    DeezerAuthSection); the app keeps only the resulting `arl` session
 *    cookie. The arl is a credential: it is never logged or displayed.
 * 2. `deezer.getUserData` on the gw-light API
 *    (`https://www.deezer.com/ajax/gw-light.php`) bootstraps the session:
 *    it returns the `checkForm` api token, the user object, and inside
 *    `USER.OPTIONS` the `license_token` needed for stream exchange.
 * 3. Tracks are found with the public search API (no auth) and matched
 *    with [FuzzyMatcher]. The track's stream token comes from gw-light
 *    (`song.getListByAlbum`, falling back to `deezer.pageTrack`).
 * 4. `https://media.deezer.com/v1/get_url` exchanges license token +
 *    track token for the real (encrypted) media URL.
 * 5. The audio bytes are Blowfish-CBC "stripe" encrypted; playback and
 *    downloads decrypt them on the fly via DeezerDecryptingDataSource —
 *    this resolver only hands out the encrypted URL plus the song-id
 *    registration the data source needs for key derivation.
 *
 * Quality depends on the account: HiFi/Premium (and the free trial of
 * them) can fetch FLAC; free accounts fall back to MP3. Deezer file
 * format ids (the numeric keys behind the FILESIZE_* fields, deemix's
 * table): 1 = MP3 128 kbps, 3 = MP3 320 kbps, 9 = FLAC. The get_url
 * exchange itself names formats as strings ("MP3_128", "MP3_320",
 * "FLAC") with cipher "BF_CBC_STRIPE".
 *
 * Fails fast and silently when no arl is stored, so the fallback chains
 * can offer it unconditionally and it simply abstains when signed out.
 */
internal object DeezerStreamResolver {
    private const val TAG = "DeezerFallback"
    private const val RESOLVE_TIMEOUT_MS = 25_000L
    private const val DEFAULT_STREAM_EXPIRY_SECONDS = 21_000
    private const val MIN_MATCH_SCORE = 0.6
    private const val TITLE_WEIGHT = 0.6
    private const val ARTIST_WEIGHT = 0.4

    const val CACHE_KEY_PREFIX = "deezer:"

    private const val GW_URL = "https://www.deezer.com/ajax/gw-light.php"
    private const val SEARCH_URL = "https://api.deezer.com/search"
    private const val GET_URL_ENDPOINT = "https://media.deezer.com/v1/get_url"
    private const val STREAM_CIPHER = "BF_CBC_STRIPE"

    const val FORMAT_MP3_128 = "MP3_128"
    const val FORMAT_MP3_320 = "MP3_320"
    const val FORMAT_FLAC = "FLAC"

    fun cacheKeyFor(songId: String): String = CACHE_KEY_PREFIX + songId

    /** The only credential this feature stores: the user's arl cookie. */
    data class Config(
        val arl: String,
    )

    data class AccountInfo(
        val userId: String,
        val displayName: String?,
        /** From USER.OPTIONS when present: whether the account may stream lossless. */
        val losslessAllowed: Boolean?,
    )

    class DeezerApiException(message: String) : IllegalStateException(message)

    // ------------------------------------------------------------------
    // Stream URL -> Deezer song id registry.
    //
    // The decrypting data source needs the Deezer song id to derive the
    // Blowfish key. On the playback path the id travels in the DataSpec
    // key ("deezer:<songId>"); on the download path the spec key stays
    // the app's own media id, so resolve() registers the URL -> id
    // mapping here instead. In-memory only: after a process restart a
    // resumed download simply re-resolves (and re-registers) first.
    // ------------------------------------------------------------------
    private val streamSongIds = ConcurrentHashMap<String, String>()

    fun songIdForStream(url: String): String? = streamSongIds[url]

    private fun registerStream(
        url: String,
        deezerSongId: String,
    ) {
        if (streamSongIds.size > 512) streamSongIds.clear()
        streamSongIds[url] = deezerSongId
    }

    // ------------------------------------------------------------------
    // Preferences
    // ------------------------------------------------------------------
    suspend fun configFromContext(context: Context): Config? {
        val prefs = context.dataStore.data.firstOrNull() ?: return null
        val arl = prefs[DeezerArlKey]?.trim().orEmpty()
        return if (arl.isBlank()) null else Config(arl)
    }

    suspend fun accountNameFromContext(context: Context): String? {
        val prefs = context.dataStore.data.firstOrNull() ?: return null
        return prefs[DeezerAccountNameKey]?.takeIf { it.isNotBlank() }
    }

    // ------------------------------------------------------------------
    // JSON navigation helpers (gw-light payloads are loosely typed)
    // ------------------------------------------------------------------
    private fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

    private fun JsonElement?.asArray(): JsonArray? = this as? JsonArray

    private fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.contentOrNull

    // ------------------------------------------------------------------
    // gw-light session
    // ------------------------------------------------------------------
    private data class GwSession(
        val arl: String,
        val checkForm: String,
        val licenseToken: String,
        val account: AccountInfo,
    )

    @Volatile
    private var session: GwSession? = null

    private val bootstrapMutex = Mutex()

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

    /**
     * Validates an arl by bootstrapping a session with it. Succeeds with
     * the account snapshot when the arl belongs to a signed-in user
     * (gw-light answers anonymous sessions with USER_ID 0 — that is a
     * failure here). Used by the settings sign-in flow; stores nothing.
     */
    suspend fun validateArl(arl: String): Result<AccountInfo> =
        runCatching {
            withContext(Dispatchers.IO) {
                withTimeout(RESOLVE_TIMEOUT_MS) {
                    bootstrap(arl.trim(), force = true).account
                }
            }
        }

    private suspend fun bootstrap(
        arl: String,
        force: Boolean = false,
    ): GwSession =
        bootstrapMutex.withLock {
            val cached = session
            if (!force && cached != null && cached.arl == arl) return@withLock cached
            val results =
                callGwRaw(arl, apiToken = "", method = "deezer.getUserData", params = JsonObject(emptyMap()))
            val user = results["USER"].asObject()
                ?: throw DeezerApiException("Deezer getUserData returned no user object")
            val userId = user["USER_ID"].asString().orEmpty()
            if (userId.isBlank() || userId == "0") {
                throw DeezerApiException("Deezer session is not signed in (arl missing or expired)")
            }
            val options = user["OPTIONS"].asObject()
            val licenseToken = options?.get("license_token").asString().orEmpty()
            if (licenseToken.isBlank()) {
                throw DeezerApiException("Deezer getUserData returned no license token")
            }
            val account =
                AccountInfo(
                    userId = userId,
                    displayName =
                        user["BLOG_NAME"].asString()?.takeIf { it.isNotBlank() }
                            ?: user["EMAIL"].asString()?.substringBefore("@")?.takeIf { it.isNotBlank() },
                    losslessAllowed = (options?.get("web_lossless") as? JsonPrimitive)?.booleanOrNull,
                )
            val fresh =
                GwSession(
                    arl = arl,
                    checkForm = results["checkForm"].asString().orEmpty(),
                    licenseToken = licenseToken,
                    account = account,
                )
            session = fresh
            fresh
        }

    /** gw-light call with the session token, re-bootstrapping once on token errors. */
    private suspend fun gw(
        config: Config,
        method: String,
        params: JsonObject,
    ): JsonObject {
        val active = bootstrap(config.arl)
        return try {
            callGwRaw(config.arl, active.checkForm, method, params)
        } catch (e: DeezerApiException) {
            if (!e.message.orEmpty().contains("token", ignoreCase = true)) throw e
            Timber.tag(TAG).i("gw-light token rejected for %s; re-bootstrapping once", method)
            val refreshed = bootstrap(config.arl, force = true)
            callGwRaw(config.arl, refreshed.checkForm, method, params)
        }
    }

    private suspend fun callGwRaw(
        arl: String,
        apiToken: String,
        method: String,
        params: JsonObject,
    ): JsonObject {
        val raw: String =
            try {
                httpClient.post(GW_URL) {
                    parameter("method", method)
                    parameter("input", "3")
                    parameter("api_version", "1.0")
                    parameter("api_token", apiToken)
                    header("Cookie", "arl=$arl")
                    setBody(TextContent(json.encodeToString(JsonObject.serializer(), params), ContentType.Text.Plain))
                }.body()
            } catch (cancellation: CancellationException) {
                throw cancellation
            }
        val root = json.parseToJsonElement(raw).jsonObject
        root["error"]?.let { error ->
            val message =
                error.asObject()?.get("message").asString()
                    ?: error.asString()
                    ?: error.toString()
            throw DeezerApiException("Deezer $method failed: $message")
        }
        return root["results"].asObject()
            ?: throw DeezerApiException("Deezer $method returned no results")
    }

    // ------------------------------------------------------------------
    // Public search (no auth)
    // ------------------------------------------------------------------
    @Serializable
    private data class DeezerArtistDto(
        val name: String? = null,
    )

    @Serializable
    private data class DeezerAlbumDto(
        val id: Long? = null,
        val title: String? = null,
    )

    @Serializable
    private data class DeezerTrackDto(
        val id: Long? = null,
        val title: String? = null,
        val duration: Int? = null,
        val readable: Boolean? = null,
        val artist: DeezerArtistDto? = null,
        val album: DeezerAlbumDto? = null,
    )

    @Serializable
    private data class DeezerSearchResponse(
        val data: List<DeezerTrackDto> = emptyList(),
    )

    private suspend fun search(
        title: String,
        artist: String?,
    ): DeezerTrackDto {
        val query = listOfNotNull(title, artist).joinToString(" ").trim()
        val response: DeezerSearchResponse =
            try {
                httpClient.get(SEARCH_URL) {
                    parameter("q", query)
                }.body()
            } catch (cancellation: CancellationException) {
                throw cancellation
            }
        val candidates = response.data.filter { it.id != null && !it.title.isNullOrBlank() && it.readable != false }
        if (candidates.isEmpty()) {
            throw DeezerApiException("Deezer found no match for \"$title\"")
        }
        return candidates
            .map { track -> track to matchScore(track, title, artist) }
            .maxByOrNull { (_, score) -> score }
            ?.takeIf { (_, score) -> score >= MIN_MATCH_SCORE }
            ?.first
            ?: throw DeezerApiException("Deezer found no confident match for \"$title\"")
    }

    private fun matchScore(
        track: DeezerTrackDto,
        title: String,
        artist: String?,
    ): Double {
        val titleScore = FuzzyMatcher.jaroWinkler(track.title.orEmpty(), title)
        val candidateArtist = track.artist?.name?.takeIf { it.isNotBlank() }
        if (artist == null || candidateArtist == null) return titleScore
        val artistScore = FuzzyMatcher.jaroWinkler(candidateArtist, artist)
        return (titleScore * TITLE_WEIGHT) + (artistScore * ARTIST_WEIGHT)
    }

    // ------------------------------------------------------------------
    // Track token + stream URL exchange
    // ------------------------------------------------------------------
    private data class TrackTokenResult(
        val trackToken: String,
        val fileSizes: Map<String, Long>,
    )

    private suspend fun trackToken(
        config: Config,
        track: DeezerTrackDto,
    ): TrackTokenResult {
        val trackId = track.id!!.toString()
        // Primary: the album listing carries every song's TRACK_TOKEN.
        val albumId = track.album?.id
        if (albumId != null) {
            val fromAlbum =
                runCatching {
                    val results =
                        gw(
                            config,
                            "song.getListByAlbum",
                            buildJsonObject {
                                put("album_id", albumId.toString())
                                put("nb", 100)
                            },
                        )
                    val songs = results["SONGS"].asObject()?.get("data").asArray().orEmpty()
                    val item =
                        songs
                            .firstOrNull { entry -> entry.asObject()?.get("SNG_ID").asString() == trackId }
                            .asObject()
                    val token = item?.get("TRACK_TOKEN").asString()
                    if (item != null && !token.isNullOrBlank()) {
                        TrackTokenResult(token, parseFileSizes(item))
                    } else {
                        null
                    }
                }.getOrNull()
            if (fromAlbum != null) return fromAlbum
        }
        // Fallback: the track page payload carries the token directly.
        val results =
            gw(
                config,
                "deezer.pageTrack",
                buildJsonObject { put("id", trackId) },
            )
        val data = results["DATA"].asObject() ?: results
        val token = data["TRACK_TOKEN"].asString()
            ?: throw DeezerApiException("Deezer returned no track token for $trackId")
        return TrackTokenResult(token, parseFileSizes(data))
    }

    private fun parseFileSizes(item: JsonObject): Map<String, Long> {
        val sizes = item["FILESIZES"].asObject() ?: return emptyMap()
        return sizes.mapNotNull { (key, value) ->
            value.asString()?.toLongOrNull()?.let { key to it }
        }.toMap()
    }

    /** Exchanges a track token for the encrypted media URL at one format. Null when Deezer refuses that format. */
    private suspend fun exchangeStreamUrl(
        licenseToken: String,
        trackToken: String,
        format: String,
    ): String? {
        val payload =
            buildJsonObject {
                put("license_token", licenseToken)
                put(
                    "media",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", "FULL")
                                put(
                                    "formats",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("cipher", STREAM_CIPHER)
                                                put("format", format)
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    },
                )
                put(
                    "track_tokens",
                    buildJsonArray { add(JsonPrimitive(trackToken)) },
                )
            }
        val raw: String =
            try {
                httpClient.post(GET_URL_ENDPOINT) {
                    setBody(TextContent(json.encodeToString(JsonObject.serializer(), payload), ContentType.Application.Json))
                }.body()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Deezer get_url failed for format %s", format)
                return null
            }
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val first = root["data"].asArray()?.firstOrNull().asObject() ?: return null
        if (first["errors"] != null) return null
        val media = first["media"].asArray()?.firstOrNull().asObject() ?: return null
        return media["sources"]
            .asArray()
            ?.firstOrNull()
            .asObject()
            ?.get("url")
            .asString()
    }

    // ------------------------------------------------------------------
    // Resolution
    // ------------------------------------------------------------------
    suspend fun resolve(
        videoId: String,
        audioQuality: AudioQuality,
        title: String?,
        artist: String?,
        config: Config?,
    ): Result<YTPlayerUtils.PlaybackData> =
        runCatching {
            val activeConfig = config?.takeIf { it.arl.isNotBlank() }
                ?: throw DeezerApiException("Deezer is not signed in; skipping")
            val queryTitle = title?.trim().orEmpty()
            if (queryTitle.isBlank()) {
                throw DeezerApiException("Deezer needs a track title; skipping")
            }
            withContext(Dispatchers.IO) {
                withTimeout(RESOLVE_TIMEOUT_MS) {
                    resolveFromDeezer(videoId, queryTitle, artist?.trim()?.takeIf { it.isNotBlank() }, audioQuality, activeConfig)
                }
            }
        }

    private suspend fun resolveFromDeezer(
        videoId: String,
        title: String,
        artist: String?,
        audioQuality: AudioQuality,
        config: Config,
    ): YTPlayerUtils.PlaybackData {
        val activeSession = bootstrap(config.arl)
        val track = search(title, artist)
        val deezerId = track.id!!.toString()
        val tokenResult = trackToken(config, track)

        val formats =
            if (audioQuality == AudioQuality.LOW) {
                listOf(FORMAT_MP3_128, FORMAT_MP3_320, FORMAT_FLAC)
            } else {
                listOf(FORMAT_FLAC, FORMAT_MP3_320, FORMAT_MP3_128)
            }
        var streamUrl: String? = null
        var chosenFormat: String? = null
        for (format in formats) {
            val url = exchangeStreamUrl(activeSession.licenseToken, tokenResult.trackToken, format)
            if (!url.isNullOrBlank()) {
                streamUrl = url
                chosenFormat = format
                break
            }
        }
        val finalUrl = streamUrl ?: throw DeezerApiException("Deezer refused every format for \"$title\"")
        val format = chosenFormat ?: FORMAT_MP3_128
        registerStream(finalUrl, deezerId)

        val fileSize = tokenResult.fileSizes["FILESIZE_$format"]?.takeIf { it > 0L }
        val durationSeconds = track.duration?.takeIf { it > 0 }
        val bitrate =
            when {
                fileSize != null && durationSeconds != null -> ((fileSize * 8) / durationSeconds).toInt()
                format == FORMAT_FLAC -> 1_411_200
                format == FORMAT_MP3_320 -> 320_000
                else -> 128_000
            }
        Timber.tag(TAG).i(
            "Deezer resolved %s via track %s (%s): \"%s\" by %s",
            videoId,
            deezerId,
            format,
            track.title,
            track.artist?.name,
        )
        return YTPlayerUtils.PlaybackData(
            audioConfig = null,
            videoDetails =
                PlayerResponse.VideoDetails(
                    videoId = videoId,
                    title = track.title ?: title,
                    author = track.artist?.name?.takeIf { it.isNotBlank() } ?: artist,
                    lengthSeconds = durationSeconds?.toString(),
                ),
            playbackTracking = null,
            format =
                PlayerResponse.StreamingData.Format(
                    itag = -1,
                    url = finalUrl,
                    mimeType = if (format == FORMAT_FLAC) "audio/flac" else "audio/mpeg",
                    bitrate = bitrate,
                    width = null,
                    height = null,
                    contentLength = fileSize,
                    quality = if (format == FORMAT_MP3_128) "medium" else "high",
                    fps = null,
                    qualityLabel = null,
                    averageBitrate = bitrate,
                    audioQuality = if (format == FORMAT_MP3_128) "AUDIO_QUALITY_MEDIUM" else "AUDIO_QUALITY_HIGH",
                    approxDurationMs = durationSeconds?.let { (it * 1000L).toString() },
                    audioSampleRate = null,
                    audioChannels = null,
                    loudnessDb = null,
                    lastModified = null,
                    signatureCipher = null,
                    cipher = null,
                ),
            streamUrl = finalUrl,
            streamExpiresInSeconds = DEFAULT_STREAM_EXPIRY_SECONDS,
            authFingerprint = YouTube.currentPlaybackAuthState().fingerprint,
        )
    }
}
