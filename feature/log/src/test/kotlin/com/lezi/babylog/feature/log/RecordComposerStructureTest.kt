package com.lezi.babylog.feature.log

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordComposerStructureTest {
    private val sourceDir: File by lazy {
        repositoryRoot().resolve("feature/log/src/main/kotlin/com/lezi/babylog/feature/log")
    }

    @Test
    fun requestStateViewModelHostAndCompletionHaveDedicatedUnits() {
        val host = source("RecordComposer.kt")
        val contract = source("RecordComposerContract.kt")
        val viewModel = source("RecordComposerViewModel.kt")
        val completion = source("RecordComposerCompletion.kt")
        val sessionGate = source("RecordComposerSessionGate.kt")

        val ownedSources = listOf(host, contract, viewModel, completion, sessionGate)
        ownedSources.forEach { source ->
            assertTrue(source.lineSequence().count() < 1_000)
        }

        assertTrue("fun RecordComposerHost(" in host)
        assertFalse("class RecordComposerViewModel" in host)
        assertFalse("sealed interface RecordComposerRequest" in host)
        assertFalse("class RecordComposerSavedState" in host)
        assertFalse("fun dispatchRecordSaveCompletion(" in host)

        assertTrue("sealed interface RecordComposerRequest" in contract)
        assertTrue("class RecordComposerSavedState" in contract)
        assertTrue("class RecordComposerViewModel" in viewModel)
        assertTrue("fun reconcileNextFeedPlan(" in viewModel)
        assertTrue("fun dispatchRecordSaveCompletion(" in completion)
        assertTrue("class RecordComposerSessionGate" in sessionGate)

        val splitSources = ownedSources.joinToString("\n")
        assertEquals(1, splitSources.occurrences("fun RecordComposerHost("))
        assertEquals(1, splitSources.occurrences("class RecordComposerViewModel"))
        assertEquals(1, splitSources.occurrences("sealed interface RecordComposerRequest"))
        assertEquals(1, splitSources.occurrences("class RecordComposerSavedState"))
        assertEquals(1, splitSources.occurrences("class RecordComposerSessionGate"))
        assertEquals(1, splitSources.occurrences("fun dispatchRecordSaveCompletion("))
    }

    private fun source(name: String): String = sourceDir.resolve(name).readText()

    private fun String.occurrences(needle: String): Int =
        windowed(size = needle.length, step = 1).count { it == needle }

    private fun repositoryRoot(): File = generateSequence(
        seed = File(requireNotNull(System.getProperty("user.dir"))),
        nextFunction = { it.parentFile },
    ).first { candidate -> candidate.resolve("settings.gradle.kts").isFile }
}
