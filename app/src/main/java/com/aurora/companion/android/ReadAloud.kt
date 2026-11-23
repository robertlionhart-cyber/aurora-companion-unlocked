package com.aurora.companion.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * 1.0 read-aloud ("audiobook"): reads a Library book with the companion's Piper voice, one sentence at a time.
 *
 * - The text is split into sentences; a producer synthesizes up to [AHEAD] sentences ahead while the current one plays,
 *   so memory stays small (a few MB of audio at most) and there are no gaps.
 * - Own AudioTrack (media / speech), so pausing, skipping and speed changes don't touch chat speech.
 * - Audio focus: pauses for calls and other apps, resumes after a short interruption; pauses when headphones unplug.
 * - Keeps reading with the screen off through [ReadAloudService] (foreground "media playback" service with a media
 *   notification and lock-screen controls).
 * - The position is saved per book (sentence start offset), so "Read this to me" continues where it stopped.
 * - Needs only the voice, never the chat model.
 */
object ReadAloud {
    private const val TAG = "AuroraReadAloud"
    private const val AHEAD = 2
    val SPEEDS = listOf(0.75f, 0.9f, 1.0f, 1.15f, 1.3f, 1.5f)
    /** Sleep / bedtime timer choices in minutes; [SLEEP_END_OF_CHAPTER] stops at the end of the chapter. */
    val SLEEP_CHOICES = listOf(15, 30, 45, 60)
    const val SLEEP_END_OF_CHAPTER = -1

    data class State(
        val file: String,
        val title: String,
        val author: String,
        val playing: Boolean = false,
        val loading: Boolean = true,
        val chapterTitles: List<String> = emptyList(),
        val chapter: Int = 0,
        val sentence: Int = 0,
        val sentences: Int = 0,
        /** 0..1 within the current chapter. */
        val chapterProgress: Float = 0f,
        val bookProgress: Float = 0f,
        val current: String = "",
        val speed: Float = 1.0f,
        /** Wall-clock end of the sleep timer, or null. */
        val sleepAt: Long? = null,
        val sleepEndOfChapter: Boolean = false,
        val finished: Boolean = false,
        val error: String? = null,
        /** 1.0.2: the bedtime timer just ended the reading (the companion shows its sleeping clip until you go on). */
        val sleptByTimer: Boolean = false,
    ) {
        val chapterTitle get() = chapterTitles.getOrNull(chapter) ?: ""
        val sleepOn get() = sleepAt != null || sleepEndOfChapter
    }

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var app: Context? = null
    private var voice: VoiceEngine? = null
    /** Called before reading starts (the ViewModel stops chat speech). */
    @Volatile var beforePlay: (() -> Unit)? = null

    // Current book.
    private var book: BookInfo? = null
    private var text: String = ""
    private var sents: BookText.Sentences? = null
    private var chapters: List<BookText.Chapter> = emptyList()
    private val idx = AtomicInteger(0)
    private val gen = AtomicInteger(0)
    @Volatile private var paused = true
    private var job: Job? = null
    private var sleepJob: Job? = null

    /** 1.0.2: the companion's picture for the lock-screen / notification media controls (set by the view model). */
    @Volatile var artwork: android.graphics.Bitmap? = null
    private var track: AudioTrack? = null
    private var loadJob: Job? = null

    fun attach(ctx: Context, v: VoiceEngine) { app = ctx.applicationContext; voice = v }

    /** The app is closing for good: stop and release. */
    fun detach() { stop(); voice = null }

    private fun prefs() = app!!.getSharedPreferences("aurora", 0)
    fun savedPos(ctx: Context, file: String) = ctx.getSharedPreferences("aurora", 0).getInt("listen_pos_$file", 0)
    fun lastFile(ctx: Context): String? = ctx.getSharedPreferences("aurora", 0).getString("listen_last", null)
    private fun savePos(file: String, pos: Int) = prefs().edit().putInt("listen_pos_$file", pos).putString("listen_last", file).apply()

    val active get() = _state.value != null
    val isPlaying get() = _state.value?.playing == true

