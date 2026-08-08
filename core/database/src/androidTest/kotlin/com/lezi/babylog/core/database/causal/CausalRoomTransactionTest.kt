package com.lezi.babylog.core.database.causal

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.CarePlanEntity
import com.lezi.babylog.core.database.CustomItemEntity
import com.lezi.babylog.core.database.LeziDatabase
import com.lezi.babylog.core.database.RecordEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room transactional seams for freeze read, exact CAS ack, branch receipt,
 * resolution apply, and media reference changes (ticket 04).
 */
@RunWith(AndroidJUnit4::class)
class CausalRoomTransactionTest {
    private lateinit var database: LeziDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            LeziDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun freezeThenExactCasAckAdvancesBaseAndClearsPending() = runBlocking {
        val records = database.recordDao()
        records.upsert(
            RecordEntity(
                clientUuid = "record-cas",
                babyId = 1,
                type = "nursing",
                timestamp = 10L,
                updatedAt = 100L,
                syncDirty = true,
                baseVersion = "v-base",
            ),
        )

        val frozen = records.freezeDirtyEpoch(
            clientUuid = "record-cas",
            contentEpoch = 100L,
            newMutationId = "mut-1",
        )
        assertThat(frozen?.mutationId).isEqualTo("mut-1")
        assertThat(frozen?.baseVersion).isEqualTo("v-base")
        assertThat(frozen?.syncDirty).isTrue()

        // Retry freeze at same epoch reuses mutation id.
        val refrozen = records.freezeDirtyEpoch(
            clientUuid = "record-cas",
            contentEpoch = 100L,
            newMutationId = "mut-should-not-replace",
        )
        assertThat(refrozen?.mutationId).isEqualTo("mut-1")

        assertThat(
            records.acknowledgeCausalAcceptedOrMerged(
                clientUuid = "record-cas",
                expectedMutationId = "mut-1",
                expectedContentEpoch = 100L,
                newBaseVersion = "v-stable-2",
            ),
        ).isTrue()
        val settled = requireNotNull(records.getByClientUuid("record-cas"))
        assertThat(settled.baseVersion).isEqualTo("v-stable-2")
        assertThat(settled.mutationId).isNull()
        assertThat(settled.syncDirty).isFalse()
        assertThat(settled.openConflictId).isNull()

        assertThat(
            records.acknowledgeCausalAcceptedOrMerged(
                clientUuid = "record-cas",
                expectedMutationId = "mut-1",
                expectedContentEpoch = 100L,
                newBaseVersion = "v-should-not-apply",
            ),
        ).isFalse()
    }

    @Test
    fun branchedReceiptConvertsPendingToUnresolvedConflict() = runBlocking {
        val records = database.recordDao()
        records.upsert(
            RecordEntity(
                clientUuid = "record-branch",
                babyId = 1,
                type = "formula",
                timestamp = 20L,
                updatedAt = 200L,
                syncDirty = true,
                baseVersion = "v-base",
                mutationId = "mut-b",
            ),
        )
        // Ensure freeze path sets dirty epoch state consistently.
        records.freezeDirtyEpoch(
            clientUuid = "record-branch",
            contentEpoch = 200L,
            newMutationId = "mut-b",
        )

        assertThat(
            records.acknowledgeCausalBranched(
                clientUuid = "record-branch",
                expectedMutationId = "mut-b",
                expectedContentEpoch = 200L,
                conflictId = "conflict-1",
                branchVersionId = "branch-v9",
                stableBaseVersion = "v-base",
            ),
        ).isTrue()

        val branched = requireNotNull(records.getByClientUuid("record-branch"))
        assertThat(branched.syncDirty).isFalse()
        assertThat(branched.openConflictId).isEqualTo("conflict-1")
        assertThat(branched.localBranchVersionId).isEqualTo("branch-v9")
        assertThat(branched.mutationId).isEqualTo("mut-b")
        assertThat(branched.baseVersion).isEqualTo("v-base")
        // Not fully consistent: still has open conflict.
        assertThat(branched.openConflictId).isNotNull()
    }

