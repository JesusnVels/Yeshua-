/*
 * LunarTune (2026)
 * © cognitiveshadows03 — github.com/cognitiveshadows03
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

/*
 * Built-in knowledge base for Yeshua's AI assistant. Answers are served
 * locally (no network needed) for "how do I …" questions. Keep entries short
 * and verify them against the real app before adding new ones.
 */

package dev.yeshua.player.utils

object AssistantKnowledge {
    private data class Entry(
        val keywords: List<String>,
        val answer: String,
    )

    private val entries =
        listOf(
            Entry(
                keywords = listOf("player design", "player style", "player layout", "fusion", "change player", "now playing style"),
                answer = "You can change the Now Playing look under Settings → Appearance → Player design. There are 12 styles: Classic, Modern, Minimal, Cinematic, Little, Expressive, Immersive, Immersive Legacy, Immersive Extended, Material Extended, Apple Music, and Fusion — Fusion blends Spotify-style full-bleed artwork with a YouTube Music-style action row.",
            ),
            Entry(
                keywords = listOf("sleep timer", "sleep", "auto stop", "turn off music"),
                answer = "The sleep timer lives in the player menu: open Now Playing, tap the ⋮ menu, and choose Sleep timer. You can also set it to finish the current song instead of cutting it off mid-play.",
            ),
            Entry(
                keywords = listOf("stats", "statistics", "most played", "top songs", "listening history", "wrapped"),
                answer = "Your listening stats live under Settings → Statistics (or the Stats screen). You'll find most-played songs, artists and albums, listening patterns by hour and day, a yearly calendar, and trends. Your history also powers smart features like Forgotten Favorites on Home.",
            ),
            Entry(
                keywords = listOf("tempo", "import history", "import stats", "tempo backup"),
                answer = "You can import listening history from the Tempo app: open the Stats screen, tap the import button in the top bar, and pick your Tempo backup ZIP. Plays are matched to your library first, then looked up on YouTube so nothing gets missed. You can also turn on background import so it keeps working if you leave the screen.",
            ),
            Entry(
                keywords = listOf("download", "offline", "save song", "offline mode"),
                answer = "To listen offline, download songs or playlists — look for the download button on songs, albums and playlists. Downloaded music plays without any connection. Manage everything under Settings → Storage → Downloads.",
            ),
            Entry(
                keywords = listOf("lyrics", "synced lyrics", "karaoke", "lyrics offset", "lyrics timing"),
                answer = "Lyrics show on the Now Playing screen — swipe or tap to open them. Yeshua pulls time-synced lyrics from several providers and shows them karaoke-style. If the timing feels off, open the lyrics menu and nudge the sync offset; it's remembered per song.",
            ),
            Entry(
                keywords = listOf("queue", "up next", "play next", "add to queue"),
                answer = "Open the queue from Now Playing to see what's up next. Long-press any song, album or artist for options like Play next and Add to queue. You can also save named queues and they'll survive app restarts.",
            ),
            Entry(
                keywords = listOf("android auto", "car", "carplay"),
                answer = "Yeshua works with Android Auto: browse Home, playlists, artists and downloads from your car's screen, and use voice commands like “Play …”. There's also an Up Next view in the car.",
            ),
            Entry(
                keywords = listOf("backup", "restore", "export", "transfer"),
                answer = "Back up your playlists, settings and stats under Settings → Storage → Backup & restore. Move the backup file to a new device to restore everything.",
            ),
            Entry(
                keywords = listOf("theme", "dark mode", "amoled", "black theme", "appearance", "color"),
                answer = "Look and feel live under Settings → Appearance: dynamic Material You colors or preset palettes, dark mode, a pure-black OLED option (Yeshua Black keeps your accent color), custom fonts, blur controls and more.",
            ),
            Entry(
                keywords = listOf("equalizer", "eq", "bass", "audio quality"),
                answer = "The equalizer lives in the player settings — you'll find presets and manual bands there. Audio quality for streaming and downloads can be tuned under Settings → Player.",
            ),
            Entry(
                keywords = listOf("account", "login", "sign in", "youtube account", "spotify"),
                answer = "Manage accounts under Settings → Account. You can sign in with YouTube for your library and playlists, and connect Spotify for discovery features like playlist import.",
            ),
            Entry(
                keywords = listOf("assistant", "what can you do", "help", "commands", "features"),
                answer = "I'm Yeshua's built-in assistant. I can play or queue any song (try “play …”), show lyrics for what's playing, name a song from a lyric snippet, dig into your stats (“what's my top artist this month”, “play something I haven't heard in a while”), and explain any app feature — just ask.",
            ),
        )

    /**
     * Returns a local answer for "how do I …" style questions, or null when
     * the query isn't a known help topic (caller falls back to the AI).
     */
    fun findLocalAnswer(query: String): String? {
        val q = query.lowercase()
        var best: Entry? = null
        var bestScore = 0
        for (entry in entries) {
            var score = 0
            for (kw in entry.keywords) {
                if (q.contains(kw)) score += kw.length
            }
            if (score > bestScore) {
                bestScore = score
                best = entry
            }
        }
        // Require a meaningful keyword hit, not a stray two-letter match.
        return if (bestScore >= 6) best?.answer else null
    }

    /** Short capability summary, injected into the AI system prompt. */
    fun capabilitySummary(): String =
        "You can: play or queue songs by name; show synced lyrics for the now-playing song or a named song; " +
            "identify a song from a lyric snippet; answer personalized stats questions using the provided " +
            "listening summary (top songs/artists, forgotten favorites); explain app features " +
            "(12 player designs incl. Fusion, sleep timer in the player ⋮ menu, Stats screen, Tempo history import, " +
            "downloads/offline, lyrics with per-song sync offset, named queues, Android Auto, backup & restore). " +
            "When the user wants an action, emit exactly one JSON block on its own line AFTER your reply text, " +
            "using one of these schemas: " +
            "{\"action\":\"play\",\"query\":\"song title artist\"}, " +
            "{\"action\":\"queue\",\"query\":\"song title artist\"}, " +
            "{\"action\":\"play_most_played\"}, " +
            "{\"action\":\"play_forgotten\"}, " +
            "{\"action\":\"lyrics\",\"query\":\"optional song title artist\"}, " +
            "{\"action\":\"identify\",\"snippet\":\"lyric words\"}. " +
            "Omit the JSON block for plain conversation. Never invent features the app doesn't have. " +
            "Keep replies short (1-3 sentences) unless the user asks for detail."
}
