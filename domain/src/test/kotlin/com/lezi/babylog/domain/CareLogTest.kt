package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.CalendarEventDao
import com.lezi.babylog.core.database.CalendarEventEntity
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
import com.lezi.babylog.core.database.MediaAssetDao
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.MembershipDao
import com.lezi.babylog.core.database.MembershipEntity
import com.lezi.babylog.core.database.PendingReminderCleanup
import com.lezi.babylog.core.database.PendingReminderCleanupOperation
import com.lezi.babylog.core.database.PendingReminderCleanupStore
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.datastore.LocalClearSettingsFinish
import com.lezi.babylog.core.datastore.LocalClearSettingsSnapshot
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.core.model.CarePlan
import com.lezi.babylog.core.model.CarePlanStatus
import com.lezi.babylog.core.model.RecordItemIdentity
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.Sex
import com.lezi.babylog.core.model.SettingsLocal
import com.lezi.babylog.core.model.displayLabel
import com.lezi.babylog.core.model.isPlanableNonStateful
import com.lezi.babylog.core.model.itemIdentity
import com.lezi.babylog.core.model.visibleBusinessText
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
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
    fun familyScaffoldSupportsFirstRunJoinWithoutPublishingPlaceholderBaby() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()

        care.ensureFamilyScaffold()

        assertThat(care.observeHasBaby().first()).isFalse()
        assertThat(care.listBabies()).isEmpty()
        assertThat(fakes.users.get()).isNotNull()
        assertThat(fakes.families.listAll()).hasSize(1)
        val familyId = fakes.families.listAll().single().id
        assertThat(fakes.memberships.listForFamily(familyId)).hasSize(1)
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
    fun updateLocalDisplayNameCachesMembershipNameAndRejectsPlaceholder() = runTest {
        val care = Fakes().careLog()
        care.ensureFamilyScaffold()
        assertThat(care.localFamilyIdentity().displayName).isEqualTo("我（本机）")

        care.updateLocalDisplayName("  妈妈  ")
        assertThat(care.localFamilyIdentity().displayName).isEqualTo("妈妈")

        care.updateLocalDisplayName("我（本机）")
        assertThat(care.localFamilyIdentity().displayName).isEqualTo("我（本机）")

        care.updateLocalDisplayName("爸爸")
        care.updateLocalDisplayName("   ")
        assertThat(care.localFamilyIdentity().displayName).isEqualTo("我（本机）")
    }

    @Test
    fun updateBabyProfile_canSetBirthdayAndWeight() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val id = care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 10))
        fakes.babies.update(fakes.babies.get(id)!!.copy(dueDateEpochDay = 20))
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
        assertThat(fakes.babies.get(id)!!.dueDateEpochDay).isEqualTo(20)

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
        care.renameBaby(first, "豆豆新名")
        assertThat(care.deleteBaby(second)).isTrue()

        assertThat(fakes.transactions.runCount - before).isEqualTo(3)
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
    fun addRecordStampsMembershipAuthorAndLegacyDeviceLinkFromJoinedSession() = runTest {
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
        assertThat(entity.createdByDeviceId).isEqualTo(sessionDevice)
        assertThat(entity.toModel().createdByDeviceId).isEqualTo(sessionDevice)
        // LocalUser.deviceId is a separate UUID — must not be used as the uploader link key.
        assertThat(entity.createdByDeviceId).isNotEqualTo(fakes.users.get()!!.deviceId)
    }

    @Test
    fun addRecordLeavesWriterDeviceIdNullWhenSessionHasNoDevice() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val id = care.addRecord(
            babyId = babyId,
            type = RecordType.PEE,
            timestamp = 1_000L,
            payloadJson = """{"pee_amount":2}""",
        )
        assertThat(fakes.records.get(id)!!.createdByDeviceId).isNull()
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
    fun mergeBabyProfilesKeepsOnlyLatestSleepOpen() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val target = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val source = care.addBaby(CreateBabyInput(nickname = "临时", birthdayEpochDay = 2))
        val staleOpenId = care.sleepDown(source, at = 1_000L)
        val latestOpenId = care.sleepDown(target, at = 2_000L)

        assertThat(care.mergeBabyProfiles(source, target)).isTrue()

        val opens = fakes.records.listOpenSleeps(target)
        assertThat(opens.map { it.id }).containsExactly(latestOpenId)
        val stale = requireNotNull(fakes.records.get(staleOpenId))
        assertThat(stale.endTimestamp).isEqualTo(2_000L)
        assertThat(stale.payloadJson).contains("\"anomaly_flag\":true")
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
        // Short Latin mid-alias hits must not return whole type classes.
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
        assertThat(care.search(babyId, "e")).isEmpty()
    }

    @Test
    fun searchTreatsSqlLikeMetacharactersAsLiterals() = runTest {
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
        // "_" must remain literal; wildcard semantics would also match "axb".
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
    fun concreteCustomRecordRoundTripsItemIdentityAndTitleSnapshot() = runTest {
        val care = Fakes().careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val itemId = care.addCustomItem("抚触", iconSlot = 2)

        val recordId = care.addRecord(
            babyId = babyId,
            type = RecordType.CUSTOM,
            timestamp = 2_000L,
            payloadJson = """{"title":"抚触","detail":"晚间","custom_item_id":$itemId,"icon_slot":2}""",
        )
        val loaded = care.getRecord(recordId)!!

        assertThat(loaded.type).isEqualTo(RecordType.CUSTOM)
        assertThat(loaded.displayLabel()).isEqualTo("抚触")
        assertThat(loaded.itemIdentity()).isEqualTo(RecordItemIdentity.custom(itemId))
        // Renaming the definition does not rewrite the historical snapshot.
        care.updateCustomItem(
            care.observeCustomItems().first().single().copy(name = "新抚触"),
        )
        val afterRename = care.getRecord(recordId)!!
        assertThat(afterRename.displayLabel()).isEqualTo("抚触")
        assertThat(afterRename.itemIdentity()).isEqualTo(RecordItemIdentity.custom(itemId))
    }

    @Test
    fun customItemOwnership_memberCanOnlyManageOwnAndAdminManagesAll() = runTest {
        assertThat(
            canManageCustomItemDefinition(
                creatorMembershipId = "m-creator",
                actorMembershipId = "m-creator",
                actorIsAdmin = false,
            ),
        ).isTrue()
        assertThat(
            canManageCustomItemDefinition(
                creatorMembershipId = "m-creator",
                actorMembershipId = "m-other",
                actorIsAdmin = false,
            ),
        ).isFalse()
        assertThat(
            canManageCustomItemDefinition(
                creatorMembershipId = "m-creator",
                actorMembershipId = "m-admin",
                actorIsAdmin = true,
            ),
        ).isTrue()
        // Creator left: admin still manages the definition.
        assertThat(
            canManageCustomItemDefinition(
                creatorMembershipId = "m-left",
                actorMembershipId = "m-admin",
                actorIsAdmin = true,
            ),
        ).isTrue()
        // Offline single-device: empty creator and actor.
        assertThat(
            canManageCustomItemDefinition(
                creatorMembershipId = "",
                actorMembershipId = "",
                actorIsAdmin = false,
            ),
        ).isTrue()
        // Legacy empty creator after join: non-admin cannot claim.
        assertThat(
            canManageCustomItemDefinition(
                creatorMembershipId = "",
                actorMembershipId = "m-member",
                actorIsAdmin = false,
            ),
        ).isFalse()
    }

    @Test
    fun pendingCreatorAcknowledgementAllowsOnlyTheExactLocalBlankEntities() = runTest {
        val pending = setOf(
            com.lezi.babylog.sync.CreatorAcknowledgementRef(
                entityType = "custom_item",
                clientUuid = "item-local-pending",
            ),
            com.lezi.babylog.sync.CreatorAcknowledgementRef(
                entityType = "care_plan",
                clientUuid = "plan-local-pending",
            ),
        )
        val sync = RecordingSyncPort(
            membershipId = "m-canonical",
            role = com.lezi.babylog.sync.FamilyRole.Member,
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

        assertThat(care.canManageCustomItem(localItem)).isTrue()
        assertThat(care.canManageCarePlan(localPlan)).isTrue()
        assertThat(care.canManageCustomItem(unknownItem)).isFalse()
        assertThat(care.canManageCarePlan(unknownPlan)).isFalse()

        sync.replaceSession(
            sync.currentSession().copy(pendingCreatorAcknowledgements = emptySet()),
        )

        assertThat(care.canManageCustomItem(localItem)).isFalse()
        assertThat(care.canManageCarePlan(localPlan)).isFalse()
    }

    @Test
    fun pendingCreatorAcknowledgementEnforcesExactEditAndDeletePermissions() = runTest {
        val sync = RecordingSyncPort(
            membershipId = "m-canonical",
            role = com.lezi.babylog.sync.FamilyRole.Member,
            familyId = "fam-1",
            deviceId = "dev-1",
            pendingCreatorAcknowledgements = setOf(
                com.lezi.babylog.sync.CreatorAcknowledgementRef(
                    "custom_item",
                    "item-local-pending",
                ),
                com.lezi.babylog.sync.CreatorAcknowledgementRef(
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
    fun customItemStampsCreatorMembershipAndRejectsNonOwnerEdit() = runTest {
        val memberSync = RecordingSyncPort(
            membershipId = "m-member",
            role = com.lezi.babylog.sync.FamilyRole.Member,
            familyId = "fam-1",
            deviceId = "dev-1",
        )
        val fakes = Fakes(memberSync)
        val care = fakes.careLog()
        care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val id = care.addCustomItem("药", 1)
        val item = care.observeCustomItems().first().single()
        assertThat(item.createdByMembershipId).isEqualTo("m-member")
        assertThat(item.clientUuid).isNotEmpty()

        // Simulate another member's definition present in DB.
        fakes.customItems.upsert(
            CustomItemEntity(
                id = 99L,
                clientUuid = "uuid-other",
                familyId = 1L,
                name = "他人项目",
                iconSlot = 0,
                sortOrder = 1,
                updatedAt = 1L,
                createdByMembershipId = "m-other",
            ),
        )
        val foreign = care.observeCustomItems().first().first { it.id == 99L }
        val denied = runCatching {
            care.updateCustomItem(foreign.copy(name = "篡改"))
        }.exceptionOrNull()
        assertThat(denied).isInstanceOf(CustomItemPermissionException::class.java)
        assertThat(care.observeCustomItems().first().first { it.id == 99L }.name)
            .isEqualTo("他人项目")

        // Owner may manage the foreign definition (leave takeover).
        val adminSync = RecordingSyncPort(
            membershipId = "m-admin",
            role = com.lezi.babylog.sync.FamilyRole.Owner,
            familyId = "fam-1",
            deviceId = "dev-admin",
        )
        val adminCare = Fakes(adminSync).let { f ->
            f.customItems.upsert(
                CustomItemEntity(
                    id = 99L,
                    clientUuid = "uuid-other",
                    familyId = 1L,
                    name = "他人项目",
                    iconSlot = 0,
                    sortOrder = 1,
                    updatedAt = 1L,
                    createdByMembershipId = "m-left",
                ),
            )
            f.careLog()
        }
        // Need a baby for family scaffold paths; update only needs existing row.
        adminCare.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        adminCare.updateCustomItem(
            CustomRecordItem(
                id = 99L,
                name = "管理员接管",
                iconSlot = 0,
                sortOrder = 1,
                createdByMembershipId = "m-left",
            ),
        )
    }

    @Test
    fun customItemTombstoneHidesFromObserveButKeepsIncludingDeleted() = runTest {
        val owned = Fakes()
        val log = owned.careLog()
        log.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val itemId = log.addCustomItem("保留墓碑", 0)
        log.deleteCustomItem(itemId)
        assertThat(log.observeCustomItems().first()).isEmpty()
        assertThat(owned.customItems.listAllIncludingDeleted()).hasSize(1)
        assertThat(owned.customItems.listAllIncludingDeleted().single().deletedAt).isNotNull()
        // Local hide (settings) is separate from tombstone — still a preference key only.
        assertThat(RecordItemIdentity.customCatalogKey(itemId)).isEqualTo("custom:$itemId")
    }

    @Test
    fun customItemFieldSnapshotDoesNotUseLiveNameAfterRename() = runTest {
        val item = CustomRecordItem(id = 3, name = "抚触", iconSlot = 2, sortOrder = 0)
        val snap = item.toFieldSnapshot()
        assertThat(snap.titleSnapshot).isEqualTo("抚触")
        assertThat(snap.iconSlot).isEqualTo(2)
        assertThat(snap.customItemId).isEqualTo(3L)
        val renamed = item.copy(name = "新名")
        // Historical snapshot object stays independent of later renames.
        assertThat(snap.titleSnapshot).isEqualTo("抚触")
        assertThat(renamed.toFieldSnapshot().titleSnapshot).isEqualTo("新名")
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

        // Future actual time is rejected.
        val futureFail = runCatching {
            care.fulfillCarePlan(
                carePlanId = planId,
                actualTimestamp = now + 10_000L,
                nowMillis = now,
            )
        }.exceptionOrNull()
        assertThat(futureFail).isInstanceOf(IllegalArgumentException::class.java)
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
    fun carePlanPhotosCreateUpdateFulfillAndTombstoneKeepOwnership() = runTest {
        val fakes = Fakes()
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
        assertThat(fakes.media.listForCarePlan(plan2).single().deletedAt).isNotNull()
    }

    @Test
    fun carePlanManagePermissionMatchesMembershipAcl() = runTest {
        val creatorSync = RecordingSyncPort(
            membershipId = "m-creator",
            role = com.lezi.babylog.sync.FamilyRole.Member,
            familyId = "fam-1",
            deviceId = "dev-1",
        )
        val fakes = Fakes(creatorSync)
        fakes.wireTransactionalSnapshots()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
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
            role = com.lezi.babylog.sync.FamilyRole.Member,
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
            role = com.lezi.babylog.sync.FamilyRole.Owner,
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
            role = com.lezi.babylog.sync.FamilyRole.Member,
            familyId = "fam-1",
            deviceId = "dev-1",
        )
        val fakes = Fakes(creatorSync)
        fakes.wireTransactionalSnapshots()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
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
            role = com.lezi.babylog.sync.FamilyRole.Member,
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
    fun multiCandidateFulfillmentPicksAdminAndHidesLoserFromOrdinarySurfaces() = runTest {
        // Joined member session so fulfill stamps offline role/membership trails
        // (ticket 26 residual: originator adjudication before server freeze pull).
        val memberSync = RecordingSyncPort(
            membershipId = "m-member",
            role = com.lezi.babylog.sync.FamilyRole.Member,
            familyId = "fam-1",
            deviceId = "dev-member",
        )
        val fakes = Fakes(memberSync)
        fakes.wireTransactionalSnapshots()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
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
                createdByUserId = 1L,
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
            role = com.lezi.babylog.sync.FamilyRole.Member,
            familyId = "fam-audit",
            deviceId = "dev-member",
        )
        val memberFakes = Fakes(memberSync)
        memberFakes.wireTransactionalSnapshots()
        val memberCare = memberFakes.careLog()
        val babyId = memberCare.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
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
                createdByUserId = 1L,
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
            role = com.lezi.babylog.sync.FamilyRole.Owner,
            familyId = "fam-audit",
            deviceId = "dev-owner",
        )
        val adminCare = CareLog(
            memberFakes.babies,
            memberFakes.records,
            memberFakes.carePlans,
            memberFakes.calendarEvents,
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
            memberFakes.localDataClearCoordinator(adminSync),
            memberFakes.calendarReminderMutationGuard,
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
            role = com.lezi.babylog.sync.FamilyRole.Owner,
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
                createdByUserId = 1L,
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
                createdByUserId = 1L,
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
    fun recordHasPriorFamilyRevisionUsesMediaReceiptsIncludingTombstones() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val recordId = care.addRecord(
            babyId = babyId,
            type = RecordType.FORMULA,
            timestamp = 1_000L,
            photoLocalPaths = listOf("photos/old.jpg"),
        )
        assertThat(care.recordHasPriorFamilyRevision(recordId)).isFalse()

        // Family-published receipt on the active photo → mutation chrome path.
        val active = fakes.media.listActiveForRecord(recordId).single()
        fakes.media.update(active.copy(remoteUri = "lezi-sync:media-old", syncDirty = false))
        assertThat(care.recordHasPriorFamilyRevision(recordId)).isTrue()

        // Even after the photo is tombstoned (user removed it in a new edit), the
        // prior package receipt still proves family still sees the old complete version.
        fakes.media.update(active.copy(remoteUri = "lezi-sync:media-old", deletedAt = 2_000L, syncDirty = false))
        assertThat(care.recordHasPriorFamilyRevision(recordId)).isTrue()
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
    fun historicalMemoAndBareCustomRemainReadableWithoutMigration() = runTest {
        val care = Fakes().careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val memoId = care.addRecord(
            babyId = babyId,
            type = RecordType.MEMO,
            timestamp = 1_000L,
            payloadJson = """{"body":"旧备注"}""",
        )
        val otherId = care.addRecord(
            babyId = babyId,
            type = RecordType.OTHER,
            timestamp = 1_100L,
            payloadJson = """{"title":"旧其他","detail":"x"}""",
        )
        val bareCustomId = care.addRecord(
            babyId = babyId,
            type = RecordType.CUSTOM,
            timestamp = 1_200L,
            payloadJson = """{"title":"仅标题历史"}""",
        )

        assertThat(care.getRecord(memoId)!!.type).isEqualTo(RecordType.MEMO)
        assertThat(care.getRecord(otherId)!!.displayLabel()).isEqualTo("旧其他")
        assertThat(care.getRecord(bareCustomId)!!.displayLabel()).isEqualTo("仅标题历史")
        assertThat(care.getRecord(bareCustomId)!!.itemIdentity()).isNull()
        assertThat(RecordItemIdentity.isInvalidNewEntryReference("memo")).isTrue()
        assertThat(RecordItemIdentity.isInvalidNewEntryReference("other")).isTrue()
        assertThat(RecordItemIdentity.isInvalidNewEntryReference("custom")).isTrue()
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
        val now = System.currentTimeMillis()
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.PEE,
            scheduledAt = now + 60_000L,
            nowMillis = now,
        )
        care.fulfillCarePlan(carePlanId = planId, actualTimestamp = now, nowMillis = now + 1)
        val requestsBeforeClear = sync.requests

        care.clearRecordsOnly()

        assertThat(fakes.records.listAllIncludingDeleted()).isEmpty()
        assertThat(fakes.calendarEvents.listForBabyIncludingDeleted(babyId)).isEmpty()
        assertThat(fakes.carePlans.listAllIncludingDeleted()).isEmpty()
        assertThat(fakes.fulfillmentCandidates.listAllIncludingDeleted()).isEmpty()
        assertThat(fakes.babies.listAll()).hasSize(1)
        assertThat(fakes.reminders.cancelledCarePlanIds).contains(planId)
        assertThat(sync.requests).isEqualTo(requestsBeforeClear)
        assertThat(sync.localRecordReconciliations).isEqualTo(1)
    }

    @Test
    fun recordsClearMarkerCapturesOnlyMappedOrProviderTouchedPlanUuids() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = System.currentTimeMillis()
        suspend fun newPlan(): CarePlanEntity {
            val id = care.createCarePlan(
                babyId = babyId,
                type = RecordType.BATH,
                scheduledAt = now + 60_000L + fakes.carePlans.listAllIncludingDeleted().size,
                nowMillis = now,
            )
            return checkNotNull(fakes.carePlans.get(id))
        }
        val mapped = newPlan()
        val withEventId = newPlan().let { plan ->
            plan.copy(systemCalendarEventId = "evt-known").also {
                fakes.carePlans.update(it)
            }
        }
        val uidLookupOnly = newPlan().let { plan ->
            plan.copy(systemCalendarProjectionPending = true).also {
                fakes.carePlans.update(it)
            }
        }
        val untouched = newPlan()
        fakes.settings.setSystemCalendarEventMapJson(
            "{\"${mapped.clientUuid}\":\"evt-mapped\"}",
        )

        val failure = runCatching { care.clearRecordsOnly() }.exceptionOrNull()

        assertThat(failure).isInstanceOf(LocalRecordsClearCommittedException::class.java)
        assertThat(fakes.pendingReminderCleanup.pending?.systemCalendarProjections)
            .containsExactly(
                mapped.clientUuid,
                "evt-mapped",
                withEventId.clientUuid,
                "evt-known",
                uidLookupOnly.clientUuid,
                null,
            )
        assertThat(fakes.pendingReminderCleanup.pending?.systemCalendarProjections)
            .doesNotContainKey(untouched.clientUuid)
    }

    @Test
    fun clearRecordsOnlyCancelsNextFeedAndEveryCalendarReminder() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val first = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val second = care.addBaby(CreateBabyInput(nickname = "果果", birthdayEpochDay = 2))
        val firstEvent = care.addCalendarEvent(first, "疫苗", 10_000L, 9_000L)
        val secondEvent = care.addCalendarEvent(second, "体检", 20_000L, 19_000L)
        fakes.reminders.scheduleNextFeed()
        fakes.reminders.scheduleCalendar(firstEvent, secondEvent)

        care.clearRecordsOnly()

        assertThat(fakes.reminders.nextFeedScheduled).isFalse()
        assertThat(fakes.reminders.scheduledCalendarIds).isEmpty()
    }

    @Test
    fun calendarWriteSchedulesReminderThroughCareLogSeam() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))

        val eventId = care.addCalendarEvent(babyId, "疫苗", 10_000L, 9_000L)

        assertThat(fakes.reminders.scheduledCalendarIds).containsExactly(eventId)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun recordsClearCannotInterleaveBetweenCalendarWriteAndAlarmSchedule() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val scheduleEntered = CompletableDeferred<Unit>()
        val allowSchedule = CompletableDeferred<Unit>()
        fakes.reminders.scheduleEntered = scheduleEntered
        fakes.reminders.allowSchedule = allowSchedule

        val add = async { care.addCalendarEvent(babyId, "疫苗", 10_000L, 9_000L) }
        scheduleEntered.await()
        val clear = async { care.clearRecordsOnly() }
        runCurrent()

        assertThat(clear.isCompleted).isFalse()
        allowSchedule.complete(Unit)
        val eventId = add.await()
        clear.await()

        assertThat(fakes.calendarEvents.listForBabyIncludingDeleted(babyId)).isEmpty()
        assertThat(fakes.reminders.scheduledCalendarIds).isEmpty()
        assertThat(fakes.reminders.recordClearBatches).containsExactly(listOf(eventId))
    }

    @Test
    fun deleteBabyCancelsOnlyThatBabysCalendarReminders() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val keep = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val deleted = care.addBaby(CreateBabyInput(nickname = "临时", birthdayEpochDay = 2))
        val keptEvent = care.addCalendarEvent(keep, "保留日程", 10_000L, 9_000L)
        val deletedEvent = care.addCalendarEvent(deleted, "删除日程", 20_000L, 19_000L)
        fakes.reminders.scheduleNextFeed()
        fakes.reminders.scheduleCalendar(keptEvent, deletedEvent)

        assertThat(care.deleteBaby(deleted)).isTrue()

        assertThat(fakes.reminders.nextFeedScheduled).isTrue()
        assertThat(fakes.reminders.scheduledCalendarIds).containsExactly(keptEvent)
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
        val now = System.currentTimeMillis()
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.PEE,
            scheduledAt = now + 60_000L,
            nowMillis = now,
        )
        assertThat(fakes.reminders.scheduledCarePlanIds).contains(planId)
        val requestsBeforeClear = sync.requests

        care.clearAllLocalData()

        assertThat(fakes.records.listAllIncludingDeleted()).isEmpty()
        assertThat(fakes.babies.listAllIncludingDeleted()).isEmpty()
        assertThat(fakes.customItems.listAll()).isEmpty()
        assertThat(fakes.carePlans.listAllIncludingDeleted()).isEmpty()
        assertThat(fakes.fulfillmentCandidates.listAllIncludingDeleted()).isEmpty()
        assertThat(fakes.calendarEvents.listForBaby(babyId)).isEmpty()
        assertThat(fakes.families.listAll()).isEmpty()
        assertThat(fakes.memberships.listForFamily(1)).isEmpty()
        assertThat(fakes.users.get()).isNull()
        assertThat(fakes.settings.currentBabyId.first()).isNull()
        assertThat(fakes.reminders.cancelledCarePlanIds).contains(planId)
        assertThat(fakes.reminders.scheduledCarePlanIds).doesNotContain(planId)
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
        assertThat(stored.payloadJson).contains("\"photos\"")
        assertThat(stored.payloadJson).contains("a.jpg")
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
    fun updateRecordReconcilesMediaAndSoftDeleteTombstonesPhotos() = runTest {
        val fakes = Fakes()
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
        assertThat(allMedia.single { it.localUri == drop }.deletedAt).isNotNull()

        care.deleteRecord(recordId)
        assertThat(fakes.media.listActiveForRecord(recordId)).isEmpty()
        assertThat(fakes.media.listForRecord(recordId).all { it.deletedAt != null }).isTrue()
        assertThat(fakes.records.getIncludingDeleted(recordId)!!.deletedAt).isNotNull()
    }

    @Test
    fun listRecordPhotoPathsFallsBackToLegacyPayloadPhotosWithoutMediaRows() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        // Historical row: photos only in payload, no media_assets (pre-common-attachment).
        val recordId = fakes.records.upsert(
            RecordEntity(
                clientUuid = "legacy-memo",
                babyId = babyId,
                type = RecordType.MEMO.key,
                timestamp = 1_000L,
                endTimestamp = null,
                note = null,
                createdByUserId = 1L,
                payloadJson = """{"body":"旧备注","photos":["legacy/a.jpg","legacy/b.jpg"]}""",
                schemaVersion = 1,
                updatedAt = 1_000L,
            ),
        )

        assertThat(care.listRecordPhotoPaths(recordId))
            .containsExactly("legacy/a.jpg", "legacy/b.jpg")
            .inOrder()
        assertThat(fakes.media.listActiveForRecord(recordId)).isEmpty()

        // Subsequent edit normalizes onto MediaAsset while preserving paths.
        care.updateRecord(
            id = recordId,
            timestamp = 1_000L,
            endTimestamp = null,
            note = null,
            payloadJson = """{"body":"旧备注"}""",
            photoLocalPaths = listOf("legacy/a.jpg", "legacy/b.jpg"),
        )
        assertThat(fakes.media.listActiveForRecord(recordId).map { it.localUri })
            .containsExactly("legacy/a.jpg", "legacy/b.jpg")
        assertThat(fakes.records.get(recordId)!!.payloadJson).contains("legacy/a.jpg")
    }

    @Test
    fun confirmSleepPersistsPhotosWithOpenAndClosedIntervals() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val path = "/data/user/0/com.lezi/files/record-media/sleep.jpg"
        val openId = care.confirmSleep(
            babyId = babyId,
            expectedOpenSleepId = null,
            timestamp = 1_000L,
            endTimestamp = null,
            note = null,
            payloadJson = """{"is_nap":true}""",
            photoLocalPaths = listOf(path),
        )
        assertThat(care.listRecordPhotoPaths(openId)).containsExactly(path)

        care.confirmSleep(
            babyId = babyId,
            expectedOpenSleepId = openId,
            timestamp = 1_000L,
            endTimestamp = 3_600_000L,
            note = "小睡",
            payloadJson = """{"is_nap":true}""",
            photoLocalPaths = listOf(path),
        )
        assertThat(care.listRecordPhotoPaths(openId)).containsExactly(path)
        assertThat(fakes.records.get(openId)!!.payloadJson).contains("sleep.jpg")
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
    fun convertCalendarEventToCarePlanCancelsEventReminderOnSuccessOnly() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = System.currentTimeMillis()
        val eventId = care.addCalendarEvent(
            babyId = babyId,
            title = "疫苗",
            eventAt = now + 120_000L,
            remindAt = now + 60_000L,
        )
        assertThat(fakes.reminders.scheduledCalendarIds).contains(eventId)
        val planId = care.convertCalendarEventToCarePlan(
            eventId = eventId,
            type = RecordType.VACCINE,
            payloadJson = """{"name":"五联"}""",
            nowMillis = now,
        )
        assertThat(planId).isGreaterThan(0L)
        assertThat(fakes.reminders.scheduledCalendarIds).doesNotContain(eventId)
        assertThat(care.listCalendarEvents(babyId).none { it.id == eventId }).isTrue()
        assertThat(care.getCarePlan(planId)!!.type).isEqualTo(RecordType.VACCINE)
        assertThat(fakes.reminders.scheduledCarePlanIds).contains(planId)
    }

    @Test
    fun createCustomCarePlanStampsNameIconSnapshot() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val customId = care.addCustomItem("抚触", iconSlot = 2)
        val now = System.currentTimeMillis()
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.CUSTOM,
            scheduledAt = now + 90_000L,
            customItemId = customId,
            payloadJson = "{}",
            nowMillis = now,
        )
        val plan = care.getCarePlan(planId)!!
        assertThat(plan.customItemId).isEqualTo(customId)
        assertThat(plan.payloadJson).contains("抚触")
        assertThat(plan.displayLabel()).isEqualTo("抚触")
        care.updateCustomItem(
            care.observeCustomItems().first().single().copy(name = "新名字"),
        )
        // Historical plan keeps snapshot label.
        assertThat(care.getCarePlan(planId)!!.displayLabel()).isEqualTo("抚触")
    }

    @Test
    fun everyPlanableNonStatefulBuiltInCanCreateAndFulfillCarePlan() = runTest {
        val planable = RecordType.entries.filter { it.isPlanableNonStateful }
        assertThat(planable).isNotEmpty()
        assertThat(planable).doesNotContain(RecordType.SLEEP)
        assertThat(planable).doesNotContain(RecordType.NURSING)
        assertThat(planable).doesNotContain(RecordType.MEMO)
        assertThat(planable).doesNotContain(RecordType.OTHER)
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

    private fun samplePlanPayload(type: RecordType): String = when (type) {
        RecordType.PEE -> """{"pee_amount":2}"""
        RecordType.POOP -> """{"poop_amount":2,"poop_color":1,"poop_shape":1}"""
        RecordType.BOTH_DIAPER ->
            """{"pee_amount":1,"poop_amount":1,"poop_color":1,"poop_shape":1}"""
        RecordType.FORMULA, RecordType.PUMPED_FEED -> """{"amount_ml":90}"""
        RecordType.PUMP_EXPRESS -> """{"amount_ml":60}"""
        RecordType.TEMPERATURE -> """{"celsius":36.8}"""
        RecordType.MEDICINE -> """{"name":"维生素","dose":"1滴"}"""
        RecordType.VACCINE -> """{"name":"五联"}"""
        RecordType.HEIGHT -> """{"value":55.0,"unit":"cm"}"""
        RecordType.WEIGHT -> """{"value":5000.0,"unit":"g"}"""
        RecordType.HEAD, RecordType.CHEST, RecordType.FOOT_SIZE ->
            """{"value":35.0,"unit":"cm"}"""
        RecordType.BABY_FOOD, RecordType.SNACK, RecordType.DRINK ->
            """{"name":"米糊","amount":"1勺"}"""
        RecordType.DIARY, RecordType.BATH, RecordType.WALK, RecordType.COUGH,
        RecordType.RASH, RecordType.VOMIT, RecordType.INJURY, RecordType.HOSPITAL,
        -> """{"body":"备注"}"""
        else -> "{}"
    }

    @Test
    fun hiddenCustomItemStillAllowsFulfillOfExistingPlan() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val customId = care.addCustomItem("抚触", iconSlot = 0)
        val now = System.currentTimeMillis()
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.CUSTOM,
            scheduledAt = now + 60_000L,
            customItemId = customId,
            nowMillis = now,
        )
        // Local hide must not block fulfill of an already-created plan.
        fakes.settings.setHiddenItems(setOf("custom:$customId"))
        val recordId = care.fulfillCarePlan(
            carePlanId = planId,
            actualTimestamp = now,
            nowMillis = now + 1,
        )
        assertThat(recordId).isGreaterThan(0L)
        assertThat(care.getCarePlan(planId)!!.displayLabel()).isEqualTo("抚触")
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
    fun convertCalendarEventFailureKeepsLegacyEventAndReminder() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = System.currentTimeMillis()
        val eventId = care.addCalendarEvent(
            babyId = babyId,
            title = "疫苗",
            eventAt = now + 180_000L,
            remindAt = now + 60_000L,
        )
        assertThat(fakes.reminders.scheduledCalendarIds).contains(eventId)
        // Retired memo is not planable — conversion must fail closed and keep the event.
        val err = runCatching {
            care.convertCalendarEventToCarePlan(
                eventId = eventId,
                type = RecordType.MEMO,
                nowMillis = now,
            )
        }.exceptionOrNull()
        assertThat(err).isNotNull()
        assertThat(care.listCalendarEvents(babyId).any { it.id == eventId }).isTrue()
        assertThat(fakes.reminders.scheduledCalendarIds).contains(eventId)
        assertThat(care.getCarePlan(1L)).isNull()
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
            payloadJson = """{"left_min":0,"right_min":0,"order":"LR"}""",
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
            payloadJson = """{"left_min":10,"right_min":5,"order":"LR"}""",
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
                payloadJson = """{"left_min":1,"right_min":1,"order":"LR"}""",
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
            nowMillis = now + 1,
        )
        assertThat(care.getCarePlan(plan2)!!.status).isEqualTo(CarePlanStatus.PENDING)
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
        care.sleepUp(babyId, at = now + 30 * 60_000L)
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
    fun systemCalendarProjectionCancelsLeziReminderOnSuccess() = runTest {
        val fakes = Fakes()
        fakes.systemCalendar.permission = true
        fakes.settings.setSystemCalendarEnabled(true)
        fakes.settings.setSystemCalendarId("cal-1")
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = System.currentTimeMillis()
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.FORMULA,
            scheduledAt = now + 60_000L,
            payloadJson = """{"amount_ml":90}""",
            nowMillis = now,
        )
        assertThat(fakes.systemCalendar.upserts).isNotEmpty()
        assertThat(fakes.reminders.scheduledCarePlanIds).doesNotContain(planId)
        assertThat(fakes.reminders.cancelledCarePlanIds).contains(planId)
        assertThat(care.isCarePlanSystemCalendarUnsynced(planId)).isFalse()
    }

    @Test
    fun systemCalendarFailureFallsBackToLeziReminderWithoutRollingBackPlan() = runTest {
        val fakes = Fakes()
        fakes.systemCalendar.permission = true
        fakes.systemCalendar.failUpsert = true
        fakes.settings.setSystemCalendarEnabled(true)
        fakes.settings.setSystemCalendarId("cal-1")
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = System.currentTimeMillis()
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.PEE,
            scheduledAt = now + 60_000L,
            payloadJson = """{"pee_amount":1}""",
            nowMillis = now,
        )
        assertThat(care.getCarePlan(planId)).isNotNull()
        assertThat(fakes.reminders.scheduledCarePlanIds).contains(planId)
        assertThat(care.isCarePlanSystemCalendarUnsynced(planId)).isTrue()
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
    fun providerEventWithoutReadyReminderKeepsLeziFallbackAndUnsyncedStatus() = runTest {
        val fakes = Fakes()
        fakes.systemCalendar.permission = true
        fakes.systemCalendar.failReminder = true
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

        val entity = fakes.carePlans.get(planId)!!
        assertThat(entity.systemCalendarEventId).isNotNull()
        assertThat(entity.systemCalendarReminderReady).isFalse()
        assertThat(entity.systemCalendarProjectionPending).isFalse()
        assertThat(fakes.reminders.scheduledCarePlanIds).contains(planId)
        assertThat(care.isCarePlanSystemCalendarUnsynced(planId)).isTrue()
    }

    @Test
    fun failedProviderUpdateCannotReusePriorReminderGeneration() = runTest {
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

        fakes.systemCalendar.failUpsert = true
        fakes.reminders.scheduledCarePlanIds.clear()
        care.updateCarePlan(
            carePlanId = planId,
            scheduledAt = now + 180_000L,
            nowMillis = now + 1L,
        )

        assertThat(fakes.carePlans.get(planId)!!.systemCalendarReminderReady).isFalse()
        assertThat(fakes.reminders.scheduledCarePlanIds).contains(planId)
        assertThat(care.isCarePlanSystemCalendarUnsynced(planId)).isTrue()
    }

    @Test
    fun indeterminateStaleProviderOwnerNeverEnablesSecondLeziReminder() = runTest {
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

        fakes.systemCalendar.providerStillOwnsStaleReminder = true
        fakes.reminders.scheduledCarePlanIds.clear()
        fakes.reminders.cancelledCarePlanIds.clear()
        care.updateCarePlan(
            carePlanId = planId,
            scheduledAt = now + 180_000L,
            nowMillis = now + 1L,
        )

        val entity = fakes.carePlans.get(planId)!!
        assertThat(entity.systemCalendarReminderReady).isFalse()
        assertThat(entity.systemCalendarProjectionPending).isTrue()
        assertThat(fakes.reminders.scheduledCarePlanIds).doesNotContain(planId)
        assertThat(fakes.reminders.cancelledCarePlanIds).contains(planId)
        assertThat(care.isCarePlanSystemCalendarUnsynced(planId)).isTrue()
    }

    @Test
    fun configuredCalendarWithoutPermissionSchedulesLeziForPendingHandoff() = runTest {
        val fakes = Fakes()
        fakes.systemCalendar.permission = false
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

        assertThat(fakes.carePlans.get(planId)!!.systemCalendarProjectionPending).isFalse()
        assertThat(fakes.reminders.scheduledCarePlanIds).contains(planId)
        assertThat(fakes.reminders.cancelledCarePlanIds).doesNotContain(planId)
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
    fun perPlanCalendarRouteOnlyEditDoesNotAdvanceFamilyRevision() = runTest {
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
        val before = fakes.carePlans.get(planId)!!
        fakes.carePlans.markSynced(before.clientUuid, before.updatedAt)

        care.updateCarePlan(
            carePlanId = planId,
            scheduledAt = before.scheduledAt,
            note = before.note,
            payloadJson = before.payloadJson,
            schemaVersion = before.schemaVersion,
            projectToSystemCalendar = false,
            nowMillis = now + 1,
        )

        val after = fakes.carePlans.get(planId)!!
        assertThat(after.updatedAt).isEqualTo(before.updatedAt)
        assertThat(after.syncDirty).isFalse()
        assertThat(after.systemCalendarProjectionEnabled).isFalse()
    }

    @Test
    fun systemCalendarUnconfiguredUsesLeziReminderOnly() = runTest {
        val fakes = Fakes()
        // Enabled false / no calendar id — never project, never request permission.
        fakes.systemCalendar.permission = false
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = System.currentTimeMillis()
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.BATH,
            scheduledAt = now + 60_000L,
            nowMillis = now,
        )
        assertThat(fakes.systemCalendar.upserts).isEmpty()
        assertThat(fakes.reminders.scheduledCarePlanIds).contains(planId)
        assertThat(care.isCarePlanSystemCalendarUnsynced(planId)).isFalse()
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
    fun migratedLegacyCarePlanAlarmIsConsumedAtMostOnce() = runTest {
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
        fakes.carePlans.update(plan.copy(legacyCarePlanReminderPending = true))

        assertThat(
            care.shouldDeliverCarePlanReminder(planId, plan.clientUuid, Long.MIN_VALUE),
        ).isTrue()
        assertThat(
            care.shouldDeliverCarePlanReminder(planId, plan.clientUuid, Long.MIN_VALUE),
        ).isFalse()
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
    fun systemCalendarPermissionRevokeMarksUnsyncedWithoutRollingBackPlan() = runTest {
        val fakes = Fakes()
        fakes.systemCalendar.permission = true
        fakes.settings.setSystemCalendarEnabled(true)
        fakes.settings.setSystemCalendarId("cal-1")
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = System.currentTimeMillis()
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.FORMULA,
            scheduledAt = now + 90_000L,
            payloadJson = """{"amount_ml":90}""",
            nowMillis = now,
        )
        assertThat(care.isCarePlanSystemCalendarUnsynced(planId)).isFalse()
        fakes.systemCalendar.permission = false
        assertThat(care.isCarePlanSystemCalendarUnsynced(planId)).isTrue()
        assertThat(care.getCarePlan(planId)!!.status).isEqualTo(CarePlanStatus.PENDING)
        assertThat(SYSTEM_CALENDAR_UNSYNCED_LABEL).isEqualTo("未同步到系统日历")
    }

    @Test
    fun systemCalendarVanishedTargetOrEventMarksUnsynced() = runTest {
        val fakes = Fakes()
        fakes.systemCalendar.permission = true
        fakes.settings.setSystemCalendarEnabled(true)
        fakes.settings.setSystemCalendarId("cal-1")
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = System.currentTimeMillis()
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.PEE,
            scheduledAt = now + 90_000L,
            payloadJson = """{"pee_amount":1}""",
            nowMillis = now,
        )
        assertThat(care.isCarePlanSystemCalendarUnsynced(planId)).isFalse()
        // Target calendar disappears from provider.
        fakes.systemCalendar.writableCalendarIds = emptySet()
        assertThat(care.isCarePlanSystemCalendarUnsynced(planId)).isTrue()
        // Restore target but drop the mapped event.
        fakes.systemCalendar.writableCalendarIds = setOf("cal-1")
        val plan = care.getCarePlan(planId)!!
        val map = parseSystemCalendarEventMap(
            fakes.settings.settings.first().systemCalendarEventMapJson,
        )
        val eventId = map[plan.clientUuid]
        assertThat(eventId).isNotNull()
        fakes.systemCalendar.missingEventIds += eventId!!
        assertThat(care.isCarePlanSystemCalendarUnsynced(planId)).isTrue()
    }

    @Test
    fun systemCalendarDisclosureLevelsProjectTitleDescriptionAndDeepLink() = runTest {
        val fakes = Fakes()
        fakes.systemCalendar.permission = true
        fakes.settings.setSystemCalendarEnabled(true)
        fakes.settings.setSystemCalendarId("cal-1")
        fakes.settings.setSystemCalendarDisclosureLevel(1)
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = System.currentTimeMillis()
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.FORMULA,
            scheduledAt = now + 60_000L,
            note = "补充维D",
            payloadJson = """{"amount_ml":90}""",
            photoLocalPaths = listOf(
                "file:///data/data/com.lezi.babylog/cache/photo1.jpg",
                "content://media/external/images/media/99",
            ),
            nowMillis = now,
        )
        val l1 = fakes.systemCalendar.upserts.last()
        assertThat(l1.title).isEqualTo("乐记 · 护理计划")
        assertThat(l1.description).isNull()
        assertThat(l1.customAppUri).isNull()
        assertThat(l1.title).doesNotContain("content://")
        assertThat(l1.title).doesNotContain("file://")

        fakes.settings.setSystemCalendarDisclosureLevel(2)
        care.projectOrScheduleCarePlanReminder(care.getCarePlan(planId)!!)
        val l2 = fakes.systemCalendar.upserts.last()
        assertThat(l2.title).isEqualTo("年年 · 配方奶")
        assertThat(l2.description).isNull()
        assertThat(l2.customAppUri).isNull()

        fakes.settings.setSystemCalendarDisclosureLevel(3)
        care.projectOrScheduleCarePlanReminder(care.getCarePlan(planId)!!)
        val l3 = fakes.systemCalendar.upserts.last()
        val plan = care.getCarePlan(planId)!!
        assertThat(l3.title).isEqualTo("年年 · 配方奶")
        assertThat(l3.description).contains("补充维D")
        assertThat(l3.description).contains("照片 2 张，打开乐记查看")
        assertThat(l3.description).contains("lezi://care-plan/${plan.clientUuid}")
        assertThat(l3.customAppUri).isEqualTo("lezi://care-plan/${plan.clientUuid}")
        // Photo local paths / content URIs must never leak into calendar text.
        assertThat(l3.description).doesNotContain("content://")
        assertThat(l3.description).doesNotContain("file://")
        assertThat(l3.description).doesNotContain("/data/data/")
        assertThat(l3.description).doesNotContain("photo1.jpg")
        assertThat(fakes.reminders.scheduledCarePlanIds).doesNotContain(planId)
    }

    @Test
    fun systemCalendarDisclosureChangeReprojectsOnlyOpenFuturePlans() = runTest {
        val fakes = Fakes()
        fakes.systemCalendar.permission = true
        fakes.settings.setSystemCalendarEnabled(true)
        fakes.settings.setSystemCalendarId("cal-1")
        fakes.settings.setSystemCalendarDisclosureLevel(2)
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = System.currentTimeMillis()
        val futureId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.BATH,
            scheduledAt = now + 120_000L,
            nowMillis = now,
        )
        val pastId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.PEE,
            scheduledAt = now + 30_000L,
            payloadJson = """{"pee_amount":1}""",
            nowMillis = now,
        )
        // Move past plan into missed (scheduled before "later" now).
        care.skipCarePlan(pastId, nowMillis = now + 1)
        // Re-create a completed terminal path: fulfill is heavier; soft-delete also terminals.
        care.deleteCarePlan(pastId, nowMillis = now + 2)

        fakes.systemCalendar.upserts.clear()
        fakes.settings.setSystemCalendarDisclosureLevel(1)
        care.reprojectOpenFutureSystemCalendarCopies(nowMillis = now + 10)
        // Only the still-open future plan is reprojected.
        assertThat(fakes.systemCalendar.upserts).hasSize(1)
        assertThat(fakes.systemCalendar.upserts.single().title).isEqualTo("乐记 · 护理计划")
        val futurePlan = care.getCarePlan(futureId)!!
        assertThat(fakes.systemCalendar.upserts.single().carePlanClientUuid)
            .isEqualTo(futurePlan.clientUuid)
    }

    @Test
    fun systemCalendarExternalDeleteRebuildsEventWithoutDuplicateLeziReminder() = runTest {
        val fakes = Fakes()
        fakes.systemCalendar.permission = true
        fakes.settings.setSystemCalendarEnabled(true)
        fakes.settings.setSystemCalendarId("cal-1")
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = System.currentTimeMillis()
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.FORMULA,
            scheduledAt = now + 90_000L,
            payloadJson = """{"amount_ml":120}""",
            nowMillis = now,
        )
        val plan = care.getCarePlan(planId)!!
        val firstMap = parseSystemCalendarEventMap(
            fakes.settings.settings.first().systemCalendarEventMapJson,
        )
        val oldEventId = firstMap[plan.clientUuid]!!
        assertThat(fakes.reminders.scheduledCarePlanIds).doesNotContain(planId)

        // User deletes the event outside Lezi.
        fakes.systemCalendar.missingEventIds += oldEventId
        fakes.systemCalendar.upserts.clear()
        fakes.reminders.scheduledCarePlanIds.clear()
        fakes.reminders.cancelledCarePlanIds.clear()

        val ok = care.projectOrScheduleCarePlanReminder(plan)
        assertThat(ok).isTrue()
        // The adapter strictly detects absence and rebuilds within one upsert command.
        assertThat(fakes.systemCalendar.upserts).hasSize(1)
        assertThat(fakes.systemCalendar.upserts.single().existingEventId).isEqualTo(oldEventId)
        val newMap = parseSystemCalendarEventMap(
            fakes.settings.settings.first().systemCalendarEventMapJson,
        )
        val newEventId = newMap[plan.clientUuid]
        assertThat(newEventId).isNotNull()
        assertThat(newEventId).isNotEqualTo(oldEventId)
        // Single reminder owner remains system calendar — no Lezi schedule.
        assertThat(fakes.reminders.scheduledCarePlanIds).doesNotContain(planId)
        assertThat(fakes.reminders.cancelledCarePlanIds).contains(planId)
    }

    @Test
    fun systemCalendarRemovedOnCompleteSkipAndDelete() = runTest {
        val fakes = Fakes()
        fakes.systemCalendar.permission = true
        fakes.settings.setSystemCalendarEnabled(true)
        fakes.settings.setSystemCalendarId("cal-1")
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = System.currentTimeMillis()

        val skipId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.BATH,
            scheduledAt = now + 60_000L,
            nowMillis = now,
        )
        val skipPlan = care.getCarePlan(skipId)!!
        val skipEvent = parseSystemCalendarEventMap(
            fakes.settings.settings.first().systemCalendarEventMapJson,
        )[skipPlan.clientUuid]
        assertThat(skipEvent).isNotNull()
        care.skipCarePlan(skipId, nowMillis = now + 1)
        assertThat(fakes.systemCalendar.deleted).contains(skipEvent)
        assertThat(
            parseSystemCalendarEventMap(
                fakes.settings.settings.first().systemCalendarEventMapJson,
            ),
        ).doesNotContainKey(skipPlan.clientUuid)

        val delId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.PEE,
            scheduledAt = now + 90_000L,
            payloadJson = """{"pee_amount":1}""",
            nowMillis = now,
        )
        val delPlan = care.getCarePlan(delId)!!
        val delEvent = parseSystemCalendarEventMap(
            fakes.settings.settings.first().systemCalendarEventMapJson,
        )[delPlan.clientUuid]
        care.deleteCarePlan(delId, nowMillis = now + 2)
        assertThat(fakes.systemCalendar.deleted).contains(delEvent)
        assertThat(
            parseSystemCalendarEventMap(
                fakes.settings.settings.first().systemCalendarEventMapJson,
            ),
        ).doesNotContainKey(delPlan.clientUuid)

        val fulfillId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.FORMULA,
            scheduledAt = now + 120_000L,
            payloadJson = """{"amount_ml":90}""",
            nowMillis = now,
        )
        val fulfillPlan = care.getCarePlan(fulfillId)!!
        val fulfillEvent = parseSystemCalendarEventMap(
            fakes.settings.settings.first().systemCalendarEventMapJson,
        )[fulfillPlan.clientUuid]
        care.fulfillCarePlan(
            carePlanId = fulfillId,
            actualTimestamp = now,
            payloadJson = """{"amount_ml":90}""",
            nowMillis = now,
        )
        assertThat(fakes.systemCalendar.deleted).contains(fulfillEvent)
        assertThat(
            parseSystemCalendarEventMap(
                fakes.settings.settings.first().systemCalendarEventMapJson,
            ),
        ).doesNotContainKey(fulfillPlan.clientUuid)
    }

    @Test
    fun terminalPlanRetainsProjectionIdentityUntilProviderDeletionIsConfirmed() = runTest {
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
        val plan = care.getCarePlan(planId)!!
        val eventId = parseSystemCalendarEventMap(
            fakes.settings.settings.first().systemCalendarEventMapJson,
        ).getValue(plan.clientUuid)

        fakes.systemCalendar.permission = false
        care.skipCarePlan(planId, nowMillis = now + 1)

        assertThat(
            parseSystemCalendarEventMap(
                fakes.settings.settings.first().systemCalendarEventMapJson,
            ),
        ).containsEntry(plan.clientUuid, eventId)

        fakes.systemCalendar.permission = true
        care.rescheduleCarePlanReminders(nowMillis = now + 2)

        assertThat(fakes.systemCalendar.deleted).contains(eventId)
        assertThat(
            parseSystemCalendarEventMap(
                fakes.settings.settings.first().systemCalendarEventMapJson,
            ),
        ).doesNotContainKey(plan.clientUuid)
    }

    @Test
    fun processRestartRemovesOldFutureProjectionAfterPlanMovesToMissed() = runTest {
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
        val beforeCrash = fakes.carePlans.get(planId)!!
        val eventId = beforeCrash.systemCalendarEventId!!

        // Simulate process death after the Room edit committed but before provider I/O.
        fakes.carePlans.update(
            beforeCrash.copy(
                scheduledAt = now - 1L,
                systemCalendarReminderReady = false,
            ),
        )
        fakes.systemCalendar.deleted.clear()
        fakes.reminders.scheduledCarePlanIds.clear()

        care.rescheduleCarePlanReminders(nowMillis = now)

        assertThat(fakes.systemCalendar.deleted).contains(eventId)
        assertThat(fakes.reminders.scheduledCarePlanIds).doesNotContain(planId)
        assertThat(fakes.reminders.cancelledCarePlanIds).contains(planId)
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
    fun convertCalendarEventToNursingCarePlanSucceeds() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val now = System.currentTimeMillis()
        val eventId = care.addCalendarEvent(
            babyId = babyId,
            title = "喂奶",
            eventAt = now + 180_000L,
            remindAt = now + 60_000L,
        )
        val planId = care.convertCalendarEventToCarePlan(
            eventId = eventId,
            type = RecordType.NURSING,
            nowMillis = now,
        )
        assertThat(planId).isGreaterThan(0L)
        assertThat(care.getCarePlan(planId)!!.type).isEqualTo(RecordType.NURSING)
        assertThat(care.listCalendarEvents(babyId).none { it.id == eventId }).isTrue()
        assertThat(
            fakes.records.listAllIncludingDeleted().none { it.type == RecordType.NURSING.key },
        ).isTrue()
    }

    @Test
    fun convertRecordToCarePlanTransfersFieldsPhotosAndTombstonesRecord() = runTest {
        val fakes = Fakes()
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
            payloadJson = """{"left_min":5,"right_min":4,"order":"LR"}""",
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
            payloadJson = """{"is_nap":true}""",
        )
        assertThat(fakes.records.findOpenSleep(babyId)?.id).isEqualTo(openSleepId)
        val planId = care.convertRecordToCarePlan(
            recordId = openSleepId,
            scheduledAt = now + 90_000L,
            payloadJson = """{"is_nap":true}""",
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

}
private class Fakes(
    private val syncPort: com.lezi.babylog.sync.SyncPort =
        com.lezi.babylog.sync.NoOpSyncPort(),
) {
    val users = FakeLocalUserDao()
    val families = FakeFamilyDao()
    val memberships = FakeMembershipDao()
    val babies = FakeBabyDao()
    val fulfillmentCandidates = FakeFulfillmentCandidateDao()
    val records = FakeRecordDao(
        conflictExcluded = {
            fulfillmentCandidates.itemsSnapshot()
                .filter {
                    it.adoptionStatus ==
                        com.lezi.babylog.core.model.FulfillmentAdoptionStatus.CONFLICT_NOT_ADOPTED &&
                        it.deletedAt == null
                }
                .map { it.recordClientUuid }
                .toSet()
        },
    )
    val carePlans = FakeCarePlanDao()
    val calendarEvents = FakeCalendarEventDao()
    val customItems = FakeCustomItemDao()
    val media = FakeMediaAssetDao()
    val pendingReminderCleanup = FakePendingReminderCleanupStore()
    val settings = FakeSettingsStore()
    val reminders = FakeReminderCleanupPort()
    val systemCalendar = FakeSystemCalendarPort()
    val transactions = RecordingTransactionRunner()
    val calendarReminderMutationGuard = CalendarReminderMutationGuard()

    fun wireTransactionalSnapshots() {
        transactions.onBegin += {
            records.beginTx()
            media.beginTx()
            carePlans.beginTx()
            fulfillmentCandidates.beginTx()
        }
        transactions.onCommit += {
            records.commitTx()
            media.commitTx()
            carePlans.commitTx()
            fulfillmentCandidates.commitTx()
        }
        transactions.onRollback += {
            records.rollbackTx()
            media.rollbackTx()
            carePlans.rollbackTx()
            fulfillmentCandidates.rollbackTx()
        }
    }

    fun localDataClearCoordinator(
        sync: com.lezi.babylog.sync.SyncPort = syncPort,
    ): LocalDataClearCoordinator = DefaultLocalDataClearCoordinator(
        persistence = DaoLocalDataClearPersistence(
            babyDao = babies,
            recordDao = records,
            carePlanDao = carePlans,
            calendarEventDao = calendarEvents,
            customItemDao = customItems,
            localUserDao = users,
            familyDao = families,
            membershipDao = memberships,
            fulfillmentCandidateDao = fulfillmentCandidates,
            pendingReminderCleanupStore = pendingReminderCleanup,
            transactionRunner = transactions,
        ),
        settings = StoreLocalDataClearSettings(settings),
        syncPort = sync,
        reminderCleanup = reminders,
        systemCalendar = systemCalendar,
        pendingReminderCleanupStore = pendingReminderCleanup,
        mutationGuard = calendarReminderMutationGuard,
    )

    fun careLog() = CareLog(
        babies,
        records,
        carePlans,
        calendarEvents,
        customItems,
        users,
        families,
        memberships,
        media,
        settings,
        syncPort,
        reminders,
        transactions,
        systemCalendar,
        fulfillmentCandidates,
        localDataClearCoordinator(),
        calendarReminderMutationGuard,
    )
}

