package com.echoradio.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * The DJ's speaking voice: Gemini only. If there's no key or Gemini can't make a line,
 * the DJ stays quiet for that line (it still shows as text). There is no phone voice.
 */
class DjVoice(context: Context, private val prefs: Prefs) {
    /** Built-in voice used when Gemini's voice is off, resting or failing. */
    private val offline = OfflineVoice(context)
    var enabled = true
    /** Called with a reason the first time the Gemini voice fails. */
    var onGeminiFailed: ((String) -> Unit)? = null
    private var reportedFailure = false
    @Volatile private var track: AudioTrack? = null

    /** Gemini said "too many requests": the voice rests until [until] (epoch ms). */
    class RateLimited(val until: Long) : Exception("free Gemini voice limit reached")

    private var lastRestNotice = 0L

    /** Speaks [text] and returns when it's finished (or straight away if the voice is off or resting). */
    suspend fun speak(text: String) {
        if (!enabled || text.isBlank()) { delay(600); return }
        if (prefs.voice == OFFLINE_VOICE || prefs.geminiKey.isEmpty()) { speakOffline(text); return }
        val resting = restingUntil()
        if (resting != null) { noticeResting(resting); speakOffline(text); return }
        val job = cache.remove(text) ?: scope.async { synthesize(text) }
        val startedAt = System.currentTimeMillis()
        fun left() = WAIT_MS - (System.currentTimeMillis() - startedAt)
        suspend fun attempt(block: suspend () -> Pair<ByteArray, Int>, ms: Long): Result<Pair<ByteArray, Int>> = try {
            Result.success(withTimeout(ms) { block() })
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Result.failure(Exception("it took too long"))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e   // the DJ was interrupted (skip / new request): not a voice failure
        } catch (e: Exception) {
            Result.failure(e)
        }
        var res = attempt({ job.await() }, WAIT_MS)
        var err = res.exceptionOrNull()
        // One quick retry for a passing error, never for a rate limit or a timeout
        if (err != null && err !is RateLimited && err.message != "it took too long" && left() > 2000) {
            res = attempt({ synthesize(text) }, left()); err = res.exceptionOrNull()
        }
        // The chosen library voice (e.g. Pegasus) couldn't be found: use Puck for this line
        if (err != null && err !is RateLimited && prefs.voice in LIBRARY_VOICES && left() > 2000) {
            val backup = attempt({ synthesizeWith(text, "Puck") }, left())
            if (backup.isSuccess) { onGeminiFailed?.invoke("${err.message}; using Puck instead"); res = backup; err = null }
        }
        res.getOrNull()?.let { play(it.first, it.second); return }
        if (err is RateLimited) noticeResting(err.until)
        else onGeminiFailed?.invoke("Gemini voice unavailable (${err?.message ?: "unknown error"}), using the built-in voice")
        speakOffline(text)
    }

    /** The on-phone voice: always available, no internet needed. */
    private suspend fun speakOffline(text: String) {
        val audio = runCatching { offline.synthesize(text) }.getOrNull()
        if (audio != null && audio.first.isNotEmpty()) play(audio.first, audio.second) else delay(400)
    }

