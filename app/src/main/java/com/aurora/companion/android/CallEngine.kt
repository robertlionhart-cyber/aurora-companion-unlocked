package com.aurora.companion.android

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phone-call style, always-listening loop (0.4.0).
 *  - Mic: VOICE_COMMUNICATION source + AcousticEchoCanceler + NoiseSuppressor when available,
 *    audio mode IN_COMMUNICATION, and Aurora's voice is played in the SAME audio session so the
 *    platform echo canceller can remove it.
 *  - Silero VAD on every 32 ms frame (512 samples @ 16 kHz) with a pre-roll ring buffer, so an
 *    utterance is captured from speech onset even when it interrupts Aurora (barge-in).
 *  - While Aurora is talking: higher VAD threshold + longer minimum speech before barge-in fires.
 */
class CallEngine(private val context: Context) {

    interface Listener {
        fun isAuroraBusy(): Boolean          // speaking or thinking -> speech counts as barge-in
        fun isAuroraSpeaking(): Boolean      // TTS audible -> use stricter thresholds
        fun onBargeIn()
        fun onSpeechStart()
        fun onUtterance(samples: FloatArray)
        fun onLevel(level: Float)
        fun onError(msg: String)
    }

    data class Settings(val bargeIn: Boolean, val sensitivity: String)

    private val running = AtomicBoolean(false)
    @Volatile var muted = false
    @Volatile var settings = Settings(true, "medium")
    private var thread: Thread? = null
    private var vad: Vad? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var prevMode = AudioManager.MODE_NORMAL

    /** Audio session id of the call recorder (0 until started); Aurora's TTS track joins it. */
    @Volatile var sessionId: Int = 0
        private set

    val isRunning get() = running.get()