private class FakeSystemCalendarPort : SystemCalendarPort {
    var permission = false
    var failUpsert = false
    var failReminder = false
    var providerStillOwnsStaleReminder = false
    /** When non-null, only these calendar ids are writable (simulates vanished target). */
    var writableCalendarIds: Set<String>? = null
    /** Event ids that no longer exist in the provider (external delete). */
    val missingEventIds = mutableSetOf<String>()
    val upserts = mutableListOf<SystemCalendarUpsert>()
    val deleted = mutableListOf<String>()
    private var nextEventId = 1L
    private val liveEventIds = mutableSetOf<String>()
    private val eventOwners = mutableMapOf<String, String>()

    override fun hasCalendarPermission(): Boolean = permission

    override suspend fun listWritableCalendars(): List<SystemCalendarTarget> =
        if (!permission) {
            emptyList()
        } else {
            val ids = writableCalendarIds ?: setOf("cal-1")
            ids.map { id ->
                SystemCalendarTarget(
                    calendarId = id,
                    displayName = "本地",
                    accountName = "local",
                )
            }
        }

    override suspend fun upsertEvent(
        request: SystemCalendarUpsert,
    ): SystemCalendarUpsertResult {
        upserts += request
        if (providerStillOwnsStaleReminder) {
            return SystemCalendarUpsertResult(
                eventId = request.existingEventId,
                outcome = SystemCalendarUpsertOutcome.ProviderStillOwnsStale,
            )
        }
        if (!permission || failUpsert || !isWritableCalendar(request.calendarId)) {
            return SystemCalendarUpsertResult(
                eventId = request.existingEventId,
                outcome = SystemCalendarUpsertOutcome.ReleasedOrAbsent,
            )
        }
        val existing = request.existingEventId
        if (!existing.isNullOrBlank()) {
            if (
                existing !in missingEventIds &&
                existing in liveEventIds &&
                eventOwners[existing] == request.carePlanClientUuid
            ) {
                return SystemCalendarUpsertResult(existing, reminderReady = !failReminder)
            }
        }
        val id = "evt-${nextEventId++}"
        liveEventIds += id
        eventOwners[id] = request.carePlanClientUuid
        missingEventIds.remove(id)
        return SystemCalendarUpsertResult(id, reminderReady = !failReminder)
    }

