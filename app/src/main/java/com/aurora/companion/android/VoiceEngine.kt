package com.aurora.companion.android

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Offline voice for Aurora, built on sherpa-onnx (bundled in the APK):
 *  - Speech-to-text: Moonshine tiny (English, int8) from assets/asr
 *  - Text-to-speech: Robert's Piper voice (VITS) from assets/robert + espeak-ng-data
 */
class VoiceEngine(private val context: Context) {

    private var tts: OfflineTts? = null
    private var asr: OfflineRecognizer? = null
    private var vad: Vad? = null
    private val ttsLock = ReentrantLock()
    val ttsReady get() = tts != null
    val asrReady get() = asr != null
    val vadReady get() = vad != null

    /** Which voice is loaded, and the speaker id for multi-speaker voices (LibriTTS). */
    var voiceId: String = VoiceCatalog.ROBERT.id
        private set
    @Volatile var speakerId: Int = 0

    /** Heavy: call from a background thread. Loads (or switches to) a voice. */
    fun initTts(voice: VoiceOption = VoiceCatalog.ROBERT) {
        val dataDir = copyAssetDir("robert/espeak-ng-data")
        val vits = if (voice.builtIn) {
            OfflineTtsVitsModelConfig(
                model = "robert/en_US-robert-medium.onnx",
                tokens = "robert/tokens.txt",
                dataDir = dataDir,
                noiseScale = voice.noiseScale, noiseScaleW = voice.noiseScaleW,
            )
        } else {
            val dir = VoiceCatalog.dir(context, voice)
            OfflineTtsVitsModelConfig(
                model = File(dir, VoiceCatalog.MODEL_FILE).absolutePath,
                tokens = File(dir, VoiceCatalog.TOKENS_FILE).absolutePath,
                dataDir = dataDir,
                noiseScale = voice.noiseScale, noiseScaleW = voice.noiseScaleW,
            )
        }
        val cfg = OfflineTtsConfig(
            model = OfflineTtsModelConfig(vits = vits, numThreads = 2, debug = false, provider = "cpu"),
            maxNumSentences = 1,
        )
        val fresh = OfflineTts(assetManager = if (voice.builtIn) context.assets else null, config = cfg)
        ttsLock.withLock {
            runCatching { tts?.release() }
            tts = fresh
            voiceId = voice.id
        }
        Log.i(TAG, "TTS ready: voice=${voice.id} speakers=${fresh.numSpeakers()} sampleRate=${fresh.sampleRate()}")
    }

    fun numSpeakers(): Int = tts?.numSpeakers() ?: 1

