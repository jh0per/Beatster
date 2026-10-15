package com.datawithart.beatster.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.datawithart.beatster.engine.Pcm
import java.io.File
import java.nio.ByteOrder

/**
 * Turns bundled assets, project files and picked documents into [Pcm]. WAV is parsed directly;
 * anything else (mp3, m4a, ogg, flac...) goes through MediaExtractor + MediaCodec.
 */
class AudioDecoder(private val context: Context) {

    fun decodeAsset(path: String): Pcm? = runCatching {
        val bytes = context.assets.open(path).use { it.readBytes() }
        WavCodec.decode(bytes) ?: decodeWithCodec { it.setDataSource(context.assets.openFd(path)) }
    }.onFailure { Log.w(TAG, "asset $path", it) }.getOrNull()

    fun decodeFile(file: File): Pcm? = runCatching {
        val bytes = file.readBytes()
        WavCodec.decode(bytes) ?: decodeWithCodec { it.setDataSource(file.absolutePath) }
    }.onFailure { Log.w(TAG, "file $file", it) }.getOrNull()

    fun decodeUri(uri: Uri): Pcm? = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        bytes?.let(WavCodec::decode) ?: decodeWithCodec { it.setDataSource(context, uri, null) }
    }.onFailure { Log.w(TAG, "uri $uri", it) }.getOrNull()

    private fun decodeWithCodec(setSource: (MediaExtractor) -> Unit): Pcm? {
        val extractor = MediaExtractor()
        try {
            setSource(extractor)
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                return drain(extractor, codec, format)
            } finally {
                codec.stop()
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun drain(extractor: MediaExtractor, codec: MediaCodec, inputFormat: MediaFormat): Pcm? {
        var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var floatOutput = false
        val out = FloatList()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        while (!outputDone) {
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val buffer = codec.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floatOutput = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                }
                outIndex >= 0 -> {
                    val buffer = codec.getOutputBuffer(outIndex)!!.order(ByteOrder.nativeOrder())
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    if (floatOutput) {
                        val fb = buffer.asFloatBuffer()
                        while (fb.hasRemaining()) out.add(fb.get())
                    } else {
                        val sb = buffer.asShortBuffer()
                        while (sb.hasRemaining()) out.add(sb.get() / 32768f)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    if (out.size > MAX_SAMPLES) outputDone = true
                }
            }
        }
        if (out.size == 0 || channels <= 0) return null
        return Pcm(out.toArray(), channels, sampleRate)
    }

    /** Growable float array without boxing. */
    private class FloatList {
        private var data = FloatArray(1 shl 16)
        var size = 0
            private set

        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }

    private companion object {
        const val TAG = "AudioDecoder"
        const val TIMEOUT_US = 10_000L
        // ~10 minutes of stereo 48 kHz; pads and clips never need more.
        const val MAX_SAMPLES = 48_000 * 2 * 600
    }
}