    override suspend fun findOwnedEvent(
        carePlanClientUuid: String,
    ): SystemCalendarOwnedEventLookup {
        if (!permission) return SystemCalendarOwnedEventLookup.Unavailable
        val ids = liveEventIds.filterTo(linkedSetOf()) {
            it !in missingEventIds && eventOwners[it] == carePlanClientUuid
        }
        return if (ids.isEmpty()) {
            SystemCalendarOwnedEventLookup.Absent
        } else {
            SystemCalendarOwnedEventLookup.Found(ids)
        }
    }

    override suspend fun deleteEvent(
        eventId: String,
        carePlanClientUuid: String?,
    ): Boolean {
        deleted += eventId
        if (!permission) return false
        if (carePlanClientUuid != null && eventOwners[eventId] != carePlanClientUuid) return false
        liveEventIds.remove(eventId)
        eventOwners.remove(eventId)
        return true
    }

    override suspend fun eventState(
        eventId: String,
        carePlanClientUuid: String?,
    ): SystemCalendarEventState = when {
        !permission -> SystemCalendarEventState.UNAVAILABLE
        eventId in missingEventIds -> SystemCalendarEventState.ABSENT
        eventId in liveEventIds &&
            (carePlanClientUuid == null || eventOwners[eventId] == carePlanClientUuid) ->
            SystemCalendarEventState.PRESENT
        else -> SystemCalendarEventState.ABSENT
    }

