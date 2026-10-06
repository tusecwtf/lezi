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
import com.lezi.babylog.core.database.causal.WakeObservationEntity
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
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
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
class CareLogRecordWriteTest {
    @Test
    fun addRecordRejectsBabyDeletedAfterComposerOpened() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        care.createBaby(CreateBabyInput(nickname = "保留", birthdayEpochDay = 1))
        val deletedBaby = care.addBaby(CreateBabyInput(nickname = "待删除", birthdayEpochDay = 2))
        assertThat(care.deleteBaby(deletedBaby)).isTrue()

        val failure = runCatching {
            care.addRecord(deletedBaby, RecordType.PEE, timestamp = 1_000L)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(fakes.records.listForBaby(deletedBaby)).isEmpty()
    }
    @Test
    fun addRecordStampsMembershipAuthorFromJoinedSession() = runTest {
        val sessionDevice = "sync-session-device-xyz"
        val sessionMembership = "membership-session-xyz"
        val sync = RecordingSyncPort(
            deviceId = sessionDevice,
            familyId = "family-joined",
            membershipId = sessionMembership,
        )
        val fakes = Fakes(sync)
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val id = care.addRecord(
            babyId = babyId,
            type = RecordType.PEE,
            timestamp = 1_000L,
            payloadJson = """{"pee_amount":2}""",
        )
        val entity = fakes.records.get(id)!!
        assertThat(entity.createdByMembershipId).isEqualTo(sessionMembership)
        assertThat(entity.toModel().createdByMembershipId).isEqualTo(sessionMembership)
    }
    @Test
    fun addRecordLeavesMembershipAuthorEmptyBeforeFamilyJoin() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val id = care.addRecord(
            babyId = babyId,
            type = RecordType.PEE,
            timestamp = 1_000L,
            payloadJson = """{"pee_amount":2}""",
        )
        assertThat(fakes.records.get(id)!!.createdByMembershipId).isEmpty()
    }

    @Test
    fun addRecordFinishesWithoutWaitingForFamilySync() = runTest {
        val sync = RecordingSyncPort(
            delegate = object : com.lezi.babylog.sync.SyncPort by com.lezi.babylog.sync.NoOpSyncPort() {
                override suspend fun sync(
                    trigger: com.lezi.babylog.sync.SyncTrigger,
                ): Result<Unit> = error("记护理不得等待家庭同步裁决")
            },
        )
        val fakes = Fakes(sync)
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))

        val id = care.addRecord(
            babyId = babyId,
            type = RecordType.PEE,
            timestamp = 1_000L,
            payloadJson = """{"pee_amount":2}""",
        )

        assertThat(fakes.records.get(id)).isNotNull()
        assertThat(sync.requests).isGreaterThan(0)
    }

    @Test
    fun observeRecords_isLiveAndUsesHalfOpenDateRange() = runTest {
        val care = Fakes().careLog()
        val babyId = care.createBaby(
            CreateBabyInput(nickname = "豆豆", birthdayEpochDay = LocalDate.of(2026, 1, 1).toEpochDay()),
        )
        val startDay = LocalDate.of(2026, 7, 17)
        val endDay = LocalDate.of(2026, 7, 24)
        val range = care.observeRecords(babyId, startDay, endDay, zone)
        val emissions = mutableListOf<List<com.lezi.babylog.core.model.Record>>()
        val collection = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            range.take(3).toList(emissions)
        }

        val atStart = startDay.atStartOfDay(zone).toInstant().toEpochMilli()
        val id = care.addRecord(babyId, RecordType.PEE, timestamp = atStart)
        yield()
        care.deleteRecord(id)
        yield()
        collection.join()

        assertThat(emissions.map { records -> records.map { it.id } })
            .containsExactly(emptyList<Long>(), listOf(id), emptyList<Long>())
            .inOrder()

        val atEnd = endDay.atStartOfDay(zone).toInstant().toEpochMilli()
        care.addRecord(babyId, RecordType.POOP, timestamp = atEnd)
        assertThat(range.first()).isEmpty()
    }
    @Test
    fun deleteOperationsReportMissingOrAlreadyDeletedTargets() = runTest {
        val care = Fakes().careLog()
        val babyId = care.createBaby(
            CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1),
        )
        val recordId = care.addRecord(babyId, RecordType.PEE, timestamp = 1_000L)
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.PEE,
            scheduledAt = 10_000L,
            nowMillis = 1_000L,
            projectToSystemCalendar = false,
        )

        assertThat(care.deleteRecord(recordId)).isTrue()
        assertThat(care.deleteRecord(recordId)).isFalse()
        assertThat(care.deleteRecord(Long.MAX_VALUE)).isFalse()
        assertThat(care.deleteCarePlan(planId, nowMillis = 2_000L)).isTrue()
        assertThat(care.deleteCarePlan(planId, nowMillis = 3_000L)).isFalse()
        assertThat(care.deleteCarePlan(Long.MAX_VALUE, nowMillis = 3_000L)).isFalse()
    }
    @Test
    fun daySummaryAndWeekSummaryExcludeSourceRoleRows() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        val day = LocalDate.of(2026, 7, 22)
        val at = day.atStartOfDay(zone).toInstant().toEpochMilli() + 3_600_000L
        care.addRecord(
            babyId,
            RecordType.PEE,
            timestamp = at,
            payloadJson = """{"pee_amount":1}""",
            nowMillis = at + 5,
        )
        val sourceId = care.addRecord(
            babyId,
            RecordType.PEE,
            timestamp = at + 1,
            payloadJson = """{"pee_amount":2}""",
            nowMillis = at + 5,
        )
        fakes.sourceRelations.applyPullSummary(
            relationId = "rel-source",
            recordClientUuid = fakes.records.get(sourceId)!!.clientUuid,
            role = com.lezi.babylog.core.database.causal.SourceRelationRole.SOURCE,
            peerIds = listOf("display-peer"),
            observedAt = at,
        )

        assertThat(care.daySummary(babyId, day, zone, now = at + 10).peeCount).isEqualTo(1)
        val weekStart = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        assertThat(care.weekSummary(babyId, weekStart, zone, now = at + 10).totalPee).isEqualTo(1)
    }

    @Test
    fun recentNotes_keepsNewestDistinctTrimmedNotes() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        val base = 1_700_000_000_000L
        val notes = listOf("a", "a", "  b  ", " ", "c", "b", "d", "e", "f")
        notes.forEachIndexed { index, note ->
            care.addRecord(
                babyId = babyId,
                type = RecordType.PEE,
                timestamp = base + index,
                note = note,
                payloadJson = """{"pee_amount":1}""",
                nowMillis = base + notes.size,
            )
        }

        assertThat(care.recentNotes(babyId, RecordType.PEE, limit = 5))
            .containsExactly("f", "e", "d", "b", "c")
            .inOrder()
    }

    @Test
    fun recentNotes_pagesPastNewlineAndTabVariants() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        val base = 1_700_000_000_000L
        val notes = listOf("a", "b", "c", "d", "e") + List(41) { "\n" } + listOf("\tb\t", "  b  ")
        notes.forEachIndexed { index, note ->
            care.addRecord(
                babyId = babyId,
                type = RecordType.PEE,
                timestamp = base + index,
                note = note,
                payloadJson = """{"pee_amount":1}""",
                nowMillis = base + notes.size,
            )
        }

        assertThat(care.recentNotes(babyId, RecordType.PEE, limit = 5))
            .containsExactly("b", "e", "d", "c", "a")
            .inOrder()
    }

    @Test
    fun listRecordPhotoPaths_preservesRecordOrderThenIdOrder() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        val now = 1_700_000_000_000L
        val first = care.addRecord(
            babyId,
            RecordType.PEE,
            timestamp = now,
            payloadJson = """{"pee_amount":1}""",
            nowMillis = now + 10,
        )
        val second = care.addRecord(
            babyId,
            RecordType.PEE,
            timestamp = now + 1,
            payloadJson = """{"pee_amount":1}""",
            nowMillis = now + 10,
        )
        fakes.media.upsert(
            MediaAssetEntity(recordId = first, localUri = "shared.jpg", createdAt = 1),
        )
        fakes.media.upsert(
            MediaAssetEntity(recordId = first, localUri = "a.jpg", createdAt = 2),
        )
        fakes.media.upsert(
            MediaAssetEntity(
                recordId = second,
                localUri = "gone.jpg",
                createdAt = 3,
                deletedAt = 4,
            ),
        )
        fakes.media.upsert(
            MediaAssetEntity(recordId = second, localUri = "shared.jpg", createdAt = 5),
        )
        fakes.media.upsert(
            MediaAssetEntity(recordId = second, localUri = "only-b.jpg", createdAt = 6),
        )

        assertThat(care.listRecordPhotoPaths(listOf(first, second)))
            .containsExactly("shared.jpg", "a.jpg", "shared.jpg", "only-b.jpg")
            .inOrder()
    }

    @Test
    fun listRecordPhotoPaths_chunksQueriesAt500AndKeepsInputOrder() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val recordIds = (1L..600L).toList()
        recordIds.forEach { id ->
            fakes.media.upsert(
                MediaAssetEntity(
                    recordId = id,
                    localUri = "photos/$id.jpg",
                    createdAt = id,
                ),
            )
        }
        val requested = recordIds.asReversed()

        val paths = care.listRecordPhotoPaths(requested)

        assertThat(fakes.media.maxListActiveForRecordsInSize).isGreaterThan(0)
        assertThat(fakes.media.maxListActiveForRecordsInSize).isAtMost(500)
        assertThat(paths).containsExactlyElementsIn(requested.map { "photos/$it.jpg" }).inOrder()
    }

    @Test
    fun sleepDownUp_pairsDuration() = runTest {
        val care = Fakes().careLog()
        val babyId = care.createBaby(
            CreateBabyInput(nickname = "豆豆", birthdayEpochDay = LocalDate.of(2026, 1, 1).toEpochDay()),
        )
        val day = LocalDate.of(2026, 7, 22)
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli() + 10 * 3600_000L
        care.sleepDown(babyId, start)
        care.sleepUp(babyId, start + 90 * 60_000L)
        assertThat(care.daySummary(babyId, day, zone).sleepMinutes).isEqualTo(90)
    }
    @Test
    fun daySummary_includesPreviousDaySleepAndCountsOpenIntervalToNow() = runTest {
        val care = Fakes().careLog()
        val babyId = care.createBaby(
            CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1),
        )
        val day = LocalDate.of(2026, 7, 23)
        val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
        care.addRecord(
            babyId = babyId,
            type = RecordType.SLEEP,
            timestamp = dayStart - 30 * 60_000L,
            endTimestamp = dayStart + 45 * 60_000L,
            payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
        )
        care.addRecord(
            babyId = babyId,
            type = RecordType.SLEEP,
            timestamp = dayStart + 2 * 60 * 60_000L,
            payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
        )

        val records = care.dayRecords(babyId, day, zone)
        val summary = care.daySummary(
            babyId = babyId,
            day = day,
            zone = zone,
            now = dayStart + 3 * 60 * 60_000L,
        )

        assertThat(records).hasSize(2)
        assertThat(summary.sleepMinutes).isEqualTo(45 + 60)
    }
    @Test
    fun observeOpenSleep_isIndependentOfViewedDay() = runTest {
        val care = Fakes().careLog()
        val babyId = care.createBaby(
            CreateBabyInput(nickname = "豆豆", birthdayEpochDay = LocalDate.of(2026, 1, 1).toEpochDay()),
        )
        val yesterday = LocalDate.of(2026, 7, 22)
        val start = yesterday.atTime(23, 30).toInstant(zone).toEpochMilli()

        assertThat(care.observeOpenSleep(babyId).first()).isNull()
        care.sleepDown(babyId, start)
        assertThat(care.observeOpenSleep(babyId).first()!!.timestamp).isEqualTo(start)
        care.sleepUp(babyId, start + 2 * 60 * 60_000L)
        assertThat(care.observeOpenSleep(babyId).first()).isNull()
    }
    @Test
    fun confirmSleep_rechecksStateAndOnlyClosesTheExpectedOpenInterval() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(
            CreateBabyInput(nickname = "豆豆", birthdayEpochDay = LocalDate.of(2026, 1, 1).toEpochDay()),
        )
        val start = 1_700_000_000_000L
        val openId = care.confirmSleep(
            babyId = babyId,
            expectedOpenSleepId = null,
            timestamp = start,
            endTimestamp = null,
            note = "午睡",
            payloadJson = """{"is_nap":true,"anomaly_flag":false}""",
        )

        val duplicateFailure = runCatching {
            care.confirmSleep(
                babyId = babyId,
                expectedOpenSleepId = null,
                timestamp = start + 60_000L,
                endTimestamp = null,
                note = null,
                payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
            )
        }.exceptionOrNull()
        assertThat(duplicateFailure).isInstanceOf(SleepStateChangedException::class.java)
        assertThat(fakes.records.listForBaby(babyId)).hasSize(1)

        care.confirmSleep(
            babyId = babyId,
            expectedOpenSleepId = openId,
            timestamp = start,
            endTimestamp = start + 30 * 60_000L,
            note = "午睡",
            payloadJson = """{"is_nap":true,"anomaly_flag":false}""",
        )
        assertThat(care.observeOpenSleep(babyId).first()).isNull()
        assertThat(care.getRecord(openId)!!.endTimestamp).isEqualTo(start + 30 * 60_000L)
    }
    @Test
    fun updateRecord_cannotClearCompletedSleepEnd() = runTest {
        val care = Fakes().careLog()
        val babyId = care.createBaby(
            CreateBabyInput(nickname = "豆豆", birthdayEpochDay = LocalDate.of(2026, 1, 1).toEpochDay()),
        )
        val start = LocalDate.of(2026, 7, 22).atTime(20, 0).toInstant(zone).toEpochMilli()
        val id = care.addRecord(
            babyId = babyId,
            type = RecordType.SLEEP,
            timestamp = start,
            endTimestamp = start + 90 * 60_000L,
            payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
        )

        val failure = runCatching {
            care.updateRecord(
                id = id,
                timestamp = start,
                endTimestamp = null,
                note = null,
                payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(care.getRecord(id)!!.endTimestamp).isEqualTo(start + 90 * 60_000L)
    }
    @Test
    fun currentWriteBoundaryRejectsUnsupportedMalformedAndBareCustomPayloads() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))

        val unsupported = runCatching {
            care.addRecord(
                babyId = babyId,
                type = RecordType.FORMULA,
                payloadJson = """{"amount_ml":120}""",
                schemaVersion = 1,
            )
        }.exceptionOrNull()
        val malformed = runCatching {
            care.addRecord(
                babyId = babyId,
                type = RecordType.FORMULA,
                payloadJson = """{"amount_ml":""",
            )
        }.exceptionOrNull()
        val bareCustom = runCatching {
            care.addRecord(
                babyId = babyId,
                type = RecordType.CUSTOM,
                payloadJson = """{"title":"抚触"}""",
            )
        }.exceptionOrNull()

        assertThat(unsupported).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(malformed).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(bareCustom).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(fakes.records.listAllIncludingDeleted()).isEmpty()
    }

    @Test
    fun addRecordRejectsOutOfRangePayloadBeforeRowIsStored() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))

        val hotFever = runCatching {
            care.addRecord(
                babyId = babyId,
                type = RecordType.TEMPERATURE,
                payloadJson = """{"celsius":60.0}""",
            )
        }.exceptionOrNull()
        val negativeNursing = runCatching {
            care.addRecord(
                babyId = babyId,
                type = RecordType.NURSING,
                payloadJson = """{"left_min":-5,"right_min":5,"order":"L","record_mode":"end"}""",
            )
        }.exceptionOrNull()
        val intentOnlyNursing = runCatching {
            care.addRecord(
                babyId = babyId,
                type = RecordType.NURSING,
                payloadJson = """{"left_min":0,"right_min":0,"order":"LR","record_mode":"end"}""",
            )
        }.exceptionOrNull()
        val oversizedMilk = runCatching {
            care.addRecord(
                babyId = babyId,
                type = RecordType.FORMULA,
                payloadJson = """{"amount_ml":1000}""",
            )
        }.exceptionOrNull()

        // Non-UI entries must fail at local write: the server refuses the same
        // rows with invalid_domain, which would leave them visible locally but
        // forever pending (0.5.4 S5 zombie rows).
        assertThat(hotFever).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(negativeNursing).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(intentOnlyNursing).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(oversizedMilk).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(fakes.records.listAllIncludingDeleted()).isEmpty()
    }

    @Test
    fun updateRecordRejectsOutOfRangePayloadAndKeepsStoredFact() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        val id = care.addRecord(
            babyId = babyId,
            type = RecordType.PEE,
            payloadJson = """{"pee_amount":2}""",
        )

        val failure = runCatching {
            care.updateRecord(
                id = id,
                timestamp = fakes.records.get(id)!!.timestamp,
                endTimestamp = null,
                note = null,
                payloadJson = """{"pee_amount":9}""",
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(fakes.records.get(id)!!.payloadJson).isEqualTo("""{"pee_amount":2}""")
    }

    @Test
    fun addRecordAcceptsFullServerNumericDomain() = runTest {
        // Boundary alignment: in-range edge values the server accepts must pass
        // the local gate too — the sink adds no stricter-than-server limits.
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))

        val feverEdge = care.addRecord(
            babyId = babyId,
            type = RecordType.TEMPERATURE,
            payloadJson = """{"celsius":43.0}""",
        )
        val maxPee = care.addRecord(
            babyId = babyId,
            type = RecordType.PEE,
            payloadJson = """{"pee_amount":3}""",
        )
        val maxMilk = care.addRecord(
            babyId = babyId,
            type = RecordType.FORMULA,
            payloadJson = """{"amount_ml":999,"prepared_ml":999,"duration_min":1440}""",
        )

        assertThat(feverEdge).isGreaterThan(0L)
        assertThat(maxPee).isGreaterThan(0L)
        assertThat(maxMilk).isGreaterThan(0L)
        assertThat(fakes.records.listForBaby(babyId)).hasSize(3)
    }

    @Test
    fun opaqueRecordCannotBeEditedOrConverted() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        val raw = """{"amount_ml":"""
        val recordId = fakes.records.upsert(
            RecordEntity(
                clientUuid = "damaged-record",
                babyId = babyId,
                type = RecordType.FORMULA.key,
                timestamp = 1_000L,
                endTimestamp = null,
                note = null,
                payloadJson = raw,
                schemaVersion = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
                updatedAt = 1_000L,
            ),
        )

        val updateFailure = runCatching {
            care.updateRecord(
                id = recordId,
                timestamp = 1_000L,
                endTimestamp = null,
                note = null,
                payloadJson = """{"amount_ml":120}""",
                nowMillis = 2_000L,
            )
        }.exceptionOrNull()
        val convertFailure = runCatching {
            care.convertRecordToCarePlan(
                recordId = recordId,
                scheduledAt = 3_000L,
                nowMillis = 2_000L,
            )
        }.exceptionOrNull()

        assertThat(updateFailure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(convertFailure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(fakes.records.get(recordId)!!.payloadJson).isEqualTo(raw)
        assertThat(fakes.records.get(recordId)!!.deletedAt).isNull()
        assertThat(fakes.carePlans.listAllIncludingDeleted()).isEmpty()
    }

    @Test
    fun opaquePlanCannotBeEditedOrFulfilled() = runTest {
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
        val plan = fakes.carePlans.get(planId)!!
        val raw = "{"
        fakes.carePlans.update(plan.copy(payloadJson = raw))

        val updateFailure = runCatching {
            care.updateCarePlan(
                carePlanId = planId,
                scheduledAt = now + 90_000L,
                payloadJson = "{}",
                nowMillis = now,
            )
        }.exceptionOrNull()
        val fulfillFailure = runCatching {
            care.fulfillCarePlan(
                carePlanId = planId,
                actualTimestamp = now,
                payloadJson = "{}",
                nowMillis = now + 1L,
            )
        }.exceptionOrNull()

        assertThat(updateFailure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(fulfillFailure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(fakes.carePlans.get(planId)!!.payloadJson).isEqualTo(raw)
        assertThat(fakes.records.listAllIncludingDeleted()).isEmpty()
    }

    @Test
    fun sleepLegacyEndMustBeStrictlyAfterStart_wakeAllowsEqualityPerWire() = runTest {
        val care = Fakes().careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        val start = 1_700_000_000_000L

        // Dual-compat denormalized sleep end still requires end > start.
        val addFailure = runCatching {
            care.addRecord(
                babyId = babyId,
                type = RecordType.SLEEP,
                timestamp = start,
                endTimestamp = start,
            )
        }.exceptionOrNull()
        assertThat(addFailure).isInstanceOf(IllegalArgumentException::class.java)

        val openId = care.sleepDown(babyId, start)
        // Wire §4.5: wake_timestamp >= sleep.timestamp (equality legal).
        care.sleepUp(babyId, start, nowMillis = start)
        val entityEnd = // raw entity stays null
            // use fakes via getRecord projected end
            care.getRecord(openId)!!.endTimestamp
        assertThat(entityEnd).isEqualTo(start)
        // Pre-start still rejected.
        val open2 = care.sleepDown(babyId, start + 10_000L)
        val preStart = runCatching {
            care.sleepUp(babyId, start + 10_000L - 1L, nowMillis = start + 10_000L)
        }.exceptionOrNull()
        assertThat(preStart).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(care.observeOpenSleep(babyId).first()?.id).isEqualTo(open2)
    }
    @Test
    fun sleepDownTwice_keepsOneOpenSleepAndMarksAnomaly() = runTest {
        val f = Fakes()
        val care = f.careLog()
        val babyId = care.createBaby(
            CreateBabyInput(nickname = "豆豆", birthdayEpochDay = LocalDate.of(2026, 1, 1).toEpochDay()),
        )
        val t0 = 1_700_000_000_000L
        val firstId = care.sleepDown(babyId, t0)
        val secondId = care.sleepDown(babyId, t0 + 60_000L)
        val all = f.records.listForBaby(babyId)
        assertThat(secondId).isEqualTo(firstId)
        assertThat(all).hasSize(1)
        assertThat(all.single().endTimestamp).isNull()
        assertThat(all.single().payloadJson).contains("\"anomaly_flag\":true")
    }
    @Test
    fun sleepUp_doesNotAutoCloseOlderOpenSleeps_wakesOnlyLatest() = runTest {
        val f = Fakes()
        val care = f.careLog()
        val babyId = care.createBaby(
            CreateBabyInput(nickname = "豆豆", birthdayEpochDay = LocalDate.of(2026, 1, 1).toEpochDay()),
        )
        val t0 = 1_700_000_000_000L
        // Simulate sync-introduced duplicate open sleeps bypassing CareLog mutex.
        f.records.upsert(
            RecordEntity(
                clientUuid = "sleep-stale",
                babyId = babyId,
                type = RecordType.SLEEP.key,
                timestamp = t0,
                endTimestamp = null,
                note = null,
                payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
                updatedAt = t0,
            ),
        )
        f.records.upsert(
            RecordEntity(
                clientUuid = "sleep-latest",
                babyId = babyId,
                type = RecordType.SLEEP.key,
                timestamp = t0 + 60 * 60_000L,
                endTimestamp = null,
                note = null,
                payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
                updatedAt = t0 + 60 * 60_000L,
            ),
        )

        val closedId = care.sleepUp(babyId, t0 + 2 * 60 * 60_000L)

        val all = f.records.listForBaby(babyId)
        assertThat(all).hasSize(2)
        val stale = all.single { it.clientUuid == "sleep-stale" }
        val latest = all.single { it.clientUuid == "sleep-latest" }
        // Ticket 06: older open retained (overlap pending); no synthetic end.
        assertThat(stale.endTimestamp).isNull()
        assertThat(stale.payloadJson).doesNotContain("\"anomaly_flag\":true")
        assertThat(latest.endTimestamp).isNull()
        assertThat(care.listWakeObservations("sleep-latest")).hasSize(1)
        assertThat(care.listWakeObservations("sleep-stale")).isEmpty()
        assertThat(closedId).isEqualTo(latest.id)
        // Latest is no longer open (has provisional wake); older open may still surface.
        assertThat(care.observeOpenSleep(babyId).first()?.clientUuid).isEqualTo("sleep-stale")
    }
    @Test
    fun equalStartOpenSleeps_wakeTargetsLexicalLatestWithoutClosingPeer() = runTest {
        val fakes = Fakes()
        fakes.clock.now = 400_000L
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        val start = 1_000L
        // Equal starts: latest by client UUID is wake target; peer stays open.
        fakes.records.upsert(openSleep("z-sleep", babyId, start))
        fakes.records.upsert(openSleep("a-sleep", babyId, start))

        care.sleepUp(babyId, at = 500_000L)

        val rows = fakes.records.listForBaby(babyId).associateBy(RecordEntity::clientUuid)
        assertThat(rows.getValue("a-sleep").endTimestamp).isNull()
        assertThat(rows.getValue("z-sleep").endTimestamp).isNull()
        assertThat(care.listWakeObservations("z-sleep")).hasSize(1)
        assertThat(care.listWakeObservations("a-sleep")).isEmpty()
    }

    @Test
    fun effectiveWakeRowsAreNotOpen_andHealDoesNotRewriteWakeTimes() = runTest {
        val fakes = Fakes()
        fakes.clock.now = 400_000L
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        // Defense: even with endTimestamp nulled, an effective WakeObservation marks
        // the sleep closed so multi-row heal cannot invent synthetic ends.
        fakes.records.upsert(
            RecordEntity(
                clientUuid = "closed-a",
                babyId = babyId,
                type = RecordType.SLEEP.key,
                timestamp = 1_000L,
                endTimestamp = null,
                note = null,
                payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
                updatedAt = 1_000L,
                effectiveWakeObservationClientUuid = "wake-a",
            ),
        )
        fakes.records.upsert(
            RecordEntity(
                clientUuid = "closed-b",
                babyId = babyId,
                type = RecordType.SLEEP.key,
                timestamp = 2_000L,
                endTimestamp = null,
                note = null,
                payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
                updatedAt = 2_000L,
                effectiveWakeObservationClientUuid = "wake-b",
            ),
        )
        // Dual-compat denormalized closed sleep (migration keeps endTimestamp).
        fakes.records.upsert(
            RecordEntity(
                clientUuid = "closed-denorm",
                babyId = babyId,
                type = RecordType.SLEEP.key,
                timestamp = 500L,
                endTimestamp = 1_500L,
                note = null,
                payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
                updatedAt = 1_500L,
                effectiveWakeObservationClientUuid = "wake-denorm",
            ),
        )
        fakes.wakeObservations.upsert(
            WakeObservationEntity(
                clientUuid = "wake-a",
                sleepRecordClientUuid = "closed-a",
                wakeTimestamp = 1_500L,
                observerMembershipId = "member-a",
                updatedAt = 1_500L,
            ),
        )
        fakes.wakeObservations.upsert(
            WakeObservationEntity(
                clientUuid = "wake-b",
                sleepRecordClientUuid = "closed-b",
                wakeTimestamp = 2_500L,
                observerMembershipId = "member-b",
                updatedAt = 2_500L,
            ),
        )

        assertThat(fakes.records.listOpenSleeps(babyId)).isEmpty()
        assertThat(fakes.records.findOpenSleep(babyId)).isNull()

        care.sleepDown(babyId, at = 3_000L)
        val opens = fakes.records.listOpenSleeps(babyId)
        assertThat(opens).hasSize(1)
        assertThat(opens.single().clientUuid).isNotIn(listOf("closed-a", "closed-b", "closed-denorm"))

        val byUuid = fakes.records.listForBaby(babyId).associateBy(RecordEntity::clientUuid)
        assertThat(byUuid.getValue("closed-a").endTimestamp).isNull()
        assertThat(byUuid.getValue("closed-a").effectiveWakeObservationClientUuid)
            .isEqualTo("wake-a")
        assertThat(byUuid.getValue("closed-b").endTimestamp).isNull()
        assertThat(byUuid.getValue("closed-b").effectiveWakeObservationClientUuid)
            .isEqualTo("wake-b")
        assertThat(byUuid.getValue("closed-denorm").endTimestamp).isEqualTo(1_500L)
        assertThat(byUuid.getValue("closed-denorm").payloadJson)
            .doesNotContain("\"anomaly_flag\":true")
    }
    @Test
    fun completeNursing_payload() = runTest {
        val care = Fakes().careLog()
        val babyId = care.createBaby(
            CreateBabyInput(nickname = "豆豆", birthdayEpochDay = LocalDate.of(2026, 1, 1).toEpochDay()),
        )
        val start = 1_700_000_100_000L
        val id = care.completeNursing(
            babyId = babyId,
            leftMin = 12,
            rightMin = 8,
            order = "LR",
            amountMl = 40,
            startedAt = start,
            endedAt = start + 20 * 60_000L,
        )
        val rec = care.getRecord(id)!!
        assertThat(rec.type).isEqualTo(RecordType.NURSING)
        assertThat(rec.payloadJson).contains("\"left_min\":12")
        assertThat(rec.payloadJson).contains("\"right_min\":8")
        assertThat(rec.payloadJson).contains("\"amount_ml\":40")
        val day = java.time.Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
        assertThat(care.daySummary(babyId, day, zone).nursingMinutes).isEqualTo(20)
        assertThat(care.daySummary(babyId, day, zone).feedMl).isEqualTo(40)
    }
    @Test
    fun completeNursing_recordModeControlsStoredTimestamp() = runTest {
        val care = Fakes().careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        val start = 1_700_000_100_000L
        val end = start + 20 * 60_000L

        val startRecord = care.getRecord(
            care.completeNursing(
                babyId = babyId,
                leftMin = 12,
                rightMin = 8,
                order = "LR",
                startedAt = start,
                endedAt = end,
                recordMode = "start",
            ),
        )!!
        val endRecord = care.getRecord(
            care.completeNursing(
                babyId = babyId,
                leftMin = 12,
                rightMin = 8,
                order = "LR",
                startedAt = start,
                endedAt = end,
                recordMode = "end",
            ),
        )!!

        assertThat(startRecord.timestamp).isEqualTo(start)
        assertThat(startRecord.endTimestamp).isEqualTo(end)
        assertThat(startRecord.payloadJson).contains("\"record_mode\":\"start\"")
        assertThat(endRecord.timestamp).isEqualTo(end)
        assertThat(endRecord.endTimestamp).isNull()
        assertThat(endRecord.payloadJson).contains("\"record_mode\":\"end\"")
    }
    @Test
    fun completeNursing_replayWithStableCompletionUuidIsIdempotent() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        val completionUuid = "8ba9c421-9a85-4e37-94fe-e15e8f4a40f6"

        val first = care.completeNursing(
            babyId = babyId,
            leftMin = 3,
            rightMin = 2,
            order = "LR",
            amountMl = 40,
            startedAt = 1_000L,
            endedAt = 301_000L,
            completionClientUuid = completionUuid,
        )
        val replay = care.completeNursing(
            babyId = babyId,
            leftMin = 99,
            rightMin = 0,
            order = "L",
            amountMl = 90,
            startedAt = 1_000L,
            endedAt = 999_000L,
            completionClientUuid = completionUuid,
        )

        assertThat(replay).isEqualTo(first)
        assertThat(fakes.records.listForBaby(babyId)).hasSize(1)
        assertThat(care.getRecord(first)!!.payloadJson).contains("\"left_min\":3")
        assertThat(care.getRecord(first)!!.payloadJson).contains("\"amount_ml\":40")
    }
    @Test
    fun completeNursing_replayWithSoftDeletedCompletionUuidFailsClosed() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        val completionUuid = "03ac5d03-5815-4777-a4ae-17097cb9cad9"
        val deletedId = care.completeNursing(
            babyId = babyId,
            leftMin = 3,
            rightMin = 2,
            order = "LR",
            startedAt = 1_000L,
            endedAt = 301_000L,
            completionClientUuid = completionUuid,
        )
        care.deleteRecord(deletedId)

        val failure = runCatching {
            care.completeNursing(
                babyId = babyId,
                leftMin = 4,
                rightMin = 1,
                order = "LR",
                startedAt = 1_000L,
                endedAt = 301_000L,
                completionClientUuid = completionUuid,
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure).hasMessageThat().isEqualTo("这次计时记录已删除，请重试或改记")
        assertThat(fakes.records.listAllIncludingDeleted()).hasSize(1)
        assertThat(fakes.records.getIncludingDeleted(deletedId)!!.deletedAt).isNotNull()
    }
    @Test
    fun addRecord_replayWithComposerClientUuidKeepsOneOriginalFact() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        val clientUuid = "composer-add-record-identity"

        val first = care.addRecord(
            babyId = babyId,
            type = RecordType.DIARY,
            timestamp = 1_000L,
            note = "第一次确认",
            payloadJson = """{"body":"只应写入一次"}""",
            clientUuid = clientUuid,
        )
        val replay = care.addRecord(
            babyId = babyId,
            type = RecordType.DIARY,
            timestamp = 2_000L,
            note = "进程重建后不应覆盖",
            payloadJson = """{"body":"不应覆盖"}""",
            clientUuid = clientUuid,
        )

        assertThat(replay).isEqualTo(first)
        assertThat(fakes.records.listForBaby(babyId)).hasSize(1)
        assertThat(care.getRecord(first)!!.timestamp).isEqualTo(1_000L)
        assertThat(care.getRecord(first)!!.note).isEqualTo("第一次确认")
    }
    @Test
    fun confirmSleep_replayWithComposerClientUuidKeepsOneSleepFact() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        val now = System.currentTimeMillis()
        val clientUuid = "composer-confirm-sleep-identity"

        val first = care.confirmSleep(
            babyId = babyId,
            expectedOpenSleepId = null,
            timestamp = now - 1_000L,
            endTimestamp = null,
            note = "睡下",
            payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
            nowMillis = now,
            clientUuid = clientUuid,
        )
        val replay = care.confirmSleep(
            babyId = babyId,
            expectedOpenSleepId = null,
            timestamp = now - 1_000L,
            endTimestamp = null,
            note = "睡下",
            payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
            nowMillis = now,
            clientUuid = clientUuid,
        )

        assertThat(replay).isEqualTo(first)
        assertThat(fakes.records.listForBaby(babyId)).hasSize(1)
        assertThat(care.getRecord(first)!!.clientUuid).isEqualTo(clientUuid)
    }
    @Test
    fun confirmWake_replayAfterTheOpenSleepWasClosedIsIdempotent() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        val now = System.currentTimeMillis()
        val startedAt = now - 60_000L
        val endedAt = now - 1_000L
        val payload = """{"is_nap":false,"anomaly_flag":false}"""
        val openId = care.confirmSleep(
            babyId = babyId,
            expectedOpenSleepId = null,
            timestamp = startedAt,
            endTimestamp = null,
            note = "睡下",
            payloadJson = payload,
            nowMillis = now,
            clientUuid = "open-sleep-identity",
        )

        val first = care.confirmSleep(
            babyId = babyId,
            expectedOpenSleepId = openId,
            timestamp = startedAt,
            endTimestamp = endedAt,
            note = "醒来",
            payloadJson = payload,
            nowMillis = now,
            clientUuid = "composer-wake-operation-identity",
        )
        val replay = care.confirmSleep(
            babyId = babyId,
            expectedOpenSleepId = openId,
            timestamp = startedAt,
            endTimestamp = endedAt,
            note = "醒来",
            payloadJson = payload,
            nowMillis = now,
            clientUuid = "composer-wake-operation-identity",
        )

        assertThat(replay).isEqualTo(first)
        assertThat(fakes.records.listForBaby(babyId)).hasSize(1)
        // Projected end from WakeObservation; Sleep note stays the open-start note.
        assertThat(care.getRecord(openId)!!.endTimestamp).isEqualTo(endedAt)
        assertThat(fakes.records.get(openId)!!.endTimestamp).isNull()
        assertThat(care.getRecord(openId)!!.note).isEqualTo("睡下")
        val wakes = care.listWakeObservations(care.getRecord(openId)!!.clientUuid)
        assertThat(wakes).hasSize(1)
        assertThat(wakes.single().clientUuid).isEqualTo("composer-wake-operation-identity")
        assertThat(wakes.single().note).isEqualTo("醒来")
        assertThat(wakes.single().wakeTimestamp).isEqualTo(endedAt)
    }
    @Test
    fun convertRecord_replayWithComposerClientUuidKeepsOnePlan() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        val now = System.currentTimeMillis()
        val recordId = care.addRecord(
            babyId = babyId,
            type = RecordType.BATH,
            timestamp = now - 60_000L,
            nowMillis = now,
        )
        val clientUuid = "composer-convert-plan-identity"

        val first = care.convertRecordToCarePlan(
            recordId = recordId,
            scheduledAt = now + 60_000L,
            nowMillis = now,
            clientUuid = clientUuid,
        )
        val replay = care.convertRecordToCarePlan(
            recordId = recordId,
            scheduledAt = now + 60_000L,
            nowMillis = now,
            clientUuid = clientUuid,
        )

        assertThat(replay).isEqualTo(first)
        assertThat(fakes.carePlans.listAllIncludingDeleted()).hasSize(1)
        assertThat(care.getCarePlan(first)!!.clientUuid).isEqualTo(clientUuid)
        assertThat(fakes.records.getIncludingDeleted(recordId)!!.deletedAt).isNotNull()
    }
    @Test
    fun completeNursing_rejectsUntrustedOrderBeforePersistence() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        val maliciousOrder = """LR","injected":true,"order":""""

        val failure = runCatching {
            care.completeNursing(
                babyId = babyId,
                leftMin = 1,
                rightMin = 2,
                order = maliciousOrder,
                startedAt = 1_000L,
                endedAt = 3_000L,
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(fakes.records.listForBaby(babyId)).isEmpty()
    }
    @Test
    fun pumpExpress_notInFeedMl() = runTest {
        val care = Fakes().careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        val day = LocalDate.of(2026, 7, 22)
        val ts = day.atStartOfDay(zone).toInstant().toEpochMilli() + 1000
        care.addRecord(babyId, RecordType.PUMP_EXPRESS, timestamp = ts, payloadJson = """{"amount_ml":90}""")
        care.addRecord(babyId, RecordType.PUMPED_FEED, timestamp = ts + 1, payloadJson = """{"amount_ml":60}""")
        val s = care.daySummary(babyId, day, zone)
        assertThat(s.feedMl).isEqualTo(60)
        assertThat(s.pumpedFeedMl).isEqualTo(60)
    }
    @Test
    fun searchAndWeekSummary() = runTest {
        val care = Fakes().careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        val day = LocalDate.now(zone)
        val ts = day.atStartOfDay(zone).toInstant().toEpochMilli() + 1000
        care.addRecord(babyId, RecordType.BATH, timestamp = ts, note = "布洛芬 2.5ml")
        care.addRecord(babyId, RecordType.DIARY, timestamp = ts + 1, payloadJson = """{"body":"今天发烧了"}""")
        care.addRecord(
            babyId,
            RecordType.FORMULA,
            timestamp = ts + 2,
            payloadJson = """{"amount_ml":120}""",
        )
        care.addRecord(
            babyId,
            RecordType.WEIGHT,
            timestamp = ts + 3,
            payloadJson = """{"value":6350,"unit":"g"}""",
        )
        care.addRecord(
            babyId,
            RecordType.PEE,
            timestamp = ts + 4,
            payloadJson = """{"pee_amount":3}""",
        )
        care.addRecord(
            babyId,
            RecordType.NURSING,
            timestamp = ts + 5,
            payloadJson =
                """{"left_min":10,"right_min":0,"order":"L","record_mode":"end"}""",
        )
        assertThat(care.search(babyId, "布洛芬")).hasSize(1)
        assertThat(care.search(babyId, "发烧")).hasSize(1)
        assertThat(care.search(babyId, "配方奶").single().type).isEqualTo(RecordType.FORMULA)
        assertThat(care.search(babyId, "120ml").single().type).isEqualTo(RecordType.FORMULA)
        assertThat(care.search(babyId, "6.35kg").single().type).isEqualTo(RecordType.WEIGHT)
        assertThat(care.search(babyId, "6.35").single().type).isEqualTo(RecordType.WEIGHT)
        assertThat(care.search(babyId, "尿量大").single().type).isEqualTo(RecordType.PEE)
        assertThat(care.search(babyId, "左10分").single().type).isEqualTo(RecordType.NURSING)
        assertThat(care.search(babyId, "amount_ml")).isEmpty()
        assertThat(care.search(babyId, "不存在的词xyz")).isEmpty()
        assertThat(care.search(babyId, "   ")).isEmpty()
        // Short Latin mid-alias hits must not return whole type classes.
        assertThat(care.search(babyId, "e")).isEmpty()
        assertThat(care.search(babyId, "a")).isEmpty()
        assertThat(care.search(babyId, "pee").single().type).isEqualTo(RecordType.PEE)
        assertThat(care.search(babyId, "formula").single().type).isEqualTo(RecordType.FORMULA)
        val id = care.addRecord(babyId, RecordType.BATH, timestamp = ts + 3, note = "临时")
        care.deleteRecord(id)
        assertThat(care.search(babyId, "临时")).isEmpty()
        val b2 = care.addBaby(CreateBabyInput(nickname = "B", birthdayEpochDay = 2))
        care.addRecord(b2, RecordType.BATH, timestamp = ts, note = "布洛芬")
        assertThat(care.search(babyId, "布洛芬")).hasSize(1)
        val ws = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val summary = care.weekSummary(babyId, ws, zone)
        assertThat(summary.totalFeedMl).isEqualTo(120)
        val widget = care.recentCareSummary(babyId, zone)
        assertThat(widget.feedMl).isEqualTo(120)
        assertThat(widget.babyName).isEqualTo("豆豆")
    }
    @Test
    fun searchProjectsMoreSleepsThanTheExplicitRootCap() = runTest {
        val care = Fakes().careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        val base = 1_700_000_000_000L
        val count = 65
        repeat(count) { index ->
            val start = base + index * 2L
            care.addRecord(
                babyId = babyId,
                type = RecordType.SLEEP,
                timestamp = start,
                endTimestamp = start + 1L,
                payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
                nowMillis = base + count * 2L,
            )
        }

        val hits = care.search(babyId, "睡")

        assertThat(hits).hasSize(count)
        assertThat(hits.map { it.type }.distinct()).containsExactly(RecordType.SLEEP)
    }

    @Test
    fun searchTreatsSqlLikeMetacharactersAsLiterals() = runTest {
        val care = Fakes().careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        val day = LocalDate.now(zone)
        val ts = day.atStartOfDay(zone).toInstant().toEpochMilli() + 1000
        care.addRecord(
            babyId,
            RecordType.BATH,
            timestamp = ts,
            note = "浓度 100% 达标",
        )
        care.addRecord(
            babyId,
            RecordType.BATH,
            timestamp = ts + 1,
            note = "code a_b path",
        )
        care.addRecord(
            babyId,
            RecordType.BATH,
            timestamp = ts + 2,
            note = "path\\file backup",
        )
        // Control: plain substring without metacharacters.
        care.addRecord(
            babyId,
            RecordType.BATH,
            timestamp = ts + 3,
            note = "100ml plain",
        )

        assertThat(care.search(babyId, "100%").single().note).isEqualTo("浓度 100% 达标")
        // "%" as wildcard would have matched "100ml plain" too.
        assertThat(care.search(babyId, "100%").map { it.note }).doesNotContain("100ml plain")

        assertThat(care.search(babyId, "a_b").single().note).isEqualTo("code a_b path")
        // "_" must remain literal; wildcard semantics would also match "axb".
        care.addRecord(babyId, RecordType.BATH, timestamp = ts + 4, note = "axb only")
        assertThat(care.search(babyId, "a_b").map { it.note }).containsExactly("code a_b path")

        assertThat(care.search(babyId, "path\\file").single().note)
            .isEqualTo("path\\file backup")
        assertThat(care.search(babyId, "pathfile")).isEmpty()
    }
    @Test
    fun recentSummaryKeepsLatestFactAcrossDayBoundary() = runTest {
        val care = Fakes().careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        val today = LocalDate.now(zone)
        val yesterdayAt = today.minusDays(1)
            .atTime(23, 45)
            .toInstant(zone)
            .toEpochMilli()
        care.addRecord(
            babyId = babyId,
            type = RecordType.FORMULA,
            timestamp = yesterdayAt,
            payloadJson = """{"amount_ml":90}""",
        )

        val widget = care.recentCareSummary(babyId, zone)

        assertThat(widget.feedMl).isEqualTo(0)
        assertThat(widget.lastLabel).contains("配方奶")
        assertThat(widget.lastLabel).contains("90ml")
        assertThat(widget.lastLabel).contains("23:45")
    }
    @Test
    fun recordManagePermissionMatchesMembershipAcl() = runTest {
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
        val now = 4_000_000L
        val recordId = care.addRecord(
            babyId = babyId,
            type = RecordType.PEE,
            timestamp = now,
            payloadJson = """{"pee_amount":1}""",
        )
        val owned = care.getRecord(recordId)!!
        assertThat(owned.createdByMembershipId).isEqualTo("m-creator")
        assertThat(care.canManageRecord(owned)).isTrue()

        // Foreign ordinary member cannot update/delete/convert.
        val foreignSync = RecordingSyncPort(
            membershipId = "m-other",
            role = com.lezi.babylog.sync.session.FamilyRole.Member,
            familyId = "fam-1",
            deviceId = "dev-2",
        )
        val foreignCare = Fakes(foreignSync).let { f ->
            f.wireTransactionalSnapshots()
            f.records.upsert(fakes.records.get(recordId)!!)
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
        val foreignView = foreignCare.getRecord(recordId)!!
        assertThat(foreignCare.canManageRecord(foreignView)).isFalse()
        // Timeline capabilities and mutation share the pure membership rule.
        assertThat(
            canManageCreatorOwnedFamilyEntity(
                creatorMembershipId = foreignView.createdByMembershipId,
                actorMembershipId = "m-other",
                actorIsAdmin = false,
            ),
        ).isFalse()
        assertThat(
            runCatching {
                foreignCare.updateRecord(
                    id = recordId,
                    timestamp = now,
                    endTimestamp = null,
                    note = "篡改",
                    payloadJson = """{"pee_amount":2}""",
                    nowMillis = now,
                )
            }.exceptionOrNull(),
        ).isInstanceOf(RecordPermissionException::class.java)
        assertThat(
            runCatching { foreignCare.deleteRecord(recordId) }.exceptionOrNull(),
        ).isInstanceOf(RecordPermissionException::class.java)
        assertThat(
            runCatching {
                foreignCare.convertRecordToCarePlan(
                    recordId = recordId,
                    scheduledAt = now + 60_000L,
                    nowMillis = now,
                )
            }.exceptionOrNull(),
        ).isInstanceOf(RecordPermissionException::class.java)
        // Unauthorized paths leave the fact intact.
        assertThat(foreignCare.getRecord(recordId)!!.note).isNull()
        assertThat(foreignCare.getRecord(recordId)!!.deletedAt).isNull()

        // Owner may manage others' records.
        val adminSync = RecordingSyncPort(
            membershipId = "m-admin",
            role = com.lezi.babylog.sync.session.FamilyRole.Owner,
            familyId = "fam-1",
            deviceId = "dev-admin",
        )
        val adminCare = Fakes(adminSync).let { f ->
            f.wireTransactionalSnapshots()
            f.records.upsert(fakes.records.get(recordId)!!)
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
        assertThat(adminCare.canManageRecord(adminCare.getRecord(recordId)!!)).isTrue()
        adminCare.updateRecord(
            id = recordId,
            timestamp = now,
            endTimestamp = null,
            note = "管理员接管",
            payloadJson = """{"pee_amount":1}""",
            nowMillis = now,
        )
        assertThat(adminCare.getRecord(recordId)!!.note).isEqualTo("管理员接管")

        // Offline dual-empty membership still manages local records.
        val offline = Fakes()
        offline.wireTransactionalSnapshots()
        val offlineCare = offline.careLog()
        val offlineBaby = offlineCare.createBaby(
            CreateBabyInput(nickname = "离线宝", birthdayEpochDay = 1),
        )
        val offlineId = offlineCare.addRecord(
            babyId = offlineBaby,
            type = RecordType.PEE,
            timestamp = now,
            payloadJson = """{"pee_amount":1}""",
        )
        val offlineRecord = offlineCare.getRecord(offlineId)!!
        assertThat(offlineRecord.createdByMembershipId).isEmpty()
        assertThat(offlineCare.canManageRecord(offlineRecord)).isTrue()
        offlineCare.updateRecord(
            id = offlineId,
            timestamp = now,
            endTimestamp = null,
            note = "离线可改",
            payloadJson = """{"pee_amount":1}""",
            nowMillis = now,
        )
        assertThat(offlineCare.getRecord(offlineId)!!.note).isEqualTo("离线可改")
        assertThat(offlineCare.deleteRecord(offlineId)).isTrue()
    }

    /**
     * Family wake is a baby-level fact: any member may record a WakeObservation on
     * another's open SleepStart. SleepStart fields are never rewritten; B1 closer
     * privilege is replaced by observer self-edit of the WakeObservation.
     */
    @Test
    fun foreignMemberWakeOfOpenSleepCreatesObservationWithoutRewritingSleepStart() = runTest {
        val momSync = RecordingSyncPort(
            membershipId = "m-mom",
            role = com.lezi.babylog.sync.session.FamilyRole.Member,
            familyId = "fam-1",
            deviceId = "dev-mom",
        )
        val momFakes = Fakes(momSync)
        momFakes.wireTransactionalSnapshots()
        val momCare = momFakes.careLog()
        val babyId = momFakes.seedFamilyAuthorityBaby()
        val wakeAt = System.currentTimeMillis() - 1_000L
        val startedAt = wakeAt - 90 * 60_000L
        val payload = """{"is_nap":false,"anomaly_flag":false}"""
        val openId = momCare.confirmSleep(
            babyId = babyId,
            expectedOpenSleepId = null,
            timestamp = startedAt,
            endTimestamp = null,
            note = "妈妈记下睡",
            payloadJson = payload,
            nowMillis = startedAt + 1_000L,
            clientUuid = "sleep-open-mom",
        )
        val open = momCare.getRecord(openId)!!
        assertThat(open.createdByMembershipId).isEqualTo("m-mom")
        assertThat(open.endTimestamp).isNull()

        val dadSync = RecordingSyncPort(
            membershipId = "m-dad",
            role = com.lezi.babylog.sync.session.FamilyRole.Member,
            familyId = "fam-1",
            deviceId = "dev-dad",
        )
        val dadFakes = Fakes(dadSync)
        dadFakes.wireTransactionalSnapshots()
        dadFakes.records.upsert(momFakes.records.get(openId)!!)
        dadFakes.babies.upsert(momFakes.babies.get(babyId)!!)
        val dadCare = dadFakes.careLog()
        val openOnDad = dadCare.getRecord(openId)!!
        assertThat(dadCare.canManageRecord(openOnDad)).isFalse()
        assertThat(dadCare.observeOpenSleep(babyId).first()!!.id).isEqualTo(openId)

        // Client may try to rewrite sleep-down time / is_nap; wake path ignores them.
        val wakeResultId = dadCare.confirmSleep(
            babyId = babyId,
            expectedOpenSleepId = openId,
            timestamp = startedAt + 60_000L,
            endTimestamp = wakeAt,
            note = "爸爸记醒来",
            payloadJson = """{"is_nap":true,"anomaly_flag":false}""",
            nowMillis = wakeAt,
            clientUuid = "composer-wake-dad-op",
        )

        assertThat(wakeResultId).isEqualTo(openId)
        assertThat(dadCare.observeOpenSleep(babyId).first()).isNull()
        val sleepEntity = dadFakes.records.get(openId)!!
        assertThat(sleepEntity.endTimestamp).isNull()
        assertThat(sleepEntity.timestamp).isEqualTo(startedAt)
        assertThat(sleepEntity.createdByMembershipId).isEqualTo("m-mom")
        assertThat(sleepEntity.note).isEqualTo("妈妈记下睡")
        assertThat(sleepEntity.payloadJson).contains("\"is_nap\":false")
        val wakes = dadCare.listWakeObservations(sleepEntity.clientUuid)
        assertThat(wakes).hasSize(1)
        assertThat(wakes.single().note).isEqualTo("爸爸记醒来")
        assertThat(wakes.single().wakeTimestamp).isEqualTo(wakeAt)
        assertThat(wakes.single().observerMembershipId).isEqualTo("m-dad")
        // Sleep manage stays author-only; observer may edit only their own wake.
        val closed = dadCare.getRecord(openId)!!
        assertThat(dadCare.canManageRecord(closed)).isFalse()
        assertThat(dadCare.canEditRecord(closed)).isFalse()
        assertThat(dadCare.canDeleteRecord(closed)).isFalse()
        assertThat(dadCare.canEditWakeObservation(wakes.single().clientUuid)).isTrue()

        val correctedEnd = wakeAt + 5 * 60_000L
        dadCare.updateWakeObservation(
            clientUuid = wakes.single().clientUuid,
            wakeTimestamp = correctedEnd,
            note = "醒来纠错",
            nowMillis = correctedEnd,
        )
        assertThat(dadCare.getWakeObservation(wakes.single().clientUuid)!!.wakeTimestamp)
            .isEqualTo(correctedEnd)
        assertThat(dadCare.getWakeObservation(wakes.single().clientUuid)!!.note)
            .isEqualTo("醒来纠错")
        // SleepStart fields untouched.
        assertThat(dadFakes.records.get(openId)!!.timestamp).isEqualTo(startedAt)

        assertThat(
            runCatching { dadCare.deleteRecord(openId) }.exceptionOrNull(),
        ).isInstanceOf(RecordPermissionException::class.java)
    }

    @Test
    fun confirmSleep_managerMovesSleepStartThenWritesWake() = runTest {
        val momSync = RecordingSyncPort(
            membershipId = "m-mom",
            role = com.lezi.babylog.sync.session.FamilyRole.Member,
            familyId = "fam-1",
            deviceId = "dev-mom",
        )
        val fakes = Fakes(momSync)
        fakes.wireTransactionalSnapshots()
        val care = fakes.careLog()
        val babyId = fakes.seedFamilyAuthorityBaby()
        val wakeAt = System.currentTimeMillis() - 1_000L
        val startedAt = wakeAt - 90 * 60_000L
        val movedStart = startedAt + 15 * 60_000L
        val payload = """{"is_nap":false,"anomaly_flag":false}"""
        val openId = care.confirmSleep(
            babyId = babyId,
            expectedOpenSleepId = null,
            timestamp = startedAt,
            endTimestamp = null,
            note = "妈妈记下睡",
            payloadJson = payload,
            nowMillis = startedAt + 1_000L,
            clientUuid = "sleep-open-mom-move",
        )
        val open = care.getRecord(openId)!!
        assertThat(care.canManageRecord(open)).isTrue()

        val wakeResultId = care.confirmSleep(
            babyId = babyId,
            expectedOpenSleepId = openId,
            timestamp = movedStart,
            endTimestamp = wakeAt,
            note = "醒来并改睡下",
            payloadJson = payload,
            nowMillis = wakeAt,
            clientUuid = "composer-wake-mom-move",
        )

        assertThat(wakeResultId).isEqualTo(openId)
        val projected = care.getRecord(openId)!!
        assertThat(projected.timestamp).isEqualTo(movedStart)
        assertThat(projected.endTimestamp).isEqualTo(wakeAt)
        assertThat(projected.note).isEqualTo("妈妈记下睡")
        val stored = fakes.records.get(openId)!!
        assertThat(stored.timestamp).isEqualTo(movedStart)
        assertThat(stored.endTimestamp).isNull()
        val wakes = care.listWakeObservations(projected.clientUuid)
        assertThat(wakes).hasSize(1)
        assertThat(wakes.single().wakeTimestamp).isEqualTo(wakeAt)
        assertThat(wakes.single().wakeTimestamp).isGreaterThan(stored.timestamp)
        assertThat(wakes.single().note).isEqualTo("醒来并改睡下")
        assertThat(care.observeOpenSleep(babyId).first()).isNull()
    }

    @Test
    fun foreignMemberSleepUpCreatesOwnWakeWithoutSleepManage() = runTest {
        val momSync = RecordingSyncPort(
            membershipId = "m-mom",
            role = com.lezi.babylog.sync.session.FamilyRole.Member,
            familyId = "fam-1",
            deviceId = "dev-mom",
        )
        val momFakes = Fakes(momSync)
        momFakes.wireTransactionalSnapshots()
        val momCare = momFakes.careLog()
        val babyId = momFakes.seedFamilyAuthorityBaby()
        val startedAt = System.currentTimeMillis() - 2 * 60 * 60_000L
        val openId = momCare.confirmSleep(
            babyId = babyId,
            expectedOpenSleepId = null,
            timestamp = startedAt,
            endTimestamp = null,
            note = null,
            payloadJson = """{"is_nap":true,"anomaly_flag":false}""",
            nowMillis = startedAt + 1_000L,
            clientUuid = "sleep-open-mom-up",
        )
        val dadSync = RecordingSyncPort(
            membershipId = "m-dad",
            role = com.lezi.babylog.sync.session.FamilyRole.Member,
            familyId = "fam-1",
            deviceId = "dev-dad",
        )
        val dadFakes = Fakes(dadSync)
        dadFakes.wireTransactionalSnapshots()
        dadFakes.records.upsert(momFakes.records.get(openId)!!)
        dadFakes.babies.upsert(momFakes.babies.get(babyId)!!)
        val dadCare = dadFakes.careLog()
        val wakeAt = startedAt + 90 * 60_000L
        val closedId = dadCare.sleepUp(babyId, at = wakeAt, nowMillis = wakeAt)
        assertThat(closedId).isEqualTo(openId)
        val closed = dadCare.getRecord(openId)!!
        assertThat(closed.endTimestamp).isEqualTo(wakeAt) // projected
        assertThat(dadFakes.records.get(openId)!!.endTimestamp).isNull()
        assertThat(closed.timestamp).isEqualTo(startedAt)
        assertThat(closed.payloadJson).contains("\"is_nap\":true")
        assertThat(dadCare.canManageRecord(closed)).isFalse()
        assertThat(dadCare.canEditRecord(closed)).isFalse()
        assertThat(dadCare.canDeleteRecord(closed)).isFalse()
        val wake = dadCare.listWakeObservations(closed.clientUuid).single()
        assertThat(dadCare.canEditWakeObservation(wake.clientUuid)).isTrue()
    }

    @Test
    fun cleanupFailureDoesNotMisreportTheCommittedRecordDeleteAsReplayable() = runTest {
        val sync = RecordingSyncPort().apply {
            mediaCleanupFailures += IllegalStateException("gc retry required")
        }
        val fakes = Fakes(sync)
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val path = "record/retry.jpg"
        val recordId = care.addRecord(
            babyId = babyId,
            type = RecordType.DIARY,
            timestamp = 1_000L,
            payloadJson = """{"body":"日记"}""",
            photoLocalPaths = listOf(path),
        )

        care.deleteRecord(recordId)

        assertThat(fakes.records.getIncludingDeleted(recordId)?.deletedAt).isNotNull()
        assertThat(fakes.media.listForRecord(recordId).single().localUri).isEqualTo(path)
        assertThat(sync.requests).isGreaterThan(0)
    }
    @Test
    fun completeNursingWithCarePlanIdCompletesPlanIdempotently() = runTest {
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
        val completionUuid = "nursing-complete-uuid-1"
        val first = care.completeNursing(
            babyId = babyId,
            leftMin = 8,
            rightMin = 4,
            order = "LR",
            startedAt = now - 12 * 60_000L,
            endedAt = now,
            completionClientUuid = completionUuid,
            carePlanId = planId,
        )
        assertThat(care.getCarePlan(planId)!!.status).isEqualTo(CarePlanStatus.COMPLETED)
        assertThat(care.getCarePlan(planId)!!.fulfilledRecordClientUuid).isEqualTo(completionUuid)
        // Replay same completion uuid: same record, plan stays completed once.
        val replay = care.completeNursing(
            babyId = babyId,
            leftMin = 8,
            rightMin = 4,
            order = "LR",
            startedAt = now - 12 * 60_000L,
            endedAt = now,
            completionClientUuid = completionUuid,
            carePlanId = planId,
        )
        assertThat(replay).isEqualTo(first)
        assertThat(
            fakes.records.listAllIncludingDeleted()
                .count { it.type == RecordType.NURSING.key && it.deletedAt == null },
        ).isEqualTo(1)
        // Cancel path: clearing timer without completeNursing leaves other plans pending.
        val plan2 = care.createCarePlan(
            babyId = babyId,
            type = RecordType.NURSING,
            scheduledAt = now + 120_000L,
            payloadJson =
                """{"left_min":0,"right_min":0,"order":"LR","record_mode":"end"}""",
            nowMillis = now + 1,
        )
        assertThat(care.getCarePlan(plan2)!!.status).isEqualTo(CarePlanStatus.PENDING)
    }
    @Test
    fun completeNursingFailsClosedWhenPlanAlreadyCompletedByOtherRecord() = runTest {
        val fakes = Fakes()
        fakes.wireTransactionalSnapshots()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = 86_000_000L
        val planPhotos = listOf("plans/bound-a.jpg", "plans/bound-b.jpg")
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.NURSING,
            scheduledAt = now + 60_000L,
            payloadJson =
                """{"left_min":0,"right_min":0,"order":"LR","record_mode":"end"}""",
            photoLocalPaths = planPhotos,
            nowMillis = now,
        )
        val firstUuid = "timer-first-completion"
        val firstId = care.completeNursing(
            babyId = babyId,
            leftMin = 6,
            rightMin = 2,
            order = "LR",
            startedAt = now - 9 * 60_000L,
            endedAt = now,
            completionClientUuid = firstUuid,
            carePlanId = planId,
            nowMillis = now,
        )
        val planAfterFirst = care.getCarePlan(planId)!!
        assertThat(planAfterFirst.status).isEqualTo(CarePlanStatus.COMPLETED)
        assertThat(planAfterFirst.fulfilledRecordClientUuid).isEqualTo(firstUuid)
        val mediaAfterFirst = fakes.media.listAllIncludingDeleted()
        val recordsAfterFirst = fakes.records.listAllIncludingDeleted()
        val candidatesAfterFirst = fakes.fulfillmentCandidates.listAllIncludingDeleted()

        val error = runCatching {
            care.completeNursing(
                babyId = babyId,
                leftMin = 4,
                rightMin = 0,
                order = "L",
                startedAt = now - 5 * 60_000L,
                endedAt = now + 1L,
                completionClientUuid = "timer-second-completion",
                carePlanId = planId,
                nowMillis = now + 1L,
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error).hasMessageThat().contains("该护理计划已由其他记录完成")
        // Fail closed: no leftover second record/media; plan binding unchanged.
        assertThat(fakes.records.listAllIncludingDeleted()).containsExactlyElementsIn(recordsAfterFirst)
        assertThat(fakes.media.listAllIncludingDeleted()).containsExactlyElementsIn(mediaAfterFirst)
        assertThat(fakes.fulfillmentCandidates.listAllIncludingDeleted())
            .containsExactlyElementsIn(candidatesAfterFirst)
        assertThat(care.getCarePlan(planId)).isEqualTo(planAfterFirst)
        assertThat(care.listRecordPhotoPaths(firstId)).containsExactlyElementsIn(planPhotos).inOrder()
        assertThat(care.listCarePlanPhotoPaths(planId)).containsExactlyElementsIn(planPhotos).inOrder()
    }
    @Test
    fun convertRecordToCarePlanProjectsSystemCalendarByDefault() = runTest {
        val fakes = Fakes()
        fakes.systemCalendar.permission = true
        fakes.settings.setSystemCalendarEnabled(true)
        fakes.settings.setSystemCalendarId("cal-1")
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = System.currentTimeMillis()
        val recordId = care.addRecord(
            babyId = babyId,
            type = RecordType.FORMULA,
            timestamp = now - 60_000L,
            payloadJson = """{"amount_ml":80}""",
            note = "转计划",
        )
        fakes.systemCalendar.upserts.clear()
        val planId = care.convertRecordToCarePlan(
            recordId = recordId,
            scheduledAt = now + 180_000L,
            note = "转计划",
            nowMillis = now,
        )
        assertThat(fakes.systemCalendar.upserts).isNotEmpty()
        val plan = care.getCarePlan(planId)!!
        val map = parseSystemCalendarEventMap(
            fakes.settings.settings.first().systemCalendarEventMapJson,
        )
        assertThat(map).containsKey(plan.clientUuid)
        assertThat(fakes.reminders.scheduledCarePlanIds).doesNotContain(planId)
    }
    @Test
    fun convertRecordToCarePlanRejectsNonFutureAndDoesNotStartStatefulActions() = runTest {
        val fakes = Fakes()
        fakes.wireTransactionalSnapshots()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = 32_000_000L
        val nursingId = care.addRecord(
            babyId = babyId,
            type = RecordType.NURSING,
            timestamp = now - 30_000L,
            payloadJson =
                """{"left_min":5,"right_min":4,"order":"LR","record_mode":"end"}""",
        )
        val past = runCatching {
            care.convertRecordToCarePlan(
                recordId = nursingId,
                scheduledAt = now,
                nowMillis = now,
            )
        }.exceptionOrNull()
        assertThat(past).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(fakes.records.get(nursingId)!!.deletedAt).isNull()

        // Sleep open interval convert: soft-deletes open sleep, never leaves a new open interval.
        val openSleepId = care.confirmSleep(
            babyId = babyId,
            expectedOpenSleepId = null,
            timestamp = now - 5_000L,
            endTimestamp = null,
            note = null,
            payloadJson = """{"is_nap":true,"anomaly_flag":false}""",
        )
        assertThat(fakes.records.findOpenSleep(babyId)?.id).isEqualTo(openSleepId)
        val planId = care.convertRecordToCarePlan(
            recordId = openSleepId,
            scheduledAt = now + 90_000L,
            payloadJson = """{"is_nap":true,"anomaly_flag":false}""",
            nowMillis = now,
        )
        assertThat(care.getCarePlan(planId)!!.type).isEqualTo(RecordType.SLEEP)
        assertThat(care.getCarePlan(planId)!!.sourceRecordClientUuid)
            .isEqualTo(fakes.records.getIncludingDeleted(openSleepId)!!.clientUuid)
        assertThat(fakes.records.findOpenSleep(babyId)).isNull()
        assertThat(
            fakes.records.listAllIncludingDeleted()
                .none { it.type == RecordType.SLEEP.key && it.deletedAt == null },
        ).isTrue()

        // Ordinary updateRecord cannot sneak a future timestamp past convert.
        val formulaId = care.addRecord(
            babyId = babyId,
            type = RecordType.FORMULA,
            timestamp = now - 1_000L,
            payloadJson = """{"amount_ml":100}""",
            nowMillis = now,
        )
        val futureUpdate = runCatching {
            care.updateRecord(
                id = formulaId,
                timestamp = now + 10_000L,
                endTimestamp = null,
                note = null,
                payloadJson = """{"amount_ml":100}""",
                nowMillis = now,
            )
        }.exceptionOrNull()
        assertThat(futureUpdate).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(fakes.records.get(formulaId)!!.timestamp).isEqualTo(now - 1_000L)
    }
    @Test
    fun factCreatePathsRejectFutureTimesWithZeroSkew() = runTest {
        val fakes = Fakes()
        fakes.wireTransactionalSnapshots()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = 50_000_000L

        val addFuture = runCatching {
            care.addRecord(
                babyId = babyId,
                type = RecordType.PEE,
                timestamp = now + 1L,
                payloadJson = """{"pee_amount":2}""",
                nowMillis = now,
            )
        }.exceptionOrNull()
        assertThat(addFuture).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(addFuture!!.message).isEqualTo("不能选未来时刻")
        assertThat(fakes.records.listForBaby(babyId)).isEmpty()

        val sleepEndFuture = runCatching {
            care.addRecord(
                babyId = babyId,
                type = RecordType.SLEEP,
                timestamp = now - 60_000L,
                endTimestamp = now + 1L,
                payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
                nowMillis = now,
            )
        }.exceptionOrNull()
        assertThat(sleepEndFuture).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(sleepEndFuture!!.message).isEqualTo("不能选未来时刻")

        val confirmFutureStart = runCatching {
            care.confirmSleep(
                babyId = babyId,
                expectedOpenSleepId = null,
                timestamp = now + 1L,
                endTimestamp = null,
                note = null,
                payloadJson = """{"is_nap":true,"anomaly_flag":false}""",
                nowMillis = now,
            )
        }.exceptionOrNull()
        assertThat(confirmFutureStart).isInstanceOf(IllegalArgumentException::class.java)

        val openId = care.confirmSleep(
            babyId = babyId,
            expectedOpenSleepId = null,
            timestamp = now - 10_000L,
            endTimestamp = null,
            note = null,
            payloadJson = """{"is_nap":true,"anomaly_flag":false}""",
            nowMillis = now,
        )
        val confirmFutureEnd = runCatching {
            care.confirmSleep(
                babyId = babyId,
                expectedOpenSleepId = openId,
                timestamp = now - 10_000L,
                endTimestamp = now + 1L,
                note = null,
                payloadJson = """{"is_nap":true,"anomaly_flag":false}""",
                nowMillis = now,
            )
        }.exceptionOrNull()
        assertThat(confirmFutureEnd).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(fakes.records.get(openId)!!.endTimestamp).isNull()

        val nursingFuture = runCatching {
            care.completeNursing(
                babyId = babyId,
                leftMin = 5,
                rightMin = 0,
                order = "L",
                startedAt = now - 5_000L,
                endedAt = now + 1L,
                nowMillis = now,
            )
        }.exceptionOrNull()
        assertThat(nursingFuture).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(
            fakes.records.listForBaby(babyId).none { it.type == RecordType.NURSING.key },
        ).isTrue()

        val sleepDownFuture = runCatching {
            care.sleepDown(babyId = babyId, at = now + 1L, nowMillis = now)
        }.exceptionOrNull()
        assertThat(sleepDownFuture).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(sleepDownFuture!!.message).isEqualTo("不能选未来时刻")
        // Existing open sleep from confirm path still the only open interval.
        assertThat(fakes.records.findOpenSleep(babyId)?.id).isEqualTo(openId)

        // Close open interval first so sleepUp future gate is exercised on a clean close.
        care.confirmSleep(
            babyId = babyId,
            expectedOpenSleepId = openId,
            timestamp = now - 10_000L,
            endTimestamp = now,
            note = null,
            payloadJson = """{"is_nap":true,"anomaly_flag":false}""",
            nowMillis = now,
        )
        care.sleepDown(babyId = babyId, at = now - 1_000L, nowMillis = now)
        val sleepUpFuture = runCatching {
            care.sleepUp(babyId = babyId, at = now + 1L, nowMillis = now)
        }.exceptionOrNull()
        assertThat(sleepUpFuture).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(sleepUpFuture!!.message).isEqualTo("不能选未来时刻")
        assertThat(fakes.records.findOpenSleep(babyId)?.endTimestamp).isNull()
    }
    @Test
    fun completeNursingWithCarePlanIdAllowsFiveMinuteSkewOnActualTimes() = runTest {
        val fakes = Fakes()
        fakes.wireTransactionalSnapshots()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = 70_000_000L
        val fiveMin = RecordTime.FULFILLMENT_ACTUAL_TIME_SKEW_MILLIS
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.NURSING,
            scheduledAt = now + 3_600_000L,
            payloadJson =
                """{"left_min":0,"right_min":0,"order":"LR","record_mode":"end"}""",
            nowMillis = now,
        )

        val beyond = runCatching {
            care.completeNursing(
                babyId = babyId,
                leftMin = 5,
                rightMin = 0,
                order = "L",
                startedAt = now - 5_000L,
                endedAt = now + fiveMin + 1L,
                completionClientUuid = "nursing-skew-beyond",
                carePlanId = planId,
                nowMillis = now,
            )
        }.exceptionOrNull()
        assertThat(beyond).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(beyond!!.message).isEqualTo("不能选未来时刻")
        assertThat(care.getCarePlan(planId)!!.status).isEqualTo(CarePlanStatus.PENDING)
        assertThat(
            fakes.records.listForBaby(babyId).none { it.type == RecordType.NURSING.key },
        ).isTrue()

        val recordId = care.completeNursing(
            babyId = babyId,
            leftMin = 5,
            rightMin = 0,
            order = "L",
            startedAt = now - 5_000L,
            endedAt = now + fiveMin,
            completionClientUuid = "nursing-skew-ok",
            carePlanId = planId,
            nowMillis = now,
        )
        assertThat(care.getRecord(recordId)!!.timestamp).isEqualTo(now + fiveMin)
        assertThat(care.getCarePlan(planId)!!.status).isEqualTo(CarePlanStatus.COMPLETED)
        assertThat(care.getCarePlan(planId)!!.fulfilledRecordClientUuid)
            .isEqualTo("nursing-skew-ok")
    }
}