    @Test
    fun ownerResolutionAppliesSourceRelationWithoutRecordTombstone() = runBlocking {
        val sources = database.sourceRelationDao()
        val records = database.recordDao()
        records.upsert(
            RecordEntity(
                clientUuid = "display-r",
                babyId = 1,
                type = "nursing",
                timestamp = 1L,
                updatedAt = 1L,
                syncDirty = false,
                deletedAt = null,
            ),
        )
        records.upsert(
            RecordEntity(
                clientUuid = "source-r",
                babyId = 1,
                type = "nursing",
                timestamp = 2L,
                updatedAt = 2L,
                syncDirty = false,
                deletedAt = null,
            ),
        )

        sources.applyOwnerGroupResolution(
            relation = SourceRelationEntity(
                relationId = "rel-1",
                displayClientUuid = "display-r",
                mediaRetained = true,
                reason = "owner_group_resolve",
                mutationId = "mut-resolve",
                createdByMembershipId = "owner-m",
                createdAt = 10L,
            ),
            members = listOf(
                SourceRelationMemberEntity("rel-1", "display-r", "display"),
                SourceRelationMemberEntity("rel-1", "source-r", "source"),
            ),
        )

        assertThat(sources.get("rel-1")?.displayClientUuid).isEqualTo("display-r")
        assertThat(sources.listMembers("rel-1")).hasSize(2)
        assertThat(records.getByClientUuid("source-r")?.deletedAt).isNull()
        assertThat(records.getByClientUuid("display-r")?.deletedAt).isNull()
    }

    @Test
    fun mediaReferenceChangeKeepsBytesWhileAnyHolderRemains() = runBlocking {
        val refs = database.mediaReferenceDao()
        val mediaUuid = "media-ref-1"
        refs.replaceHolders(
            mediaUuid,
            listOf(
                MediaReferenceEntity(
                    mediaUuid = mediaUuid,
                    holderKind = MediaReferenceHolderKind.STABLE_ROOT,
                    holderId = "v-stable",
                    localUri = "/path/a.jpg",
                    createdAt = 1L,
                ),
                MediaReferenceEntity(
                    mediaUuid = mediaUuid,
                    holderKind = MediaReferenceHolderKind.CONFLICT_BRANCH,
                    holderId = "branch-1",
                    localUri = "/path/a.jpg",
                    createdAt = 2L,
                ),
            ),
        )
        assertThat(refs.countHoldersForMedia(mediaUuid)).isEqualTo(2)
        assertThat(refs.countHoldersForLocalUri("/path/a.jpg")).isEqualTo(2)

        refs.remove(mediaUuid, MediaReferenceHolderKind.STABLE_ROOT, "v-stable")
        assertThat(refs.countHoldersForMedia(mediaUuid)).isEqualTo(1)
        assertThat(
            mediaBytesEligibleForCleanup(
                activeMediaAssetReferences = 0,
                mediaReferenceHolders = refs.countHoldersForMedia(mediaUuid),
            ),
        ).isFalse()

        refs.remove(mediaUuid, MediaReferenceHolderKind.CONFLICT_BRANCH, "branch-1")
        assertThat(
            mediaBytesEligibleForCleanup(
                activeMediaAssetReferences = 0,
                mediaReferenceHolders = refs.countHoldersForMedia(mediaUuid),
            ),
        ).isTrue()
    }

    @Test
    fun wakeObservationPersistsCausalFieldsAndSleepLink() = runBlocking {
        val wakes = database.wakeObservationDao()
        val id = wakes.upsert(
            WakeObservationEntity(
                clientUuid = "wake-1",
                sleepRecordClientUuid = "sleep-1",
                wakeTimestamp = 500L,
                observerMembershipId = "member-a",
                note = "awake",
                withdrawn = false,
                updatedAt = 500L,
                syncDirty = true,
                baseVersion = null,
            ),
        )
        assertThat(id).isGreaterThan(0)
        val frozen = wakes.freezeDirtyEpoch(
            clientUuid = "wake-1",
            contentEpoch = 500L,
            newMutationId = "wake-mut",
        )
        assertThat(frozen?.mutationId).isEqualTo("wake-mut")
        assertThat(frozen?.sleepRecordClientUuid).isEqualTo("sleep-1")
        assertThat(wakes.listActiveForSleep("sleep-1")).hasSize(1)

        wakes.upsert(
            WakeObservationEntity(
                clientUuid = "wake-withdrawn",
                sleepRecordClientUuid = "sleep-1",
                wakeTimestamp = 600L,
                observerMembershipId = "member-b",
                withdrawn = true,
                updatedAt = 600L,
                syncDirty = false,
            ),
        )
        assertThat(wakes.listActiveForSleep("sleep-1").map { it.clientUuid })
            .containsExactly("wake-1")
    }