    override suspend fun isWritableCalendar(calendarId: String): Boolean =
        permission && listWritableCalendars().any { it.calendarId == calendarId }
}


private class FakeReminderCleanupPort : ReminderCleanupPort {
    var nextFeedScheduled: Boolean = false
        private set
    val scheduledCalendarIds = linkedSetOf<Long>()
    val recordClearBatches = mutableListOf<List<Long>>()
    var scheduleEntered: CompletableDeferred<Unit>? = null
    var allowSchedule: CompletableDeferred<Unit>? = null

    fun scheduleNextFeed() {
        nextFeedScheduled = true
    }

    fun scheduleCalendar(vararg eventIds: Long) {
        scheduledCalendarIds += eventIds.toList()
    }

    override suspend fun scheduleCalendar(event: CalendarEvent): Boolean {
        if (event.remindAt == null) return false
        scheduleEntered?.complete(Unit)
        allowSchedule?.await()
        scheduledCalendarIds += event.id
        return true
    }

    override suspend fun cancelCalendar(eventId: Long) {
        scheduledCalendarIds -= eventId
    }

    val scheduledCarePlanIds = linkedSetOf<Long>()
    val cancelledCarePlanIds = mutableListOf<Long>()
    var carePlanScheduleEnabled: Boolean = true
    var carePlanPermissionGranted: Boolean = true
    var carePlanScheduleFailure: Throwable? = null
    var carePlanCancelFailure: Throwable? = null

