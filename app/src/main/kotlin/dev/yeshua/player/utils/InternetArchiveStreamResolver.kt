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
import java.net.URLEncoder

/**
 * FLOSS fallback resolver backed by the Internet Archive (archive.org).
 *
 * archive.org is a free public service run by the Internet Archive
 * nonprofit: no API key, no account, and no self-hosted server are needed.
 * Its audio catalog (Live Music Archive, Creative Commons and
 * public-domain releases) is heavily lossless — most items carry the
 * original FLAC alongside MP3/Ogg derivatives.
 *
 * Like Navidrome there is no YouTube-ID lookup: the track is found with
 * the archive's `advancedsearch` API using the song's title (and artist
 * when known), the best-matching item's file list is inspected, and the
 * best encode of the matched track wins — FLAC first, then Ogg Vorbis,
 * then the highest-bitrate MP3. When no title is available, or nothing
 * matches confidently, the resolver fails fast so the chain falls
 * through. Resolved URLs are mapped onto [YTPlayerUtils.PlaybackData] so
 * the regular ExoPlayer stack consumes them unchanged.
 */
internal object InternetArchiveStreamResolver {
    private const val TAG = "ArchiveOrgFallback"
    private const val RESOLVE_TIMEOUT_MS = 15_000L
    private const val DEFAULT_STREAM_EXPIRY_SECONDS = 21_000
    private const val SEARCH_ROWS = 10
    private const val MAX_ITEMS_TO_INSPECT = 5
    private const val MIN_MATCH_SCORE = 0.6
    private const val SAME_TRACK_SCORE_TOLERANCE = 0.05

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
    private data class ArchiveSearchDoc(
        val identifier: String? = null,
        val title: String? = null,
    )

    @Serializable
    private data class ArchiveSearchBody(
        val numFound: Int = 0,
        val docs: List<ArchiveSearchDoc> = emptyList(),
    )

    @Serializable
    private data class ArchiveSearchResponse(
        val response: ArchiveSearchBody? = null,
    )

    @Serializable
    private data class ArchiveFile(
        val name: String? = null,
        val format: String? = null,
        val source: String? = null,
        val length: String? = null,
        val bitrate: String? = null,
        val size: String? = null,
        val title: String? = null,
    )

    @Serializable
    private data class ArchiveItemMetadata(
        val title: String? = null,
    )

    @Serializable
    private data class ArchiveMetadataResponse(
        val metadata: ArchiveItemMetadata? = null,
        val files: List<ArchiveFile> = emptyList(),
    )

