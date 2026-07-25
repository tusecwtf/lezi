package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.CalendarEventDao
import com.lezi.babylog.core.database.CalendarEventEntity
import com.lezi.babylog.core.database.CustomItemDao
import com.lezi.babylog.core.database.CustomItemEntity
import com.lezi.babylog.core.database.FamilyDao
import com.lezi.babylog.core.database.FamilyEntity
import com.lezi.babylog.core.database.LocalUserDao
import com.lezi.babylog.core.database.LocalUserEntity
import com.lezi.babylog.core.database.MediaAssetDao
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.MembershipDao
import com.lezi.babylog.core.database.MembershipEntity
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.SettingsLocal
import com.lezi.babylog.core.model.visibleBusinessText
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
                avatarPath = "baby_avatars/xiaoman.jpg",
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
        assertThat(baby.avatarPath).isEqualTo("baby_avatars/xiaoman.jpg")
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
    fun startupReadsNeverRenameBaby() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val id = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))

        assertThat(care.getCurrentBaby()!!.nickname).isEqualTo("豆豆")
        assertThat(fakes.babies.get(id)!!.nickname).isEqualTo("豆豆")

        repeat(3) { care.getCurrentBaby() }
        assertThat(care.getCurrentBaby()!!.nickname).isEqualTo("豆豆")
        assertThat(fakes.babies.get(id)!!.nickname).isEqualTo("豆豆")
    }

    @Test
    fun getCurrentBabyDoesNotImplicitlyWriteCurrentBabyId() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val first = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        val second = care.addBaby(CreateBabyInput(nickname = "果果", birthdayEpochDay = 2))
        // Stale pointer that no longer matches an active selection intent.
        fakes.settings.setCurrentBabyId(null)

        val resolved = care.getCurrentBaby()
        assertThat(resolved).isNotNull()
        assertThat(resolved!!.id).isEqualTo(first)
        // Read path must not self-heal the setting; only explicit mutators write.
        assertThat(fakes.settings.currentBabyId.first()).isNull()

        care.setCurrentBaby(second)
        assertThat(fakes.settings.currentBabyId.first()).isEqualTo(second)
        assertThat(care.getCurrentBaby()!!.id).isEqualTo(second)
    }

    @Test
    fun addBabyAndAddCustomItemUseSharedDatabaseTransaction() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val before = fakes.transactions.runCount

        care.addBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        care.addCustomItem("自定义项", iconSlot = 0)

        assertThat(fakes.transactions.runCount - before).isAtLeast(2)
    }

    @Test
    fun localFamilyIdentityUsesReadOnlyDefaultsBeforeBootstrap() = runTest {
        val care = Fakes().careLog()

        assertThat(care.localFamilyIdentity()).isEqualTo(
            LocalFamilyIdentity(
                deviceId = "—",
                displayName = "我（本机）",
                familyId = 1L,
            ),
        )
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
                avatarPath = "baby_avatars/doudou.jpg",
            ),
        )
        val baby = care.getCurrentBaby()!!
        assertThat(baby.birthdayEpochDay).isEqualTo(day)
        assertThat(baby.birthWeightGrams).isEqualTo(3500)
        assertThat(baby.avatarPath).isEqualTo("baby_avatars/doudou.jpg")
        assertThat(baby.sex?.name).isEqualTo("MALE")

        care.updateBabyProfile(
            id,
            UpdateBabyInput(
                nickname = "豆豆",
                sex = "MALE",
                birthdayEpochDay = day,
                birthWeightGrams = 3500,
                avatarPath = null,
            ),
        )
        assertThat(care.getCurrentBaby()!!.avatarPath).isNull()
    }

    @Test
    fun babyReadModifyWritesUseTheSharedDatabaseTransaction() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val first = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 10))
        val second = care.createBaby(CreateBabyInput(nickname = "果果", birthdayEpochDay = 11))
        val before = fakes.transactions.runCount

        care.updateBabyProfile(
            first,
            UpdateBabyInput(nickname = "豆豆", birthdayEpochDay = 12),
        )
        care.updateBabyDueDate(first, 20)
        care.renameBaby(first, "豆豆新名")
        assertThat(care.deleteBaby(second)).isTrue()

        assertThat(fakes.transactions.runCount - before).isEqualTo(4)
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
    fun explicitBabyMergeRequiresIdsAndProvidesPreview() = runTest {
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
        val preview = care.previewBabyMerge(sourceBabyId = b, targetBabyId = a)
        assertThat(preview!!.sourceBabyId).isEqualTo(b)
        assertThat(preview.targetBabyId).isEqualTo(a)
        assertThat(preview.recordCount).isEqualTo(1)

        assertThat(care.mergeBabyProfiles(sourceBabyId = b, targetBabyId = a)).isTrue()
        val remaining = care.listBabies()
        assertThat(remaining).hasSize(1)
        assertThat(remaining.single().nickname).isEqualTo("年年")
        val keeperId = remaining.single().id
        assertThat(fakes.records.listForBaby(keeperId)).hasSize(2)
    }

    @Test
    fun mergeBabyProfilesMovesTombstonesAndAvatarMedia() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val target = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val source = care.addBaby(CreateBabyInput(nickname = "临时", birthdayEpochDay = 2))
        val liveId = care.addRecord(source, RecordType.PEE, timestamp = 1_000)
        val tombstoneId = care.addRecord(source, RecordType.FORMULA, timestamp = 2_000)
        care.deleteRecord(tombstoneId)
        fakes.media.seed(
            MediaAssetEntity(
                clientUuid = "avatar-source",
                kind = "avatar",
                babyId = source,
                localUri = "avatars/source.jpg",
                createdAt = 100,
                updatedAt = 100,
            ),
        )

        assertThat(care.mergeBabyProfiles(sourceBabyId = source, targetBabyId = target)).isTrue()

        assertThat(fakes.records.listAllIncludingDeleted().map { it.id to it.babyId })
            .containsExactly(liveId to target, tombstoneId to target)
        assertThat(fakes.media.listAllIncludingDeleted().single().babyId).isEqualTo(target)
        assertThat(fakes.media.listAllIncludingDeleted().single().syncDirty).isTrue()
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
        )
        care.addRecord(
            babyId = babyId,
            type = RecordType.SLEEP,
            timestamp = dayStart + 2 * 60 * 60_000L,
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
        )

        val failure = runCatching {
            care.updateRecord(
                id = id,
                timestamp = start,
                endTimestamp = null,
                note = null,
                payloadJson = "{}",
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(care.getRecord(id)!!.endTimestamp).isEqualTo(start + 90 * 60_000L)
    }

    @Test
    fun sleepEndMustBeStrictlyAfterStart() = runTest {
        val care = Fakes().careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        val start = 1_700_000_000_000L

        val addFailure = runCatching {
            care.addRecord(
                babyId = babyId,
                type = RecordType.SLEEP,
                timestamp = start,
                endTimestamp = start,
            )
        }.exceptionOrNull()
        val openId = care.sleepDown(babyId, start)
        val closeFailure = runCatching {
            care.sleepUp(babyId, start)
        }.exceptionOrNull()

        assertThat(addFailure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(closeFailure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(care.getRecord(openId)!!.endTimestamp).isNull()
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
    fun sleepUp_healsDuplicateOpenSleepsBeforeClosingLatest() = runTest {
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
                createdByUserId = 1,
                payloadJson = "{}",
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
                createdByUserId = 1,
                payloadJson = "{}",
                updatedAt = t0 + 60 * 60_000L,
            ),
        )

        val closedId = care.sleepUp(babyId, t0 + 2 * 60 * 60_000L)

        val all = f.records.listForBaby(babyId)
        assertThat(all).hasSize(2)
        assertThat(all.none { it.endTimestamp == null }).isTrue()
        val stale = all.single { it.clientUuid == "sleep-stale" }
        val latest = all.single { it.clientUuid == "sleep-latest" }
        assertThat(stale.endTimestamp).isEqualTo(t0 + 60 * 60_000L)
        assertThat(stale.payloadJson).contains("\"anomaly_flag\":true")
        assertThat(latest.endTimestamp).isEqualTo(t0 + 2 * 60 * 60_000L)
        assertThat(closedId).isEqualTo(latest.id)
        assertThat(care.observeOpenSleep(babyId).first()).isNull()
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
        care.addRecord(
            babyId,
            RecordType.FORMULA,
            timestamp = ts + 2,
            payloadJson = """{"amount_ml":120,"internal_debug":"secretvalue"}""",
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
            payloadJson = """{"left_min":10,"right_min":0,"order":"L"}""",
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
        assertThat(care.search(babyId, "secretvalue")).isEmpty()
        assertThat(care.search(babyId, "不存在的词xyz")).isEmpty()
        assertThat(care.search(babyId, "   ")).isEmpty()
        // ISS-030: short Latin mid-alias hits must not return whole type classes.
        assertThat(care.search(babyId, "e")).isEmpty()
        assertThat(care.search(babyId, "a")).isEmpty()
        assertThat(care.search(babyId, "pee").single().type).isEqualTo(RecordType.PEE)
        assertThat(care.search(babyId, "formula").single().type).isEqualTo(RecordType.FORMULA)
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
    fun searchUnitSuffixQueries_matchViaVisibleText() = runTest {
        val care = Fakes().careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        val day = LocalDate.now(zone)
        val ts = day.atStartOfDay(zone).toInstant().toEpochMilli() + 1000
        care.addRecord(
            babyId,
            RecordType.FORMULA,
            timestamp = ts,
            payloadJson = """{"amount_ml":120}""",
        )
        care.addRecord(
            babyId,
            RecordType.WEIGHT,
            timestamp = ts + 1,
            payloadJson = """{"value":6350,"unit":"g"}""",
        )
        val formula = care.search(babyId, "120ml")
        val weight = care.search(babyId, "6.35kg")
        assertThat(formula.map { it.type }).containsExactly(RecordType.FORMULA)
        assertThat(weight.map { it.type }).containsExactly(RecordType.WEIGHT)
        assertThat(care.getRecord(formula.single().id)!!.visibleBusinessText()).isEqualTo("120ml")
        // short latin mid-alias still empty on these records
        assertThat(care.search(babyId, "e")).isEmpty()
    }

    @Test
    fun searchTreatsSqlLikeMetacharactersAsLiterals() = runTest {
        // F-F-01: user queries with %, _, \ must not expand as SQL wildcards.
        val care = Fakes().careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        val day = LocalDate.now(zone)
        val ts = day.atStartOfDay(zone).toInstant().toEpochMilli() + 1000
        care.addRecord(
            babyId,
            RecordType.MEMO,
            timestamp = ts,
            note = "浓度 100% 达标",
        )
        care.addRecord(
            babyId,
            RecordType.MEMO,
            timestamp = ts + 1,
            note = "code a_b path",
        )
        care.addRecord(
            babyId,
            RecordType.MEMO,
            timestamp = ts + 2,
            note = "path\\file backup",
        )
        // Control: plain substring without metacharacters.
        care.addRecord(
            babyId,
            RecordType.MEMO,
            timestamp = ts + 3,
            note = "100ml plain",
        )

        assertThat(care.search(babyId, "100%").single().note).isEqualTo("浓度 100% 达标")
        // "%" as wildcard would have matched "100ml plain" too.
        assertThat(care.search(babyId, "100%").map { it.note }).doesNotContain("100ml plain")

        assertThat(care.search(babyId, "a_b").single().note).isEqualTo("code a_b path")
        // "_" as single-char wildcard would match "100ml plain" notes with any mid char —
        // ensure a note "axb" is not produced as a false hit via type/payload alone.
        care.addRecord(babyId, RecordType.MEMO, timestamp = ts + 4, note = "axb only")
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

    @Test
    fun customItemsRejectEleventhAndKeepStableSnapshots() = runTest {
        val care = Fakes().careLog()
        care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        repeat(10) { index ->
            care.addCustomItem("项目$index", index % 8)
        }

        val failure = runCatching {
            care.addCustomItem("第十一个", 0)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CustomItemLimitException::class.java)
        assertThat(care.observeCustomItems().first()).hasSize(10)
    }

    @Test
    fun clearRecordsOnlyHardDeletesLocallyWithoutRequestingFamilySync() = runTest {
        val sync = RecordingSyncPort()
        val fakes = Fakes(sync)
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        care.addRecord(babyId, RecordType.PEE, timestamp = 1_000)
        care.addCalendarEvent(
            babyId = babyId,
            title = "疫苗",
            note = null,
            eventAt = 3_000,
            remindAt = null,
        )
        val requestsBeforeClear = sync.requests

        care.clearRecordsOnly()

        assertThat(fakes.records.listAllIncludingDeleted()).isEmpty()
        assertThat(fakes.calendarEvents.listForBabyIncludingDeleted(babyId)).isEmpty()
        assertThat(fakes.babies.listAll()).hasSize(1)
        assertThat(sync.requests).isEqualTo(requestsBeforeClear)
        assertThat(sync.localRecordReconciliations).isEqualTo(1)
    }

    @Test
    fun clearAllLocalDataWipesBabiesCustomItemsCalendarAndUsesSyncBarrier() = runTest {
        val sync = RecordingSyncPort()
        val fakes = Fakes(sync)
        val care = fakes.careLog()
        val babyId = care.createBaby(
            CreateBabyInput(
                nickname = "豆豆",
                birthdayEpochDay = 1,
                avatarPath = "baby_avatars/doudou.jpg",
            ),
        )
        care.addRecord(babyId, RecordType.PEE, timestamp = 1_000)
        care.addCustomItem("自定义奶粉", iconSlot = 0)
        care.addCalendarEvent(
            babyId = babyId,
            title = "体检",
            note = null,
            eventAt = 2_000,
            remindAt = null,
        )
        val requestsBeforeClear = sync.requests

        care.clearAllLocalData()

        assertThat(fakes.records.listAllIncludingDeleted()).isEmpty()
        assertThat(fakes.babies.listAllIncludingDeleted()).isEmpty()
        assertThat(fakes.customItems.listAll()).isEmpty()
        assertThat(fakes.calendarEvents.listForBaby(babyId)).isEmpty()
        assertThat(fakes.families.listAll()).isEmpty()
        assertThat(fakes.memberships.listForFamily(1)).isEmpty()
        assertThat(fakes.users.get()).isNull()
        assertThat(fakes.settings.currentBabyId.first()).isNull()
        assertThat(sync.requests).isEqualTo(requestsBeforeClear)
        assertThat(sync.fullLocalWipes).isEqualTo(1)
        assertThat(fakes.transactions.runCount).isAtLeast(1)
    }

    @Test
    fun syncVersionAlwaysAdvancesAcrossClockRollbackAndSameMillisecondWrites() {
        assertThat(nextSyncUpdatedAt(previous = 2_000, candidate = 900)).isEqualTo(2_001)
        assertThat(nextSyncUpdatedAt(previous = 2_000, candidate = 2_000)).isEqualTo(2_001)
        assertThat(nextSyncUpdatedAt(previous = 2_000, candidate = 2_500)).isEqualTo(2_500)
    }

}
private class Fakes(
    private val syncPort: com.lezi.babylog.sync.SyncPort =
        com.lezi.babylog.sync.NoOpSyncPort(),
) {
    val users = FakeLocalUserDao()
    val families = FakeFamilyDao()
    val memberships = FakeMembershipDao()
    val babies = FakeBabyDao()
    val records = FakeRecordDao()
    val calendarEvents = FakeCalendarEventDao()
    val customItems = FakeCustomItemDao()
    val media = FakeMediaAssetDao()
    val settings = FakeSettingsStore()
    val transactions = RecordingTransactionRunner()

    fun careLog() = CareLog(
        babies,
        records,
        calendarEvents,
        customItems,
        users,
        families,
        memberships,
        media,
        settings,
        syncPort,
        transactions,
    )
}

private class RecordingTransactionRunner :
    com.lezi.babylog.core.database.DatabaseTransactionRunner {
    var runCount = 0

    override suspend fun <T> run(block: suspend () -> T): T {
        runCount += 1
        return block()
    }
}

private class RecordingSyncPort(
    delegate: com.lezi.babylog.sync.SyncPort = com.lezi.babylog.sync.NoOpSyncPort(),
) : com.lezi.babylog.sync.SyncPort by delegate {
    var requests = 0
    var localRecordReconciliations = 0
    var fullLocalWipes = 0

    override fun requestSync(trigger: com.lezi.babylog.sync.SyncTrigger) {
        requests++
    }

    override suspend fun clearLocalRecords(clearLocal: suspend () -> Unit): Result<Unit> {
        localRecordReconciliations++
        clearLocal()
        return Result.success(Unit)
    }

    override suspend fun clearAllLocalData(clearLocal: suspend () -> Unit): Result<Unit> {
        fullLocalWipes++
        clearLocal()
        return Result.success(Unit)
    }
}

private class FakeMediaAssetDao : MediaAssetDao {
    private val items = mutableListOf<MediaAssetEntity>()
    private val seq = AtomicLong(1)

    fun seed(entity: MediaAssetEntity): Long {
        val id = entity.id.takeIf { it != 0L } ?: seq.getAndIncrement()
        items.removeAll { it.id == id }
        items += entity.copy(id = id)
        return id
    }

    override suspend fun upsert(asset: MediaAssetEntity): Long = seed(asset)

    override suspend fun listForRecord(recordId: Long): List<MediaAssetEntity> =
        items.filter { it.recordId == recordId }

    override suspend fun listActiveForRecord(recordId: Long): List<MediaAssetEntity> =
        items.filter { it.recordId == recordId && it.deletedAt == null }.sortedBy { it.id }

    override suspend fun activeAvatarForBaby(babyId: Long): MediaAssetEntity? =
        items.filter { it.babyId == babyId && it.kind == "avatar" && it.deletedAt == null }
            .maxWithOrNull(compareBy<MediaAssetEntity> { it.updatedAt }.thenBy { it.id })

    override suspend fun listAllIncludingDeleted(): List<MediaAssetEntity> =
        items.sortedBy { it.id }

    override suspend fun listPendingSync(): List<MediaAssetEntity> =
        items.filter { it.syncDirty }.sortedBy { it.id }

    override suspend fun markSynced(clientUuid: String, updatedAt: Long) {
        items.replaceAll {
            if (it.clientUuid == clientUuid && it.updatedAt == updatedAt) {
                it.copy(syncDirty = false)
            } else {
                it
            }
        }
    }

    override suspend fun listMissingLocalBytes(): List<MediaAssetEntity> =
        items.filter {
            it.deletedAt == null && it.remoteUri != null && it.localUri.isEmpty()
        }.sortedBy { it.id }

    override suspend fun getByClientUuid(uuid: String): MediaAssetEntity? =
        items.find { it.clientUuid == uuid }

    override suspend fun update(asset: MediaAssetEntity) {
        items.replaceAll { if (it.id == asset.id) asset else it }
    }

    override suspend fun clearRemoteUris() {
        items.replaceAll { it.copy(remoteUri = null, syncDirty = true) }
    }

    override suspend fun deleteLogMedia() {
        items.removeAll { it.kind == "log" }
    }

    override suspend fun deleteForRecord(recordId: Long) {
        items.removeAll { it.recordId == recordId }
    }

    override suspend fun deleteAll() {
        items.clear()
    }
}

private class FakeCustomItemDao : CustomItemDao {
    private val items = MutableStateFlow<List<CustomItemEntity>>(emptyList())
    private val seq = AtomicLong(1)

    override fun observeAll(): Flow<List<CustomItemEntity>> = items
    override suspend fun listAll(): List<CustomItemEntity> = items.value

    override suspend fun upsert(item: CustomItemEntity): Long {
        val id = item.id.takeIf { it > 0 } ?: seq.getAndIncrement()
        items.value = items.value.filterNot { it.id == id } + item.copy(id = id)
        return id
    }

    override suspend fun update(item: CustomItemEntity) {
        items.value = items.value.map { if (it.id == item.id) item else it }
    }

    override suspend fun softDelete(id: Long, deletedAt: Long) {
        items.value = items.value.map {
            if (it.id == id) it.copy(deletedAt = deletedAt, updatedAt = deletedAt) else it
        }.filter { it.deletedAt == null }
    }

    override suspend fun deleteAll() {
        items.value = emptyList()
    }
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

    override suspend fun listForBabyIncludingDeleted(babyId: Long): List<CalendarEventEntity> =
        items.value.filter { it.babyId == babyId }.sortedWith(
            compareBy<CalendarEventEntity> { it.eventAt }.thenBy { it.id },
        )

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

    override suspend fun setTimePickerStyle(style: String) = Unit
    override suspend fun setInfantFeverAdviceEnabled(enabled: Boolean) = Unit
    override suspend fun setCorrectedAgeEnabled(enabled: Boolean) = Unit

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

    override suspend fun setTimelineOrder(order: String) = Unit

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

    override suspend fun getIncludingDeleted(id: Long): BabyEntity? =
        items.value.find { it.id == id }

    override suspend fun getByClientUuid(uuid: String): BabyEntity? =
        active().find { it.clientUuid == uuid }

    override suspend fun listAllIncludingDeleted(): List<BabyEntity> = items.value

    override suspend fun listPendingSync(): List<BabyEntity> =
        items.value.filter(BabyEntity::syncDirty).sortedBy(BabyEntity::id)

    override suspend fun markSynced(clientUuid: String, updatedAt: Long) {
        items.update { values ->
            values.map {
                if (it.clientUuid == clientUuid && it.updatedAt == updatedAt) {
                    it.copy(syncDirty = false)
                } else {
                    it
                }
            }
        }
    }

    override suspend fun markAllPendingSync() {
        items.update { values -> values.map { it.copy(syncDirty = true) } }
    }

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

    override suspend fun updateAvatarReplica(
        clientUuid: String,
        avatarMediaUuid: String?,
        avatarPath: String?,
    ) {
        items.update { current ->
            current.map {
                if (it.clientUuid == clientUuid) {
                    it.copy(
                        avatarMediaUuid = avatarMediaUuid,
                        avatarPath = avatarPath,
                    )
                } else {
                    it
                }
            }
        }
    }

    override suspend fun updateAvatarMediaForLocalSnapshot(
        id: Long,
        expectedUpdatedAt: Long,
        expectedAvatarPath: String?,
        avatarMediaUuid: String?,
    ): Int {
        var changed = 0
        items.update { current ->
            current.map {
                if (
                    it.id == id &&
                    it.updatedAt == expectedUpdatedAt &&
                    it.avatarPath == expectedAvatarPath
                ) {
                    changed = 1
                    it.copy(avatarMediaUuid = avatarMediaUuid, syncDirty = true)
                } else {
                    it
                }
            }
        }
        return changed
    }

    override suspend fun updateAvatarPathForReplica(
        id: Long,
        expectedAvatarMediaUuid: String?,
        avatarPath: String?,
    ): Int {
        var changed = 0
        items.update { current ->
            current.map {
                if (it.id == id && it.avatarMediaUuid == expectedAvatarMediaUuid) {
                    changed = 1
                    it.copy(avatarPath = avatarPath)
                } else {
                    it
                }
            }
        }
        return changed
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
                    it.overlapsRange(startInclusive, endExclusive)
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
                    it.overlapsRange(startInclusive, endExclusive)
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
                it.overlapsRange(startInclusive, endExclusive)
        }.sortedByDescending { it.timestamp }

    override suspend fun get(id: Long): RecordEntity? =
        items.value.find { it.id == id && it.deletedAt == null }

    override suspend fun getIncludingDeleted(id: Long): RecordEntity? =
        items.value.find { it.id == id }

    override suspend fun getByClientUuid(uuid: String): RecordEntity? =
        items.value.find { it.clientUuid == uuid }

    override suspend fun listAllIncludingDeleted(): List<RecordEntity> = items.value

    override suspend fun listPendingSync(): List<RecordEntity> =
        items.value.filter(RecordEntity::syncDirty).sortedBy(RecordEntity::id)

    override suspend fun markSynced(clientUuid: String, updatedAt: Long) {
        items.update { values ->
            values.map {
                if (it.clientUuid == clientUuid && it.updatedAt == updatedAt) {
                    it.copy(syncDirty = false)
                } else {
                    it
                }
            }
        }
    }

    override suspend fun markAllPendingSync() {
        items.update { values -> values.map { it.copy(syncDirty = true) } }
    }

    override suspend fun findOpenSleep(babyId: Long): RecordEntity? =
        listOpenSleeps(babyId).firstOrNull()

    override suspend fun listOpenSleeps(babyId: Long): List<RecordEntity> =
        items.value
            .filter {
                it.babyId == babyId &&
                    it.type == "sleep" &&
                    it.deletedAt == null &&
                    it.endTimestamp == null
            }
            .sortedWith(
                compareByDescending<RecordEntity> { it.timestamp }.thenByDescending { it.id },
            )

    override fun observeOpenSleep(babyId: Long): Flow<RecordEntity?> =
        items.map { records ->
            records
                .filter {
                    it.babyId == babyId &&
                        it.type == "sleep" &&
                        it.deletedAt == null &&
                        it.endTimestamp == null
                }
                .maxWithOrNull(
                    compareBy<RecordEntity> { it.timestamp }.thenBy { it.id },
                )
        }

    override suspend fun listForBaby(babyId: Long): List<RecordEntity> =
        items.value.filter { it.babyId == babyId && it.deletedAt == null }
            .sortedByDescending { it.timestamp }

    override suspend fun searchCandidates(
        babyId: Long,
        escapedPattern: String,
        matchingTypeKeys: List<String>,
    ): List<RecordEntity> {
        // Mirror Room `LIKE :escapedPattern ESCAPE '\'` (see SqlLikeEscaped +
        // RecordSearchTest) so CareLog.search unit tests do not green on a
        // weaker contains() approximation of user metacharacters.
        return items.value.filter {
            it.babyId == babyId &&
                it.deletedAt == null &&
                (
                    it.note?.lowercase()?.let { note ->
                        matchesSqlLike(note, escapedPattern)
                    } == true ||
                        matchesSqlLike(it.payloadJson.lowercase(), escapedPattern) ||
                        it.type in matchingTypeKeys
                )
        }.sortedByDescending { it.timestamp }
    }

    override suspend fun listRange(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): List<RecordEntity> =
        items.value.filter {
            it.babyId == babyId &&
                it.deletedAt == null &&
                it.overlapsRange(startInclusive, endExclusive)
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

    override suspend fun updatePayloadReplica(
        id: Long,
        expectedPayloadJson: String,
        payloadJson: String,
    ): Int {
        var changed = 0
        items.update { current ->
            current.map {
                if (it.id == id && it.payloadJson == expectedPayloadJson) {
                    changed = 1
                    it.copy(payloadJson = payloadJson)
                } else {
                    it
                }
            }
        }
        return changed
    }

    override suspend fun softDelete(id: Long, deletedAt: Long) {
        items.update { cur ->
            cur.map {
                if (it.id == id) {
                    it.copy(deletedAt = deletedAt, updatedAt = deletedAt, syncDirty = true)
                } else {
                    it
                }
            }
        }
    }

    override suspend fun deleteAll() {
        items.value = emptyList()
    }

    private fun RecordEntity.overlapsRange(
        startInclusive: Long,
        endExclusive: Long,
    ): Boolean {
        val sleepEnd = endTimestamp
        return timestamp < endExclusive &&
            (
                timestamp >= startInclusive ||
                    (
                        type == RecordType.SLEEP.key &&
                            (sleepEnd == null || sleepEnd > startInclusive)
                        )
                )
    }
}
