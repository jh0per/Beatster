package com.datawithart.beatster.data

import com.datawithart.beatster.model.Project
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Projects live in `<root>/<folder>/`: `project.json` plus the audio it references
 * (WAV files under `pads/` for loaded or sampled pad sounds and under `audio/` for recorded clips).
 */
class ProjectStore(private val root: File) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    data class Entry(val folder: String, val name: String, val modified: Long)

    fun list(): List<Entry> {
        val dirs = root.listFiles { f -> f.isDirectory && File(f, PROJECT_FILE).isFile }.orEmpty()
        return dirs.map { dir ->
            val name = runCatching { decode(File(dir, PROJECT_FILE).readText()).name }.getOrDefault(dir.name)
            Entry(dir.name, name, File(dir, PROJECT_FILE).lastModified())
        }.sortedByDescending { it.modified }
    }

    fun dir(folder: String): File = File(root, folder).also { it.mkdirs() }

    /** A folder name derived from [name] that is not used yet. */
    fun newFolder(name: String): String {
        val base = sanitize(name).ifEmpty { "project" }
        var candidate = base
        var n = 2
        while (File(root, candidate).exists()) candidate = "$base-${n++}"
        File(root, candidate).mkdirs()
        return candidate
    }

    fun save(folder: String, project: Project) {
        val dir = dir(folder)
        val tmp = File(dir, "$PROJECT_FILE.tmp")
        tmp.writeText(encode(project))
        // Write-then-rename so a crash mid-save never leaves a half-written project.
        if (!tmp.renameTo(File(dir, PROJECT_FILE))) {
            File(dir, PROJECT_FILE).writeText(encode(project))
            tmp.delete()
        }
    }

    fun load(folder: String): Project? {
        val file = File(File(root, folder), PROJECT_FILE)
        if (!file.isFile) return null
        return runCatching { decode(file.readText()) }.getOrNull()
    }

    /** Copies a project folder (for "save as"). Returns the new folder name. */
    fun copy(fromFolder: String, newName: String): String {
        val target = newFolder(newName)
        val source = File(root, fromFolder)
        if (source.isDirectory) source.copyRecursively(File(root, target), overwrite = true)
        return target
    }

    fun delete(folder: String) {
        File(root, folder).deleteRecursively()
    }

    fun encode(project: Project): String = json.encodeToString(Project.serializer(), project)

    fun decode(text: String): Project = json.decodeFromString(Project.serializer(), text)

    companion object {
        const val PROJECT_FILE = "project.json"

        fun sanitize(name: String): String =
            name.trim().replace(Regex("[^A-Za-z0-9 _\\-\\p{L}]"), "").replace(' ', '_').take(40)
    }
}
