package com.echoradio.app

import android.content.Intent
import android.Manifest
import android.app.AlarmManager
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.speech.RecognizerIntent
import android.widget.PopupMenu
import androidx.activity.result.contract.ActivityResultContracts
import coil.imageLoader
import coil.request.ImageRequest
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.ArrayAdapter
import androidx.activity.OnBackPressedCallback
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import coil.load
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.types.PlayerState
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var web: SpotifyWeb
    private lateinit var brain: DjBrain
    private lateinit var voice: DjVoice
    private var remote: SpotifyAppRemote? = null

    // Radio state
    private val queue = mutableListOf<Track>()
    private val history = mutableListOf<Track>()
    private var current: Track? = null
    private var vibe = ""
    private var started = false
    private var filling = false
    private var waitingForTracks = false
    private var transitioning = false
    private var lastPos = 0L
    private var duration = 0L
    private var paused = true
    private var playJob: Job? = null

    // Views
    private lateinit var djName: TextView
    private lateinit var lamp: TextView
    private lateinit var status: TextView
    private lateinit var art: ImageView
    private lateinit var npTitle: TextView
    private lateinit var npArtist: TextView
    private lateinit var progress: SeekBar
    private lateinit var timeNow: TextView
    private lateinit var timeEnd: TextView
    private lateinit var djLine: TextView
    private lateinit var btnMain: MaterialButton
    private lateinit var btnPlay: ImageButton
    private lateinit var btnSkip: ImageButton
    private lateinit var btnPrev: ImageButton
    private lateinit var btnVoice: ImageButton
    private lateinit var chatSheet: View
    private lateinit var scrim: View
    private lateinit var chatScroll: ScrollView
    private var seeking = false
    private lateinit var talkView: DjTalkView
    private lateinit var btnLike: ImageButton
    private var likedCurrent = false
    private var sleepJob: Job? = null
    private var aboutJob: Job? = null
    private var pendingAlarm = false
    private var talking = false
    /** URI of the Spotify track shown while the DJ isn't driving (after reopening, or your own pick). */
    private var spotifyShownUri: String? = null
    /** What Gemini understood the current request to be: "song", "artist", "vibe" or "" (unknown). */
    private var requestType = ""
    private var requestArtist: String? = null
    private var theme = AlbumColors.DEFAULT
    private var themeAnim: android.animation.ValueAnimator? = null

    /** Voice requests: Android's own speech recognition screen. */
    private val speechLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val heard = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!heard.isNullOrBlank()) { vibeInput.setText(heard); sendVibe() }
    }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private var taste: Taste? = null
    private var tasteAt = 0L
    private var quickSkips = 0
    private val skipped = mutableListOf<Track>()
    private var switchFrom: String? = null
    private var songsSinceTalk = 0
    private var talkGap = 3
    private lateinit var log: LinearLayout
    private lateinit var queueView: LinearLayout
    private lateinit var queueEmpty: TextView
    private lateinit var vibeInput: EditText
    private lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        web = SpotifyWeb(prefs)
        brain = DjBrain(prefs)
        voice = DjVoice(this, prefs)
        voice.onGeminiFailed = { why ->
            runOnUiThread { say(Kind.NOTE, why) }
        }
        bindViews()
        talkView.levelProvider = { voice.level() }
        setupInsets()

        djName.text = prefs.djName
        updateMainButton()
        say(Kind.DJ, "Hey, I'm ${prefs.djName}. Connect your Spotify, tell me what you're feeling, and I'll run the set.")
        if (prefs.geminiKey.isEmpty()) djLine.text = "Hey, I'm ${prefs.djName}. Add a Gemini API key in Settings (⋮) so I can pick songs and talk to you."
        NowPlaying.onCommand = { cmd -> runOnUiThread { handleCommand(cmd) } }
        handleIntent(intent)
        handleAlarm(intent)
        handleWidget(intent)
        startProgressTicker()
    }

    override fun onStart() {
        super.onStart()
        if (web.isLoggedIn && prefs.clientId.isNotEmpty()) connectRemote()
    }

    override fun onDestroy() {
        NowPlaying.onCommand = null
        PlaybackService.stop(this)
        remote?.let { SpotifyAppRemote.disconnect(it) }
        remote = null
        voice.shutdown()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
        handleAlarm(intent)
        handleWidget(intent)
    }

    /** Spotify login comes back as echoradio://callback?code=… */
    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "echoradio") return
        data.getQueryParameter("error")?.let { say(Kind.NOTE, "Spotify login was cancelled."); return }
        val code = data.getQueryParameter("code") ?: return
        setIntent(Intent(this, MainActivity::class.java))
        lifecycleScope.launch {
            try {
                web.exchangeCode(code)
                say(Kind.NOTE, "Logged in to Spotify.")
                taste = null
                updateMainButton()
                lifecycleScope.launch { loadTaste() }
                connectRemote()
            } catch (e: Exception) {
                say(Kind.NOTE, e.message ?: "Spotify login failed.")
            }
        }
    }

    // ---------- Spotify app (playback) ----------

    private fun connectRemote() {
        if (remote?.isConnected == true) return
        setStatus("Connecting to Spotify app…")
        val params = ConnectionParams.Builder(prefs.clientId)
            .setRedirectUri(SpotifyWeb.REDIRECT_URI)
            .showAuthView(true)
            .build()
        SpotifyAppRemote.connect(this, params, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                remote = appRemote
                setStatus("Connected to Spotify")
                if (pendingAlarm) { pendingAlarm = false; startAlarmSet() }
                appRemote.playerApi.subscribeToPlayerState().setEventCallback { st ->
                    runOnUiThread { onPlayerState(st) }
                }
                updateMainButton()
            }

            override fun onFailure(error: Throwable) {
                remote = null
                setStatus("Not connected to Spotify")
                say(Kind.NOTE, "Couldn't reach the Spotify app: ${error.message ?: error.javaClass.simpleName}. " +
                    "Make sure Spotify is installed and you're logged in.")
                updateMainButton()
            }
        })
    }

    private fun onPlayerState(st: PlayerState) {
        val playing = st.track
        if (paused != st.isPaused && (started || spotifyShownUri != null)) { NowPlaying.playing = !st.isPaused; PlaybackService.update(this) }
        paused = st.isPaused
        val cur = current
        if (cur == null && playing != null && !transitioning && playJob?.isActive != true) {
            // Nothing from the DJ is playing: mirror whatever Spotify is doing
            showSpotifyTrack(playing, st.playbackPosition)
            updateMainButton()
            return
        }
        if (playing != null && cur != null && playing.uri == cur.uri) {
            lastPos = st.playbackPosition
            duration = playing.duration
        }
        updateMainButton()
        if (transitioning || cur == null || playing == null) return
        val songEnded = st.isPaused && st.playbackPosition == 0L && lastPos > duration - 4000
        if (playing.uri != cur.uri) {
            // Our song finished and Spotify moved on by itself: carry on with the DJ's next pick.
            // Anything else (you chose a song in Spotify): leave it playing and don't override it.
            if (duration > 0 && lastPos >= duration - 6000) advance() else followSpotify(playing)
            return
        }
        if (songEnded) advance()
    }

    /** You picked something in Spotify: show it, and hand back to the DJ only when you ask. */
    private fun followSpotify(t: com.spotify.protocol.types.Track) {
        playJob?.cancel(); voice.stop()
        current = null
        showSpotifyTrack(t, 0)
        setStatus("Playing your pick from Spotify. Tap the DJ button to jump back in")
        updateMainButton()
    }

    /** Shows a track Spotify is playing on its own: cover, title, progress, colours, widget and lock screen. */
    private fun showSpotifyTrack(t: com.spotify.protocol.types.Track, position: Long) {
        lastPos = position
        duration = t.duration
        if (spotifyShownUri == t.uri) return
        spotifyShownUri = t.uri
        npTitle.text = t.name
        npArtist.text = listOfNotNull(t.artist?.name, t.album?.name).filter { it.isNotBlank() }.joinToString(" · ")
        if (!started) { setStatus("Playing on Spotify. Tap the DJ button for a set"); queueEmpty.text = "Tap the DJ button and I'll take it from here." }
        setLiked(false)
        remote?.userApi?.getLibraryState(t.uri)?.setResultCallback { st -> runOnUiThread { if (spotifyShownUri == t.uri) setLiked(st.isAdded) } }
        NowPlaying.title = t.name; NowPlaying.artist = t.artist?.name ?: ""; NowPlaying.playing = !paused; NowPlaying.art = null
        startBackground()
        t.imageUri?.let { img ->
            remote?.imagesApi?.getImage(img, com.spotify.protocol.types.Image.Dimension.LARGE)?.setResultCallback { bmp ->
                runOnUiThread {
                    if (spotifyShownUri != t.uri || bmp == null) return@runOnUiThread
                    art.setImageBitmap(bmp)
                    NowPlaying.art = bmp
                    PlaybackService.update(this)
                    applyTheme(AlbumColors.extract(bmp))
                }
            }
        }
    }

    private fun startProgressTicker() = lifecycleScope.launch {
        while (isActive) {
            delay(1000)
            if (!paused && !transitioning && (current != null || spotifyShownUri != null)) lastPos += 1000
            if (lastPos > 60_000 && quickSkips > 0) quickSkips = 0   // they're enjoying this one
            if (!seeking) progress.progress = if (duration > 0) (lastPos * 1000 / duration).toInt().coerceIn(0, 1000) else 0
            timeNow.text = fmt(lastPos.coerceAtMost(duration))
            timeEnd.text = fmt(duration)
        }
    }

    // ---------- building the set ----------

    private suspend fun fill(more: Boolean): String {
        if (filling) return ""
        filling = true
        renderQueue()
        var intro = ""
        try {
            // Never queue anything played recently, including in earlier sessions
            val seen = ((history + queue).map { it.id } + prefs.playedIds + prefs.dislikedIds).toMutableSet()
            val madeForYou = switchFrom == null && isMadeForYou(vibe)
            val myTaste = loadTaste()
            val artist = when {
                madeForYou || switchFrom != null || vibe.contains(':') || vibe.startsWith(DjBrain.SIMILAR_TO) -> null
                requestType == "artist" -> requestArtist               // Gemini named the artist
                requestType == "vibe" -> null                          // Gemini says it's a general vibe
                else -> web.detectArtist(vibe)                         // no Gemini answer: best guess
            }
            val before = queue.size
            // Artist request, first batch: go straight to Spotify so music starts fast.
            // Gemini plans the later batches in the background while you listen.
            val quickArtistStart = artist != null && !more
            if (prefs.geminiKey.isNotEmpty() && !quickArtistStart) {
                try {
                    val plan = withTimeout(if (more) 30_000 else 16_000) { brain.plan(vibe, more, history, queue, artist, myTaste, madeForYou, switchFrom, skipped, greet = firstSetToday()) }
                    if (switchFrom != null && plan.category.isNotBlank()) {
                        vibe = plan.category          // new direction sticks for later refills
                        setStatus("Now playing: ${plan.category}")
                    }
                    var extras = 0
                    intro = plan.intro
                    if (!more && intro.isNotBlank()) voice.prefetch(intro)
                    // Look all the songs up on Spotify at the same time
                    val found = coroutineScope { plan.picks.map { p -> async { p to web.find(p.artist, p.title) } }.awaitAll() }
                    for ((p, tFound) in found) {
                        val t = tFound ?: continue
                        if (artist != null && !web.isBy(t, artist)) {          // stick to the asked-for artist…
                            if (!more || extras >= 2) continue                  // …except a couple of switch-ups later on
                            extras++
                        }
                        if (seen.add(t.id)) queue.add(t.copy(line = p.line.ifBlank { DjBrain.simpleLine(t) }))
                        renderQueue()
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                    say(Kind.NOTE, "The DJ took too long to think, so I'm starting with a quick search.")
                } catch (e: java.io.InterruptedIOException) {
                    say(Kind.NOTE, "The DJ took too long to think, so I'm starting with a quick search.")
                } catch (e: Exception) {
                    say(Kind.NOTE, "${e.message}. Using simple search for now.")
                }
            }
            if (artist != null && queue.size - before < 4) {
                // Top up straight from the artist's catalogue
                web.artistTracks(artist).filter { seen.add(it.id) }.take(8 - (queue.size - before).coerceAtLeast(0))
                    .forEach { queue.add(it.copy(line = DjBrain.simpleLine(it))) }
                if (!more && intro.isBlank()) intro = listOf("You asked for $artist, so let's go.", "$artist, coming right up.",
                    "Alright, a $artist set. Here we go.").random()
            }
            if (madeForYou && myTaste != null && queue.size - before < 4) {
                // No DJ picks available: play from the songs they love
                myTaste.favourites.shuffled().filter { seen.add(it.id) }.take(6)
                    .forEach { queue.add(it.copy(line = DjBrain.simpleLine(it))) }
                if (!more && intro.isBlank()) intro = "Here's a mix made just for you."
            }
            if (queue.size < 2) {
                val term = if (vibe.startsWith(DjBrain.SIMILAR_TO)) vibe.substringAfterLast(" by ").trim()
                    else if (madeForYou) "${myTaste?.genres?.firstOrNull() ?: "pop"}" else if (vibe.contains("surprise", true))
                    ((taste?.genres.orEmpty()) + listOf("afrobeats", "soul", "indie pop", "deep house", "hip hop", "jazz",
                        "amapiano", "r&b", "dancehall", "neo soul", "alternative", "gqom", "trap", "afro house")).random() else vibe
                val off = (0..8).random() * 10
                val results = web.preferExplicit(web.search(term, 10, off) + web.search(term, 10, off + 10))
                results.shuffled().filter { seen.add(it.id) }.take(8)
                    .forEach { queue.add(it.copy(line = DjBrain.simpleLine(it))) }
                if (!more && intro.isBlank()) intro = "Alright, ${vibe.lowercase()} coming right up."
            }
            if (queue.isEmpty()) say(Kind.NOTE, "Nothing matched that. Try an artist, genre or mood.")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            say(Kind.NOTE, e.message ?: "Something went wrong building the set.")
        } finally {
            filling = false
            renderQueue()
        }
        return intro
    }

    private fun startSet(newVibe: String) {
        if (remote?.isConnected != true) {
            say(Kind.NOTE, "Connect Spotify first.")
            return
        }
        vibe = newVibe
        quickSkips = 0
        skipped.clear()
        queue.clear()
        renderQueue()
        playJob?.cancel()
        voice.stop()
        playJob = lifecycleScope.launch {
            remote?.playerApi?.pause()
            // Work out what was asked for: a song, an artist or a vibe
            requestType = ""; requestArtist = null
            val understood = if (prefs.geminiKey.isNotEmpty() && !isMadeForYou(newVibe) && !newVibe.contains(':'))
                runCatching { withTimeout(7_000) { brain.understand(newVibe) } }.getOrNull() else null
            if (understood != null) requestType = understood.type
            if (understood?.type == "artist" && understood.artist.isNotBlank()) requestArtist = web.resolveArtist(understood.artist)
            // A specific song: play it right away, then more in the same vibe
            val song = when {
                understood?.type == "song" && understood.title.isNotBlank() -> web.findSong(understood.title, understood.artist)
                understood == null -> web.detectSong(newVibe)
                else -> null
            }
            if (song != null) {
                vibe = "${DjBrain.SIMILAR_TO} \"${song.title}\" by ${song.artist.substringBefore(", ")}"
                queue.add(song.copy(line = ""))
                started = true
                updateMainButton()
                startBackground()
                setStatus("Your request, then more like it")
                songsSinceTalk = 0
                talkGap = 3
                talk(listOf("You got it. ${song.title}.", "Coming right up: ${song.title}.",
                    "${song.title} by ${song.artist.substringBefore(", ")}. Good choice.").random())
                playNext()              // more songs in this vibe are found in the background
                return@launch
            }
            val wait = say(Kind.NOTE, "The DJ is digging through the crates…")
            val intro = fill(more = false)
            if (status.text == "The DJ is digging through the crates…") setStatus("Connected to Spotify")
            started = true
            updateMainButton()
            startBackground()
            songsSinceTalk = 0
            talkGap = 3
            if (recapDue()) playRecap()
            if (intro.isNotBlank()) talk(intro) else songsSinceTalk = talkGap   // no intro: introduce the first song
            playNext()
        }
    }

    // ---------- playback: the DJ talks between songs ----------

    private fun advance() {
        playJob?.cancel()
        voice.stop()
        playJob = lifecycleScope.launch { playNext() }
    }

    private suspend fun playNext() {
        transitioning = true
        remote?.playerApi?.pause()
        if (queue.size < 3 && vibe.isNotEmpty()) lifecycleScope.launch { fill(more = true) }
        val next = queue.removeFirstOrNull()
        if (next == null) {
            if (filling) waitingForTracks = true else { current = null; transitioning = false }
            updateMainButton()
            return
        }
        current = next
        history.add(next)
        prefs.rememberPlayed(next)
        lastPos = 0
        duration = next.durationMs
        renderNow()
        renderQueue()
        // The DJ only chimes in every few songs, like real radio
        songsSinceTalk++
        if (songsSinceTalk >= talkGap && next.line.isNotBlank()) {
            talk(next.line)
            songsSinceTalk = 0
            talkGap = (3..4).random()
        }
        // Get the voice for the next line ready in the background
        queue.firstOrNull()?.let { if (songsSinceTalk + 1 >= talkGap) voice.prefetch(it.line) }
        remote?.playerApi?.play(next.uri)?.setErrorCallback {
            runOnUiThread { say(Kind.NOTE, "Couldn't play ${next.title}. Skipping."); advance() }
        }
        delay(2500) // let Spotify switch tracks before we start watching for the end
        transitioning = false
    }

    private suspend fun talk(text: String) {
        say(Kind.DJ, text)
        talking = true
        NowPlaying.djLine = text
        if (started) PlaybackService.update(this)
        lamp.setBackgroundResource(R.drawable.lamp_on)
        lamp.setTextColor(ContextCompat.getColor(this, R.color.dj_deep))
        lamp.text = "ON AIR"
        showTalking(true)
        try {
            voice.speak(text)
        } finally {
            lamp.setBackgroundResource(R.drawable.lamp_off)
            lamp.setTextColor(ContextCompat.getColor(this, R.color.fg))
            lamp.text = "LIVE"
            showTalking(false)
            talking = false
        }
    }

    // ---------- UI ----------

    private fun bindViews() {
        djName = findViewById(R.id.djName)
        lamp = findViewById(R.id.lamp)
        status = findViewById(R.id.status)
        djLine = findViewById(R.id.djLine)
        art = findViewById(R.id.art)
        talkView = findViewById(R.id.talkView)
        npTitle = findViewById(R.id.npTitle)
        npArtist = findViewById(R.id.npArtist)
        progress = findViewById(R.id.progress)
        timeNow = findViewById(R.id.timeNow)
        timeEnd = findViewById(R.id.timeEnd)
        btnMain = findViewById(R.id.btnMain)
        btnPlay = findViewById(R.id.btnPlay)
        btnSkip = findViewById(R.id.btnSkip)
        btnPrev = findViewById(R.id.btnPrev)
        btnVoice = findViewById(R.id.btnVoice)
        log = findViewById(R.id.log)
        queueView = findViewById(R.id.queue)
        queueEmpty = findViewById(R.id.queueEmpty)
        vibeInput = findViewById(R.id.vibeInput)
        scroll = findViewById(R.id.scroll)
        chatSheet = findViewById(R.id.chatSheet)
        scrim = findViewById(R.id.scrim)
        chatScroll = findViewById(R.id.chatScroll)

        btnMain.setOnClickListener { onMainButton(fromMain = true) }
        btnPlay.setOnClickListener { onMainButton() }
        btnSkip.setOnClickListener { onSkip() }
        btnPrev.setOnClickListener { previous() }
        btnVoice.setOnClickListener {
            voice.enabled = !voice.enabled
            if (!voice.enabled) voice.stop()
            btnVoice.setImageResource(if (voice.enabled) R.drawable.ic_voice_on else R.drawable.ic_voice_off)
            toastNote(if (voice.enabled) "DJ voice on" else "DJ voice off")
        }
        findViewById<View>(R.id.btnDj).setOnClickListener { openChat() }
        findViewById<View>(R.id.btnDj).setOnLongClickListener { listen(); true }
        findViewById<View>(R.id.btnMic).setOnClickListener { listen() }
        btnLike = findViewById(R.id.btnLike)
        btnLike.setOnClickListener { toggleLike() }
        findViewById<View>(R.id.btnDislike).setOnClickListener { neverAgain() }
        findViewById<View>(R.id.btnShare).setOnClickListener { share() }
        art.setOnClickListener { askAboutSong() }
        findViewById<View>(R.id.btnCloseChat).setOnClickListener { closeChat() }
        scrim.setOnClickListener { closeChat() }
        findViewById<View>(R.id.btnSend).setOnClickListener { sendVibe() }
        vibeInput.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEND) { sendVibe(); true } else false
        }
        findViewById<View>(R.id.btnSettings).setOnClickListener { showMenu(it) }

        progress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) timeNow.text = fmt(duration * value / 1000)
            }
            override fun onStartTrackingTouch(sb: SeekBar) { seeking = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                seeking = false
                if (current != null && duration > 0) {
                    lastPos = duration * sb.progress / 1000
                    remote?.playerApi?.seekTo(lastPos)
                }
            }
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (chatSheet.visibility == View.VISIBLE) closeChat()
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })

        val chips = findViewById<ChipGroup>(R.id.chips)
        (listOf(MADE_FOR_YOU) + todaysShows() + listOf("Surprise me")).distinct().forEach { label ->
            chips.addView(Chip(this).apply {
                text = label
                setOnClickListener { vibeInput.setText(SHOWS[label] ?: label); sendVibe() }
            })
        }
    }

    // ---------- colours from the album cover ----------

    private fun paintTheme(t: AlbumTheme) {
        val r = dp(12).toFloat()
        findViewById<View>(R.id.hero).background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(t.card, t.cardEnd)).apply { cornerRadius = r }
        val bg = ContextCompat.getColor(this, R.color.bg)
        val mid = androidx.core.graphics.ColorUtils.blendARGB(t.page, bg, 0.55f)
        scroll.background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(t.page, mid, bg))
        findViewById<View>(R.id.root).setBackgroundColor(t.page)
    }

    /** Blends the screen's colours from the current theme to [to] over about half a second. */
    private fun applyTheme(to: AlbumTheme) {
        NowPlaying.color = to.page
        EchoWidget.update(this)
        val from = theme
        if (from == to) return
        themeAnim?.cancel()
        val ev = android.animation.ArgbEvaluator()
        themeAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700
            addUpdateListener { a ->
                val f = a.animatedFraction
                val now = AlbumTheme(
                    ev.evaluate(f, from.card, to.card) as Int,
                    ev.evaluate(f, from.cardEnd, to.cardEnd) as Int,
                    ev.evaluate(f, from.page, to.page) as Int)
                theme = now
                paintTheme(now)
            }
            start()
        }
    }

    /** True only for the first set of the day (and marks the day as greeted). */
    private fun firstSetToday(): Boolean {
        val today = java.time.LocalDate.now().toString()
        if (prefs.lastGreetDay == today) return false
        prefs.lastGreetDay = today
        return true
    }

    // ---------- buttons from the notification / lock screen ----------

    private fun handleCommand(cmd: String) {
        when (cmd) {
            "play" -> if (started || spotifyShownUri != null) remote?.playerApi?.resume() else onMainButton()
            "pause" -> remote?.playerApi?.pause()
            "next" -> onSkip()
            "prev" -> previous()
        }
    }

    /** Keeps the DJ running with the screen off, with controls on the lock screen. */
    private fun startBackground() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        PlaybackService.update(this)
    }

    // ---------- voice requests ----------

    private fun listen() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "What do you want to hear?")
        try { speechLauncher.launch(i) } catch (e: Exception) { toastNote("Voice input isn't available on this phone") }
    }

    // ---------- like / never again / share ----------

    private fun setLiked(on: Boolean) {
        likedCurrent = on
        btnLike.setImageResource(if (on) R.drawable.ic_heart_filled else R.drawable.ic_heart)
        btnLike.setColorFilter(ContextCompat.getColor(this, if (on) R.color.dj_glow else R.color.fg))
    }

    private fun toggleLike() {
        val user = remote?.userApi ?: run { toastNote("Connect Spotify first"); return }
        val t = current ?: run {
            // A song Spotify is playing on its own
            val uri = spotifyShownUri ?: return
            if (likedCurrent) { user.removeFromLibrary(uri); setLiked(false); toastNote("Removed from Liked Songs") }
            else { user.addToLibrary(uri); setLiked(true); toastNote("Saved to Liked Songs") }
            return
        }
        if (likedCurrent) {
            user.removeFromLibrary(t.uri); prefs.removeLiked(t); setLiked(false); toastNote("Removed from Liked Songs")
        } else {
            user.addToLibrary(t.uri); prefs.addLiked(t); setLiked(true); toastNote("Saved to Liked Songs. I'll play more like this")
        }
    }

    private fun neverAgain() {
        val t = current ?: return
        prefs.addDisliked(t)
        queue.removeAll { it.id == t.id }
        toastNote("Got it, never again")
        advance()
    }

    private fun share() {
        val t = current ?: return
        val text = "Listening to ${t.title} by ${t.artist.substringBefore(", ")} 🎧 https://open.spotify.com/track/${t.id}"
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share song"))
    }

    // ---------- ask about the song ----------

    private fun askAboutSong() {
        val t = current ?: return
        if (prefs.geminiKey.isEmpty()) { toastNote("Add a Gemini key in Settings so I can tell you about songs"); return }
        if (talking || transitioning || aboutJob?.isActive == true) return
        aboutJob = lifecycleScope.launch {
            val wasPlaying = !paused
            setStatus("Looking it up…")
            val text = runCatching { brain.about(t) }.getOrNull()
            setStatus("Connected to Spotify")
            if (text.isNullOrBlank()) { toastNote("Couldn't find anything on this one right now"); return@launch }
            if (current?.id != t.id) return@launch
            remote?.playerApi?.pause()
            talk(text)
            if (wasPlaying && current?.id == t.id) remote?.playerApi?.resume()
        }
    }

    // ---------- weekly recap ----------

    private fun weekStats(minPlays: Int): String? {
        val weekAgo = System.currentTimeMillis() - 7L * 24 * 3600_000
        val log = prefs.playLog
        val week = log.filter { it.first >= weekAgo }
        if (week.size < minPlays) return null
        val top = week.groupingBy { it.second }.eachCount().entries.sortedByDescending { it.value }.take(3)
        val before = log.filter { it.first < weekAgo }.map { it.second }.toSet()
        val fresh = week.map { it.second }.distinct().filter { it !in before }.take(3)
        return "Songs played: ${week.size}. Top artists: ${top.joinToString(", ") { "${it.key} (${it.value} plays)" }}. " +
            "Artists new to them this week: ${fresh.joinToString(", ").ifEmpty { "none" }}. " +
            "Songs they hearted: ${prefs.likedNames.takeLast(3).joinToString("; ").ifEmpty { "none yet" }}."
    }

    private fun recapDue() = System.currentTimeMillis() - prefs.lastRecapAt >= 7L * 24 * 3600_000 && weekStats(15) != null

    private suspend fun playRecap(minPlays: Int = 15): Boolean {
        val stats = weekStats(minPlays) ?: return false
        prefs.lastRecapAt = System.currentTimeMillis()
        val text = if (prefs.geminiKey.isNotEmpty()) runCatching { brain.recap(stats) }.getOrNull() else null
        talk(text ?: run {
            val week = prefs.playLog.filter { it.first >= System.currentTimeMillis() - 7L * 24 * 3600_000 }
            val top = week.groupingBy { it.second }.eachCount().maxByOrNull { it.value }?.key
            "Quick look at your week: ${week.size} songs, and your top artist was $top. Let's keep it going."
        })
        return true
    }

    // ---------- shows ----------

    private val SHOWS = linkedMapOf(
        "Morning boost" to "Morning boost: upbeat feel-good songs to start the day",
        "Throwback Thursday" to "Throwback Thursday: hits and favourites from the 2000s and 2010s",
        "Amapiano Friday" to "Amapiano Friday: amapiano and SA house to kick off the weekend",
        "Saturday party" to "Saturday party: high-energy party songs",
        "Sunday chill" to "Sunday chill: relaxed, soulful songs",
        "Study session" to "Study session: calm, mostly instrumental focus music",
        "Workout" to "Workout: high-energy hype songs",
        "Wind down" to "Wind down: slow, mellow songs for the end of the day",
    )

    /** All shows, with the one that fits today and now first. */
    private fun todaysShows(): List<String> {
        val now = java.time.LocalDateTime.now()
        val first = when {
            now.hour in 5..10 -> "Morning boost"
            now.hour >= 21 || now.hour < 5 -> "Wind down"
            now.dayOfWeek == java.time.DayOfWeek.THURSDAY -> "Throwback Thursday"
            now.dayOfWeek == java.time.DayOfWeek.FRIDAY -> "Amapiano Friday"
            now.dayOfWeek == java.time.DayOfWeek.SATURDAY -> "Saturday party"
            now.dayOfWeek == java.time.DayOfWeek.SUNDAY -> "Sunday chill"
            else -> "Study session"
        }
        return listOf(first) + SHOWS.keys.filter { it != first }
    }

    // ---------- menu: settings, sleep timer, alarm, recap ----------

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Settings")
            menu.add(0, 2, 1, if (sleepJob?.isActive == true) "Sleep timer (on)" else "Sleep timer")
            menu.add(0, 3, 2, if (prefs.alarmOn) "DJ alarm (%02d:%02d)".format(prefs.alarmHour, prefs.alarmMinute) else "DJ alarm")
            menu.add(0, 4, 3, "Weekly recap")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> showSettings()
                    2 -> showSleepTimer()
                    3 -> showAlarm()
                    4 -> lifecycleScope.launch {
                        if (talking) return@launch
                        val wasPlaying = !paused && started
                        remote?.playerApi?.pause()
                        if (!playRecap(minPlays = 3)) toastNote("Play a few more songs first, then I'll have a recap for you")
                        if (wasPlaying) remote?.playerApi?.resume()
                    }
                }
                true
            }
            show()
        }
    }

    private fun showSleepTimer() {
        val labels = arrayOf("Off", "15 minutes", "30 minutes", "45 minutes", "1 hour", "1.5 hours")
        val minutes = intArrayOf(0, 15, 30, 45, 60, 90)
        MaterialAlertDialogBuilder(this).setTitle("Sleep timer").setItems(labels) { _, i ->
            sleepJob?.cancel()
            if (minutes[i] == 0) { setStatus("Sleep timer off"); return@setItems }
            val endAt = System.currentTimeMillis() + minutes[i] * 60_000L
            setStatus("Sleep timer: music stops at " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(endAt)))
            sleepJob = lifecycleScope.launch {
                delay(minutes[i] * 60_000L)
                playJob?.cancel()
                remote?.playerApi?.pause()
                talk(listOf("That's it from me tonight. Sleep well.", "Time to call it a night. Goodnight, rest well.",
                    "And that's the end of the set. Sleep tight.").random())
                setStatus("Sleep timer finished")
            }
        }.show()
    }

    private fun showAlarm() {
        if (Build.VERSION.SDK_INT in 31..32 && !getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
            toastNote("Allow alarms for EchoRadio, then try again")
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)) }
            return
        }
        TimePickerDialog(this, { _, h, m ->
            prefs.alarmHour = h; prefs.alarmMinute = m; prefs.alarmOn = true
            DjAlarm.schedule(this)
            toastNote("DJ alarm set for %02d:%02d every day".format(h, m))
        }, prefs.alarmHour, prefs.alarmMinute, true).apply {
            setTitle("Wake up with the DJ")
            if (prefs.alarmOn) setButton(TimePickerDialog.BUTTON_NEUTRAL, "Turn off") { _, _ ->
                prefs.alarmOn = false; DjAlarm.schedule(this@MainActivity); toastNote("DJ alarm off")
            }
        }.show()
    }

    /** The alarm opened the app: wake the screen, then start a morning set once Spotify is connected. */
    private fun handleAlarm(intent: Intent?) {
        if (intent?.getBooleanExtra(DjAlarm.EXTRA_ALARM, false) != true) return
        intent.removeExtra(DjAlarm.EXTRA_ALARM)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        DjAlarm.schedule(this)                      // set tomorrow's alarm
        if (remote?.isConnected == true) startAlarmSet() else { pendingAlarm = true; if (web.isLoggedIn) connectRemote() }
    }

    /** The widget's DJ button: open straight to the request box. */
    private fun handleWidget(intent: Intent?) {
        if (intent?.getBooleanExtra(EchoWidget.EXTRA_OPEN_CHAT, false) != true) return
        intent.removeExtra(EchoWidget.EXTRA_OPEN_CHAT)
        chatSheet.post { openChat() }
    }

    private fun startAlarmSet() {
        say(Kind.YOU, "Wake-up call")
        startSet("Wake-up call: feel-good, upbeat songs to start the day. Open with a warm good-morning.")
    }

    // ---------- your taste ----------

    private val MADE_FOR_YOU = "Made for you"

    private fun isMadeForYou(v: String) = v.equals(MADE_FOR_YOU, true) || v.contains("my taste", true) ||
        v.contains("my music", true) || v.contains("my songs", true) || v.contains("songs i like", true)

    /** Fetches (and caches for 30 minutes) what the listener plays on Spotify. */
    private suspend fun loadTaste(): Taste? {
        if (!web.hasTasteAccess) return null
        if (taste != null && System.currentTimeMillis() - tasteAt < 30 * 60_000) return taste
        val t = web.taste() ?: return taste
        if (taste == null && t.nowArtists.isNotEmpty())
            say(Kind.NOTE, "Got your taste: ${t.nowArtists.take(3).joinToString(", ")} and more.")
        taste = t
        tasteAt = System.currentTimeMillis()
        return t
    }

    // ---------- fit around the status bar, navigation bar and keyboard ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun setupInsets() {
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val keyboardUp = insets.isVisible(WindowInsetsCompat.Type.ime())

            // Player: never under the clock / notch, never under the gesture bar
            scroll.setPadding(0, bars.top, 0, bars.bottom)

            // Chat sheet: sits on top of the keyboard when it's open
            val bottom = maxOf(ime.bottom, bars.bottom)
            chatSheet.setPadding(chatSheet.paddingLeft, chatSheet.paddingTop, chatSheet.paddingRight, dp(12) + bottom)
            val screenH = resources.displayMetrics.heightPixels
            val room = screenH - bars.top - bottom - dp(200)          // space left for the messages
            val chatH = if (keyboardUp) room.coerceIn(dp(120), dp(300)) else minOf(dp(320), (screenH * 0.42).toInt())
            chatScroll.layoutParams = chatScroll.layoutParams.apply { height = chatH }
            if (keyboardUp) chatScroll.post { chatScroll.scrollTo(0, log.height) }
            WindowInsetsCompat.CONSUMED
        }
        // Album art: square, but not so big it pushes the controls off small screens
        art.post {
            val w = art.width
            val maxH = (resources.displayMetrics.heightPixels * 0.40).toInt()
            art.layoutParams = art.layoutParams.apply { height = minOf(w, maxH) }
        }
        // Keep the newest message visible while typing
        vibeInput.setOnFocusChangeListener { _, has -> if (has) chatScroll.postDelayed({ chatScroll.scrollTo(0, log.height) }, 300) }
    }

    // ---------- talking animation ----------

    private fun showTalking(on: Boolean) {
        if (on && !voice.enabled) return  // voice switched off: keep the cover
        if (on) {
            talkView.visibility = View.VISIBLE
            talkView.start()
            talkView.animate().alpha(1f).setDuration(300).start()
            art.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(300).start()
        } else {
            talkView.animate().alpha(0f).setDuration(450).withEndAction {
                talkView.stop(); talkView.visibility = View.GONE
            }.start()
            art.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(450).start()
        }
    }

    // ---------- DJ chat sheet ----------

    private fun openChat() {
        if (chatSheet.visibility == View.VISIBLE) return
        scrim.alpha = 0f
        scrim.visibility = View.VISIBLE
        scrim.animate().alpha(1f).setDuration(200).start()
        chatSheet.visibility = View.VISIBLE
        chatSheet.post {
            chatSheet.translationY = chatSheet.height.toFloat()
            chatSheet.animate().translationY(0f).setDuration(250).start()
            chatScroll.scrollTo(0, log.height)
        }
    }

    private fun closeChat() {
        if (chatSheet.visibility != View.VISIBLE) return
        hideKeyboard()
        scrim.animate().alpha(0f).setDuration(200).withEndAction { scrim.visibility = View.GONE }.start()
        chatSheet.animate().translationY(chatSheet.height.toFloat()).setDuration(220)
            .withEndAction { chatSheet.visibility = View.GONE }.start()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(vibeInput.windowToken, 0)
    }

    private fun toastNote(text: String) =
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()

    // ---------- skipping: lots of quick skips = change the category ----------

    private fun onSkip() {
        val cur = current
        if (cur == null && queue.isEmpty()) { if (spotifyShownUri != null) remote?.playerApi?.skipNext(); return }
        if (cur != null && lastPos < 30_000) {      // skipped within 30 s = not feeling it
            quickSkips++
            skipped.add(cur)
            if (skipped.size > 10) skipped.removeAt(0)
        }
        if (quickSkips >= 3 && started && vibe.isNotEmpty()) switchCategory() else advance()
    }

    private fun switchCategory() {
        quickSkips = 0
        val old = vibe
        switchFrom = old
        requestType = "vibe"; requestArtist = null      // the new direction is a general vibe
        queue.clear()
        playJob?.cancel()
        voice.stop()
        playJob = lifecycleScope.launch {
            remote?.playerApi?.pause()
            say(Kind.NOTE, "Lots of skips. Switching it up…")
            while (filling) delay(200)   // let any refill of the old style finish…
            queue.clear()                // …then throw it away
            if (prefs.geminiKey.isEmpty()) {
                // No DJ brain: jump to another genre (from their taste if we know it)
                val pool = (taste?.genres.orEmpty() + listOf("amapiano", "afrobeats", "r&b", "hip hop",
                    "deep house", "neo soul", "indie pop", "gqom", "jazz", "dancehall"))
                vibe = pool.filterNot { old.contains(it, true) }.shuffled().first()
            }
            val intro = fill(more = false)
            switchFrom = null
            songsSinceTalk = 0
            talkGap = 3
            talk(intro.ifBlank { "Not feeling it? Let's try some $vibe instead." })
            playNext()
        }
    }

    // ---------- skip back ----------

    private fun previous() {
        val cur = current ?: run { if (spotifyShownUri != null) remote?.playerApi?.skipPrevious(); return }
        if (lastPos > 4000 || history.size < 2) {
            remote?.playerApi?.seekTo(0)
            lastPos = 0
            return
        }
        history.removeAt(history.lastIndex)              // the current song
        val prev = history.removeAt(history.lastIndex)   // the one before it
        queue.add(0, cur)
        queue.add(0, prev.copy(line = "Running it back. ${prev.title}, ${prev.artist}."))
        advance()
    }

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun onMainButton(fromMain: Boolean = false) {
        when {
            fromMain && web.isLoggedIn && !web.hasTasteAccess -> web.startLogin(this)
            prefs.clientId.isEmpty() -> { say(Kind.NOTE, "Add your Spotify Client ID in Settings first."); showSettings() }
            !web.isLoggedIn -> web.startLogin(this)
            remote?.isConnected != true -> connectRemote()
            current == null && spotifyShownUri != null -> if (paused) remote?.playerApi?.resume() else remote?.playerApi?.pause()
            !started -> {
                val v = vibeInput.text.toString().trim().ifEmpty { "Surprise me" }
                vibeInput.setText("")
                say(Kind.YOU, v)
                startSet(v)
            }
            current == null && queue.isEmpty() -> openChat()
            paused -> remote?.playerApi?.resume()
            else -> remote?.playerApi?.pause()
        }
    }

    private fun sendVibe() {
        val v = vibeInput.text.toString().trim()
        if (v.isEmpty()) return
        vibeInput.setText("")
        say(Kind.YOU, v)
        closeChat()
        startSet(v)
    }

    private fun updateMainButton() {
        val connected = web.isLoggedIn && remote?.isConnected == true
        val needTaste = connected && !web.hasTasteAccess
        btnMain.visibility = if (connected && !needTaste) View.GONE else View.VISIBLE
        btnMain.text = if (needTaste) "Let the DJ learn your taste" else "Connect Spotify"
        val active = started || spotifyShownUri != null
        btnPlay.setImageResource(if (connected && active && !paused) R.drawable.ic_pause else R.drawable.ic_play)
        btnPlay.alpha = if (connected) 1f else 0.5f
        btnSkip.alpha = if (connected && active) 1f else 0.4f
        btnPrev.alpha = if (connected && active) 1f else 0.4f
    }

    private fun setStatus(text: String) { status.text = text }

    private enum class Kind { DJ, YOU, NOTE }

    /**
     * No chat history: the DJ's words go on the DJ card, status messages on the card's status line,
     * and your requests aren't kept.
     */
    private fun say(kind: Kind, text: String): TextView {
        when (kind) {
            Kind.DJ -> djLine.text = text
            Kind.NOTE -> setStatus(text)
            Kind.YOU -> {}
        }
        return TextView(this)   // kept for callers that remove a "please wait" message
    }

    private fun renderNow() {
        val t = current ?: return
        spotifyShownUri = null
        art.load(t.art)
        npTitle.text = t.title
        npArtist.text = listOf(t.artist, t.album, t.year).filter { it.isNotBlank() }.joinToString(" · ")
        // Lock screen / notification
        NowPlaying.title = t.title; NowPlaying.artist = t.artist; NowPlaying.playing = true; NowPlaying.art = null
        if (started) PlaybackService.update(this)
        lifecycleScope.launch {
            val r = runCatching { imageLoader.execute(ImageRequest.Builder(this@MainActivity).data(t.art).size(512).allowHardware(false).build()) }.getOrNull()
            (r?.drawable as? BitmapDrawable)?.bitmap?.let {
                if (current?.id == t.id) {
                    NowPlaying.art = it; PlaybackService.update(this@MainActivity)
                    applyTheme(AlbumColors.extract(it))      // the app takes on the cover's colours
                }
            }
        }
        // Heart shows whether it's already in Liked Songs
        setLiked(false)
        remote?.userApi?.getLibraryState(t.uri)?.setResultCallback { st -> runOnUiThread { if (current?.id == t.id) setLiked(st.isAdded) } }
    }

    /** The queue stays hidden so what's next is a surprise; only a status hint is shown. */
    private fun renderQueue() {
        queueEmpty.text = when {
            filling && queue.isEmpty() -> "The DJ is digging through the crates…"
            queue.isNotEmpty() -> "What's next? That's a surprise."
            started -> "That's the set. Tap the DJ button for more."
            else -> "Tap the DJ button and tell me what you're in the mood for."
        }
        if (waitingForTracks && queue.isNotEmpty()) { waitingForTracks = false; advance() }
    }

    private fun showSettings() {
        val v = layoutInflater.inflate(R.layout.dialog_settings, null)
        val client = v.findViewById<EditText>(R.id.setClient).apply { setText(prefs.clientId) }
        val key = v.findViewById<EditText>(R.id.setKey).apply { setText(prefs.geminiKey) }
        val name = v.findViewById<EditText>(R.id.setName).apply { setText(prefs.djName) }
        val model = v.findViewById<EditText>(R.id.setModel).apply { setText(prefs.model) }
        val voiceNames = DjVoice.VOICES.map { it.first }
        val voiceLabels = DjVoice.VOICES.map { "${it.first} (${it.second})" }
        val voicePick = v.findViewById<Spinner>(R.id.setVoice).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, voiceLabels)
            setSelection(voiceNames.indexOf(prefs.voice).coerceAtLeast(0))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Settings")
            .setView(v)
            .setPositiveButton("Save") { _, _ ->
                prefs.clientId = client.text.toString()
                prefs.geminiKey = key.text.toString()
                prefs.djName = name.text.toString()
                prefs.model = model.text.toString()
                prefs.voice = voiceNames[voicePick.selectedItemPosition]
                voice.resetFailure()
                djName.text = prefs.djName
                updateMainButton()
            }
            .setNeutralButton("Disconnect Spotify") { _, _ ->
                web.logout()
                remote?.let { SpotifyAppRemote.disconnect(it) }
                remote = null
                setStatus("Not connected to Spotify")
                updateMainButton()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
