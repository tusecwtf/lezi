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
class CareLogCarePlanTest {
    @Test
    fun carePlanCreationRejectsUnsupportedPayloadSchema() = runTest {
        val care = Fakes().careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        val now = System.currentTimeMillis()

        val failure = runCatching {
            care.createCarePlan(
                babyId = babyId,
                type = RecordType.BATH,
                scheduledAt = now + 60_000L,
                schemaVersion = 1,
                nowMillis = now,
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
    }
    @Test
    fun createCarePlan_replayWithComposerClientUuidKeepsOneOriginalPlan() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        val now = System.currentTimeMillis()
        val clientUuid = "composer-create-plan-identity"

        val first = care.createCarePlan(
            babyId = babyId,
            type = RecordType.BATH,
            scheduledAt = now + 60_000L,
            note = "第一次确认",
            nowMillis = now,
            clientUuid = clientUuid,
        )
        val replay = care.createCarePlan(
            babyId = babyId,
            type = RecordType.BATH,
            scheduledAt = now + 120_000L,
            note = "进程重建后不应覆盖",
            nowMillis = now,
            clientUuid = clientUuid,
        )

        assertThat(replay).isEqualTo(first)
        assertThat(fakes.carePlans.listAllIncludingDeleted()).hasSize(1)
        assertThat(care.getCarePlan(first)!!.scheduledAt).isEqualTo(now + 60_000L)
        assertThat(care.getCarePlan(first)!!.note).isEqualTo("第一次确认")
    }
    @Test
    fun fulfillCarePlan_replayWithComposerClientUuidKeepsOneLinkedFact() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        val now = System.currentTimeMillis()
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.BATH,
            scheduledAt = now + 60_000L,
            nowMillis = now,
        )
        val clientUuid = "composer-fulfill-plan-identity"

        val first = care.fulfillCarePlan(
            carePlanId = planId,
            actualTimestamp = now,
            nowMillis = now,
            clientUuid = clientUuid,
        )
        val replay = care.fulfillCarePlan(
            carePlanId = planId,
            actualTimestamp = now,
            nowMillis = now,
            clientUuid = clientUuid,
        )