    /** Opens [b] (at [fromPos], else where it stopped last time) and starts reading when [autoplay]. */
    fun open(b: BookInfo, fromPos: Int? = null, autoplay: Boolean = true) {
        val ctx = app ?: return
        if (book?.file == b.file && sents != null) {
            if (fromPos != null) seekTo(sents!!.indexAt(fromPos))
            if (autoplay) play()
            return
        }
        stopPlayback()
        book = b
        val speed = prefs().getFloat("listen_speed", 1.0f)
        _state.value = State(b.file, b.title, b.author, loading = true, speed = speed)
        loadJob?.cancel()
        loadJob = scope.launch {
            try {
                val rt = withContext(Dispatchers.IO) { Library.readerText(ctx, b) }
                val s = BookText.sentences(rt.text)
                val ch = BookText.chapters(rt.text)
                if (s.size == 0) throw IllegalStateException("This book has no text to read.")
                text = rt.text; sents = s; chapters = ch
                var pos = fromPos ?: savedPos(ctx, b.file)
                // First listen: skip a title page / table of contents and start at the first real chapter.
                if (fromPos == null && pos == 0 && ch.size > 1 && ch[0].title == "Opening" &&
                    Regex("\\bcontents\\b", RegexOption.IGNORE_CASE).containsMatchIn(rt.text.substring(0, ch[1].start))) pos = ch[1].start
                var i = s.indexAt(pos)
                if (i >= s.size - 1) i = 0      // finished last time: start over
                idx.set(i)
                _state.update { it?.copy(loading = false, chapterTitles = ch.map { c -> c.title }, sentences = s.size) }
                publish()
                if (autoplay) play()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "open ${b.file}", e)
                _state.update { it?.copy(loading = false, error = e.message ?: "Couldn't open this book.") }
            }
        }
    }

    fun play() {
        val s = sents ?: return
        val v = voice
        if (v == null || !v.ttsReady) { _state.update { it?.copy(error = "The voice is still loading. Try again in a few seconds.") }; return }
        if (idx.get() >= s.size) idx.set(0)
        beforePlay?.invoke()
        if (!requestFocus()) { _state.update { it?.copy(error = "Another app is using audio right now.") }; return }
        registerNoisy()
        paused = false
        _state.update { it?.copy(playing = true, finished = false, error = null, sleptByTimer = false) }
        ReadAloudService.start(app!!)
        if (job?.isActive == true) { track?.play(); return }
        startPipeline(idx.get())
    }

    fun pause() {
        if (_state.value?.playing != true) return
        paused = true
        runCatching { track?.pause() }
        _state.update { it?.copy(playing = false) }
        book?.let { b -> sents?.let { s -> savePos(b.file, s.starts[idx.get().coerceIn(0, s.size - 1)]) } }
    }

    fun toggle() { if (isPlaying) pause() else play() }

    /** Closes the player (keeps the saved position). */
    fun stop() {
        pause()
        stopPlayback()
        loadJob?.cancel()
        cancelSleep()
        abandonFocus(); unregisterNoisy()
        runCatching { track?.release() }; track = null
        book = null; sents = null; text = ""; chapters = emptyList()
        _state.value = null
        app?.let { ReadAloudService.stop(it) }
    }

    private fun stopPlayback() {
        gen.incrementAndGet()
        job?.cancel(); job = null
        runCatching { track?.pause(); track?.flush() }
    }

    private fun seekTo(i: Int) {
        val s = sents ?: return
        val to = i.coerceIn(0, s.size - 1)
        val wasPlaying = isPlaying
        stopPlayback()
        idx.set(to)
        book?.let { savePos(it.file, s.starts[to]) }
        publish()
        if (wasPlaying) { paused = false; track?.play(); startPipeline(to) }
    }

    fun skipSentence(delta: Int) = seekTo(idx.get() + delta)

    fun skipParagraph(delta: Int) {
        val s = sents ?: return
        seekTo(if (delta < 0) s.prevParagraph(idx.get()) else s.nextParagraph(idx.get()))
    }

    fun chapter(delta: Int) {
        val s = sents ?: return
        if (chapters.isEmpty()) return
        val cur = BookText.chapterAt(chapters, s.starts[idx.get().coerceIn(0, s.size - 1)])
        // "Previous" a few sentences into a chapter goes back to its start, like a music player.
        val target = if (delta < 0 && s.starts[idx.get()] - chapters[cur].start > 300) cur else (cur + delta).coerceIn(0, chapters.size - 1)
        goToChapter(target)
    }

    fun goToChapter(c: Int) {
        val s = sents ?: return
        val ch = chapters.getOrNull(c) ?: return
        seekTo(s.indexAt(ch.start).let { if (s.starts[it] < ch.start && it + 1 < s.size) it + 1 else it })
    }

    fun setSpeed(sp: Float) {
        prefs().edit().putFloat("listen_speed", sp).apply()
        _state.update { it?.copy(speed = sp) }
        if (isPlaying) seekTo(idx.get())   // re-synthesize from the current sentence at the new speed
    }

    /** Minutes, [SLEEP_END_OF_CHAPTER], or 0 to turn the timer off. */
    fun setSleep(minutes: Int) {
        cancelSleep()
        when {
            minutes == SLEEP_END_OF_CHAPTER -> _state.update { it?.copy(sleepEndOfChapter = true, sleepAt = null) }
            minutes > 0 -> {
                val at = System.currentTimeMillis() + minutes * 60_000L
                _state.update { it?.copy(sleepAt = at, sleepEndOfChapter = false) }
                sleepJob = scope.launch {
                    while (isActive && System.currentTimeMillis() < at) delay(1000)
                    if (isActive) { Log.i(TAG, "sleep timer: pausing"); fadeAndPause() }
                }
            }
            else -> _state.update { it?.copy(sleepAt = null, sleepEndOfChapter = false) }
        }
    }

    private fun cancelSleep() { sleepJob?.cancel(); sleepJob = null; _state.update { it?.copy(sleepAt = null, sleepEndOfChapter = false) } }

    /** Sleep timer end: a short fade so it doesn't stop mid-word abruptly. */
    private suspend fun fadeAndPause() {
        val t = track
        if (t != null && isPlaying) for (k in 10 downTo 1) { runCatching { t.setVolume(k / 10f) }; delay(150) }
        withContext(Dispatchers.Main) { pause(); cancelSleep(); _state.update { it?.copy(sleptByTimer = true) } }
        runCatching { track?.setVolume(1f) }
    }

    private fun publish() { runCatching { publish0() } }

    private fun publish0() {
        val s = sents ?: return
        val i = idx.get().coerceIn(0, s.size - 1)
        val c = if (chapters.isEmpty()) 0 else BookText.chapterAt(chapters, s.starts[i])
        val cStart = chapters.getOrNull(c)?.start ?: 0
        val cEnd = chapters.getOrNull(c + 1)?.start ?: text.length
        val cur = text.substring(s.starts[i], s.ends[i])
        _state.update { it?.copy(sentence = i, chapter = c, current = cur,
            chapterProgress = ((s.starts[i] - cStart).toFloat() / maxOf(1, cEnd - cStart)).coerceIn(0f, 1f),
            bookProgress = (i.toFloat() / maxOf(1, s.size - 1)).coerceIn(0f, 1f)) }
    }

    /** What the voice says for sentence [i]: no [Illustration] tags, footnote marks, underscores or symbols. */
    private fun speechText(s: BookText.Sentences, txt: String, i: Int): String {
        var t = txt.substring(s.starts[i], s.ends[i])
        t = t.replace(Regex("\\[(Illustration|Footnote|Sidenote)[^\\]]*\\]?", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\[\\d{1,3}\\]"), "")
            .replace(Regex("--+"), ", ")
            .replace('_', ' ')
            .replace('\n', ' ')
        return VoiceEngine.cleanForSpeech(t)
    }

    private class Chunk(val i: Int, val samples: FloatArray?, val rate: Int)

    private fun startPipeline(from: Int) {
        val g = gen.incrementAndGet()
        val s = sents ?: return
        val b = book ?: return
        val v = voice ?: return
        val txt = text
        val speed = _state.value?.speed ?: 1f
        val startChapter = if (chapters.isEmpty()) 0 else BookText.chapterAt(chapters, s.starts[from.coerceIn(0, s.size - 1)])
        job = scope.launch(Dispatchers.Default) {
            val ch = Channel<Chunk>(AHEAD)
            val producer = launch(Dispatchers.Default) {
                var i = from
                while (isActive && i < s.size && g == gen.get()) {
                    val t = speechText(s, txt, i)
                    val a = if (t.any { it.isLetterOrDigit() }) runCatching { v.synth(t, speed) }
                        .onFailure { Log.e(TAG, "synth failed", it) }.getOrNull() else null
                    ch.send(Chunk(i, a?.first, a?.second ?: 0))
                    i++
                }
                ch.close()
            }
            try {
                for (c in ch) {
                    if (g != gen.get()) break
                    // End-of-chapter sleep timer: stop at the first sentence of the next chapter.
                    if (_state.value?.sleepEndOfChapter == true && chapters.isNotEmpty() && c.i > from &&
                        BookText.chapterAt(chapters, s.starts[c.i]) != startChapter) {
                        idx.set(c.i); publish(); savePos(b.file, s.starts[c.i])
                        drain(g)
                        withContext(Dispatchers.Main) { if (g == gen.get()) { pause(); cancelSleep(); _state.update { it?.copy(sleptByTimer = true) } } }
                        break
                    }
                    idx.set(c.i)
                    publish()
                    savePos(b.file, s.starts[c.i])
                    val samples = c.samples ?: continue
                    if (samples.isEmpty()) continue
                    playSamples(samples, c.rate, g)
                    // A short breath after each paragraph.
                    if (c.i + 1 < s.size && s.paraStart[c.i + 1]) playSamples(FloatArray(c.rate / 3), c.rate, g)
                }
                if (g == gen.get() && idx.get() >= s.size - 1 && !producer.isActive) {
                    drain(g)
                    if (g == gen.get()) {
                        Log.i(TAG, "finished ${b.file}")
                        savePos(b.file, 0)
                        withContext(Dispatchers.Main) { pause(); idx.set(0); savePos(b.file, 0); _state.update { it?.copy(finished = true) } }
                    }
                }
            } finally {
                producer.cancel()
            }
        }
    }

    private fun ensureTrack(rate: Int): AudioTrack {
        track?.let { if (it.sampleRate == rate && it.state == AudioTrack.STATE_INITIALIZED) return it; runCatching { it.release() } }
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(minBuf, rate * 4 / 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().also { track = it }
    }

    /** Writes [samples] without blocking forever: waits while paused, stops when the generation changes (seek / stop). */
    private suspend fun playSamples(samples: FloatArray, rate: Int, g: Int) {
        val t = ensureTrack(rate)
        if (!paused && t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
        var off = 0
        val chunk = rate / 10
        while (off < samples.size) {
            if (g != gen.get()) return
            if (paused) { delay(60); continue }
            if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
            val w = t.write(samples, off, minOf(chunk, samples.size - off), AudioTrack.WRITE_NON_BLOCKING)
            if (w < 0) { Log.e(TAG, "AudioTrack write error $w"); return }
            if (w == 0) delay(25) else off += w
        }
    }

    /** Waits until what's buffered has been heard (end of book / chapter). */
    private suspend fun drain(g: Int) {
        val t = track ?: return
        repeat(40) { if (g != gen.get() || paused) return; delay(50) }
        runCatching { t.stop() }
    }

    // ---------- audio focus / headphones ----------
    private var focusReq: AudioFocusRequest? = null
    @Volatile private var resumeOnGain = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        scope.launch(Dispatchers.Main) {
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> { resumeOnGain = false; pause() }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    // Speech isn't ducked (hard to follow quietly): pause and continue afterwards.
                    if (isPlaying) { resumeOnGain = true; pause() }
                }
                AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnGain) { resumeOnGain = false; play() }
            }
        }
    }

    private fun requestFocus(): Boolean {
        val am = app?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return true
        if (Build.VERSION.SDK_INT >= 26) {
            val req = focusReq ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(focusListener).build().also { focusReq = it }
            return am.requestAudioFocus(req) != AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }
        return true
    }

    private fun abandonFocus() {
        val am = app?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        focusReq?.let { runCatching { am.abandonAudioFocusRequest(it) } }
        resumeOnGain = false
    }

    private var noisyRegistered = false
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { if (i?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause() }
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        val ctx = app ?: return
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(ctx, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            noisyRegistered = true
        }.onFailure { Log.w(TAG, "noisy receiver", it) }
    }

    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        runCatching { app?.unregisterReceiver(noisy) }
        noisyRegistered = false
    }
}
