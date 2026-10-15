package com.datawithart.beatster.audio

import com.datawithart.beatster.engine.Pcm
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Minimal RIFF/WAVE reader (PCM 8/16/24/32-bit, float 32/64) and 16-bit writer. */
object WavCodec {
    private const val FORMAT_PCM = 1
    private const val FORMAT_FLOAT = 3
    private const val FORMAT_EXTENSIBLE = 0xFFFE

    fun isWav(bytes: ByteArray): Boolean =
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE"

    /** Returns null for anything that is not a WAV file we understand. */
    fun decode(bytes: ByteArray): Pcm? {
        if (!isWav(bytes)) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var format = -1
        var channels = 0
        var sampleRate = 0
        var bits = 0
        var dataOffset = -1
        var dataSize = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = buf.getInt(pos + 4)
            val body = pos + 8
            if (size < 0) return null
            when (id) {
                "fmt " -> {
                    if (size < 16 || body + 16 > bytes.size) return null
                    format = buf.getShort(body).toInt() and 0xFFFF
                    channels = buf.getShort(body + 2).toInt()
                    sampleRate = buf.getInt(body + 4)
                    bits = buf.getShort(body + 14).toInt()
                    if (format == FORMAT_EXTENSIBLE && size >= 26 && body + 26 <= bytes.size) {
                        format = buf.getShort(body + 24).toInt() and 0xFFFF
                    }
                }
                "data" -> {
                    dataOffset = body
                    // Some writers leave the size at 0 or too big when streaming; clamp to the file.
                    dataSize = if (size == 0 || body.toLong() + size > bytes.size) bytes.size - body else size
                }
            }
            if (dataOffset >= 0 && format >= 0) break
            pos = body + size + (size and 1)
        }
        if (dataOffset < 0 || channels <= 0 || sampleRate <= 0) return null
        val bytesPerSample = bits / 8
        if (bytesPerSample <= 0) return null
        val frameBytes = bytesPerSample * channels
        val frames = dataSize / frameBytes
        val out = FloatArray(frames * channels)
        var p = dataOffset
        for (i in out.indices) {
            out[i] = when {
                format == FORMAT_PCM && bits == 8 -> ((bytes[p].toInt() and 0xFF) - 128) / 128f
                format == FORMAT_PCM && bits == 16 -> buf.getShort(p) / 32768f
                format == FORMAT_PCM && bits == 24 -> {
                    val v = (bytes[p].toInt() and 0xFF) or ((bytes[p + 1].toInt() and 0xFF) shl 8) or
                        (bytes[p + 2].toInt() shl 16)
                    v / 8388608f
                }
                format == FORMAT_PCM && bits == 32 -> (buf.getInt(p) / 2147483648.0).toFloat()
                format == FORMAT_FLOAT && bits == 32 -> buf.getFloat(p)
                format == FORMAT_FLOAT && bits == 64 -> buf.getDouble(p).toFloat()
                else -> return null
            }
            p += bytesPerSample
        }
        return Pcm(out, channels, sampleRate)
    }

    /** Encodes interleaved float audio as 16-bit PCM WAV. */
    fun encode16(pcm: Pcm): ByteArray {
        val dataBytes = pcm.samples.size * 2
        val buf = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + dataBytes)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)
        buf.putShort(FORMAT_PCM.toShort())
        buf.putShort(pcm.channels.toShort())
        buf.putInt(pcm.sampleRate)
        buf.putInt(pcm.sampleRate * pcm.channels * 2)
        buf.putShort((pcm.channels * 2).toShort())
        buf.putShort(16)
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataBytes)
        for (s in pcm.samples) {
            buf.putShort((s.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort())
        }
        return buf.array()
    }
}
