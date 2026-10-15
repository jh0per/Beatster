package com.datawithart.beatster.audio

import com.datawithart.beatster.engine.Pcm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavCodecTest {
    @Test
    fun sixteenBitRoundTrip() {
        val samples = floatArrayOf(0f, 0.5f, -0.5f, 0.999f, -1f, 0.25f)
        val bytes = WavCodec.encode16(Pcm(samples, 2, 44_100))
        val decoded = WavCodec.decode(bytes)!!
        assertEquals(2, decoded.channels)
        assertEquals(44_100, decoded.sampleRate)
        assertEquals(3, decoded.frames)
        for (i in samples.indices) assertEquals(samples[i], decoded.samples[i], 1f / 16_000)
    }

    @Test
    fun readsFloatAnd24BitData() {
        assertEquals(0.75f, WavCodec.decode(wav(3, 32, 1, floatBytes(0.75f)))!!.samples[0], 1e-6f)
        // 0x400000 = 0.5 in 24-bit
        assertEquals(0.5f, WavCodec.decode(wav(1, 24, 1, byteArrayOf(0, 0, 0x40)))!!.samples[0], 1e-6f)
        assertEquals(-0.5f, WavCodec.decode(wav(1, 24, 1, byteArrayOf(0, 0, 0xC0.toByte())))!!.samples[0], 1e-6f)
    }

    @Test
    fun rejectsNonWav() {
        assertNull(WavCodec.decode(ByteArray(64)))
        assertNull(WavCodec.decode("RIFF....WAVEjunk".toByteArray()))
    }

    @Test
    fun decodesTheBundledKit() {
        val dir = listOf(File("src/main/assets/samples"), File("app/src/main/assets/samples")).first { it.isDirectory }
        val files = dir.walkTopDown().filter { it.extension == "wav" }.toList()
        assertEquals(16, files.size)
        for (f in files) {
            val pcm = WavCodec.decode(f.readBytes())
            assertNotNull(f.path, pcm)
            assertEquals(44_100, pcm!!.sampleRate)
            assertTrue(f.path, pcm.frames > 1000)
        }
    }

    private fun floatBytes(v: Float) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v).array()

    private fun wav(format: Int, bits: Int, channels: Int, data: ByteArray): ByteArray {
        val b = ByteBuffer.allocate(44 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + data.size); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(format.toShort()); b.putShort(channels.toShort())
        b.putInt(48_000); b.putInt(48_000 * channels * bits / 8); b.putShort((channels * bits / 8).toShort())
        b.putShort(bits.toShort())
        b.put("data".toByteArray()); b.putInt(data.size); b.put(data)
        return b.array()
    }
}
