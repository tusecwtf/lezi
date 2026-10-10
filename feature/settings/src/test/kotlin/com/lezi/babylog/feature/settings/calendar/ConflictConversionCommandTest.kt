package com.lezi.babylog.feature.settings.calendar

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ConflictConversionCommandTest {
    @Test fun refreshFailurePreservesCommittedOutcomeAndRetryOnlyRefreshes() = runBlocking {
        val records = mutableListOf<Long>()
        var readable = false
        val command = ConflictConversionCommand(
            convert = { (records.size + 1L).also { records += it } },
            refresh = { if (!readable) error("database read failed") },
        )
        command.convert("candidate-a")
        assertEquals(1L, command.state.value.recordId)
        assertNull(command.state.value.saveError)
        assertNotNull(command.state.value.refreshWarning)
        readable = true
        command.convert("candidate-a")
        assertEquals(listOf(1L), records)
        assertNull(command.state.value.refreshWarning)
    }
}