        assertThat(replay).isEqualTo(first)
        assertThat(fakes.records.listForBaby(babyId)).hasSize(1)
        assertThat(care.getRecord(first)!!.clientUuid).isEqualTo(clientUuid)
        assertThat(care.getCarePlan(planId)!!.fulfilledRecordClientUuid).isEqualTo(clientUuid)
    }
    @Test
    fun pendingCreatorAcknowledgementAllowsOnlyTheExactLocalBlankEntities() = runTest {
        val pending = setOf(
            com.lezi.babylog.sync.session.CreatorAcknowledgementRef(
                entityType = "custom_item",
                clientUuid = "item-local-pending",
            ),
            com.lezi.babylog.sync.session.CreatorAcknowledgementRef(
                entityType = "care_plan",
                clientUuid = "plan-local-pending",
            ),
            com.lezi.babylog.sync.session.CreatorAcknowledgementRef(
                entityType = "record",
                clientUuid = "record-local-pending",
            ),
        )
        val sync = RecordingSyncPort(
            membershipId = "m-canonical",
            role = com.lezi.babylog.sync.session.FamilyRole.Member,
            familyId = "fam-1",
            deviceId = "dev-1",
            pendingCreatorAcknowledgements = pending,
        )
        val care = Fakes(sync).careLog()
        val localItem = CustomRecordItem(
            id = 1,
            clientUuid = "item-local-pending",
            name = "本机项目",
            iconSlot = 0,
            sortOrder = 0,
            createdByMembershipId = "",
        )
        val unknownItem = localItem.copy(
            id = 2,
            clientUuid = "item-legacy-unknown",
            name = "未知项目",
        )
        val localPlan = CarePlan(
            id = 1,
            clientUuid = "plan-local-pending",
            babyId = 1,
            type = RecordType.PEE,
            scheduledAt = 10_000,
            scheduledZoneId = "Asia/Shanghai",
            createdByMembershipId = "",
            updatedAt = 1,
        )
        val unknownPlan = localPlan.copy(
            id = 2,
            clientUuid = "plan-legacy-unknown",
        )
        val localRecord = com.lezi.babylog.core.model.Record(
            id = 1,
            clientUuid = "record-local-pending",
            babyId = 1,
            type = RecordType.PEE,
            timestamp = 10_000,
            payloadJson = """{"pee_amount":1}""",
            createdByMembershipId = "",
            updatedAt = 1,
        )
        val unknownRecord = localRecord.copy(
            id = 2,
            clientUuid = "record-legacy-unknown",
        )

        assertThat(care.canManageCustomItem(localItem)).isTrue()
        assertThat(care.canManageCarePlan(localPlan)).isTrue()
        assertThat(care.canManageRecord(localRecord)).isTrue()
        assertThat(care.canManageCustomItem(unknownItem)).isFalse()
        assertThat(care.canManageCarePlan(unknownPlan)).isFalse()
        assertThat(care.canManageRecord(unknownRecord)).isFalse()

        sync.replaceSession(
            sync.currentSession().copy(pendingCreatorAcknowledgements = emptySet()),
        )

        assertThat(care.canManageCustomItem(localItem)).isFalse()
        assertThat(care.canManageCarePlan(localPlan)).isFalse()
        assertThat(care.canManageRecord(localRecord)).isFalse()
    }
    @Test
    fun pendingCreatorAcknowledgementEnforcesExactEditAndDeletePermissions() = runTest {
        val sync = RecordingSyncPort(
            membershipId = "m-canonical",
            role = com.lezi.babylog.sync.session.FamilyRole.Member,
            familyId = "fam-1",
            deviceId = "dev-1",
            pendingCreatorAcknowledgements = setOf(
                com.lezi.babylog.sync.session.CreatorAcknowledgementRef(
                    "custom_item",
                    "item-local-pending",
                ),
                com.lezi.babylog.sync.session.CreatorAcknowledgementRef(
                    "care_plan",
                    "plan-local-pending",
                ),
            ),
        )
        val fakes = Fakes(sync)
        fakes.wireTransactionalSnapshots()
        val care = fakes.careLog()
        fakes.babies.upsert(
            BabyEntity(
                id = 1,
                familyId = 1,
                clientUuid = "baby-local",
                nickname = "年年",
                birthdayEpochDay = 1,
                themeColorArgb = 0,
                updatedAt = 1,
            ),
        )
        fakes.customItems.upsert(
            CustomItemEntity(
                id = 1,
                clientUuid = "item-local-pending",
                familyId = 1,
                name = "本机项目",
                iconSlot = 0,
                sortOrder = 0,
                updatedAt = 1,
                createdByMembershipId = "",
                syncDirty = false,
            ),
        )
        fakes.customItems.upsert(
            CustomItemEntity(
                id = 2,
                clientUuid = "item-legacy-unknown",
                familyId = 1,
                name = "未知项目",
                iconSlot = 0,
                sortOrder = 1,
                updatedAt = 1,
                createdByMembershipId = "",
                syncDirty = false,
            ),
        )
        fakes.carePlans.upsert(
            CarePlanEntity(
                id = 1,
                clientUuid = "plan-local-pending",
                babyId = 1,
                type = "pee",
                scheduledAt = 10_000,
                scheduledZoneId = "Asia/Shanghai",
                updatedAt = 1,
                createdByMembershipId = "",
                syncDirty = false,
            ),
        )
        fakes.carePlans.upsert(
            CarePlanEntity(
                id = 2,
                clientUuid = "plan-legacy-unknown",
                babyId = 1,
                type = "pee",
                scheduledAt = 10_000,
                scheduledZoneId = "Asia/Shanghai",
                updatedAt = 1,
                createdByMembershipId = "",
                syncDirty = false,
            ),
        )

        val localItem = care.observeCustomItems().first().first { it.id == 1L }
        care.updateCustomItem(localItem.copy(name = "本机项目已编辑"))
        care.deleteCarePlan(carePlanId = 1, nowMillis = 2)

        assertThat(fakes.customItems.getById(1)?.name).isEqualTo("本机项目已编辑")
        assertThat(fakes.carePlans.get(1)?.deletedAt).isEqualTo(2)
        val unknownItem = care.observeCustomItems().first().first { it.id == 2L }
        assertThat(
            runCatching {
                care.updateCustomItem(unknownItem.copy(name = "不应成功"))
            }.exceptionOrNull(),
        ).isInstanceOf(CustomItemPermissionException::class.java)
        assertThat(
            runCatching { care.deleteCarePlan(carePlanId = 2, nowMillis = 2) }
                .exceptionOrNull(),
        ).isInstanceOf(CarePlanPermissionException::class.java)
    }
    @Test
    fun carePlanCreateIsIsolatedFromRecordSurfacesAndFulfillIsAtomic() = runTest {
        val fakes = Fakes()
        fakes.wireTransactionalSnapshots()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = 1_000_000L
        val scheduled = now + 3_600_000L

        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.PEE,
            scheduledAt = scheduled,
            note = "换尿布",
            payloadJson = """{"pee_amount":2}""",
            nowMillis = now,
        )
        val plan = care.getCarePlan(planId)!!
        assertThat(plan.type).isEqualTo(RecordType.PEE)
        assertThat(plan.status).isEqualTo(CarePlanStatus.PENDING)
        assertThat(plan.note).isEqualTo("换尿布")

        // Isolation: day records / summary / search do not include the plan.
        val day = java.time.Instant.ofEpochMilli(scheduled)
            .atZone(java.time.ZoneOffset.UTC).toLocalDate()
        assertThat(care.dayRecords(babyId, day, java.time.ZoneOffset.UTC)).isEmpty()
        val summary = care.daySummary(babyId, day, java.time.ZoneOffset.UTC, now)
        assertThat(summary.peeCount).isEqualTo(0)
        assertThat(summary.feedMl).isEqualTo(0)
        assertThat(care.search(babyId, "换尿布")).isEmpty()
        assertThat(
            care.observeDayPendingPlans(babyId, day, java.time.ZoneOffset.UTC).first(),
        ).hasSize(1)

        // Fulfill allows up to now + 5 minutes; beyond that is rejected.
        val fiveMin = RecordTime.FULFILLMENT_ACTUAL_TIME_SKEW_MILLIS
        val beyondSkewFail = runCatching {
            care.fulfillCarePlan(
                carePlanId = planId,
                actualTimestamp = now + fiveMin + 1L,
                nowMillis = now,
            )
        }.exceptionOrNull()
        assertThat(beyondSkewFail).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(care.getCarePlan(planId)!!.status).isEqualTo(CarePlanStatus.PENDING)

        val recordId = care.fulfillCarePlan(
            carePlanId = planId,
            actualTimestamp = now - 1_000L,
            nowMillis = now,
        )
        val completed = care.getCarePlan(planId)!!
        assertThat(completed.status).isEqualTo(CarePlanStatus.COMPLETED)
        assertThat(completed.fulfilledRecordClientUuid).isNotNull()
        val record = care.getRecord(recordId)!!
        assertThat(record.type).isEqualTo(RecordType.PEE)
        assertThat(record.clientUuid).isEqualTo(completed.fulfilledRecordClientUuid)
        assertThat(care.observeDayPendingPlans(babyId, day, java.time.ZoneOffset.UTC).first())
            .isEmpty()
    }
    @Test
    fun carePlanUpdateToPastBecomesMissedWithoutRecordAndSkipTombstones() = runTest {
        val fakes = Fakes()
        fakes.wireTransactionalSnapshots()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val zone = java.time.ZoneOffset.UTC
        val now = 10_000_000L
        val future = now + 3_600_000L
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.PEE,
            scheduledAt = future,
            note = "计划",
            nowMillis = now,
            zone = zone,
        )
        // Move scheduled time into the past → effective missed, no Record.
        care.updateCarePlan(
            carePlanId = planId,
            scheduledAt = now - 60_000L,
            note = "改到过去",
            nowMillis = now,
        )
        val updated = care.getCarePlan(planId)!!
        assertThat(updated.note).isEqualTo("改到过去")
        assertThat(updated.status).isEqualTo(CarePlanStatus.PENDING)
        assertThat(updated.effectiveStatus(now)).isEqualTo(CarePlanStatus.MISSED)
        assertThat(care.dayRecords(babyId, java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate(), zone))
            .isEmpty()

        // Today list: missed first (ASC within group). Keep all times on the same UTC day.
        val dayStart = java.time.LocalDate.of(2024, 6, 15)
            .atStartOfDay(zone).toInstant().toEpochMilli()
        val dayNow = dayStart + 12 * 3_600_000L // noon
        val sameDayPending = dayNow + 3_600_000L // 13:00
        val otherDayStart = dayStart + 24 * 3_600_000L
        val otherDayPlanAt = otherDayStart + 10 * 3_600_000L

        val plan2 = care.createCarePlan(
            babyId = babyId,
            type = RecordType.BATH,
            scheduledAt = sameDayPending,
            nowMillis = dayNow,
            zone = zone,
        )
        val earlierMissed = care.createCarePlan(
            babyId = babyId,
            type = RecordType.WALK,
            scheduledAt = dayNow + 1_000L,
            nowMillis = dayNow - 5_000L,
            zone = zone,
        )
        val otherDayPlan = care.createCarePlan(
            babyId = babyId,
            type = RecordType.DIARY,
            scheduledAt = otherDayPlanAt,
            payloadJson = """{"body":"日记"}""",
            nowMillis = dayNow,
            zone = zone,
        )
        // Force earlierMissed + planId into past relative to dayNow via update.
        care.updateCarePlan(earlierMissed, scheduledAt = dayNow - 120_000L, nowMillis = dayNow)
        care.updateCarePlan(planId, scheduledAt = dayNow - 30_000L, nowMillis = dayNow)
        val today = care.observeTodayPendingPlans(babyId, zone, dayNow).first()
        assertThat(today.map { it.id }).containsExactly(earlierMissed, planId, plan2).inOrder()
        assertThat(today.map { it.effectiveStatus(dayNow) }).containsExactly(
            CarePlanStatus.MISSED,
            CarePlanStatus.MISSED,
            CarePlanStatus.PENDING,
        ).inOrder()
        // other-day plan is not mixed into "today" until its local day (missed-after-midnight
        // still appears in today via overdue branch — otherDayPlan is still in the future).
        assertThat(today.map { it.id }).doesNotContain(otherDayPlan)

        // Non-today day bounds only that local day.
        val otherDay = java.time.Instant.ofEpochMilli(otherDayPlanAt).atZone(zone).toLocalDate()
        val dayOnly = care.observeDayPendingPlans(babyId, otherDay, zone).first()
        assertThat(dayOnly.map { it.id }).containsExactly(otherDayPlan)

        care.skipCarePlan(plan2, nowMillis = dayNow)
        assertThat(care.getCarePlan(plan2)!!.status).isEqualTo(CarePlanStatus.SKIPPED)
        assertThat(care.observeTodayPendingPlans(babyId, zone, dayNow).first().map { it.id })
            .doesNotContain(plan2)
        assertThat(fakes.records.listAllIncludingDeleted()).isEmpty()

        care.deleteCarePlan(planId, nowMillis = dayNow)
        assertThat(care.getCarePlan(planId)!!.deletedAt).isNotNull()
        assertThat(care.observeTodayPendingPlans(babyId, zone, dayNow).first().map { it.id })
            .doesNotContain(planId)
    }
    @Test
    fun carePlanManagePermissionMatchesMembershipAcl() = runTest {
        val creatorSync = RecordingSyncPort(
            membershipId = "m-creator",
            role = com.lezi.babylog.sync.session.FamilyRole.Member,
            familyId = "fam-1",
            deviceId = "dev-1",
        )
        val fakes = Fakes(creatorSync)
        fakes.wireTransactionalSnapshots()
        val care = fakes.careLog()
        val babyId = fakes.seedFamilyAuthorityBaby()
        val now = 5_000_000L
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.PEE,
            scheduledAt = now + 60_000L,
            nowMillis = now,
        )
        assertThat(care.getCarePlan(planId)!!.createdByMembershipId).isEqualTo("m-creator")

        // Foreign member cannot skip/edit/delete.
        val foreignSync = RecordingSyncPort(
            membershipId = "m-other",
            role = com.lezi.babylog.sync.session.FamilyRole.Member,
            familyId = "fam-1",
            deviceId = "dev-2",
        )
        val foreignCare = Fakes(foreignSync).let { f ->
            f.wireTransactionalSnapshots()
            // Share the same plan row.
            f.carePlans.upsert(fakes.carePlans.get(planId)!!)
            f.babies.upsert(
                BabyEntity(
                    id = babyId,
                    familyId = 1L,
                    clientUuid = "b",
                    nickname = "年年",
                    birthdayEpochDay = 1,
                    themeColorArgb = 0,
                    updatedAt = 1L,
                    familyAuthority = true,
                ),
            )
            f.careLog()
        }
        assertThat(
            runCatching { foreignCare.skipCarePlan(planId, nowMillis = now) }.exceptionOrNull(),
        ).isInstanceOf(CarePlanPermissionException::class.java)
        assertThat(
            runCatching {
                foreignCare.updateCarePlan(planId, scheduledAt = now + 90_000L, nowMillis = now)
            }.exceptionOrNull(),
        ).isInstanceOf(CarePlanPermissionException::class.java)
        assertThat(
            runCatching { foreignCare.deleteCarePlan(planId, nowMillis = now) }.exceptionOrNull(),
        ).isInstanceOf(CarePlanPermissionException::class.java)

        // Owner can manage others' plans.
        val adminSync = RecordingSyncPort(
            membershipId = "m-admin",
            role = com.lezi.babylog.sync.session.FamilyRole.Owner,
            familyId = "fam-1",
            deviceId = "dev-admin",
        )
        val adminCare = Fakes(adminSync).let { f ->
            f.wireTransactionalSnapshots()
            f.carePlans.upsert(fakes.carePlans.get(planId)!!)
            f.babies.upsert(
                BabyEntity(
                    id = babyId,
                    familyId = 1L,
                    clientUuid = "b",
                    nickname = "年年",
                    birthdayEpochDay = 1,
                    themeColorArgb = 0,
                    updatedAt = 1L,
                ),
            )
            f.careLog()
        }
        adminCare.skipCarePlan(planId, nowMillis = now)
        assertThat(adminCare.getCarePlan(planId)!!.status).isEqualTo(CarePlanStatus.SKIPPED)
    }
    @Test
    fun foreignMemberCanFulfillOthersPlanWithoutGainingManageRights() = runTest {
        val creatorSync = RecordingSyncPort(
            membershipId = "m-creator",
            role = com.lezi.babylog.sync.session.FamilyRole.Member,
            familyId = "fam-1",
            deviceId = "dev-1",
        )
        val fakes = Fakes(creatorSync)
        fakes.wireTransactionalSnapshots()
        val care = fakes.careLog()
        val babyId = fakes.seedFamilyAuthorityBaby()
        val now = 6_000_000L
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.PEE,
            scheduledAt = now + 60_000L,
            nowMillis = now,
        )
        val plan = fakes.carePlans.get(planId)!!

        // Foreign ordinary member fulfills the creator's plan.
        val foreignSync = RecordingSyncPort(
            membershipId = "m-other",
            role = com.lezi.babylog.sync.session.FamilyRole.Member,
            familyId = "fam-1",
            deviceId = "dev-2",
        )
        val foreign = Fakes(foreignSync)
        foreign.wireTransactionalSnapshots()
        foreign.carePlans.upsert(plan)
        foreign.babies.upsert(
            BabyEntity(
                id = babyId,
                familyId = 1L,
                clientUuid = "b",
                nickname = "年年",
                birthdayEpochDay = 1,
                themeColorArgb = 0,
                updatedAt = 1L,
                familyAuthority = true,
            ),
        )
        val foreignCare = foreign.careLog()
        val recordId = foreignCare.fulfillCarePlan(
            carePlanId = planId,
            actualTimestamp = now,
            nowMillis = now + 1,
        )
        assertThat(recordId).isGreaterThan(0L)
        val completed = foreignCare.getCarePlan(planId)!!
        assertThat(completed.status).isEqualTo(CarePlanStatus.COMPLETED)
        assertThat(completed.createdByMembershipId).isEqualTo("m-creator")
        // Non-manager fulfill completes locally only — server rejects care_plan rewrite.
        assertThat(completed.syncDirty).isFalse()
        val candidates = foreignCare.listFulfillmentCandidatesForPlan(completed.clientUuid)
        assertThat(candidates).hasSize(1)
        assertThat(candidates.single().recordClientUuid)
            .isEqualTo(completed.fulfilledRecordClientUuid)
        assertThat(candidates.single().confirmedAt).isEqualTo(completed.fulfilledAt)
        assertThat(candidates.single().syncDirty).isTrue()

        // Fulfilling does not grant manage rights on the author's plan.
        assertThat(foreignCare.canManageCarePlan(completed)).isFalse()
        // Open plan owned by creator: skip/delete/update still ACL-denied.
        val openId = foreign.carePlans.upsert(
            plan.copy(
                id = 0,
                clientUuid = "plan-still-open",
                status = "pending",
                fulfilledRecordClientUuid = null,
                fulfilledAt = null,
                updatedAt = now + 3,
            ),
        )
        assertThat(
            runCatching {
                foreignCare.updateCarePlan(
                    openId,
                    scheduledAt = now + 120_000L,
                    nowMillis = now + 4,
                )
            }.exceptionOrNull(),
        ).isInstanceOf(CarePlanPermissionException::class.java)
        assertThat(
            runCatching { foreignCare.skipCarePlan(openId, nowMillis = now + 5) }.exceptionOrNull(),
        ).isInstanceOf(CarePlanPermissionException::class.java)
        assertThat(
            runCatching { foreignCare.deleteCarePlan(openId, nowMillis = now + 6) }.exceptionOrNull(),
        ).isInstanceOf(CarePlanPermissionException::class.java)
    }
    @Test
    fun multiCandidateFulfillmentPicksAdminAndHidesLoserFromSurfaces() = runTest {
        // Joined member session so fulfill stamps offline role/membership trails
        // (ticket 26 residual: originator adjudication before server freeze pull).
        val memberSync = RecordingSyncPort(
            membershipId = "m-member",
            role = com.lezi.babylog.sync.session.FamilyRole.Member,
            familyId = "fam-1",
            deviceId = "dev-member",
        )
        val fakes = Fakes(memberSync)
        fakes.wireTransactionalSnapshots()
        val care = fakes.careLog()
        val babyId = fakes.seedFamilyAuthorityBaby()
        val now = 8_000_000L
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.BATH,
            scheduledAt = now + 60_000L,
            nowMillis = now,
        )
        val planUuid = care.getCarePlan(planId)!!.clientUuid
        val zone = java.time.ZoneOffset.UTC
        val day = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

        // Local member fulfill first (only fact visible until peers arrive).
        val localRecordId = care.fulfillCarePlan(
            carePlanId = planId,
            actualTimestamp = now,
            note = "local-member-fulfill",
            nowMillis = now + 1,
        )
        val localRecord = care.getRecord(localRecordId)!!
        assertThat(care.dayRecords(babyId, day, zone).map { it.clientUuid })
            .contains(localRecord.clientUuid)
        val localCand = care.listFulfillmentCandidatesForPlan(planUuid).single()
        assertThat(localCand.adoptionStatus)
            .isEqualTo(com.lezi.babylog.core.model.FulfillmentAdoptionStatus.ADOPTED)
        assertThat(localCand.submitterMembershipId).isEqualTo("m-member")
        assertThat(localCand.submitterRole).isEqualTo("member")

        // Simulate remote owner candidate arriving with later confirmed_at but admin role.
        val ownerRecordUuid = "owner-fulfill-record"
        fakes.records.upsert(
            RecordEntity(
                clientUuid = ownerRecordUuid,
                babyId = babyId,
                type = RecordType.BATH.key,
                timestamp = now,
                note = "owner-fulfill",
                payloadJson = "{}",
                updatedAt = now + 50,
                syncDirty = false,
            ),
        )
        fakes.fulfillmentCandidates.upsert(
            FulfillmentCandidateEntity(
                clientUuid = "owner-cand",
                carePlanClientUuid = planUuid,
                recordClientUuid = ownerRecordUuid,
                actualTimestamp = now + 9_000,
                confirmedAt = now + 100,
                submitterMembershipId = "m-owner",
                submitterRole = "owner",
                updatedAt = now + 100,
                syncDirty = false,
            ),
        )
        care.resolveFulfillmentAuthorityForPlan(planUuid)

        val plan = care.getCarePlan(planId)!!
        assertThat(plan.fulfilledRecordClientUuid).isEqualTo(ownerRecordUuid)
        val candidates = care.listFulfillmentCandidatesForPlan(planUuid)
        assertThat(candidates).hasSize(2)
        val winner = candidates.single { it.clientUuid == "owner-cand" }
        val loser = candidates.single { it.clientUuid == localCand.clientUuid }
        assertThat(winner.adoptionStatus)
            .isEqualTo(com.lezi.babylog.core.model.FulfillmentAdoptionStatus.ADOPTED)
        assertThat(loser.adoptionStatus)
            .isEqualTo(com.lezi.babylog.core.model.FulfillmentAdoptionStatus.CONFLICT_NOT_ADOPTED)
        // Loser record and photos retained.
        assertThat(care.getRecord(localRecordId)).isNotNull()
        assertThat(care.getRecord(localRecordId)!!.deletedAt).isNull()

        // Ordinary surfaces only show winner.
        assertThat(care.dayRecords(babyId, day, zone).map { it.clientUuid })
            .containsExactly(ownerRecordUuid)
        assertThat(care.search(babyId, "owner-fulfill").map { it.clientUuid })
            .containsExactly(ownerRecordUuid)
        assertThat(care.search(babyId, "local-member-fulfill")).isEmpty()
        // Idempotent re-resolve.
        care.resolveFulfillmentAuthorityForPlan(planUuid)
        assertThat(care.getCarePlan(planId)!!.fulfilledRecordClientUuid).isEqualTo(ownerRecordUuid)
        assertThat(
            care.listFulfillmentCandidatesForPlan(planUuid)
                .map { it.clientUuid to it.adoptionStatus }
                .toSet(),
        ).containsExactly(
            "owner-cand" to com.lezi.babylog.core.model.FulfillmentAdoptionStatus.ADOPTED,
            localCand.clientUuid to
                com.lezi.babylog.core.model.FulfillmentAdoptionStatus.CONFLICT_NOT_ADOPTED,
        )
    }
    @Test
    fun conflictAuditListAndConvertAreAdminOnlyAndIdempotent() = runTest {
        val memberSync = RecordingSyncPort(
            membershipId = "m-member",
            role = com.lezi.babylog.sync.session.FamilyRole.Member,
            familyId = "fam-audit",
            deviceId = "dev-member",
        )
        val memberFakes = Fakes(memberSync)
        memberFakes.wireTransactionalSnapshots()
        val memberCare = memberFakes.careLog()
        val babyId = memberFakes.seedFamilyAuthorityBaby()
        val now = 11_000_000L
        val planId = memberCare.createCarePlan(
            babyId = babyId,
            type = RecordType.BATH,
            scheduledAt = now + 60_000L,
            nowMillis = now,
        )
        val planUuid = memberCare.getCarePlan(planId)!!.clientUuid
        val loserRecordId = memberCare.fulfillCarePlan(
            carePlanId = planId,
            actualTimestamp = now,
            note = "member-fulfill",
            photoLocalPaths = listOf("loser/photo.jpg"),
            nowMillis = now + 1,
        )
        val loserRecord = memberCare.getRecord(loserRecordId)!!
        // Remote admin candidate wins authority.
        memberFakes.records.upsert(
            RecordEntity(
                clientUuid = "owner-rec-audit",
                babyId = babyId,
                type = RecordType.BATH.key,
                timestamp = now,
                note = "owner-fulfill",
                payloadJson = "{}",
                updatedAt = now + 50,
                syncDirty = false,
            ),
        )
        memberFakes.fulfillmentCandidates.upsert(
            FulfillmentCandidateEntity(
                clientUuid = "owner-cand-audit",
                carePlanClientUuid = planUuid,
                recordClientUuid = "owner-rec-audit",
                actualTimestamp = now + 9_000,
                confirmedAt = now + 100,
                submitterMembershipId = "m-owner",
                submitterRole = "owner",
                updatedAt = now + 100,
                syncDirty = false,
            ),
        )
        memberCare.resolveFulfillmentAuthorityForPlan(planUuid)
        val loserCand = memberCare.listFulfillmentCandidatesForPlan(planUuid)
            .single { it.recordClientUuid == loserRecord.clientUuid }
        assertThat(loserCand.adoptionStatus)
            .isEqualTo(com.lezi.babylog.core.model.FulfillmentAdoptionStatus.CONFLICT_NOT_ADOPTED)

        // Non-admin: empty list, null detail, convert throws.
        assertThat(memberCare.listConflictNotAdoptedAudits(carePlanClientUuid = planUuid))
            .isEmpty()
        assertThat(memberCare.getConflictNotAdoptedAudit(loserCand.clientUuid)).isNull()
        val denied = runCatching {
            memberCare.convertConflictNotAdoptedToIndependentRecord(loserCand.clientUuid)
        }.exceptionOrNull()
        assertThat(denied).isInstanceOf(ConflictAuditPermissionException::class.java)

        // Admin path on same data (rebind CareLog with owner session).
        val adminSync = RecordingSyncPort(
            membershipId = "m-owner",
            role = com.lezi.babylog.sync.session.FamilyRole.Owner,
            familyId = "fam-audit",
            deviceId = "dev-owner",
        )
        val adminCare = CareLog(
            memberFakes.babies,
            memberFakes.records,
            memberFakes.carePlans,
            memberFakes.customItems,
            memberFakes.users,
            memberFakes.families,
            memberFakes.memberships,
            memberFakes.media,
            memberFakes.settings,
            adminSync,
            memberFakes.reminders,
            memberFakes.transactions,
            memberFakes.systemCalendar,
            memberFakes.fulfillmentCandidates,
            memberFakes.calendarReminderMutationGuard,
            memberFakes.clock,
            mediaPathGate = com.lezi.babylog.core.database.MediaLocalPathGate(),
            localDataMutationEpoch = memberFakes.localDataMutationEpoch,
            wakeObservationDao = memberFakes.wakeObservations,
            conflictSummaryDao = memberFakes.conflictSummaries,
            conflictDetailCacheDao = memberFakes.conflictDetailCache,
            sourceRelationDao = memberFakes.sourceRelations,
        )
        val audits = adminCare.listConflictNotAdoptedAudits(carePlanClientUuid = planUuid)
        assertThat(audits).hasSize(1)
        val audit = audits.single()
        assertThat(audit.candidateClientUuid).isEqualTo(loserCand.clientUuid)
        assertThat(audit.photoLocalPaths).containsExactly("loser/photo.jpg")
        assertThat(audit.notAdoptedReason).contains("管理员")
        assertThat(audit.isConverted).isFalse()

        val convertedId = adminCare.convertConflictNotAdoptedToIndependentRecord(
            candidateClientUuid = loserCand.clientUuid,
            nowMillis = now + 200,
        )
        val converted = adminCare.getRecord(convertedId)!!
        assertThat(converted.clientUuid).isNotEqualTo(loserRecord.clientUuid)
        assertThat(converted.note).isEqualTo("member-fulfill")
        assertThat(adminCare.listRecordPhotoPaths(convertedId))
            .containsExactly("loser/photo.jpg")
        // Ordinary surfaces include the new independent record.
        val day = java.time.Instant.ofEpochMilli(now)
            .atZone(java.time.ZoneOffset.UTC)
            .toLocalDate()
        assertThat(
            adminCare.dayRecords(babyId, day, java.time.ZoneOffset.UTC).map { it.clientUuid },
        ).contains(converted.clientUuid)
        // Loser still excluded; plan authority unchanged.
        assertThat(
            adminCare.dayRecords(babyId, day, java.time.ZoneOffset.UTC).map { it.clientUuid },
        ).doesNotContain(loserRecord.clientUuid)
        assertThat(adminCare.getCarePlan(planId)!!.fulfilledRecordClientUuid)
            .isEqualTo("owner-rec-audit")
        val after = adminCare.listFulfillmentCandidatesForPlan(planUuid)
            .single { it.clientUuid == loserCand.clientUuid }
        assertThat(after.adoptionStatus)
            .isEqualTo(com.lezi.babylog.core.model.FulfillmentAdoptionStatus.CONFLICT_NOT_ADOPTED)
        assertThat(after.convertedRecordClientUuid).isEqualTo(converted.clientUuid)

        // Idempotent retry.
        val again = adminCare.convertConflictNotAdoptedToIndependentRecord(
            candidateClientUuid = loserCand.clientUuid,
            nowMillis = now + 300,
        )
        assertThat(again).isEqualTo(convertedId)
        val ordinaryBath = adminCare.dayRecords(babyId, day, java.time.ZoneOffset.UTC)
            .filter { it.type == RecordType.BATH }
        assertThat(ordinaryBath.map { it.clientUuid }.toSet())
            .containsExactly("owner-rec-audit", converted.clientUuid)
        // Media ownership is independent (new media clientUuids on converted record).
        val loserMedia = memberFakes.media.listActiveForRecord(loserRecordId)
        val convertedMedia = memberFakes.media.listActiveForRecord(convertedId)
        assertThat(convertedMedia).hasSize(1)
        assertThat(loserMedia).hasSize(1)
        assertThat(convertedMedia.single().clientUuid)
            .isNotEqualTo(loserMedia.single().clientUuid)
        assertThat(convertedMedia.single().localUri).isEqualTo("loser/photo.jpg")
    }
    @Test
    fun fulfillCarePlanStampsLocalSubmitterTrailFromJoinedSession() = runTest {
        val ownerSync = RecordingSyncPort(
            membershipId = "m-owner-local",
            role = com.lezi.babylog.sync.session.FamilyRole.Owner,
            familyId = "fam-stamp",
            deviceId = "dev-owner",
        )
        val fakes = Fakes(ownerSync)
        fakes.wireTransactionalSnapshots()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = 8_500_000L
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.BATH,
            scheduledAt = now + 60_000L,
            nowMillis = now,
        )
        val planUuid = care.getCarePlan(planId)!!.clientUuid
        care.fulfillCarePlan(
            carePlanId = planId,
            actualTimestamp = now,
            nowMillis = now + 1,
        )
        val cand = care.listFulfillmentCandidatesForPlan(planUuid).single()
        assertThat(cand.submitterMembershipId).isEqualTo("m-owner-local")
        assertThat(cand.submitterRole).isEqualTo("owner")
        assertThat(cand.adoptionStatus)
            .isEqualTo(com.lezi.babylog.core.model.FulfillmentAdoptionStatus.ADOPTED)
        // Unjoined session leaves blank trails (server freeze still required for peers).
        val offline = Fakes()
        offline.wireTransactionalSnapshots()
        val offlineCare = offline.careLog()
        val offlineBaby = offlineCare.createBaby(
            CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 2),
        )
        val offlinePlan = offlineCare.createCarePlan(
            babyId = offlineBaby,
            type = RecordType.PEE,
            scheduledAt = now + 90_000L,
            nowMillis = now + 2,
        )
        offlineCare.fulfillCarePlan(
            carePlanId = offlinePlan,
            actualTimestamp = now + 2,
            nowMillis = now + 3,
        )
        val offlineCand = offlineCare.listFulfillmentCandidatesForPlan(
            offlineCare.getCarePlan(offlinePlan)!!.clientUuid,
        ).single()
        assertThat(offlineCand.submitterMembershipId).isEmpty()
        assertThat(offlineCand.submitterRole).isEmpty()
    }
    @Test
    fun multiCandidateEarlierConfirmedAtWinsAmongPeersAndIgnoresActualTime() = runTest {
        val fakes = Fakes()
        fakes.wireTransactionalSnapshots()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = 9_000_000L
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.FORMULA,
            scheduledAt = now + 60_000L,
            payloadJson = """{"amount_ml":90}""",
            nowMillis = now,
        )
        val planUuid = care.getCarePlan(planId)!!.clientUuid
        fakes.carePlans.update(
            fakes.carePlans.get(planId)!!.copy(
                status = "completed",
                fulfilledRecordClientUuid = "rec-late",
                fulfilledAt = now + 200,
                updatedAt = now + 200,
                syncDirty = false,
            ),
        )
        fakes.records.upsert(
            RecordEntity(
                clientUuid = "rec-late",
                babyId = babyId,
                type = RecordType.FORMULA.key,
                timestamp = now + 50,
                payloadJson = """{"amount_ml":90}""",
                updatedAt = now + 200,
            ),
        )
        fakes.records.upsert(
            RecordEntity(
                clientUuid = "rec-early",
                babyId = babyId,
                type = RecordType.FORMULA.key,
                timestamp = now + 5_000,
                payloadJson = """{"amount_ml":120}""",
                updatedAt = now + 100,
            ),
        )
        fakes.fulfillmentCandidates.upsert(
            FulfillmentCandidateEntity(
                clientUuid = "cand-late",
                carePlanClientUuid = planUuid,
                recordClientUuid = "rec-late",
                actualTimestamp = now + 50,
                confirmedAt = now + 200,
                submitterRole = "member",
                updatedAt = now + 200,
                syncDirty = false,
            ),
        )
        fakes.fulfillmentCandidates.upsert(
            FulfillmentCandidateEntity(
                clientUuid = "cand-early",
                carePlanClientUuid = planUuid,
                recordClientUuid = "rec-early",
                actualTimestamp = now + 5_000,
                confirmedAt = now + 100,
                submitterRole = "member",
                updatedAt = now + 100,
                syncDirty = false,
            ),
        )
        care.resolveFulfillmentAuthorityForPlan(planUuid)
        assertThat(care.getCarePlan(planId)!!.fulfilledRecordClientUuid).isEqualTo("rec-early")
        val day = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneOffset.UTC).toLocalDate()
        assertThat(
            care.dayRecords(babyId, day, java.time.ZoneOffset.UTC).map { it.clientUuid },
        ).containsExactly("rec-early")
    }
    @Test
    fun fulfillCarePlanEmitsStableCandidateAndRetryReusesIdentity() = runTest {
        val fakes = Fakes()
        fakes.wireTransactionalSnapshots()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = 7_000_000L
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.BATH,
            scheduledAt = now + 60_000L,
            photoLocalPaths = listOf("plans/a.jpg"),
            nowMillis = now,
        )
        val planUuid = care.getCarePlan(planId)!!.clientUuid
        val recordId = care.fulfillCarePlan(
            carePlanId = planId,
            actualTimestamp = now,
            photoLocalPaths = listOf("records/f1.jpg"),
            nowMillis = now + 1,
        )
        val first = care.listFulfillmentCandidatesForPlan(planUuid).single()
        assertThat(first.recordClientUuid).isEqualTo(care.getRecord(recordId)!!.clientUuid)
        assertThat(first.confirmedAt).isEqualTo(care.getCarePlan(planId)!!.fulfilledAt)
        assertThat(first.clientUuid).isNotEmpty()
        val frozenUuid = first.clientUuid
        val frozenConfirm = first.confirmedAt

        // Simulate sync mark then retry re-dirty via ensure path (completeNursing replay).
        fakes.fulfillmentCandidates.markSynced(first.clientUuid, first.updatedAt)
        assertThat(care.listFulfillmentCandidatesForPlan(planUuid).single().syncDirty).isFalse()
        // Idempotent completeOpen path through completeNursing-style ensure:
        // re-dirty same candidate without minting a new uuid.
        val nursingPlanId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.NURSING,
            scheduledAt = now + 120_000L,
            payloadJson =
                """{"left_min":0,"right_min":0,"order":"LR","record_mode":"end"}""",
            nowMillis = now + 2,
        )
        val nursingUuid = care.getCarePlan(nursingPlanId)!!.clientUuid
        val completionUuid = "nursing-complete-uuid-stable"
        care.completeNursing(
            babyId = babyId,
            leftMin = 5,
            rightMin = 3,
            order = "LR",
            startedAt = now,
            endedAt = now + 500,
            completionClientUuid = completionUuid,
            carePlanId = nursingPlanId,
        )
        val nursingCand = care.listFulfillmentCandidatesForPlan(nursingUuid).single()
        // Replay completeNursing with same completion id → same candidate identity.
        care.completeNursing(
            babyId = babyId,
            leftMin = 5,
            rightMin = 3,
            order = "LR",
            startedAt = now,
            endedAt = now + 500,
            completionClientUuid = completionUuid,
            carePlanId = nursingPlanId,
        )
        val nursingCand2 = care.listFulfillmentCandidatesForPlan(nursingUuid).single()
        assertThat(nursingCand2.clientUuid).isEqualTo(nursingCand.clientUuid)
        assertThat(nursingCand2.confirmedAt).isEqualTo(nursingCand.confirmedAt)
        assertThat(care.listFulfillmentCandidatesForPlan(planUuid).single().clientUuid)
            .isEqualTo(frozenUuid)
        assertThat(care.listFulfillmentCandidatesForPlan(planUuid).single().confirmedAt)
            .isEqualTo(frozenConfirm)
    }
    @Test
    fun carePlanFulfillRollsBackWhenRecordInsertFails() = runTest {
        val fakes = Fakes()
        fakes.wireTransactionalSnapshots()
        // Fail second record insert path by throwing inside transaction after plan load:
        // use a non-existent baby on a crafted plan to force requireActiveBaby failure mid-tx.
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = 2_000_000L
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.BATH,
            scheduledAt = now + 60_000L,
            nowMillis = now,
        )
        // Soft-delete baby so fulfill fails requireActiveBaby after plan is loaded.
        fakes.babies.listAll().first().let { baby ->
            fakes.babies.update(baby.copy(deletedAt = now))
        }
        // FakeBabyDao.get may still return deleted — force by clearing babies.
        fakes.babies.deleteAll()
        val failure = runCatching {
            care.fulfillCarePlan(
                carePlanId = planId,
                actualTimestamp = now - 100L,
                nowMillis = now,
            )
        }.exceptionOrNull()
        assertThat(failure).isNotNull()
        // Plan row still pending (transaction rolled back).
        assertThat(fakes.carePlans.get(planId)?.status).isEqualTo("pending")
        assertThat(fakes.records.listAllIncludingDeleted()).isEmpty()
    }
    @Test
    fun createCarePlanSchedulesReminderAndFulfillCancels() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = System.currentTimeMillis()
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.PEE,
            scheduledAt = now + 60_000L,
            payloadJson = """{"pee_amount":2}""",
            nowMillis = now,
        )
        assertThat(fakes.reminders.scheduledCarePlanIds).contains(planId)
        care.fulfillCarePlan(
            carePlanId = planId,
            actualTimestamp = now,
            payloadJson = """{"pee_amount":2}""",
            nowMillis = now + 1,
        )
        assertThat(fakes.reminders.scheduledCarePlanIds).doesNotContain(planId)
        assertThat(fakes.reminders.cancelledCarePlanIds).contains(planId)
    }
    @Test
    fun createUpdateSkipDeleteCarePlanMarksDirtyAndRequestsFamilySync() = runTest {
        val sync = RecordingSyncPort()
        val fakes = Fakes(sync)
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = System.currentTimeMillis()
        val beforeCreate = sync.requests
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.FORMULA,
            scheduledAt = now + 120_000L,
            photoLocalPaths = listOf("photos/p0.jpg"),
            payloadJson = """{"amount_ml":90}""",
            nowMillis = now,
        )
        val created = care.getCarePlan(planId)!!
        assertThat(created.syncDirty).isTrue()
        assertThat(sync.requests).isGreaterThan(beforeCreate)
        assertThat(fakes.media.listActiveForCarePlan(planId)).hasSize(1)
        assertThat(fakes.media.listActiveForCarePlan(planId).single().syncDirty).isTrue()

        val beforeUpdate = sync.requests
        care.updateCarePlan(
            carePlanId = planId,
            scheduledAt = now + 180_000L,
            note = "改备注",
            nowMillis = now + 1,
        )
        assertThat(care.getCarePlan(planId)!!.syncDirty).isTrue()
        assertThat(sync.requests).isGreaterThan(beforeUpdate)

        val beforeSkip = sync.requests
        care.skipCarePlan(planId, nowMillis = now + 2)
        assertThat(fakes.carePlans.get(planId)!!.syncDirty).isTrue()
        assertThat(fakes.carePlans.get(planId)!!.status).isEqualTo("skipped")
        assertThat(sync.requests).isGreaterThan(beforeSkip)

        val plan2 = care.createCarePlan(
            babyId = babyId,
            type = RecordType.PEE,
            scheduledAt = now + 200_000L,
            nowMillis = now + 3,
        )
        val beforeDelete = sync.requests
        care.deleteCarePlan(plan2, nowMillis = now + 4)
        assertThat(fakes.carePlans.get(plan2)!!.deletedAt).isNotNull()
        assertThat(fakes.carePlans.get(plan2)!!.syncDirty).isTrue()
        assertThat(sync.requests).isGreaterThan(beforeDelete)
    }
    @Test
    fun onFamilyCarePlansAppliedProjectsOpenAndCancelsTerminalWithoutRequestingPermission() =
        runTest {
            val fakes = Fakes()
            fakes.systemCalendar.permission = false
            val care = fakes.careLog()
            val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
            val now = System.currentTimeMillis()
            val openId = care.createCarePlan(
                babyId = babyId,
                type = RecordType.PEE,
                scheduledAt = now + 60_000L,
                nowMillis = now,
            )
            val openUuid = care.getCarePlan(openId)!!.clientUuid
            // Simulate remote-applied clean row (syncDirty=false).
            fakes.carePlans.update(
                fakes.carePlans.get(openId)!!.copy(syncDirty = false),
            )
            fakes.reminders.scheduledCarePlanIds.clear()
            care.onFamilyCarePlansApplied(listOf(openUuid))
            // No calendar permission → Lezi reminder path; never requests permission.
            assertThat(fakes.systemCalendar.upserts).isEmpty()
            assertThat(fakes.reminders.scheduledCarePlanIds).contains(openId)

            val skipId = care.createCarePlan(
                babyId = babyId,
                type = RecordType.FORMULA,
                scheduledAt = now + 90_000L,
                payloadJson = """{"amount_ml":90}""",
                nowMillis = now + 1,
            )
            care.skipCarePlan(skipId, nowMillis = now + 2)
            val skipUuid = fakes.carePlans.get(skipId)!!.clientUuid
            fakes.carePlans.update(
                fakes.carePlans.get(skipId)!!.copy(syncDirty = false),
            )
            fakes.reminders.cancelledCarePlanIds.clear()
            care.onFamilyCarePlansApplied(listOf(skipUuid))
            assertThat(fakes.reminders.cancelledCarePlanIds).contains(skipId)
        }
    @Test
    fun committedCarePlanSkipContinuesWhenAlarmCancellationFails() = runTest {
        val sync = RecordingSyncPort()
        val fakes = Fakes(sync)
        fakes.systemCalendar.permission = true
        fakes.settings.setSystemCalendarEnabled(true)
        fakes.settings.setSystemCalendarId("cal-1")
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = System.currentTimeMillis()
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.BATH,
            scheduledAt = now + 60_000L,
            nowMillis = now,
        )
        val planUuid = care.getCarePlan(planId)!!.clientUuid
        val eventId = parseSystemCalendarEventMap(
            fakes.settings.settings.first().systemCalendarEventMapJson,
        )[planUuid]
        assertThat(eventId).isNotNull()
        fakes.reminders.carePlanCancelFailure = IllegalStateException("alarm service unavailable")
        val syncRequestsBeforeSkip = sync.requests

        val failure = runCatching {
            care.skipCarePlan(planId, nowMillis = now + 1)
        }.exceptionOrNull()

        assertThat(failure).isNull()
        assertThat(fakes.carePlans.get(planId)!!.status).isEqualTo("skipped")
        assertThat(fakes.systemCalendar.deleted).contains(eventId)
        assertThat(sync.requests).isGreaterThan(syncRequestsBeforeSkip)
    }
    @Test
    fun onFamilyCarePlansAppliedCancelsReminderAndSystemCalendarOnPeerCompleteAndDelete() =
        runTest {
            // Dual-device residual path: peer terminal package lands on next foreground
            // sync → receiving device cancels its own Lezi reminder + system calendar copy.
            val fakes = Fakes()
            fakes.systemCalendar.permission = true
            fakes.settings.setSystemCalendarEnabled(true)
            fakes.settings.setSystemCalendarId("cal-1")
            val care = fakes.careLog()
            val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
            val now = System.currentTimeMillis()

            // Peer complete: local open plan already projected to system calendar.
            val completeId = care.createCarePlan(
                babyId = babyId,
                type = RecordType.BATH,
                scheduledAt = now + 60_000L,
                nowMillis = now,
            )
            val completeUuid = care.getCarePlan(completeId)!!.clientUuid
            val completeEvent = parseSystemCalendarEventMap(
                fakes.settings.settings.first().systemCalendarEventMapJson,
            )[completeUuid]
            assertThat(completeEvent).isNotNull()
            // Simulate remote-applied completed package (syncDirty=false, status completed).
            fakes.carePlans.update(
                fakes.carePlans.get(completeId)!!.copy(
                    status = CarePlanStatus.COMPLETED.storageKey,
                    fulfilledRecordClientUuid = "peer-record",
                    fulfilledAt = now + 10,
                    updatedAt = now + 10,
                    syncDirty = false,
                ),
            )
            fakes.reminders.cancelledCarePlanIds.clear()
            fakes.systemCalendar.deleted.clear()
            care.onFamilyCarePlansApplied(listOf(completeUuid))
            assertThat(fakes.reminders.cancelledCarePlanIds).contains(completeId)
            assertThat(fakes.systemCalendar.deleted).contains(completeEvent)
            assertThat(
                parseSystemCalendarEventMap(
                    fakes.settings.settings.first().systemCalendarEventMapJson,
                ),
            ).doesNotContainKey(completeUuid)

            // Peer delete/tombstone: open plan projected, then remote soft-delete applied.
            val deleteId = care.createCarePlan(
                babyId = babyId,
                type = RecordType.PEE,
                scheduledAt = now + 120_000L,
                nowMillis = now + 20,
            )
            val deleteUuid = care.getCarePlan(deleteId)!!.clientUuid
            val deleteEvent = parseSystemCalendarEventMap(
                fakes.settings.settings.first().systemCalendarEventMapJson,
            )[deleteUuid]
            assertThat(deleteEvent).isNotNull()
            fakes.carePlans.update(
                fakes.carePlans.get(deleteId)!!.copy(
                    deletedAt = now + 30,
                    updatedAt = now + 30,
                    syncDirty = false,
                ),
            )
            fakes.reminders.cancelledCarePlanIds.clear()
            fakes.systemCalendar.deleted.clear()
            care.onFamilyCarePlansApplied(listOf(deleteUuid))
            assertThat(fakes.reminders.cancelledCarePlanIds).contains(deleteId)
            assertThat(fakes.systemCalendar.deleted).contains(deleteEvent)
            assertThat(
                parseSystemCalendarEventMap(
                    fakes.settings.settings.first().systemCalendarEventMapJson,
                ),
            ).doesNotContainKey(deleteUuid)
        }
    @Test
    fun everyPlanableNonStatefulBuiltInCanCreateAndFulfillCarePlan() = runTest {
        val planable = RecordType.entries.filter { it.isPlanableNonStateful }
        assertThat(planable).isNotEmpty()
        assertThat(planable).doesNotContain(RecordType.SLEEP)
        assertThat(planable).doesNotContain(RecordType.NURSING)
        assertThat(planable).doesNotContain(RecordType.CUSTOM)

        for (type in planable) {
            val fakes = Fakes()
            val care = fakes.careLog()
            val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
            // Reminder adapter uses wall-clock now for eligibility.
            val now = System.currentTimeMillis()
            val payload = samplePlanPayload(type)
            val planId = care.createCarePlan(
                babyId = babyId,
                type = type,
                scheduledAt = now + 60_000L,
                payloadJson = payload,
                nowMillis = now,
            )
            val plan = care.getCarePlan(planId)!!
            assertThat(plan.type).isEqualTo(type)
            assertThat(plan.status).isEqualTo(CarePlanStatus.PENDING)
            assertThat(fakes.reminders.scheduledCarePlanIds).contains(planId)

            val recordId = care.fulfillCarePlan(
                carePlanId = planId,
                actualTimestamp = now,
                payloadJson = payload,
                nowMillis = now + 1,
            )
            assertThat(recordId).isGreaterThan(0L)
            assertThat(care.getCarePlan(planId)!!.status).isEqualTo(CarePlanStatus.COMPLETED)
            assertThat(fakes.records.get(recordId)!!.type).isEqualTo(type.key)
            assertThat(fakes.reminders.cancelledCarePlanIds).contains(planId)
        }
    }
    @Test
    fun carePlanReminderPermissionDeniedDoesNotBlockCreate() = runTest {
        val fakes = Fakes()
        fakes.reminders.carePlanPermissionGranted = false
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = System.currentTimeMillis()
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.FORMULA,
            scheduledAt = now + 60_000L,
            payloadJson = """{"amount_ml":80}""",
            nowMillis = now,
        )
        assertThat(planId).isGreaterThan(0L)
        assertThat(care.getCarePlan(planId)).isNotNull()
        // Schedule attempted but adapter soft-failed without throwing.
        assertThat(fakes.reminders.scheduledCarePlanIds).doesNotContain(planId)
    }
    @Test
    fun updateCarePlanReschedulesReminderAndSkipCancels() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = 5_000_000L
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.PEE,
            scheduledAt = now + 60_000L,
            payloadJson = """{"pee_amount":1}""",
            nowMillis = now,
        )
        assertThat(fakes.reminders.scheduledCarePlanIds).contains(planId)
        care.updateCarePlan(
            carePlanId = planId,
            scheduledAt = now + 120_000L,
            nowMillis = now + 1,
        )
        assertThat(fakes.reminders.scheduledCarePlanIds).contains(planId)
        care.skipCarePlan(planId, nowMillis = now + 2)
        assertThat(fakes.reminders.scheduledCarePlanIds).doesNotContain(planId)
        assertThat(fakes.reminders.cancelledCarePlanIds).contains(planId)
    }
    @Test
    fun editingCalendarReadyPlanNeverPublishesNoOwnerHandoffState() = runTest {
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
            scheduledAt = now + 60_000L,
            nowMillis = now,
        )
        assertThat(fakes.carePlans.get(planId)!!.systemCalendarReminderReady).isTrue()

        val guardEntered = CompletableDeferred<Unit>()
        val releaseGuard = CompletableDeferred<Unit>()
        val guardHolder = launch {
            fakes.calendarReminderMutationGuard.withLock {
                guardEntered.complete(Unit)
                releaseGuard.await()
            }
        }
        guardEntered.await()
        val edit = async {
            care.updateCarePlan(
                carePlanId = planId,
                scheduledAt = now + 120_000L,
                note = "改期",
                nowMillis = now + 1L,
            )
        }
        runCurrent()

        val committedBeforeProviderHandoff = fakes.carePlans.get(planId)!!
        assertThat(committedBeforeProviderHandoff.scheduledAt).isEqualTo(now + 120_000L)
        assertThat(
            committedBeforeProviderHandoff.systemCalendarReminderReady ||
                committedBeforeProviderHandoff.systemCalendarProjectionPending,
        ).isTrue()

        releaseGuard.complete(Unit)
        edit.await()
        guardHolder.join()
        val reprojected = fakes.carePlans.get(planId)!!
        assertThat(reprojected.systemCalendarReminderReady).isTrue()
        assertThat(reprojected.systemCalendarProjectionPending).isFalse()
    }
    @Test
    fun nursingCarePlanIsIntentOnlyAndManualFulfillCompletesAtomically() = runTest {
        val fakes = Fakes()
        fakes.wireTransactionalSnapshots()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = System.currentTimeMillis()
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.NURSING,
            scheduledAt = now + 60_000L,
            payloadJson =
                """{"left_min":0,"right_min":0,"order":"LR","record_mode":"end"}""",
            nowMillis = now,
        )
        assertThat(care.getCarePlan(planId)!!.status).isEqualTo(CarePlanStatus.PENDING)
        // Schedule must not invent a nursing Record.
        assertThat(
            fakes.records.listAllIncludingDeleted().none { it.type == RecordType.NURSING.key },
        ).isTrue()

        val recordId = care.fulfillCarePlan(
            carePlanId = planId,
            actualTimestamp = now,
            payloadJson =
                """{"left_min":10,"right_min":5,"order":"LR","record_mode":"end"}""",
            nowMillis = now + 1,
        )
        assertThat(recordId).isGreaterThan(0L)
        assertThat(care.getCarePlan(planId)!!.status).isEqualTo(CarePlanStatus.COMPLETED)
        assertThat(fakes.records.get(recordId)!!.type).isEqualTo(RecordType.NURSING.key)
        // Second fulfill fails closed (no double record).
        val second = runCatching {
            care.fulfillCarePlan(
                carePlanId = planId,
                actualTimestamp = now,
                payloadJson =
                    """{"left_min":1,"right_min":1,"order":"LR","record_mode":"end"}""",
                nowMillis = now + 2,
            )
        }.exceptionOrNull()
        assertThat(second).isNotNull()
        assertThat(
            fakes.records.listAllIncludingDeleted()
                .count { it.type == RecordType.NURSING.key && it.deletedAt == null },
        ).isEqualTo(1)
    }
    @Test
    fun sleepCarePlanIsIntentOnlyOpenAndClosedFulfill() = runTest {
        val fakes = Fakes()
        fakes.wireTransactionalSnapshots()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = System.currentTimeMillis()
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.SLEEP,
            scheduledAt = now + 60_000L,
            payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
            nowMillis = now,
        )
        assertThat(care.getCarePlan(planId)!!.status).isEqualTo(CarePlanStatus.PENDING)
        assertThat(
            fakes.records.listAllIncludingDeleted().none { it.type == RecordType.SLEEP.key },
        ).isTrue()

        // Confirm 睡下: open interval is the fact; plan completes with it.
        val openId = care.fulfillCarePlan(
            carePlanId = planId,
            actualTimestamp = now,
            endTimestamp = null,
            nowMillis = now + 1,
        )
        val open = fakes.records.get(openId)!!
        assertThat(open.type).isEqualTo(RecordType.SLEEP.key)
        assertThat(open.endTimestamp).isNull()
        assertThat(care.getCarePlan(planId)!!.status).isEqualTo(CarePlanStatus.COMPLETED)

        // Second open-sleep fulfill while open sleep exists fails closed.
        val plan2 = care.createCarePlan(
            babyId = babyId,
            type = RecordType.SLEEP,
            scheduledAt = now + 90_000L,
            payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
            nowMillis = now + 2,
        )
        val race = runCatching {
            care.fulfillCarePlan(
                carePlanId = plan2,
                actualTimestamp = now,
                endTimestamp = null,
                nowMillis = now + 3,
            )
        }.exceptionOrNull()
        assertThat(race).isInstanceOf(SleepStateChangedException::class.java)
        assertThat(care.getCarePlan(plan2)!!.status).isEqualTo(CarePlanStatus.PENDING)

        // Close the open sleep, then closed-interval fulfill of plan2 succeeds.
        care.sleepUp(babyId, at = now + 1_000L, nowMillis = now + 1_000L)
        val closedId = care.fulfillCarePlan(
            carePlanId = plan2,
            actualTimestamp = now - 40 * 60_000L,
            endTimestamp = now - 10 * 60_000L,
            nowMillis = now + 4,
        )
        val closed = fakes.records.get(closedId)!!
        assertThat(closed.endTimestamp).isEqualTo(now - 10 * 60_000L)
        assertThat(care.getCarePlan(plan2)!!.status).isEqualTo(CarePlanStatus.COMPLETED)
    }
    @Test
    fun alarmManagerFailureNeverRollsBackCommittedCarePlan() = runTest {
        val fakes = Fakes()
        fakes.reminders.carePlanScheduleFailure = IllegalStateException("alarm unavailable")
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = System.currentTimeMillis()

        val result = runCatching {
            care.createCarePlan(
                babyId = babyId,
                type = RecordType.BATH,
                scheduledAt = now + 60_000L,
                nowMillis = now,
            )
        }

        assertThat(result.isSuccess).isTrue()
        assertThat(care.getCarePlan(result.getOrThrow())).isNotNull()
        assertThat(fakes.reminders.cancelledCarePlanIds).contains(result.getOrThrow())
    }
    @Test
    fun perPlanCalendarOptOutSurvivesReloadEditAndBootReconciliation() = runTest {
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
            projectToSystemCalendar = false,
        )

        assertThat(care.getCarePlan(planId)!!.systemCalendarProjectionEnabled).isFalse()
        assertThat(care.isCarePlanSystemCalendarUnsynced(planId)).isFalse()
        assertThat(fakes.systemCalendar.upserts).isEmpty()

        fakes.reminders.scheduledCarePlanIds.clear()
        care.rescheduleCarePlanReminders(nowMillis = now + 1)
        assertThat(fakes.systemCalendar.upserts).isEmpty()
        assertThat(fakes.reminders.scheduledCarePlanIds).contains(planId)

        care.updateCarePlan(
            carePlanId = planId,
            scheduledAt = now + 180_000L,
            projectToSystemCalendar = true,
            nowMillis = now + 2,
        )
        assertThat(care.getCarePlan(planId)!!.systemCalendarProjectionEnabled).isTrue()
        assertThat(fakes.systemCalendar.upserts).isNotEmpty()
    }
    @Test
    fun deliveredCarePlanAlarmRejectsStaleGenerationAndProviderOwnership() = runTest {
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
        val plan = fakes.carePlans.get(planId)!!

        assertThat(
            care.shouldDeliverCarePlanReminder(planId, plan.clientUuid, plan.scheduledAt),
        ).isTrue()
        assertThat(
            care.shouldDeliverCarePlanReminder(planId, plan.clientUuid, plan.scheduledAt + 1L),
        ).isFalse()
        fakes.carePlans.update(plan.copy(systemCalendarProjectionPending = true))
        assertThat(
            care.shouldDeliverCarePlanReminder(planId, plan.clientUuid, plan.scheduledAt),
        ).isFalse()
        fakes.carePlans.update(
            plan.copy(
                systemCalendarProjectionPending = false,
                systemCalendarReminderReady = true,
            ),
        )
        assertThat(
            care.shouldDeliverCarePlanReminder(planId, plan.clientUuid, plan.scheduledAt),
        ).isFalse()
    }
    @Test
    fun deletedBabyOwnsNoDeliverableCarePlanAlarm() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        care.createBaby(CreateBabyInput(nickname = "保留", birthdayEpochDay = 1))
        val deletedBabyId = care.addBaby(
            CreateBabyInput(nickname = "待删除", birthdayEpochDay = 2),
        )
        val now = System.currentTimeMillis()
        val planId = care.createCarePlan(
            babyId = deletedBabyId,
            type = RecordType.BATH,
            scheduledAt = now + 120_000L,
            nowMillis = now,
        )
        val plan = requireNotNull(fakes.carePlans.get(planId))
        assertThat(
            care.shouldDeliverCarePlanReminder(plan.id, plan.clientUuid, plan.scheduledAt),
        ).isTrue()

        assertThat(care.deleteBaby(deletedBabyId)).isTrue()

        assertThat(
            care.shouldDeliverCarePlanReminder(plan.id, plan.clientUuid, plan.scheduledAt),
        ).isFalse()
    }
    @Test
    fun bootReconciliationCancelsFuturePlansWhoseBabyWasDeleted() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        care.createBaby(CreateBabyInput(nickname = "保留", birthdayEpochDay = 1))
        val deletedBabyId = care.addBaby(
            CreateBabyInput(nickname = "待删除", birthdayEpochDay = 2),
        )
        val now = System.currentTimeMillis()
        val planId = care.createCarePlan(
            babyId = deletedBabyId,
            type = RecordType.BATH,
            scheduledAt = now + 120_000L,
            nowMillis = now,
        )
        assertThat(fakes.reminders.scheduledCarePlanIds).contains(planId)
        assertThat(care.deleteBaby(deletedBabyId)).isTrue()

        fakes.reminders.cancelledCarePlanIds.clear()
        care.rescheduleCarePlanReminders(nowMillis = now + 1L)

        assertThat(fakes.reminders.scheduledCarePlanIds).doesNotContain(planId)
        assertThat(fakes.reminders.cancelledCarePlanIds).contains(planId)
    }
    @Test
    fun fulfillCarePlanAllowsFiveMinuteSkewOnActualStartAndSleepEnd() = runTest {
        val fakes = Fakes()
        fakes.wireTransactionalSnapshots()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = 60_000_000L
        val fiveMin = RecordTime.FULFILLMENT_ACTUAL_TIME_SKEW_MILLIS
        assertThat(fiveMin).isEqualTo(5 * 60_000L)

        val peePlan = care.createCarePlan(
            babyId = babyId,
            type = RecordType.PEE,
            scheduledAt = now + 3_600_000L,
            payloadJson = """{"pee_amount":2}""",
            nowMillis = now,
        )
        // Exactly +5 minutes passes.
        val peeRecordId = care.fulfillCarePlan(
            carePlanId = peePlan,
            actualTimestamp = now + fiveMin,
            nowMillis = now,
        )
        assertThat(care.getRecord(peeRecordId)!!.timestamp).isEqualTo(now + fiveMin)
        assertThat(care.getCarePlan(peePlan)!!.status).isEqualTo(CarePlanStatus.COMPLETED)

        // Write is allowed under fulfill skew, but aggregation waits for the clock.
        val written = care.getRecord(peeRecordId)!!
        val day = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        assertThat(
            CareAggregation.day(listOf(written), day, zone, now).bucket.pee,
        ).isEqualTo(0)
        assertThat(
            CareAggregation.day(listOf(written), day, zone, now + fiveMin).bucket.pee,
        ).isEqualTo(1)

        val sleepPlan = care.createCarePlan(
            babyId = babyId,
            type = RecordType.SLEEP,
            scheduledAt = now + 3_600_000L,
            payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
            nowMillis = now,
        )
        // Closed sleep end exactly at boundary passes.
        val sleepRecordId = care.fulfillCarePlan(
            carePlanId = sleepPlan,
            actualTimestamp = now - 60_000L,
            endTimestamp = now + fiveMin,
            nowMillis = now,
        )
        assertThat(care.getRecord(sleepRecordId)!!.endTimestamp).isEqualTo(now + fiveMin)

        val sleepPlan2 = care.createCarePlan(
            babyId = babyId,
            type = RecordType.SLEEP,
            scheduledAt = now + 3_700_000L,
            payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
            nowMillis = now,
        )
        val endBeyond = runCatching {
            care.fulfillCarePlan(
                carePlanId = sleepPlan2,
                actualTimestamp = now - 60_000L,
                endTimestamp = now + fiveMin + 1L,
                nowMillis = now,
            )
        }.exceptionOrNull()
        assertThat(endBeyond).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(endBeyond!!.message).isEqualTo("不能选未来时刻")
        assertThat(care.getCarePlan(sleepPlan2)!!.status).isEqualTo(CarePlanStatus.PENDING)

        val startBeyondPlan = care.createCarePlan(
            babyId = babyId,
            type = RecordType.FORMULA,
            scheduledAt = now + 3_800_000L,
            payloadJson = """{"amount_ml":100}""",
            nowMillis = now,
        )
        val startBeyond = runCatching {
            care.fulfillCarePlan(
                carePlanId = startBeyondPlan,
                actualTimestamp = now + fiveMin + 1L,
                nowMillis = now,
            )
        }.exceptionOrNull()
        assertThat(startBeyond).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(care.getCarePlan(startBeyondPlan)!!.status).isEqualTo(CarePlanStatus.PENDING)
    }
}