    override suspend fun scheduleCarePlan(plan: CarePlan): Boolean {
        carePlanScheduleFailure?.let { throw it }
        if (!carePlanScheduleEnabled || !carePlanPermissionGranted) return false
        if (plan.deletedAt != null) return false
        // Domain already gates future scheduledAt via nowMillis. Tests inject
        // synthetic epochs, so do not re-check wall-clock here (real adapter does).
        if (plan.status != CarePlanStatus.PENDING) return false
        scheduledCarePlanIds += plan.id
        // Replace semantics: a later schedule supersedes prior cancel bookkeeping.
        cancelledCarePlanIds.removeAll { it == plan.id }
        return true
    }

    override suspend fun cancelCarePlan(carePlanId: Long) {
        carePlanCancelFailure?.let { throw it }
        scheduledCarePlanIds -= carePlanId
        cancelledCarePlanIds += carePlanId
    }

    override suspend fun cancelCarePlanByClientUuid(clientUuid: String) {
        // Test adapter keys by local id only.
    }

    override suspend fun cancelForRecordsClear(
        calendarEventIds: Collection<Long>,
        cancelNextFeed: Boolean,
    ) {
        recordClearBatches += calendarEventIds.toList()
        if (cancelNextFeed) nextFeedScheduled = false
        scheduledCalendarIds.removeAll(calendarEventIds.toSet())
    }

