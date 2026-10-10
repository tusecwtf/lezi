package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SyncPortCapabilityTest {
    @Test
    fun disabledSyncStillExecutesItsImplementedLocalClearWorkflow() = runTest {
        var localFactsPresent = true
        var committedCleanupFinished = false
        val workflow = object : LocalClearWorkflow {
            override suspend fun <T> withLocalExclusion(block: suspend () -> T): T = block()
            override suspend fun clearRoom() { localFactsPresent = false }
            override suspend fun finishCommitted() { committedCleanupFinished = true }
        }
        val port: SyncPort = NoOpSyncPort()
        port.clearLocalData(com.lezi.babylog.core.database.LocalDataClearScope.AllLocalData, workflow)
            .getOrThrow()
        assertThat(localFactsPresent).isFalse()
        assertThat(committedCleanupFinished).isTrue()
        assertThat(port.forgetEndpoint().isFailure).isTrue()
    }

    @Test
    fun unimplementedSafetyActionsCannotReportSuccess() = runTest {
        val port: SyncPort = NoOpSyncPort()
        val results = listOf(
            port.forgetEndpoint(),
            port.abandonRejectedMutation("record", "record-1"),
            port.abandonPendingLocalMutations(emptyList()),
            port.dismissUnresolvedLocally("record", "record-1", UnresolvedLocalKind.Rejected),
        )
        results.forEach { result ->
            assertThat(result.exceptionOrNull()).isInstanceOf(UnsupportedOperationException::class.java)
        }
    }
}
