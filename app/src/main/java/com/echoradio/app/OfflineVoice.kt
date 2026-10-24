package com.echoradio.app

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A natural-sounding voice that runs entirely on the phone (Piper "Ryan" voice through sherpa-onnx).
 * No internet, no API key, no limits. Loads in the background when the app starts.
 */
class OfflineVoice(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engine: Deferred<OfflineTts?> = scope.async { runCatching { load() }.getOrNull() }

    private fun load(): OfflineTts {
        val dataDir = copyEspeakData()
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = "tts/en_US-ryan-medium.onnx",
                    tokens = "tts/tokens.txt",
                    dataDir = dataDir,
                    lengthScale = 0.95f,       // a touch quicker, more like radio
                ),
                numThreads = 2,
            ),
        )
        return OfflineTts(assetManager = app.assets, config = config)
    }

    /** The pronunciation data must be real files, so it's copied out of the app once. */
    private fun copyEspeakData(): String {
        val target = File(app.filesDir, "espeak-ng-data")
        val done = File(target, ".copied-v2")
        if (done.exists()) return target.absolutePath
        target.deleteRecursively()
        fun copy(assetPath: String, out: File) {
            val children = app.assets.list(assetPath).orEmpty()
            if (children.isEmpty()) {
                out.parentFile?.mkdirs()
                app.assets.open(assetPath).use { input -> out.outputStream().use { input.copyTo(it) } }
            } else {
                out.mkdirs()
                for (c in children) copy("$assetPath/$c", File(out, c))
            }
        }
        copy("tts/espeak-ng-data", target)
        done.createNewFile()
        return target.absolutePath
    }

    /** Speaks [text] into 16-bit PCM. Returns the audio and its sample rate, or null if unavailable. */
    suspend fun synthesize(text: String): Pair<ByteArray, Int>? = withContext(Dispatchers.Default) {
        val tts = engine.await() ?: return@withContext null
        val audio = synchronized(tts) { tts.generate(text = text, sid = 0, speed = 1.0f) }
        val samples = audio.samples
        val pcm = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
            pcm[2 * i] = (v and 0xFF).toByte()
            pcm[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        pcm to audio.sampleRate
    }
}
