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
        // Legacy dual-master completion path must stay deleted.
        assertFalse("fun dispatchRecordSaveCompletion(" in host)
        assertFalse("fun dispatchRecordSaveCompletion(" in completion)
        assertFalse("fun dispatchRecordSaveCompletion(" in viewModel)

        assertTrue("sealed interface RecordComposerRequest" in contract)
        assertTrue("class RecordComposerSavedState" in contract)
        assertTrue("class RecordComposerViewModel" in viewModel)
        assertTrue("fun reconcileNextFeedPlan(" in viewModel)
        // Pure post-save entrypoints are the single completion pipeline.
        assertTrue("fun applyComposerPostSaveOutcome(" in completion)
        assertTrue("fun consumeComposerPostSavePresentation(" in completion)
        assertTrue("fun composerPostSaveOutcome(" in completion)
        assertTrue("fun persistComposerPostSave(" in completion)
        assertTrue("fun mapComposerPostSaveUiState(" in completion)
        assertTrue("fun shouldOpenComposerWriteSession(" in completion)
        assertTrue("class RecordComposerSessionGate" in sessionGate)
        // Composer path keeps one explicit complete path (no orphan dismissNextFeedPlan).
        assertFalse("fun dismissNextFeedPlan(" in viewModel)
        assertTrue("fun completeNextFeedOffer(" in viewModel)

        val splitSources = ownedSources.joinToString("\n")
        assertEquals(1, splitSources.occurrences("fun RecordComposerHost("))
        assertEquals(1, splitSources.occurrences("class RecordComposerViewModel"))
        assertEquals(1, splitSources.occurrences("sealed interface RecordComposerRequest"))
        assertEquals(1, splitSources.occurrences("class RecordComposerSavedState"))
        assertEquals(1, splitSources.occurrences("class RecordComposerSessionGate"))
        assertEquals(1, splitSources.occurrences("fun applyComposerPostSaveOutcome("))
        assertEquals(1, splitSources.occurrences("fun consumeComposerPostSavePresentation("))
        assertEquals(0, splitSources.occurrences("fun dispatchRecordSaveCompletion("))
    }

    private fun source(name: String): String = sourceDir.resolve(name).readText()

    private fun String.occurrences(needle: String): Int =
        windowed(size = needle.length, step = 1).count { it == needle }

    private fun repositoryRoot(): File = generateSequence(
        seed = File(requireNotNull(System.getProperty("user.dir"))),
        nextFunction = { it.parentFile },
    ).first { candidate -> candidate.resolve("settings.gradle.kts").isFile }
}
