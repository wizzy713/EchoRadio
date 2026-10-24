package com.echoradio.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class Pick(val artist: String, val title: String, val line: String)
/** What the listener asked for: a specific song, one artist, or a general vibe. */
data class MusicRequest(val type: String, val title: String = "", val artist: String = "")

data class SetPlan(val intro: String, val picks: List<Pick>, val category: String = "")

/** Asks Gemini to pick songs and write what the DJ says before each one. */
class DjBrain(private val prefs: Prefs) {

    suspend fun plan(vibe: String, more: Boolean, played: List<Track>, queued: List<Track>, artist: String? = null, taste: Taste? = null, madeForYou: Boolean = false,
             switchFrom: String? = null, skipped: List<Track> = emptyList(), greet: Boolean = false): SetPlan =
        withContext(Dispatchers.IO) {
            val name = prefs.djName
            val playedTxt = (prefs.playedNames + played.map { "${it.artist.substringBefore(", ")} – ${it.title}" })
                .distinct().takeLast(60).joinToString("; ").ifEmpty { "nothing yet" }
            val angle = ANGLES.random()
            // Only open-ended requests get a random angle; anything specific is followed exactly
            val openEnded = !vibe.startsWith(SIMILAR_TO) && (madeForYou || switchFrom != null || vibe.contains(':')) ||
                !vibe.startsWith(SIMILAR_TO) &&
                Regex("\\b(surprise|anything|something|random|mix|vibes?|mood|chill|party|focus|study|workout|relax|sleep|new music)\\b", RegexOption.IGNORE_CASE).containsMatchIn(vibe)
            val queuedTxt = queued.joinToString("; ") { "${it.artist} – ${it.title}" }.ifEmpty { "nothing" }
            val system = "You are $name, a warm, upbeat radio DJ hosting a personal music stream for one listener in South Africa. " +
                "Speak like a real DJ on air: casual, very short, specific. Say less rather than more. Share a fact about an artist or song only when you are confident it is true; " +
                "otherwise talk about the feel of the track. No emoji, hashtags or stage directions, because your words are read aloud."
            val introSpec = if (more) "" else "one short sentence (under 15 words) reacting to the vibe"
            val user = """
                Listener's vibe: "$vibe".
                ${if (more) "Keep the set going in the same spirit with fresh picks." else "This is a new set."}
                ${nowContext(greet && !more)}
                ${if (prefs.likedNames.isNotEmpty()) "Songs they hearted recently (more like these): ${prefs.likedNames.takeLast(12).joinToString("; ")}." else ""}
                ${if (prefs.dislikedNames.isNotEmpty()) "Songs they never want again (avoid these and anything too similar): ${prefs.dislikedNames.takeLast(12).joinToString("; ")}." else ""}
                Recently played (this and earlier sessions): $playedTxt. Already queued: $queuedTxt. Don't repeat any of these.
                ${when {
                    artist != null -> ""
                    vibe.startsWith(SIMILAR_TO) -> "Pick songs that match the sound and mood of that song: same lane of genre, tempo, energy and era. Mix artists (at most 2 songs by the same artist, and not that song again)."
                    openEnded -> "For freshness, take this angle this time: $angle. Avoid the most obvious, most-streamed picks; at least half should be songs the listener probably hasn't heard much. Every set should feel different from the last."
                    else -> "The listener asked for something specific. Follow it exactly: if they name artists, every song must be by those artists (features count); if they name songs, genres or eras, stay squarely inside that. Don't drift to other styles."
                }}
                ${when {
                    artist != null && !more -> "The listener asked for $artist. Every song MUST be by $artist (songs where $artist is a featured artist also count)."
                    artist != null -> "The listener asked for $artist. Make 5 of the 6 songs by $artist, and switch it up with 1 song by similar artists they'd probably love."
                    more -> "Switch it up a little from what's already played: drift to a neighbouring style, era or tempo while keeping the overall vibe. At most one song per artist."
                    else -> "Mix it up: vary eras, tempos and sub-genres, include a couple of surprising-but-fitting picks, and at most one song per artist."
                }}
                ${when {
                    taste == null -> ""
                    madeForYou -> "This is a personal mix. What they listen to: ${taste.summary()} Pick songs they'd love: mostly artists they already play, plus artists very close to their taste they may not know yet. Up to 2 can be songs they already love."
                    artist == null && openEnded -> "Tailor it to their taste while matching the vibe. What they listen to: ${taste.summary()} Lean toward their artists and similar ones; at most 1 song they already know well."
                    else -> ""
                }}
                ${if (switchFrom != null) "The listener kept skipping songs from \"$switchFrom\"" +
                    (if (skipped.isNotEmpty()) " (skipped: ${skipped.takeLast(5).joinToString("; ") { "${it.artist.substringBefore(", ")} – ${it.title}" }})" else "") +
                    ". Switch to a clearly different direction they'd still enjoy. If that request named an artist, stay close to it: " +
                    "move to similar artists in the same lane rather than a totally different genre" +
                    (if (taste != null) ", based on their taste" else "") + ". Name it in \"category\" (2-4 words)." else ""}
                Pick 6 real songs that are on Spotify. Return ONLY JSON:
                {"intro":"$introSpec",${if (switchFrom != null) "\"category\":\"\"," else ""}"tracks":[{"artist":"","title":"","line":"one short sentence (under 18 words) said before the song. Mention only the main artist, never featured artists. Don't always name the song"}]}
            """.trimIndent()

            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                .put("contents", JSONArray().put(JSONObject().put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", user)))))
                .put("generationConfig", JSONObject()
                    .put("responseMimeType", "application/json")
                    .put("temperature", 1.3)
                    .put("topP", 0.97)
                    .put("seed", (0..Int.MAX_VALUE).random()))
                .toString()
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/${prefs.model}:generateContent")
                .header("x-goog-api-key", prefs.geminiKey)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            val client = Http.client.newBuilder()
                .callTimeout(if (more) 30L else 16L, java.util.concurrent.TimeUnit.SECONDS).build()
            client.newCall(req).execute().use { r ->
                val raw = r.body?.string() ?: ""
                if (!r.isSuccessful) {
                    val msg = runCatching { JSONObject(raw).getJSONObject("error").getString("message") }.getOrNull()
                    throw Exception("Gemini returned ${r.code}" + (msg?.let { ": $it" } ?: ""))
                }
                val parts = JSONObject(raw).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts")
                val text = (0 until parts.length())
                    .filter { !parts.getJSONObject(it).optBoolean("thought") }
                    .joinToString("") { parts.getJSONObject(it).optString("text") }
                val json = JSONObject(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1))
                val arr = json.optJSONArray("tracks") ?: JSONArray()
                SetPlan(
                    intro = json.optString("intro"),
                    category = json.optString("category"),
                    picks = (0 until arr.length()).map {
                        val o = arr.getJSONObject(it)
                        Pick(o.optString("artist"), o.optString("title"), o.optString("line"))
                    }.filter { it.artist.isNotBlank() && it.title.isNotBlank() }
                )
            }
        }

    /** e.g. "It's Saturday, 17:13 (late afternoon) in South Africa. Let that shape the energy and how you greet them." */
    private fun nowContext(greet: Boolean): String {
        val now = java.time.LocalDateTime.now()
        val part = when (now.hour) {
            in 5..8 -> "early morning"; in 9..11 -> "morning"; in 12..13 -> "midday"; in 14..16 -> "afternoon"
            in 17..19 -> "early evening"; in 20..22 -> "evening"; else -> "late night"
        }
        val day = now.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)
        val time = "It's $day, ${"%02d:%02d".format(now.hour, now.minute)} ($part) in South Africa. Let that quietly shape the energy."
        return if (greet) "$time This is their first set today, so you can greet them for the time of day."
        else "$time Don't greet them, and don't mention the day or the time; just react to what they asked."
    }