    private fun noticeResting(until: Long) {
        if (until == lastRestNotice) return          // say it once per resting period
        lastRestNotice = until
        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(until))
        onGeminiFailed?.invoke("Gemini voice limit reached until $time, using the built-in voice")
    }

    // ---- rate limits: each voice model has its own free quota ----
    private val blockedUntil = ConcurrentHashMap<String, Long>()

    private fun availableModels(): List<String> {
        val now = System.currentTimeMillis()
        return Prefs.TTS_MODELS.filter { (blockedUntil[it] ?: 0L) <= now }
    }

    /** When every voice model is rate-limited, the time the first one frees up; otherwise null. */
    fun restingUntil(): Long? = if (availableModels().isEmpty()) Prefs.TTS_MODELS.mapNotNull { blockedUntil[it] }.minOrNull() else null

    private fun parseRetry(raw: String): Long {
        val now = System.currentTimeMillis()
        if (raw.contains("PerDay", true) || raw.contains("per day", true)) {
            // Daily quotas reset at midnight Pacific time
            val la = java.time.ZoneId.of("America/Los_Angeles")
            return java.time.ZonedDateTime.now(la).toLocalDate().plusDays(1).atStartOfDay(la).toInstant().toEpochMilli() + 60_000
        }
        val secs = Regex("\"retryDelay\"\\s*:\\s*\"(\\d+(?:\\.\\d+)?)s\"").find(raw)?.groupValues?.get(1)?.toDoubleOrNull()
            ?: Regex("retry in (\\d+(?:\\.\\d+)?)\\s*s", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)?.toDoubleOrNull()
            ?: 60.0
        return now + (secs * 1000).toLong() + 2_000
    }

    /** Asks Gemini's speech model (Interactions API) for audio. Returns 16-bit PCM and its sample rate. */
    private suspend fun synthesize(text: String): Pair<ByteArray, Int> = synthesizeWith(text, null)

    private suspend fun synthesizeWith(text: String, forceVoice: String?): Pair<ByteArray, Int> {
        for (model in availableModels()) {
            try {
                return requestTts(model, text, forceVoice)
            } catch (e: RateLimited) {
                blockedUntil[model] = e.until                    // this model is used up; try the other one
            }
        }
        throw RateLimited(restingUntil() ?: (System.currentTimeMillis() + 60_000))
    }

    private suspend fun requestTts(model: String, text: String, forceVoice: String?): Pair<ByteArray, Int> = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("model", model)
            .put("input", JSONArray().put(JSONObject()
                .put("type", "user_input")
                .put("content", JSONArray().put(JSONObject()
                    .put("type", "text")
                    .put("text", text)
                    .put("annotations", JSONArray().put(JSONObject()
                        .put("type", "speech_metadata")
                        .put("style", "relaxed, upbeat late-night radio DJ, warm and conversational")))))))
            .put("response_format", JSONObject()
                .put("type", "audio").put("mime_type", "audio/l16").put("sample_rate", 24000))
            .put("generation_config", JSONObject()
                .put("speech_config", JSONArray().put(JSONObject().put("voice", forceVoice ?: voiceId()))))
            .toString()
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/interactions")
            .header("x-goog-api-key", prefs.geminiKey)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        Http.client.newCall(req).execute().use { r ->
            val raw = r.body?.string() ?: ""
            if (r.code == 429) throw RateLimited(parseRetry(raw))
            if (!r.isSuccessful) {
                val msg = runCatching { JSONObject(raw).getJSONObject("error").getString("message") }.getOrNull()
                throw Exception("Gemini voice returned ${r.code}" + (msg?.let { ": $it" } ?: ""))
            }
            val steps = JSONObject(raw).optJSONArray("steps") ?: throw Exception("No audio in Gemini reply")
            var data: String? = null
            var mime = ""
            for (i in 0 until steps.length()) {
                val content = steps.getJSONObject(i).optJSONArray("content") ?: continue
                for (j in 0 until content.length()) {
                    val c = content.getJSONObject(j)
                    if (c.optString("type") == "audio" && c.has("data")) { data = c.getString("data"); mime = c.optString("mime_type") }
                }
            }
            val bytes = Base64.decode(data ?: throw Exception("No audio in Gemini reply"), Base64.DEFAULT)
            val rate = Regex("rate=(\\d+)").find(mime)?.groupValues?.get(1)?.toIntOrNull() ?: 24000
            stripWav(bytes, rate)
        }
    }

    /** Built-in voices are used by name; library voices (like Pegasus) are looked up once and remembered. */
    private fun voiceId(): String {
        val name = prefs.voice
        if (name !in LIBRARY_VOICES) return name
        prefs.voiceIdFor(name)?.let { return it }
        val url = "https://generativelanguage.googleapis.com/v1beta/voices?search=" + java.net.URLEncoder.encode(name, "UTF-8")
        val req = Request.Builder().url(url).header("x-goog-api-key", prefs.geminiKey).build()
        val id = Http.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw Exception("couldn't search Gemini's voice library (${r.code})")
            val root = JSONObject(r.body?.string() ?: "{}")
            val arr = root.optJSONArray("voices") ?: root.optJSONArray("items") ?: JSONArray()
            val all = (0 until arr.length()).map { arr.getJSONObject(it) }
            fun label(o: JSONObject) = listOf("display_name", "displayName", "name", "id").joinToString(" ") { o.optString(it) }
            (all.firstOrNull { o -> listOf("display_name", "displayName", "name").any { o.optString(it).equals(name, true) } }
                ?: all.firstOrNull { label(it).contains(name, true) })
                ?.let { o -> o.optString("id").ifEmpty { o.optString("name") } }
        } ?: throw Exception("Gemini's voice library has no voice called $name")
        prefs.setVoiceIdFor(name, id)
        return id
    }

    /** If the audio came back as a WAV file, drop the header and read its sample rate. */
    private fun stripWav(b: ByteArray, fallbackRate: Int): Pair<ByteArray, Int> {
        if (b.size < 44 || String(b, 0, 4, Charsets.US_ASCII) != "RIFF") return b to fallbackRate
        val rate = (b[24].toInt() and 0xFF) or ((b[25].toInt() and 0xFF) shl 8) or
            ((b[26].toInt() and 0xFF) shl 16) or ((b[27].toInt() and 0xFF) shl 24)
        var i = 12
        while (i + 8 <= b.size) {
            val id = String(b, i, 4, Charsets.US_ASCII)
            val len = (b[i + 4].toInt() and 0xFF) or ((b[i + 5].toInt() and 0xFF) shl 8) or
                ((b[i + 6].toInt() and 0xFF) shl 16) or ((b[i + 7].toInt() and 0xFF) shl 24)
            if (id == "data") return b.copyOfRange(i + 8, minOf(b.size, i + 8 + len)) to rate
            i += 8 + len
        }
        return b.copyOfRange(44, b.size) to rate
    }

    private suspend fun play(pcm: ByteArray, rate: Int) {
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size)
            .build()
        track = t
        playingPcm = pcm
        playingRate = rate
        try {
            t.write(pcm, 0, pcm.size)
            t.play()
            val ms = pcm.size / 2 * 1000L / rate
            delay(ms + 150)
        } finally {
            runCatching { t.stop() }
            t.release()
            if (track === t) { track = null; playingPcm = null }
        }
    }

    @Volatile private var playingPcm: ByteArray? = null
    @Volatile private var playingRate = 24000

    /** Loudness of the voice right now (0..1), for the talking animation. */
    fun level(): Float {
        val t = track ?: return 0f
        val pcm = playingPcm ?: return 0f
        val head = runCatching { t.playbackHeadPosition }.getOrDefault(0)
        val window = playingRate / 25                      // 40 ms of audio
        val start = head.coerceAtLeast(0)
        val end = minOf(start + window, pcm.size / 2)
        if (end <= start) return 0f
        var sum = 0.0
        for (i in start until end) {
            val v = ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xFF)).toShort().toDouble()
            sum += v * v
        }
        val rms = kotlin.math.sqrt(sum / (end - start))
        return (rms / 6000.0).toFloat().coerceIn(0f, 1f)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = ConcurrentHashMap<String, Deferred<Pair<ByteArray, Int>>>()

    /** Starts making the audio for a line early so it's ready when the DJ needs it. */
    fun prefetch(text: String) {
        if (!enabled || text.isBlank() || prefs.geminiKey.isEmpty() || prefs.voice == OFFLINE_VOICE || restingUntil() != null) return
        if (cache.size > 8) cache.clear()
        cache.getOrPut(text) { scope.async { synthesize(text) } }
    }

    /** Lets a new key or voice be tried again after a failure. */
    fun resetFailure() { reportedFailure = false }

    fun stop() {
        track?.let { runCatching { it.pause(); it.flush() } }
    }

    fun shutdown() { stop(); scope.cancel() }

    companion object {
        /** Gemini voices offered in Settings (name to description). */
        /** Longest the DJ waits for its voice before letting the song start (retries included). */
        const val WAIT_MS = 10_000L

        /** Voices from Gemini's Extended Voice Library (looked up by name at first use). */
        val LIBRARY_VOICES = emptySet<String>()

        /** The built-in, on-phone voice. */
        const val OFFLINE_VOICE = "Ryan"

        val VOICES = listOf(
            "Puck" to "Gemini voice, falls back to Ryan when needed",
            OFFLINE_VOICE to "built in, works offline, no limits",
        )
    }
}
