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
class CareLogNextFeedTest {
    @Test
    fun nextFeedCarePlan_reusesStableIdentityAndCarriesNoFabricatedFeedFact() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = 1_800_000_000_000L

        val firstId = care.scheduleNextFeedCarePlan(
            babyId = babyId,
            feedType = RecordType.NURSING,
            scheduledAt = now + 60_000L,
            zone = zone,
            nowMillis = now,
        )
        val first = fakes.carePlans.get(firstId)!!
        val babyUuid = fakes.babies.get(babyId)!!.clientUuid
        assertThat(first.clientUuid).isEqualTo(nextFeedPlanClientUuid(babyUuid, "initial"))
        assertThat(first.note).startsWith(NEXT_FEED_PLAN_MARKER)
        assertThat(care.getCarePlan(firstId)!!.note).isNull()
        assertThat(first.payloadJson).contains("\"left_min\":0")
        assertThat(first.payloadJson).contains("\"right_min\":0")

        val duplicateId = fakes.carePlans.upsert(
            first.copy(
                id = 0,
                clientUuid = "10000000-0000-4000-8000-000000000099",
                updatedAt = first.updatedAt + 10L,
            ),
        )

        val secondId = care.scheduleNextFeedCarePlan(
            babyId = babyId,
            feedType = RecordType.FORMULA,
            scheduledAt = now + 120_000L,
            zone = zone,
            nowMillis = now + 1L,
        )
        val second = fakes.carePlans.get(secondId)!!
        assertThat(secondId).isEqualTo(firstId)
        assertThat(second.clientUuid).isEqualTo(first.clientUuid)
        assertThat(second.payloadJson).contains("\"amount_ml\":0")
        assertThat(fakes.carePlans.get(duplicateId)!!.deletedAt).isNotNull()
        assertThat(fakes.reminders.cancelledCarePlanIds).contains(duplicateId)

