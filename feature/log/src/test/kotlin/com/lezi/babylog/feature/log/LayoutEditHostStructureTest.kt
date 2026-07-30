package com.lezi.babylog.feature.log

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutEditHostStructureTest {
    private val sourceDir: File by lazy {
        repositoryRoot().resolve("feature/log/src/main/kotlin/com/lezi/babylog/feature/log")
    }

    @Test
    fun alternativeInputHasDedicatedUnit() {
        val host = source("LayoutEditMode.kt")
        val alternativeInput = source("LayoutEditAlternativeInput.kt")

        assertFalse("fun Modifier.layoutAlternativeInput(" in host)
        assertFalse("fun catalogActions(" in host)
        assertFalse("fun categoryActions(" in host)
        assertFalse("fun slotActions(" in host)
        assertTrue("fun Modifier.layoutAlternativeInput(" in alternativeInput)
        assertTrue("fun catalogActions(" in alternativeInput)
        assertTrue("fun categoryActions(" in alternativeInput)
        assertTrue("fun slotActions(" in alternativeInput)
    }

    @Test
    fun catalogAndLocalDeletedHaveDedicatedUnit() {
        val host = source("LayoutEditMode.kt")
        val catalog = source("LayoutEditCatalogSurface.kt")

        assertFalse("fun LayoutEditCatalogSurface(" in host)
        assertFalse("fun LayoutCatalogGrid(" in host)
        assertTrue("fun LayoutEditCatalogSurface(" in catalog)
        assertTrue("fun LayoutCatalogGrid(" in catalog)
        assertTrue("layout_edit_local_deleted" in catalog)
        assertFalse("layout_edit_local_deleted" in host)
    }

    @Test
    fun dockHasDedicatedUnit() {
        val host = source("LayoutEditMode.kt")
        val dock = source("LayoutEditDock.kt")

        assertFalse("fun LauncherEditDock(" in host)
        assertTrue("fun LauncherEditDock(" in dock)
        assertTrue("layout_edit_more_locked" in dock)
        assertFalse("layout_edit_more_locked" in host)
    }

    @Test
    fun hostOnlyOwnsTheSingleCanvasOrchestrator() {
        val host = source("LayoutEditMode.kt")
        val splitSources = listOf(
            host,
            source("LayoutEditAlternativeInput.kt"),
            source("LayoutEditCatalogSurface.kt"),
            source("LayoutEditDock.kt"),
        ).joinToString("\n")

        assertTrue(host.lineSequence().count() < 1_000)
        assertEquals(1, splitSources.occurrences("fun LayoutEditCanvas("))
        assertEquals(1, splitSources.occurrences("fun LayoutEditCatalogSurface("))
        assertEquals(1, splitSources.occurrences("fun LauncherEditDock("))
        assertEquals(1, splitSources.occurrences("fun Modifier.layoutAlternativeInput("))
    }

    private fun source(name: String): String = sourceDir.resolve(name).readText()

    private fun String.occurrences(needle: String): Int =
        windowed(size = needle.length, step = 1).count { it == needle }

    private fun repositoryRoot(): File = generateSequence(
        seed = File(requireNotNull(System.getProperty("user.dir"))),
        nextFunction = { it.parentFile },
    ).first { candidate -> candidate.resolve("settings.gradle.kts").isFile }
}
