package com.aurora.companion.android

import android.content.Context
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 1.0.1: a short meow (Leo, the cat look) or woof (Barry, the dog look) before the companion talks. The clips are
 * bundled in assets/sounds (16-bit mono WAV, public domain / CC0, see THIRD_PARTY_NOTICES.md), a little quieter than
 * the voice, and a different one plays each time.
 */
object AnimalSounds {
    private const val TAG = "AnimalSounds"
    private val CLIPS = mapOf(
        "cat" to listOf("cat_1", "cat_2", "cat_3", "cat_4", "cat_5"),
        "dog" to listOf("dog_1", "dog_2", "dog_3", "dog_4"),
    )
    /** Short quiet gap between the animal sound and the first word. */
    private const val GAP_SEC = 0.18f
    private val cache = HashMap<String, Pair<FloatArray, Int>>()
    private val last = HashMap<String, String>()

    /** "cat" / "dog" for the cat and dog looks (built-in Leo and Barry, or a custom character using their look). */
    fun kindFor(look: String?): String? = when (look) {
        "leo" -> "cat"
        "barry" -> "dog"
        else -> null
    }

    /** A random clip for [kind] (never the same one twice in a row), at [rate] Hz, followed by a short silence. */
    @Synchronized
    fun next(ctx: Context, kind: String, rate: Int): FloatArray? {
        val names = CLIPS[kind] ?: return null
        val pick = names.filter { it != last[kind] }.ifEmpty { names }.random()
        last[kind] = pick
        val (pcm, srcRate) = cache.getOrPut(pick) {
            runCatching { decodeWav(ctx.assets.open("sounds/$pick.wav").use { it.readBytes() }) }
                .onFailure { Log.e(TAG, "can't read $pick", it) }.getOrNull() ?: return null
        }
        val out = resample(pcm, srcRate, rate)
        return out.copyOf(out.size + (rate * GAP_SEC).toInt())
    }

    /** Minimal PCM WAV reader (16-bit, mono or stereo -> mono). Returns samples in -1..1 and the sample rate. */
    fun decodeWav(b: ByteArray): Pair<FloatArray, Int> {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        require(b.size > 44 && String(b, 0, 4) == "RIFF" && String(b, 8, 4) == "WAVE") { "not a WAV file" }
        var pos = 12; var rate = 0; var ch = 1; var bits = 16
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4); val len = bb.getInt(pos + 4)
            if (id == "fmt ") { ch = bb.getShort(pos + 10).toInt(); rate = bb.getInt(pos + 12); bits = bb.getShort(pos + 22).toInt() }
            if (id == "data") {
                require(bits == 16 && rate > 0 && ch >= 1) { "unsupported WAV ($bits bit, $ch ch)" }
                val n = minOf(len, b.size - pos - 8) / (2 * ch)
                val out = FloatArray(n)
                for (i in 0 until n) {
                    var s = 0f
                    for (c in 0 until ch) s += bb.getShort(pos + 8 + (i * ch + c) * 2) / 32768f
                    out[i] = s / ch
                }
                return out to rate
            }
            pos += 8 + len + (len and 1)
        }
        error("no audio data")
    }

    /** Linear resampling (the clips are 22.05 kHz like the voices, so this is normally a copy). */
    fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || x.isEmpty()) return x.copyOf()
        val n = (x.size.toLong() * to / from).toInt()
        return FloatArray(n) { i ->
            val p = i.toDouble() * from / to
            val j = p.toInt().coerceAtMost(x.size - 1)
            val k = (j + 1).coerceAtMost(x.size - 1)
            val f = (p - j).toFloat()
            x[j] * (1 - f) + x[k] * f
        }
    }
}
