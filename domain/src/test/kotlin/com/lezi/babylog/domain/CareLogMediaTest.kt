package com.lezi.babylog.domain
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.CarePlanDao
import com.lezi.babylog.core.database.CarePlanEntity
import com.lezi.babylog.core.database.CustomItemDao
import com.lezi.babylog.core.database.CustomItemEntity
import com.lezi.babylog.core.database.FamilyDao
import com.lezi.babylog.core.database.FamilyEntity
import com.lezi.babylog.core.database.FulfillmentCandidateDao
import com.lezi.babylog.core.database.FulfillmentCandidateEntity
import com.lezi.babylog.core.database.LocalUserDao
import com.lezi.babylog.core.database.LocalUserEntity
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.MembershipDao
import com.lezi.babylog.core.database.MembershipEntity
import com.lezi.babylog.core.database.PendingReminderCleanup
import com.lezi.babylog.core.database.PendingReminderCleanupStore
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.datastore.LocalClearSettingsSnapshot
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.core.model.CarePlan
import com.lezi.babylog.core.model.CarePlanStatus
import com.lezi.babylog.core.model.CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION
import com.lezi.babylog.core.model.DeviceLayoutSnapshot
import com.lezi.babylog.core.model.NEXT_FEED_PLAN_MARKER
import com.lezi.babylog.core.model.NextFeedPlanReconciliation
import com.lezi.babylog.core.model.RecordItemIdentity
import com.lezi.babylog.core.model.RecordTime
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.Sex
import com.lezi.babylog.core.model.SettingsLocal
import com.lezi.babylog.core.model.displayLabel
import com.lezi.babylog.core.model.isNextFeedPlanNote
import com.lezi.babylog.core.model.isPlanableNonStateful
import com.lezi.babylog.core.model.itemIdentity
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.yield
import org.junit.Test
import com.lezi.babylog.domain.calendar.SYSTEM_CALENDAR_UNSYNCED_LABEL
import com.lezi.babylog.domain.calendar.SystemCalendarEventState
import com.lezi.babylog.domain.calendar.SystemCalendarOwnedEventLookup
import com.lezi.babylog.domain.calendar.SystemCalendarPort
import com.lezi.babylog.domain.calendar.SystemCalendarTarget
import com.lezi.babylog.domain.calendar.SystemCalendarUpsert
import com.lezi.babylog.domain.calendar.SystemCalendarUpsertOutcome
import com.lezi.babylog.domain.calendar.SystemCalendarUpsertResult
import com.lezi.babylog.domain.calendar.encodeSystemCalendarEventMap
import com.lezi.babylog.domain.calendar.parseSystemCalendarEventMap
import com.lezi.babylog.domain.carelog.CareAggregation
import com.lezi.babylog.domain.carelog.FakeMediaAssetDao
import com.lezi.babylog.domain.carelog.matchesSqlLike
import com.lezi.babylog.domain.carelog.weekStartFor
import com.lezi.babylog.domain.careplan.CarePlanReminderProjection
import com.lezi.babylog.domain.careplan.ReminderCleanupPort
import com.lezi.babylog.domain.careplan.nextFeedPlanClientUuid
import com.lezi.babylog.domain.growth.CareLogGrowthMeasurementRecordStore
import com.lezi.babylog.domain.growth.DefaultGrowthMeasurementLifecycle
import com.lezi.babylog.domain.growth.GrowthMeasurementSaveResult
import com.lezi.babylog.domain.growth.GrowthReferenceSource
import com.lezi.babylog.domain.growth.SaveGrowthMeasurement
import com.lezi.babylog.domain.localdata.CalendarReminderMutationGuard
import com.lezi.babylog.domain.localdata.LocalDataClearInProgressException
import com.lezi.babylog.domain.localdata.LocalDataMutationEpoch
import com.lezi.babylog.domain.localdata.DaoLocalDataClearPersistence
import com.lezi.babylog.domain.localdata.DefaultLocalDataClearCoordinator
import com.lezi.babylog.domain.localdata.LocalDataClearCoordinator
import com.lezi.babylog.domain.localdata.LocalDataClearScope
import com.lezi.babylog.domain.localdata.LocalRecordsClearCommittedException
import com.lezi.babylog.domain.localdata.NursingTimerCleanupPort
import com.lezi.babylog.domain.localdata.StoreLocalDataClearSettings
import com.lezi.babylog.domain.nextSyncUpdatedAt
import com.lezi.babylog.domain.toModel

// Split from CareLogTest kitchen sink by contract cluster (ticket 06).
class CareLogMediaTest {
    @Test
    fun carePlanPhotosCreateUpdateFulfillAndTombstoneKeepOwnership() = runTest {
        val sync = RecordingSyncPort()
        val fakes = Fakes(sync)
        fakes.wireTransactionalSnapshots()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = 20_000_000L
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.PEE,
            scheduledAt = now + 60_000L,
            note = "带图计划",
            photoLocalPaths = listOf("plans/a.jpg", "plans/b.jpg"),
            nowMillis = now,
        )
        assertThat(care.listCarePlanPhotoPaths(planId)).containsExactly("plans/a.jpg", "plans/b.jpg")
        val planMedia = fakes.media.listActiveForCarePlan(planId)
        assertThat(planMedia).hasSize(2)
        assertThat(planMedia.all { it.recordId == null && it.carePlanId == planId }).isTrue()

        care.updateCarePlan(
            carePlanId = planId,
            scheduledAt = now + 90_000L,
            photoLocalPaths = listOf("plans/a.jpg", "plans/c.jpg"),
            nowMillis = now + 1,
        )
        assertThat(care.listCarePlanPhotoPaths(planId)).containsExactly("plans/a.jpg", "plans/c.jpg")
        val tombstonedB = fakes.media.listForCarePlan(planId).first { it.localUri == "plans/b.jpg" }
        assertThat(tombstonedB.deletedAt).isNotNull()
        assertThat(sync.mediaCleanupCandidates).containsExactly(setOf(tombstonedB.clientUuid))