    override suspend fun cancelForBabyDelete(calendarEventIds: Collection<Long>) {
        scheduledCalendarIds.removeAll(calendarEventIds.toSet())
    }
}

private class FakePendingReminderCleanupStore : PendingReminderCleanupStore {
    var pending: PendingReminderCleanup? = null
    var loadFailure: Throwable? = null
    var deleteCount: Int = 0

    override suspend fun load(
        operation: PendingReminderCleanupOperation,
    ): PendingReminderCleanup? {
        loadFailure?.let { throw it }
        return pending?.takeIf { it.operation == operation }
    }

    override suspend fun upsert(pending: PendingReminderCleanup) {
        val existing = this.pending?.takeIf { it.operation == pending.operation }
        this.pending = pending.copy(
            calendarEventIds = existing?.calendarEventIds.orEmpty() + pending.calendarEventIds,
            carePlanIds = existing?.carePlanIds.orEmpty() + pending.carePlanIds,
            systemCalendarProjections =
                existing?.systemCalendarProjections.orEmpty() +
                    pending.systemCalendarProjections,
            familyServerRetained =
                existing?.familyServerRetained == true || pending.familyServerRetained,
        )
    }

    override suspend fun delete(operation: PendingReminderCleanupOperation) {
        deleteCount += 1
        if (pending?.operation == operation) pending = null
    }
}

private class RecordingTransactionRunner :
    com.lezi.babylog.core.database.DatabaseTransactionRunner {
    var runCount = 0
    val onBegin = mutableListOf<() -> Unit>()
    val onCommit = mutableListOf<() -> Unit>()
    val onRollback = mutableListOf<() -> Unit>()

    override suspend fun <T> run(block: suspend () -> T): T {
        runCount += 1
        onBegin.forEach { it() }
        return try {
            val result = block()
            onCommit.forEach { it() }
            result
        } catch (t: Throwable) {
            onRollback.forEach { it() }
            throw t
        }
    }
}

private class RecordingSyncPort(
    delegate: com.lezi.babylog.sync.SyncPort = com.lezi.babylog.sync.NoOpSyncPort(),
    private val deviceId: String = "",
    private val familyId: String = "",
    private val membershipId: String = "",
    private val role: com.lezi.babylog.sync.FamilyRole = com.lezi.babylog.sync.FamilyRole.None,
    pendingCreatorAcknowledgements: Set<com.lezi.babylog.sync.CreatorAcknowledgementRef> =
        emptySet(),
) : com.lezi.babylog.sync.SyncPort by delegate {
    var requests = 0
    var localRecordReconciliations = 0
    var fullLocalWipes = 0

    private val sessionState = MutableStateFlow(
        com.lezi.babylog.sync.SyncSession(
            familyId = familyId,
            deviceId = deviceId,
            membershipId = membershipId,
            role = role,
            pendingCreatorAcknowledgements = pendingCreatorAcknowledgements,
        ),
    )

    override fun session(): Flow<com.lezi.babylog.sync.SyncSession> = sessionState

    fun currentSession(): com.lezi.babylog.sync.SyncSession = sessionState.value

    fun replaceSession(session: com.lezi.babylog.sync.SyncSession) {
        sessionState.value = session
    }

    override fun requestSync(trigger: com.lezi.babylog.sync.SyncTrigger) {
        requests++
    }

    override suspend fun clearLocalRecords(
        workflow: com.lezi.babylog.sync.LocalClearWorkflow,
    ): Result<Unit> {
        localRecordReconciliations++
        workflow.withLocalExclusion {
            workflow.clearRoom()
            workflow.finishCommitted()
        }
        return Result.success(Unit)
    }

    override suspend fun clearAllLocalData(
        workflow: com.lezi.babylog.sync.LocalClearWorkflow,
    ): Result<Unit> {
        fullLocalWipes++
        workflow.withLocalExclusion {
            workflow.clearRoom()
            workflow.finishCommitted()
        }
        return Result.success(Unit)
    }
}

