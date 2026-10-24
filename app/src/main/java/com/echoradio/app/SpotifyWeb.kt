package com.echoradio.app

import android.app.Activity
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Spotify Web API access: PKCE login in a browser tab (no server needed) and track search.
 * Playback itself goes through the Spotify app via App Remote (see MainActivity).
 */
class SpotifyWeb(private val prefs: Prefs) {

    companion object {
        const val REDIRECT_URI = "echoradio://callback"
        private const val SCOPES =
            "app-remote-control user-read-private user-read-playback-state user-modify-playback-state " +
                "user-top-read user-library-read user-read-recently-played"
        const val TASTE_SCOPE = "user-top-read"
    }

    private fun b64url(bytes: ByteArray) =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    fun startLogin(activity: Activity) {
        val raw = ByteArray(64).also { SecureRandom().nextBytes(it) }
        val verifier = b64url(raw)
        prefs.verifier = verifier
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val url = Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", prefs.clientId)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .build()
        CustomTabsIntent.Builder().build().launchUrl(activity, url)
    }

    val isLoggedIn get() = prefs.refreshToken != null

    suspend fun exchangeCode(code: String) = tokenRequest(
        FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", REDIRECT_URI)
            .add("client_id", prefs.clientId)
            .add("code_verifier", prefs.verifier)
            .build()
    )