    fun initVad() {
        if (vad != null) return
        vad = Vad(
            assetManager = context.assets,
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = "vad/silero_vad.onnx",
                    threshold = 0.5f,
                    minSilenceDuration = 0.25f,
                    minSpeechDuration = 0.25f,
                    windowSize = 512,
                    maxSpeechDuration = 30f,
                ),
                sampleRate = MIC_RATE,
                numThreads = 1,
                provider = "cpu",
            ),
        )
        Log.i(TAG, "VAD ready")
    }

    fun initAsr() {
        if (asr != null) return
        val cfg = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                moonshine = OfflineMoonshineModelConfig(
                    encoder = "asr/encoder_model.ort",
                    mergedDecoder = "asr/decoder_model_merged.ort",
                ),
                tokens = "asr/tokens.txt",
                numThreads = 2,
                provider = "cpu",
            ),
        )
        asr = OfflineRecognizer(assetManager = context.assets, config = cfg)
        Log.i(TAG, "ASR ready")
    }

    /**
     * Moonshine v2 (sherpa-onnx 1.13.8) fails on clips longer than about 10 s
     * ("Attempting to broadcast an axis..."), so long recordings are split into pieces
     * of 8 s or less, cut at the quietest moment, and the texts are joined.
     */
    fun transcribe(samples: FloatArray, sampleRate: Int = MIC_RATE): String {
        if (asr == null) return ""
        val maxLen = MAX_SEGMENT_SEC * sampleRate
        if (samples.size <= maxLen) return decodeOne(samples, sampleRate)
        val parts = mutableListOf<String>()
        val win = sampleRate * 3 / 100            // 30 ms energy window
        var start = 0
        while (start < samples.size) {
            var end = minOf(start + maxLen, samples.size)
            if (end < samples.size) {
                var best = end
                var bestE = Float.MAX_VALUE
                var i = start + MIN_SEGMENT_SEC * sampleRate
                while (i + win <= end) {
                    var e = 0f
                    for (k in i until i + win) e += samples[k] * samples[k]
                    if (e < bestE) { bestE = e; best = i + win / 2 }
                    i += win / 2
                }
                end = best
            }
            if (end - start >= sampleRate / 4) {
                val t = decodeOne(samples.copyOfRange(start, end), sampleRate)
                if (t.isNotBlank()) parts.add(t)
            }
            start = end
        }
        Log.i(TAG, "ASR: ${samples.size} samples in ${parts.size} segment(s)")
        return parts.joinToString(" ").trim()
    }

    private fun decodeOne(samples: FloatArray, sampleRate: Int): String {
        val rec = asr ?: return ""
        val stream = rec.createStream()
        return try {
            stream.acceptWaveform(samples, sampleRate)
            rec.decode(stream)
            rec.getResult(stream).text.trim().also { Log.i(TAG, "ASR segment ${samples.size} samples -> ${it.length} chars") }
        } catch (e: Throwable) {
            Log.e(TAG, "ASR segment failed (${samples.size} samples)", e); ""
        } finally {
            stream.release()
        }
    }

    // ---------------- microphone ----------------
    private val recording = AtomicBoolean(false)

    /** Blocking: records 16 kHz mono until [stopRecording] or [maxSeconds]. Returns float samples. */
    @SuppressLint("MissingPermission") // caller checks RECORD_AUDIO
    fun recordBlocking(maxSeconds: Int = 30): FloatArray {
        val minBuf = AudioRecord.getMinBufferSize(MIC_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, MIC_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, MIC_RATE))
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "Microphone is busy or unavailable" }
        val out = ArrayList<ShortArray>()
        val buf = ShortArray(1600)
        var total = 0
        recording.set(true)
        rec.startRecording()
        try {
            while (recording.get() && total < maxSeconds * MIC_RATE) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) { out.add(buf.copyOf(n)); total += n }
            }
        } finally {
            recording.set(false)
            runCatching { rec.stop() }
            rec.release()
        }
        val f = FloatArray(total)
        var i = 0
        for (chunk in out) for (s in chunk) f[i++] = s / 32768f
        return f
    }

    fun stopRecording() = recording.set(false)

    enum class AutoResult { SPEECH, NO_SPEECH, CANCELLED }

    /**
     * Hands-free: records until the speaker has been quiet for [endSilenceMs] after speaking
     * (Silero VAD), or [maxSeconds], or [stopRecording]. Gives up if nobody starts talking
     * within [noSpeechTimeoutMs]. [onLevel] gets 0..1 mic level about 10x per second.
     */
    @SuppressLint("MissingPermission")
    fun recordAuto(
        onLevel: (Float, Boolean) -> Unit,
        endSilenceMs: Int = 1200, noSpeechTimeoutMs: Int = 8000, maxSeconds: Int = 30,
    ): Pair<AutoResult, FloatArray> {
        val v = vad ?: return AutoResult.CANCELLED to FloatArray(0)
        v.reset()
        val minBuf = AudioRecord.getMinBufferSize(MIC_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, MIC_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, MIC_RATE))
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "Microphone is busy or unavailable" }
        val chunks = ArrayList<FloatArray>()
        val buf = ShortArray(MIC_RATE / 10)       // 100 ms
        var total = 0
        var speechStartChunk = -1
        var silentMs = 0
        var result = AutoResult.NO_SPEECH
        recording.set(true)
        rec.startRecording()
        try {
            while (true) {
                if (!recording.get()) { result = if (speechStartChunk >= 0) AutoResult.SPEECH else AutoResult.CANCELLED; break }
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                val f = FloatArray(n) { buf[it] / 32768f }
                chunks.add(f); total += n
                var sum = 0.0
                for (x in f) sum += x * x
                val rms = Math.sqrt(sum / n).toFloat()
                v.acceptWaveform(f)
                val speaking = v.isSpeechDetected()
                if (!v.empty()) v.clear()           // we only need the live speech/no-speech flag
                onLevel((rms * 12f).coerceIn(0f, 1f), speechStartChunk >= 0)
                val elapsedMs = total * 1000 / MIC_RATE
                if (speaking) {
                    if (speechStartChunk < 0) { speechStartChunk = chunks.size - 1; Log.i(TAG, "VAD: speech started at ${elapsedMs} ms") }
                    silentMs = 0
                } else if (speechStartChunk >= 0) {
                    silentMs += n * 1000 / MIC_RATE
                    if (silentMs >= endSilenceMs) { result = AutoResult.SPEECH; Log.i(TAG, "VAD: end of speech at ${elapsedMs} ms"); break }
                }
                if (speechStartChunk < 0 && elapsedMs >= noSpeechTimeoutMs) { result = AutoResult.NO_SPEECH; Log.i(TAG, "VAD: no speech within ${noSpeechTimeoutMs} ms"); break }
                if (elapsedMs >= maxSeconds * 1000) { result = if (speechStartChunk >= 0) AutoResult.SPEECH else AutoResult.NO_SPEECH; break }
            }
        } finally {
            recording.set(false)
            runCatching { rec.stop() }
            rec.release()
        }
        if (result != AutoResult.SPEECH) return result to FloatArray(0)
        // keep ~0.5 s before speech started (VAD needs a moment to trigger)
        val from = maxOf(0, speechStartChunk - 5)
        val keep = chunks.subList(from, chunks.size)
        val out = FloatArray(keep.sumOf { it.size })
        var i = 0
        for (c in keep) { System.arraycopy(c, 0, out, i, c.size); i += c.size }
        Log.i(TAG, "VAD: kept ${out.size} samples (${out.size * 1000 / MIC_RATE} ms)")
        return result to out
    }

    // ---------------- speaker ----------------
    private val epoch = AtomicInteger(0)
    private var track: AudioTrack? = null
    private var trackIsCall = false
    /** 0 = normal media playback; otherwise the call recorder's session (echo cancellation reference). */
    @Volatile private var callSession = 0

    /** Switch Aurora's playback between normal media and call audio (same session as the call mic). */
    fun setCallAudio(sessionId: Int) {
        stopSpeaking()
        callSession = sessionId
        Log.i(TAG, "TTS output -> " + if (sessionId != 0) "call (session $sessionId, VOICE_COMMUNICATION)" else "media")
    }

    fun currentEpoch() = epoch.get()

    /** Interrupt anything being spoken now; queued sentences with an old epoch are skipped. */
    fun stopSpeaking() {
        epoch.incrementAndGet()
        track?.let { runCatching { it.pause(); it.flush() } }
    }

    /** Blocking: synthesizes and plays one sentence, unless interrupted. */
    fun speakBlocking(text: String, myEpoch: Int, speed: Float = 1.0f) {
        if (myEpoch != epoch.get()) return
        val audio = ttsLock.withLock {
            val engine = tts ?: return
            val sid = if (engine.numSpeakers() > 1) speakerId.coerceIn(0, engine.numSpeakers() - 1) else 0
            engine.generate(text = text, sid = sid, speed = speed)
        }
        Log.i(TAG, "TTS generated ${audio.samples.size} samples @${audio.sampleRate} Hz for ${text.length} chars")
        if (myEpoch != epoch.get() || audio.samples.isEmpty()) return
        val t = ensureTrack(audio.sampleRate)
        t.setVolume(1f)
        if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
        var written = 0
        val chunk = audio.sampleRate / 10
        var off = 0
        while (off < audio.samples.size && myEpoch == epoch.get()) {
            val n = minOf(chunk, audio.samples.size - off)
            val w = t.write(audio.samples, off, n, AudioTrack.WRITE_BLOCKING)
            if (w < 0) { Log.e(TAG, "AudioTrack write error $w"); break }
            written += w
            off += n
        }
        Log.i(TAG, "TTS wrote $written/${audio.samples.size} samples")
    }

    private fun ensureTrack(rate: Int): AudioTrack {
        val wantCall = callSession != 0
        track?.let {
            if (it.sampleRate == rate && trackIsCall == wantCall && (!wantCall || it.audioSessionId == callSession)) return it
            runCatching { it.release() }
        }
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        trackIsCall = wantCall
        val b = AudioTrack.Builder()
        if (wantCall) b.setSessionId(callSession)
        return b
            .setAudioAttributes(AudioAttributes.Builder()
                // Call mode: VOICE_COMMUNICATION so the platform echo canceller references it.
                .setUsage(if (wantCall) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(minBuf, rate * 4 / 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().also { track = it }
    }

    fun release() {
        stopSpeaking(); stopRecording()
        runCatching { track?.release() }; track = null
        runCatching { tts?.release() }; tts = null
        runCatching { asr?.release() }; asr = null
        runCatching { vad?.release() }; vad = null
    }

    /** espeak-ng needs real files, so copy that small folder out of the APK once per app version. */
    private fun copyAssetDir(assetPath: String): String {
        val versionTag = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        }.getOrDefault("0")
        val dest = File(context.filesDir, assetPath)
        val marker = File(dest, ".copied-$versionTag")
        if (marker.exists()) return dest.absolutePath
        dest.deleteRecursively()
        copyRecursive(assetPath, dest)
        marker.createNewFile()
        return dest.absolutePath
    }

    private fun copyRecursive(assetPath: String, dest: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input -> dest.outputStream().use { input.copyTo(it) } }
        } else {
            dest.mkdirs()
            for (c in children) copyRecursive("$assetPath/$c", File(dest, c))
        }
    }

    companion object {
        private const val TAG = "AuroraVoice"
        const val MIC_RATE = 16000
        const val MAX_SEGMENT_SEC = 8
        const val MIN_SEGMENT_SEC = 4

        /** Make text pleasant to speak: drop markdown symbols, emojis and stage directions. */
        fun cleanForSpeech(s: String): String = s
            .replace(Regex("\\*[^*]{0,80}\\*(?=\\s|$)") ) { m -> if (m.value.length > 40) m.value.trim('*') else "" }
            .replace(Regex("[*_#`~>|]"), " ")
            .replace(Regex("[\\p{So}\\p{Cs}\\p{Cn}\\uFE0F\\u200D]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}

/** Splits a token stream into speakable sentences as they complete. */
class SentenceSplitter {
    private val buf = StringBuilder()
    fun push(token: String): List<String> {
        buf.append(token)
        val out = mutableListOf<String>()
        while (true) {
            var cut = -1
            for (i in buf.indices) {
                val c = buf[i]
                val endPunct = (c == '.' || c == '!' || c == '?') && i + 1 < buf.length && buf[i + 1].isWhitespace()
                if (endPunct || c == '\n') { cut = i + 1; break }
            }
            if (cut <= 0) break
            val s = buf.substring(0, cut).trim()
            buf.delete(0, cut)
            if (s.any { it.isLetterOrDigit() }) out.add(s)
        }
        return out
    }
    fun flush(): String? {
        val s = buf.toString().trim(); buf.clear()
        return if (s.any { it.isLetterOrDigit() }) s else null
    }
}