        val thirdId = care.scheduleNextFeedCarePlan(
            babyId = babyId,
            feedType = RecordType.PUMPED_FEED,
            scheduledAt = now + 180_000L,
            zone = zone,
            nowMillis = now + 2L,
        )
        val third = fakes.carePlans.get(thirdId)!!
        assertThat(thirdId).isEqualTo(firstId)
        assertThat(third.clientUuid).isEqualTo(first.clientUuid)
        assertThat(third.type).isEqualTo(RecordType.PUMPED_FEED.key)
        assertThat(third.payloadJson).contains("\"amount_ml\":0")
        assertThat(
            fakes.carePlans.listAllIncludingDeleted().count {
                it.deletedAt == null && isNextFeedPlanNote(it.note)
            },
        ).isEqualTo(1)
    }
    @Test
    fun nextFeedReconciliationReadsCommittedOpenMarkerTruth() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = 1_800_000_000_000L

        assertThat(care.reconcileNextFeedPlan(babyId))
            .isEqualTo(NextFeedPlanReconciliation.Absent)

        val planId = care.scheduleNextFeedCarePlan(
            babyId = babyId,
            feedType = RecordType.NURSING,
            scheduledAt = now + 60_000L,
            zone = zone,
            nowMillis = now,
        )
        val persisted = requireNotNull(fakes.carePlans.get(planId))

        assertThat(care.reconcileNextFeedPlan(babyId)).isEqualTo(
            NextFeedPlanReconciliation.Found(
                clientUuid = persisted.clientUuid,
                scheduledAtMillis = persisted.scheduledAt,
            ),
        )
    }
    @Test
    fun nextFeedCarePlan_rejectsUnsupportedTypeAndNonFutureTime() = runTest {
        val care = Fakes().careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = 1_800_000_000_000L

        assertThat(
            runCatching {
                care.scheduleNextFeedCarePlan(babyId, RecordType.SLEEP, now + 1L, nowMillis = now)
            }.exceptionOrNull(),
        ).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(
            runCatching {
                care.scheduleNextFeedCarePlan(babyId, RecordType.NURSING, now, nowMillis = now)
            }.exceptionOrNull(),
        ).isInstanceOf(IllegalArgumentException::class.java)
    }
    @Test
    fun nextFeedCarePlanDoesNotRewriteAnotherMembersOpenPlan() = runTest {
        val sync = RecordingSyncPort(
            membershipId = "member-local",
            role = com.lezi.babylog.sync.session.FamilyRole.Member,
            familyId = "family",
        )
        val fakes = Fakes(sync)
        val babyId = fakes.babies.upsert(
            BabyEntity(
                familyId = 1,
                nickname = "家庭宝宝",
                birthdayEpochDay = 1,
                themeColorArgb = 0,
                clientUuid = "baby-authority",
                updatedAt = 1,
                syncDirty = false,
                familyAuthority = true,
            ),
        )
        val planId = fakes.carePlans.upsert(
            CarePlanEntity(
                clientUuid = nextFeedPlanClientUuid("baby-authority", "initial"),
                babyId = babyId,
                type = RecordType.FORMULA.key,
                scheduledAt = 10_000,
                scheduledZoneId = "UTC",
                note = NEXT_FEED_PLAN_MARKER,
                payloadJson = """{"amount_ml":0}""",
                createdByMembershipId = "member-other",
                updatedAt = 1,
                syncDirty = false,
            ),
        )

        val error = runCatching {
            fakes.careLog().scheduleNextFeedCarePlan(
                babyId,
                RecordType.FORMULA,
                scheduledAt = 20_000,
                nowMillis = 2_000,
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(CarePlanPermissionException::class.java)
        assertThat(fakes.carePlans.get(planId)!!.scheduledAt).isEqualTo(10_000)
        assertThat(fakes.careLog().reconcileNextFeedPlan(babyId)).isEqualTo(
            NextFeedPlanReconciliation.Found(
                clientUuid = nextFeedPlanClientUuid("baby-authority", "initial"),
                scheduledAtMillis = 10_000,
            ),
        )
    }
    @Test
    fun nextFeedReconciliationTreatsMissedAsOpenAndTerminalOrDeletedAsAbsent() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val planId = fakes.carePlans.upsert(
            CarePlanEntity(
                clientUuid = "next-feed-plan",
                babyId = babyId,
                type = RecordType.NURSING.key,
                scheduledAt = 10_000,
                scheduledZoneId = "UTC",
                note = NEXT_FEED_PLAN_MARKER,
                payloadJson = """{"left_min":0,"right_min":0}""",
                status = CarePlanStatus.MISSED.storageKey,
                updatedAt = 1,
                syncDirty = false,
            ),
        )

        assertThat(care.reconcileNextFeedPlan(babyId)).isInstanceOf(
            NextFeedPlanReconciliation.Found::class.java,
        )

        val missed = requireNotNull(fakes.carePlans.get(planId))
        fakes.carePlans.update(missed.copy(status = CarePlanStatus.COMPLETED.storageKey))
        assertThat(care.reconcileNextFeedPlan(babyId))
            .isEqualTo(NextFeedPlanReconciliation.Absent)

        fakes.carePlans.update(missed.copy(status = CarePlanStatus.SKIPPED.storageKey))
        assertThat(care.reconcileNextFeedPlan(babyId))
            .isEqualTo(NextFeedPlanReconciliation.Absent)

        fakes.carePlans.update(missed.copy(deletedAt = 2L))
        assertThat(care.reconcileNextFeedPlan(babyId))
            .isEqualTo(NextFeedPlanReconciliation.Absent)
    }
    @Test
    fun nextFeedReconciliationWaitsForInFlightPersistenceBeforeReportingTruth() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val transactionEntered = CompletableDeferred<Unit>()
        val allowCommit = CompletableDeferred<Unit>()
        fakes.transactions.beforeNextRun = {
            transactionEntered.complete(Unit)
            allowCommit.await()
        }

        val schedule = async(start = CoroutineStart.UNDISPATCHED) {
            care.scheduleNextFeedCarePlan(
                babyId = babyId,
                feedType = RecordType.NURSING,
                scheduledAt = 20_000,
                nowMillis = 10_000,
            )
        }
        transactionEntered.await()
        val reconciliation = async { care.reconcileNextFeedPlan(babyId) }
        yield()

        assertThat(reconciliation.isCompleted).isFalse()
        allowCommit.complete(Unit)
        schedule.await()
        assertThat(reconciliation.await()).isInstanceOf(
            NextFeedPlanReconciliation.Found::class.java,
        )
    }
    @Test
    fun fulfillNextFeedCarePlanStripsInternalMarkerFromRecordNote() = runTest {
        val fakes = Fakes()
        fakes.wireTransactionalSnapshots()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = 1_800_000_000_000L

        // Marker-only next-feed plan: omit note on fulfill → record has no marker.
        // Fulfill passes explicit actual payloads: the plan's intent-only payload
        // fallback is a non-UI zombie row since S5 rejects it at local write.
        val markerOnlyId = care.scheduleNextFeedCarePlan(
            babyId = babyId,
            feedType = RecordType.NURSING,
            scheduledAt = now + 60_000L,
            zone = zone,
            nowMillis = now,
        )
        val markerOnlyPlanNote = fakes.carePlans.get(markerOnlyId)!!.note
        assertThat(markerOnlyPlanNote).startsWith(NEXT_FEED_PLAN_MARKER)
        val markerOnlyRecordId = care.fulfillCarePlan(
            carePlanId = markerOnlyId,
            actualTimestamp = now,
            payloadJson = """{"left_min":10,"right_min":0,"order":"L","record_mode":"end"}""",
            nowMillis = now + 1L,
        )
        val markerOnlyRecord = care.getRecord(markerOnlyRecordId)!!
        assertThat(markerOnlyRecord.note).isNull()
        assertThat(markerOnlyRecord.note.orEmpty()).doesNotContain(NEXT_FEED_PLAN_MARKER)
        // Non-goal: completed plan row keeps storage marker (no scrub rewrite).
        assertThat(fakes.carePlans.get(markerOnlyId)!!.note).isEqualTo(markerOnlyPlanNote)
        assertThat(fakes.carePlans.get(markerOnlyId)!!.status)
            .isEqualTo(CarePlanStatus.COMPLETED.storageKey)

        // Plan with user-visible note under marker: omit note → only visible text on record.
        val withVisibleId = care.scheduleNextFeedCarePlan(
            babyId = babyId,
            feedType = RecordType.FORMULA,
            scheduledAt = now + 120_000L,
            zone = zone,
            nowMillis = now + 2L,
        )
        care.updateCarePlan(
            carePlanId = withVisibleId,
            scheduledAt = now + 120_000L,
            note = "带奶瓶",
            nowMillis = now + 3L,
        )
        val withVisiblePlanNote = fakes.carePlans.get(withVisibleId)!!.note
        assertThat(withVisiblePlanNote).isEqualTo("$NEXT_FEED_PLAN_MARKER 带奶瓶")
        val visibleRecordId = care.fulfillCarePlan(
            carePlanId = withVisibleId,
            actualTimestamp = now + 10L,
            payloadJson = """{"amount_ml":120}""",
            nowMillis = now + 4L,
        )
        assertThat(care.getRecord(visibleRecordId)!!.note).isEqualTo("带奶瓶")
        assertThat(fakes.carePlans.get(withVisibleId)!!.note).isEqualTo(withVisiblePlanNote)

        // Explicit clean note is kept; no marker invented.
        val explicitPlanId = care.scheduleNextFeedCarePlan(
            babyId = babyId,
            feedType = RecordType.PUMPED_FEED,
            scheduledAt = now + 180_000L,
            zone = zone,
            nowMillis = now + 5L,
        )
        val explicitPlanNote = fakes.carePlans.get(explicitPlanId)!!.note
        assertThat(explicitPlanNote).startsWith(NEXT_FEED_PLAN_MARKER)
        val explicitRecordId = care.fulfillCarePlan(
            carePlanId = explicitPlanId,
            actualTimestamp = now + 20L,
            note = "现场备注",
            payloadJson = """{"amount_ml":80}""",
            nowMillis = now + 6L,
        )
        assertThat(care.getRecord(explicitRecordId)!!.note).isEqualTo("现场备注")
        assertThat(care.getRecord(explicitRecordId)!!.note.orEmpty())
            .doesNotContain(NEXT_FEED_PLAN_MARKER)
        assertThat(fakes.carePlans.get(explicitPlanId)!!.note).isEqualTo(explicitPlanNote)

        // Fail-closed: explicit note that still carries the protocol prefix is stripped.
        val leakyPlanId = care.scheduleNextFeedCarePlan(
            babyId = babyId,
            feedType = RecordType.NURSING,
            scheduledAt = now + 240_000L,
            zone = zone,
            nowMillis = now + 7L,
        )
        val leakyPlanNote = fakes.carePlans.get(leakyPlanId)!!.note
        val leakyRecordId = care.fulfillCarePlan(
            carePlanId = leakyPlanId,
            actualTimestamp = now + 30L,
            note = "$NEXT_FEED_PLAN_MARKER 泄漏备注",
            payloadJson = """{"left_min":0,"right_min":8,"order":"R","record_mode":"end"}""",
            nowMillis = now + 8L,
        )
        assertThat(care.getRecord(leakyRecordId)!!.note).isEqualTo("泄漏备注")
        assertThat(fakes.carePlans.get(leakyPlanId)!!.note).isEqualTo(leakyPlanNote)
    }
    @Test
    fun ownerMergeKeepsOneOpenNextFeedAndPublishesLosingTombstone() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val target = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val source = care.addBaby(CreateBabyInput(nickname = "临时", birthdayEpochDay = 2))
        val now = 1_800_000_000_000L
        val targetPlanId = care.scheduleNextFeedCarePlan(
            target,
            RecordType.NURSING,
            scheduledAt = now + 60_000,
            nowMillis = now,
        )
        val sourcePlanId = care.scheduleNextFeedCarePlan(
            source,
            RecordType.FORMULA,
            scheduledAt = now + 120_000,
            nowMillis = now,
        )

        assertThat(care.mergeBabyProfiles(source, target)).isTrue()

        val open = fakes.carePlans.listAllIncludingDeleted().filter {
            it.babyId == target && it.deletedAt == null && isNextFeedPlanNote(it.note)
        }
        assertThat(open.map(CarePlanEntity::id)).containsExactly(targetPlanId)
        val loser = fakes.carePlans.get(sourcePlanId)!!
        assertThat(loser.deletedAt).isNotNull()
        assertThat(loser.syncDirty).isTrue()
        assertThat(fakes.reminders.cancelledCarePlanIds).contains(sourcePlanId)
    }
}
