package com.lezi.babylog.core.database.causal

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.CarePlanEntity
import com.lezi.babylog.core.database.CustomItemEntity
import com.lezi.babylog.core.database.LeziDatabase
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.database.RoomDatabaseTransactionRunner
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
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
    fun allCausalRootDaosRejectStaleCapturedEpochWithoutDowngrade() = runBlocking {
        database.babyDao().upsert(
            BabyEntity(
                familyId = 1,
                nickname = "new",
                birthdayEpochDay = 1,
                themeColorArgb = 0,
                clientUuid = "baby-stale-freeze",
                updatedAt = 200,
            ),
        )
        database.recordDao().upsert(
            RecordEntity(
                clientUuid = "record-stale-freeze",
                babyId = 1,
                type = "nursing",
                timestamp = 1,
                updatedAt = 200,
            ),
        )
        database.carePlanDao().upsert(
            CarePlanEntity(
                clientUuid = "plan-stale-freeze",
                babyId = 1,
                type = "nursing",
                scheduledAt = 1,
                scheduledZoneId = "UTC",
                updatedAt = 200,
            ),
        )
        database.customItemDao().upsert(
            CustomItemEntity(
                clientUuid = "custom-stale-freeze",
                familyId = 1,
                name = "new",
                iconSlot = 0,
                updatedAt = 200,
            ),
        )
        database.wakeObservationDao().upsert(
            WakeObservationEntity(
                clientUuid = "wake-stale-freeze",
                sleepRecordClientUuid = "sleep-1",
                wakeTimestamp = 200,
                updatedAt = 200,
            ),
        )

        assertThat(database.babyDao().freezeDirtyEpoch("baby-stale-freeze", 100, "old")).isNull()
        assertThat(database.recordDao().freezeDirtyEpoch("record-stale-freeze", 100, "old")).isNull()
        assertThat(database.carePlanDao().freezeDirtyEpoch("plan-stale-freeze", 100, "old")).isNull()
        assertThat(database.customItemDao().freezeDirtyEpoch("custom-stale-freeze", 100, "old")).isNull()
        assertThat(
            database.wakeObservationDao().freezeDirtyEpoch("wake-stale-freeze", 100, "old"),
        ).isNull()
        assertThat(database.babyDao().getByClientUuid("baby-stale-freeze")!!.updatedAt).isEqualTo(200)
        assertThat(database.recordDao().getByClientUuid("record-stale-freeze")!!.updatedAt).isEqualTo(200)
        assertThat(database.carePlanDao().getByClientUuid("plan-stale-freeze")!!.updatedAt).isEqualTo(200)
        assertThat(database.customItemDao().getByClientUuid("custom-stale-freeze")!!.updatedAt).isEqualTo(200)
        assertThat(
            database.wakeObservationDao().getByClientUuid("wake-stale-freeze")!!.updatedAt,
        ).isEqualTo(200)
    }

    @Test
    fun settlementReceiptRootMediaAndConflictEvidenceRollbackTogether() = runBlocking {
        val records = database.recordDao()
        val recordId = records.upsert(
            RecordEntity(
                clientUuid = "record-rollback",
                babyId = 1,
                type = "formula",
                timestamp = 1,
                updatedAt = 100,
                syncDirty = true,
                mutationId = "mut-rollback",
                baseVersion = "v0",
            ),
        )
        database.mediaAssetDao().upsert(
            MediaAssetEntity(
                recordId = recordId,
                clientUuid = "media-rollback",
                localUri = "media/rollback.jpg",
                createdAt = 1,
                updatedAt = 100,
                syncDirty = true,
            ),
        )

        assertThat(
            runCatching {
                RoomDatabaseTransactionRunner(database).run {
                    check(
                        records.acknowledgeCausalAcceptedOrMerged(
                            clientUuid = "record-rollback",
                            expectedMutationId = "mut-rollback",
                            expectedContentEpoch = 100,
                            newBaseVersion = "v1",
                        ),
                    )
                    database.mediaAssetDao().markSynced("media-rollback", 100)
                    database.conflictSummaryDao().upsert(
                        ConflictSummaryEntity(
                            conflictId = "conflict-rollback",
                            entityType = "record",
                            clientUuid = "record-rollback",
                            stableVersionId = "v1",
                            status = "open",
                            kind = "concurrent",
                            updatedAt = 100,
                        ),
                    )
                    error("projection failed")
                }
            }.exceptionOrNull(),
        ).isNotNull()

        val record = requireNotNull(records.getByClientUuid("record-rollback"))
        assertThat(record.syncDirty).isTrue()
        assertThat(record.mutationId).isEqualTo("mut-rollback")
        assertThat(record.baseVersion).isEqualTo("v0")
        assertThat(database.mediaAssetDao().getByClientUuid("media-rollback")!!.syncDirty).isTrue()
        assertThat(database.conflictSummaryDao().get("conflict-rollback")).isNull()
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
    fun relationOnlyDeltaInvalidatesObserversAndRollsBackWithTheOuterPage() = runBlocking {
        val sources = database.sourceRelationDao()
        val invalidation = async {
            withTimeout(1_000) {
                sources.observeAllMembers().drop(1).first()
            }
        }
        yield()

        sources.applyPullSummary(
            relationId = "rel-observed",
            recordClientUuid = "display-observed",
            role = SourceRelationRole.DISPLAY,
            peerIds = listOf("source-observed"),
            observedAt = 20,
        )

        assertThat(invalidation.await().map { it.recordClientUuid })
            .containsExactly("display-observed", "source-observed")
        val rollback = runCatching {
            RoomDatabaseTransactionRunner(database).run {
                sources.applyPullSummary(
                    relationId = "rel-rolled-back",
                    recordClientUuid = "display-rolled-back",
                    role = SourceRelationRole.DISPLAY,
                    peerIds = listOf("source-rolled-back"),
                    observedAt = 30,
                )
                error("record body dependency failed")
            }
        }
        assertThat(rollback.exceptionOrNull()).isNotNull()
        assertThat(sources.get("rel-rolled-back")).isNull()
        assertThat(sources.listMembers("rel-rolled-back")).isEmpty()
    }

    @Test
    fun sourceFirstPullDeltaSurvivesReopenAndDisplayFinalizesCanonicalSet() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "source-relation-${System.nanoTime()}.db"
        var local = Room.databaseBuilder(context, LeziDatabase::class.java, databaseName).build()
        try {
            val firstDao = local.sourceRelationDao()
            val invalidation = async {
                withTimeout(1_000) {
                    firstDao.observeAllMembers().drop(1).first()
                }
            }
            yield()
            firstDao.applyPullSummary(
                relationId = "rel-restart",
                recordClientUuid = "source-a",
                role = SourceRelationRole.SOURCE,
                peerIds = listOf("display", "source-b"),
                observedAt = 40,
            )
            assertThat(invalidation.await().map { it.recordClientUuid })
                .containsExactly("source-a")
            assertThat(firstDao.get("rel-restart")?.displayClientUuid).isEmpty()

            local.close()
            local = Room.databaseBuilder(context, LeziDatabase::class.java, databaseName).build()
            val restartedDao = local.sourceRelationDao()
            assertThat(restartedDao.observeAllMembers().first().map { it.recordClientUuid })
                .containsExactly("source-a")
            restartedDao.applyPullSummary(
                relationId = "rel-restart",
                recordClientUuid = "display",
                role = SourceRelationRole.DISPLAY,
                peerIds = listOf("source-a", "source-b"),
                observedAt = 41,
            )

            assertThat(restartedDao.get("rel-restart")?.displayClientUuid).isEqualTo("display")
            assertThat(restartedDao.listMembers("rel-restart"))
                .containsExactly(
                    SourceRelationMemberEntity("rel-restart", "display", SourceRelationRole.DISPLAY),
                    SourceRelationMemberEntity("rel-restart", "source-a", SourceRelationRole.SOURCE),
                    SourceRelationMemberEntity("rel-restart", "source-b", SourceRelationRole.SOURCE),
                )
        } finally {
            local.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun legacyPullMarkerUpgradesThroughPublicTransitionAndFreezesClosedSet() = runBlocking {
        val relationId = "rel-legacy"
        val sqlite = database.openHelper.writableDatabase
        sqlite.execSQL(
            """
            INSERT INTO source_relations(
                relationId, displayClientUuid, mediaRetained, reason, mutationId,
                createdByMembershipId, createdAt
            ) VALUES (?, ?, 1, ?, ?, '', 1)
            """.trimIndent(),
            arrayOf(relationId, "display", SourceRelationReason.PULL_SUMMARY, "pull-$relationId"),
        )
        sqlite.execSQL(
            "INSERT INTO source_relation_members(relationId, recordClientUuid, role) VALUES (?, ?, ?)",
            arrayOf(relationId, "display", SourceRelationRole.DISPLAY),
        )
        sqlite.execSQL(
            "INSERT INTO source_relation_members(relationId, recordClientUuid, role) VALUES (?, ?, ?)",
            arrayOf(relationId, "source", SourceRelationRole.SOURCE),
        )
        val sources = database.sourceRelationDao()

        sources.applyPullSummary(
            relationId = relationId,
            recordClientUuid = "source",
            role = SourceRelationRole.SOURCE,
            peerIds = listOf("display"),
            observedAt = 2,
        )

        assertThat(sources.get(relationId)?.mutationId).startsWith("pull-$relationId:")
        val drift = runCatching {
            sources.applyPullSummary(
                relationId = relationId,
                recordClientUuid = "source",
                role = SourceRelationRole.SOURCE,
                peerIds = listOf("display", "other"),
                observedAt = 3,
            )
        }
        assertThat(drift.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(sources.listMembers(relationId).map { it.recordClientUuid })
            .containsExactly("display", "source")
    }

    @Test
    fun pendingPullMemberRoleDriftRollsBack() = runBlocking {
        val sources = database.sourceRelationDao()
        sources.applyPullSummary(
            relationId = "rel-role-drift",
            recordClientUuid = "source-a",
            role = SourceRelationRole.SOURCE,
            peerIds = listOf("display", "source-b"),
            observedAt = 1,
        )

        val drift = runCatching {
            sources.applyPullSummary(
                relationId = "rel-role-drift",
                recordClientUuid = "source-a",
                role = SourceRelationRole.DISPLAY,
                peerIds = listOf("display", "source-b"),
                observedAt = 2,
            )
        }

        assertThat(drift.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(sources.get("rel-role-drift")?.displayClientUuid).isEmpty()
        assertThat(sources.listMembers("rel-role-drift"))
            .containsExactly(
                SourceRelationMemberEntity(
                    "rel-role-drift",
                    "source-a",
                    SourceRelationRole.SOURCE,
                ),
            )
    }

    @Test
    fun malformedCanonicalTransitionAndPeerSetDriftRollBack() = runBlocking {
        val sources = database.sourceRelationDao()
        val relation = SourceRelationEntity(
            relationId = "rel-invalid",
            displayClientUuid = "display",
            mediaRetained = true,
            reason = SourceRelationReason.OWNER_GROUP_RESOLVE,
            mutationId = "mut-invalid",
            createdByMembershipId = "owner",
            createdAt = 50,
        )
        val relationMismatch = runCatching {
            sources.applyOwnerGroupResolution(
                relation,
                listOf(
                    SourceRelationMemberEntity("other-relation", "display", SourceRelationRole.DISPLAY),
                    SourceRelationMemberEntity("rel-invalid", "source", SourceRelationRole.SOURCE),
                ),
            )
        }
        assertThat(relationMismatch.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(sources.get("rel-invalid")).isNull()

        val invalidRole = runCatching {
            sources.applyOwnerGroupResolution(
                relation,
                listOf(
                    SourceRelationMemberEntity("rel-invalid", "display", SourceRelationRole.DISPLAY),
                    SourceRelationMemberEntity("rel-invalid", "source", "winner"),
                ),
            )
        }
        assertThat(invalidRole.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(sources.get("rel-invalid")).isNull()

        sources.applyPullSummary(
            relationId = "rel-peer-drift",
            recordClientUuid = "source-a",
            role = SourceRelationRole.SOURCE,
            peerIds = listOf("display", "source-b"),
            observedAt = 60,
        )
        val peerDrift = runCatching {
            sources.applyPullSummary(
                relationId = "rel-peer-drift",
                recordClientUuid = "display",
                role = SourceRelationRole.DISPLAY,
                peerIds = listOf("source-a"),
                observedAt = 61,
            )
        }
        assertThat(peerDrift.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(sources.get("rel-peer-drift")?.displayClientUuid).isEmpty()
        assertThat(sources.listMembers("rel-peer-drift").map { it.recordClientUuid })
            .containsExactly("source-a")
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
