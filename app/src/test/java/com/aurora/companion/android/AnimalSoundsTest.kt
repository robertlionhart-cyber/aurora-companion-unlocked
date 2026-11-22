package com.aurora.companion.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AnimalSoundsTest {
    private val dir = listOf("src/main/assets/sounds", "app/src/main/assets/sounds").map(::File).first { it.isDirectory }

    @Test fun catAndDogLooks() {
        assertEquals("cat", AnimalSounds.kindFor("leo"))
        assertEquals("dog", AnimalSounds.kindFor("barry"))
        assertNull(AnimalSounds.kindFor("aurora"))
        assertNull(AnimalSounds.kindFor("pebbie"))
        assertNull(AnimalSounds.kindFor(null))
    }

    @Test fun clipsAreShortGentleAndReadable() {
        val clips = dir.listFiles { f -> f.name.endsWith(".wav") }!!.sortedBy { it.name }
        assertEquals(9, clips.size)
        for (f in clips) {
            val (pcm, rate) = AnimalSounds.decodeWav(f.readBytes())
            assertEquals(f.name, 22050, rate)
            val sec = pcm.size / rate.toFloat()
            assertTrue("${f.name} is $sec s", sec in 0.4f..1.5f)
            val peak = pcm.maxOf { kotlin.math.abs(it) }
            assertTrue("${f.name} peak $peak", peak in 0.05f..0.75f)
        }
    }

    @Test fun resampleKeepsLength() {
        val x = FloatArray(22050) { (it % 100) / 100f }
        assertEquals(22050, AnimalSounds.resample(x, 22050, 22050).size)
        assertEquals(24000, AnimalSounds.resample(x, 22050, 24000).size)
        assertEquals(16000, AnimalSounds.resample(x, 22050, 16000).size)
    }
}
