package com.datawithart.beatster.audio

import android.content.ContentResolver
import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap

data class LoadedSample(
    val id: Int,
    val label: String
)

/**
 * Small wrapper around SoundPool so we can trigger pads with low latency.
 */
class AudioEngine(private val context: Context) {

    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(16)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build()
        )
        .build()

    private val loadedSamples = ConcurrentHashMap<Int, Boolean>()
    private val activeLoops = ConcurrentHashMap<Int, Int>()
    private val tempFiles = ConcurrentHashMap<Int, File>()

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            loadedSamples[sampleId] = status == 0
            tempFiles.remove(sampleId)?.delete()
        }
    }

    fun load(uri: Uri): LoadedSample? {
        val descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r") ?: return null
        descriptor.use { afd ->
            val id = soundPool.load(afd, 1)
            loadedSamples[id] = true
            val label = uri.displayName(context.contentResolver) ?: "Sample $id"
            return LoadedSample(id = id, label = label)
        }
    }

    fun load(file: File, keepFile: Boolean = false): LoadedSample? {
        if (!file.exists()) return null
        FileInputStream(file).use { fis ->
            val id = soundPool.load(fis.fd, 0, file.length(), 1)
            loadedSamples[id] = true
            val label = file.nameWithoutExtension.ifEmpty { "Sample $id" }
            if (keepFile) {
                tempFiles[id] = file
            }
            return LoadedSample(id = id, label = label)
        }
    }

    fun loadAsset(path: String): LoadedSample? {
        val label = path.substringAfterLast('/').substringBeforeLast('.').ifEmpty { "Sample" }
        // Prefer direct FD; fall back to temp file if asset is compressed.
        try {
            context.assets.openFd(path).use { afd ->
                return try {
                    val id = soundPool.load(afd, 1)
                    LoadedSample(id = id, label = label)
                } catch (_: Exception) {
                    null
                }
            }
        } catch (_: Exception) {
            // try copy fallback
        }
        return loadAssetViaCopy(path, label)
    }

    fun loadAssetViaCopy(path: String, label: String): LoadedSample? {
        return try {
            val tmp = File.createTempFile("sample_", ".tmp", context.cacheDir)
            context.assets.open(path).use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            load(tmp, keepFile = true)?.copy(label = label)
        } catch (_: Exception) {
            null
        }
    }

    fun play(sampleId: Int) {
        soundPool.play(sampleId, 1f, 1f, 1, 0, 1f)
    }

    fun playLoop(sampleId: Int, volume: Float = 1f): Int? {
        val streamId = soundPool.play(sampleId, volume, volume, 1, -1, 1f)
        return if (streamId != 0) {
            activeLoops[sampleId] = streamId
            streamId
        } else {
            null
        }
    }

    fun stopLoop(sampleId: Int) {
        activeLoops.remove(sampleId)?.let { streamId ->
            soundPool.stop(streamId)
        }
    }

    fun stopAllLoops() {
        activeLoops.values.forEach { streamId ->
            soundPool.stop(streamId)
        }
        activeLoops.clear()
    }

    fun unload(sampleId: Int) {
        soundPool.unload(sampleId)
        loadedSamples.remove(sampleId)
        tempFiles.remove(sampleId)?.delete()
        stopLoop(sampleId)
    }

    fun release() {
        stopAllLoops()
        soundPool.release()
        loadedSamples.clear()
        tempFiles.values.forEach { it.delete() }
        tempFiles.clear()
    }
}

private fun Uri.displayName(contentResolver: ContentResolver): String? {
    return contentResolver.query(this, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst() && nameIndex >= 0) {
            cursor.getString(nameIndex)
        } else {
            null
        }
    }
}