private class FakeMediaAssetDao : MediaAssetDao {
    private val items = mutableListOf<MediaAssetEntity>()
    private val seq = AtomicLong(1)
    var failUpserts: Boolean = false
    private var txSnapshot: List<MediaAssetEntity>? = null
    private var txSeq: Long? = null

    fun beginTx() {
        txSnapshot = items.toList()
        txSeq = seq.get()
    }

    fun commitTx() {
        txSnapshot = null
        txSeq = null
    }

    fun rollbackTx() {
        txSnapshot?.let {
            items.clear()
            items += it
        }
        txSeq?.let { seq.set(it) }
        txSnapshot = null
        txSeq = null
    }

    fun seed(entity: MediaAssetEntity): Long {
        val id = entity.id.takeIf { it != 0L } ?: seq.getAndIncrement()
        items.removeAll { it.id == id }
        items += entity.copy(id = id)
        return id
    }

    override suspend fun upsert(asset: MediaAssetEntity): Long {
        if (failUpserts) {
            throw IllegalStateException("media upsert failed")
        }
        return seed(asset)
    }

    override suspend fun listForRecord(recordId: Long): List<MediaAssetEntity> =
        items.filter { it.recordId == recordId }

    override suspend fun listActiveForRecord(recordId: Long): List<MediaAssetEntity> =
        items.filter { it.recordId == recordId && it.deletedAt == null }.sortedBy { it.id }

    override suspend fun listForCarePlan(carePlanId: Long): List<MediaAssetEntity> =
        items.filter { it.carePlanId == carePlanId }

    override suspend fun listActiveForCarePlan(carePlanId: Long): List<MediaAssetEntity> =
        items.filter { it.carePlanId == carePlanId && it.deletedAt == null }.sortedBy { it.id }

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

    override suspend fun deleteByClientUuids(clientUuids: List<String>) {
        items.removeAll { it.clientUuid in clientUuids }
    }

    override suspend fun deleteForRecord(recordId: Long) {
        items.removeAll { it.recordId == recordId }
    }

    override suspend fun deleteAll() {
        items.clear()
    }
}

private class FakeFulfillmentCandidateDao : FulfillmentCandidateDao {
    private val items = MutableStateFlow<List<FulfillmentCandidateEntity>>(emptyList())
    private val seq = AtomicLong(1)
    private var snapshot: List<FulfillmentCandidateEntity>? = null

    fun itemsSnapshot(): List<FulfillmentCandidateEntity> = items.value

    fun beginTx() {
        snapshot = items.value
    }

    fun commitTx() {
        snapshot = null
    }

    fun rollbackTx() {
        snapshot?.let { items.value = it }
        snapshot = null
    }

    override suspend fun get(id: Long): FulfillmentCandidateEntity? =
        items.value.firstOrNull { it.id == id }

    override suspend fun getByClientUuid(clientUuid: String): FulfillmentCandidateEntity? =
        items.value.firstOrNull { it.clientUuid == clientUuid }

    override suspend fun listForCarePlan(carePlanClientUuid: String): List<FulfillmentCandidateEntity> =
        items.value.filter { it.carePlanClientUuid == carePlanClientUuid }.sortedBy { it.id }

    override suspend fun listForRecord(recordClientUuid: String): List<FulfillmentCandidateEntity> =
        items.value.filter { it.recordClientUuid == recordClientUuid }.sortedBy { it.id }

    override suspend fun listAllIncludingDeleted(): List<FulfillmentCandidateEntity> = items.value

    override suspend fun listPendingSync(): List<FulfillmentCandidateEntity> =
        items.value.filter { it.syncDirty }.sortedBy { it.id }

    override suspend fun listConflictNotAdoptedRecordUuids(): List<String> =
        items.value
            .filter {
                it.adoptionStatus ==
                    com.lezi.babylog.core.model.FulfillmentAdoptionStatus.CONFLICT_NOT_ADOPTED &&
                    it.deletedAt == null
            }
            .map { it.recordClientUuid }

    override suspend fun listConflictNotAdopted(): List<FulfillmentCandidateEntity> =
        items.value
            .filter {
                it.adoptionStatus ==
                    com.lezi.babylog.core.model.FulfillmentAdoptionStatus.CONFLICT_NOT_ADOPTED &&
                    it.deletedAt == null
            }
            .sortedWith(compareBy({ it.confirmedAt }, { it.clientUuid }))

    override suspend fun listConflictNotAdoptedForCarePlan(
        carePlanClientUuid: String,
    ): List<FulfillmentCandidateEntity> =
        listConflictNotAdopted().filter { it.carePlanClientUuid == carePlanClientUuid }

    override fun observeConflictNotAdoptedRecordUuids(): Flow<List<String>> =
        items.map { list ->
            list.filter {
                it.adoptionStatus ==
                    com.lezi.babylog.core.model.FulfillmentAdoptionStatus.CONFLICT_NOT_ADOPTED &&
                    it.deletedAt == null
            }.map { it.recordClientUuid }
        }

    override suspend fun markSynced(clientUuid: String, updatedAt: Long) {
        items.value = items.value.map {
            if (it.clientUuid == clientUuid && it.updatedAt == updatedAt) {
                it.copy(syncDirty = false)
            } else {
                it
            }
        }
    }

    override suspend fun upsert(candidate: FulfillmentCandidateEntity): Long {
        val id = candidate.id.takeIf { it > 0 } ?: seq.getAndIncrement()
        items.value = items.value.filterNot { it.id == id || it.clientUuid == candidate.clientUuid } +
            candidate.copy(id = id)
        return id
    }

    override suspend fun update(candidate: FulfillmentCandidateEntity) {
        items.value = items.value.map { if (it.id == candidate.id) candidate else it }
    }

    override suspend fun deleteAll() {
        items.value = emptyList()
    }
}

private class FakeCarePlanDao : CarePlanDao {
    private val items = MutableStateFlow<List<CarePlanEntity>>(emptyList())
    private val seq = AtomicLong(1)
    private var snapshot: List<CarePlanEntity>? = null

    fun beginTx() {
        snapshot = items.value
    }

    fun commitTx() {
        snapshot = null
    }

    fun rollbackTx() {
        snapshot?.let { items.value = it }
        snapshot = null
    }

    override fun observeDayPending(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): Flow<List<CarePlanEntity>> = items.map { list ->
        list.filter {
            it.babyId == babyId &&
                it.deletedAt == null &&
                it.status in setOf("pending", "missed") &&
                it.scheduledAt >= startInclusive &&
                it.scheduledAt < endExclusive
        }.sortedBy { it.scheduledAt }
    }

    override fun observeTodayPending(
        babyId: Long,
        dayStart: Long,
        dayEnd: Long,
        nowMillis: Long,
    ): Flow<List<CarePlanEntity>> = items.map { list ->
        list.filter {
            it.babyId == babyId &&
                it.deletedAt == null &&
                it.status in setOf("pending", "missed") &&
                (it.scheduledAt < nowMillis ||
                    (it.scheduledAt >= dayStart && it.scheduledAt < dayEnd))
        }.sortedBy { it.scheduledAt }
    }

    override fun observeRange(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): Flow<List<CarePlanEntity>> = items.map { list ->
        list.filter {
            it.babyId == babyId &&
                it.deletedAt == null &&
                it.scheduledAt >= startInclusive &&
                it.scheduledAt < endExclusive
        }.sortedBy { it.scheduledAt }
    }

    override suspend fun listOpenFuture(babyId: Long, nowMillis: Long): List<CarePlanEntity> =
        items.value.filter {
            it.babyId == babyId &&
                it.deletedAt == null &&
                it.status in setOf("pending", "missed") &&
                it.scheduledAt > nowMillis
        }.sortedBy { it.scheduledAt }

    override suspend fun listAllOpenFuture(nowMillis: Long): List<CarePlanEntity> =
        items.value.filter {
            it.deletedAt == null &&
                it.status in setOf("pending", "missed") &&
                it.scheduledAt > nowMillis
        }.sortedBy { it.scheduledAt }

    override suspend fun get(id: Long): CarePlanEntity? =
        items.value.firstOrNull { it.id == id }

    override suspend fun getByClientUuid(clientUuid: String): CarePlanEntity? =
        items.value.firstOrNull { it.clientUuid == clientUuid }

    /** Test-only: includes tombstones for convert failure rollback assertions. */
    override suspend fun listAllIncludingDeleted(): List<CarePlanEntity> = items.value

    override suspend fun listPendingSync(): List<CarePlanEntity> =
        items.value.filter { it.syncDirty }.sortedBy { it.id }

    override suspend fun markSynced(clientUuid: String, updatedAt: Long) {
        items.value = items.value.map {
            if (it.clientUuid == clientUuid && it.updatedAt == updatedAt) {
                it.copy(syncDirty = false)
            } else {
                it
            }
        }
    }

    override suspend fun markAllPendingSync() {
        items.value = items.value.map { it.copy(syncDirty = true) }
    }

    override suspend fun upsert(plan: CarePlanEntity): Long {
        val id = plan.id.takeIf { it > 0 } ?: seq.getAndIncrement()
        items.value = items.value.filterNot { it.id == id } + plan.copy(id = id)
        return id
    }

    override suspend fun update(plan: CarePlanEntity) {
        items.value = items.value.map { if (it.id == plan.id) plan else it }
    }

    override suspend fun updateSystemCalendarProjection(
        clientUuid: String,
        eventId: String?,
        reminderReady: Boolean,
        pending: Boolean,
    ) {
        items.value = items.value.map {
            if (it.clientUuid == clientUuid) {
                it.copy(
                    systemCalendarEventId = eventId,
                    systemCalendarReminderReady = reminderReady,
                    systemCalendarProjectionPending = pending,
                )
            } else {
                it
            }
        }
    }

    override suspend fun updateSystemCalendarProjectionEnabled(
        clientUuid: String,
        enabled: Boolean,
    ) {
        items.value = items.value.map {
            if (it.clientUuid == clientUuid) {
                it.copy(
                    systemCalendarProjectionEnabled = enabled,
                )
            } else {
                it
            }
        }
    }

    override suspend fun markLegacyCarePlanReminderReplaced(clientUuid: String) {
        items.value = items.value.map {
            if (it.clientUuid == clientUuid) it.copy(legacyCarePlanReminderPending = false) else it
        }
    }

    override suspend fun consumeLegacyCarePlanReminder(id: Long, clientUuid: String): Int {
        var changed = 0
        items.value = items.value.map {
            if (it.id == id && it.clientUuid == clientUuid && it.legacyCarePlanReminderPending) {
                changed = 1
                it.copy(legacyCarePlanReminderPending = false)
            } else {
                it
            }
        }
        return changed
    }

