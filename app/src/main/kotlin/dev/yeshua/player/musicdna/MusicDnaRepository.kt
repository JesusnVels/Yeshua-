/*
 * LunarTune (2026)
 * © cognitiveshadows03 — github.com/cognitiveshadows03
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package dev.yeshua.player.musicdna

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import dev.yeshua.player.utils.dataStore
import dev.yeshua.player.utils.reportException
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class MusicDnaBackstory(
    val title: String,
    val text: String,
    val source: String,
    val fetchedAt: Long,
)

/**
 * Short artist/track backstories for the Music DNA section.
 *
 * No Genius token is plumbed in the app, so this uses the Wikipedia REST summary
 * API (https://en.wikipedia.org/api/rest_v1/page/summary/{title}), which needs no
 * key. Results are cached in memory and in DataStore with a TTL; failures —
 * including offline — fall back to the cache, and callers hide the section when
 * there is nothing to show.
 */
@Singleton
class MusicDnaRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val memoryCache = ConcurrentHashMap<String, MusicDnaBackstory>()

        private val client =
            HttpClient(OkHttp) {
                engine {
                    config {
                        connectTimeout(NetTimeoutSeconds, TimeUnit.SECONDS)
                        readTimeout(NetTimeoutSeconds, TimeUnit.SECONDS)
                        writeTimeout(NetTimeoutSeconds, TimeUnit.SECONDS)
                        retryOnConnectionFailure(false)
                    }
                }
            }

        suspend fun backstory(
            artistName: String,
            trackTitle: String,
        ): MusicDnaBackstory? =
            withContext(Dispatchers.IO) {
                val key = cacheKey(artistName, trackTitle)
                memoryCache[key]?.takeIf { it.isFresh() }?.let { return@withContext it }
                readDiskCache()[key]?.takeIf { it.isFresh() }?.let {
                    memoryCache[key] = it
                    return@withContext it
                }
                val fetched =
                    try {
                        fetchBackstory(artistName, trackTitle)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        reportException(e)
                        null
                    }
                if (fetched != null) {
                    memoryCache[key] = fetched
                    writeDiskCache(key, fetched)
                }
                fetched
            }

        private suspend fun fetchBackstory(
            artistName: String,
            trackTitle: String,
        ): MusicDnaBackstory? {
            val candidates =
                listOf(artistName, "$trackTitle (song)", trackTitle)
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
            for (candidate in candidates) {
                val summary = fetchSummary(candidate) ?: continue
                return summary
            }
            return null
        }

        private suspend fun fetchSummary(title: String): MusicDnaBackstory? {
            val encoded = URLEncoder.encode(title, Charsets.UTF_8.name()).replace("+", "%20")
            val response: HttpResponse =
                client.get("$WikipediaSummaryBase/$encoded") {
                    headers {
                        append("User-Agent", USER_AGENT)
                    }
                }
            if (response.status != HttpStatusCode.OK) return null
            val json = JSONObject(response.bodyAsText())
            if (json.optString("type") == "disambiguation") return null
            val extract = json.optString("extract").trim()
            if (extract.isBlank()) return null
            return MusicDnaBackstory(
                title = json.optString("title").ifBlank { title },
                text = extract.take(MaxExtractChars),
                source = "Wikipedia",
                fetchedAt = System.currentTimeMillis(),
            )
        }

        private suspend fun readDiskCache(): Map<String, MusicDnaBackstory> =
            runCatching {
                val json = context.dataStore.data.map { it[MusicDnaCacheKey] }.first()
                if (json.isNullOrBlank()) return emptyMap()
                val root = JSONObject(json)
                buildMap {
                    root.keys().forEach { key ->
                        val entry = root.optJSONObject(key) ?: return@forEach
                        val text = entry.optString("text")
                        if (text.isBlank()) return@forEach
                        put(
                            key,
                            MusicDnaBackstory(
                                title = entry.optString("title"),
                                text = text,
                                source = entry.optString("source").ifBlank { "Wikipedia" },
                                fetchedAt = entry.optLong("fetchedAt"),
                            ),
                        )
                    }
                }
            }.getOrElse { error ->
                reportException(error)
                emptyMap()
            }

        private suspend fun writeDiskCache(
            key: String,
            backstory: MusicDnaBackstory,
        ) {
            runCatching {
                val pruned =
                    (readDiskCache() + (key to backstory)).filterValues { it.isFresh() }
                val root = JSONObject()
                pruned.forEach { (entryKey, entry) ->
                    root.put(
                        entryKey,
                        JSONObject().apply {
                            put("title", entry.title)
                            put("text", entry.text)
                            put("source", entry.source)
                            put("fetchedAt", entry.fetchedAt)
                        },
                    )
                }
                context.dataStore.edit { prefs ->
                    prefs[MusicDnaCacheKey] = root.toString()
                }
            }.onFailure { reportException(it) }
        }

        private fun cacheKey(
            artistName: String,
            trackTitle: String,
        ): String = "v1|${artistName.trim().lowercase()}|${trackTitle.trim().lowercase()}"

        private fun MusicDnaBackstory.isFresh(): Boolean =
            System.currentTimeMillis() - fetchedAt < CacheTtlMs

        companion object {
            private val MusicDnaCacheKey = stringPreferencesKey("music_dna_cache_v1")
            private const val WikipediaSummaryBase = "https://en.wikipedia.org/api/rest_v1/page/summary"
            private const val USER_AGENT = "Yeshua/1.0 (music-backstory; https://github.com/ReCapzPhyco-420/Yeshua)"
            private const val NetTimeoutSeconds = 8L
            private const val CacheTtlMs = 30L * 24 * 60 * 60 * 1000
            private const val MaxExtractChars = 700
        }
    }
