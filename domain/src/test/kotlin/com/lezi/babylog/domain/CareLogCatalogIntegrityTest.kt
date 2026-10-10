package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CareLogCatalogIntegrityTest {
    @Test
    fun rollbackDeleteKeepsNewTombstonePendingWhenOldPublicationArrivesAndCanPublishAgain() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        care.createBaby(CreateBabyInput(nickname = "测试宝宝", birthdayEpochDay = 1))
        val id = care.addCustomItem("测试项目", 1)
        // A one-hour lead models clock rollback within the server's 24h future-skew
        // allowance, rather than relying on an unpublishable Long.MAX_VALUE epoch.
        val oldEpoch = System.currentTimeMillis() + 3_600_000L
        val original = fakes.customItems.getById(id)!!.copy(updatedAt = oldEpoch)
        fakes.customItems.update(original)
        assertThat(fakes.customItems.freezeCommitFirstEpoch(original.clientUuid, oldEpoch, "old-publish"))
            .isNotNull()
        care.deleteCustomItem(id)
        val deleted = fakes.customItems.getById(id)!!
        assertThat(deleted.updatedAt).isEqualTo(oldEpoch + 1)
        assertThat(deleted.deletedAt).isEqualTo(deleted.updatedAt)
        assertThat(fakes.customItems.settleCommitFirstAcceptedOrMerged(
            original.clientUuid, "old-publish", oldEpoch, "old-server-version",
        )).isEqualTo(com.lezi.babylog.core.database.causal.CommitFirstSettlementEpoch.SupersededEpoch)
        val afterOldReceipt = fakes.customItems.getById(id)!!
        assertThat(afterOldReceipt.deletedAt).isEqualTo(deleted.deletedAt)
        assertThat(afterOldReceipt.updatedAt).isEqualTo(deleted.updatedAt)
        assertThat(afterOldReceipt.syncDirty).isTrue()
        assertThat(afterOldReceipt.mutationId).isNull()
        assertThat(fakes.customItems.freezeCommitFirstEpoch(
            original.clientUuid, deleted.updatedAt, "delete-publish",
        )).isNotNull()
        assertThat(fakes.customItems.settleCommitFirstAcceptedOrMerged(
            original.clientUuid, "delete-publish", deleted.updatedAt, "delete-server-version",
        )).isEqualTo(com.lezi.babylog.core.database.causal.CommitFirstSettlementEpoch.CurrentEpoch)
        val settled = fakes.customItems.getById(id)!!
        assertThat(settled.deletedAt).isEqualTo(deleted.deletedAt)
        assertThat(settled.syncDirty).isFalse()
        assertThat(settled.mutationId).isNull()
        assertThat(care.observeCustomItems().first()).isEmpty()
    }

    @Test
    fun revisionAdvancesForSameMillisecondAndRollbackCandidates() {
        for (candidate in listOf(1_000L, 999L)) {
            assertThat(nextSyncUpdatedAt(1_000L, candidate)).isEqualTo(1_001L)
        }
    }

    @Test
    fun editingADeletedDefinitionFailsInsteadOfReportingSaved() = runTest {
        val care = Fakes().careLog()
        care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        val id = care.addCustomItem("运动", 1)
        val draft = care.observeCustomItems().first().single { it.id == id }.copy(name = "练习")
        care.deleteCustomItem(id)
        assertThat(runCatching { care.updateCustomItem(draft) }.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(care.observeCustomItems().first()).isEmpty()
    }

    @Test
    fun deletionRechecksTheCurrentDefinitionInsideTheCommit() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        val id = care.addCustomItem("运动", 1)
        val draft = care.observeCustomItems().first().single { it.id == id }
        fakes.transactions.beforeNextRun = {
            fakes.customItems.update(fakes.customItems.getById(id)!!.copy(
                name = "家庭已修改", updatedAt = Long.MAX_VALUE - 100,
            ))
        }
        care.deleteCustomItem(id)
        assertThat(care.observeCustomItems().first()).isEmpty()
        assertThat(runCatching { care.updateCustomItem(draft) }.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
    }
}
