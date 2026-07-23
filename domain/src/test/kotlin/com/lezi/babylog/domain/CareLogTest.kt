package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.CalendarEventDao
import com.lezi.babylog.core.database.CalendarEventEntity
import com.lezi.babylog.core.database.FamilyDao
import com.lezi.babylog.core.database.FamilyEntity
import com.lezi.babylog.core.database.LocalUserDao
import com.lezi.babylog.core.database.LocalUserEntity
import com.lezi.babylog.core.database.MembershipDao
import com.lezi.babylog.core.database.MembershipEntity
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.SettingsLocal
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test

class CareLogTest {
    private val zone = ZoneOffset.UTC

    @Test
    fun createBaby_setsCurrentAndFields() = runTest {
        val care = Fakes().careLog()
        val day = LocalDate.of(2026, 1, 15).toEpochDay()
        val id = care.createBaby(
            CreateBabyInput(
                nickname = "小满",
                sex = "FEMALE",
                birthdayEpochDay = day,
                birthWeightGrams = 3200,
                themeColorArgb = 0xFFAA442B.toInt(),
            ),
        )
        val baby = care.getCurrentBaby()
        assertThat(id).isGreaterThan(0)
        assertThat(baby).isNotNull()
        assertThat(baby!!.nickname).isEqualTo("小满")
        assertThat(baby.sex?.name).isEqualTo("FEMALE")
        assertThat(baby.birthdayEpochDay).isEqualTo(day)
        assertThat(baby.birthWeightGrams).isEqualTo(3200)
        assertThat(baby.themeColorArgb).isEqualTo(0xFFAA442B.toInt())
        assertThat(care.observeHasBaby().first()).isTrue()
    }

    @Test
    fun addBaby_rejectsDuplicateNickname() = runTest {
        val care = Fakes().careLog()
        care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val thrown = runCatching {
            care.addBaby(CreateBabyInput(nickname = " 年年 ", birthdayEpochDay = 2))
        }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(DuplicateBabyNicknameException::class.java)
        assertThat(care.listBabies()).hasSize(1)
    }

