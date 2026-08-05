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
class CareLogBabyProfileTest {
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
    fun familyScaffoldIsIdempotentAfterALocalBabyAndPreservesItsFacts() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "本机宝宝", birthdayEpochDay = 1))
        val recordId = care.addRecord(
            babyId = babyId,
            type = RecordType.PEE,
            timestamp = 1_000,
            payloadJson = """{"pee_amount":2}""",
        )
        val babyBefore = care.listBabies().single()
        val recordBefore = care.getRecord(recordId)

        care.ensureFamilyScaffold()

        assertThat(care.listBabies()).containsExactly(babyBefore)
        assertThat(care.getRecord(recordId)).isEqualTo(recordBefore)
        assertThat(fakes.families.listAll()).hasSize(1)
        assertThat(fakes.memberships.listForFamily(fakes.families.listAll().single().id)).hasSize(1)
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
    fun updateBabyProfile_canSetBirthdayAndWeight() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
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
    fun updateBabyProfileTombstonesOldAvatarBeforeReferenceAwareCleanup() = runTest {
        val sync = RecordingSyncPort()
        val fakes = Fakes(sync)
        val care = fakes.careLog()
        val babyId = care.createBaby(
            CreateBabyInput(
                nickname = "豆豆",
                birthdayEpochDay = 10,
                avatarPath = "baby_avatars/old.jpg",
            ),
        )
        val baby = fakes.babies.get(babyId)!!
        fakes.media.seed(
            MediaAssetEntity(
                clientUuid = "avatar-before-replace",
                kind = "avatar",
                babyId = babyId,
                localUri = "baby_avatars/old.jpg",
                remoteUri = "lezi-sync:family:avatar-before-replace",
                createdAt = 100,
                updatedAt = 100,
            ),
        )
        fakes.babies.update(baby.copy(avatarMediaUuid = "avatar-before-replace"))

        care.updateBabyProfile(
            babyId,
            UpdateBabyInput(
                nickname = "豆豆",
                birthdayEpochDay = 10,
                avatarPath = "baby_avatars/new.jpg",
            ),
        )

        val updated = fakes.babies.get(babyId)!!
        assertThat(updated.avatarPath).isEqualTo("baby_avatars/new.jpg")
        assertThat(updated.avatarMediaUuid).isNull()
        val oldAvatar = fakes.media.getByClientUuid("avatar-before-replace")!!
        assertThat(oldAvatar.deletedAt).isNotNull()
        assertThat(oldAvatar.updatedAt).isEqualTo(oldAvatar.deletedAt)
        assertThat(oldAvatar.syncDirty).isTrue()
        assertThat(sync.mediaCleanupCandidates)
            .containsExactly(setOf("avatar-before-replace"))
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
    fun deleteBabyTombstonesAvatarClearsPointersAndHandsCleanupAfterCommit() = runTest {
        val avatarUpdatedAt = 5_000L
        val fixture = seedDeleteBabyAvatarFixture(
            primaryAvatarUuid = "avatar-delete-primary",
            avatarPath = "baby_avatars/target.jpg",
            avatarUpdatedAt = avatarUpdatedAt,
            mime = "image/jpeg",
            byteSize = 128,
        )

        assertThat(fixture.care.deleteBaby(fixture.targetId)).isTrue()

        val tombstone = fixture.fakes.babies.getIncludingDeleted(fixture.targetId)!!
        assertThat(tombstone.deletedAt).isNotNull()
        assertThat(tombstone.updatedAt).isEqualTo(tombstone.deletedAt)
        assertThat(tombstone.updatedAt).isGreaterThan(avatarUpdatedAt)
        assertThat(tombstone.avatarMediaUuid).isNull()
        assertThat(tombstone.avatarPath).isNull()
        assertThat(tombstone.syncDirty).isTrue()

        val avatar = fixture.fakes.media.getByClientUuid(fixture.primaryAvatarUuid)!!
        assertThat(avatar.deletedAt).isNotNull()
        assertThat(avatar.updatedAt).isEqualTo(avatar.deletedAt)
        assertThat(avatar.updatedAt).isAtLeast(tombstone.updatedAt)
        assertThat(avatar.syncDirty).isTrue()
        assertThat(avatar.localUri).isEqualTo("baby_avatars/target.jpg")
        assertThat(fixture.sync.mediaCleanupCandidates)
            .containsExactly(setOf(fixture.primaryAvatarUuid))
        assertThat(fixture.sync.requests).isGreaterThan(0)
    }
    @Test
    fun deleteBabyTombstonesEveryActiveAvatarNotOnlyPointer() = runTest {
        val pointedUuid = "avatar-pointed"
        val legacyUuid = "avatar-legacy-active"
        val fixture = seedDeleteBabyAvatarFixture(
            primaryAvatarUuid = pointedUuid,
            avatarPath = "baby_avatars/pointed.jpg",
            avatarUpdatedAt = 10L,
            extraActiveAvatarUuids = listOf(legacyUuid to "baby_avatars/legacy.jpg"),
            otherBabyAvatarUuid = "avatar-other-baby",
        )

        assertThat(fixture.care.deleteBaby(fixture.targetId)).isTrue()

        assertThat(fixture.fakes.media.getByClientUuid(pointedUuid)?.deletedAt).isNotNull()
        assertThat(fixture.fakes.media.getByClientUuid(legacyUuid)?.deletedAt).isNotNull()
        assertThat(fixture.fakes.media.getByClientUuid("avatar-other-baby")?.deletedAt).isNull()
        assertThat(fixture.sync.mediaCleanupCandidates.single())
            .containsExactly(pointedUuid, legacyUuid)
    }
    @Test
    fun deleteBabyKeepsBabyAvatarAndFilesWhenTransactionFails() = runTest {
        val avatarUuid = "avatar-tx-fail"
        val fixture = seedDeleteBabyAvatarFixture(
            primaryAvatarUuid = avatarUuid,
            avatarPath = "baby_avatars/tx.jpg",
            avatarUpdatedAt = 42L,
            wireTransactionalSnapshots = true,
        )
        val beforeBaby = fixture.fakes.babies.get(fixture.targetId)!!
        val mediaBefore = fixture.fakes.media.listAllIncludingDeleted()
        val requestsBefore = fixture.sync.requests
        fixture.fakes.media.failUpdateAfterSuccessfulUpdates(0)

        val error = runCatching { fixture.care.deleteBaby(fixture.targetId) }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalStateException::class.java)
        assertThat(error).hasMessageThat().contains("media update failed")
        assertThat(fixture.fakes.babies.getIncludingDeleted(fixture.targetId)).isEqualTo(beforeBaby)
        assertThat(fixture.fakes.media.listAllIncludingDeleted()).containsExactlyElementsIn(mediaBefore)
        assertThat(fixture.sync.mediaCleanupCandidates).isEmpty()
        assertThat(fixture.sync.requests).isEqualTo(requestsBefore)
    }
    @Test
    fun deleteBabyCleanupFailureKeepsCommittedTombstonesAndRetryMarker() = runTest {
        // Domain only asserts cleanup Result.failure does not roll back logical tombstones;
        // durable file-marker retry is owned by ReferenceAwareMediaFileCleanup.
        val path = "baby_avatars/retry.jpg"
        val fixture = seedDeleteBabyAvatarFixture(
            primaryAvatarUuid = "avatar-cleanup-retry",
            avatarPath = path,
            avatarUpdatedAt = 7L,
            cleanupFailure = IllegalStateException("gc retry required"),
        )

        assertThat(fixture.care.deleteBaby(fixture.targetId)).isTrue()

        assertThat(fixture.fakes.babies.getIncludingDeleted(fixture.targetId)?.deletedAt).isNotNull()
        assertThat(fixture.fakes.babies.getIncludingDeleted(fixture.targetId)?.avatarMediaUuid).isNull()
        val avatar = fixture.fakes.media.getByClientUuid(fixture.primaryAvatarUuid)!!
        assertThat(avatar.deletedAt).isNotNull()
        assertThat(avatar.localUri).isEqualTo(path)
        assertThat(fixture.sync.mediaCleanupCandidates)
            .containsExactly(setOf(fixture.primaryAvatarUuid))
        assertThat(fixture.sync.requests).isGreaterThan(0)
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
    fun familyMemberCannotMutateAuthorityBaby_andLocalPreferencesStayLocal() = runTest {
        val sync = RecordingSyncPort()
        val fakes = Fakes(sync)
        val care = fakes.careLog()
        val local = care.createBaby(CreateBabyInput(nickname = "本机", birthdayEpochDay = 1))
        val authority = fakes.babies.upsert(
            BabyEntity(
                familyId = 1,
                nickname = "家庭宝宝",
                birthdayEpochDay = 2,
                themeColorArgb = 10,
                clientUuid = "authority-baby",
                updatedAt = 100,
                syncDirty = false,
                familyAuthority = true,
            ),
        )
        sync.replaceSession(
            com.lezi.babylog.sync.session.SyncSession(
                familyId = "family",
                deviceId = "member-device",
                membershipId = "member",
                role = com.lezi.babylog.sync.session.FamilyRole.Member,
            ),
        )

        assertThat(care.listBabies().map { it.id }).containsExactly(authority)
        assertThat(care.observeMemberLocalBabyOrphans().first().map { it.id }).containsExactly(local)
        for (failure in listOf(
            runCatching { care.addBaby(CreateBabyInput("新增", birthdayEpochDay = 3)) }.exceptionOrNull(),
            runCatching { care.renameBaby(authority, "改名") }.exceptionOrNull(),
            runCatching { care.deleteBaby(authority) }.exceptionOrNull(),
        )) {
            assertThat(failure).isInstanceOf(BabyProfilePermissionException::class.java)
        }

        care.updateBabyLocalPreferences(authority, themeColorArgb = 20, sortOrder = 3)
        val updated = fakes.babies.get(authority)!!
        assertThat(updated.themeColorArgb).isEqualTo(20)
        assertThat(updated.sortOrder).isEqualTo(3)
        assertThat(updated.updatedAt).isEqualTo(100)
        assertThat(updated.syncDirty).isFalse()

        care.updateBabyLocalOrder(listOf(authority))
        val reordered = fakes.babies.get(authority)!!
        assertThat(reordered.sortOrder).isEqualTo(0)
        assertThat(reordered.updatedAt).isEqualTo(100)
        assertThat(reordered.syncDirty).isFalse()
    }
    @Test
    fun memberSingleAuthorityAutoRebindsOrphan_withoutPublishingBabyTombstone() = runTest {
        val sync = RecordingSyncPort()
        val fakes = Fakes(sync)
        val care = fakes.careLog()
        val local = care.createBaby(CreateBabyInput(nickname = "本机", birthdayEpochDay = 1))
        care.addRecord(local, RecordType.PEE, timestamp = 1_000L, payloadJson = """{"pee_amount":2}""")
        care.createCarePlan(
            local,
            RecordType.PEE,
            scheduledAt = 10_000L,
            payloadJson = """{"pee_amount":2}""",
            nowMillis = 1_000L,
        )
        val authority = fakes.babies.upsert(
            BabyEntity(
                familyId = 1,
                nickname = "家庭宝宝",
                birthdayEpochDay = 2,
                themeColorArgb = 10,
                clientUuid = "authority-baby",
                updatedAt = 100,
                syncDirty = false,
                familyAuthority = true,
            ),
        )
        sync.replaceSession(
            com.lezi.babylog.sync.session.SyncSession(
                familyId = "family",
                deviceId = "member-device",
                membershipId = "member",
                role = com.lezi.babylog.sync.session.FamilyRole.Member,
            ),
        )

        assertThat(care.reconcileMemberLocalBabies()).isEqualTo(1)
        assertThat(fakes.records.listAllIncludingDeleted().single().babyId).isEqualTo(authority)
        assertThat(fakes.records.listAllIncludingDeleted().single().syncDirty).isTrue()
        assertThat(fakes.carePlans.listAllIncludingDeleted().single().babyId).isEqualTo(authority)
        val source = fakes.babies.getIncludingDeleted(local)!!
        assertThat(source.deletedAt).isNotNull()
        assertThat(source.syncDirty).isFalse()
        assertThat(fakes.settings.currentBabyId.first()).isEqualTo(authority)
    }
    @Test
    fun initialFamilyApplyUsesMemberRulesBeforeSessionIsPersisted() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val orphan = care.createBaby(CreateBabyInput(nickname = "本机", birthdayEpochDay = 1))
        care.addRecord(orphan, RecordType.PEE, timestamp = 1_000, payloadJson = """{"pee_amount":2}""")
        val authority = fakes.babies.upsert(
            BabyEntity(
                familyId = 1,
                nickname = "家庭宝宝",
                birthdayEpochDay = 2,
                themeColorArgb = 10,
                clientUuid = "authority-before-session-save",
                updatedAt = 100,
                syncDirty = false,
                familyAuthority = true,
            ),
        )

        assertThat(care.reconcileMemberLocalBabiesAfterFamilyApply()).isEqualTo(1)

        assertThat(fakes.records.listAllIncludingDeleted().single().babyId).isEqualTo(authority)
        assertThat(fakes.babies.getIncludingDeleted(orphan)!!.syncDirty).isFalse()
    }
    @Test
    fun orphanRebindKeepsExistingAuthorityNextFeedAndDiscardsUnpublishedDuplicate() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val orphan = care.createBaby(CreateBabyInput(nickname = "本机", birthdayEpochDay = 1))
        val now = 1_800_000_000_000L
        val orphanPlanId = care.scheduleNextFeedCarePlan(
            orphan,
            RecordType.FORMULA,
            scheduledAt = now + 60_000,
            nowMillis = now,
        )
        val authority = fakes.babies.upsert(
            BabyEntity(
                familyId = 1,
                nickname = "家庭宝宝",
                birthdayEpochDay = 2,
                themeColorArgb = 10,
                clientUuid = "authority-with-next-feed",
                updatedAt = 100,
                syncDirty = false,
                familyAuthority = true,
            ),
        )
        val authorityPlanId = fakes.carePlans.upsert(
            CarePlanEntity(
                clientUuid = nextFeedPlanClientUuid("authority-with-next-feed", "initial"),
                babyId = authority,
                type = RecordType.NURSING.key,
                scheduledAt = now + 120_000,
                scheduledZoneId = "UTC",
                note = NEXT_FEED_PLAN_MARKER,
                payloadJson =
                    """{"left_min":0,"right_min":0,"order":"LR","record_mode":"end"}""",
                createdByMembershipId = "member-remote",
                updatedAt = now,
                syncDirty = false,
            ),
        )

        assertThat(care.reconcileMemberLocalBabiesAfterFamilyApply()).isEqualTo(1)

        val open = fakes.carePlans.listAllIncludingDeleted().filter {
            it.babyId == authority && it.deletedAt == null && isNextFeedPlanNote(it.note)
        }
        assertThat(open.map(CarePlanEntity::id)).containsExactly(authorityPlanId)
        val discarded = fakes.carePlans.get(orphanPlanId)!!
        assertThat(discarded.deletedAt).isNotNull()
        assertThat(discarded.syncDirty).isFalse()
        assertThat(fakes.reminders.cancelledCarePlanIds).contains(orphanPlanId)
    }
    @Test
    fun orphanRebindRekeysSoleNextFeedToAuthorityIdentity() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val orphan = care.createBaby(CreateBabyInput(nickname = "本机", birthdayEpochDay = 1))
        val now = 1_800_000_000_000L
        val planId = care.scheduleNextFeedCarePlan(
            orphan,
            RecordType.FORMULA,
            scheduledAt = now + 60_000,
            nowMillis = now,
        )
        val oldClientUuid = fakes.carePlans.get(planId)!!.clientUuid
        val authority = fakes.babies.upsert(
            BabyEntity(
                familyId = 1,
                nickname = "家庭宝宝",
                birthdayEpochDay = 2,
                themeColorArgb = 10,
                clientUuid = "authority-without-next-feed",
                updatedAt = 100,
                syncDirty = false,
                familyAuthority = true,
            ),
        )

        assertThat(care.reconcileMemberLocalBabiesAfterFamilyApply()).isEqualTo(1)

        val moved = fakes.carePlans.get(planId)!!
        assertThat(moved.babyId).isEqualTo(authority)
        assertThat(moved.clientUuid)
            .isEqualTo(nextFeedPlanClientUuid("authority-without-next-feed", "initial"))
        assertThat(moved.syncDirty).isTrue()
        assertThat(fakes.reminders.carePlanOperations.takeLast(2))
            .containsExactly(
                "cancel:$planId",
                "schedule:$planId:${moved.clientUuid}",
            ).inOrder()
        assertThat(
            care.shouldDeliverCarePlanReminder(planId, oldClientUuid, moved.scheduledAt),
        ).isFalse()
        assertThat(
            care.shouldDeliverCarePlanReminder(planId, moved.clientUuid, moved.scheduledAt),
        ).isTrue()
    }
    @Test
    fun orphanRebindRekeysNextFeedCalendarProjectionWithoutStaleIdentity() = runTest {
        val fakes = Fakes()
        fakes.systemCalendar.permission = true
        fakes.settings.setSystemCalendarEnabled(true)
        fakes.settings.setSystemCalendarId("cal-1")
        val care = fakes.careLog()
        val orphan = care.createBaby(CreateBabyInput(nickname = "本机", birthdayEpochDay = 1))
        val now = 1_800_000_000_000L
        val planId = care.scheduleNextFeedCarePlan(
            orphan,
            RecordType.FORMULA,
            scheduledAt = now + 60_000,
            nowMillis = now,
        )
        val before = fakes.carePlans.get(planId)!!
        val oldEventId = requireNotNull(before.systemCalendarEventId)
        val authority = fakes.babies.upsert(
            BabyEntity(
                familyId = 1,
                nickname = "家庭宝宝",
                birthdayEpochDay = 2,
                themeColorArgb = 10,
                clientUuid = "authority-calendar-next-feed",
                updatedAt = 100,
                syncDirty = false,
                familyAuthority = true,
            ),
        )

        assertThat(care.reconcileMemberLocalBabiesAfterFamilyApply()).isEqualTo(1)

        val moved = fakes.carePlans.get(planId)!!
        assertThat(moved.babyId).isEqualTo(authority)
        assertThat(fakes.systemCalendar.deleted).contains(oldEventId)
        assertThat(fakes.systemCalendar.upserts.last().carePlanClientUuid)
            .isEqualTo(moved.clientUuid)
        val eventMap = parseSystemCalendarEventMap(
            fakes.settings.settings.first().systemCalendarEventMapJson,
        )
        assertThat(eventMap).doesNotContainKey(before.clientUuid)
        assertThat(eventMap).containsKey(moved.clientUuid)
    }
    @Test
    fun memberMultipleAuthoritiesRequireExplicitOrphanToAuthorityMerge() = runTest {
        val sync = RecordingSyncPort()
        val fakes = Fakes(sync)
        val care = fakes.careLog()
        val orphan = care.createBaby(CreateBabyInput(nickname = "本机", birthdayEpochDay = 1))
        val authorityA = fakes.babies.upsert(
            BabyEntity(0, 1, "大宝", birthdayEpochDay = 2, themeColorArgb = 1,
                clientUuid = "authority-a", updatedAt = 10, syncDirty = false, familyAuthority = true),
        )
        val authorityB = fakes.babies.upsert(
            BabyEntity(0, 1, "二宝", birthdayEpochDay = 3, themeColorArgb = 2,
                clientUuid = "authority-b", updatedAt = 11, syncDirty = false, familyAuthority = true),
        )
        sync.replaceSession(
            com.lezi.babylog.sync.session.SyncSession(
                familyId = "family",
                deviceId = "member-device",
                membershipId = "member",
                role = com.lezi.babylog.sync.session.FamilyRole.Member,
            ),
        )

        assertThat(care.reconcileMemberLocalBabies()).isEqualTo(0)
        assertThat(care.previewBabyMerge(orphan, authorityA)).isNotNull()
        assertThat(
            runCatching { care.previewBabyMerge(authorityA, authorityB) }.exceptionOrNull(),
        ).isInstanceOf(BabyProfilePermissionException::class.java)
        assertThat(care.mergeBabyProfiles(orphan, authorityB)).isTrue()
    }
    @Test
    fun mergeBabyProfilesMovesFactsButTombstonesSourceAvatarAssociation() = runTest {
        val sync = RecordingSyncPort()
        val fakes = Fakes(sync)
        val care = fakes.careLog()
        val target = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val source = care.addBaby(CreateBabyInput(nickname = "临时", birthdayEpochDay = 2))
        val liveId = care.addRecord(source, RecordType.PEE, timestamp = 1_000)
        val tombstoneId = care.addRecord(
            source,
            RecordType.FORMULA,
            timestamp = 2_000,
            payloadJson = """{"amount_ml":90}""",
        )
        care.deleteRecord(tombstoneId)
        fakes.media.seed(
            MediaAssetEntity(
                clientUuid = "avatar-source",
                kind = "avatar",
                babyId = source,
                localUri = "avatars/source.jpg",
                remoteUri = "lezi-sync:family:avatar-source",
                createdAt = 100,
                updatedAt = 100,
            ),
        )

        assertThat(care.mergeBabyProfiles(sourceBabyId = source, targetBabyId = target)).isTrue()

        assertThat(fakes.records.listAllIncludingDeleted().map { it.id to it.babyId })
            .containsExactly(liveId to target, tombstoneId to target)
        val avatar = fakes.media.listAllIncludingDeleted().single()
        assertThat(avatar.babyId).isEqualTo(source)
        assertThat(avatar.deletedAt).isNotNull()
        assertThat(avatar.updatedAt).isEqualTo(avatar.deletedAt)
        assertThat(avatar.syncDirty).isTrue()
        assertThat(sync.mediaCleanupCandidates).containsExactly(setOf("avatar-source"))
    }
    @Test
    fun mergeBabyProfilesMovesCarePlansAndKeepsReminderOwnership() = runTest {
        val fakes = Fakes()
        fakes.systemCalendar.permission = true
        fakes.settings.setSystemCalendarEnabled(true)
        fakes.settings.setSystemCalendarId("cal-1")
        val care = fakes.careLog()
        val target = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val source = care.addBaby(CreateBabyInput(nickname = "临时", birthdayEpochDay = 2))
        val now = System.currentTimeMillis()
        val localReminderPlanId = care.createCarePlan(
            babyId = source,
            type = RecordType.PEE,
            scheduledAt = now + 120_000L,
            payloadJson = """{"pee_amount":1}""",
            nowMillis = now,
            projectToSystemCalendar = false,
        )
        val projectedPlanId = care.createCarePlan(
            babyId = source,
            type = RecordType.FORMULA,
            scheduledAt = now + 180_000L,
            payloadJson = """{"amount_ml":90}""",
            nowMillis = now,
        )
        val tombstonePlanId = care.createCarePlan(
            babyId = source,
            type = RecordType.PEE,
            scheduledAt = now + 240_000L,
            payloadJson = """{"pee_amount":2}""",
            nowMillis = now,
            projectToSystemCalendar = false,
        )
        care.deleteCarePlan(tombstonePlanId, nowMillis = now + 1L)
        val before = fakes.carePlans.listAllIncludingDeleted().associateBy { it.id }
        assertThat(before.getValue(projectedPlanId).systemCalendarEventId).isNotNull()

        val preview = requireNotNull(care.previewBabyMerge(source, target))
        assertThat(preview.carePlanCount).isEqualTo(2)
        assertThat(care.mergeBabyProfiles(source, target)).isTrue()

        val moved = fakes.carePlans.listAllIncludingDeleted()
            .filter { it.id in before.keys }
            .associateBy { it.id }
        assertThat(moved.keys)
            .containsExactly(localReminderPlanId, projectedPlanId, tombstonePlanId)
        moved.forEach { (id, plan) ->
            assertThat(plan.babyId).isEqualTo(target)
            assertThat(plan.syncDirty).isTrue()
            assertThat(plan.updatedAt).isGreaterThan(before.getValue(id).updatedAt)
        }
        assertThat(moved.getValue(tombstonePlanId).deletedAt)
            .isEqualTo(before.getValue(tombstonePlanId).deletedAt)
        assertThat(fakes.reminders.scheduledCarePlanIds).contains(localReminderPlanId)
        val localReminderPlan = moved.getValue(localReminderPlanId)
        assertThat(
            care.shouldDeliverCarePlanReminder(
                localReminderPlan.id,
                localReminderPlan.clientUuid,
                localReminderPlan.scheduledAt,
            ),
        ).isTrue()
        val projectedBefore = before.getValue(projectedPlanId)
        val projectedAfter = moved.getValue(projectedPlanId)
        assertThat(projectedAfter.systemCalendarEventId)
            .isEqualTo(projectedBefore.systemCalendarEventId)
        assertThat(projectedAfter.systemCalendarReminderReady)
            .isEqualTo(projectedBefore.systemCalendarReminderReady)
        assertThat(care.isCarePlanSystemCalendarUnsynced(projectedPlanId)).isFalse()
    }
    @Test
    fun mergeBabyProfilesReprojectsExistingL2CalendarCopyWithTargetNicknameAndIdentity() = runTest {
        val fakes = Fakes()
        fakes.systemCalendar.permission = true
        fakes.settings.setSystemCalendarEnabled(true)
        fakes.settings.setSystemCalendarId("cal-1")
        fakes.settings.setSystemCalendarDisclosureLevel(2)
        val care = fakes.careLog()
        val target = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val source = care.addBaby(CreateBabyInput(nickname = "临时", birthdayEpochDay = 2))
        val now = System.currentTimeMillis()
        val planId = care.createCarePlan(
            babyId = source,
            type = RecordType.FORMULA,
            scheduledAt = now + 120_000L,
            payloadJson = """{"amount_ml":90}""",
            nowMillis = now,
        )
        val before = requireNotNull(fakes.carePlans.get(planId))
        val originalEventId = requireNotNull(before.systemCalendarEventId)
        assertThat(fakes.systemCalendar.upserts.last().title).isEqualTo("临时 · 配方奶")
        fakes.systemCalendar.upserts.clear()

        assertThat(care.mergeBabyProfiles(source, target)).isTrue()

        val reproject = fakes.systemCalendar.upserts.single()
        assertThat(reproject.title).isEqualTo("年年 · 配方奶")
        assertThat(reproject.carePlanClientUuid).isEqualTo(before.clientUuid)
        assertThat(reproject.existingEventId).isEqualTo(originalEventId)
        val after = requireNotNull(fakes.carePlans.get(planId))
        assertThat(after.babyId).isEqualTo(target)
        assertThat(after.systemCalendarEventId).isEqualTo(originalEventId)
        assertThat(after.systemCalendarReminderReady).isTrue()
        assertThat(after.systemCalendarProjectionPending).isFalse()
    }
    @Test
    fun mergeBabyProfilesKeepsCommittedMergeAndRecoverableL3ProjectionOnProviderFailure() = runTest {
        val fakes = Fakes()
        fakes.systemCalendar.permission = true
        fakes.settings.setSystemCalendarEnabled(true)
        fakes.settings.setSystemCalendarId("cal-1")
        fakes.settings.setSystemCalendarDisclosureLevel(3)
        val care = fakes.careLog()
        val target = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val source = care.addBaby(CreateBabyInput(nickname = "临时", birthdayEpochDay = 2))
        val now = System.currentTimeMillis()
        val planId = care.createCarePlan(
            babyId = source,
            type = RecordType.BATH,
            scheduledAt = now + 120_000L,
            note = "睡前洗澡",
            nowMillis = now,
        )
        val before = requireNotNull(fakes.carePlans.get(planId))
        val originalEventId = requireNotNull(before.systemCalendarEventId)
        fakes.systemCalendar.providerStillOwnsStaleReminder = true
        fakes.systemCalendar.upserts.clear()

        assertThat(care.mergeBabyProfiles(source, target)).isTrue()

        val failedReproject = fakes.systemCalendar.upserts.single()
        assertThat(failedReproject.title).isEqualTo("年年 · 洗澡")
        assertThat(failedReproject.existingEventId).isEqualTo(originalEventId)
        val committed = requireNotNull(fakes.carePlans.get(planId))
        assertThat(committed.babyId).isEqualTo(target)
        assertThat(committed.systemCalendarEventId).isEqualTo(originalEventId)
        assertThat(committed.systemCalendarReminderReady).isFalse()
        assertThat(committed.systemCalendarProjectionPending).isTrue()
        assertThat(care.isCarePlanSystemCalendarUnsynced(planId)).isTrue()

        fakes.systemCalendar.providerStillOwnsStaleReminder = false
        fakes.systemCalendar.upserts.clear()
        care.rescheduleCarePlanReminders(nowMillis = now + 1L)

        val retry = fakes.systemCalendar.upserts.single()
        assertThat(retry.title).isEqualTo("年年 · 洗澡")
        assertThat(retry.existingEventId).isEqualTo(originalEventId)
        val reconciled = requireNotNull(fakes.carePlans.get(planId))
        assertThat(reconciled.systemCalendarEventId).isEqualTo(originalEventId)
        assertThat(reconciled.systemCalendarReminderReady).isTrue()
        assertThat(reconciled.systemCalendarProjectionPending).isFalse()
        assertThat(care.isCarePlanSystemCalendarUnsynced(planId)).isFalse()
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
}
