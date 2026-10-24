package com.echoradio.app

import android.content.Context
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

data class Track(
    val id: String,
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val art: String,
    val thumb: String,
    val year: String,
    val durationMs: Long,
    val line: String = "",
    val explicit: Boolean = false
)

/** A snapshot of what the listener actually plays on Spotify. */
data class Taste(
    val nowArtists: List<String>,
    val allTimeArtists: List<String>,
    val genres: List<String>,
    val favourites: List<Track>,
    val recent: List<Track>
) {
    /** Short description handed to the DJ. */
    fun summary(): String = buildString {
        // A different random slice each time, so the DJ doesn't anchor on the same few names
        if (nowArtists.isNotEmpty()) append("Into lately: ${nowArtists.shuffled().take(7).joinToString(", ")}. ")
        if (allTimeArtists.isNotEmpty()) append("All-time favourites: ${allTimeArtists.shuffled().take(7).joinToString(", ")}. ")
        if (genres.isNotEmpty()) append("Genres they play most: ${genres.shuffled().joinToString(", ")}. ")
        val songs = (favourites.shuffled().take(8) + recent.shuffled().take(4)).distinctBy { it.id }
        if (songs.isNotEmpty()) append("Songs they love or played recently: " +
            songs.joinToString("; ") { "${it.artist.substringBefore(", ")} – ${it.title}" } + ".")
    }.trim()
}

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
}

/** Simple key/value settings stored on the phone. */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("echo_radio", Context.MODE_PRIVATE)

    private fun str(key: String, def: String = "") = p.getString(key, def) ?: def
    private fun put(key: String, value: String?) = p.edit().apply {
        if (value == null) remove(key) else putString(key, value)
    }.apply()

    // Always the built-in ID; ignore any old value saved by version 1.0
    var clientId: String get() = DEFAULT_CLIENT_ID; set(v) = put("client", null)
    var geminiKey: String get() = str("gemini_key"); set(v) = put("gemini_key", v.trim())
    var djName: String get() = str("name", "DJ Echo"); set(v) = put("name", v.trim().ifEmpty { "DJ Echo" })
    var model: String get() = str("gemini_model", DEFAULT_MODEL).let { if (it in OLD_MODELS || it.startsWith("claude")) DEFAULT_MODEL else it }; set(v) = put("gemini_model", v.trim().ifEmpty { DEFAULT_MODEL })

    var voice: String get() = str("voice", "Puck").let { if (it == "Ryan") it else "Puck" }; set(v) = put("voice", v)
    fun voiceIdFor(name: String): String? = p.getString("voice_id_$name", null)
    fun setVoiceIdFor(name: String, id: String) = put("voice_id_$name", id)

    /** Spotify IDs of the last ~300 songs played (newest last). */
    var playedIds: List<String>
        get() = str("played_ids").split(",").filter { it.isNotBlank() }
        set(v) = put("played_ids", v.takeLast(300).joinToString(","))
    /** "Artist – Title" of the last ~60 songs played, for the DJ's "don't repeat" list. */
    var playedNames: List<String>
        get() = str("played_names").split("\n").filter { it.isNotBlank() }
        set(v) = put("played_names", v.takeLast(60).joinToString("\n"))

    fun rememberPlayed(t: Track) {
        playedIds = playedIds.filter { it != t.id } + t.id
        val name = nameOf(t)
        playedNames = playedNames.filter { it != name } + name
        // Dated log for the weekly recap: "time|artist|title"
        val log = str("play_log").split("\n").filter { it.isNotBlank() }
        put("play_log", (log + "${System.currentTimeMillis()}|${t.artist.substringBefore(", ")}|${t.title}").takeLast(600).joinToString("\n"))
    }

    /** (time, artist, title) of songs played, oldest first. */
    val playLog: List<Triple<Long, String, String>>
        get() = str("play_log").split("\n").mapNotNull { l ->
            val parts = l.split("|"); if (parts.size < 3) null else parts[0].toLongOrNull()?.let { Triple(it, parts[1], parts[2]) }
        }

    // ---- likes and never-agains ----
    private fun nameOf(t: Track) = "${t.artist.substringBefore(", ")} – ${t.title}"
    val likedNames: List<String> get() = str("liked_names").split("\n").filter { it.isNotBlank() }
    fun addLiked(t: Track) = put("liked_names", (likedNames.filter { it != nameOf(t) } + nameOf(t)).takeLast(40).joinToString("\n"))
    fun removeLiked(t: Track) = put("liked_names", likedNames.filter { it != nameOf(t) }.joinToString("\n"))
    val dislikedIds: Set<String> get() = str("disliked_ids").split(",").filter { it.isNotBlank() }.toSet()
    val dislikedNames: List<String> get() = str("disliked_names").split("\n").filter { it.isNotBlank() }
    fun addDisliked(t: Track) {
        put("disliked_ids", (dislikedIds + t.id).joinToString(","))
        put("disliked_names", (dislikedNames.filter { it != nameOf(t) } + nameOf(t)).takeLast(40).joinToString("\n"))
    }

    // ---- weekly recap, alarm ----
    var lastGreetDay: String get() = str("greet_day"); set(v) = put("greet_day", v)
    var lastRecapAt: Long get() = p.getLong("last_recap", 0); set(v) = p.edit().putLong("last_recap", v).apply()
    var alarmOn: Boolean get() = p.getBoolean("alarm_on", false); set(v) = p.edit().putBoolean("alarm_on", v).apply()
    var alarmHour: Int get() = p.getInt("alarm_h", 7); set(v) = p.edit().putInt("alarm_h", v).apply()
    var alarmMinute: Int get() = p.getInt("alarm_m", 0); set(v) = p.edit().putInt("alarm_m", v).apply()

    var scopes: String get() = str("scopes"); set(v) = put("scopes", v)

    var verifier: String get() = str("verifier"); set(v) = put("verifier", v)
    var accessToken: String? get() = p.getString("access", null); set(v) = put("access", v)
    var refreshToken: String? get() = p.getString("refresh", null); set(v) = put("refresh", v)
    var expiresAt: Long get() = p.getLong("expires", 0); set(v) = p.edit().putLong("expires", v).apply()

    companion object {
        /** Echo Radio's registered Spotify app (dashboard: developer.spotify.com). */
        const val DEFAULT_CLIENT_ID = "04ebf8fe5f6a42a7a1b4fd9653be442f"
        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
        val OLD_MODELS = setOf("gemini-3.8-flash")
        const val TTS_MODEL = "gemini-3.8-flash-lite-tts"
        /** Voice models tried in order; each has its own free-tier limit. */
        val TTS_MODELS = listOf("gemini-3.8-flash-lite-tts", "gemini-3.8-flash-tts")
        const val PHONE_VOICE = "Phone voice"
    }
}