    @Test
    fun updateBabyProfile_canSetBirthdayAndWeight() = runTest {
        val care = Fakes().careLog()
        val id = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 10))
        val day = LocalDate.of(2025, 12, 1).toEpochDay()
        care.updateBabyProfile(
            id,
            UpdateBabyInput(
                nickname = "豆豆",
                sex = "MALE",
                birthdayEpochDay = day,
                birthWeightGrams = 3500,
            ),
        )
        val baby = care.getCurrentBaby()!!
        assertThat(baby.birthdayEpochDay).isEqualTo(day)
        assertThat(baby.birthWeightGrams).isEqualTo(3500)
        assertThat(baby.sex?.name).isEqualTo("MALE")
    }

    @Test
    fun deleteBaby_removesExtraProfile() = runTest {
        val care = Fakes().careLog()
        val a = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val b = care.addBaby(CreateBabyInput(nickname = "临时", birthdayEpochDay = 2))
        care.addRecord(babyId = a, type = RecordType.PEE, timestamp = 1_000L, payloadJson = """{"pee_amount":2}""")
        assertThat(care.listBabies()).hasSize(2)
        assertThat(care.deleteBaby(b)).isTrue()
        assertThat(care.listBabies().map { it.nickname }).containsExactly("年年")
        assertThat(care.getCurrentBaby()!!.id).isEqualTo(a)
    }

    @Test
    fun dedupeBabiesByNickname_mergesRecordsOntoKeeper() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val a = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val b = fakes.babies.upsert(
            BabyEntity(
                familyId = 1,
                nickname = "年年",
                birthdayEpochDay = 2,
                themeColorArgb = CreateBabyInput.DEFAULT_THEME_COLOR,
                clientUuid = "dup-niannian",
                updatedAt = System.currentTimeMillis(),
            ),
        )
        care.addRecord(babyId = a, type = RecordType.PEE, timestamp = 1_000L, payloadJson = """{"pee_amount":2}""")
        care.addRecord(babyId = b, type = RecordType.FORMULA, timestamp = 2_000L, payloadJson = """{"amount_ml":90}""")
        care.dedupeBabiesByNickname()
        val remaining = care.listBabies()
        assertThat(remaining).hasSize(1)
        assertThat(remaining.single().nickname).isEqualTo("年年")
        val keeperId = remaining.single().id
        assertThat(fakes.records.listForBaby(keeperId)).hasSize(2)
    }

    @Test
    fun daySummary_formulaAndPee() = runTest {
        val care = Fakes().careLog()
        val babyId = care.createBaby(
            CreateBabyInput(nickname = "豆豆", birthdayEpochDay = LocalDate.of(2026, 1, 1).toEpochDay()),
        )
        val day = LocalDate.of(2026, 7, 22)
        val base = day.atStartOfDay(zone).toInstant().toEpochMilli() + 8 * 3600_000L
        care.addRecord(babyId, RecordType.FORMULA, timestamp = base, payloadJson = """{"amount_ml":100}""")
        care.addRecord(babyId, RecordType.FORMULA, timestamp = base + 1000, payloadJson = """{"amount_ml":50}""")
        care.addRecord(babyId, RecordType.PEE, timestamp = base + 2000, payloadJson = """{"pee_amount":2}""")
        val summary = care.daySummary(babyId, day, zone)
        assertThat(summary.feedMl).isEqualTo(150)
        assertThat(summary.formulaMl).isEqualTo(150)
        assertThat(summary.peeCount).isEqualTo(1)
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
    fun softDelete_removesFromSummary() = runTest {
        val care = Fakes().careLog()
        val babyId = care.createBaby(
            CreateBabyInput(nickname = "豆豆", birthdayEpochDay = LocalDate.of(2026, 1, 1).toEpochDay()),
        )
        val day = LocalDate.of(2026, 7, 22)
        val base = day.atStartOfDay(zone).toInstant().toEpochMilli() + 9 * 3600_000L
        val id = care.addRecord(babyId, RecordType.FORMULA, timestamp = base, payloadJson = """{"amount_ml":120}""")
        assertThat(care.daySummary(babyId, day, zone).feedMl).isEqualTo(120)
        care.deleteRecord(id)
        assertThat(care.daySummary(babyId, day, zone).feedMl).isEqualTo(0)
        assertThat(care.dayRecords(babyId, day, zone)).isEmpty()
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
            payloadJson = """{"is_nap":true}""",
        )

        val duplicateFailure = runCatching {
            care.confirmSleep(
                babyId = babyId,
                expectedOpenSleepId = null,
                timestamp = start + 60_000L,
                endTimestamp = null,
                note = null,
                payloadJson = "{}",
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
            payloadJson = """{"is_nap":true}""",
        )
        assertThat(care.observeOpenSleep(babyId).first()).isNull()
        assertThat(care.getRecord(openId)!!.endTimestamp).isEqualTo(start + 30 * 60_000L)
    }

    @Test
    fun updateRecord_canClearCompletedSleepEnd() = runTest {
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
        )

        care.updateRecord(
            id = id,
            timestamp = start,
            endTimestamp = null,
            note = null,
            payloadJson = "{}",
        )

        assertThat(care.getRecord(id)!!.endTimestamp).isNull()
    }

    @Test
    fun sleepDownTwice_marksAnomaly() = runTest {
        val f = Fakes()
        val care = f.careLog()
        val babyId = care.createBaby(
            CreateBabyInput(nickname = "豆豆", birthdayEpochDay = LocalDate.of(2026, 1, 1).toEpochDay()),
        )
        val t0 = 1_700_000_000_000L
        care.sleepDown(babyId, t0)
        care.sleepDown(babyId, t0 + 60_000L)
        val all = f.records.listForBaby(babyId)
        assertThat(all).hasSize(2)
        assertThat(all.any { it.payloadJson.contains("anomaly_flag") }).isTrue()
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
    fun multiBaby_isolation() = runTest {
        val care = Fakes().careLog()
        val a = care.createBaby(CreateBabyInput(nickname = "A", birthdayEpochDay = 1))
        val b = care.addBaby(CreateBabyInput(nickname = "B", birthdayEpochDay = 2))
        val day = LocalDate.of(2026, 7, 22)
        val ts = day.atStartOfDay(zone).toInstant().toEpochMilli() + 1000
        care.addRecord(a, RecordType.PEE, timestamp = ts)
        care.addRecord(b, RecordType.POOP, timestamp = ts + 1)
        assertThat(care.daySummary(a, day, zone).peeCount).isEqualTo(1)
        assertThat(care.daySummary(a, day, zone).poopCount).isEqualTo(0)
        assertThat(care.daySummary(b, day, zone).poopCount).isEqualTo(1)
        care.setCurrentBaby(b)
        assertThat(care.getCurrentBaby()!!.nickname).isEqualTo("B")
    }

    @Test
    fun amountCandidates_insertsExactLast() {
        val c = amountCandidates(step = 5, lastMl = 123)
        assertThat(c).contains(123)
        assertThat(c).contains(120)
        assertThat(c).contains(125)
        assertThat(c[amountCenterIndex(c, 123)]).isEqualTo(123)
        val step15 = amountCandidates(15, 120)
        assertThat(step15[1] - step15[0]).isEqualTo(15)
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
        care.addRecord(babyId, RecordType.MEMO, timestamp = ts, note = "布洛芬 2.5ml", payloadJson = """{"body":"服药"}""")
        care.addRecord(babyId, RecordType.DIARY, timestamp = ts + 1, payloadJson = """{"body":"今天发烧了"}""")
        care.addRecord(babyId, RecordType.FORMULA, timestamp = ts + 2, payloadJson = """{"amount_ml":120}""")
        assertThat(care.search(babyId, "布洛芬")).hasSize(1)
        assertThat(care.search(babyId, "发烧")).hasSize(1)
        assertThat(care.search(babyId, "不存在的词xyz")).isEmpty()
        assertThat(care.search(babyId, "   ")).isEmpty()
        val id = care.addRecord(babyId, RecordType.MEMO, timestamp = ts + 3, note = "临时")
        care.deleteRecord(id)
        assertThat(care.search(babyId, "临时")).isEmpty()
        val b2 = care.addBaby(CreateBabyInput(nickname = "B", birthdayEpochDay = 2))
        care.addRecord(b2, RecordType.MEMO, timestamp = ts, note = "布洛芬")
        assertThat(care.search(babyId, "布洛芬")).hasSize(1)
        val ws = weekStartFor(day, 1)
        val summary = care.weekSummary(babyId, ws, zone)
        assertThat(summary.totalFeedMl).isEqualTo(120)
        val widget = care.recentCareSummary(babyId, zone)
        assertThat(widget.feedMl).isEqualTo(120)
        assertThat(widget.babyName).isEqualTo("豆豆")
    }

    @Test
    fun calendarEventsStayBehindDomainSeam() = runTest {
        val care = Fakes().careLog()
        val babyId = care.createBaby(
            CreateBabyInput(nickname = "年年", birthdayEpochDay = 1),
        )
        val eventAt = 2_000_000L

        val id = care.addCalendarEvent(
            babyId = babyId,
            title = "体检",
            eventAt = eventAt,
            remindAt = eventAt - 60_000L,
        )
        val events = care.observeCalendarEvents(
            babyId = babyId,
            startInclusive = 1_000_000L,
            endExclusive = 3_000_000L,
        ).first()

        assertThat(id).isGreaterThan(0)
        assertThat(events).hasSize(1)
        assertThat(events.single().title).isEqualTo("体检")
    }

}
private class Fakes {
    val users = FakeLocalUserDao()
    val families = FakeFamilyDao()
    val memberships = FakeMembershipDao()
    val babies = FakeBabyDao()
    val records = FakeRecordDao()
    val calendarEvents = FakeCalendarEventDao()
    val settings = FakeSettingsStore()

