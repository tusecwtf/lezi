package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.sync.backend.RemoteDeviceRemovedException
import com.lezi.babylog.sync.engine.localReplicaBaby
import com.lezi.babylog.sync.session.SyncSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * S3 (0.5.4) cleanup receipt: a remote device removal must persist a loss
 * receipt BEFORE the frozen terminal clear runs, so 「何时被移除、清掉了多少条
 * 未同步」 stays visible instead of silently emptied. The clear semantics
 * themselves are unchanged (sync-trusted-endpoint.md frozen contract).
 */
class RealSyncPortDeviceRemovalReceiptTest {

    @Test
    fun remoteDeviceRemovalPersistsReceiptBeforeClearingLocalData() = runTest {
        val gate = ReceiptObservingClearGate()
        val rig = SyncRig(
            session = joinedSession("family-a"),
            removedDeviceLocalClearGate = gate,
        )
        rig.awaitStartupRecovery()
        gate.receiptSnapshot = { rig.preferences.deviceRemovedReceiptValue }
        seedPendingRecords(rig, pendingCount = 2)
        rig.backend.pullFailures += RemoteDeviceRemovedException()

        val result = rig.port.sync(SyncTrigger.PullToRefresh)

        assertThat(result.exceptionOrNull())
            .isInstanceOf(RemoteDeviceRemovedException::class.java)
        // The clear itself still ran with unchanged semantics...
        assertThat(gate.calls).isEqualTo(1)
        assertThat(rig.preferences.current()).isEqualTo(SyncSession())
        assertThat(rig.preferences.hasPendingDeviceRemovalClear()).isFalse()
        // ...but the receipt was durable first, naming the wiped pending count.
        val receipt = rig.preferences.deviceRemovedReceiptValue
        assertThat(receipt).isNotNull()
        assertThat(receipt!!.clearedPendingCount).isEqualTo(2)
        assertThat(receipt.removedAtEpochMillis).isEqualTo(rig.clock.now)
        assertThat(receipt.reason).isEqualTo(DEVICE_REMOVED_RECEIPT_REASON)
        // Order proof: the clear gate observed the receipt already durable.
        assertThat(gate.receiptSeenAtClear).isEqualTo(receipt)
    }

    @Test
    fun interruptedClearKeepsReceiptAndRetainedRowsForNextRecovery() = runTest {
        val gate = TestRemovedDeviceLocalClearGate()
        gate.failures.addLast(IllegalStateException("local cleanup interrupted"))
        val rig = SyncRig(
            session = joinedSession("family-a"),
            removedDeviceLocalClearGate = gate,
        )
        rig.awaitStartupRecovery()
        seedPendingRecords(rig, pendingCount = 1)
        rig.backend.pullFailures += RemoteDeviceRemovedException()

        assertThat(rig.port.sync(SyncTrigger.PullToRefresh).isFailure).isTrue()

        // Durable-before-act: the receipt survives although the clear did not
        // finish, and the local data is still present for recovery.
        assertThat(rig.preferences.current()).isEqualTo(joinedSession("family-a"))
        assertThat(rig.preferences.deviceRemovedReceiptValue).isNotNull()
        assertThat(rig.preferences.deviceRemovedReceiptValue!!.clearedPendingCount).isEqualTo(1)
    }

    @Test
    fun receiptIsPresentedOnceThenConsumedThroughThePortSeam() = runTest {
        val rig = SyncRig(session = joinedSession("family-a"))
        rig.awaitStartupRecovery()
        rig.backend.pullFailures += RemoteDeviceRemovedException()
        rig.port.sync(SyncTrigger.PullToRefresh)

        val presented = rig.port.deviceRemovedReceipt().first()
        assertThat(presented).isNotNull()
        assertThat(presented!!.reason).isEqualTo(DEVICE_REMOVED_RECEIPT_REASON)

        rig.port.consumeDeviceRemovedReceipt()
        assertThat(rig.port.deviceRemovedReceipt().first()).isNull()
        assertThat(rig.preferences.deviceRemovedReceiptCleared.value).isTrue()
    }

    /** Seeds [pendingCount] dirty records so the receipt has a real count to name. */
    private fun seedPendingRecords(rig: SyncRig, pendingCount: Int) {
        val babyId = rig.babies.seed(localReplicaBaby().copy(syncDirty = false))
        repeat(pendingCount) { index ->
            rig.records.seed(
                RecordEntity(
                    clientUuid = "00000000-0000-4000-8000-00000000530$index",
                    babyId = babyId,
                    type = "formula",
                    timestamp = 5_300L + index,
                    payloadJson = """{"amount_ml":60}""",
                    schemaVersion = 2,
                    updatedAt = 5_300L + index,
                    syncDirty = true,
                ),
            )
        }
    }

    /**
     * Clear gate that snapshots the receipt visible at clear time, proving the
     * durable-before-act ordering without depending on private method names.
     */
    private class ReceiptObservingClearGate : RemovedDeviceLocalClearGate {
        var calls = 0
        var receiptSeenAtClear: DeviceRemovedCleanupReceipt? = null
        var receiptSnapshot: (() -> DeviceRemovedCleanupReceipt?)? = null

        override suspend fun localClearWorkflow(): LocalClearWorkflow {
            calls += 1
            receiptSeenAtClear = receiptSnapshot?.invoke()
        return NoOpRemovedDeviceLocalClearGate().localClearWorkflow()
        }
    }
}