    /** Starts the loop. Returns the record session id (for TTS) or throws. RECORD_AUDIO must be granted. */
    @SuppressLint("MissingPermission")
    fun start(listener: Listener): Int {
        if (running.get()) return sessionId
        if (vad == null) {
            vad = Vad(
                assetManager = context.assets,
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(model = "vad/silero_vad.onnx", windowSize = FRAME),
                    sampleRate = RATE, numThreads = 1, provider = "cpu",
                ),
            )
        }
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE))
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "Microphone is busy or unavailable" }
        sessionId = rec.audioSessionId

        // Echo cancellation / noise suppression on the recorder's session.
        val aecAvail = AcousticEchoCanceler.isAvailable()
        aec = if (aecAvail) runCatching { AcousticEchoCanceler.create(sessionId)?.apply { enabled = true } }.getOrNull() else null
        val nsAvail = NoiseSuppressor.isAvailable()
        ns = if (nsAvail) runCatching { NoiseSuppressor.create(sessionId)?.apply { enabled = true } }.getOrNull() else null
        Log.i(TAG, "Call audio: session=$sessionId AEC available=$aecAvail enabled=${aec?.enabled} NS available=$nsAvail enabled=${ns?.enabled}")

        routeForCall(true)
        running.set(true)
        thread = Thread({ loop(rec, listener) }, "aurora-call-mic").apply { start() }
        return sessionId
    }

    fun stop() {
        running.set(false)
        thread?.join(1500)
        thread = null
        runCatching { aec?.release() }; aec = null
        runCatching { ns?.release() }; ns = null
        routeForCall(false)
        sessionId = 0
    }

    fun release() {
        stop()
        runCatching { vad?.release() }; vad = null
    }

    /** IN_COMMUNICATION mode; loudspeaker unless headphones / Bluetooth are connected. */
    private fun routeForCall(on: Boolean) {
        try {
            if (on) {
                prevMode = am.mode
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                val headset = outs.any {
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }
                if (Build.VERSION.SDK_INT >= 31) {
                    val target = am.availableCommunicationDevices.firstOrNull {
                        if (headset) it.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE && it.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                        else it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                    if (target != null) am.setCommunicationDevice(target)
                    Log.i(TAG, "Call route: headset=$headset device=${target?.type}")
                } else {
                    @Suppress("DEPRECATION")
                    am.isSpeakerphoneOn = !headset
                    Log.i(TAG, "Call route: headset=$headset speakerphone=${!headset}")
                }
            } else {
                if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
                else { @Suppress("DEPRECATION") am.isSpeakerphoneOn = false }
                am.mode = prevMode
            }
        } catch (e: Exception) {
            Log.w(TAG, "audio routing failed", e)
        }
    }

    private fun loop(rec: AudioRecord, l: Listener) {
        val v = vad!!
        val frame = ShortArray(FRAME)
        val preRoll = ArrayDeque<FloatArray>()          // last ~0.6 s
        val utter = ArrayList<FloatArray>()
        var inSpeech = false
        var speechRunMs = 0
        var silenceMs = 0
        var lastLevel = 0L
        try {
            rec.startRecording()
            Log.i(TAG, "Call loop started")
            while (running.get()) {
                var got = 0
                while (got < FRAME && running.get()) {
                    val n = rec.read(frame, got, FRAME - got)
                    if (n < 0) throw IllegalStateException("mic read error $n")
                    got += n
                }
                if (!running.get()) break
                val f = FloatArray(FRAME) { frame[it] / 32768f }
                val now = System.currentTimeMillis()
                if (now - lastLevel > 90) {
                    var sum = 0.0; for (x in f) sum += x * x
                    l.onLevel(if (muted) 0f else (Math.sqrt(sum / FRAME).toFloat() * 12f).coerceIn(0f, 1f))
                    lastLevel = now
                }
                if (muted) { inSpeech = false; utter.clear(); preRoll.clear(); speechRunMs = 0; continue }

                val prob = v.compute(f)
                val speaking = l.isAuroraSpeaking()
                val s = settings
                if (!inSpeech) {
                    preRoll.addLast(f); while (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
                    // While Aurora talks: stricter threshold + longer minimum speech; or ignore if barge-in is off.
                    val thr = if (speaking) bargeThreshold(s.sensitivity) else START_THRESHOLD
                    val minMs = if (speaking) bargeMinMs(s.sensitivity) else MIN_SPEECH_MS
                    if (speaking && !s.bargeIn) { speechRunMs = 0; continue }
                    if (prob >= thr) speechRunMs += FRAME_MS else speechRunMs = maxOf(0, speechRunMs - FRAME_MS)
                    if (speechRunMs >= minMs) {
                        inSpeech = true; silenceMs = 0
                        utter.clear(); utter.addAll(preRoll); preRoll.clear()
                        val busy = l.isAuroraBusy()
                        Log.i(TAG, "speech start (prob=%.2f thr=%.2f auroraSpeaking=$speaking busy=$busy)".format(prob, thr))
                        if (busy) { if (s.bargeIn) { Log.i(TAG, "BARGE-IN"); l.onBargeIn() } }
                        l.onSpeechStart()
                    }
                } else {
                    utter.add(f)
                    if (prob < END_THRESHOLD) silenceMs += FRAME_MS else silenceMs = 0
                    val lenMs = utter.size * FRAME_MS
                    if (silenceMs >= END_SILENCE_MS || lenMs >= MAX_UTTER_MS) {
                        inSpeech = false; speechRunMs = 0
                        val keepFrames = utter.size - (silenceMs / FRAME_MS) + 6   // drop most trailing silence
                        val frames = utter.subList(0, keepFrames.coerceIn(1, utter.size))
                        val out = FloatArray(frames.size * FRAME)
                        frames.forEachIndexed { i, a -> System.arraycopy(a, 0, out, i * FRAME, FRAME) }
                        utter.clear()
                        Log.i(TAG, "utterance ${out.size} samples (${out.size * 1000 / RATE} ms)")
                        if (out.size >= RATE / 3) l.onUtterance(out)
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "call loop failed", e)
            l.onError(e.message ?: "microphone error")
        } finally {
            runCatching { rec.stop() }
            rec.release()
            Log.i(TAG, "Call loop stopped")
        }
    }

    companion object {
        private const val TAG = "AuroraCall"
        const val RATE = 16000
        const val FRAME = 512                 // Silero window @16 kHz = 32 ms
        const val FRAME_MS = 32
        const val PRE_ROLL_FRAMES = 19        // ~0.6 s captured before onset
        const val START_THRESHOLD = 0.5f
        const val END_THRESHOLD = 0.35f
        const val MIN_SPEECH_MS = 192
        const val END_SILENCE_MS = 1000
        const val MAX_UTTER_MS = 30_000

        fun bargeThreshold(sens: String) = when (sens) { "low" -> 0.9f; "high" -> 0.65f; else -> 0.8f }
        fun bargeMinMs(sens: String) = when (sens) { "low" -> 480; "high" -> 288; else -> 352 }
    }
}
