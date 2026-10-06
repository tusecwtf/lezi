package com.lezi.babylog.feature.log.layout
import com.lezi.babylog.core.model.DEVICE_LAYOUT_SNAPSHOT_VERSION
import com.lezi.babylog.core.model.DeviceLayoutSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.composer.*
import com.lezi.babylog.feature.log.photo.*

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
    fun eachSubmitReturnsItsOwnNormalizedCompletionReceipt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val first = snapshot("pee").copy(quickRecordSlots = listOf("pee"))
        val normalizedFirst = snapshot("pee")
        val second = snapshot("sleep")
        val writer = DeviceLayoutSnapshotWriter(scope) { value ->
            if (value == normalizedFirst) throw IllegalStateException("first failed")
        }

        val firstReceipt = writer.submit(first)
        val secondReceipt = writer.submit(second)

        assertEquals(1L, firstReceipt.sequence)
        assertEquals(normalizedFirst, firstReceipt.snapshot)
        assertTrue(firstReceipt.result.await().isFailure)
        assertEquals(2L, secondReceipt.sequence)
        assertEquals(second, secondReceipt.snapshot)
        assertTrue(secondReceipt.result.await().isSuccess)
        scope.cancel()
    }

    @Test
    fun cancellingAReceiptWaiterDoesNotCancelItsPersistCommand() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val persistStarted = CompletableDeferred<Unit>()
        val releasePersist = CompletableDeferred<Unit>()
        val value = snapshot("pee")
        val writer = DeviceLayoutSnapshotWriter(scope) {
            persistStarted.complete(Unit)
            releasePersist.await()
        }

        val receipt = writer.submit(value)
        persistStarted.await()
        val waiter = launch { receipt.result.await() }
        waiter.cancelAndJoin()
        releasePersist.complete(Unit)

        assertTrue(receipt.result.await().isSuccess)
        assertTrue(writer.flush().isSuccess)
        assertEquals(DeviceLayoutWriteState.Saved(value), writer.state.value)
        scope.cancel()
    }

    @Test
    fun failedAfterCompensationIsVisibleAndRetryRestoresUiDurability() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val before = snapshot("pee")
        val after = snapshot("")
        var durable = after
        var failCompensation = true
        val writer = DeviceLayoutSnapshotWriter(scope) { value ->
            if (value == after && failCompensation) {
                throw IllegalStateException("compensation failed")
            }
            durable = value
        }

        assertTrue(writer.submit(before).result.await().isSuccess)
        val compensation = writer.submit(after)
        assertTrue(compensation.result.await().isFailure)

        assertEquals(before, durable)
        assertEquals(
            "布局没有保存成功，下次进入会用默认布局，可重试",
            layoutWriteAnnouncement(
                prefs = after.toLayoutPrefs(),
                state = writer.state.value,
                hasSubmittedIntent = true,
            ),
        )

        failCompensation = false
        assertTrue(writer.retryLatest().isSuccess)
        assertEquals(after, durable)
        assertEquals(DeviceLayoutWriteState.Saved(after), writer.state.value)
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

    @Test
    fun mismatchedVersionSubmitFailsClosedWithoutThrowing() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var persistCalls = 0
        val writer = DeviceLayoutSnapshotWriter(scope) { persistCalls++ }
        val future = snapshot("pee").copy(version = DEVICE_LAYOUT_SNAPSHOT_VERSION + 1)

        val receipt = writer.submit(future)

        assertTrue(receipt.result.isCompleted)
        assertTrue(receipt.result.await().isFailure)
        assertTrue(writer.state.value is DeviceLayoutWriteState.Failed)
        assertEquals(0, persistCalls)
        assertTrue(writer.retryLatest().isFailure)
        assertEquals(0, persistCalls)
        assertTrue(writer.state.value is DeviceLayoutWriteState.Failed)
        scope.cancel()
    }

    @Test
    fun submitAfterWriterClosedFailsClosedWithoutThrowing() = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Default)
        var persistCalls = 0
        val writer = DeviceLayoutSnapshotWriter(scope) { persistCalls++ }
        job.cancelAndJoin()

        val receipt = writer.submit(snapshot("pee"))

        assertTrue(receipt.result.isCompleted)
        assertTrue(receipt.result.await().isFailure)
        assertTrue(writer.state.value is DeviceLayoutWriteState.Failed)
        assertEquals(0, persistCalls)
        assertFalse(writer.flush().isSuccess)
        assertTrue(writer.state.value is DeviceLayoutWriteState.Failed)
        scope.cancel()
    }

    private fun snapshot(firstSlot: String): DeviceLayoutSnapshot =
        DeviceLayoutSnapshot(quickRecordSlots = listOf(firstSlot, "", "", ""))
}
