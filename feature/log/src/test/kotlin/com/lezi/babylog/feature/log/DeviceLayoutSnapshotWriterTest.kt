package com.lezi.babylog.feature.log

import com.lezi.babylog.core.model.DeviceLayoutSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceLayoutSnapshotWriterTest {
    @Test
    fun rapidSnapshotsPersistInRequestOrderAndLatestWins() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val releaseFirst = CompletableDeferred<Unit>()
        val persisted = mutableListOf<DeviceLayoutSnapshot>()
        val first = snapshot("pee")
        val second = snapshot("sleep")
        val writer = DeviceLayoutSnapshotWriter(scope) { value ->
            if (value == first) releaseFirst.await()
            synchronized(persisted) { persisted += value }
        }

        writer.submit(first)
        writer.submit(second)
        releaseFirst.complete(Unit)
        val result = writer.flush()

        assertTrue(result.isSuccess)
        assertEquals(listOf(first, second), synchronized(persisted) { persisted.toList() })
        assertEquals(DeviceLayoutWriteState.Saved(second), writer.state.value)
        scope.cancel()
    }

    @Test
    fun failedLatestSnapshotStaysRetryableAndPreviousValueRemainsValid() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val previous = snapshot("pee")
        val next = snapshot("sleep")
        var durable = previous
        var fail = true
        val writer = DeviceLayoutSnapshotWriter(scope) { value ->
            if (fail) throw IllegalStateException("disk full")
            durable = value
        }

        writer.submit(next)
        val failed = writer.flush()

        assertTrue(failed.isFailure)
        assertEquals(previous, durable)
        assertTrue(writer.state.value is DeviceLayoutWriteState.Failed)

        fail = false
        val retried = writer.retryLatest()

        assertTrue(retried.isSuccess)
        assertEquals(next, durable)
        assertEquals(DeviceLayoutWriteState.Saved(next), writer.state.value)
        scope.cancel()
    }

    @Test
    fun newerSuccessSupersedesAnOlderFailureWithoutReplayingIt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val persisted = mutableListOf<DeviceLayoutSnapshot>()
        val first = snapshot("pee")
        val second = snapshot("sleep")
        val writer = DeviceLayoutSnapshotWriter(scope) { value ->
            if (value == first) throw IllegalStateException("first failed")
            persisted += value
        }

        writer.submit(first)
        writer.submit(second)
        val result = writer.flush()

        assertTrue(result.isSuccess)
        assertEquals(listOf(second), persisted)
        assertEquals(DeviceLayoutWriteState.Saved(second), writer.state.value)
        scope.cancel()
    }

    private fun snapshot(firstSlot: String): DeviceLayoutSnapshot =
        DeviceLayoutSnapshot(quickRecordSlots = listOf(firstSlot, "", "", ""))
}
