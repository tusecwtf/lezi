package com.lezi.babylog.feature.log

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogScreenStructureTest {
    private val sourceDir: File by lazy {
        repositoryRoot().resolve("feature/log/src/main/kotlin/com/lezi/babylog/feature/log")
    }

    @Test
    fun routeTimelineDockAndStateHaveDedicatedUnits() {
        val host = source("LogScreen.kt")
        val timeline = source("LogTimeline.kt")
        val dock = source("LogQuickDock.kt")
        val state = source("LogViewModel.kt")

        assertTrue(host.lineSequence().count() < 1_500)
        assertTrue("fun LogRoute(" in host)
        assertFalse("class LogViewModel" in host)
        assertFalse("fun buildTimelineLanes(" in host)
        assertFalse("fun OneHandQuickDock(" in host)

        assertTrue("class LogViewModel" in state)
        assertTrue("fun buildTimelineLanes(" in timeline)
        assertTrue("fun OneHandQuickDock(" in dock)

        val splitSources = listOf(host, timeline, dock, state).joinToString("\n")
        assertEquals(1, splitSources.occurrences("fun LogRoute("))
        assertEquals(1, splitSources.occurrences("class LogViewModel"))
        assertEquals(1, splitSources.occurrences("fun buildTimelineLanes("))
        assertEquals(1, splitSources.occurrences("fun OneHandQuickDock("))
    }

    private fun source(name: String): String = sourceDir.resolve(name).readText()

    private fun String.occurrences(needle: String): Int =
        windowed(size = needle.length, step = 1).count { it == needle }

    private fun repositoryRoot(): File = generateSequence(
        seed = File(requireNotNull(System.getProperty("user.dir"))),
        nextFunction = { it.parentFile },
    ).first { candidate -> candidate.resolve("settings.gradle.kts").isFile }
}