    fun careLog() = CareLog(
        babies,
        records,
        calendarEvents,
        users,
        families,
        memberships,
        settings,
        FakeOutboxDao(),
        com.lezi.babylog.sync.NoOpSyncPort(),
        object : com.lezi.babylog.core.database.DatabaseTransactionRunner {
            override suspend fun <T> run(block: suspend () -> T): T = block()
        },
    )
}

private class FakeCalendarEventDao : CalendarEventDao {
    private val items = MutableStateFlow<List<CalendarEventEntity>>(emptyList())
    private val seq = AtomicLong(1)

    override fun observeRange(
        babyId: Long,
        start: Long,
        end: Long,
    ): Flow<List<CalendarEventEntity>> = items.map { events ->
        events.filter {
            it.babyId == babyId &&
                it.deletedAt == null &&
                it.eventAt >= start &&
                it.eventAt < end
        }.sortedBy { it.eventAt }
    }

    override suspend fun listForBaby(babyId: Long): List<CalendarEventEntity> =
        items.value.filter { it.babyId == babyId && it.deletedAt == null }
            .sortedBy { it.eventAt }

    override suspend fun upsert(event: CalendarEventEntity): Long {
        val id = event.id.takeIf { it != 0L } ?: seq.getAndIncrement()
        items.update { current ->
            current.filterNot { it.id == id } + event.copy(id = id)
        }
        return id
    }

