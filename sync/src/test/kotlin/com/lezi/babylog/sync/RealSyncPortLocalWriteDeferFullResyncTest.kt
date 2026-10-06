package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.SyncStatus
import com.lezi.babylog.sync.backend.CausalCommitBatchResult
import com.lezi.babylog.sync.backend.CausalCommitStatus
import com.lezi.babylog.sync.backend.CausalCommitUnitResult
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.ShallowSyncState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Public seam: [SyncPort.pendingPublishCount] / [SyncPort.shallowStatus] after a
 * LocalWrite generation-mismatch failure. Publish count stays the dirty atomic
 * units; shallow status still refuses Synced because transport is Error and
 * the independent pending flag is set.
 */
class RealSyncPortLocalWriteDeferFullResyncTest {

    @Test
    fun localWriteGenerationDriftKeepsAtomicUnitCountAndDoesNotReportSynced() = runTest {
        val session = joinedSession("family-a").copy(
            role = FamilyRole.Owner,
            pullCursor = 31,
        )
        val rig = SyncRig(session = session)
        rig.backend.enableCausal = true
        val babyId = rig.babies.seed(
            localBaby().copy(
                syncDirty = false,
                familyAuthority = true,
                baseVersion = "v-baby",
            ),
        )
        rig.records.seed(
            localRecord(babyId).copy(
                clientUuid = "record-port-localwrite-409",
                type = "formula",
                payloadJson = """{"amount_ml":60}""",
                schemaVersion = 2,
                updatedAt = 10,
                syncDirty = true,
                baseVersion = null,
            ),
        )
        rig.backend.onCausalCommit = { units ->
            val unit = units.single()
            rig.backend.nextCausalCommit = CausalCommitBatchResult(
                generation = "other-generation",
                results = listOf(
                    CausalCommitUnitResult(
                        status = CausalCommitStatus.ACCEPTED,
                        mutationId = unit.mutationId,
                        requestHash = "h",
                        stableVersionId = "ignored-drift-version",
                        stableRootJson = unit.rootJson,
                    ),
                ),
            )
        }

        val result = rig.port.sync(SyncTrigger.LocalWrite)

        assertThat(result.isFailure).isTrue()
        assertThat(rig.backend.pullCount).isEqualTo(0)
        assertThat(rig.preferences.hasPendingGenerationResync()).isTrue()
        assertThat(rig.port.pendingPublishCount().first()).isEqualTo(1)
        assertThat(rig.port.pendingGenerationResync().first()).isTrue()
        val line = rig.port.shallowStatus().first()
        assertThat(line.state).isEqualTo(ShallowSyncState.Error)
        assertThat(line.pendingCount).isEqualTo(1)
        assertThat(line.text).isEqualTo("暂时无法同步 · 待同步 1 项 · 下拉重试")
        assertThat(rig.port.status().first()).isEqualTo(SyncStatus.Error)
    }
}