    override suspend fun updatePayloadReplica(
        id: Long,
        expectedPayloadJson: String,
        payloadJson: String,
    ): Int {
        var changed = 0
        items.value = items.value.map {
            if (it.id == id && it.payloadJson == expectedPayloadJson) {
                changed = 1
                it.copy(payloadJson = payloadJson)
            } else {
                it
            }
        }
        return changed
    }

    override suspend fun softDelete(id: Long, deletedAt: Long) {
        items.value = items.value.map {
            if (it.id == id) {
                it.copy(deletedAt = deletedAt, updatedAt = deletedAt, syncDirty = true)
            } else {
                it
            }
        }
    }

    override suspend fun deleteAll() {
        items.value = emptyList()
    }
}

private class FakeCustomItemDao : CustomItemDao {
    private val items = MutableStateFlow<List<CustomItemEntity>>(emptyList())
    private val seq = AtomicLong(1)

    override fun observeAll(): Flow<List<CustomItemEntity>> =
        items.map { list -> list.filter { it.deletedAt == null } }

    override suspend fun listAll(): List<CustomItemEntity> =
        items.value.filter { it.deletedAt == null }

    override suspend fun listAllIncludingDeleted(): List<CustomItemEntity> = items.value

    override suspend fun getById(id: Long): CustomItemEntity? =
        items.value.firstOrNull { it.id == id }

    override suspend fun getByClientUuid(clientUuid: String): CustomItemEntity? =
        items.value.firstOrNull { it.clientUuid == clientUuid }

    override suspend fun listPendingSync(): List<CustomItemEntity> =
        items.value.filter { it.syncDirty }

    override suspend fun markSynced(clientUuid: String, updatedAt: Long) {
        items.value = items.value.map {
            if (it.clientUuid == clientUuid && it.updatedAt == updatedAt) {
                it.copy(syncDirty = false)
            } else {
                it
            }
        }
    }

    override suspend fun upsert(item: CustomItemEntity): Long {
        val id = item.id.takeIf { it > 0 } ?: seq.getAndIncrement()
        items.value = items.value.filterNot { it.id == id } + item.copy(id = id)
        return id
    }

    override suspend fun update(item: CustomItemEntity) {
        items.value = items.value.map { if (it.id == item.id) item else it }
    }

    override suspend fun softDelete(id: Long, deletedAt: Long) {
        // Keep tombstone in memory (include-deleted semantics for leave/ownership tests).
        items.value = items.value.map {
            if (it.id == id) {
                it.copy(deletedAt = deletedAt, updatedAt = deletedAt, syncDirty = true)
            } else {
                it
            }
        }
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
    private var nextFeedEpoch = 0L
    private val dark = MutableStateFlow("system")
    private val step = MutableStateFlow(5)
    private val timerEnabled = MutableStateFlow(true)
    private val interval = MutableStateFlow(180)
    private val recordAt = MutableStateFlow("end")
    private val order = MutableStateFlow("[]")
    private val hidden = MutableStateFlow(emptySet<String>())
    private val weekStart = MutableStateFlow(1)
    private val systemCalEnabled = MutableStateFlow(false)
    private val carePlanLocalReminders = MutableStateFlow(true)
    private val systemCalId = MutableStateFlow<String?>(null)
    private val systemCalDisclosure = MutableStateFlow(2)
    private val systemCalMap = MutableStateFlow("{}")
    private val showAvg = MutableStateFlow(false)
    private val comparePrev = MutableStateFlow(false)

    private fun snapshot(): SettingsLocal = SettingsLocal(
        darkMode = dark.value,
        amountStepMl = step.value,
        timerEnabled = timerEnabled.value,
        nursingIntervalMin = interval.value,
        recordAtStartOrEnd = recordAt.value,
        nextFeedAt = nextFeed.value,
        nextFeedEpoch = nextFeedEpoch.toString(),
        itemOrderJson = order.value,
        hiddenItems = hidden.value,
        weekStart = weekStart.value,
        systemCalendarEnabled = systemCalEnabled.value,
        carePlanLocalRemindersEnabled = carePlanLocalReminders.value,
        systemCalendarId = systemCalId.value,
        systemCalendarDisclosureLevel = systemCalDisclosure.value,
        systemCalendarEventMapJson = systemCalMap.value,
    )

    private val settingsState = MutableStateFlow(snapshot())

    private fun publish() {
        settingsState.value = snapshot()
    }

    override val settings: Flow<SettingsLocal> = settingsState

    override val currentBabyId: Flow<Long?> = babyId
    override val nursingTimerJson: Flow<String?> = timer

    override suspend fun setCurrentBabyId(id: Long?) {
        babyId.value = id
    }

    override suspend fun setDarkMode(mode: String) {
        dark.value = mode
        publish()
    }

    override suspend fun setVisualStyle(style: String) = Unit
    override suspend fun setPreferredHand(hand: String) = Unit

    override suspend fun setTimerEnabled(enabled: Boolean) {
        timerEnabled.value = enabled
        publish()
    }

    override suspend fun setAmountStepMl(stepMl: Int) {
        step.value = stepMl
        publish()
    }

    override suspend fun setTimeStepMin(step: Int) = Unit

    override suspend fun setTimePickerStyle(style: String) = Unit
    override suspend fun setInfantFeverAdviceEnabled(enabled: Boolean) = Unit

    override suspend fun setNursingIntervalMin(min: Int) {
        interval.value = min
        publish()
    }

    override suspend fun setRecordAt(startOrEnd: String) {
        recordAt.value = startOrEnd
        publish()
    }

    override suspend fun setNextFeedAt(epochMs: Long?): String {
        nextFeed.value = epochMs
        nextFeedEpoch += 1L
        publish()
        return nextFeedEpoch.toString()
    }

    override suspend fun clearNextFeedAt() {
        setNextFeedAt(null)
    }

    override suspend fun clearNextFeedAtIfEpoch(expectedEpoch: String): Boolean {
        if (nextFeed.value == null || expectedEpoch != nextFeedEpoch.toString()) return false
        clearNextFeedAt()
        return true
    }

    override suspend fun clearLegacyNextFeedAtIfEpochMissing(): Boolean = false

    override suspend fun setItemOrderJson(json: String) {
        order.value = json
        publish()
    }

    override suspend fun setCategoryOrderJson(json: String) = Unit

    override suspend fun setHiddenItems(items: Set<String>) {
        hidden.value = items
        publish()
    }

    override suspend fun setQuickRecordSlots(slots: List<String>) = Unit

    override suspend fun setTimelineOrder(order: String) = Unit

    override suspend fun setNursingTimerJson(json: String?) {
        timer.value = json
    }

    override suspend fun setWeekStart(day: Int) {
        weekStart.value = day
        publish()
    }

    override val showAvgSleep = showAvg
    override val comparePrevWeek = comparePrev
    override suspend fun setShowAvgSleep(enabled: Boolean) {
        showAvg.value = enabled
    }
    override suspend fun setCarePlanLocalRemindersEnabled(enabled: Boolean) {
        carePlanLocalReminders.value = enabled
        publish()
    }

    override suspend fun setSystemCalendarEnabled(enabled: Boolean) {
        systemCalEnabled.value = enabled
        publish()
    }

    override suspend fun setSystemCalendarId(calendarId: String?) {
        systemCalId.value = calendarId
        publish()
    }

    override suspend fun setSystemCalendarDisclosureLevel(level: Int) {
        systemCalDisclosure.value = level
        publish()
    }

    override suspend fun setSystemCalendarEventMapJson(json: String) {
        systemCalMap.value = json
        publish()
    }

    override suspend fun captureLocalClearSettings(): LocalClearSettingsSnapshot =
        LocalClearSettingsSnapshot(
            currentBabyId = babyId.value,
            nextFeedAt = nextFeed.value,
            systemCalendarProjections = parseSystemCalendarEventMap(systemCalMap.value),
            nextFeedEpoch = nextFeedEpoch.toString(),
        )

    override suspend fun finishLocalClearSettings(
        snapshot: LocalClearSettingsSnapshot,
        clearCurrentBabyId: Boolean,
    ): LocalClearSettingsFinish {
        if (clearCurrentBabyId && babyId.value == snapshot.currentBabyId) {
            babyId.value = null
        }
        val cancelNextFeedAlarm = snapshot.nextFeedEpoch == nextFeedEpoch.toString()
        if (cancelNextFeedAlarm) {
            nextFeed.value = null
        }
        val retained = parseSystemCalendarEventMap(systemCalMap.value)
            .filter { (clientUuid, eventId) ->
                snapshot.systemCalendarProjections[clientUuid] != eventId
            }
        systemCalMap.value = encodeSystemCalendarEventMap(retained)
        publish()
        return LocalClearSettingsFinish(cancelNextFeedAlarm)
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

private class FakeRecordDao(
    private val conflictExcluded: () -> Set<String> = { emptySet() },
) : RecordDao {
    private val items = MutableStateFlow<List<RecordEntity>>(emptyList())
    private val seq = AtomicLong(1)
    private var txSnapshot: List<RecordEntity>? = null
    private var txSeq: Long? = null

    private fun RecordEntity.isOrdinarySurface(): Boolean =
        clientUuid !in conflictExcluded()

    fun beginTx() {
        txSnapshot = items.value
        txSeq = seq.get()
    }

    fun commitTx() {
        txSnapshot = null
        txSeq = null
    }

    fun rollbackTx() {
        txSnapshot?.let { items.value = it }
        txSeq?.let { seq.set(it) }
        txSnapshot = null
        txSeq = null
    }

    override fun observeRange(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): Flow<List<RecordEntity>> =
        items.map { list ->
            list.filter {
                it.babyId == babyId &&
                    it.deletedAt == null &&
                    it.isOrdinarySurface() &&
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
                    it.isOrdinarySurface() &&
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
                it.isOrdinarySurface() &&
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

    override suspend fun mergeCanonicalAuthor(
        clientUuid: String,
        expectedUpdatedAt: Long,
        membershipId: String,
    ): Int {
        var changed = 0
        items.update { values ->
            values.map {
                if (
                    it.clientUuid == clientUuid &&
                    it.updatedAt == expectedUpdatedAt &&
                    membershipId.isNotBlank()
                ) {
                    changed = 1
                    it.copy(createdByMembershipId = membershipId)
                } else {
                    it
                }
            }
        }
        return changed
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
        items.value.filter {
            it.babyId == babyId && it.deletedAt == null && it.isOrdinarySurface()
        }.sortedByDescending { it.timestamp }

    override suspend fun searchCandidates(
        babyId: Long,
        escapedPattern: String,
        matchingTypeKeys: List<String>,
    ): List<RecordEntity> {
        // Mirror Room `LIKE :escapedPattern ESCAPE '\'` (see SqlLikeEscaped +
        // RecordSearchTest) so tests exercise user metacharacter handling rather
        // than a weaker contains() approximation.
        return items.value.filter {
            it.babyId == babyId &&
                it.deletedAt == null &&
                it.isOrdinarySurface() &&
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
                it.isOrdinarySurface() &&
                it.overlapsRange(startInclusive, endExclusive)
        }.sortedBy { it.timestamp }

    override suspend fun listByType(babyId: Long, type: String): List<RecordEntity> =
        items.value.filter {
            it.babyId == babyId &&
                it.deletedAt == null &&
                it.isOrdinarySurface() &&
                it.type == type
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