    override suspend fun update(event: CalendarEventEntity) {
        items.update { current ->
            current.map { if (it.id == event.id) event else it }
        }
    }

    override suspend fun softDelete(id: Long, deletedAt: Long) {
        items.update { current ->
            current.map {
                if (it.id == id) it.copy(deletedAt = deletedAt, updatedAt = deletedAt) else it
            }
        }
    }

    override suspend fun deleteAll() {
        items.value = emptyList()
    }
}

private class FakeSettingsStore : SettingsStore {
    private val babyId = MutableStateFlow<Long?>(null)
    private val timer = MutableStateFlow<String?>(null)
    private val nextFeed = MutableStateFlow<Long?>(null)
    private val dark = MutableStateFlow("system")
    private val step = MutableStateFlow(5)
    private val timerEnabled = MutableStateFlow(true)
    private val interval = MutableStateFlow(180)
    private val recordAt = MutableStateFlow("end")
    private val order = MutableStateFlow("[]")
    private val hidden = MutableStateFlow(emptySet<String>())
    private val weekStart = MutableStateFlow(1)

    override val settings: Flow<SettingsLocal> =
        kotlinx.coroutines.flow.combine(dark, step) { d, st -> d to st }
            .let { base ->
                kotlinx.coroutines.flow.combine(base, timerEnabled, interval, recordAt) { pair, te, iv, ra ->
                    SettingsLocal(
                        darkMode = pair.first,
                        amountStepMl = pair.second,
                        timerEnabled = te,
                        nursingIntervalMin = iv,
                        recordAtStartOrEnd = ra,
                    )
                }
            }
            .let { partial ->
                kotlinx.coroutines.flow.combine(partial, nextFeed, order, hidden, weekStart) { s, nf, od, hd, ws ->
                    s.copy(
                        nextFeedAt = nf,
                        itemOrderJson = od,
                        hiddenItems = hd,
                        weekStart = ws,
                    )
                }
            }


    override val currentBabyId: Flow<Long?> = babyId
    override val nursingTimerJson: Flow<String?> = timer

    override suspend fun setCurrentBabyId(id: Long?) {
        babyId.value = id
    }

    override suspend fun setDarkMode(mode: String) {
        dark.value = mode
    }

    override suspend fun setVisualStyle(style: String) = Unit
    override suspend fun setPreferredHand(hand: String) = Unit

    override suspend fun setTimerEnabled(enabled: Boolean) {
        timerEnabled.value = enabled
    }

    override suspend fun setAmountStepMl(stepMl: Int) {
        step.value = stepMl
    }