    @Test
    fun freezeAfterBranchedSameEpochDoesNotRequeueOnRecord() = runBlocking {
        val records = database.recordDao()
        records.upsert(
            RecordEntity(
                clientUuid = "record-freeze-branch",
                babyId = 1,
                type = "nursing",
                timestamp = 10L,
                updatedAt = 100L,
                syncDirty = true,
                baseVersion = "v-base",
                mutationId = "mut-b",
            ),
        )
        assertThat(
            records.acknowledgeCausalBranched(
                clientUuid = "record-freeze-branch",
                expectedMutationId = "mut-b",
                expectedContentEpoch = 100L,
                conflictId = "c-1",
                branchVersionId = "br-1",
                stableBaseVersion = "v-base",
            ),
        ).isTrue()
        val refrozen = records.freezeDirtyEpoch(
            clientUuid = "record-freeze-branch",
            contentEpoch = 100L,
            newMutationId = "mut-should-not",
        )
        assertThat(refrozen?.syncDirty).isFalse()
        assertThat(refrozen?.mutationId).isEqualTo("mut-b")
        assertThat(refrozen?.openConflictId).isEqualTo("c-1")
    }

    @Test
    fun babyCarePlanCustomItemFreezeAndExactCasAck() = runBlocking {
        val babies = database.babyDao()
        babies.upsert(
            BabyEntity(
                familyId = 1L,
                nickname = "A",
                birthdayEpochDay = 1L,
                themeColorArgb = 0,
                clientUuid = "baby-cas",
                updatedAt = 10L,
                syncDirty = true,
                baseVersion = "v-b0",
            ),
        )
        assertThat(
            babies.freezeDirtyEpoch("baby-cas", 10L, "mut-baby")?.mutationId,
        ).isEqualTo("mut-baby")
        assertThat(
            babies.acknowledgeCausalAcceptedOrMerged(
                clientUuid = "baby-cas",
                expectedMutationId = "mut-baby",
                expectedContentEpoch = 10L,
                newBaseVersion = "v-b1",
            ),
        ).isTrue()
        assertThat(babies.getByClientUuid("baby-cas")?.baseVersion).isEqualTo("v-b1")
        assertThat(babies.getByClientUuid("baby-cas")?.syncDirty).isFalse()

        val plans = database.carePlanDao()
        plans.upsert(
            CarePlanEntity(
                clientUuid = "plan-cas",
                babyId = 1L,
                type = "nursing",
                scheduledAt = 20L,
                scheduledZoneId = "UTC",
                updatedAt = 20L,
                syncDirty = true,
                baseVersion = "v-p0",
            ),
        )
        assertThat(
            plans.freezeDirtyEpoch("plan-cas", 20L, "mut-plan")?.mutationId,
        ).isEqualTo("mut-plan")
        assertThat(
            plans.acknowledgeCausalBranched(
                clientUuid = "plan-cas",
                expectedMutationId = "mut-plan",
                expectedContentEpoch = 20L,
                conflictId = "pc-1",
                branchVersionId = "pb-1",
                stableBaseVersion = "v-p0",
            ),
        ).isTrue()
        assertThat(plans.getByClientUuid("plan-cas")?.openConflictId).isEqualTo("pc-1")
        assertThat(plans.getByClientUuid("plan-cas")?.syncDirty).isFalse()

        val customs = database.customItemDao()
        customs.upsert(
            CustomItemEntity(
                clientUuid = "custom-cas",
                familyId = 1L,
                name = "抚触",
                iconSlot = 0,
                updatedAt = 30L,
                syncDirty = true,
                baseVersion = "v-c0",
            ),
        )
        assertThat(
            customs.freezeDirtyEpoch("custom-cas", 30L, "mut-custom")?.mutationId,
        ).isEqualTo("mut-custom")
        assertThat(
            customs.acknowledgeCausalAcceptedOrMerged(
                clientUuid = "custom-cas",
                expectedMutationId = "mut-custom",
                expectedContentEpoch = 30L,
                newBaseVersion = "v-c1",
            ),
        ).isTrue()
        assertThat(customs.getByClientUuid("custom-cas")?.baseVersion).isEqualTo("v-c1")
    }
}