        val recordId = care.fulfillCarePlan(
            carePlanId = planId,
            actualTimestamp = now,
            photoLocalPaths = listOf("plans/a.jpg", "plans/c.jpg"),
            nowMillis = now + 2,
        )
        // Record owns its own media rows; plan rows remain plan-owned.
        val recordMedia = fakes.media.listActiveForRecord(recordId)
        assertThat(recordMedia.map { it.localUri }).containsExactly("plans/a.jpg", "plans/c.jpg")
        assertThat(recordMedia.all { it.carePlanId == null && it.recordId == recordId }).isTrue()
        assertThat(fakes.media.listActiveForCarePlan(planId).map { it.localUri })
            .containsExactly("plans/a.jpg", "plans/c.jpg")

        // Soft-delete another plan with photos → media tombstones, files not required gone.
        val plan2 = care.createCarePlan(
            babyId = babyId,
            type = RecordType.BATH,
            scheduledAt = now + 120_000L,
            photoLocalPaths = listOf("plans/x.jpg"),
            nowMillis = now + 3,
        )
        care.deleteCarePlan(plan2, nowMillis = now + 4)
        assertThat(fakes.media.listActiveForCarePlan(plan2)).isEmpty()
        val deletedPlanMedia = fakes.media.listForCarePlan(plan2).single()
        assertThat(deletedPlanMedia.deletedAt).isNotNull()
        assertThat(sync.mediaCleanupCandidates)
            .containsExactly(
                setOf(tombstonedB.clientUuid),
                setOf(deletedPlanMedia.clientUuid),
            ).inOrder()
    }
    @Test
    fun carePlanPhotoOnlyUpdateCanReplaceAndExplicitlyClearAttachments() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = 20_250_000L
        val scheduledAt = now + 60_000L
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.PEE,
            scheduledAt = scheduledAt,
            photoLocalPaths = listOf("plans/a.jpg", "plans/b.jpg"),
            nowMillis = now,
        )
        val beforeUpdate = fakes.carePlans.get(planId)!!

        care.updateCarePlan(
            carePlanId = planId,
            scheduledAt = scheduledAt,
            photoLocalPaths = listOf("plans/b.jpg", "plans/c.jpg"),
            nowMillis = now + 1L,
        )

        assertThat(care.listCarePlanPhotoPaths(planId))
            .containsExactly("plans/b.jpg", "plans/c.jpg").inOrder()
        assertThat(fakes.carePlans.get(planId)!!.updatedAt).isGreaterThan(beforeUpdate.updatedAt)

        care.updateCarePlan(
            carePlanId = planId,
            scheduledAt = scheduledAt,
            photoLocalPaths = emptyList(),
            nowMillis = now + 2L,
        )

        assertThat(care.listCarePlanPhotoPaths(planId)).isEmpty()
        assertThat(fakes.media.listForCarePlan(planId).all { it.deletedAt != null }).isTrue()
    }
    @Test
    fun carePlanPhotoReplaceFailureRollsBackMediaAndLeavesRootUnchanged() = runTest {
        val fakes = Fakes()
        fakes.wireTransactionalSnapshots()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = 20_500_000L
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.PEE,
            scheduledAt = now + 60_000L,
            note = "原计划",
            photoLocalPaths = listOf("plans/a.jpg", "plans/b.jpg"),
            nowMillis = now,
        )
        care.updateCarePlan(
            carePlanId = planId,
            scheduledAt = now + 90_000L,
            note = "原计划",
            photoLocalPaths = listOf("plans/a.jpg"),
            nowMillis = now + 1L,
        )
        val planBefore = fakes.carePlans.get(planId)
        val mediaBefore = fakes.media.listForCarePlan(planId)
        fakes.media.failUpdateAfterSuccessfulUpdates(1)

        val error = runCatching {
            care.updateCarePlan(
                carePlanId = planId,
                scheduledAt = now + 120_000L,
                note = "不应提交",
                photoLocalPaths = listOf("plans/b.jpg", "plans/c.jpg"),
                nowMillis = now + 2L,
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalStateException::class.java)
        assertThat(error).hasMessageThat().contains("media update failed")
        assertThat(fakes.carePlans.get(planId)).isEqualTo(planBefore)
        assertThat(fakes.media.listForCarePlan(planId)).containsExactlyElementsIn(mediaBefore)
        assertThat(care.listCarePlanPhotoPaths(planId)).containsExactly("plans/a.jpg")
    }
    @Test
    fun fulfillCarePlanKeepsOriginalPlanPhotosAndRecordsOnlyConfirmedDraftOrder() = runTest {
        val fakes = Fakes()
        fakes.wireTransactionalSnapshots()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = 21_000_000L
        val planPhotos = listOf("plans/a.jpg", "plans/b.jpg", "plans/c.jpg")
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.PEE,
            scheduledAt = now + 60_000L,
            photoLocalPaths = planPhotos,
            nowMillis = now,
        )

        val recordId = care.fulfillCarePlan(
            carePlanId = planId,
            actualTimestamp = now,
            // Removed plan b, retained a/c, imported draft.jpg; duplicate a is normalized.
            photoLocalPaths = listOf("plans/a.jpg", "plans/c.jpg", "draft.jpg", "plans/a.jpg"),
            nowMillis = now + 1L,
        )

        assertThat(care.listCarePlanPhotoPaths(planId)).containsExactlyElementsIn(planPhotos).inOrder()
        assertThat(care.listRecordPhotoPaths(recordId))
            .containsExactly("plans/a.jpg", "plans/c.jpg", "draft.jpg")
            .inOrder()
        assertThat(fakes.media.listActiveForCarePlan(planId).all { it.recordId == null }).isTrue()
        assertThat(fakes.media.listActiveForRecord(recordId).all { it.carePlanId == null }).isTrue()
    }
    @Test
    fun fulfillCarePlanMediaFailureRollsBackFactAndLeavesPlanPhotosUnchanged() = runTest {
        val fakes = Fakes()
        fakes.wireTransactionalSnapshots()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = 22_000_000L
        val planPhotos = listOf("plans/a.jpg", "plans/b.jpg")
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.PEE,
            scheduledAt = now + 60_000L,
            photoLocalPaths = planPhotos,
            nowMillis = now,
        )
        val mediaBefore = fakes.media.listAllIncludingDeleted()

        fakes.media.failUpserts = true
        val error = runCatching {
            care.fulfillCarePlan(
                carePlanId = planId,
                actualTimestamp = now,
                photoLocalPaths = listOf("plans/a.jpg", "draft.jpg"),
                nowMillis = now + 1L,
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalStateException::class.java)
        assertThat(fakes.records.listAllIncludingDeleted()).isEmpty()
        assertThat(fakes.fulfillmentCandidates.listAllIncludingDeleted()).isEmpty()
        assertThat(care.getCarePlan(planId)!!.status).isEqualTo(CarePlanStatus.PENDING)
        assertThat(care.listCarePlanPhotoPaths(planId)).containsExactlyElementsIn(planPhotos).inOrder()
        assertThat(fakes.media.listAllIncludingDeleted()).containsExactlyElementsIn(mediaBefore)
    }
    @Test
    fun rootPublicationReceiptMapsToRecordAndCarePlanWithoutMediaEvidence() {
        val record = RecordEntity(
            clientUuid = "record-root-receipt",
            babyId = 1,
            type = "formula",
            timestamp = 1_000,
            updatedAt = 2_000,
            familyPublishedUpdatedAt = 1_900,
        ).toModel()
        val plan = CarePlanEntity(
            clientUuid = "plan-root-receipt",
            babyId = 1,
            type = "formula",
            scheduledAt = 2_500,
            scheduledZoneId = "UTC",
            updatedAt = 3_000,
            familyPublishedUpdatedAt = 2_900,
        ).toModel()

        assertThat(record.familyPublishedUpdatedAt).isEqualTo(1_900)
        assertThat(plan.familyPublishedUpdatedAt).isEqualTo(2_900)
    }
    @Test
    fun growthMeasurementEditPreservesExistingRecordPhotos() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = System.currentTimeMillis()
        val recordId = care.addRecord(
            babyId = babyId,
            type = RecordType.WEIGHT,
            timestamp = now - 1_000L,
            payloadJson = """{"value":6500,"unit":"g"}""",
            photoLocalPaths = listOf("record-media/growth.jpg"),
        )
        val lifecycle = DefaultGrowthMeasurementLifecycle(
            CareLogGrowthMeasurementRecordStore(care),
            object : GrowthReferenceSource {
                override fun reference(type: RecordType, sex: Sex?) = null
            },
        )

        val result = lifecycle.save(
            SaveGrowthMeasurement(
                babyId = babyId,
                type = RecordType.WEIGHT,
                displayValue = 6.7,
                measuredAt = now - 500L,
                note = "复测",
                existingRecordId = recordId,
                nowMillis = now,
            ),
        )

        assertThat(result).isEqualTo(GrowthMeasurementSaveResult.Saved(recordId))
        assertThat(care.listRecordPhotoPaths(recordId))
            .containsExactly("record-media/growth.jpg")
    }
    @Test
    fun addRecordAttachesUpToThreePhotosInOneTransactionForAnyType() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val paths = listOf(
            "/data/user/0/com.lezi/files/record-media/a.jpg",
            "/data/user/0/com.lezi/files/record-media/b.jpg",
            "/data/user/0/com.lezi/files/record-media/c.jpg",
        )

        val recordId = care.addRecord(
            babyId = babyId,
            type = RecordType.PEE,
            timestamp = 1_000L,
            payloadJson = """{"pee_amount":2}""",
            photoLocalPaths = paths,
        )

        assertThat(care.listRecordPhotoPaths(recordId)).containsExactlyElementsIn(paths).inOrder()
        assertThat(fakes.media.listActiveForRecord(recordId).map { it.localUri })
            .containsExactlyElementsIn(paths)
        val stored = fakes.records.get(recordId)!!
        assertThat(stored.payloadJson).doesNotContain("\"photos\"")
        assertThat(stored.payloadJson).doesNotContain("a.jpg")
        assertThat(fakes.transactions.runCount).isAtLeast(1)

        val overLimit = runCatching {
            care.addRecord(
                babyId = babyId,
                type = RecordType.FORMULA,
                timestamp = 2_000L,
                payloadJson = """{"amount_ml":120}""",
                photoLocalPaths = paths + "/data/user/0/com.lezi/files/record-media/d.jpg",
            )
        }.exceptionOrNull()
        assertThat(overLimit).isInstanceOf(IllegalArgumentException::class.java)
    }
    @Test
    fun recordPhotoAttachDuringLocalClearEpochFailsWithoutHalfRoot() = runTest {
        val sync = RecordingSyncPort()
        val mutationEpoch = LocalDataMutationEpoch()
        val fakes = Fakes(syncPort = sync, localDataMutationEpoch = mutationEpoch)
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val clearEntered = CompletableDeferred<Unit>()
        val allowClearToFinish = CompletableDeferred<Unit>()
        sync.localClearEntered = clearEntered
        sync.allowLocalClearToContinue = allowClearToFinish
        val clearing = launch {
            fakes.localDataClearCoordinator(sync).clear(LocalDataClearScope.RecordsOnly)
        }
        clearEntered.await()

        val failure = runCatching {
            care.addRecord(
                babyId = babyId,
                type = RecordType.PEE,
                timestamp = 1_000L,
                payloadJson = """{"pee_amount":2}""",
                photoLocalPaths = listOf("record-media/concurrent-draft.jpg"),
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(LocalDataClearInProgressException::class.java)
        assertThat(fakes.records.listAllIncludingDeleted()).isEmpty()
        assertThat(fakes.media.listAllIncludingDeleted()).isEmpty()
        allowClearToFinish.complete(Unit)
        clearing.join()
    }
    @Test
    fun updateRecordReconcilesMediaAndExplicitEmptyClearsPhotos() = runTest {
        val sync = RecordingSyncPort()
        val fakes = Fakes(sync)
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val keep = "/data/user/0/com.lezi/files/record-media/keep.jpg"
        val drop = "/data/user/0/com.lezi/files/record-media/drop.jpg"
        val add = "/data/user/0/com.lezi/files/record-media/add.jpg"
        val recordId = care.addRecord(
            babyId = babyId,
            type = RecordType.DIARY,
            timestamp = 1_000L,
            payloadJson = """{"body":"日记"}""",
            photoLocalPaths = listOf(keep, drop),
        )

        care.updateRecord(
            id = recordId,
            timestamp = 1_100L,
            endTimestamp = null,
            note = null,
            payloadJson = """{"body":"日记改"}""",
            photoLocalPaths = listOf(keep, add),
        )

        assertThat(care.listRecordPhotoPaths(recordId)).containsExactly(keep, add).inOrder()
        val allMedia = fakes.media.listForRecord(recordId)
        assertThat(allMedia.filter { it.deletedAt == null }.map { it.localUri })
            .containsExactly(keep, add)
        val dropped = allMedia.single { it.localUri == drop }
        assertThat(dropped.deletedAt).isNotNull()
        assertThat(sync.mediaCleanupCandidates).containsExactly(setOf(dropped.clientUuid))

        care.updateRecord(
            id = recordId,
            timestamp = 1_200L,
            endTimestamp = null,
            note = null,
            payloadJson = """{"body":"日记改"}""",
            photoLocalPaths = emptyList(),
        )
        assertThat(care.listRecordPhotoPaths(recordId)).isEmpty()
        assertThat(fakes.media.listForRecord(recordId).all { it.deletedAt != null }).isTrue()

        care.deleteRecord(recordId)
        assertThat(fakes.media.listActiveForRecord(recordId)).isEmpty()
        assertThat(fakes.media.listForRecord(recordId).all { it.deletedAt != null }).isTrue()
        assertThat(fakes.records.getIncludingDeleted(recordId)!!.deletedAt).isNotNull()
    }
    @Test
    fun deleteRecordHandsExactPhotoTombstonesToCommittedCleanup() = runTest {
        val sync = RecordingSyncPort()
        val fakes = Fakes(sync)
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val recordId = care.addRecord(
            babyId = babyId,
            type = RecordType.DIARY,
            timestamp = 1_000L,
            payloadJson = """{"body":"日记"}""",
            photoLocalPaths = listOf("record/delete.jpg"),
        )
        val mediaUuid = fakes.media.listActiveForRecord(recordId).single().clientUuid

        care.deleteRecord(recordId)

        assertThat(sync.mediaCleanupCandidates).containsExactly(setOf(mediaUuid))
        assertThat(fakes.media.getByClientUuid(mediaUuid)?.deletedAt).isNotNull()
    }
    @Test
    fun damagedPayloadPhotoFieldIsReadOnlyAndNeverActsAsMedia() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val damagedPayload = """{"body":"日记","photos":["current/a.jpg","current/b.jpg"]}"""
        val recordId = fakes.records.upsert(
            RecordEntity(
                clientUuid = "current-diary",
                babyId = babyId,
                type = RecordType.DIARY.key,
                timestamp = 1_000L,
                endTimestamp = null,
                note = null,
                payloadJson = damagedPayload,
                schemaVersion = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
                updatedAt = 1_000L,
            ),
        )

        assertThat(care.listRecordPhotoPaths(recordId)).isEmpty()
        assertThat(fakes.media.listActiveForRecord(recordId)).isEmpty()

        val failure = runCatching {
            care.updateRecord(
                id = recordId,
                timestamp = 1_000L,
                endTimestamp = null,
                note = null,
                payloadJson = """{"body":"日记"}""",
                photoLocalPaths = listOf("current/a.jpg", "current/b.jpg"),
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(fakes.media.listActiveForRecord(recordId)).isEmpty()
        assertThat(fakes.records.get(recordId)!!.payloadJson).isEqualTo(damagedPayload)
    }
    @Test
    fun confirmSleepPersistsPhotosWithOpenAndClosedIntervals() = runTest {
        val sync = RecordingSyncPort()
        val fakes = Fakes(sync)
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val path = "/data/user/0/com.lezi/files/record-media/sleep.jpg"
        val removedPath = "/data/user/0/com.lezi/files/record-media/sleep-removed.jpg"
        val openId = care.confirmSleep(
            babyId = babyId,
            expectedOpenSleepId = null,
            timestamp = 1_000L,
            endTimestamp = null,
            note = null,
            payloadJson = """{"is_nap":true,"anomaly_flag":false}""",
            photoLocalPaths = listOf(path, removedPath),
        )
        assertThat(care.listRecordPhotoPaths(openId)).containsExactly(path, removedPath)
        val removedUuid = fakes.media.listActiveForRecord(openId)
            .single { it.localUri == removedPath }
            .clientUuid

        care.confirmSleep(
            babyId = babyId,
            expectedOpenSleepId = openId,
            timestamp = 1_000L,
            endTimestamp = 3_600_000L,
            note = "小睡",
            payloadJson = """{"is_nap":true,"anomaly_flag":false}""",
            photoLocalPaths = listOf(path),
        )
        assertThat(care.listRecordPhotoPaths(openId)).containsExactly(path)
        assertThat(sync.mediaCleanupCandidates).containsExactly(setOf(removedUuid))
        assertThat(fakes.records.get(openId)!!.payloadJson).doesNotContain("sleep.jpg")
    }
    @Test
    fun addRecordPhotoAttachFailureLeavesNoVisibleRecordOrMedia() = runTest {
        val fakes = Fakes()
        fakes.wireTransactionalSnapshots()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val recordsBefore = fakes.records.listAllIncludingDeleted().size
        val mediaBefore = fakes.media.listAllIncludingDeleted().size

        fakes.media.failUpserts = true
        val err = runCatching {
            care.addRecord(
                babyId = babyId,
                type = RecordType.FORMULA,
                timestamp = 2_000L,
                payloadJson = """{"amount_ml":120}""",
                photoLocalPaths = listOf("/data/user/0/com.lezi/files/record-media/boom.jpg"),
            )
        }.exceptionOrNull()

        assertThat(err).isInstanceOf(IllegalStateException::class.java)
        assertThat(err!!.message).contains("media upsert failed")
        // Same domain transaction as record insert — failure must not leave orphans.
        assertThat(fakes.records.listAllIncludingDeleted()).hasSize(recordsBefore)
        assertThat(fakes.media.listAllIncludingDeleted()).hasSize(mediaBefore)
    }
    @Test
    fun onFamilyCarePlansAppliedCancelsAPastPlanInsteadOfSchedulingAnImmediateReminder() =
        runTest {
            val fakes = Fakes()
            fakes.systemCalendar.permission = false
            val care = fakes.careLog()
            val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
            val planId = care.createCarePlan(
                babyId = babyId,
                type = RecordType.PEE,
                scheduledAt = 10_000L,
                nowMillis = 1_000L,
            )
            val planUuid = care.getCarePlan(planId)!!.clientUuid
            fakes.carePlans.update(fakes.carePlans.get(planId)!!.copy(syncDirty = false))
            fakes.reminders.scheduledCarePlanIds.clear()
            fakes.reminders.cancelledCarePlanIds.clear()

            care.onFamilyCarePlansApplied(
                planClientUuids = listOf(planUuid),
                nowMillis = 20_000L,
            )

            assertThat(fakes.reminders.scheduledCarePlanIds).doesNotContain(planId)
            assertThat(fakes.reminders.cancelledCarePlanIds).contains(planId)
        }
    @Test
    fun completeNursingWithCarePlanPhotosClonesIndependentActiveMediaInOrder() = runTest {
        val fakes = Fakes()
        fakes.wireTransactionalSnapshots()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = 80_000_000L
        val planPhotos = listOf("plans/n1.jpg", "plans/n2.jpg", "plans/n3.jpg")
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.NURSING,
            scheduledAt = now + 60_000L,
            payloadJson =
                """{"left_min":0,"right_min":0,"order":"LR","record_mode":"end"}""",
            photoLocalPaths = planPhotos,
            nowMillis = now,
        )
        val planMediaBefore = fakes.media.listActiveForCarePlan(planId)
        assertThat(planMediaBefore.map { it.localUri }).containsExactlyElementsIn(planPhotos).inOrder()
        val planClientUuids = planMediaBefore.map { it.clientUuid }.toSet()

        val recordId = care.completeNursing(
            babyId = babyId,
            leftMin = 6,
            rightMin = 3,
            order = "LR",
            startedAt = now - 10 * 60_000L,
            endedAt = now,
            completionClientUuid = "timer-plan-photos-uuid",
            carePlanId = planId,
            nowMillis = now,
        )

        // Record gets same-order active media with independent client UUIDs.
        assertThat(care.listRecordPhotoPaths(recordId))
            .containsExactlyElementsIn(planPhotos)
            .inOrder()
        val recordMedia = fakes.media.listActiveForRecord(recordId)
        assertThat(recordMedia).hasSize(3)
        assertThat(recordMedia.map { it.clientUuid }.toSet())
            .containsNoneIn(planClientUuids)
        assertThat(recordMedia.all { it.carePlanId == null && it.recordId == recordId && it.syncDirty })
            .isTrue()

        // Plan media ownership, order, and local paths stay active (shared bytes OK).
        assertThat(care.listCarePlanPhotoPaths(planId))
            .containsExactlyElementsIn(planPhotos)
            .inOrder()
        assertThat(fakes.media.listActiveForCarePlan(planId).map { it.clientUuid }.toSet())
            .isEqualTo(planClientUuids)
        assertThat(fakes.media.listActiveForCarePlan(planId).all { it.recordId == null }).isTrue()
        assertThat(care.getCarePlan(planId)!!.status).isEqualTo(CarePlanStatus.COMPLETED)
    }
    @Test
    fun completeNursingWithCarePlanPhotosReplayDoesNotRecloneMedia() = runTest {
        val fakes = Fakes()
        fakes.wireTransactionalSnapshots()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = 81_000_000L
        val planPhotos = listOf("plans/a.jpg", "plans/b.jpg")
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.NURSING,
            scheduledAt = now + 60_000L,
            payloadJson =
                """{"left_min":0,"right_min":0,"order":"LR","record_mode":"end"}""",
            photoLocalPaths = planPhotos,
            nowMillis = now,
        )
        val completionUuid = "timer-plan-photos-replay"
        val first = care.completeNursing(
            babyId = babyId,
            leftMin = 5,
            rightMin = 2,
            order = "L",
            startedAt = now - 8 * 60_000L,
            endedAt = now,
            completionClientUuid = completionUuid,
            carePlanId = planId,
            nowMillis = now,
        )
        val mediaAfterFirst = fakes.media.listActiveForRecord(first).map { it.clientUuid }
        assertThat(mediaAfterFirst).hasSize(2)

        val replay = care.completeNursing(
            babyId = babyId,
            leftMin = 5,
            rightMin = 2,
            order = "L",
            startedAt = now - 8 * 60_000L,
            endedAt = now,
            completionClientUuid = completionUuid,
            carePlanId = planId,
            nowMillis = now + 1L,
        )
        assertThat(replay).isEqualTo(first)
        assertThat(fakes.media.listActiveForRecord(first).map { it.clientUuid })
            .containsExactlyElementsIn(mediaAfterFirst)
            .inOrder()
        // Plan still holds its original two rows; no extra clones anywhere.
        assertThat(care.listCarePlanPhotoPaths(planId))
            .containsExactlyElementsIn(planPhotos)
            .inOrder()
        assertThat(
            fakes.media.listAllIncludingDeleted().count {
                it.kind == "log" && it.deletedAt == null
            },
        ).isEqualTo(4) // 2 plan + 2 record
    }
    @Test
    fun completeNursingPlanPhotoCloneFailureRollsBackRecordAndPlan() = runTest {
        val fakes = Fakes()
        fakes.wireTransactionalSnapshots()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = 82_000_000L
        val planPhotos = listOf("plans/x.jpg", "plans/y.jpg")
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.NURSING,
            scheduledAt = now + 60_000L,
            payloadJson =
                """{"left_min":0,"right_min":0,"order":"LR","record_mode":"end"}""",
            photoLocalPaths = planPhotos,
            nowMillis = now,
        )
        val mediaBefore = fakes.media.listAllIncludingDeleted()
        val planBefore = fakes.carePlans.get(planId)!!

        fakes.media.failUpserts = true
        val error = runCatching {
            care.completeNursing(
                babyId = babyId,
                leftMin = 4,
                rightMin = 1,
                order = "RL",
                startedAt = now - 5 * 60_000L,
                endedAt = now,
                completionClientUuid = "timer-plan-photos-fail",
                carePlanId = planId,
                nowMillis = now,
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalStateException::class.java)
        assertThat(fakes.records.listAllIncludingDeleted()).isEmpty()
        assertThat(fakes.fulfillmentCandidates.listAllIncludingDeleted()).isEmpty()
        assertThat(care.getCarePlan(planId)!!.status).isEqualTo(CarePlanStatus.PENDING)
        assertThat(fakes.carePlans.get(planId)).isEqualTo(planBefore)
        assertThat(care.listCarePlanPhotoPaths(planId))
            .containsExactlyElementsIn(planPhotos)
            .inOrder()
        assertThat(fakes.media.listAllIncludingDeleted()).containsExactlyElementsIn(mediaBefore)
    }
    @Test
    fun completeNursingWithoutPlanPhotosOrCarePlanIdLeavesRecordMediaEmpty() = runTest {
        val fakes = Fakes()
        fakes.wireTransactionalSnapshots()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = 83_000_000L
        // No-photo plan still completes without inventing media.
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.NURSING,
            scheduledAt = now + 60_000L,
            payloadJson =
                """{"left_min":0,"right_min":0,"order":"LR","record_mode":"end"}""",
            nowMillis = now,
        )
        val withPlan = care.completeNursing(
            babyId = babyId,
            leftMin = 3,
            rightMin = 0,
            order = "L",
            startedAt = now - 4 * 60_000L,
            endedAt = now,
            completionClientUuid = "timer-no-plan-photos",
            carePlanId = planId,
            nowMillis = now,
        )
        assertThat(care.listRecordPhotoPaths(withPlan)).isEmpty()
        assertThat(care.getCarePlan(planId)!!.status).isEqualTo(CarePlanStatus.COMPLETED)

        // Non-plan timer completion is unchanged (no media).
        val freeTimer = care.completeNursing(
            babyId = babyId,
            leftMin = 2,
            rightMin = 2,
            order = "LR",
            startedAt = now - 3 * 60_000L,
            endedAt = now + 1L,
            completionClientUuid = "timer-no-plan-at-all",
            carePlanId = null,
            nowMillis = now + 1L,
        )
        assertThat(care.listRecordPhotoPaths(freeTimer)).isEmpty()
        assertThat(fakes.media.listActiveForRecord(freeTimer)).isEmpty()
    }
    @Test
    fun completeNursingWithSeedPhotoPathsMergesOntoRecordAndKeepsPlanMedia() = runTest {
        // Ticket 09: explicit seed photo list (seed + live plan, pre-merged) attaches to Record.
        val fakes = Fakes()
        fakes.wireTransactionalSnapshots()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = 84_500_000L
        val planPhotos = listOf("plans/live-a.jpg", "plans/live-b.jpg")
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.NURSING,
            scheduledAt = now + 60_000L,
            payloadJson =
                """{"left_min":0,"right_min":0,"order":"LR","record_mode":"end"}""",
            photoLocalPaths = planPhotos,
            nowMillis = now,
        )
        val planClientUuids = fakes.media.listActiveForCarePlan(planId).map { it.clientUuid }.toSet()
        // Seed order: owned import first, then one plan path; live plan also has live-b.
        val merged = listOf("imports/owned.jpg", "plans/live-a.jpg", "plans/live-b.jpg")
        val recordId = care.completeNursing(
            babyId = babyId,
            leftMin = 4,
            rightMin = 1,
            order = "LR",
            startedAt = now - 5 * 60_000L,
            endedAt = now,
            completionClientUuid = "timer-seed-photos-uuid",
            carePlanId = planId,
            photoLocalPaths = merged,
            nowMillis = now,
        )
        assertThat(care.listRecordPhotoPaths(recordId))
            .containsExactlyElementsIn(merged)
            .inOrder()
        assertThat(fakes.media.listActiveForRecord(recordId).map { it.clientUuid }.toSet())
            .containsNoneIn(planClientUuids)
        assertThat(care.listCarePlanPhotoPaths(planId))
            .containsExactlyElementsIn(planPhotos)
            .inOrder()
        assertThat(care.getCarePlan(planId)!!.status).isEqualTo(CarePlanStatus.COMPLETED)

        // Free-timer path with seed-only photos (no care plan).
        val freeId = care.completeNursing(
            babyId = babyId,
            leftMin = 2,
            rightMin = 0,
            order = "L",
            startedAt = now - 3 * 60_000L,
            endedAt = now + 1L,
            completionClientUuid = "timer-seed-only-uuid",
            carePlanId = null,
            photoLocalPaths = listOf("imports/solo.jpg"),
            nowMillis = now + 1L,
        )
        assertThat(care.listRecordPhotoPaths(freeId)).containsExactly("imports/solo.jpg")
    }
    @Test
    fun completeNursingUsesLivePlanMediaNotStaleSnapshot() = runTest {
        val fakes = Fakes()
        fakes.wireTransactionalSnapshots()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = 84_000_000L
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.NURSING,
            scheduledAt = now + 60_000L,
            payloadJson =
                """{"left_min":0,"right_min":0,"order":"LR","record_mode":"end"}""",
            photoLocalPaths = listOf("plans/old-a.jpg", "plans/old-b.jpg"),
            nowMillis = now,
        )
        // Simulate plan photos changing after timer started (no UI photo args on complete).
        care.updateCarePlan(
            carePlanId = planId,
            scheduledAt = now + 60_000L,
            photoLocalPaths = listOf("plans/live-only.jpg"),
            nowMillis = now + 1L,
        )
        assertThat(care.listCarePlanPhotoPaths(planId)).containsExactly("plans/live-only.jpg")

        val recordId = care.completeNursing(
            babyId = babyId,
            leftMin = 7,
            rightMin = 0,
            order = "R",
            startedAt = now - 6 * 60_000L,
            endedAt = now + 2L,
            completionClientUuid = "timer-live-plan-media",
            carePlanId = planId,
            nowMillis = now + 2L,
        )
        assertThat(care.listRecordPhotoPaths(recordId)).containsExactly("plans/live-only.jpg")
        assertThat(care.listCarePlanPhotoPaths(planId)).containsExactly("plans/live-only.jpg")
    }
    @Test
    fun completeNursingClonedPlanPhotosKeepSharedPathWhileEitherOwnerActive() = runTest {
        val sync = RecordingSyncPort()
        val fakes = Fakes(sync)
        fakes.wireTransactionalSnapshots()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = 85_000_000L
        val planPhotos = listOf("plans/share-a.jpg", "plans/share-b.jpg")
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.NURSING,
            scheduledAt = now + 60_000L,
            payloadJson =
                """{"left_min":0,"right_min":0,"order":"LR","record_mode":"end"}""",
            photoLocalPaths = planPhotos,
            nowMillis = now,
        )
        val planMediaUuids = fakes.media.listActiveForCarePlan(planId).map { it.clientUuid }.toSet()

        val recordId = care.completeNursing(
            babyId = babyId,
            leftMin = 5,
            rightMin = 1,
            order = "LR",
            startedAt = now - 7 * 60_000L,
            endedAt = now,
            completionClientUuid = "timer-shared-path-refs",
            carePlanId = planId,
            nowMillis = now,
        )
        val recordMediaUuids = fakes.media.listActiveForRecord(recordId).map { it.clientUuid }.toSet()
        // Both owners reference each shared path after timer clone.
        planPhotos.forEach { path ->
            assertThat(fakes.media.countActiveReferences(path)).isEqualTo(2)
        }

        // Tombstone plan media only: shared paths stay reclaim-blocked by record rows.
        assertThat(care.deleteCarePlan(planId, nowMillis = now + 1L)).isTrue()
        planPhotos.forEach { path ->
            assertThat(fakes.media.countActiveReferences(path)).isEqualTo(1)
        }
        assertThat(fakes.media.listActiveForCarePlan(planId)).isEmpty()
        assertThat(care.listRecordPhotoPaths(recordId)).containsExactlyElementsIn(planPhotos).inOrder()
        assertThat(sync.mediaCleanupCandidates).contains(planMediaUuids)
        // Domain hands tombstone UUIDs to sync cleanup; physical delete must not run
        // while countActiveReferences > 0 (ReferenceAwareMediaFileCleanup contract).
        assertThat(planPhotos.any { fakes.media.countActiveReferences(it) > 0 }).isTrue()

        // Tombstone record side: last active refs drop; cleanup is again requested.
        assertThat(care.deleteRecord(recordId)).isTrue()
        planPhotos.forEach { path ->
            assertThat(fakes.media.countActiveReferences(path)).isEqualTo(0)
        }
        assertThat(sync.mediaCleanupCandidates).contains(recordMediaUuids)
    }
    @Test
    fun localCarePlanReminderToggleImmediatelyCancelsAndRebuildsOpenPlans() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = System.currentTimeMillis()
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.BATH,
            scheduledAt = now + 120_000L,
            nowMillis = now,
        )
        assertThat(fakes.reminders.scheduledCarePlanIds).contains(planId)

        care.setCarePlanLocalRemindersEnabled(false, nowMillis = now + 1)
        assertThat(fakes.reminders.scheduledCarePlanIds).doesNotContain(planId)
        assertThat(fakes.settings.settings.first().carePlanLocalRemindersEnabled).isFalse()

        care.setCarePlanLocalRemindersEnabled(true, nowMillis = now + 2)
        assertThat(fakes.settings.settings.first().carePlanLocalRemindersEnabled).isTrue()
        assertThat(fakes.reminders.scheduledCarePlanIds).contains(planId)
    }
    @Test
    fun movingAPlanToMissedRemovesItsFutureProjectionImmediately() = runTest {
        val fakes = Fakes()
        fakes.systemCalendar.permission = true
        fakes.settings.setSystemCalendarEnabled(true)
        fakes.settings.setSystemCalendarId("cal-1")
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = System.currentTimeMillis()
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.BATH,
            scheduledAt = now + 120_000L,
            nowMillis = now,
        )
        val planUuid = care.getCarePlan(planId)!!.clientUuid
        val eventId = parseSystemCalendarEventMap(
            fakes.settings.settings.first().systemCalendarEventMapJson,
        ).getValue(planUuid)
        fakes.systemCalendar.deleted.clear()
        fakes.reminders.scheduledCarePlanIds.clear()

        care.updateCarePlan(
            carePlanId = planId,
            scheduledAt = now - 1L,
            nowMillis = now,
        )

        assertThat(fakes.systemCalendar.deleted).contains(eventId)
        assertThat(fakes.reminders.scheduledCarePlanIds).doesNotContain(planId)
        assertThat(fakes.reminders.cancelledCarePlanIds).contains(planId)
        assertThat(
            parseSystemCalendarEventMap(
                fakes.settings.settings.first().systemCalendarEventMapJson,
            ),
        ).doesNotContainKey(planUuid)
    }
    @Test
    fun convertRecordToCarePlanTransfersFieldsPhotosAndTombstonesRecord() = runTest {
        val sync = RecordingSyncPort()
        val fakes = Fakes(sync)
        fakes.wireTransactionalSnapshots()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = 30_000_000L
        val keep = "/data/user/0/com.lezi/files/record-media/keep.jpg"
        val drop = "/data/user/0/com.lezi/files/record-media/drop.jpg"
        val recordId = care.addRecord(
            babyId = babyId,
            type = RecordType.PEE,
            timestamp = now - 60_000L,
            note = "原备注",
            payloadJson = """{"pee_amount":2}""",
            photoLocalPaths = listOf(keep, drop),
        )
        val sourceUuid = fakes.records.get(recordId)!!.clientUuid
        val sourceMediaUuids = fakes.media.listActiveForRecord(recordId)
            .mapTo(linkedSetOf()) { it.clientUuid }

        val planId = care.convertRecordToCarePlan(
            recordId = recordId,
            scheduledAt = now + 120_000L,
            note = "改后备注",
            payloadJson = """{"pee_amount":3}""",
            photoLocalPaths = listOf(keep),
            nowMillis = now,
        )

        // Original fact is soft-deleted and gone from ordinary surfaces.
        assertThat(fakes.records.get(recordId)).isNull()
        assertThat(fakes.records.getIncludingDeleted(recordId)!!.deletedAt).isNotNull()
        assertThat(fakes.media.listActiveForRecord(recordId)).isEmpty()
        assertThat(fakes.media.listForRecord(recordId).all { it.deletedAt != null }).isTrue()

        val plan = care.getCarePlan(planId)!!
        assertThat(plan.status).isEqualTo(CarePlanStatus.PENDING)
        assertThat(plan.scheduledAt).isEqualTo(now + 120_000L)
        assertThat(plan.note).isEqualTo("改后备注")
        assertThat(plan.payloadJson).contains("\"pee_amount\":3")
        assertThat(plan.sourceRecordClientUuid).isEqualTo(sourceUuid)
        assertThat(care.listCarePlanPhotoPaths(planId)).containsExactly(keep)
        val planMedia = fakes.media.listActiveForCarePlan(planId)
        assertThat(planMedia.map { it.localUri }).containsExactly(keep)
        assertThat(planMedia.all { it.recordId == null && it.carePlanId == planId }).isTrue()
        // No dual-active ownership for the same path.
        assertThat(
            fakes.media.listAllIncludingDeleted()
                .filter { it.localUri == keep && it.deletedAt == null },
        ).hasSize(1)
        assertThat(sync.mediaCleanupCandidates).containsExactly(sourceMediaUuids)
        assertThat(fakes.reminders.scheduledCarePlanIds).contains(planId)
    }
    @Test
    fun convertRecordToCarePlanFailureLeavesRecordAndMediaIntact() = runTest {
        val fakes = Fakes()
        fakes.wireTransactionalSnapshots()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = 31_000_000L
        val path = "/data/user/0/com.lezi/files/record-media/stable.jpg"
        val recordId = care.addRecord(
            babyId = babyId,
            type = RecordType.FORMULA,
            timestamp = now - 10_000L,
            payloadJson = """{"amount_ml":120}""",
            photoLocalPaths = listOf(path),
        )
        val recordsBefore = fakes.records.listAllIncludingDeleted().size
        val mediaBefore = fakes.media.listAllIncludingDeleted().size
        val plansBefore = fakes.carePlans.listAllIncludingDeleted().size

        fakes.media.failUpserts = true
        val err = runCatching {
            care.convertRecordToCarePlan(
                recordId = recordId,
                scheduledAt = now + 60_000L,
                payloadJson = """{"amount_ml":120}""",
                photoLocalPaths = listOf(path),
                nowMillis = now,
            )
        }.exceptionOrNull()

        assertThat(err).isInstanceOf(IllegalStateException::class.java)
        assertThat(fakes.records.get(recordId)).isNotNull()
        assertThat(fakes.records.get(recordId)!!.deletedAt).isNull()
        assertThat(care.listRecordPhotoPaths(recordId)).containsExactly(path)
        assertThat(fakes.records.listAllIncludingDeleted()).hasSize(recordsBefore)
        assertThat(fakes.media.listAllIncludingDeleted()).hasSize(mediaBefore)
        assertThat(fakes.carePlans.listAllIncludingDeleted()).hasSize(plansBefore)
        assertThat(fakes.reminders.scheduledCarePlanIds).isEmpty()
    }
}