    /** One plain-text reply from Gemini (used for song facts and the weekly recap). */
    private suspend fun say(prompt: String, timeoutSec: Long = 15): String = withContext(Dispatchers.IO) {
        val system = "You are ${prefs.djName}, a warm radio DJ talking to one listener in South Africa. " +
            "Your words are read aloud: no emoji, hashtags, lists or stage directions. Only state facts you're confident are true."
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .toString()
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/${prefs.model}:generateContent")
            .header("x-goog-api-key", prefs.geminiKey)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val client = Http.client.newBuilder().callTimeout(timeoutSec, java.util.concurrent.TimeUnit.SECONDS).build()
        client.newCall(req).execute().use { r ->
            val raw = r.body?.string() ?: ""
            if (!r.isSuccessful) throw Exception("Gemini returned ${r.code}")
            val parts = JSONObject(raw).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
            (0 until parts.length()).filter { !parts.getJSONObject(it).optBoolean("thought") }
                .joinToString("") { parts.getJSONObject(it).optString("text") }.trim()
        }
    }

    /** Quickly works out whether the request is a song, an artist or a vibe (about a second). */
    suspend fun understand(request: String): MusicRequest = withContext(Dispatchers.IO) {
        val prompt = "A listener typed this music request: \"$request\"\n" +
            "Classify it. Return ONLY JSON: {\"type\":\"song|artist|vibe\",\"title\":\"\",\"artist\":\"\"}\n" +
            "- song: they want one specific song, even if they don't say the word song (e.g. \"lucid dreams\", \"play saturn by sza\", \"that blinding lights song\"). " +
            "Give the official title, and the artist if you know it.\n" +
            "- artist: they want music by one artist. Give the artist's correct name (with accents) in artist.\n" +
            "- vibe: anything else (moods, genres, eras, several artists, open requests). Leave title and artist empty."
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0))
            .toString()
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/${prefs.model}:generateContent")
            .header("x-goog-api-key", prefs.geminiKey)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val client = Http.client.newBuilder().callTimeout(6, java.util.concurrent.TimeUnit.SECONDS).build()
        client.newCall(req).execute().use { r ->
            val raw = r.body?.string() ?: ""
            if (!r.isSuccessful) throw Exception("Gemini returned ${r.code}")
            val parts = JSONObject(raw).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
            val text = (0 until parts.length()).filter { !parts.getJSONObject(it).optBoolean("thought") }
                .joinToString("") { parts.getJSONObject(it).optString("text") }
            val j = JSONObject(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1))
            MusicRequest(j.optString("type").lowercase(), j.optString("title").trim(), j.optString("artist").trim())
        }
    }

    /** A quick spoken bit about the song that's playing. */
    suspend fun about(t: Track): String = say(
        "The listener tapped the song that's playing and wants to know about it: \"${t.title}\" by ${t.artist}" +
            (if (t.album.isNotBlank()) " from the album \"${t.album}\"" else "") + (if (t.year.isNotBlank()) " (${t.year})" else "") +
            ". In 2-3 short sentences (under 60 words), tell them something interesting about the song or the artist. " +
            "If you're not sure of specific facts, talk about the artist's style and what makes the track work."
    )

    /** The weekly recap, from the listener's real play counts. */
    suspend fun recap(stats: String): String = say(
        "Give the listener a quick weekly recap of their listening, like a radio DJ would, in 3-4 short sentences (under 70 words). " +
            "Here are their real stats for the past 7 days: $stats Be warm and specific, then say what you'll lean into next."
    )

    companion object {
        /** Vibe text for "keep going with songs like the one I asked for". */
        const val SIMILAR_TO = "More songs with the same vibe as"

        /** Random directions so "surprise me" never plays out the same way twice. */
        private val ANGLES = listOf(
            "deep cuts and album tracks instead of singles", "songs from the 2000s", "songs from the 2010s",
            "brand-new releases from the last year", "90s classics", "late-night slow burners", "high-energy bangers",
            "South African artists", "African artists beyond South Africa", "UK artists", "underrated gems",
            "features and collaborations", "artists the listener likes, but their less famous songs",
            "artists similar to their favourites they may not know yet", "feel-good summer songs",
            "moody and atmospheric", "songs built around a great beat drop", "throwbacks they'd be surprised to hear",
            "one-hit wonders and forgotten bangers", "rising artists with small followings"
        )

        private fun main(t: Track) = t.artist.substringBefore(", ")
        private val lines = listOf<(Track) -> String>(
            { "Here's ${main(it)}. Turn it up." },
            { "Next up, ${it.title}." },
            { "Keeping it moving with ${main(it)}." },
            { "This one's ${main(it)}${if (it.year.isNotEmpty()) ", from ${it.year}" else ""}." },
            { "Switching it up a little." }
        )

        /** Built-in line for when there's no Gemini key. */
        fun simpleLine(t: Track) = lines.random()(t)
    }
}