    override suspend fun setTimeStepMin(step: Int) = Unit

    override suspend fun setNursingIntervalMin(min: Int) {
        interval.value = min
    }

    override suspend fun setRecordAt(startOrEnd: String) {
        recordAt.value = startOrEnd
    }

    override suspend fun setNextFeedAt(epochMs: Long?) {
        nextFeed.value = epochMs
    }

    override suspend fun clearNextFeedAt() {
        nextFeed.value = null
    }

    override suspend fun setItemOrderJson(json: String) {
        order.value = json
    }

    override suspend fun setHiddenItems(items: Set<String>) {
        hidden.value = items
    }

    override suspend fun setNursingTimerJson(json: String?) {
        timer.value = json
    }

    override suspend fun setWeekStart(day: Int) {
        weekStart.value = day
    }

    private val showAvg = MutableStateFlow(false)
    private val comparePrev = MutableStateFlow(false)
    override val showAvgSleep = showAvg
    override val comparePrevWeek = comparePrev
    override suspend fun setShowAvgSleep(enabled: Boolean) {
        showAvg.value = enabled
    }
    override suspend fun setComparePrevWeek(enabled: Boolean) {
        comparePrev.value = enabled
    }
}

private class FakeLocalUserDao : LocalUserDao {
    private var user: LocalUserEntity? = null
    private val seq = AtomicLong(1)

    override suspend fun get(): LocalUserEntity? = user

    override suspend fun upsert(user: LocalUserEntity): Long {
        val id = if (user.id == 0L) seq.getAndIncrement() else user.id
        this.user = user.copy(id = id)
        return id
    }

    override suspend fun deleteAll() {
        user = null
    }
}

private class FakeFamilyDao : FamilyDao {
    private val items = mutableListOf<FamilyEntity>()
    private val seq = AtomicLong(1)

    override suspend fun get(id: Long): FamilyEntity? = items.find { it.id == id }

    override suspend fun listAll(): List<FamilyEntity> = items.toList()

    override suspend fun insert(family: FamilyEntity): Long {
        val id = if (family.id == 0L) seq.getAndIncrement() else family.id
        items.removeAll { it.id == id }
        items += family.copy(id = id)
        return id
    }

    override suspend fun deleteAll() {
        items.clear()
    }
}

private class FakeMembershipDao : MembershipDao {
    private val items = mutableListOf<MembershipEntity>()

    override suspend fun upsert(membership: MembershipEntity) {
        items.removeAll { it.familyId == membership.familyId && it.userId == membership.userId }
        items += membership
    }

    override suspend fun listForFamily(familyId: Long): List<MembershipEntity> =
        items.filter { it.familyId == familyId }

    override suspend fun deleteAll() {
        items.clear()
    }
}

private class FakeBabyDao : BabyDao {
    private val items = MutableStateFlow<List<BabyEntity>>(emptyList())
    private val seq = AtomicLong(1)

    private fun active(): List<BabyEntity> = items.value.filter { it.deletedAt == null }

    override fun observeAll(): Flow<List<BabyEntity>> = items.map { list -> list.filter { it.deletedAt == null } }

    override suspend fun listAll(): List<BabyEntity> = active()

    override suspend fun get(id: Long): BabyEntity? = active().find { it.id == id }

    override suspend fun countByNickname(nickname: String, excludeId: Long): Int =
        active().count {
            it.nickname.trim() == nickname.trim() && (excludeId < 0 || it.id != excludeId)
        }

    override suspend fun countActive(): Int = active().size

    override suspend fun upsert(baby: BabyEntity): Long {
        val id = if (baby.id == 0L) seq.getAndIncrement() else baby.id
        val next = baby.copy(id = id)
        items.update { cur -> cur.filterNot { it.id == id } + next }
        return id
    }

    override suspend fun update(baby: BabyEntity) {
        items.update { cur -> cur.map { if (it.id == baby.id) baby else it } }
    }

    override suspend fun deleteAll() {
        items.value = emptyList()
    }
}

