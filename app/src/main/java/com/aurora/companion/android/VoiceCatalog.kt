package com.aurora.companion.android

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/** A Piper voice: built into the APK (Robert) or an optional, user-approved download. */
data class VoiceOption(
    val id: String,
    val title: String,
    val builtIn: Boolean,
    val modelUrl: String = "",
    val jsonUrl: String = "",
    val modelBytes: Long = 0,
    val modelSha256: String = "",
    val jsonSha256: String = "",
    val noiseScale: Float = 0.667f,
    val noiseScaleW: Float = 0.8f,
    val note: String = "",
)

object VoiceCatalog {
    const val MODEL_FILE = "model.onnx"
    const val TOKENS_FILE = "tokens.txt"
    const val JSON_FILE = "model.onnx.json"

    val ROBERT = VoiceOption(
        id = "robert", title = "Robert (built in)", builtIn = true,
        noiseScale = 0.667f, noiseScaleW = 0.8f,
        note = "Your own custom Piper voice, included in the app.",
    )

    // Same pinned file + checksums as the desktop installer (setup/aurora_setup.py, piper-voices v1.0.0).
    private const val BASE = "https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/en/en_US/libritts_r/medium/"
    val LIBRITTS = VoiceOption(
        id = "libritts_r", title = "LibriTTS-R (904 speakers)", builtIn = false,
        modelUrl = BASE + "en_US-libritts_r-medium.onnx?download=true",
        jsonUrl = BASE + "en_US-libritts_r-medium.onnx.json?download=true",
        modelBytes = 78_580_914L,
        modelSha256 = "10bb85e071d616fcf4071f369f1799d0491492ab3c5d552ec19fb548fac13195",
        jsonSha256 = "b471dc60d2d8335e819c393d196d6fbf792817f40051257b269878505bc9afb3",
        noiseScale = 0.333f, noiseScaleW = 0.333f,
        note = "Third-party Piper voice (rhasspy/piper-voices, trained on the LibriTTS-R dataset, CC BY 4.0). " +
            "Many different speakers, so pick the one you like.",
    )

    val all = listOf(ROBERT, LIBRITTS)
    fun byId(id: String?) = all.firstOrNull { it.id == id } ?: ROBERT

    fun dir(context: Context, v: VoiceOption) = File(context.filesDir, "voices/${v.id}")

    fun isInstalled(context: Context, v: VoiceOption): Boolean {
        if (v.builtIn) return true
        val d = dir(context, v)
        return File(d, MODEL_FILE).isFile && File(d, TOKENS_FILE).isFile && File(d, ".ready").isFile
    }

    /**
     * After the checksums pass: write tokens.txt from the .json (phoneme_id_map) and append the
     * metadata sherpa-onnx needs to the ONNX file. Appending ModelProto.metadata_props (field 14)
     * entries is valid protobuf (repeated fields merge), so the 78 MB file isn't rewritten.
     * Same values as sherpa-onnx scripts/piper/add_meta_data.py.
     */
    fun prepareForSherpa(context: Context, v: VoiceOption) {
        val d = dir(context, v)
        val cfg = JSONObject(File(d, JSON_FILE).readText())
        val idMap = cfg.getJSONObject("phoneme_id_map")
        File(d, TOKENS_FILE).bufferedWriter(Charsets.UTF_8).use { w ->
            for (k in idMap.keys()) {
                if (k == "\n") continue
                val arr = idMap.optJSONArray(k)
                val id = arr?.getInt(0) ?: idMap.getInt(k)
                w.write("$k $id\n")
            }
        }
        val voice = cfg.optString("lang_code", "").ifEmpty { cfg.getJSONObject("espeak").getString("voice") }
        var sampleRate = cfg.getJSONObject("audio").getInt("sample_rate")
        if (sampleRate == 22500) sampleRate = 22050
        val meta = linkedMapOf(
            "model_type" to "vits", "comment" to "piper", "language" to "English", "voice" to voice,
            "version" to "1", "has_espeak" to "1", "has_g2pw" to "0",
            "n_speakers" to cfg.optInt("num_speakers", 1).toString(), "sample_rate" to sampleRate.toString(),
        )
        RandomAccessFile(File(d, MODEL_FILE), "rw").use { raf ->
            raf.seek(raf.length())
            for ((k, value) in meta) raf.write(metaEntry(k, value))
        }
        File(d, ".ready").writeText("ok")
    }

    private fun varint(n0: Int): ByteArray {
        var n = n0
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val b = n and 0x7f
            n = n ushr 7
            if (n != 0) out.write(b or 0x80) else { out.write(b); return out.toByteArray() }
        }
    }

    private fun metaEntry(k: String, v: String): ByteArray {
        val kb = k.toByteArray(); val vb = v.toByteArray()
        val inner = byteArrayOf(0x0a) + varint(kb.size) + kb + byteArrayOf(0x12) + varint(vb.size) + vb
        return byteArrayOf(0x72) + varint(inner.size) + inner
    }
}
