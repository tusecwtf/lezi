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
class CareLogCustomItemTest {
    @Test
    fun editingATombstonedCustomItemFailsWithoutRevivingOrAddingIt() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        care.createBaby(CreateBabyInput(nickname = "测试宝宝", birthdayEpochDay = 1))
        val id = care.addCustomItem("草稿的原项目", 0)
        val intendedEdit = care.observeCustomItems().first().single().copy(name = "未提交的新名称")
        care.deleteCustomItem(id)
        val before = fakes.customItems.listAllIncludingDeleted()

        val failure = runCatching { care.updateCustomItem(intendedEdit) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure).hasMessageThat().isEqualTo("自定义项目已删除，请重新打开")
        assertThat(fakes.customItems.listAllIncludingDeleted()).containsExactlyElementsIn(before)
        assertThat(care.observeCustomItems().first()).isEmpty()
    }

    @Test
    fun editingAMissingCustomItemFailsWithoutTurningIntoAnAdd() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        care.createBaby(CreateBabyInput(nickname = "测试宝宝", birthdayEpochDay = 1))
        val intendedEdit = CustomRecordItem(999, "未提交的新名称", 0, 0, clientUuid = "missing-custom")

        val failure = runCatching { care.updateCustomItem(intendedEdit) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure).hasMessageThat().isEqualTo("自定义项目已不存在，请重新打开")
        assertThat(fakes.customItems.listAllIncludingDeleted()).isEmpty()
    }

    @Test
    fun updateAndMoveCustomItemsUseSharedDatabaseTransactions() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        care.addCustomItem("抚触", iconSlot = 0)
        care.addCustomItem("晒太阳", iconSlot = 1)
        val before = fakes.transactions.runCount
        val first = care.observeCustomItems().first().first()

        care.updateCustomItem(first.copy(name = "晚间抚触"))
        care.moveCustomItem(first.id, delta = 1)

        assertThat(fakes.transactions.runCount - before).isEqualTo(2)
    }
    @Test
    fun concurrentCustomItemRenamesToTheSameNameKeepOneUniqueDefinition() = runTest {
        val fakes = Fakes()
        fakes.transactions.serializeRuns = true
        val care = fakes.careLog()
        care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        care.addCustomItem("抚触", iconSlot = 0)
        care.addCustomItem("晒太阳", iconSlot = 1)
        val items = care.observeCustomItems().first()
        val first = items.first { it.name == "抚触" }
        val second = items.first { it.name == "晒太阳" }

        // Capture-before-yield makes the old read-check-write race deterministic:
        // without the shared transaction both updates validate the same stale catalog.
        fakes.customItems.afterListAllSnapshot = { yield() }
        val firstRename = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { care.updateCustomItem(first.copy(name = "睡前护理")) }
        }
        val secondRename = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { care.updateCustomItem(second.copy(name = "睡前护理")) }
        }
        val outcomes = listOf(firstRename.await(), secondRename.await())

        assertThat(outcomes.count { it.isSuccess }).isEqualTo(1)
        assertThat(outcomes.single { it.isFailure }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(outcomes.single { it.isFailure }.exceptionOrNull()?.message)
            .isEqualTo("自定义项目名称不可重复")
        val finalItems = care.observeCustomItems().first()
        assertThat(finalItems).hasSize(2)
        assertThat(finalItems.count { it.name == "睡前护理" }).isEqualTo(1)
    }
    @Test
    fun moveCustomItemRollsBackEverySortOrderWhenAnUpdateFails() = runTest {
        val fakes = Fakes()
        fakes.wireTransactionalSnapshots()
        val care = fakes.careLog()
        care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val firstId = care.addCustomItem("抚触", iconSlot = 0)
        care.addCustomItem("晒太阳", iconSlot = 1)
        care.addCustomItem("做操", iconSlot = 2)
        val before = fakes.customItems.listAll()
        fakes.customItems.failOnNthUpdateFromNow(2)

        val failure = runCatching {
            care.moveCustomItem(firstId, delta = 2)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(fakes.customItems.listAll()).containsExactlyElementsIn(before).inOrder()
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
            canManageCreatorOwnedFamilyEntity(
                creatorMembershipId = "m-creator",
                actorMembershipId = "m-creator",
                actorIsAdmin = false,
            ),
        ).isTrue()
        assertThat(
            canManageCreatorOwnedFamilyEntity(
                creatorMembershipId = "m-creator",
                actorMembershipId = "m-other",
                actorIsAdmin = false,
            ),
        ).isFalse()
        assertThat(
            canManageCreatorOwnedFamilyEntity(
                creatorMembershipId = "m-creator",
                actorMembershipId = "m-admin",
                actorIsAdmin = true,
            ),
        ).isTrue()
        // Creator left: admin still manages the definition.
        assertThat(
            canManageCreatorOwnedFamilyEntity(
                creatorMembershipId = "m-left",
                actorMembershipId = "m-admin",
                actorIsAdmin = true,
            ),
        ).isTrue()
        // Offline single-device: empty creator and actor.
        assertThat(
            canManageCreatorOwnedFamilyEntity(
                creatorMembershipId = "",
                actorMembershipId = "",
                actorIsAdmin = false,
            ),
        ).isTrue()
        // Legacy empty creator after join: non-admin cannot claim.
        assertThat(
            canManageCreatorOwnedFamilyEntity(
                creatorMembershipId = "",
                actorMembershipId = "m-member",
                actorIsAdmin = false,
            ),
        ).isFalse()
    }
    @Test
    fun customItemStampsCreatorMembershipAndRejectsNonOwnerEdit() = runTest {
        val memberSync = RecordingSyncPort(
            membershipId = "m-member",
            role = com.lezi.babylog.sync.session.FamilyRole.Member,
            familyId = "fam-1",
            deviceId = "dev-1",
        )
        val fakes = Fakes(memberSync)
        val care = fakes.careLog()
        fakes.seedFamilyAuthorityBaby()
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
            role = com.lezi.babylog.sync.session.FamilyRole.Owner,
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
    fun tombstonedCustomDefinitionKeepsHistoricalRecordEditableAndDeletable() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val itemId = care.addCustomItem("抚触", iconSlot = 2)
        val timestamp = System.currentTimeMillis() - 60_000L
        val payload =
            """{"title":"抚触","detail":"睡前十分钟","custom_item_id":$itemId,"icon_slot":2}"""
        val recordId = care.addRecord(
            babyId = babyId,
            type = RecordType.CUSTOM,
            timestamp = timestamp,
            payloadJson = payload,
        )

        care.deleteCustomItem(itemId)
        care.updateRecord(
            id = recordId,
            timestamp = timestamp,
            endTimestamp = null,
            note = "已完成",
            payloadJson = payload,
        )

        val edited = requireNotNull(care.getRecord(recordId))
        assertThat(edited.displayLabel()).isEqualTo("抚触")
        assertThat(edited.note).isEqualTo("已完成")
        assertThat(edited.payloadJson).contains("睡前十分钟")
        assertThat(care.observeCustomItems().first()).isEmpty()

        care.deleteRecord(recordId)
        assertThat(fakes.records.getIncludingDeleted(recordId)?.deletedAt).isNotNull()
    }
    @Test
    fun tombstonedCustomDefinitionStillAllowsPlanFulfillmentWithSnapshotAndPhotos() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        val babyId = care.createBaby(CreateBabyInput(nickname = "年年", birthdayEpochDay = 1))
        val itemId = care.addCustomItem("抚触", iconSlot = 2)
        val now = System.currentTimeMillis()
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.CUSTOM,
            scheduledAt = now + 60_000L,
            customItemId = itemId,
            payloadJson = """{"title":"抚触","custom_item_id":$itemId,"icon_slot":2}""",
            nowMillis = now,
        )
        care.deleteCustomItem(itemId)

        val recordId = care.fulfillCarePlan(
            carePlanId = planId,
            actualTimestamp = now,
            photoLocalPaths = listOf("photos/one.jpg", "photos/two.jpg"),
            nowMillis = now + 1,
        )

        val fact = requireNotNull(care.getRecord(recordId))
        assertThat(fact.displayLabel()).isEqualTo("抚触")
        assertThat(fact.payloadJson).contains("\"custom_item_id\":$itemId")
        assertThat(care.listRecordPhotoPaths(recordId))
            .containsExactly("photos/one.jpg", "photos/two.jpg")
        assertThat(care.getCarePlan(planId)?.status).isEqualTo(CarePlanStatus.COMPLETED)
        assertThat(care.observeCustomItems().first()).isEmpty()
    }
    @Test
    fun clearAllLocalDataWipesBabiesCustomItemsAndUsesSyncBarrier() = runTest {
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
        val now = System.currentTimeMillis()
        val planId = care.createCarePlan(
            babyId = babyId,
            type = RecordType.PEE,
            scheduledAt = now + 60_000L,
            nowMillis = now,
        )
        assertThat(fakes.reminders.scheduledCarePlanIds).contains(planId)
        val requestsBeforeClear = sync.requests

        fakes.localDataClearCoordinator(sync).clear(LocalDataClearScope.AllLocalData)

        assertThat(fakes.records.listAllIncludingDeleted()).isEmpty()
        assertThat(fakes.babies.listAllIncludingDeleted()).isEmpty()
        assertThat(fakes.customItems.listAll()).isEmpty()
        assertThat(fakes.carePlans.listAllIncludingDeleted()).isEmpty()
        assertThat(fakes.fulfillmentCandidates.listAllIncludingDeleted()).isEmpty()
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
            payloadJson = """{"title":"抚触","custom_item_id":$customId}""",
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
        fakes.settings.setDeviceLayoutSnapshot(
            DeviceLayoutSnapshot(hiddenItems = setOf("custom:$customId")),
        )
        val recordId = care.fulfillCarePlan(
            carePlanId = planId,
            actualTimestamp = now,
            nowMillis = now + 1,
        )
        assertThat(recordId).isGreaterThan(0L)
        assertThat(care.getCarePlan(planId)!!.displayLabel()).isEqualTo("抚触")
    }
}