private class FakeRecordDao : RecordDao {
    private val items = MutableStateFlow<List<RecordEntity>>(emptyList())
    private val seq = AtomicLong(1)

    override fun observeRange(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): Flow<List<RecordEntity>> =
        items.map { list ->
            list.filter {
                it.babyId == babyId &&
                    it.deletedAt == null &&
                    it.timestamp >= startInclusive &&
                    it.timestamp < endExclusive
            }.sortedByDescending { it.timestamp }
        }

    override fun observeDay(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): Flow<List<RecordEntity>> =
        items.map { list ->
            list.filter {
                it.babyId == babyId &&
                    it.deletedAt == null &&
                    it.timestamp >= startInclusive &&
                    it.timestamp < endExclusive
            }.sortedByDescending { it.timestamp }
        }

    override suspend fun listDay(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): List<RecordEntity> =
        items.value.filter {
            it.babyId == babyId &&
                it.deletedAt == null &&
                it.timestamp >= startInclusive &&
                it.timestamp < endExclusive
        }.sortedByDescending { it.timestamp }

    override suspend fun get(id: Long): RecordEntity? =
        items.value.find { it.id == id && it.deletedAt == null }

    override suspend fun getByClientUuid(uuid: String): RecordEntity? =
        items.value.find { it.clientUuid == uuid }

    override suspend fun findOpenSleep(babyId: Long): RecordEntity? =
        items.value
            .filter {
                it.babyId == babyId &&
                    it.type == "sleep" &&
                    it.deletedAt == null &&
                    it.endTimestamp == null
            }
            .maxByOrNull { it.timestamp }

    override fun observeOpenSleep(babyId: Long): Flow<RecordEntity?> =
        items.map { records ->
            records
                .filter {
                    it.babyId == babyId &&
                        it.type == "sleep" &&
                        it.deletedAt == null &&
                        it.endTimestamp == null
                }
                .maxByOrNull { it.timestamp }
        }

    override suspend fun listForBaby(babyId: Long): List<RecordEntity> =
        items.value.filter { it.babyId == babyId && it.deletedAt == null }
            .sortedByDescending { it.timestamp }

    override suspend fun listRange(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): List<RecordEntity> =
        items.value.filter {
            it.babyId == babyId &&
                it.deletedAt == null &&
                it.timestamp >= startInclusive &&
                it.timestamp < endExclusive
        }.sortedBy { it.timestamp }

    override suspend fun listByType(babyId: Long, type: String): List<RecordEntity> =
        items.value.filter {
            it.babyId == babyId && it.deletedAt == null && it.type == type
        }.sortedBy { it.timestamp }

    override suspend fun upsert(record: RecordEntity): Long {
        val id = if (record.id == 0L) seq.getAndIncrement() else record.id
        val next = record.copy(id = id)
        items.update { cur -> cur.filterNot { it.id == id } + next }
        return id
    }

    override suspend fun update(record: RecordEntity) {
        items.update { cur -> cur.map { if (it.id == record.id) record else it } }
    }

    override suspend fun softDelete(id: Long, deletedAt: Long) {
        items.update { cur ->
            cur.map {
                if (it.id == id) it.copy(deletedAt = deletedAt, updatedAt = deletedAt) else it
            }
        }
    }

    override suspend fun deleteAll() {
        items.value = emptyList()
    }
}

private class FakeOutboxDao : com.lezi.babylog.core.database.OutboxDao {
    private val items = mutableListOf<com.lezi.babylog.core.database.OutboxEntity>()
    private val seq = java.util.concurrent.atomic.AtomicLong(1)
    override suspend fun enqueue(row: com.lezi.babylog.core.database.OutboxEntity): Long {
        val id = if (row.id == 0L) seq.getAndIncrement() else row.id
        items += row.copy(id = id)
        return id
    }
    override suspend fun peek(limit: Int) = items.take(limit)
    override suspend fun deleteIds(ids: List<Long>) { items.removeAll { it.id in ids } }
    override suspend fun deleteAll() { items.clear() }
}
