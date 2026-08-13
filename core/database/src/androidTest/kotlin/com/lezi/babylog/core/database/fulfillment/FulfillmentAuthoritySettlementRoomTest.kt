package com.lezi.babylog.core.database.fulfillment

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.CarePlanDao
import com.lezi.babylog.core.database.CarePlanEntity
import com.lezi.babylog.core.database.FulfillmentCandidateDao
import com.lezi.babylog.core.database.FulfillmentCandidateEntity
import com.lezi.babylog.core.database.LeziDatabase
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.database.RoomDatabaseTransactionRunner
import com.lezi.babylog.core.model.FulfillmentAdoptionStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FulfillmentAuthoritySettlementRoomTest {
    private lateinit var database: LeziDatabase
    private lateinit var transactionRunner: RoomDatabaseTransactionRunner

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            LeziDatabase::class.java,
        ).build()
        transactionRunner = RoomDatabaseTransactionRunner(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun injectedCandidateOrPlanFailureRollsBackTheWholeSettlement() = runBlocking {
        for (failurePoint in FailurePoint.entries) {
            seedAuthorityGraph()
            val candidateDao = when (failurePoint) {
                FailurePoint.CandidatePatch -> FailingCandidateDao(
                    database.fulfillmentCandidateDao(),
                )
                FailurePoint.PlanRelink -> database.fulfillmentCandidateDao()
            }
            val planDao = when (failurePoint) {
                FailurePoint.CandidatePatch -> database.carePlanDao()
                FailurePoint.PlanRelink -> FailingCarePlanDao(database.carePlanDao())
            }
            val settlement = FulfillmentAuthoritySettlement(
                carePlanDao = planDao,
                fulfillmentCandidateDao = candidateDao,
                transactionRunner = transactionRunner,
            )

            assertThat(runCatching { settlement.settle(PLAN_UUID) }.exceptionOrNull())
                .isInstanceOf(InjectedDaoFailure::class.java)
            assertPreSettlementState()
            clearAuthorityGraph()
        }
    }

    @Test
    fun concurrentObserverSeesOnlyCompleteBeforeAndAfterAuthoritySnapshots() = runBlocking {
        seedAuthorityGraph(includeThirdCandidate = true)
        val initialSnapshotReady = CompletableDeferred<Unit>()
        val snapshots = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2_000L) {
                database.fulfillmentCandidateDao()
                    .observeConflictNotAdoptedRecordUuids()
                    .map { authoritySnapshot() }
                    .onEach { initialSnapshotReady.complete(Unit) }
                    .take(2)
                    .toList()
            }
        }
        withTimeout(2_000L) { initialSnapshotReady.await() }

        FulfillmentAuthoritySettlement(
            carePlanDao = database.carePlanDao(),
            fulfillmentCandidateDao = database.fulfillmentCandidateDao(),
            transactionRunner = transactionRunner,
        ).settle(PLAN_UUID)

        assertThat(snapshots.await()).containsExactly(
            AuthoritySnapshot(
                planRecordClientUuid = MEMBER_RECORD_UUID,
                adoptionByCandidate = mapOf(
                    MEMBER_CANDIDATE_UUID to "",
                    OWNER_CANDIDATE_UUID to "",
                    THIRD_CANDIDATE_UUID to "",
                ),
            ),
            AuthoritySnapshot(
                planRecordClientUuid = OWNER_RECORD_UUID,
                adoptionByCandidate = mapOf(
                    MEMBER_CANDIDATE_UUID to FulfillmentAdoptionStatus.CONFLICT_NOT_ADOPTED,
                    OWNER_CANDIDATE_UUID to FulfillmentAdoptionStatus.ADOPTED,
                    THIRD_CANDIDATE_UUID to FulfillmentAdoptionStatus.CONFLICT_NOT_ADOPTED,
                ),
            ),
        ).inOrder()
    }

    @Test
    fun settlementReplayPreservesMetadataAndTimelineInvalidationStillProjectsOnlyWinner() =
        runBlocking {
            seedAuthorityGraph()
            seedAuthorityRecords()
            val settlement = FulfillmentAuthoritySettlement(
                carePlanDao = database.carePlanDao(),
                fulfillmentCandidateDao = database.fulfillmentCandidateDao(),
                transactionRunner = transactionRunner,
            )
            val initialTimelineInvalidation = CompletableDeferred<Unit>()
            val timelineInvalidations = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(2_000L) {
                    database.timelineWindowDao()
                        .observeInvalidations()
                        .onEach { initialTimelineInvalidation.complete(Unit) }
                        .take(2)
                        .toList()
                }
            }
            withTimeout(2_000L) { initialTimelineInvalidation.await() }

            settlement.settle(PLAN_UUID)
            val planAfterFirst = database.carePlanDao().getByClientUuid(PLAN_UUID)!!
            val candidatesAfterFirst = database.fulfillmentCandidateDao()
                .listForCarePlan(PLAN_UUID)
            assertThat(timelineInvalidations.await()).hasSize(2)
            assertThat(
                database.timelineWindowDao().loadRecordProjection(
                    babyId = BABY_ID,
                    startInclusive = 0L,
                    endExclusive = 1_000L,
                ).map { it.root.clientUuid },
            ).containsExactly(OWNER_RECORD_UUID)

            settlement.settle(PLAN_UUID)

            assertThat(database.carePlanDao().getByClientUuid(PLAN_UUID))
                .isEqualTo(planAfterFirst)
            assertThat(database.fulfillmentCandidateDao().listForCarePlan(PLAN_UUID))
                .containsExactlyElementsIn(candidatesAfterFirst)
                .inOrder()
            assertThat(candidatesAfterFirst.single { it.clientUuid == MEMBER_CANDIDATE_UUID }
                .convertedRecordClientUuid).isEqualTo(INDEPENDENT_RECORD_UUID)
        }

    @Test
    fun nestedSettlementParticipatesInTheOuterRoomRollback() = runBlocking {
        seedAuthorityGraph()
        val settlement = FulfillmentAuthoritySettlement(
            carePlanDao = database.carePlanDao(),
            fulfillmentCandidateDao = database.fulfillmentCandidateDao(),
            transactionRunner = transactionRunner,
        )

        assertThat(
            runCatching {
                transactionRunner.run {
                    settlement.settle(PLAN_UUID)
                    throw InjectedDaoFailure()
                }
            }.exceptionOrNull(),
        ).isInstanceOf(InjectedDaoFailure::class.java)
        assertPreSettlementState()
    }

    private suspend fun seedAuthorityGraph(includeThirdCandidate: Boolean = false) {
        database.carePlanDao().upsert(
            CarePlanEntity(
                clientUuid = PLAN_UUID,
                babyId = BABY_ID,
                type = "formula",
                scheduledAt = 10L,
                scheduledZoneId = "UTC",
                status = "completed",
                fulfilledRecordClientUuid = MEMBER_RECORD_UUID,
                fulfilledAt = 20L,
                updatedAt = 90L,
                syncDirty = false,
            ),
        )
        database.fulfillmentCandidateDao().upsert(
            candidate(
                clientUuid = MEMBER_CANDIDATE_UUID,
                recordClientUuid = MEMBER_RECORD_UUID,
                role = "member",
                confirmedAt = 20L,
                updatedAt = 100L,
                syncDirty = false,
                convertedRecordClientUuid = INDEPENDENT_RECORD_UUID,
            ),
        )
        database.fulfillmentCandidateDao().upsert(
            candidate(
                clientUuid = OWNER_CANDIDATE_UUID,
                recordClientUuid = OWNER_RECORD_UUID,
                role = "owner",
                confirmedAt = 30L,
                updatedAt = 110L,
                syncDirty = true,
            ),
        )
        if (includeThirdCandidate) {
            database.fulfillmentCandidateDao().upsert(
                candidate(
                    clientUuid = THIRD_CANDIDATE_UUID,
                    recordClientUuid = "record-third",
                    role = "member",
                    confirmedAt = 10L,
                    updatedAt = 120L,
                    syncDirty = false,
                ),
            )
        }
    }

    private suspend fun seedAuthorityRecords() {
        database.recordDao().upsert(record(MEMBER_RECORD_UUID, updatedAt = 100L))
        database.recordDao().upsert(record(OWNER_RECORD_UUID, updatedAt = 110L))
        database.recordDao().upsert(record(INDEPENDENT_RECORD_UUID, updatedAt = 120L))
    }

    private suspend fun assertPreSettlementState() {
        val plan = database.carePlanDao().getByClientUuid(PLAN_UUID)!!
        assertThat(plan.fulfilledRecordClientUuid).isEqualTo(MEMBER_RECORD_UUID)
        assertThat(plan.fulfilledAt).isEqualTo(20L)
        assertThat(plan.updatedAt).isEqualTo(90L)
        assertThat(plan.syncDirty).isFalse()
        assertThat(
            database.fulfillmentCandidateDao().listForCarePlan(PLAN_UUID)
                .associate { it.clientUuid to it.adoptionStatus },
        ).containsExactly(
            MEMBER_CANDIDATE_UUID, "",
            OWNER_CANDIDATE_UUID, "",
        )
    }

    private suspend fun authoritySnapshot(): AuthoritySnapshot = AuthoritySnapshot(
        planRecordClientUuid = database.carePlanDao().getByClientUuid(PLAN_UUID)
            ?.fulfilledRecordClientUuid,
        adoptionByCandidate = database.fulfillmentCandidateDao().listForCarePlan(PLAN_UUID)
            .associate { it.clientUuid to it.adoptionStatus },
    )

    private suspend fun clearAuthorityGraph() {
        database.fulfillmentCandidateDao().deleteAll()
        database.carePlanDao().deleteAll()
    }

    private fun candidate(
        clientUuid: String,
        recordClientUuid: String,
        role: String,
        confirmedAt: Long,
        updatedAt: Long,
        syncDirty: Boolean,
        convertedRecordClientUuid: String = "",
    ) = FulfillmentCandidateEntity(
        clientUuid = clientUuid,
        carePlanClientUuid = PLAN_UUID,
        recordClientUuid = recordClientUuid,
        confirmedAt = confirmedAt,
        submitterRole = role,
        convertedRecordClientUuid = convertedRecordClientUuid,
        updatedAt = updatedAt,
        syncDirty = syncDirty,
    )

    private fun record(clientUuid: String, updatedAt: Long) = RecordEntity(
        clientUuid = clientUuid,
        babyId = BABY_ID,
        type = "formula",
        timestamp = 100L,
        payloadJson = """{"amount_ml":90}""",
        updatedAt = updatedAt,
        syncDirty = false,
    )

    private data class AuthoritySnapshot(
        val planRecordClientUuid: String?,
        val adoptionByCandidate: Map<String, String>,
    )

    private enum class FailurePoint {
        CandidatePatch,
        PlanRelink,
    }

    private class InjectedDaoFailure : IllegalStateException("injected DAO failure")

    private class FailingCandidateDao(
        private val delegate: FulfillmentCandidateDao,
    ) : FulfillmentCandidateDao by delegate {
        override suspend fun update(candidate: FulfillmentCandidateEntity) {
            delegate.update(candidate)
            throw InjectedDaoFailure()
        }
    }

    private class FailingCarePlanDao(
        private val delegate: CarePlanDao,
    ) : CarePlanDao by delegate {
        override suspend fun update(plan: CarePlanEntity) {
            delegate.update(plan)
            throw InjectedDaoFailure()
        }
    }

    private companion object {
        const val BABY_ID = 1L
        const val PLAN_UUID = "plan-a"
        const val MEMBER_CANDIDATE_UUID = "candidate-member"
        const val OWNER_CANDIDATE_UUID = "candidate-owner"
        const val THIRD_CANDIDATE_UUID = "candidate-third"
        const val MEMBER_RECORD_UUID = "record-member"
        const val OWNER_RECORD_UUID = "record-owner"
        const val INDEPENDENT_RECORD_UUID = "record-independent"
    }
}