    private data class TrackCandidate(
        val identifier: String,
        val item: ArchiveMetadataResponse,
        val file: ArchiveFile,
        val score: Double,
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
                throw IllegalStateException("Internet Archive needs a track title; skipping")
            }
            if (hostUnreachable) {
                throw IllegalStateException("archive.org marked unreachable this session")
            }
            withContext(Dispatchers.IO) {
                withTimeout(RESOLVE_TIMEOUT_MS) {
                    resolveFromArchive(videoId, audioQuality, queryTitle, artist?.trim()?.takeIf { it.isNotBlank() })
                }
            }
        }

    private suspend fun resolveFromArchive(
        videoId: String,
        audioQuality: AudioQuality,
        title: String,
        artist: String?,
    ): YTPlayerUtils.PlaybackData {
        var docs = searchItems(title, artist)
        if (docs.isEmpty() && artist != null) {
            // The creator clause can miss (featuring credits, name variants);
            // retry on title alone before giving up.
            docs = searchItems(title, null)
        }
        if (docs.isEmpty()) {
            throw IllegalStateException("Internet Archive found no match for \"$title\"")
        }

        var best: TrackCandidate? = null
        for (doc in docs.take(MAX_ITEMS_TO_INSPECT)) {
            val identifier = doc.identifier?.takeIf { it.isNotBlank() } ?: continue
            val item = fetchItem(identifier) ?: continue
            val candidate = bestTrackInItem(identifier, item, title) ?: continue
            if (best == null || candidate.score > best.score) {
                best = candidate
            }
        }
        val winner = best?.takeIf { it.score >= MIN_MATCH_SCORE }
            ?: throw IllegalStateException("Internet Archive found no confident match for \"$title\"")

        // The winner's siblings in the same item with (near-)equal title
        // scores are the same track in other encodings (original FLAC plus
        // MP3/Ogg derivatives); pick the best encode among them.
        val sameTrack =
            winner.item.files
                .filter { it.isAudioFile() }
                .filter { file ->
                    kotlin.math.abs(
                        trackScore(file, winner.item.metadata?.title, title) - winner.score,
                    ) <= SAME_TRACK_SCORE_TOLERANCE
                }
        val chosen = pickEncode(sameTrack.ifEmpty { listOf(winner.file) }, audioQuality)
        val streamUrl =
            "https://archive.org/download/${winner.identifier}/${chosen.name.orEmpty().encodePathSegment()}"
        val durationSeconds = parseLengthSeconds(chosen.length)
        val bitrateBps = effectiveBitrateBps(chosen, durationSeconds)
        Timber.tag(TAG).i(
            "Internet Archive resolved %s via %s: file=%s format=%s score=%.2f",
            videoId,
            winner.identifier,
            chosen.name,
            chosen.format,
            winner.score,
        )
        return YTPlayerUtils.PlaybackData(
            audioConfig = null,
            videoDetails =
                PlayerResponse.VideoDetails(
                    videoId = videoId,
                    title = chosen.title?.takeIf { it.isNotBlank() } ?: winner.item.metadata?.title ?: title,
                    author = artist,
                    lengthSeconds = durationSeconds?.takeIf { it > 0 }?.toString(),
                ),
            playbackTracking = null,
            format = chosen.toInnerTubeFormat(streamUrl, bitrateBps, durationSeconds),
            streamUrl = streamUrl,
            streamExpiresInSeconds = DEFAULT_STREAM_EXPIRY_SECONDS,
            authFingerprint = YouTube.currentPlaybackAuthState().fingerprint,
        )
    }

    private suspend fun searchItems(
        title: String,
        artist: String?,
    ): List<ArchiveSearchDoc> {
        val query =
            buildString {
                append("mediatype:audio AND title:(")
                append(title.luceneTerms())
                append(')')
                if (artist != null) {
                    append(" AND creator:(")
                    append(artist.luceneTerms())
                    append(')')
                }
            }
        val response: ArchiveSearchResponse =
            try {
                httpClient.get("https://archive.org/advancedsearch.php") {
                    parameter("q", query)
                    parameter("fl[]", "identifier")
                    parameter("fl[]", "title")
                    parameter("rows", SEARCH_ROWS)
                    parameter("output", "json")
                }.body()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                hostUnreachable = true
                Timber.tag(TAG).w(e, "archive.org search failed; marking host unreachable")
                throw e
            }
        return response.response?.docs.orEmpty().filter { !it.identifier.isNullOrBlank() }
    }

    private suspend fun fetchItem(identifier: String): ArchiveMetadataResponse? =
        runCatching {
            httpClient.get("https://archive.org/metadata/$identifier").body<ArchiveMetadataResponse>()
        }.onFailure { error ->
            if (error is CancellationException) throw error
            Timber.tag(TAG).w(error, "archive.org metadata failed for %s; skipping item", identifier)
        }.getOrNull()

    private fun bestTrackInItem(
        identifier: String,
        item: ArchiveMetadataResponse,
        title: String,
    ): TrackCandidate? =
        item.files
            .filter { it.isAudioFile() }
            .map { file ->
                TrackCandidate(
                    identifier = identifier,
                    item = item,
                    file = file,
                    score = trackScore(file, item.metadata?.title, title),
                )
            }.maxByOrNull { it.score }

    private fun trackScore(
        file: ArchiveFile,
        itemTitle: String?,
        title: String,
    ): Double {
        val candidate =
            file.title?.takeIf { it.isNotBlank() }
                ?: itemTitle?.takeIf { it.isNotBlank() }
                ?: file.name.orEmpty().substringBeforeLast('.')
        if (candidate.isBlank()) return 0.0
        return FuzzyMatcher.jaroWinkler(candidate, title)
    }

    private fun pickEncode(
        candidates: List<ArchiveFile>,
        audioQuality: AudioQuality,
    ): ArchiveFile {
        if (candidates.size == 1) return candidates.first()
        return if (audioQuality == AudioQuality.LOW) {
            candidates.minByOrNull { effectiveBitrateBps(it, parseLengthSeconds(it.length)) }
                ?: candidates.first()
        } else {
            candidates.minWithOrNull(
                compareBy<ArchiveFile> { formatRank(it.format) }
                    .thenByDescending { effectiveBitrateBps(it, parseLengthSeconds(it.length)) },
            ) ?: candidates.first()
        }
    }

    /**
     * Lower is better: lossless originals first, then the best lossy
     * encodes. Returns null for non-audio entries (images, metadata,
     * fingerprint files, torrents).
     */
    private fun formatRank(format: String?): Int? {
        val fmt = format?.trim()?.lowercase().orEmpty()
        return when {
            fmt == "flac" -> 0
            fmt == "wave" || fmt == "wav" || fmt == "aiff" -> 1
            fmt.contains("ogg") -> 2
            fmt.contains("opus") -> 3
            fmt.contains("mp3") -> 4
            fmt.contains("aac") || fmt.contains("m4a") -> 5
            else -> null
        }
    }

    private fun ArchiveFile.isAudioFile(): Boolean =
        !name.isNullOrBlank() && formatRank(format) != null

    private fun effectiveBitrateBps(
        file: ArchiveFile,
        durationSeconds: Int?,
    ): Int {
        file.bitrate?.toDoubleOrNull()?.takeIf { it > 0 }?.let { return (it * 1000).toInt() }
        val sizeBytes = file.size?.toLongOrNull()
        if (sizeBytes != null && sizeBytes > 0 && durationSeconds != null && durationSeconds > 0) {
            return (sizeBytes * 8 / durationSeconds).toInt()
        }
        return when (formatRank(file.format)) {
            0, 1 -> 1_000_000
            2, 3 -> 160_000
            4, 5 -> 192_000
            else -> 0
        }
    }

    private fun parseLengthSeconds(length: String?): Int? {
        val raw = length?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return if (raw.contains(':')) {
            raw.split(':')
                .map { it.toDoubleOrNull() ?: return null }
                .fold(0.0) { acc, part -> acc * 60 + part }
                .toInt()
        } else {
            raw.toDoubleOrNull()?.toInt()
        }
    }

    private fun String.luceneTerms(): String =
        replace(Regex("[+\\-&|!(){}\\[\\]^\"~*?:\\\\/]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "music" }

    private fun String.encodePathSegment(): String =
        URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")

    private fun ArchiveFile.toInnerTubeFormat(
        streamUrl: String,
        bitrateBps: Int,
        durationSeconds: Int?,
    ): PlayerResponse.StreamingData.Format {
        val mime =
            when (formatRank(format)) {
                0 -> "audio/flac"
                1 -> "audio/wav"
                2 -> "audio/ogg"
                3 -> "audio/opus"
                5 -> "audio/mp4"
                else -> "audio/mpeg"
            }
        return PlayerResponse.StreamingData.Format(
            itag = -1,
            url = streamUrl,
            mimeType = mime,
            bitrate = bitrateBps,
            width = null,
            height = null,
            contentLength = size?.toLongOrNull(),
            quality = if (bitrateBps >= 128_000) "medium" else "tiny",
            fps = null,
            qualityLabel = null,
            averageBitrate = bitrateBps.takeIf { it > 0 },
            audioQuality =
                when {
                    bitrateBps >= 128_000 -> "AUDIO_QUALITY_HIGH"
                    bitrateBps >= 64_000 -> "AUDIO_QUALITY_MEDIUM"
                    bitrateBps > 0 -> "AUDIO_QUALITY_LOW"
                    else -> null
                },
            approxDurationMs = durationSeconds?.takeIf { it > 0 }?.let { (it * 1000L).toString() },
            audioSampleRate = null,
            audioChannels = null,
            loudnessDb = null,
            lastModified = null,
            signatureCipher = null,
            cipher = null,
        )
    }
}