    private suspend fun tokenRequest(body: FormBody): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://accounts.spotify.com/api/token").post(body).build()
        Http.client.newCall(req).execute().use { r ->
            val json = JSONObject(r.body?.string() ?: "{}")
            if (!r.isSuccessful) {
                throw Exception("Spotify login failed: " + json.optString("error_description", json.optString("error", "${r.code}")))
            }
            val token = json.getString("access_token")
            prefs.accessToken = token
            prefs.expiresAt = System.currentTimeMillis() + json.getLong("expires_in") * 1000 - 60_000
            json.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { prefs.refreshToken = it }
            json.optString("scope").takeIf { it.isNotEmpty() }?.let { prefs.scopes = it }
            token
        }
    }

    private suspend fun token(): String {
        val t = prefs.accessToken
        if (t != null && System.currentTimeMillis() < prefs.expiresAt) return t
        val rt = prefs.refreshToken ?: throw Exception("Not connected to Spotify")
        return tokenRequest(
            FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", rt)
                .add("client_id", prefs.clientId)
                .build()
        )
    }

    fun logout() {
        prefs.accessToken = null; prefs.refreshToken = null; prefs.expiresAt = 0; prefs.scopes = ""
    }

    /** True once the user has granted the permissions needed to read their music taste. */
    val hasTasteAccess get() = prefs.scopes.split(" ").contains(TASTE_SCOPE)

    private suspend fun getJson(path: String): JSONObject? {
        val tok = token()
        return withContext(Dispatchers.IO) {
            val req = Request.Builder().url("https://api.spotify.com/v1/$path")
                .header("Authorization", "Bearer $tok").build()
            Http.client.newCall(req).execute().use { r ->
                if (r.code == 401) prefs.expiresAt = 0
                if (!r.isSuccessful) null else JSONObject(r.body?.string() ?: "{}")
            }
        }
    }

    private fun tracksFrom(arr: org.json.JSONArray?, key: String? = null): List<Track> =
        (0 until (arr?.length() ?: 0)).mapNotNull { i ->
            val o = arr!!.optJSONObject(i) ?: return@mapNotNull null
            val t = if (key != null) o.optJSONObject(key) else o
            t?.let { runCatching { toTrack(it) }.getOrNull() }
        }

    /** Reads the listener's top artists, top songs, Liked Songs and recent plays. */
    suspend fun taste(): Taste? = try {
        val topLong = getJson("me/top/artists?time_range=long_term&limit=15")
        val topNow = getJson("me/top/artists?time_range=short_term&limit=15")
        val topTracks = getJson("me/top/tracks?time_range=short_term&limit=20")
        val liked = getJson("me/tracks?limit=50")
        val recent = getJson("me/player/recently-played?limit=30")
        if (topLong == null && topNow == null && liked == null) null else {
            fun artists(j: JSONObject?) = (0 until (j?.optJSONArray("items")?.length() ?: 0)).map {
                j!!.getJSONArray("items").getJSONObject(it)
            }
            val all = artists(topNow) + artists(topLong)
            val genres = all.flatMap { a ->
                val g = a.optJSONArray("genres"); (0 until (g?.length() ?: 0)).map { g!!.getString(it) }
            }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(8).map { it.key }
            val favTracks = (tracksFrom(topTracks?.optJSONArray("items")) +
                tracksFrom(liked?.optJSONArray("items"), "track")).distinctBy { it.id }
            val recentTracks = tracksFrom(recent?.optJSONArray("items"), "track").distinctBy { it.id }
            Taste(
                nowArtists = artists(topNow).map { it.getString("name") },
                allTimeArtists = artists(topLong).map { it.getString("name") },
                genres = genres,
                favourites = favTracks,
                recent = recentTracks
            )
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        null
    }

    suspend fun search(query: String, limit: Int = 10, offset: Int = 0): List<Track> {
        val tok = token()
        return withContext(Dispatchers.IO) {
            val url = Uri.parse("https://api.spotify.com/v1/search").buildUpon()
                .appendQueryParameter("type", "track")
                .appendQueryParameter("market", "from_token")
                .appendQueryParameter("limit", limit.toString())
                .appendQueryParameter("offset", offset.toString())
                .appendQueryParameter("q", query)
                .build().toString()
            val req = Request.Builder().url(url).header("Authorization", "Bearer $tok").build()
            Http.client.newCall(req).execute().use { r ->
                if (r.code == 401) prefs.expiresAt = 0
                if (!r.isSuccessful) throw Exception("Spotify search failed (${r.code})")
                val items = JSONObject(r.body?.string() ?: "{}").optJSONObject("tracks")?.optJSONArray("items")
                    ?: return@use emptyList()
                (0 until items.length()).mapNotNull { i -> items.optJSONObject(i)?.let(::toTrack) }
            }
        }
    }

    private fun toTrack(t: JSONObject): Track {
        val album = t.getJSONObject("album")
        val images = album.optJSONArray("images")
        val big = images?.optJSONObject(0)?.optString("url") ?: ""
        val small = images?.optJSONObject((images.length() - 1).coerceAtLeast(0))?.optString("url") ?: big
        val artists = t.getJSONArray("artists")
        return Track(
            id = t.getString("id"),
            uri = t.getString("uri"),
            title = t.getString("name"),
            artist = (0 until artists.length()).joinToString(", ") { artists.getJSONObject(it).getString("name") },
            album = album.optString("name"),
            art = big,
            thumb = small,
            year = album.optString("release_date").take(4),
            durationMs = t.optLong("duration_ms"),
            explicit = t.optBoolean("explicit")
        )
    }

    /** "Jhené Aiko" and "jhene aiko" compare equal: accents removed, then letters and digits only. */
    private fun norm(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase().replace(Regex("[^a-z0-9]"), "")

    /** 0..1 how alike two names are (handles small typos like "jhene" vs "jhené" or "sza" vs "s.z.a"). */
    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length)
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        return 1.0 - d[a.length][b.length].toDouble() / maxOf(a.length, b.length)
    }

    /** Strips filler like "play some songs by" so "play me some Drake please" -> "drake". */
    private fun artistQuery(vibe: String): String {
        var q = " " + vibe.lowercase().trim() + " "
        val fillers = listOf(
            "can you", "could you", "please", "i want to hear", "i want", "listen to", "put on",
            "play me", "play", "give me", "only", "just", "some", "songs by", "songs from", "music by",
            "music from", "tracks by", "hits by", "the best of", "best of", "songs", "song", "music",
            "tracks", "hits", "by", "from", "me"
        )
        for (f in fillers) q = q.replace(Regex("\\b" + Regex.escape(f) + "\\b"), " ")
        return q.replace(Regex("[!?.,]"), " ").replace(Regex("\\s+"), " ").trim()
    }

    /** If the vibe names an artist on Spotify, returns that artist's exact name, else null. */
    suspend fun detectArtist(vibe: String): String? = try {
        val q = artistQuery(vibe)
        val generic = Regex("\\b(surprise|mood|vibes?|chill|focus|party|workout|study|sleep|relax|throwbacks?|playlist|mix|for a|something)\\b")
        if (q.length < 2 || generic.containsMatchIn(vibe.lowercase())) null else {
            val tok = token()
            withContext(Dispatchers.IO) {
                val url = Uri.parse("https://api.spotify.com/v1/search").buildUpon()
                    .appendQueryParameter("type", "artist")
                    .appendQueryParameter("limit", "5")
                    .appendQueryParameter("q", q)
                    .build().toString()
                val req = Request.Builder().url(url).header("Authorization", "Bearer $tok").build()
                Http.client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    val items = JSONObject(r.body?.string() ?: "{}").optJSONObject("artists")?.optJSONArray("items")
                        ?: return@use null
                    val nq = norm(q).removePrefix("the")
                    val names = (0 until items.length()).map { items.getJSONObject(it).getString("name") }
                    // Exact name, or the request contains the full name ("kendrick lamar hits")
                    names.firstOrNull { val n = norm(it).removePrefix("the"); n.length >= 2 && (n == nq || (n.length >= 4 && nq.contains(n))) }
                        // or the top result starts with what was typed ("kendrick" -> Kendrick Lamar)
                        ?: names.firstOrNull()?.takeIf { val n = norm(it).removePrefix("the"); nq.length >= 5 && n.startsWith(nq) }
                        // or a close spelling of one of the top results
                        ?: names.take(3).firstOrNull { similarity(norm(it).removePrefix("the"), nq) >= 0.8 }
                }
            }
        }
    } catch (e: Exception) {
        null
    }

    /** Finds a specific song on Spotify (explicit version when there is one). */
    suspend fun findSong(title: String, artist: String): Track? = try {
        var rs = if (artist.isNotEmpty()) search("track:$title artist:$artist", 10) else search("track:$title", 10)
        if (rs.isEmpty()) rs = search("$title $artist".trim(), 10)
        val nt = baseTitle(title)
        val na = norm(artist)
        // Title matches (small typos allowed), and the artist too when one was given
        val hit = rs.filter { t -> similarity(baseTitle(t.title), nt) >= 0.75 || baseTitle(t.title).contains(nt) }
            .firstOrNull { t -> na.isEmpty() || t.artist.split(", ").any { norm(it).contains(na) || similarity(norm(it), na) >= 0.75 } }
            ?: rs.firstOrNull()
        hit?.let { h -> rs.firstOrNull { it.explicit && sameSong(it.title, h.title) && norm(it.artist) == norm(h.artist) } ?: h }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        null
    }

    /** Backup when Gemini isn't available: "play the song <title>" or "play the song <title> by <artist>". */
    suspend fun detectSong(vibe: String): Track? {
        val m = Regex("^\\s*(?:please\\s+)?play\\s+(?:me\\s+)?the\\s+song\\s+(.+?)(?:\\s+by\\s+(.+?))?\\s*[.!?]*\\s*$", RegexOption.IGNORE_CASE)
            .find(vibe) ?: return null
        return findSong(m.groupValues[1].trim().trim('"', '\'', '“', '”'), m.groupValues.getOrNull(2)?.trim().orEmpty())
    }

    /** Spotify's exact spelling of an artist name Gemini gave us ("jhene aiko" -> "Jhené Aiko"). */
    suspend fun resolveArtist(name: String): String = try {
        val tok = token()
        withContext(Dispatchers.IO) {
            val url = Uri.parse("https://api.spotify.com/v1/search").buildUpon()
                .appendQueryParameter("type", "artist").appendQueryParameter("limit", "5").appendQueryParameter("q", name)
                .build().toString()
            val req = Request.Builder().url(url).header("Authorization", "Bearer $tok").build()
            Http.client.newCall(req).execute().use { r ->
                val items = JSONObject(r.body?.string() ?: "{}").optJSONObject("artists")?.optJSONArray("items")
                val names = (0 until (items?.length() ?: 0)).map { items!!.getJSONObject(it).getString("name") }
                names.maxByOrNull { similarity(norm(it), norm(name)) }?.takeIf { similarity(norm(it), norm(name)) >= 0.6 } ?: name
            }
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        name
    }

    /** Songs by one artist (including features), shuffled across their catalogue. */
    suspend fun artistTracks(artist: String): List<Track> {
        val a = norm(artist)
        // All four pages at once instead of one after another
        val pages = kotlinx.coroutines.coroutineScope {
            listOf(0, 10, 20, 30).map { off ->
                async { runCatching { search("artist:\"$artist\"", 10, off) }.getOrDefault(emptyList()) }
            }.map { it.await() }
        }
        val out = pages.flatten().filter { t -> t.artist.split(", ").any { norm(it) == a } }
        return preferExplicit(out.distinctBy { it.id }).shuffled()
    }

    /** "Song (Clean)" / "Song - Radio Edit" count as the same song as "Song". */
    private fun baseTitle(t: String) = norm(t.lowercase()
        .replace(Regex("\\s*[\\(\\[-].*(clean|radio edit|edited|explicit).*$"), ""))
    private fun sameSong(a: String, b: String) = baseTitle(a) == baseTitle(b)

    /** Where both versions of a song are in a list, keep only the explicit one. */
    fun preferExplicit(list: List<Track>): List<Track> =
        list.groupBy { norm(it.artist.substringBefore(", ")) + "|" + baseTitle(it.title) }
            .values.map { g -> g.firstOrNull { it.explicit } ?: g.first() }

    fun isBy(t: Track, artist: String) = t.artist.split(", ").any { norm(it) == norm(artist) }

    /** Finds the Spotify track for a song the DJ picked, or null if it isn't on Spotify. */
    suspend fun find(artist: String, title: String): Track? = try {
        var rs = search("track:$title artist:$artist", 10)
        if (rs.isEmpty()) rs = search("$artist $title", 10)
        val a = norm(artist).take(6)
        val matches = rs.filter { norm(it.artist).contains(a) }
        // Same song, explicit version first (Spotify often lists the clean edit separately)
        val first = matches.firstOrNull()
        first?.let { f -> matches.firstOrNull { it.explicit && sameSong(it.title, f.title) } } ?: first
    } catch (e: Exception) {
        null
    }
}
