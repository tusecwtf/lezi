package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.SyncStatus
import com.lezi.babylog.sync.SyncTrigger
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * H31 primary acceptance seam:
 * CareLog → SyncPort/RealSyncPort → ReplicaSyncEngine → real isolated lezi-sync
 * → peer Room/domain.
 *
 * Requires a current lezi-sync binary (shared cargo target-dir, tools/lezi-sync/target,
 * or LEZI_SYNC_BIN). Never touches family NAS paths or production certificates.
 */
class CareLogRealServerSeamTest {

    @Test
    fun ownerCareLogNoMediaCreateAndEditSettlesOnPeerWithoutLocalWritePull() = runBlocking {
        CareLogRealServerSeamFixture.open().use { fixture ->
            val owner = fixture.owner
            val member = fixture.member

            // --- Owner creates baby + no-media formula record via CareLog ---
            val babyId = owner.careLog.createBaby(
                CreateBabyInput(
                    nickname = "Lele",
                    birthdayEpochDay = 20_000L,
                ),
            )
            owner.settleLocalWrite()
            val baby = requireNotNull(owner.fakes.babies.get(babyId))
            assertThat(baby.syncDirty).isFalse()
            assertThat(baby.baseVersion).isNotNull()

            val createCursor = owner.currentSession().pullCursor
            val createGeneration = owner.currentSession().pullGeneration
            val recordClientUuid = "11111111-1111-4111-8111-111111111111"
            val createTs = owner.clock.nowMillis() - 60_000L
            val recordId = owner.careLog.addRecord(
                babyId = babyId,
                type = RecordType.FORMULA,
                timestamp = createTs,
                note = "first",
                payloadJson = formulaPayloadJson(amountMl = 90),
                nowMillis = owner.clock.nowMillis(),
                clientUuid = recordClientUuid,
            )
            // LocalWrite path must settle without pulling / advancing cursor.
            owner.settleLocalWrite()

            val settledCreate = requireNotNull(
                owner.fakes.records.getByClientUuid(recordClientUuid),
            )
            assertThat(settledCreate.id).isEqualTo(recordId)
            assertThat(settledCreate.syncDirty).isFalse()
            assertThat(settledCreate.mutationId).isNull()
            assertThat(settledCreate.baseVersion).isNotNull()
            assertThat(settledCreate.note).isEqualTo("first")
            assertThat(settledCreate.payloadJson).contains("\"amount_ml\":90")
            val createVersion = requireNotNull(settledCreate.baseVersion)
            assertThat(owner.currentSession().pullCursor).isEqualTo(createCursor)
            assertThat(owner.currentSession().pullGeneration).isEqualTo(createGeneration)
            assertThat(owner.port.status().first()).isEqualTo(SyncStatus.Idle)

            // --- Member pulls canonical fact into Room/domain ---
            member.pullForeground()
            val memberRecord = requireNotNull(
                member.fakes.records.getByClientUuid(recordClientUuid),
            )
            assertThat(memberRecord.syncDirty).isFalse()
            assertThat(memberRecord.baseVersion).isEqualTo(createVersion)
            assertThat(memberRecord.note).isEqualTo("first")
            assertThat(memberRecord.payloadJson).contains("\"amount_ml\":90")
            assertThat(memberRecord.createdByMembershipId)
                .isEqualTo(owner.currentSession().membershipId)
            val memberBaby = requireNotNull(
                member.fakes.babies.listAll().firstOrNull { it.clientUuid == baby.clientUuid },
            )
            assertThat(memberBaby.nickname).isEqualTo("Lele")
            val domainRecord = requireNotNull(member.careLog.getRecord(memberRecord.id))
            assertThat(domainRecord.clientUuid).isEqualTo(recordClientUuid)
            assertThat(domainRecord.note).isEqualTo("first")

            // --- Owner edits the same record via CareLog and LocalWrite-settles ---
            owner.clock.now = owner.clock.nowMillis() + 5_000L
            val editCursorBefore = owner.currentSession().pullCursor
            owner.careLog.updateRecord(
                id = recordId,
                timestamp = createTs,
                endTimestamp = null,
                note = "edited",
                payloadJson = formulaPayloadJson(amountMl = 120),
                nowMillis = owner.clock.nowMillis(),
            )
            owner.settleLocalWrite()
            val settledEdit = requireNotNull(
                owner.fakes.records.getByClientUuid(recordClientUuid),
            )
            assertThat(settledEdit.syncDirty).isFalse()
            assertThat(settledEdit.mutationId).isNull()
            assertThat(settledEdit.note).isEqualTo("edited")
            assertThat(settledEdit.payloadJson).contains("\"amount_ml\":120")
            val editVersion = requireNotNull(settledEdit.baseVersion)
            assertThat(editVersion).isNotEqualTo(createVersion)
            // Publish must not move the pull cursor.
            assertThat(owner.currentSession().pullCursor).isEqualTo(editCursorBefore)

            member.pullForeground()
            val memberEdited = requireNotNull(
                member.fakes.records.getByClientUuid(recordClientUuid),
            )
            assertThat(memberEdited.baseVersion).isEqualTo(editVersion)
            assertThat(memberEdited.note).isEqualTo("edited")
            assertThat(memberEdited.payloadJson).contains("\"amount_ml\":120")
            assertThat(
                member.fakes.records.listAllIncludingDeleted()
                    .count { it.clientUuid == recordClientUuid },
            ).isEqualTo(1)

            // --- Owner full pull keeps stable version, no duplicate / rollback ---
            val ownerCursorBeforePull = owner.currentSession().pullCursor
            owner.pullForeground()
            val ownerAfterPull = requireNotNull(
                owner.fakes.records.getByClientUuid(recordClientUuid),
            )
            assertThat(ownerAfterPull.baseVersion).isEqualTo(editVersion)
            assertThat(ownerAfterPull.syncDirty).isFalse()
            assertThat(ownerAfterPull.note).isEqualTo("edited")
            assertThat(ownerAfterPull.payloadJson).contains("\"amount_ml\":120")
            assertThat(
                owner.fakes.records.listAllIncludingDeleted()
                    .count { it.clientUuid == recordClientUuid },
            ).isEqualTo(1)
            assertThat(owner.currentSession().pullCursor).isAtLeast(ownerCursorBeforePull)
            assertThat(owner.port.status().first()).isEqualTo(SyncStatus.Idle)

            // Explicit LocalWrite plan residual: push-only contract still holds after edit.
            val cursorBeforeNoopLocalWrite = owner.currentSession().pullCursor
            assertThat(owner.port.sync(SyncTrigger.LocalWrite).isSuccess).isTrue()
            assertThat(owner.currentSession().pullCursor).isEqualTo(cursorBeforeNoopLocalWrite)

            // Isolation receipt: data root is under the process temp tree, never NAS mounts.
            assertThat(fixture.server.dataRoot.absolutePath).doesNotContain("/volume1/")
            assertThat(fixture.server.dataRoot.absolutePath).doesNotContain("nas")
            assertThat(File(fixture.server.dataRoot, "server.crt").isFile).isTrue()
        }
    }
}
