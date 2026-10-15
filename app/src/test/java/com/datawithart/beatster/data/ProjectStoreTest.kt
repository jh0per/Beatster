package com.datawithart.beatster.data

import com.datawithart.beatster.model.Note
import com.datawithart.beatster.model.Project
import com.datawithart.beatster.model.QuantizeGrid
import com.datawithart.beatster.model.TrackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class ProjectStoreTest {
    private val root = Files.createTempDirectory("beatster").toFile()
    private val store = ProjectStore(root)

    @Test
    fun saveAndLoadRoundTripsEverything() {
        val base = Project.default("My beat")
        val project = base.copy(
            bpm = 92.5,
            quantize = QuantizeGrid.EighthTriplet,
            tracks = base.tracks.map {
                if (it.type == TrackType.Synth) it.copy(notes = listOf(Note(7, 1.5, 0.5, 64, 0.7f))) else it
            },
        )
        val folder = store.newFolder(project.name)
        store.save(folder, project)
        assertEquals(project, store.load(folder))
        assertEquals(listOf("My beat"), store.list().map { it.name })
    }

    @Test
    fun foldersAreUniqueAndSafe() {
        val a = store.newFolder("Beat/../1")
        val b = store.newFolder("Beat/../1")
        assertNotEquals(a, b)
        assertEquals(false, a.contains('/'))
    }

    @Test
    fun oldOrForeignJsonStillLoads() {
        val folder = store.newFolder("x")
        root.resolve(folder).resolve(ProjectStore.PROJECT_FILE).writeText("""{"name":"Old","bpm":80.0,"future":1}""")
        val loaded = store.load(folder)!!
        assertEquals("Old", loaded.name)
        assertEquals(16, loaded.pads.size)
        assertNull(store.load("missing"))
    }

    @Test
    fun copyDuplicatesTheFolder() {
        val folder = store.newFolder("A")
        store.save(folder, Project.default("A"))
        root.resolve(folder).resolve("pads").mkdirs()
        root.resolve(folder).resolve("pads/p.wav").writeText("x")
        val copy = store.copy(folder, "B")
        assertEquals("x", root.resolve(copy).resolve("pads/p.wav").readText())
    }
}
