package com.lezi.babylog.sync.engine

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.database.causal.ConflictSnapshotCacheDao
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.backend.PullResult
import com.lezi.babylog.sync.backend.SyncHttpException
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.ReplicaCycleStateException
import com.lezi.babylog.sync.session.SyncPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class ReplicaSyncEngineResyncAuthorityContinuationTest {
    @Test
    fun missingGenerationWithActiveResetReceiptRecoversFromServer409() = runTest {
        val rig = memberRig(generation = "")
        rig.engine.resetLocalSyncReceipts(rig.preferences.current(), invalidateCurrentReceipts = true)

        completeMissingGenerationRecovery(rig)

        assertCompletedAuthority(rig)
    }

    @Test
    fun missingGenerationWithoutResetReceiptStillRecoversFromServer409() = runTest {
        val rig = memberRig(generation = "")

        completeMissingGenerationRecovery(rig)

        assertCompletedAuthority(rig)
    }

    @Test
    fun cancellationBeforeGenerationIsKnownKeepsTheResetReceiptReadable() = runTest {
        val rig = memberRig(generation = "")
        val receipt = rig.engine.resetLocalSyncReceipts(
            rig.preferences.current(),
            invalidateCurrentReceipts = true,
        )
        rig.backend.pullFailures += CancellationException("cancel before server supplies generation")

        val failure = runCatching {
            rig.newEngine().synchronize(rig.preferences.current(), SyncTrigger.PullToRefresh)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CancellationException::class.java)
        val journal = ReplicaResetReceiptJournal(rig.conflictDetails)
        assertThat(journal.load()).isEqualTo(receipt)
        assertThat(journal.loadMemberAuthority(receipt)).isNull()
        assertThat(rig.preferences.current().pullGeneration).isEmpty()
        assertThat(rig.backend.causalCommittedUnits).isEmpty()
    }

    @Test
    fun disjointResumedPageKeepsAuthorityFromEarlierCycle() = runTest {
        val rig = memberRig()
        failAfterFirstPage(rig)

        resumeWithSecondPage(rig, rig.engine)

        assertCompletedAuthority(rig)
    }

    @Test
    fun disjointResumedPageKeepsAuthorityAfterProcessRestart() = runTest {
        val rig = memberRig()
        failAfterFirstPage(rig)
        val receipt = requireNotNull(ReplicaResetReceiptJournal(rig.conflictDetails).load())
        assertThat(ReplicaResetReceiptJournal(rig.conflictDetails).loadMemberAuthority(receipt))
            .isEqualTo(MemberAuthorityProgress(NEW_GENERATION, setOf(FIRST_BABY)))

        resumeWithSecondPage(rig, rig.newEngine())

        assertCompletedAuthority(rig)
    }

    @Test
    fun classifiedContinuationFailureRetainsResetBoundaryAndDoesNotPublish() = runTest {
        val rig = memberRig()
        failAfterFirstPage(rig)
        val before = requireNotNull(rig.conflictDetails.getTransportJournal(JOURNAL_KEY))
        rig.backend.pullFailures += SyncHttpException(
            statusCode = 503,
            responseBody = """{"code":"oversized_group","entity_type":"record"}""",
        )

        val failure = runCatching {
            rig.newEngine().synchronize(rig.preferences.current(), SyncTrigger.PullToRefresh)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SyncHttpException::class.java)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(1)
        assertThat(rig.conflictDetails.getTransportJournal(JOURNAL_KEY)).isEqualTo(before)
        assertThat(rig.backend.causalCommittedUnits).isEmpty()
        assertThat(rig.babies.listFamilyAuthority().map { it.clientUuid })
            .containsExactly(FIRST_BABY, SECOND_BABY, ABSENT_BABY)

        resumeWithSecondPage(rig, rig.newEngine())
        assertCompletedAuthority(rig)
    }

    @Test
    fun cancellationAfterPageCheckpointKeepsDurableAuthorityForRestart() = runTest {
        val rig = memberRig()
        rig.backend.pullFailures += generationChanged(NEW_GENERATION)
        rig.backend.pullResults += page(FIRST_BABY, cursor = 1, hasMore = true)
        rig.backend.beforePullReturn = {
            rig.backend.beforePullReturn = {
                assertThat(rig.preferences.current().pullCursor).isEqualTo(1)
                throw CancellationException("cancel after first page checkpoint")
            }
        }

        val failure = runCatching {
            rig.engine.synchronize(rig.preferences.current(), SyncTrigger.PullToRefresh)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(rig.conflictDetails.getTransportJournal(JOURNAL_KEY)).isNotNull()
        assertThat(rig.babies.getByClientUuid(SECOND_BABY)?.familyAuthority).isTrue()
        assertThat(rig.babies.getByClientUuid(ABSENT_BABY)?.familyAuthority).isTrue()
        resumeWithSecondPage(rig, rig.newEngine())
        assertCompletedAuthority(rig)
    }

    @Test
    fun cancelledObservationWriteCannotPublishTheAppliedPageCheckpoint() = runTest {
        val rig = memberRig()
        val interruptedCache = object : ConflictSnapshotCacheDao by rig.conflictDetails {
            override suspend fun putTransportJournal(
                journalKey: String,
                payloadJson: String,
                contentEpoch: Long,
            ) {
                if (journalKey == JOURNAL_KEY) {
                    val progress = Json.parseToJsonElement(payloadJson).jsonObject["member_authority"]
                    if (progress is JsonObject &&
                        progress.getValue("baby_client_uuids").jsonArray.any {
                            it.jsonPrimitive.content == FIRST_BABY
                        }
                    ) {
                        throw CancellationException("stop before observations become durable")
                    }
                }
                rig.conflictDetails.putTransportJournal(journalKey, payloadJson, contentEpoch)
            }
        }
        val interruptedEngine = rig.newEngine(rig.preferences, interruptedCache)
        rig.backend.pullFailures += generationChanged(NEW_GENERATION)
        rig.backend.pullResults += page(FIRST_BABY, cursor = 1, hasMore = true)

        val failure = runCatching {
            interruptedEngine.synchronize(rig.preferences.current(), SyncTrigger.PullToRefresh)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(0)
        assertThat(rig.babies.getByClientUuid(FIRST_BABY)?.syncDirty).isFalse()
        assertThat(rig.babies.getByClientUuid(ABSENT_BABY)?.familyAuthority).isTrue()
        rig.backend.pullResults += page(FIRST_BABY, cursor = 1, hasMore = true)
        rig.backend.pullResults += page(SECOND_BABY, cursor = 2, hasMore = false)

        rig.newEngine().synchronize(rig.preferences.current(), SyncTrigger.PullToRefresh)

        assertCompletedAuthority(rig)
    }

    @Test
    fun restartAfterFinalCheckpointReconcilesAuthorityFromDurableObservations() = runTest {
        val rig = memberRig()
        val interruptedPreferences = object : SyncPreferences by rig.preferences {
            override suspend fun updatePullCheckpoint(
                cursor: Long,
                generation: String,
                familyName: String?,
            ) {
                rig.preferences.updatePullCheckpoint(cursor, generation, familyName)
                if (cursor == 2L) throw CancellationException("stop after final checkpoint commits")
            }
        }
        rig.backend.pullFailures += generationChanged(NEW_GENERATION)
        rig.backend.pullResults += page(FIRST_BABY, cursor = 1, hasMore = true)
        rig.backend.pullResults += page(SECOND_BABY, cursor = 2, hasMore = false)

        val failure = runCatching {
            rig.newEngine(interruptedPreferences)
                .synchronize(rig.preferences.current(), SyncTrigger.PullToRefresh)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(rig.preferences.current().pullCursor).isEqualTo(2)
        assertThat(rig.babies.getByClientUuid(ABSENT_BABY)?.familyAuthority).isTrue()
        // No replayed entities: only the journal can distinguish retained from absent babies.
        rig.newEngine().synchronize(rig.preferences.current(), SyncTrigger.PullToRefresh)

        assertCompletedAuthority(rig)
    }

    @Test
    fun legacyReceiptAtPartialCursorRewalksWithoutLosingOriginalResetRoots() = runTest {
        val rig = memberRig()
        val recordUuid = "record-edited-between-recovery-cycles"
        rig.records.seed(
            RecordEntity(
                clientUuid = recordUuid,
                babyId = requireNotNull(rig.babies.getByClientUuid(FIRST_BABY)).id,
                type = "formula",
                timestamp = 100,
                payloadJson = """{"amount_ml":120}""",
                createdByMembershipId = rig.preferences.current().membershipId,
                updatedAt = 500,
                syncDirty = false,
                baseVersion = "v-old-record",
            ),
        )
        failAfterFirstPage(rig)
        rig.records.update(
            requireNotNull(rig.records.getByClientUuid(recordUuid)).copy(
                note = "edit made after the reset",
                updatedAt = 501,
                syncDirty = true,
            ),
        )
        val journal = ReplicaResetReceiptJournal(rig.conflictDetails)
        val receipt = requireNotNull(journal.load())
        val row = requireNotNull(rig.conflictDetails.getTransportJournal(JOURNAL_KEY))
        val legacy = Json.parseToJsonElement(row.payloadJson).jsonObject.toMutableMap().apply {
            remove("member_authority")
            put("schema", JsonPrimitive(1))
        }
        rig.conflictDetails.putTransportJournal(JOURNAL_KEY, JsonObject(legacy).toString(), row.contentEpoch)
        assertThat(journal.load()).isEqualTo(receipt)
        assertThat(journal.loadMemberAuthority(receipt)).isNull()
        val pullsBefore = rig.backend.pullCursors.size
        rig.backend.pullResults += page(FIRST_BABY, cursor = 1, hasMore = true)
        val secondPage = page(SECOND_BABY, cursor = 2, hasMore = false)
        val remoteRecord = remoteReplicaRecord(recordUuid, FIRST_BABY)
        rig.backend.pullResults += secondPage.copy(
            entities = secondPage.entities + remoteRecord.copy(
                payloadJson = remoteRecord.payloadJson.replace(
                    "membership-b",
                    rig.preferences.current().membershipId,
                ),
                versionId = "v-remote-record",
                rev = 2,
            ),
        )
        rig.backend.beforePullReturn = {
            assertThat(journal.load()?.roots).isEqualTo(receipt.roots)
        }
        rig.backend.onCausalCommit = { units ->
            val mutation = units.single { it.clientUuid == recordUuid }
            assertThat(mutation.baseVersion).isEqualTo("v-remote-record")
            assertThat(mutation.rootJson).contains("edit made after the reset")
        }

        rig.newEngine().synchronize(rig.preferences.current(), SyncTrigger.PullToRefresh)

        assertThat(rig.backend.pullCursors.drop(pullsBefore)).containsExactly(0L, 1L).inOrder()
        assertThat(rig.backend.causalCommittedUnits.flatten().map { it.clientUuid })
            .containsExactly(recordUuid)
        assertCompletedAuthority(rig)
    }

    @Test
    fun anotherGenerationDiscardsEarlierGenerationAuthorityObservations() = runTest {
        val rig = memberRig()
        failAfterFirstPage(rig)
        rig.backend.pullFailures += generationChanged("newer-generation")
        rig.backend.pullResults += page(SECOND_BABY, cursor = 1, hasMore = false)
            .copy(generation = "newer-generation")

        rig.newEngine().synchronize(rig.preferences.current(), SyncTrigger.PullToRefresh)

        assertThat(rig.babies.getByClientUuid(FIRST_BABY)?.familyAuthority).isFalse()
        assertThat(rig.babies.getByClientUuid(SECOND_BABY)?.familyAuthority).isTrue()
        assertThat(rig.babies.getByClientUuid(ABSENT_BABY)?.familyAuthority).isFalse()
        assertThat(rig.conflictDetails.getTransportJournal(JOURNAL_KEY)).isNull()
        assertNoBabyPublish(rig)
    }

    @Test
    fun completedEmptyResetRevokesTheOldAuthoritySetEvenAtCursorZero() = runTest {
        val rig = memberRig()
        rig.backend.pullFailures += generationChanged(NEW_GENERATION)
        rig.backend.pullResults += PullResult(
            entities = emptyList(),
            cursor = 0,
            generation = NEW_GENERATION,
            hasMore = false,
        )

        rig.engine.synchronize(rig.preferences.current(), SyncTrigger.PullToRefresh)

        assertThat(rig.babies.listFamilyAuthority()).isEmpty()
        assertThat(rig.conflictDetails.getTransportJournal(JOURNAL_KEY)).isNull()
        assertNoBabyPublish(rig)
    }
}

private fun memberRig(generation: String = "old-generation") = ReplicaEngineRig(
    joinedReplicaSession().copy(
        role = FamilyRole.Member,
        pullCursor = 7,
        pullGeneration = generation,
    ),
).also { rig ->
    rig.backend.enableCausal = true
    listOf(FIRST_BABY, SECOND_BABY, ABSENT_BABY).forEach { uuid ->
        rig.babies.seed(localReplicaBaby().copy(clientUuid = uuid, syncDirty = false))
    }
}

private suspend fun completeMissingGenerationRecovery(rig: ReplicaEngineRig) {
    rig.backend.pullFailures += generationChanged(NEW_GENERATION)
    rig.backend.pullResults += page(FIRST_BABY, cursor = 1, hasMore = true)
    rig.backend.pullResults += page(SECOND_BABY, cursor = 2, hasMore = false)

    rig.newEngine().synchronize(rig.preferences.current(), SyncTrigger.PullToRefresh)

    assertThat(rig.preferences.current().pullGeneration).isEqualTo(NEW_GENERATION)
    assertThat(rig.backend.pullCursors.take(3)).containsExactly(7L, 0L, 1L).inOrder()
}

private suspend fun failAfterFirstPage(rig: ReplicaEngineRig) {
    rig.backend.pullFailures += generationChanged(NEW_GENERATION)
    rig.backend.pullResults += page(FIRST_BABY, cursor = 1, hasMore = true)
    rig.backend.pullResults += invalidContinuation()

    val failure = runCatching {
        rig.engine.synchronize(rig.preferences.current(), SyncTrigger.PullToRefresh)
    }.exceptionOrNull()

    assertThat(failure).isInstanceOf(ReplicaCycleStateException::class.java)
    assertThat(rig.preferences.current().pullCursor).isEqualTo(1)
    assertThat(rig.preferences.current().pullGeneration).isEqualTo(NEW_GENERATION)
    assertThat(rig.babies.listFamilyAuthority().map { it.clientUuid })
        .containsExactly(FIRST_BABY, SECOND_BABY, ABSENT_BABY)
    assertThat(rig.backend.causalCommittedUnits).isEmpty()
}

private suspend fun resumeWithSecondPage(rig: ReplicaEngineRig, engine: ReplicaSyncEngine) {
    val pullsBefore = rig.backend.pullCursors.size
    // Deliberately disjoint: the server does not repeat the already-checkpointed first Baby.
    rig.backend.pullResults += page(SECOND_BABY, cursor = 2, hasMore = false)
    engine.synchronize(rig.preferences.current(), SyncTrigger.PullToRefresh)
    assertThat(rig.backend.pullCursors.drop(pullsBefore)).containsExactly(1L)
}

private suspend fun assertCompletedAuthority(rig: ReplicaEngineRig) {
    assertThat(rig.babies.getByClientUuid(FIRST_BABY)?.familyAuthority).isTrue()
    assertThat(rig.babies.getByClientUuid(SECOND_BABY)?.familyAuthority).isTrue()
    assertThat(rig.babies.getByClientUuid(ABSENT_BABY)?.familyAuthority).isFalse()
    assertThat(rig.preferences.current().pullCursor).isEqualTo(2)
    assertThat(rig.conflictDetails.getTransportJournal(JOURNAL_KEY)).isNull()
    assertNoBabyPublish(rig)
}

private fun assertNoBabyPublish(rig: ReplicaEngineRig) {
    assertThat(rig.backend.causalCommittedUnits.flatten().none { it.entityType == "baby" }).isTrue()
}

private fun page(uuid: String, cursor: Long, hasMore: Boolean) = PullResult(
    entities = listOf(remoteReplicaBaby().copy(clientUuid = uuid, rev = cursor, versionId = "v-$uuid")),
    cursor = cursor,
    generation = NEW_GENERATION,
    hasMore = hasMore,
)

private fun invalidContinuation() = PullResult(
    entities = emptyList(),
    cursor = 1,
    generation = NEW_GENERATION,
    hasMore = true,
)

private fun generationChanged(generation: String) = SyncHttpException(
    statusCode = 409,
    responseBody = """
        {"detail":{"code":"generation_changed","action":"full_resync",
        "reset_cursor":0,"server_generation":"$generation"}}
    """.trimIndent(),
)

private const val FIRST_BABY = "baby-page-one"
private const val SECOND_BABY = "baby-page-two"
private const val ABSENT_BABY = "baby-no-longer-in-family"
private const val NEW_GENERATION = "new-generation"
private const val JOURNAL_KEY = "replica-reset-receipt:current"
