package com.lezi.babylog.domain

import com.lezi.babylog.core.common.newClientUuid
import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.CalendarEventDao
import com.lezi.babylog.core.database.CalendarEventEntity
import com.lezi.babylog.core.database.CarePlanDao
import com.lezi.babylog.core.database.CarePlanEntity
import com.lezi.babylog.core.database.CustomItemDao
import com.lezi.babylog.core.database.CustomItemEntity
import com.lezi.babylog.core.database.DatabaseTransactionRunner
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
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.LocalClearCommittedException
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.core.model.CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION
import com.lezi.babylog.core.model.CarePlan
import com.lezi.babylog.core.model.CarePlanStatus
import com.lezi.babylog.core.model.ConflictNotAdoptedAudit
import com.lezi.babylog.core.model.FulfillmentAdoptionStatus
import com.lezi.babylog.core.model.FulfillmentAuthority
import com.lezi.babylog.core.model.FulfillmentCandidate
import com.lezi.babylog.core.model.FulfillmentCandidateEvidence
import com.lezi.babylog.core.model.MAX_RECORD_PHOTOS
import com.lezi.babylog.core.model.NursingPayload
import com.lezi.babylog.core.model.MilkPayload
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordPayloadCodec
import com.lezi.babylog.core.model.RecordPayloadDocument
import com.lezi.babylog.core.model.RecordTime
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.birthWeightValidationError
import com.lezi.babylog.core.model.CustomPayload
import com.lezi.babylog.core.model.displayLabel
import com.lezi.babylog.core.model.isPlanableCarePlanType
import com.lezi.babylog.core.model.Sex
import com.lezi.babylog.core.model.SleepPayload
import com.lezi.babylog.core.model.localPhotoPaths
import com.lezi.babylog.core.model.normalizeBabyNickname
import com.lezi.babylog.core.model.withLocalPhotoPaths
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class CreateBabyInput(
    val nickname: String,
    val sex: String? = null,
    val birthdayEpochDay: Long,
    /** Birth weight in grams; null when not set. */
    val birthWeightGrams: Int? = null,
    val avatarPath: String? = null,
    val themeColorArgb: Int = DEFAULT_THEME_COLOR,
) {
    companion object {
        const val DEFAULT_THEME_COLOR: Int = 0xFF007BAE.toInt()
    }
}

data class UpdateBabyInput(
    val nickname: String,
    val sex: String? = null,
    val birthdayEpochDay: Long,
    val birthWeightGrams: Int? = null,
    val avatarPath: String? = null,
    val themeColorArgb: Int? = null,
)

/** Thrown when another active baby already uses the nickname. */
class DuplicateBabyNicknameException(val nickname: String) :
    IllegalArgumentException("宝宝昵称「$nickname」已存在")

class SleepStateChangedException :
    IllegalStateException("睡眠状态已变化，请重新打开睡眠菜单")

enum class TimeBarKind { FEED, SLEEP }

data class TimeBarSegment(
    val startMinOfDay: Int,
    val endMinOfDay: Int,
    val kind: TimeBarKind,
)

data class CalendarEvent(
    val id: Long,
    val clientUuid: String,
    val babyId: Long,
    val title: String,
    val note: String?,
    val eventAt: Long,
    val remindAt: Long?,
)

data class CustomRecordItem(
    val id: Long,
    val name: String,
    val iconSlot: Int,
    val sortOrder: Int,
    /** Stable family identity (clientUuid); not the local row id catalog key. */
    val clientUuid: String = "",
    /**
     * Server-minted membership id of the creator; empty for local-only definitions.
     */
    val createdByMembershipId: String = "",
    val updatedAt: Long = 0L,
    val deletedAt: Long? = null,
)

class CustomItemLimitException :
    IllegalStateException("自定义项目最多 10 个")

class CarePlanPermissionException :
    IllegalStateException("无权管理此护理计划")

/** Thrown when a non-admin tries to read or convert conflict-not-adopted audits. */
class ConflictAuditPermissionException :
    IllegalStateException("仅家庭管理员可查看冲突未采纳履行或转为独立记录")

class CustomItemPermissionException :
    IllegalStateException("无权修改该自定义项目")

data class LocalFamilyIdentity(
    val deviceId: String,
    val displayName: String,
    val familyId: Long,
)

data class BabyMergePreview(
    val sourceBabyId: Long,
    val sourceNickname: String,
    val targetBabyId: Long,
    val targetNickname: String,
    val recordCount: Int,
    val calendarEventCount: Int,
)

@Singleton
class CareLog @Inject constructor(
    private val babyDao: BabyDao,
    private val recordDao: RecordDao,
    private val carePlanDao: CarePlanDao,
    private val calendarEventDao: CalendarEventDao,
    private val customItemDao: CustomItemDao,
    private val localUserDao: LocalUserDao,
    private val familyDao: FamilyDao,
    private val membershipDao: MembershipDao,
    private val mediaAssetDao: MediaAssetDao,
    private val pendingReminderCleanupStore: PendingReminderCleanupStore,
    private val settings: SettingsStore,
    private val syncPort: SyncPort,
    private val reminderCleanup: ReminderCleanupPort,
    private val transactionRunner: DatabaseTransactionRunner,
    private val systemCalendar: SystemCalendarPort = NoOpSystemCalendarPort(),
    private val fulfillmentCandidateDao: FulfillmentCandidateDao,
) {
    /**
     * Serializes record create, update, delete, and confirm operations that may
     * change active sleep state. Room transactions provide atomic writes; this
     * lock makes read-check-write sequences deterministic inside this process.
     */
    private val sleepMutationMutex = Mutex()
    private val calendarReminderMutationMutex = Mutex()

    fun observeHasBaby(): Flow<Boolean> =
        babyDao.observeAll().map { it.isNotEmpty() }

    fun observeBabies(): Flow<List<Baby>> =
        babyDao.observeAll().map { list -> list.map { it.toModel() } }

    fun observeCurrentBaby(): Flow<Baby?> =
        combine(babyDao.observeAll(), settings.currentBabyId) { babies, storedId ->
            pickCurrent(babies, storedId)
        }.map { entity ->
            entity?.toModel()
        }

    suspend fun createBaby(input: CreateBabyInput): Long = addBaby(input)

    /**
     * Creates the local user/family parent rows needed to apply a remote Baby during first-run
     * join, without publishing a placeholder Baby that would prematurely leave onboarding.
     */
    suspend fun ensureFamilyScaffold() {
        val now = System.currentTimeMillis()
        transactionRunner.run {
            val userId = ensureLocalUser(now)
            ensureFamily(userId, now)
        }
    }

    suspend fun addBaby(input: CreateBabyInput): Long {
        val now = System.currentTimeMillis()
        val userId = ensureLocalUser(now)
        val familyId = ensureFamily(userId, now)
        val nickname = normalizeNickname(input.nickname)
        val weight = normalizeBirthWeightGrams(input.birthWeightGrams)
        // Nickname uniqueness check and insert share one DB transaction so
        // concurrent addBaby(同名) cannot both pass the pre-check.
        val id = transactionRunner.run {
            ensureNicknameAvailable(nickname)
            babyDao.upsert(
                BabyEntity(
                    familyId = familyId,
                    nickname = nickname,
                    sex = input.sex,
                    birthdayEpochDay = input.birthdayEpochDay,
                    birthWeightGrams = weight,
                    avatarPath = input.avatarPath,
                    themeColorArgb = input.themeColorArgb,
                    clientUuid = newClientUuid(),
                    updatedAt = now,
                ),
            )
        }
        settings.setCurrentBabyId(id)
        requestLocalSync()
        return id
    }

    /**
     * Update baby profile fields. Nickname must stay unique among active babies.
     * @throws DuplicateBabyNicknameException when another baby already uses the name
     */
    suspend fun updateBabyProfile(babyId: Long, input: UpdateBabyInput) {
        val changed = transactionRunner.run {
            val existing = babyDao.get(babyId) ?: return@run false
            val nickname = normalizeNickname(input.nickname)
            ensureNicknameAvailable(nickname, excludeId = babyId)
            babyDao.update(
                existing.copy(
                    nickname = nickname,
                    sex = input.sex,
                    birthdayEpochDay = input.birthdayEpochDay,
                    birthWeightGrams = normalizeBirthWeightGrams(input.birthWeightGrams),
                    avatarPath = input.avatarPath,
                    themeColorArgb = input.themeColorArgb ?: existing.themeColorArgb,
                    updatedAt = nextSyncUpdatedAt(
                        existing.updatedAt,
                        System.currentTimeMillis(),
                    ),
                    syncDirty = true,
                ),
            )
            true
        }
        if (!changed) return
        requestLocalSync()
    }

    /** Soft-delete a baby profile. Reassigns current baby if needed. Keeps at least one baby. */
    suspend fun deleteBaby(babyId: Long): Boolean {
        val reminderEventIds = calendarEventDao.listForBabyIncludingDeleted(babyId).map { it.id }
        var deleted = false
        val remaining = transactionRunner.run {
            val babies = babyDao.listAll()
            if (babies.size <= 1) return@run emptyList()
            val target = babies.find { it.id == babyId } ?: return@run emptyList()
            val now = nextSyncUpdatedAt(target.updatedAt, System.currentTimeMillis())
            babyDao.update(target.copy(deletedAt = now, updatedAt = now, syncDirty = true))
            deleted = true
            babyDao.listAll()
        }
        if (!deleted) return false
        reminderCleanup.cancelForBabyDelete(reminderEventIds)
        val currentId = settings.currentBabyId.first()
        if (currentId == null || currentId == babyId || remaining.none { it.id == currentId }) {
            remaining.firstOrNull()?.let { settings.setCurrentBabyId(it.id) }
        }
        requestLocalSync()
        return true
    }

    /**
     * Read-only current-baby resolution. Does not write [SettingsStore.currentBabyId];
     * callers that mutate membership (delete/merge/set) must reassign explicitly.
     */
    suspend fun getCurrentBaby(): Baby? {
        val babies = babyDao.listAll()
        val stored = settings.currentBabyId.first()
        val entity = pickCurrent(babies, stored) ?: return null
        return entity.toModel()
    }

    suspend fun listBabies(): List<Baby> = babyDao.listAll().map { it.toModel() }

    /** Read-only family identity seam for feature modules; never creates rows. */
    suspend fun localFamilyIdentity(): LocalFamilyIdentity {
        val user = localUserDao.get()
        val family = familyDao.listAll().firstOrNull()
        return LocalFamilyIdentity(
            deviceId = user?.deviceId ?: "—",
            // Cache of membership 家庭称呼 when joined; local-only placeholder otherwise.
            displayName = user?.displayName?.takeIf { it.isNotBlank() } ?: "我（本机）",
            familyId = family?.id ?: 1L,
        )
    }

    /**
     * Cache the current membership 家庭称呼 on [LocalUserEntity] after create/join/self-rename.
     * Blank / 「我（本机）」 clear the cache so UI falls back to the local placeholder.
     */
    suspend fun updateLocalDisplayName(displayName: String?) {
        val existing = localUserDao.get() ?: return
        val normalized = displayName?.trim().orEmpty()
        val stored = normalized.takeIf {
            it.isNotEmpty() && it != "我（本机）"
        }
        localUserDao.upsert(existing.copy(displayName = stored))
    }

    suspend fun setCurrentBaby(babyId: Long) {
        val baby = babyDao.get(babyId) ?: return
        settings.setCurrentBabyId(baby.id)
    }

    /**
     * Observe records inside the half-open local-date range. Sleep records that
     * start earlier are also included when their interval overlaps the range.
     * Conflict-not-adopted fulfillment records are excluded from ordinary surfaces.
     */
    fun observeRecords(
        babyId: Long,
        startDayInclusive: LocalDate,
        endDayExclusive: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Flow<List<Record>> {
        require(startDayInclusive.isBefore(endDayExclusive)) {
            "startDayInclusive must be before endDayExclusive"
        }
        val start = startDayInclusive.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = endDayExclusive.atStartOfDay(zone).toInstant().toEpochMilli()
        // DAO ordinary queries already exclude conflict-not-adopted fulfillment records.
        return recordDao.observeRange(babyId, start, end).map { list -> list.map { it.toModel() } }
    }

    fun observeDayRecords(
        babyId: Long,
        day: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Flow<List<Record>> = observeRecords(babyId, day, day.plusDays(1), zone)

    /** Observe the baby's single active sleep independently of the viewed date. */
    fun observeOpenSleep(babyId: Long): Flow<Record?> =
        recordDao.observeOpenSleep(babyId).map { it?.toModel() }

    fun observeCalendarEvents(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): Flow<List<CalendarEvent>> {
        require(startInclusive < endExclusive) {
            "startInclusive must be before endExclusive"
        }
        return calendarEventDao.observeRange(babyId, startInclusive, endExclusive)
            .map { events -> events.map { it.toModel() } }
    }

    /**
     * All non-deleted care plans in an absolute window for the lezi calendar.
     * Independent of system-calendar projection flags.
     */
    fun observeCarePlansInRange(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): Flow<List<CarePlan>> {
        require(startInclusive < endExclusive) {
            "startInclusive must be before endExclusive"
        }
        return carePlanDao.observeRange(babyId, startInclusive, endExclusive)
            .map { rows -> rows.map { it.toModel() } }
    }

    /**
     * Explicit conversion of a legacy free-title calendar event into a care plan.
     * On success: soft-deletes the event and cancels its calendar reminder so there
     * are never two active alarms. On failure: leaves the event intact.
     *
     * @return new care plan local id
     */
    suspend fun convertCalendarEventToCarePlan(
        eventId: Long,
        type: RecordType,
        payloadJson: String = "{}",
        schemaVersion: Int = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
        customItemId: Long? = null,
        photoLocalPaths: List<String> = emptyList(),
        zone: ZoneId = ZoneId.systemDefault(),
        nowMillis: Long = System.currentTimeMillis(),
    ): Long = calendarReminderMutationMutex.withLock {
        val liveEvent = resolveCalendarEvent(eventId) ?: error("日程不存在")
        if (liveEvent.deletedAt != null) error("日程已删除")
        val scheduledAt = liveEvent.eventAt
        require(scheduledAt > nowMillis) {
            "只能将未来的日程转换为护理计划"
        }
        // Create plan first; only soft-delete + cancel event reminder after success.
        val planId = createCarePlan(
            babyId = liveEvent.babyId,
            type = type,
            scheduledAt = scheduledAt,
            note = listOfNotNull(liveEvent.title.takeIf { it.isNotBlank() }, liveEvent.note)
                .joinToString(" · ")
                .ifBlank { null },
            payloadJson = payloadJson,
            schemaVersion = schemaVersion,
            customItemId = customItemId,
            photoLocalPaths = photoLocalPaths,
            zone = zone,
            nowMillis = nowMillis,
        )
        calendarEventDao.softDelete(eventId, System.currentTimeMillis())
        reminderCleanup.cancelCalendar(eventId)
        planId
    }

    private suspend fun resolveCalendarEvent(eventId: Long): CalendarEventEntity? {
        babyDao.listAllIncludingDeleted().forEach { baby ->
            calendarEventDao.listForBabyIncludingDeleted(baby.id)
                .firstOrNull { it.id == eventId }
                ?.let { return it }
        }
        return null
    }

    suspend fun addCalendarEvent(
        babyId: Long,
        title: String,
        eventAt: Long,
        remindAt: Long?,
        note: String? = null,
    ): Long = calendarReminderMutationMutex.withLock {
        val now = System.currentTimeMillis()
        val entity = CalendarEventEntity(
            clientUuid = newClientUuid(),
            babyId = babyId,
            title = title,
            note = note,
            eventAt = eventAt,
            remindAt = remindAt,
            updatedAt = now,
        )
        val id = calendarEventDao.upsert(entity)
        reminderCleanup.scheduleCalendar(entity.copy(id = id).toModel())
        id
    }

    suspend fun updateCalendarEvent(event: CalendarEvent): Boolean =
        calendarReminderMutationMutex.withLock {
            calendarEventDao.update(
                CalendarEventEntity(
                    id = event.id,
                    clientUuid = event.clientUuid,
                    babyId = event.babyId,
                    title = event.title.trim(),
                    note = event.note,
                    eventAt = event.eventAt,
                    remindAt = event.remindAt,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            val scheduled = reminderCleanup.scheduleCalendar(event)
            if (!scheduled) reminderCleanup.cancelCalendar(event.id)
            scheduled
        }

    suspend fun deleteCalendarEvent(id: Long) = calendarReminderMutationMutex.withLock {
        calendarEventDao.softDelete(id, System.currentTimeMillis())
        reminderCleanup.cancelCalendar(id)
    }

    suspend fun listCalendarEvents(babyId: Long): List<CalendarEvent> =
        calendarEventDao.listForBaby(babyId).map { it.toModel() }

    /** Rebuild alarms without racing a clear or a calendar write. */
    suspend fun rescheduleCalendarReminders() = calendarReminderMutationMutex.withLock {
        babyDao.listAll().forEach { baby ->
            calendarEventDao.listForBaby(baby.id)
                .map(CalendarEventEntity::toModel)
                .forEach { reminderCleanup.scheduleCalendar(it) }
        }
    }

    fun observeCustomItems(): Flow<List<CustomRecordItem>> =
        customItemDao.observeAll().map { items -> items.map { it.toModel() } }

    suspend fun addCustomItem(name: String, iconSlot: Int): Long {
        val normalized = name.trim()
        require(normalized.isNotEmpty()) { "自定义项目名称不能为空" }
        require(iconSlot in 0..7) { "图标槽必须在 0..7" }
        val familyId = getCurrentBaby()?.familyId
            ?: listBabies().firstOrNull()?.familyId
            ?: error("请先添加宝宝")
        val now = System.currentTimeMillis()
        val creatorMembership = currentMembershipActorId()
        // Limit/uniqueness check and insert share one DB transaction so concurrent
        // adds cannot both pass the pre-check and create an 11th item / duplicate.
        return transactionRunner.run {
            val items = customItemDao.listAll()
            if (items.size >= 10) throw CustomItemLimitException()
            require(items.none { it.name == normalized }) { "自定义项目名称不可重复" }
            customItemDao.upsert(
                CustomItemEntity(
                    clientUuid = newClientUuid(),
                    familyId = familyId,
                    name = normalized,
                    iconSlot = iconSlot,
                    sortOrder = items.size,
                    updatedAt = now,
                    createdByMembershipId = creatorMembership,
                    syncDirty = true,
                ),
            )
        }.also { requestLocalSync() }
    }

    suspend fun updateCustomItem(item: CustomRecordItem) {
        val existing = customItemDao.getById(item.id) ?: return
        if (existing.deletedAt != null) return
        requireCanManageCustomItem(existing)
        val normalized = item.name.trim()
        require(normalized.isNotEmpty()) { "自定义项目名称不能为空" }
        require(item.iconSlot in 0..7) { "图标槽必须在 0..7" }
        require(
            customItemDao.listAll().none { it.id != item.id && it.name == normalized },
        ) { "自定义项目名称不可重复" }
        val sharedChanged =
            existing.name != normalized || existing.iconSlot != item.iconSlot
        // sortOrder is device-local layout — never dirty family sync by itself.
        customItemDao.update(
            existing.copy(
                name = normalized,
                iconSlot = item.iconSlot,
                sortOrder = item.sortOrder.coerceAtLeast(0),
                updatedAt = if (sharedChanged) {
                    System.currentTimeMillis().coerceAtLeast(existing.updatedAt + 1)
                } else {
                    existing.updatedAt
                },
                syncDirty = existing.syncDirty || sharedChanged,
            ),
        )
        if (sharedChanged) requestLocalSync()
    }

    /**
     * Local reorder only. Does not bump [CustomItemEntity.updatedAt] or mark
     * [CustomItemEntity.syncDirty] so family LWW renames are not clobbered.
     */
    suspend fun moveCustomItem(id: Long, delta: Int) {
        val items = customItemDao.listAll()
        val from = items.indexOfFirst { it.id == id }
        if (from < 0) return
        requireCanManageCustomItem(items[from])
        val to = (from + delta).coerceIn(0, items.lastIndex)
        if (to == from) return
        val reordered = items.toMutableList().apply {
            add(to, removeAt(from))
        }
        reordered.forEachIndexed { index, item ->
            if (item.sortOrder != index) {
                customItemDao.update(item.copy(sortOrder = index))
            }
        }
    }

    /**
     * Soft-delete (tombstone) a shared custom definition.
     * Local hide via [SettingsLocal.hiddenItems] is separate and does not call this.
     */
    suspend fun deleteCustomItem(id: Long) {
        val existing = customItemDao.getById(id) ?: return
        if (existing.deletedAt != null) return
        requireCanManageCustomItem(existing)
        customItemDao.softDelete(id, System.currentTimeMillis())
        requestLocalSync()
    }

    /**
     * Whether the current session may edit/delete this custom definition.
     * - Owner/admin: all definitions (including after creator leave).
     * - Member: only own membership stamp.
     * - Offline / never-joined (empty membership on both sides): allow (single device).
     */
    fun canManageCustomItem(
        item: CustomRecordItem,
        actorMembershipId: String,
        actorIsAdmin: Boolean,
    ): Boolean = canManageCreatorOwnedFamilyEntity(
        creatorMembershipId = item.createdByMembershipId,
        actorMembershipId = actorMembershipId,
        actorIsAdmin = actorIsAdmin,
    )

    suspend fun canManageCustomItem(item: CustomRecordItem): Boolean {
        val session = syncPort.session().first()
        return canManageCustomItem(
            item = item,
            actorMembershipId = session.membershipId.trim(),
            actorIsAdmin = session.role == com.lezi.babylog.sync.FamilyRole.Owner,
        )
    }

    private suspend fun requireCanManageCustomItem(existing: CustomItemEntity) {
        val session = syncPort.session().first()
        val allowed = canManageCreatorOwnedFamilyEntity(
            creatorMembershipId = existing.createdByMembershipId,
            actorMembershipId = session.membershipId.trim(),
            actorIsAdmin = session.role == com.lezi.babylog.sync.FamilyRole.Owner,
        )
        if (!allowed) throw CustomItemPermissionException()
    }

    private suspend fun currentMembershipActorId(): String =
        syncPort.session().first().membershipId.trim()

    suspend fun dayRecords(
        babyId: Long,
        day: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Record> {
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return recordDao.listDay(babyId, start, end).map { it.toModel() }
    }

    suspend fun daySummary(
        babyId: Long,
        day: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
        now: Long = System.currentTimeMillis(),
    ): DailySummary {
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val records = recordDao.listDay(babyId, start, end).map { it.toModel() }
        return CareAggregation.day(records, day, zone, now).toDailySummary()
    }

    suspend fun dayTimeBar(
        babyId: Long,
        day: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<TimeBarSegment> {
        val records = dayRecords(babyId, day, zone)
        return CareAggregation.timeBar(records, day, zone)
    }

    suspend fun addRecord(
        babyId: Long,
        type: RecordType,
        timestamp: Long = System.currentTimeMillis(),
        endTimestamp: Long? = null,
        note: String? = null,
        payloadJson: String = "{}",
        schemaVersion: Int = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
        photoLocalPaths: List<String> = emptyList(),
    ): Long {
        validateSleepInterval(type, timestamp, endTimestamp)
        val photos = normalizePhotoPaths(photoLocalPaths)
        val userId = ensureLocalUser(System.currentTimeMillis())
        val now = System.currentTimeMillis()
        val clientUuid = newClientUuid()
        val record = RecordEntity(
            clientUuid = clientUuid,
            babyId = babyId,
            type = type.key,
            timestamp = timestamp,
            endTimestamp = endTimestamp,
            note = note,
            createdByUserId = userId,
            createdByDeviceId = writerDeviceId(),
            payloadJson = withLocalPhotoPaths(payloadJson, photos),
            schemaVersion = schemaVersion,
            updatedAt = now,
        )
        val id = if (type == RecordType.SLEEP && endTimestamp == null) {
            sleepMutationMutex.withLock {
                transactionRunner.run {
                    requireActiveBaby(babyId)
                    healDuplicateOpenSleeps(babyId)
                    if (recordDao.findOpenSleep(babyId) != null) {
                        throw SleepStateChangedException()
                    }
                    val inserted = insertRecord(record)
                    reconcileRecordPhotos(inserted, photos, now)
                    inserted
                }
            }
        } else {
            transactionRunner.run {
                requireActiveBaby(babyId)
                val inserted = insertRecord(record)
                reconcileRecordPhotos(inserted, photos, now)
                inserted
            }
        }
        requestLocalSync()
        return id
    }

    suspend fun updateRecord(
        id: Long,
        timestamp: Long,
        endTimestamp: Long?,
        note: String?,
        payloadJson: String,
        schemaVersion: Int = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
        photoLocalPaths: List<String> = emptyList(),
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        // Future time must use [convertRecordToCarePlan] after explicit UI confirm.
        RecordTime.pointError(timestamp, nowMillis)?.let {
            throw IllegalArgumentException(it)
        }
        val photos = normalizePhotoPaths(photoLocalPaths)
        val now = System.currentTimeMillis()
        sleepMutationMutex.withLock {
            transactionRunner.run {
                val existing = recordDao.get(id) ?: return@run
                requireActiveBaby(existing.babyId)
                val type = RecordType.fromKey(existing.type)
                if (
                    type == RecordType.SLEEP &&
                    existing.endTimestamp != null &&
                    endTimestamp == null
                ) {
                    throw IllegalArgumentException("已完成的睡眠不可改为进行中")
                }
                validateSleepInterval(type, timestamp, endTimestamp)
                updateRecordEntity(
                    existing.copy(
                        timestamp = timestamp,
                        endTimestamp = endTimestamp,
                        note = note,
                        payloadJson = withLocalPhotoPaths(payloadJson, photos),
                        schemaVersion = schemaVersion,
                        updatedAt = now,
                    ),
                )
                reconcileRecordPhotos(id, photos, now)
            }
        }
        requestLocalSync()
    }

    /**
     * Explicit conversion of an existing fact Record whose time was edited to the future.
     *
     * Single local transaction: soft-delete the record (media tombstones), create a pending
     * CarePlan transferring type/payload/note/photos, and store [CarePlan.sourceRecordClientUuid]
     * for later family-sync provenance. Any failure rolls back fully so the original record and
     * attachments stay visible with no dual-active media ownership.
     *
     * Does not start nursing timers or open sleep intervals. Post-commit reminder/projection
     * reuses the same path as [createCarePlan].
     *
     * @return new care plan local id
     */
    suspend fun convertRecordToCarePlan(
        recordId: Long,
        scheduledAt: Long,
        note: String? = null,
        payloadJson: String? = null,
        schemaVersion: Int = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
        photoLocalPaths: List<String> = emptyList(),
        zone: ZoneId = ZoneId.systemDefault(),
        nowMillis: Long = System.currentTimeMillis(),
        projectToSystemCalendar: Boolean = true,
    ): Long {
        require(scheduledAt > nowMillis) { "转为护理计划须选择未来时刻" }
        val photos = normalizePhotoPaths(photoLocalPaths)
        val peek = recordDao.get(recordId) ?: error("记录不存在")
        if (peek.deletedAt != null) error("记录已删除")
        val type = RecordType.fromKey(peek.type) ?: error("未知记录类型")
        require(type != RecordType.MEMO && type != RecordType.OTHER) {
            "备注/其他不可转为护理计划"
        }
        require(type.isPlanableCarePlanType || type == RecordType.CUSTOM) {
            "该项目不可转为护理计划"
        }

        suspend fun writeConvert(): Long = transactionRunner.run {
            val existing = recordDao.get(recordId) ?: error("记录不存在")
            if (existing.deletedAt != null) error("记录已删除")
            requireActiveBaby(existing.babyId)
            val resolvedType = RecordType.fromKey(existing.type) ?: error("未知记录类型")
            require(resolvedType != RecordType.MEMO && resolvedType != RecordType.OTHER) {
                "备注/其他不可转为护理计划"
            }
            require(resolvedType.isPlanableCarePlanType || resolvedType == RecordType.CUSTOM) {
                "该项目不可转为护理计划"
            }
            val nextPayload = payloadJson ?: existing.payloadJson
            val resolvedCustomItemId: Long?
            val stampedPayload: String
            if (resolvedType == RecordType.CUSTOM) {
                val decoded = RecordPayloadCodec.decode(
                    RecordType.CUSTOM,
                    nextPayload,
                    schemaVersion,
                ).payload as? CustomPayload
                val id = decoded?.customItemId?.takeIf { it > 0L }
                    ?: error("具体自定义项目才可转为护理计划")
                resolvedCustomItemId = id
                val def = customItemDao.getById(id)
                stampedPayload = if (def != null && def.deletedAt == null) {
                    stampCustomItemSnapshotIntoPayload(
                        payloadJson = nextPayload,
                        customItemId = id,
                        titleSnapshot = def.name,
                        iconSlot = def.iconSlot,
                    )
                } else {
                    // Keep historical name/icon snapshot when definition is gone.
                    nextPayload
                }
            } else {
                resolvedCustomItemId = null
                stampedPayload = nextPayload
            }

            val at = nextSyncUpdatedAt(existing.updatedAt, System.currentTimeMillis())
            recordDao.softDelete(recordId, at)
            tombstoneRecordPhotos(recordId, at)

            // Plan media rows are new ownership (separate clientUuids); record media
            // remain tombstoned only. Same localUri may be referenced by both, but
            // only plan rows stay active after commit.
            val planId = carePlanDao.upsert(
                CarePlanEntity(
                    clientUuid = newClientUuid(),
                    babyId = existing.babyId,
                    type = resolvedType.key,
                    customItemId = resolvedCustomItemId,
                    scheduledAt = scheduledAt,
                    scheduledZoneId = zone.id,
                    note = note,
                    payloadJson = withLocalPhotoPaths(stampedPayload, photos),
                    schemaVersion = schemaVersion,
                    status = CarePlanStatus.PENDING.storageKey,
                    createdByMembershipId = currentMembershipActorId(),
                    sourceRecordClientUuid = existing.clientUuid,
                    updatedAt = at,
                    syncDirty = true,
                ),
            )
            reconcileCarePlanPhotos(planId, photos, at)
            planId
        }

        val planId = if (type == RecordType.SLEEP) {
            // Same mutex as soft-delete/open-sleep so convert cannot leave half-live intervals.
            sleepMutationMutex.withLock { writeConvert() }
        } else {
            writeConvert()
        }
        // Creator keeps full local plan + projection immediately; family wait on package.
        carePlanDao.get(planId)?.toModel()?.let { plan ->
            projectOrScheduleCarePlanReminder(
                plan,
                projectToSystemCalendar = projectToSystemCalendar,
            )
        }
        requestLocalSync()
        return planId
    }

    suspend fun deleteRecord(id: Long) {
        sleepMutationMutex.withLock {
            transactionRunner.run {
                val existing = recordDao.get(id)
                if (existing != null) {
                    val deletedAt = nextSyncUpdatedAt(
                        existing.updatedAt,
                        System.currentTimeMillis(),
                    )
                    recordDao.softDelete(id, deletedAt)
                    tombstoneRecordPhotos(id, deletedAt)
                }
                existing
            }
        }
        requestLocalSync()
    }

    /**
     * Active record photo local paths for [recordId].
     *
     * Prefers MediaAsset rows (common attachment). Falls back to the payload
     * `photos[]` replica so historical diary/memo rows remain lossless until
     * the next edit normalizes them onto media_assets.
     */
    suspend fun listRecordPhotoPaths(recordId: Long): List<String> {
        val active = mediaAssetDao.listActiveForRecord(recordId)
            .map(MediaAssetEntity::localUri)
            .filter { it.isNotBlank() }
        if (active.isNotEmpty()) return active
        val record = recordDao.get(recordId) ?: return emptyList()
        return localPhotoPaths(record.payloadJson)
    }

    /**
     * True when this record already has family-published media receipts, so a
     * local dirty re-publish is a mutation (receivers still see the prior version).
     *
     * Includes tombstoned media: a photo removed in the new draft still proves a
     * prior complete package was family-visible until the mutation package commits.
     */
    suspend fun recordHasPriorFamilyRevision(recordId: Long): Boolean =
        mediaAssetDao.listForRecord(recordId).any { asset ->
            asset.kind == "log" && !asset.remoteUri.isNullOrBlank()
        }

    /**
     * True when this plan already has family-published media receipts, so a
     * local dirty re-publish is a mutation (receivers still see the prior version).
     * Empty photo plans treat a prior non-dirty revision as proof via remoteUri
     * on any plan media row (including tombstones).
     */
    suspend fun carePlanHasPriorFamilyRevision(carePlanId: Long): Boolean =
        mediaAssetDao.listForCarePlan(carePlanId).any { asset ->
            asset.kind == "log" && !asset.remoteUri.isNullOrBlank()
        }

    suspend fun completeNursing(
        babyId: Long,
        leftMin: Int,
        rightMin: Int,
        order: String,
        amountMl: Int? = null,
        note: String? = null,
        startedAt: Long,
        endedAt: Long,
        recordMode: String = "end",
        completionClientUuid: String = newClientUuid(),
        /**
         * When set, complete this open nursing CarePlan in the same transaction as
         * the timer record. Cancel / save-failure leave the plan pending.
         */
        carePlanId: Long? = null,
    ): Long {
        require(order in NURSING_ORDER_ALLOWLIST) {
            "不支持的哺乳顺序"
        }
        require(recordMode in setOf("start", "end")) {
            "不支持的记录时刻模式"
        }
        val payload = RecordPayloadCodec.encode(
            RecordPayloadDocument(
                type = RecordType.NURSING,
                payload = NursingPayload(
                    leftMinutes = leftMin,
                    rightMinutes = rightMin,
                    order = order,
                    amountMl = amountMl,
                    recordMode = recordMode,
                ),
                schemaVersion = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
            ),
        )
        require(completionClientUuid.isNotBlank()) { "计时完成标识不能为空" }
        val userId = ensureLocalUser(System.currentTimeMillis())
        val now = System.currentTimeMillis()
        val id = transactionRunner.run {
            val existing = recordDao.getByClientUuid(completionClientUuid)
            if (existing != null) {
                check(existing.deletedAt == null) {
                    "这次计时记录已删除，请重试或改记"
                }
                require(existing.babyId == babyId && existing.type == RecordType.NURSING.key) {
                    "计时完成标识与既有记录冲突"
                }
                // Idempotent replay: if a plan was linked, ensure it is completed
                // against this same record (no second session) and candidate identity.
                if (carePlanId != null) {
                    completeOpenCarePlanWithRecord(
                        carePlanId = carePlanId,
                        babyId = babyId,
                        expectedType = RecordType.NURSING,
                        recordClientUuid = existing.clientUuid,
                        now = now,
                        actualTimestamp = existing.timestamp,
                    )
                }
                return@run existing.id
            }
            requireActiveBaby(babyId)
            val recordTimestamp = if (recordMode == "start") startedAt else endedAt
            val inserted = insertRecord(
                RecordEntity(
                    clientUuid = completionClientUuid,
                    babyId = babyId,
                    type = RecordType.NURSING.key,
                    timestamp = recordTimestamp,
                    endTimestamp = endedAt.takeIf { recordMode == "start" },
                    note = note,
                    createdByUserId = userId,
                    createdByDeviceId = writerDeviceId(),
                    payloadJson = payload,
                    schemaVersion = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
                    updatedAt = now,
                ),
            )
            if (carePlanId != null) {
                completeOpenCarePlanWithRecord(
                    carePlanId = carePlanId,
                    babyId = babyId,
                    expectedType = RecordType.NURSING,
                    recordClientUuid = completionClientUuid,
                    now = now,
                    actualTimestamp = recordTimestamp,
                )
            }
            inserted
        }
        if (carePlanId != null) {
            reminderCleanup.cancelCarePlan(carePlanId)
            removeSystemCalendarProjection(carePlanId)
        }
        requestLocalSync()
        return id
    }

    /**
     * Confirm a stateful sleep action against the latest open interval.
     *
     * The check and local write share one process-level critical section so
     * two confirmations cannot both act on the same observed sleep state.
     */
    suspend fun confirmSleep(
        babyId: Long,
        expectedOpenSleepId: Long?,
        timestamp: Long,
        endTimestamp: Long?,
        note: String?,
        payloadJson: String,
        schemaVersion: Int = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
        photoLocalPaths: List<String> = emptyList(),
    ): Long {
        val photos = normalizePhotoPaths(photoLocalPaths)
        val id = sleepMutationMutex.withLock {
            validateSleepInterval(RecordType.SLEEP, timestamp, endTimestamp)
            transactionRunner.run {
                requireActiveBaby(babyId)
                healDuplicateOpenSleeps(babyId)
                val currentOpen = recordDao.findOpenSleep(babyId)
                if (expectedOpenSleepId == null) {
                    if (currentOpen != null) throw SleepStateChangedException()
                    val now = System.currentTimeMillis()
                    val inserted = insertRecord(
                        RecordEntity(
                            clientUuid = newClientUuid(),
                            babyId = babyId,
                            type = RecordType.SLEEP.key,
                            timestamp = timestamp,
                            endTimestamp = endTimestamp,
                            note = note,
                            createdByUserId = ensureLocalUser(now),
                            createdByDeviceId = writerDeviceId(),
                            payloadJson = withLocalPhotoPaths(payloadJson, photos),
                            schemaVersion = schemaVersion,
                            updatedAt = now,
                        ),
                    )
                    reconcileRecordPhotos(inserted, photos, now)
                    inserted
                } else {
                    if (currentOpen?.id != expectedOpenSleepId) {
                        throw SleepStateChangedException()
                    }
                    val now = System.currentTimeMillis()
                    updateRecordEntity(
                        currentOpen.copy(
                            timestamp = timestamp,
                            endTimestamp = endTimestamp,
                            note = note,
                            payloadJson = withLocalPhotoPaths(payloadJson, photos),
                            schemaVersion = schemaVersion,
                            updatedAt = now,
                        ),
                    )
                    reconcileRecordPhotos(expectedOpenSleepId, photos, now)
                    expectedOpenSleepId
                }
            }
        }
        requestLocalSync()
        return id
    }

    suspend fun sleepDown(
        babyId: Long,
        at: Long = System.currentTimeMillis(),
    ): Long {
        val id = sleepMutationMutex.withLock {
            transactionRunner.run {
                requireActiveBaby(babyId)
                healDuplicateOpenSleeps(babyId)
                val open = recordDao.findOpenSleep(babyId)
                if (open != null) {
                    val flagged = withAnomaly(open.payloadJson, open.schemaVersion)
                    if (flagged.first != open.payloadJson) {
                        updateRecordEntity(
                            open.copy(
                                payloadJson = flagged.first,
                                schemaVersion = flagged.second,
                                updatedAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                    return@run open.id
                }

                val now = System.currentTimeMillis()
                insertRecord(
                    RecordEntity(
                        clientUuid = newClientUuid(),
                        babyId = babyId,
                        type = RecordType.SLEEP.key,
                        timestamp = at,
                        endTimestamp = null,
                        note = null,
                        createdByUserId = ensureLocalUser(now),
                        createdByDeviceId = writerDeviceId(),
                        payloadJson = "{}",
                        updatedAt = now,
                    ),
                )
            }
        }
        requestLocalSync()
        return id
    }

    suspend fun sleepUp(
        babyId: Long,
        at: Long = System.currentTimeMillis(),
    ): Long {
        val id = sleepMutationMutex.withLock {
            transactionRunner.run {
                requireActiveBaby(babyId)
                healDuplicateOpenSleeps(babyId)
                val open = recordDao.findOpenSleep(babyId)
                if (open != null) {
                    validateSleepInterval(RecordType.SLEEP, open.timestamp, at)
                    updateRecordEntity(
                        open.copy(
                            endTimestamp = at,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                    return@run open.id
                }

                val now = System.currentTimeMillis()
                val start = at - 60_000L
                insertRecord(
                    RecordEntity(
                        clientUuid = newClientUuid(),
                        babyId = babyId,
                        type = RecordType.SLEEP.key,
                        timestamp = start,
                        endTimestamp = at,
                        note = null,
                        createdByUserId = ensureLocalUser(now),
                        createdByDeviceId = writerDeviceId(),
                        payloadJson = RecordPayloadCodec.encode(
                            RecordPayloadDocument(
                                type = RecordType.SLEEP,
                                payload = SleepPayload(anomaly = true),
                                schemaVersion = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
                            ),
                        ),
                        schemaVersion = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
                        updatedAt = now,
                    ),
                )
            }
        }
        requestLocalSync()
        return id
    }

    suspend fun getRecord(id: Long): Record? = recordDao.get(id)?.toModel()

    suspend fun getCarePlan(id: Long): CarePlan? = carePlanDao.get(id)?.toModel()

    /**
     * Pending/missed plans for the selected local day (non-today views).
     * Never mixed into record list/summary/search/export.
     */
    fun observeDayPendingPlans(
        babyId: Long,
        day: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Flow<List<CarePlan>> {
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return carePlanDao.observeDayPending(babyId, start, end).map { rows ->
            rows.map { it.toModel() }
        }
    }

    /**
     * Today: all overdue open plans plus today's not-yet-due plans, by scheduledAt ASC.
     */
    fun observeTodayPendingPlans(
        babyId: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        nowMillis: Long = System.currentTimeMillis(),
    ): Flow<List<CarePlan>> {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return carePlanDao.observeTodayPending(babyId, start, end, nowMillis).map { rows ->
            rows.map { it.toModel() }
                .sortedWith(
                    compareBy<CarePlan> {
                        // Missed first, then still-pending for today.
                        when (it.effectiveStatus(nowMillis)) {
                            CarePlanStatus.MISSED -> 0
                            else -> 1
                        }
                    }.thenBy { it.scheduledAt },
                )
        }
    }

    /**
     * Create a local care plan for a concrete built-in (including intent-only
     * nursing/sleep) or custom item. Future scheduled time only — past/current
     * must use [addRecord]. Schedule never starts timers or open sleep intervals.
     */
    suspend fun createCarePlan(
        babyId: Long,
        type: RecordType,
        scheduledAt: Long,
        note: String? = null,
        payloadJson: String = "{}",
        schemaVersion: Int = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
        customItemId: Long? = null,
        photoLocalPaths: List<String> = emptyList(),
        zone: ZoneId = ZoneId.systemDefault(),
        nowMillis: Long = System.currentTimeMillis(),
        /**
         * Default-on per-plan projection (ticket 21). When false, only Lezi
         * reminders are used. Unconfigured / permission deny still saves the plan.
         */
        projectToSystemCalendar: Boolean = true,
    ): Long {
        require(type != RecordType.MEMO && type != RecordType.OTHER) {
            "备注/其他不可新建护理计划"
        }
        require(type.isPlanableCarePlanType || type == RecordType.CUSTOM) {
            "该项目不可新建护理计划"
        }
        require(scheduledAt > nowMillis) { "安排护理须选择未来时刻" }
        requireActiveBaby(babyId)
        val resolvedCustomItemId: Long?
        val stampedPayload: String
        if (type == RecordType.CUSTOM) {
            val id = customItemId?.takeIf { it > 0L }
                ?: error("具体自定义项目才可安排护理计划")
            val def = customItemDao.getById(id)
                ?: error("自定义项目不存在或已删除")
            if (def.deletedAt != null) error("自定义项目已删除")
            resolvedCustomItemId = id
            // Domain-stamps name/icon snapshots so hide/rename later still fulfill/display.
            stampedPayload = stampCustomItemSnapshotIntoPayload(
                payloadJson = payloadJson,
                customItemId = id,
                titleSnapshot = def.name,
                iconSlot = def.iconSlot,
            )
        } else {
            resolvedCustomItemId = null
            stampedPayload = payloadJson
        }
        val photos = normalizePhotoPaths(photoLocalPaths)
        val now = System.currentTimeMillis()
        val id = transactionRunner.run {
            val planId = carePlanDao.upsert(
                CarePlanEntity(
                    clientUuid = newClientUuid(),
                    babyId = babyId,
                    type = type.key,
                    customItemId = resolvedCustomItemId,
                    scheduledAt = scheduledAt,
                    scheduledZoneId = zone.id,
                    note = note,
                    payloadJson = withLocalPhotoPaths(stampedPayload, photos),
                    schemaVersion = schemaVersion,
                    status = CarePlanStatus.PENDING.storageKey,
                    createdByMembershipId = currentMembershipActorId(),
                    updatedAt = now,
                    syncDirty = true,
                ),
            )
            reconcileCarePlanPhotos(planId, photos, now)
            planId
        }
        // Creator: full local plan + own reminders/calendar immediately (amber until publish).
        carePlanDao.get(id)?.toModel()?.let { plan ->
            projectOrScheduleCarePlanReminder(
                plan,
                projectToSystemCalendar = projectToSystemCalendar,
            )
        }
        requestLocalSync()
        return id
    }

    /**
     * Fulfill a pending/missed plan: confirm non-future actual time, insert linked
     * Record and complete the plan in one transaction. Any active family member may
     * fulfill any plan; manage rights (edit/skip/delete) stay author/admin-only.
     *
     * Also writes a durable [FulfillmentCandidate] with a stable UUID and immutable
     * confirm time so family publish/retry shares one candidate identity.
     *
     * Nursing manual 补记 uses this path; timer completion uses [completeNursing]
     * with [carePlanId]. Sleep uses the open-sleep mutex and may create an open
     * interval ([endTimestamp] null) or a closed interval.
     *
     * @return the new record id
     */
    suspend fun fulfillCarePlan(
        carePlanId: Long,
        actualTimestamp: Long,
        endTimestamp: Long? = null,
        note: String? = null,
        payloadJson: String? = null,
        schemaVersion: Int = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
        photoLocalPaths: List<String> = emptyList(),
        nowMillis: Long = System.currentTimeMillis(),
    ): Long {
        RecordTime.pointError(actualTimestamp, nowMillis)?.let { throw IllegalArgumentException(it) }
        val photos = normalizePhotoPaths(photoLocalPaths)
        val userId = ensureLocalUser(System.currentTimeMillis())
        // Freeze confirm time once for the candidate; wall clock for writer bookkeeping.
        val confirmedAt = System.currentTimeMillis()
        val now = confirmedAt

        // Peek type to decide whether sleep mutex is required (fail closed on races).
        val planPeek = carePlanDao.get(carePlanId) ?: error("护理计划不存在")
        val planType = RecordType.fromKey(planPeek.type) ?: error("未知记录类型")

        suspend fun writeFulfill(): Long = transactionRunner.run {
            val plan = carePlanDao.get(carePlanId)
                ?: error("护理计划不存在")
            if (plan.deletedAt != null) error("护理计划已删除")
            val status = CarePlanStatus.fromStorage(plan.status)
            require(status == CarePlanStatus.PENDING || status == CarePlanStatus.MISSED) {
                "该护理计划不可履行"
            }
            // Fulfill is allowed for any member; no author manage ACL here.
            requireActiveBaby(plan.babyId)
            val type = RecordType.fromKey(plan.type) ?: error("未知记录类型")
            val resolvedEnd = endTimestamp
            if (type == RecordType.SLEEP) {
                validateSleepInterval(RecordType.SLEEP, actualTimestamp, resolvedEnd)
                healDuplicateOpenSleeps(plan.babyId)
                val currentOpen = recordDao.findOpenSleep(plan.babyId)
                // Open-interval fulfill and closed-interval fulfill both require
                // no competing open sleep for a different interval.
                if (currentOpen != null) {
                    throw SleepStateChangedException()
                }
            } else if (resolvedEnd != null) {
                // Non-sleep types do not use interval ends on fulfill.
                error("该项目履行不支持结束时间")
            }
            val recordClientUuid = newClientUuid()
            val record = RecordEntity(
                clientUuid = recordClientUuid,
                babyId = plan.babyId,
                type = type.key,
                timestamp = actualTimestamp,
                endTimestamp = resolvedEnd,
                note = note ?: plan.note,
                createdByUserId = userId,
                createdByDeviceId = writerDeviceId(),
                payloadJson = withLocalPhotoPaths(payloadJson ?: plan.payloadJson, photos),
                schemaVersion = schemaVersion,
                updatedAt = now,
            )
            val inserted = insertRecord(record)
            reconcileRecordPhotos(inserted, photos, now)
            // Manager (creator/owner) may LWW-push completed plan status. Non-managers
            // complete only locally — server forbids care_plan rewrites for them;
            // peers re-link via fulfillment_candidate + resolveFulfillmentAuthority.
            val publishPlanCompletion = actorCanManageCarePlan(plan)
            carePlanDao.update(
                plan.copy(
                    status = CarePlanStatus.COMPLETED.storageKey,
                    fulfilledRecordClientUuid = recordClientUuid,
                    fulfilledAt = confirmedAt,
                    updatedAt = nextSyncUpdatedAt(plan.updatedAt, now),
                    syncDirty = publishPlanCompletion,
                ),
            )
            ensureFulfillmentCandidate(
                carePlanClientUuid = plan.clientUuid,
                recordClientUuid = recordClientUuid,
                actualTimestamp = actualTimestamp,
                confirmedAt = confirmedAt,
            )
            // Local multi-candidate sets (rare) re-link the plan to the authority.
            resolveFulfillmentAuthorityForPlan(plan.clientUuid)
            inserted
        }

        val recordId = if (planType == RecordType.SLEEP) {
            sleepMutationMutex.withLock { writeFulfill() }
        } else {
            writeFulfill()
        }
        reminderCleanup.cancelCarePlan(carePlanId)
        removeSystemCalendarProjection(carePlanId)
        requestLocalSync()
        return recordId
    }

    /**
     * Mark an open care plan completed against [recordClientUuid] inside an
     * already-open transaction. Idempotent when already completed with the same
     * record; refuses double-complete with a different record. Always ensures a
     * durable fulfillment candidate for the plan+record pair (nursing timer path).
     */
    private suspend fun completeOpenCarePlanWithRecord(
        carePlanId: Long,
        babyId: Long,
        expectedType: RecordType,
        recordClientUuid: String,
        now: Long,
        actualTimestamp: Long,
    ) {
        val plan = carePlanDao.get(carePlanId) ?: error("护理计划不存在")
        if (plan.deletedAt != null) error("护理计划已删除")
        require(plan.babyId == babyId) { "护理计划与宝宝不匹配" }
        require(plan.type == expectedType.key) { "护理计划类型不匹配" }
        val status = CarePlanStatus.fromStorage(plan.status)
        if (status == CarePlanStatus.COMPLETED) {
            require(plan.fulfilledRecordClientUuid == recordClientUuid) {
                "该护理计划已由其他记录完成"
            }
            // Retry path: keep the same candidate identity for this pair.
            ensureFulfillmentCandidate(
                carePlanClientUuid = plan.clientUuid,
                recordClientUuid = recordClientUuid,
                actualTimestamp = actualTimestamp,
                confirmedAt = plan.fulfilledAt ?: now,
            )
            return
        }
        require(status == CarePlanStatus.PENDING || status == CarePlanStatus.MISSED) {
            "该护理计划不可履行"
        }
        // Same ACL as fulfillCarePlan: only managers publish plan completion.
        val publishPlanCompletion = actorCanManageCarePlan(plan)
        carePlanDao.update(
            plan.copy(
                status = CarePlanStatus.COMPLETED.storageKey,
                fulfilledRecordClientUuid = recordClientUuid,
                fulfilledAt = now,
                updatedAt = nextSyncUpdatedAt(plan.updatedAt, now),
                syncDirty = publishPlanCompletion,
            ),
        )
        ensureFulfillmentCandidate(
            carePlanClientUuid = plan.clientUuid,
            recordClientUuid = recordClientUuid,
            actualTimestamp = actualTimestamp,
            confirmedAt = now,
        )
        resolveFulfillmentAuthorityForPlan(plan.clientUuid)
    }

    /**
     * Create or re-dirty the durable fulfillment candidate for a plan+record pair.
     * Same-device retries reuse the existing [FulfillmentCandidateEntity.clientUuid]
     * so outbox republish never invents a second candidate identity.
     *
     * Must run inside the caller's transaction.
     */
    private suspend fun ensureFulfillmentCandidate(
        carePlanClientUuid: String,
        recordClientUuid: String,
        actualTimestamp: Long,
        confirmedAt: Long,
    ) {
        val session = syncPort.session().first()
        // Offline trail for multi-device authority until server freeze lands on pull.
        // Server overwrites membership/role/confirmed_at on first accept; we still
        // stamp local role so admin fulfills adjudicate correctly on the originator.
        val localMembershipId = session.membershipId.trim()
        val localRole = when (session.role) {
            com.lezi.babylog.sync.FamilyRole.Owner -> "owner"
            com.lezi.babylog.sync.FamilyRole.Member -> "member"
            com.lezi.babylog.sync.FamilyRole.None -> ""
        }
        val existing = fulfillmentCandidateDao.listForCarePlan(carePlanClientUuid)
            .firstOrNull { it.recordClientUuid == recordClientUuid && it.deletedAt == null }
        if (existing != null) {
            if (!existing.syncDirty) {
                fulfillmentCandidateDao.update(
                    existing.copy(
                        syncDirty = true,
                        // Never rewrite immutable confirm time or candidate uuid.
                        // Fill empty offline trails only (server freeze may already be present).
                        submitterMembershipId = existing.submitterMembershipId
                            .ifBlank { localMembershipId },
                        submitterRole = existing.submitterRole.ifBlank { localRole },
                        updatedAt = confirmedAt.coerceAtLeast(existing.updatedAt + 1),
                    ),
                )
            }
            return
        }
        fulfillmentCandidateDao.upsert(
            FulfillmentCandidateEntity(
                clientUuid = newClientUuid(),
                carePlanClientUuid = carePlanClientUuid,
                recordClientUuid = recordClientUuid,
                actualTimestamp = actualTimestamp,
                confirmedAt = confirmedAt,
                submitterMembershipId = localMembershipId,
                submitterRole = localRole,
                updatedAt = confirmedAt,
                syncDirty = true,
            ),
        )
    }

    /**
     * Deterministic multi-candidate authority for one care plan.
     *
     * Points [CarePlan.fulfilledRecordClientUuid] at the single winner, marks losers
     * [FulfillmentAdoptionStatus.CONFLICT_NOT_ADOPTED], and never deletes Record/photos.
     * Local-only: does not dirty the plan or candidates for republish (every device
     * re-derives from frozen submitter/confirmed_at evidence after pull).
     *
     * Idempotent; safe to re-run after every apply of candidates for the plan.
     */
    suspend fun resolveFulfillmentAuthorityForPlan(carePlanClientUuid: String) {
        val live = fulfillmentCandidateDao.listForCarePlan(carePlanClientUuid)
            .filter { it.deletedAt == null }
        if (live.isEmpty()) return
        val resolution = FulfillmentAuthority.resolve(
            live.map {
                FulfillmentCandidateEvidence(
                    clientUuid = it.clientUuid,
                    recordClientUuid = it.recordClientUuid,
                    confirmedAt = it.confirmedAt,
                    submitterRole = it.submitterRole,
                )
            },
        ) ?: return
        // Local marks only — keep updatedAt/syncDirty so we do not republish.
        val patches = FulfillmentAuthority.adoptionStatusPatches(
            liveClientUuidToStatus = live.associate { it.clientUuid to it.adoptionStatus },
            resolution = resolution,
        )
        if (patches.isNotEmpty()) {
            val byUuid = live.associateBy { it.clientUuid }
            for ((clientUuid, status) in patches) {
                val candidate = byUuid[clientUuid] ?: continue
                fulfillmentCandidateDao.update(candidate.copy(adoptionStatus = status))
            }
        }
        val plan = carePlanDao.getByClientUuid(carePlanClientUuid) ?: return
        if (plan.deletedAt != null) return
        if (
            !FulfillmentAuthority.needsPlanRelink(
                currentStatusStorageKey = plan.status,
                currentFulfilledRecordClientUuid = plan.fulfilledRecordClientUuid,
                currentFulfilledAt = plan.fulfilledAt,
                resolution = resolution,
            )
        ) {
            return
        }
        carePlanDao.update(
            plan.copy(
                status = CarePlanStatus.COMPLETED.storageKey,
                fulfilledRecordClientUuid = resolution.winnerRecordClientUuid,
                fulfilledAt = resolution.winnerConfirmedAt,
                // Local re-link only; LWW plan push order must not fight resolution.
                updatedAt = plan.updatedAt,
                syncDirty = plan.syncDirty,
            ),
        )
    }

    /** Test/debug surface: candidates linked to a plan's portable id. */
    suspend fun listFulfillmentCandidatesForPlan(carePlanClientUuid: String): List<FulfillmentCandidate> =
        fulfillmentCandidateDao.listForCarePlan(carePlanClientUuid).map { it.toModel() }

    /**
     * Ordinary-surface filter: drop records that lost multi-candidate fulfillment.
     * Records and photos remain stored for audit / ticket 27 conversion.
     */
    suspend fun filterOrdinaryRecords(records: List<Record>): List<Record> {
        if (records.isEmpty()) return records
        val blocked = fulfillmentCandidateDao.listConflictNotAdoptedRecordUuids().toSet()
        if (blocked.isEmpty()) return records
        return records.filter { it.clientUuid !in blocked }
    }

    suspend fun isOrdinarySurfaceRecord(clientUuid: String): Boolean {
        val linked = fulfillmentCandidateDao.listForRecord(clientUuid)
            .filter { it.deletedAt == null }
        if (linked.isEmpty()) return true
        return linked.none { it.adoptionStatus == FulfillmentAdoptionStatus.CONFLICT_NOT_ADOPTED }
    }

    /** True when the joined session is family owner/admin. */
    suspend fun isFamilyAdmin(): Boolean {
        val session = syncPort.session().first()
        return session.role == com.lezi.babylog.sync.FamilyRole.Owner
    }

    /**
     * Admin-only conflict-not-adopted audits for a plan (or all babies when [carePlanClientUuid]
     * is null). Non-admins receive an empty list — button hide is not the only gate.
     */
    suspend fun listConflictNotAdoptedAudits(
        carePlanClientUuid: String? = null,
        babyId: Long? = null,
    ): List<ConflictNotAdoptedAudit> {
        if (!isFamilyAdmin()) return emptyList()
        val candidates = if (carePlanClientUuid != null) {
            fulfillmentCandidateDao.listConflictNotAdoptedForCarePlan(carePlanClientUuid)
        } else {
            fulfillmentCandidateDao.listConflictNotAdopted()
        }
        if (candidates.isEmpty()) return emptyList()
        val nameByMembership = resolveSubmitterDisplayNames(candidates)
        return candidates.mapNotNull { candidate ->
            buildConflictNotAdoptedAudit(candidate, nameByMembership)
        }.filter { babyId == null || it.babyId == babyId }
    }

    /**
     * Admin-only single audit detail. Null when missing, not conflict-not-adopted,
     * or the actor is not an admin (fail closed for deep links / direct calls).
     */
    suspend fun getConflictNotAdoptedAudit(
        candidateClientUuid: String,
    ): ConflictNotAdoptedAudit? {
        if (!isFamilyAdmin()) return null
        val candidate = fulfillmentCandidateDao.getByClientUuid(candidateClientUuid)
            ?: return null
        if (candidate.deletedAt != null) return null
        if (candidate.adoptionStatus != FulfillmentAdoptionStatus.CONFLICT_NOT_ADOPTED) {
            return null
        }
        val nameByMembership = resolveSubmitterDisplayNames(listOf(candidate))
        return buildConflictNotAdoptedAudit(candidate, nameByMembership)
    }

    /**
     * Admin-only: create a new ordinary Record from a conflict-not-adopted candidate.
     *
     * Does **not** flip [FulfillmentCandidate.adoptionStatus] or re-link
     * [CarePlan.fulfilledRecordClientUuid]. Copies fields/photos into a new record
     * identity with new media ownership; keeps the loser row for audit.
     *
     * Idempotent via [FulfillmentCandidateEntity.convertedRecordClientUuid]: retries
     * after partial sync failure return the same independent record id.
     *
     * @return local id of the independent ordinary Record
     */
    suspend fun convertConflictNotAdoptedToIndependentRecord(
        candidateClientUuid: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): Long {
        if (!isFamilyAdmin()) throw ConflictAuditPermissionException()

        suspend fun writeConvert(): Long = transactionRunner.run {
            val candidate = fulfillmentCandidateDao.getByClientUuid(candidateClientUuid)
                ?: error("冲突未采纳履行不存在")
            if (candidate.deletedAt != null) error("冲突未采纳履行已删除")
            require(candidate.adoptionStatus == FulfillmentAdoptionStatus.CONFLICT_NOT_ADOPTED) {
                "仅冲突未采纳履行可转为独立记录"
            }

            val existingPointer = candidate.convertedRecordClientUuid.trim()
            if (existingPointer.isNotEmpty()) {
                val existing = recordDao.getByClientUuid(existingPointer)
                if (existing != null && existing.deletedAt == null) {
                    return@run existing.id
                }
            }

            val source = recordDao.getByClientUuid(candidate.recordClientUuid)
                ?: error("未采纳履行对应的记录不存在")
            // Prefer live loser photos; payload replica is fallback for historical rows.
            val photos = if (source.deletedAt == null) {
                listRecordPhotoPaths(source.id)
            } else {
                localPhotoPaths(source.payloadJson)
            }
            val targetUuid = existingPointer.ifEmpty { newClientUuid() }
            val at = nowMillis.coerceAtLeast(source.updatedAt + 1)
            val userId = ensureLocalUser(at)
            val type = RecordType.fromKey(source.type) ?: error("未知记录类型")
            if (type == RecordType.SLEEP) {
                validateSleepInterval(type, source.timestamp, source.endTimestamp)
            }
            requireActiveBaby(source.babyId)
            if (type == RecordType.SLEEP && source.endTimestamp == null) {
                // Open sleep from a fulfill is unexpected; still guard open-sleep invariants.
                healDuplicateOpenSleeps(source.babyId)
                if (recordDao.findOpenSleep(source.babyId) != null) {
                    throw SleepStateChangedException()
                }
            }
            val newRecord = RecordEntity(
                // Reuse pointer uuid on retry so we never mint a second ordinary fact.
                id = recordDao.getByClientUuid(targetUuid)?.id ?: 0L,
                clientUuid = targetUuid,
                babyId = source.babyId,
                type = source.type,
                timestamp = source.timestamp,
                endTimestamp = source.endTimestamp,
                note = source.note,
                createdByUserId = userId,
                createdByDeviceId = writerDeviceId(),
                payloadJson = withLocalPhotoPaths(source.payloadJson, photos),
                schemaVersion = source.schemaVersion,
                updatedAt = at,
                deletedAt = null,
                syncDirty = true,
            )
            val inserted = insertRecord(newRecord)
            reconcileRecordPhotos(inserted, photos, at)

            // Pointer only — never touch adoptionStatus or plan authority.
            if (candidate.convertedRecordClientUuid != targetUuid) {
                fulfillmentCandidateDao.update(
                    candidate.copy(convertedRecordClientUuid = targetUuid),
                )
            }
            inserted
        }

        val sourceType = fulfillmentCandidateDao.getByClientUuid(candidateClientUuid)
            ?.let { recordDao.getByClientUuid(it.recordClientUuid)?.type }
        val recordId = if (sourceType == RecordType.SLEEP.key) {
            sleepMutationMutex.withLock { writeConvert() }
        } else {
            writeConvert()
        }
        requestLocalSync()
        return recordId
    }

    private suspend fun resolveSubmitterDisplayNames(
        candidates: List<FulfillmentCandidateEntity>,
    ): Map<String, String> {
        val members = syncPort.listFamilyMembers().getOrNull().orEmpty()
        val byMembership = members.mapNotNull { member ->
            val id = member.membershipId?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val name = member.displayName?.trim()?.takeIf { it.isNotEmpty() }
                ?: if (member.role == com.lezi.babylog.sync.FamilyRole.Owner) {
                    "家庭管理员"
                } else {
                    "家庭成员"
                }
            id to name
        }.toMap()
        // Ensure every candidate membership key exists for fallback labels.
        return candidates.associate { candidate ->
            val mid = candidate.submitterMembershipId.trim()
            mid to (
                byMembership[mid]
                    ?: when {
                        mid.isEmpty() -> "未知提交者"
                        FulfillmentAuthority.isAdminRole(candidate.submitterRole) -> "家庭管理员"
                        candidate.submitterRole.isNotBlank() -> "家庭成员"
                        else -> mid
                    }
                )
        }
    }

    private suspend fun buildConflictNotAdoptedAudit(
        candidate: FulfillmentCandidateEntity,
        nameByMembership: Map<String, String>,
    ): ConflictNotAdoptedAudit? {
        val plan = carePlanDao.getByClientUuid(candidate.carePlanClientUuid) ?: return null
        val source = recordDao.getByClientUuid(candidate.recordClientUuid)
        val type = source?.let { RecordType.fromKey(it.type) }
            ?: RecordType.fromKey(plan.type)
            ?: RecordType.OTHER
        val photos = when {
            source != null && source.deletedAt == null -> listRecordPhotoPaths(source.id)
            source != null -> localPhotoPaths(source.payloadJson)
            else -> emptyList()
        }
        val live = fulfillmentCandidateDao.listForCarePlan(candidate.carePlanClientUuid)
            .filter { it.deletedAt == null }
        val evidences = live.map {
            FulfillmentCandidateEvidence(
                clientUuid = it.clientUuid,
                recordClientUuid = it.recordClientUuid,
                confirmedAt = it.confirmedAt,
                submitterRole = it.submitterRole,
            )
        }
        val winner = FulfillmentAuthority.selectWinner(evidences)
        val loserEvidence = FulfillmentCandidateEvidence(
            clientUuid = candidate.clientUuid,
            recordClientUuid = candidate.recordClientUuid,
            confirmedAt = candidate.confirmedAt,
            submitterRole = candidate.submitterRole,
        )
        val reason = if (winner != null && winner.clientUuid != candidate.clientUuid) {
            FulfillmentAuthority.notAdoptedReason(loserEvidence, winner)
        } else {
            "未采纳：履行冲突裁决落选"
        }
        val convertedUuid = candidate.convertedRecordClientUuid.trim()
        val converted = convertedUuid.takeIf { it.isNotEmpty() }
            ?.let { recordDao.getByClientUuid(it) }
            ?.takeIf { it.deletedAt == null }
        val typeLabel = when {
            source != null -> source.toModel().displayLabel()
            else -> plan.toModel().displayLabel()
        }
        val mid = candidate.submitterMembershipId.trim()
        return ConflictNotAdoptedAudit(
            candidateClientUuid = candidate.clientUuid,
            carePlanClientUuid = candidate.carePlanClientUuid,
            carePlanId = plan.id,
            babyId = plan.babyId,
            type = type,
            typeLabel = typeLabel,
            note = source?.note,
            actualTimestamp = candidate.actualTimestamp ?: source?.timestamp,
            confirmedAt = candidate.confirmedAt,
            submitterMembershipId = candidate.submitterMembershipId,
            submitterRole = candidate.submitterRole,
            submitterDisplayName = nameByMembership[mid] ?: mid.ifBlank { "未知提交者" },
            notAdoptedReason = reason,
            photoLocalPaths = photos,
            sourceRecordClientUuid = candidate.recordClientUuid,
            sourceRecordId = source?.id,
            convertedRecordClientUuid = converted?.clientUuid.orEmpty(),
            convertedRecordId = converted?.id,
        )
    }

    /**
     * Whether the actor may edit/skip/soft-delete this plan.
     * Same membership rule as custom items: creator or family owner/admin.
     */
    fun canManageCarePlan(
        plan: CarePlan,
        actorMembershipId: String,
        actorIsAdmin: Boolean,
    ): Boolean = canManageCreatorOwnedFamilyEntity(
        creatorMembershipId = plan.createdByMembershipId,
        actorMembershipId = actorMembershipId,
        actorIsAdmin = actorIsAdmin,
    )

    suspend fun canManageCarePlan(plan: CarePlan): Boolean {
        val session = syncPort.session().first()
        return canManageCarePlan(
            plan = plan,
            actorMembershipId = session.membershipId.trim(),
            actorIsAdmin = session.role == com.lezi.babylog.sync.FamilyRole.Owner,
        )
    }

    private suspend fun actorCanManageCarePlan(plan: CarePlanEntity): Boolean {
        val session = syncPort.session().first()
        return canManageCreatorOwnedFamilyEntity(
            creatorMembershipId = plan.createdByMembershipId,
            actorMembershipId = session.membershipId.trim(),
            actorIsAdmin = session.role == com.lezi.babylog.sync.FamilyRole.Owner,
        )
    }

    private suspend fun requireCanManageCarePlan(plan: CarePlanEntity) {
        if (!actorCanManageCarePlan(plan)) throw CarePlanPermissionException()
    }

    /**
     * Update an open (pending/missed) plan. Scheduled time may move into the past —
     * that only yields effective [CarePlanStatus.MISSED], never auto-fulfill.
     * Does not write a Record.
     */
    suspend fun updateCarePlan(
        carePlanId: Long,
        scheduledAt: Long,
        note: String? = null,
        payloadJson: String? = null,
        schemaVersion: Int? = null,
        photoLocalPaths: List<String>? = null,
        zone: ZoneId? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val photos = photoLocalPaths?.let { normalizePhotoPaths(it) }
        transactionRunner.run {
            val plan = carePlanDao.get(carePlanId) ?: error("护理计划不存在")
            if (plan.deletedAt != null) error("护理计划已删除")
            val status = CarePlanStatus.fromStorage(plan.status)
            require(status == CarePlanStatus.PENDING || status == CarePlanStatus.MISSED) {
                "已完成或已跳过的计划不可编辑"
            }
            requireCanManageCarePlan(plan)
            requireActiveBaby(plan.babyId)
            val at = nowMillis.coerceAtLeast(plan.updatedAt + 1)
            val nextPayload = if (photos != null) {
                withLocalPhotoPaths(payloadJson ?: plan.payloadJson, photos)
            } else {
                payloadJson ?: plan.payloadJson
            }
            // Persist stored status as pending; missed is always derived from clock.
            carePlanDao.update(
                plan.copy(
                    scheduledAt = scheduledAt,
                    scheduledZoneId = zone?.id ?: plan.scheduledZoneId,
                    note = note,
                    payloadJson = nextPayload,
                    schemaVersion = schemaVersion ?: plan.schemaVersion,
                    status = CarePlanStatus.PENDING.storageKey,
                    updatedAt = at,
                    syncDirty = true,
                ),
            )
            if (photos != null) {
                reconcileCarePlanPhotos(carePlanId, photos, at)
            }
        }
        carePlanDao.get(carePlanId)?.toModel()?.let { plan ->
            projectOrScheduleCarePlanReminder(plan, projectToSystemCalendar = true)
        }
        requestLocalSync()
    }

    /**
     * Skip an open plan without creating a Record.
     */
    suspend fun skipCarePlan(
        carePlanId: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        transactionRunner.run {
            val plan = carePlanDao.get(carePlanId) ?: error("护理计划不存在")
            if (plan.deletedAt != null) error("护理计划已删除")
            val status = CarePlanStatus.fromStorage(plan.status)
            require(status == CarePlanStatus.PENDING || status == CarePlanStatus.MISSED) {
                "该护理计划不可跳过"
            }
            requireCanManageCarePlan(plan)
            carePlanDao.update(
                plan.copy(
                    status = CarePlanStatus.SKIPPED.storageKey,
                    updatedAt = nowMillis.coerceAtLeast(plan.updatedAt + 1),
                    syncDirty = true,
                ),
            )
        }
        reminderCleanup.cancelCarePlan(carePlanId)
        removeSystemCalendarProjection(carePlanId)
        requestLocalSync()
    }

    /**
     * Soft-delete a plan (tombstone). Removes it from pending views; no Record created.
     */
    suspend fun deleteCarePlan(
        carePlanId: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        transactionRunner.run {
            val plan = carePlanDao.get(carePlanId) ?: return@run
            if (plan.deletedAt != null) return@run
            requireCanManageCarePlan(plan)
            val deletedAt = nowMillis.coerceAtLeast(plan.updatedAt + 1)
            carePlanDao.softDelete(carePlanId, deletedAt)
            tombstoneCarePlanPhotos(carePlanId, deletedAt)
        }
        reminderCleanup.cancelCarePlan(carePlanId)
        removeSystemCalendarProjection(carePlanId)
        requestLocalSync()
    }

    /**
     * After a remote care_plan package is fully applied, schedule or cancel this
     * device's own reminders / system calendar copies. Never requests calendar
     * permission (passive receive). Local prefs never enter the family package.
     */
    suspend fun onFamilyCarePlansApplied(planClientUuids: List<String>) {
        if (planClientUuids.isEmpty()) return
        for (uuid in planClientUuids) {
            val plan = carePlanDao.getByClientUuid(uuid) ?: continue
            val model = plan.toModel()
            val terminal = plan.deletedAt != null ||
                model.status == CarePlanStatus.COMPLETED ||
                model.status == CarePlanStatus.SKIPPED
            if (terminal) {
                reminderCleanup.cancelCarePlan(plan.id)
                removeSystemCalendarProjection(plan.id)
            } else {
                // Device-local prefs only; hasCalendarPermission is never requested here.
                projectOrScheduleCarePlanReminder(model, projectToSystemCalendar = true)
            }
        }
    }

    private suspend fun scheduleCarePlanReminder(plan: CarePlan) {
        val scheduled = reminderCleanup.scheduleCarePlan(plan)
        if (!scheduled) {
            reminderCleanup.cancelCarePlan(plan.id)
        }
    }

    /**
     * Project to system calendar when the user configured a target; on success
     * cancel the Lezi reminder (single-source). On deny/fail/missing target,
     * fall back to Lezi reminders. Never throws — plan save already committed.
     *
     * Content follows [SystemCalendarDisclosureLevel] (prefs). When an existing
     * mapped event was deleted externally, update fails and we rebuild via insert
     * so the UUID→event map stays authoritative without dual Lezi reminders.
     *
     * @return true when a system event is present; false when Lezi reminder is used.
     */
    suspend fun projectOrScheduleCarePlanReminder(
        plan: CarePlan,
        projectToSystemCalendar: Boolean = true,
    ): Boolean {
        if (!projectToSystemCalendar) {
            scheduleCarePlanReminder(plan)
            return false
        }
        val prefs = settings.settings.first()
        val calendarId = prefs.systemCalendarId?.takeIf { it.isNotBlank() }
        val configured = prefs.systemCalendarEnabled && calendarId != null
        if (!configured || !systemCalendar.hasCalendarPermission()) {
            scheduleCarePlanReminder(plan)
            return false
        }
        val baby = babyDao.get(plan.babyId)
        val nickname = baby?.nickname?.takeIf { it.isNotBlank() } ?: "宝宝"
        val level = SystemCalendarDisclosureLevel.fromStored(prefs.systemCalendarDisclosureLevel)
        val photoCount = listCarePlanPhotoPaths(plan.id).size.coerceAtMost(MAX_RECORD_PHOTOS)
        val content = SystemCalendarDisclosurePolicy.build(
            level = level,
            babyNickname = nickname,
            recordTypeLabel = plan.displayLabel(),
            note = plan.note,
            photoCount = photoCount,
            carePlanClientUuid = plan.clientUuid,
        )
        val map = parseSystemCalendarEventMap(prefs.systemCalendarEventMapJson)
        val existingEventId = map[plan.clientUuid]
        suspend fun upsert(existing: String?): String? = runCatching {
            systemCalendar.upsertEvent(
                SystemCalendarUpsert(
                    calendarId = calendarId!!,
                    carePlanClientUuid = plan.clientUuid,
                    beginAtMillis = plan.scheduledAt,
                    title = content.title,
                    description = content.description,
                    existingEventId = existing,
                    customAppUri = content.deepLinkUri,
                ),
            )
        }.getOrNull()
        var eventId = upsert(existingEventId)
        // External delete: provider update fails → rebuild via insert and refresh map.
        if (eventId == null && !existingEventId.isNullOrBlank()) {
            eventId = upsert(existing = null)
        }
        return if (eventId != null) {
            putSystemCalendarEventMapping(plan.clientUuid, eventId)
            reminderCleanup.cancelCarePlan(plan.id)
            true
        } else {
            // Failure / missing calendar: keep Lezi reminder, leave map stale if any.
            scheduleCarePlanReminder(plan)
            false
        }
    }

    /**
     * Reproject still-open future plans after disclosure level (or target) change.
     * Only [CarePlanDao.listAllOpenFuture] rows — never expands historical disclosure.
     * Best-effort provider I/O; never throws into settings/CarePlan save paths.
     */
    suspend fun reprojectOpenFutureSystemCalendarCopies(
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        carePlanDao.listAllOpenFuture(nowMillis).forEach { entity ->
            runCatching {
                projectOrScheduleCarePlanReminder(
                    entity.toModel(),
                    projectToSystemCalendar = true,
                )
            }
        }
    }

    /**
     * Whether the last projection for [carePlanId] is missing while the user
     * wanted system calendar (for “未同步到系统日历” chrome). Best-effort.
     * Detects permission revoke, vanished target calendar, missing map entry,
     * and mapped events that no longer exist in the provider.
     */
    suspend fun isCarePlanSystemCalendarUnsynced(carePlanId: Long): Boolean {
        val plan = carePlanDao.get(carePlanId)?.toModel() ?: return false
        val prefs = settings.settings.first()
        val calendarId = prefs.systemCalendarId
        if (!prefs.systemCalendarEnabled || calendarId.isNullOrBlank()) {
            return false
        }
        val hasPermission = systemCalendar.hasCalendarPermission()
        val targetWritable = hasPermission && systemCalendar.isWritableCalendar(calendarId)
        val map = parseSystemCalendarEventMap(prefs.systemCalendarEventMapJson)
        val mappedEventId = map[plan.clientUuid]
        val eventExists = if (!mappedEventId.isNullOrBlank() && hasPermission) {
            systemCalendar.eventExists(mappedEventId)
        } else {
            false
        }
        return evaluateCarePlanSystemCalendarUnsynced(
            systemCalendarEnabled = prefs.systemCalendarEnabled,
            systemCalendarId = calendarId,
            hasPermission = hasPermission,
            targetWritable = targetWritable,
            mappedEventId = mappedEventId,
            eventExists = eventExists,
        )
    }

    private suspend fun removeSystemCalendarProjection(carePlanId: Long) {
        val plan = carePlanDao.get(carePlanId) ?: carePlanDao.get(carePlanId)
        val clientUuid = plan?.clientUuid ?: return
        val prefs = settings.settings.first()
        val map = parseSystemCalendarEventMap(prefs.systemCalendarEventMapJson).toMutableMap()
        val eventId = map.remove(clientUuid) ?: return
        runCatching { systemCalendar.deleteEvent(eventId) }
        settings.setSystemCalendarEventMapJson(encodeSystemCalendarEventMap(map))
    }

    private suspend fun putSystemCalendarEventMapping(clientUuid: String, eventId: String) {
        val prefs = settings.settings.first()
        val map = parseSystemCalendarEventMap(prefs.systemCalendarEventMapJson).toMutableMap()
        map[clientUuid] = eventId
        settings.setSystemCalendarEventMapJson(encodeSystemCalendarEventMap(map))
    }

    /**
     * Rebuild care-plan alarms for still-open future plans only (boot / process restart).
     */
    suspend fun rescheduleCarePlanReminders(
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        carePlanDao.listAllOpenFuture(nowMillis).forEach { entity ->
            projectOrScheduleCarePlanReminder(entity.toModel(), projectToSystemCalendar = true)
        }
    }

    suspend fun getCarePlanByClientUuid(clientUuid: String): CarePlan? =
        carePlanDao.getByClientUuid(clientUuid)?.toModel()

    /**
     * Active plan photo local paths for [carePlanId].
     * Prefers MediaAsset rows; falls back to payload photos[] replica.
     */
    suspend fun listCarePlanPhotoPaths(carePlanId: Long): List<String> {
        val active = mediaAssetDao.listActiveForCarePlan(carePlanId)
            .map(MediaAssetEntity::localUri)
            .filter { it.isNotBlank() }
        if (active.isNotEmpty()) return active
        val plan = carePlanDao.get(carePlanId) ?: return emptyList()
        return localPhotoPaths(plan.payloadJson)
    }

    suspend fun weekSummary(
        babyId: Long,
        weekStart: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
        now: Long = System.currentTimeMillis(),
    ): WeekSummary {
        val start = weekStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = weekStart.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        val rows = recordDao.listRange(babyId, start, end).map { it.toModel() }
        return CareAggregation.week(rows, weekStart, zone, now)
    }

    suspend fun search(babyId: Long, query: String): List<Record> {
        val normalizedQuery = query.trim().lowercase()
        if (normalizedQuery.isEmpty()) return emptyList()
        val queryNeedsConvertedWeightCandidate =
            normalizedQuery.toDoubleOrNull()?.isFinite() == true
        val matchingTypeKeys = RecordType.entries
            .filter { type ->
                (
                    type == RecordType.WEIGHT &&
                        queryNeedsConvertedWeightCandidate
                    ) ||
                    type.candidateSearchTerms().any { term ->
                        typeTermMatchesQuery(term, normalizedQuery)
                    }
            }
            .map { it.key }
            .ifEmpty { listOf(NO_MATCHING_RECORD_TYPE) }
        return recordDao.searchCandidates(
            babyId = babyId,
            escapedPattern = normalizedQuery.payloadSearchNeedle().toSqlLikePattern(),
            matchingTypeKeys = matchingTypeKeys,
        ).asSequence()
            .map { it.toModel() }
            .filter { it.matchesVisibleSearchText(normalizedQuery) }
            .toList()
    }

    suspend fun recentCareSummary(babyId: Long, zone: ZoneId = ZoneId.systemDefault()): WidgetSummaryDto {
        val baby = babyDao.get(babyId)?.toModel()
        val day = LocalDate.now(zone)
        // The daily totals are windowed inside CareAggregation, while "last"
        // intentionally spans prior days. Supplying the complete baby-scoped
        // set keeps both facts behind the same interface.
        val records = recordDao.listForBaby(babyId).map { it.toModel() }
        return CareAggregation.widget(
            records = records,
            babyName = baby?.nickname ?: "乐记",
            date = day,
            zone = zone,
        )
    }

    fun observeMeasurements(babyId: Long, type: RecordType): Flow<List<Record>> =
        recordDao.observeRange(
            babyId = babyId,
            startInclusive = Long.MIN_VALUE,
            endExclusive = Long.MAX_VALUE,
        ).map { records ->
            records.asSequence()
                .filter { it.type == type.key }
                .map { it.toModel() }
                .toList()
        }

    suspend fun recentMilkAmounts(
        babyId: Long,
        type: RecordType,
        limit: Int = 3,
    ): List<Int> {
        require(type in setOf(RecordType.FORMULA, RecordType.PUMPED_FEED, RecordType.PUMP_EXPRESS))
        return recordDao.listByType(babyId, type.key)
            .asReversed()
            .asSequence()
            .mapNotNull { (it.toModel().payload.payload as? MilkPayload)?.amountMl }
            .filter { it in 1..999 }
            .distinct()
            .take(limit)
            .toList()
    }

    suspend fun recentNotes(
        babyId: Long,
        type: RecordType,
        limit: Int = 5,
    ): List<String> = recordDao.listByType(babyId, type.key)
        .asReversed()
        .asSequence()
        .mapNotNull { it.note?.trim()?.takeIf(String::isNotBlank) }
        .distinct()
        .take(limit)
        .toList()

    /**
     * Wipe care history on this device: records, calendar events, care plans,
     * and fulfillment candidates. Baby profiles and custom items are retained.
     * Settings cleanup is deliberately local-only (not a family tombstone).
     */
    suspend fun clearRecordsOnly() = calendarReminderMutationMutex.withLock {
        val familyServerRetained = syncPort.session().first().familyId.isNotBlank()
        var failure: Throwable? = null
        var carePlanIdsToCancel = emptyList<Long>()
        try {
            syncPort.clearLocalRecords { onCommitted ->
                transactionRunner.run {
                    val calendarEventIds = allCalendarEventIds()
                    // Capture before wipe so post-commit reminder cancel still works.
                    carePlanIdsToCancel = carePlanDao.listAllIncludingDeleted().map { it.id }
                    recordDao.deleteAll()
                    calendarEventDao.deleteAll()
                    // History clear drops plans too so alarms/UI do not keep a schedule
                    // after the user wiped care history (open pending included).
                    fulfillmentCandidateDao.deleteAll()
                    carePlanDao.deleteAll()
                    persistPendingRecordClearReminderCleanup(
                        calendarEventIds = calendarEventIds,
                        familyServerRetained = familyServerRetained,
                    )
                }
                onCommitted()
                settings.clearNextFeedAt()
                settings.setSystemCalendarEventMapJson("{}")
            }.getOrThrow()
        } catch (error: Throwable) {
            failure = error.asDomainLocalClearFailure()
        }
        failure = withContext(NonCancellable) {
            val afterReminders = finishRecordClearReminders(initialFailure = failure)
            cancelCarePlanRemindersQuietly(carePlanIdsToCancel, afterReminders)
        }
        throwClearFailure(failure)
    }

    /**
     * Full local wipe including babies — only for "clear then join family".
     * Settings UI must not call this for ordinary data clear.
     *
     * Clears domain tables (records, calendar, care plans, custom items, babies,
     * family membership, local user), settings keys, and — via
     * [SyncPort.clearAllLocalData] — outbox, media_assets, and on-disk media files
     * under the same sync barrier as [clearRecordsOnly].
     */
    suspend fun clearAllLocalData() = calendarReminderMutationMutex.withLock {
        val familyServerRetained = syncPort.session().first().familyId.isNotBlank()
        var failure: Throwable? = null
        var carePlanIdsToCancel = emptyList<Long>()
        try {
            syncPort.clearAllLocalData { onCommitted ->
                transactionRunner.run {
                    val calendarEventIds = allCalendarEventIds()
                    carePlanIdsToCancel = carePlanDao.listAllIncludingDeleted().map { it.id }
                    recordDao.deleteAll()
                    calendarEventDao.deleteAll()
                    customItemDao.deleteAll()
                    fulfillmentCandidateDao.deleteAll()
                    carePlanDao.deleteAll()
                    babyDao.deleteAll()
                    membershipDao.deleteAll()
                    familyDao.deleteAll()
                    localUserDao.deleteAll()
                    persistPendingRecordClearReminderCleanup(
                        calendarEventIds = calendarEventIds,
                        familyServerRetained = familyServerRetained,
                    )
                }
                onCommitted()
                settings.setCurrentBabyId(null)
                settings.clearNextFeedAt()
                settings.setSystemCalendarEventMapJson("{}")
            }.getOrThrow()
        } catch (error: Throwable) {
            failure = error.asDomainLocalClearFailure()
        }
        failure = withContext(NonCancellable) {
            val afterReminders = finishRecordClearReminders(initialFailure = failure)
            cancelCarePlanRemindersQuietly(carePlanIdsToCancel, afterReminders)
        }
        throwClearFailure(failure)
    }

    /**
     * Best-effort cancel of care-plan alarms after a local wipe. Failures attach
     * as suppressed causes when a domain clear already committed.
     */
    private suspend fun cancelCarePlanRemindersQuietly(
        carePlanIds: List<Long>,
        initialFailure: Throwable?,
    ): Throwable? {
        if (carePlanIds.isEmpty()) return initialFailure
        return try {
            carePlanIds.forEach { reminderCleanup.cancelCarePlan(it) }
            initialFailure
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (reminderError: Throwable) {
            when (initialFailure) {
                is LocalRecordsClearCommittedException -> initialFailure.apply {
                    addSuppressed(reminderError)
                }
                else -> LocalRecordsClearCommittedException(
                    familyServerRetained = syncPort.session().first().familyId.isNotBlank(),
                    cause = reminderError,
                )
            }
        }
    }

    /** Retry the durable post-commit hand-off when a new app process starts. */
    suspend fun recoverPendingRecordClearReminders() =
        calendarReminderMutationMutex.withLock {
            val failure = withContext(NonCancellable) {
                finishRecordClearReminders(initialFailure = null)
            }
            throwClearFailure(failure)
        }

    private suspend fun finishRecordClearReminders(
        initialFailure: Throwable?,
    ): Throwable? {
        val operation = PendingReminderCleanupOperation.RECORDS_CLEAR
        val pending = pendingReminderCleanupStore.load(operation)
            ?: return initialFailure
        return try {
            reminderCleanup.cancelForRecordsClear(pending.calendarEventIds)
            pendingReminderCleanupStore.delete(operation)
            initialFailure
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (reminderError: Throwable) {
            when (initialFailure) {
                is LocalRecordsClearCommittedException -> initialFailure.apply {
                    addSuppressed(reminderError)
                }
                else -> LocalRecordsClearCommittedException(
                    familyServerRetained = pending.familyServerRetained,
                    cause = initialFailure ?: reminderError,
                ).also { classified ->
                    if (initialFailure != null) classified.addSuppressed(reminderError)
                }
            }
        }
    }

    private suspend fun allCalendarEventIds(): List<Long> =
        babyDao.listAllIncludingDeleted()
            .flatMap { baby -> calendarEventDao.listForBabyIncludingDeleted(baby.id) }
            .map { it.id }
            .distinct()

    private suspend fun persistPendingRecordClearReminderCleanup(
        calendarEventIds: Collection<Long>,
        familyServerRetained: Boolean,
    ) {
        pendingReminderCleanupStore.upsert(
            PendingReminderCleanup(
                operation = PendingReminderCleanupOperation.RECORDS_CLEAR,
                calendarEventIds = calendarEventIds.toSet(),
                familyServerRetained = familyServerRetained,
            ),
        )
    }

    private fun throwClearFailure(failure: Throwable?) {
        if (failure == null) return
        failure.cancellationCauseOrNull()?.let { throw it }
        throw failure
    }

    private fun Throwable.asDomainLocalClearFailure(): Throwable =
        if (this is LocalClearCommittedException) {
            LocalRecordsClearCommittedException(
                familyServerRetained = familyServerRetained,
                cause = this,
            )
        } else {
            this
        }

    private fun Throwable.cancellationCauseOrNull(): CancellationException? {
        var current: Throwable? = this
        while (current != null) {
            if (current is CancellationException) return current
            current = current.cause
        }
        return null
    }

    suspend fun renameBaby(babyId: Long, nickname: String) {
        val name = normalizeNickname(nickname)
        val changed = transactionRunner.run {
            val baby = babyDao.get(babyId) ?: return@run false
            if (baby.nickname == name) return@run false
            ensureNicknameAvailable(name, excludeId = babyId)
            babyDao.update(
                baby.copy(
                    nickname = name,
                    updatedAt = nextSyncUpdatedAt(
                        baby.updatedAt,
                        System.currentTimeMillis(),
                    ),
                    syncDirty = true,
                ),
            )
            true
        }
        if (!changed) return
        requestLocalSync()
    }

    /**
     * Build an explicit merge preview. Nicknames are display-only and never
     * participate in deciding which profiles or records are moved.
     */
    suspend fun previewBabyMerge(sourceBabyId: Long, targetBabyId: Long): BabyMergePreview? {
        if (sourceBabyId == targetBabyId) return null
        val source = babyDao.get(sourceBabyId) ?: return null
        val target = babyDao.get(targetBabyId) ?: return null
        if (source.familyId != target.familyId) return null
        return BabyMergePreview(
            sourceBabyId = source.id,
            sourceNickname = source.nickname,
            targetBabyId = target.id,
            targetNickname = target.nickname,
            recordCount = recordDao.listForBaby(source.id).size,
            calendarEventCount = calendarEventDao.listForBaby(source.id).size,
        )
    }

    /**
     * Apply a merge only after the UI has shown [previewBabyMerge].
     * The target profile stays intact; source records (including tombstones),
     * calendar events, and avatar media rows are re-bound to the target baby.
     */
    suspend fun mergeBabyProfiles(sourceBabyId: Long, targetBabyId: Long): Boolean {
        if (sourceBabyId == targetBabyId) return false
        val now = System.currentTimeMillis()
        val merged = sleepMutationMutex.withLock {
            transactionRunner.run {
                val source = babyDao.get(sourceBabyId) ?: return@run false
                val target = babyDao.get(targetBabyId) ?: return@run false
                if (source.familyId != target.familyId) return@run false
                // Include soft-deleted rows so tombstones stay with the keeper profile.
                recordDao.listAllIncludingDeleted()
                    .filter { it.babyId == source.id }
                    .forEach { record ->
                        recordDao.update(
                            record.copy(
                                babyId = target.id,
                                updatedAt = nextSyncUpdatedAt(record.updatedAt, now),
                                syncDirty = true,
                            ),
                        )
                    }
                calendarEventDao.listForBabyIncludingDeleted(source.id).forEach { event ->
                    calendarEventDao.update(
                        event.copy(
                            babyId = target.id,
                            updatedAt = nextSyncUpdatedAt(event.updatedAt, now),
                        ),
                    )
                }
                mediaAssetDao.listAllIncludingDeleted()
                    .filter { it.babyId == source.id }
                    .forEach { asset ->
                        mediaAssetDao.update(
                            asset.copy(
                                babyId = target.id,
                                updatedAt = nextSyncUpdatedAt(asset.updatedAt, now),
                                syncDirty = true,
                            ),
                        )
                    }
                healDuplicateOpenSleeps(target.id)
                babyDao.update(
                    nextSyncUpdatedAt(source.updatedAt, now).let { deletedAt ->
                        source.copy(
                            deletedAt = deletedAt,
                            updatedAt = deletedAt,
                            syncDirty = true,
                        )
                    },
                )
                true
            }
        }
        if (!merged) return false
        if (settings.currentBabyId.first() == sourceBabyId) {
            settings.setCurrentBabyId(targetBabyId)
        }
        requestLocalSync()
        return true
    }

    private fun normalizeNickname(raw: String): String =
        normalizeBabyNickname(raw)

    private fun normalizeBirthWeightGrams(grams: Int?): Int? {
        val g = grams ?: return null
        birthWeightValidationError(g)?.let { throw IllegalArgumentException(it) }
        return g
    }

    private suspend fun ensureNicknameAvailable(nickname: String, excludeId: Long = -1L) {
        val count = babyDao.countByNickname(nickname, excludeId)
        if (count > 0) throw DuplicateBabyNicknameException(nickname)
    }

    private suspend fun requireActiveBaby(babyId: Long): BabyEntity =
        requireNotNull(babyDao.get(babyId)) { "宝宝档案不存在，请返回后重试" }

    private suspend fun ensureLocalUser(now: Long): Long {
        localUserDao.get()?.id?.let { return it }
        // displayName stays null until the user joins/creates a family with a real 称呼.
        return localUserDao.upsert(
            LocalUserEntity(
                displayName = null,
                deviceId = UUID.randomUUID().toString(),
                createdAt = now,
            ),
        )
    }

    /**
     * Sync-session device id used as the record writer link key (`created_by_device_id`).
     * Distinct from [LocalUserEntity.deviceId]; self detection on the timeline must use this.
     * Null when the session has not allocated a device id yet (typically pre-join).
     */
    private suspend fun writerDeviceId(): String? =
        syncPort.session().first().deviceId.trim().takeIf { it.isNotEmpty() }

    private suspend fun ensureFamily(userId: Long, now: Long): Long {
        val existing = familyDao.listAll().firstOrNull()
        if (existing != null) return existing.id
        val id = familyDao.insert(FamilyEntity(ownerUserId = userId, createdAt = now))
        membershipDao.upsert(
            MembershipEntity(
                familyId = id,
                userId = userId,
                role = "owner",
                status = "active",
                joinedAt = now,
            ),
        )
        return id
    }


    private suspend fun insertRecord(record: RecordEntity): Long = recordDao.upsert(record)

    private suspend fun updateRecordEntity(record: RecordEntity) {
        val previous = recordDao.getIncludingDeleted(record.id)?.updatedAt
        recordDao.update(
            record.copy(
                updatedAt = previous
                    ?.let { nextSyncUpdatedAt(it, record.updatedAt) }
                    ?: record.updatedAt,
                syncDirty = true,
            ),
        )
    }

    private fun normalizePhotoPaths(photoLocalPaths: List<String>): List<String> {
        val normalized = photoLocalPaths.filter { it.isNotBlank() }.distinct()
        require(normalized.size <= MAX_RECORD_PHOTOS) {
            "每条记录最多 $MAX_RECORD_PHOTOS 张照片"
        }
        return normalized
    }

    /**
     * Keep MediaAsset log rows and the payload photos[] replica aligned for one record.
     * Callers must already be inside a domain transaction.
     */
    private suspend fun reconcileRecordPhotos(
        recordId: Long,
        photoPaths: List<String>,
        at: Long,
    ) {
        val existing = mediaAssetDao.listForRecord(recordId)
            .filter { it.kind == "log" }
        val active = existing.filter { it.deletedAt == null }
        val desired = photoPaths.toSet()
        for (path in photoPaths) {
            val live = active.firstOrNull { it.localUri == path }
            if (live != null) continue
            val tombstoned = existing.firstOrNull { it.localUri == path && it.deletedAt != null }
            if (tombstoned != null) {
                mediaAssetDao.update(
                    tombstoned.copy(
                        deletedAt = null,
                        updatedAt = nextSyncUpdatedAt(tombstoned.updatedAt, at),
                        syncDirty = true,
                    ),
                )
            } else {
                mediaAssetDao.upsert(
                    MediaAssetEntity(
                        recordId = recordId,
                        clientUuid = newClientUuid(),
                        kind = "log",
                        babyId = null,
                        localUri = path,
                        createdAt = at,
                        updatedAt = at,
                        syncDirty = true,
                    ),
                )
            }
        }
        active.filter { it.localUri !in desired }.forEach { asset ->
            mediaAssetDao.update(
                asset.copy(
                    deletedAt = at,
                    updatedAt = nextSyncUpdatedAt(asset.updatedAt, at),
                    syncDirty = true,
                ),
            )
        }
    }

    private suspend fun tombstoneRecordPhotos(recordId: Long, deletedAt: Long) {
        mediaAssetDao.listActiveForRecord(recordId)
            .filter { it.kind == "log" }
            .forEach { asset ->
                mediaAssetDao.update(
                    asset.copy(
                        deletedAt = deletedAt,
                        updatedAt = nextSyncUpdatedAt(asset.updatedAt, deletedAt),
                        syncDirty = true,
                    ),
                )
            }
    }

    /**
     * Align plan-owned MediaAsset log rows and the payload photos[] replica.
     * Plan photos never share ownership with record media rows (separate clientUuids).
     * Physical file cleanup is deferred until no active entity references the path.
     */
    private suspend fun reconcileCarePlanPhotos(
        carePlanId: Long,
        photoPaths: List<String>,
        at: Long,
    ) {
        val existing = mediaAssetDao.listForCarePlan(carePlanId)
            .filter { it.kind == "log" }
        val active = existing.filter { it.deletedAt == null }
        val desired = photoPaths.toSet()
        for (path in photoPaths) {
            val live = active.firstOrNull { it.localUri == path }
            if (live != null) continue
            val tombstoned = existing.firstOrNull { it.localUri == path && it.deletedAt != null }
            if (tombstoned != null) {
                mediaAssetDao.update(
                    tombstoned.copy(
                        deletedAt = null,
                        updatedAt = nextSyncUpdatedAt(tombstoned.updatedAt, at),
                        syncDirty = true,
                    ),
                )
            } else {
                mediaAssetDao.upsert(
                    MediaAssetEntity(
                        carePlanId = carePlanId,
                        recordId = null,
                        clientUuid = newClientUuid(),
                        kind = "log",
                        babyId = null,
                        localUri = path,
                        createdAt = at,
                        updatedAt = at,
                        syncDirty = true,
                    ),
                )
            }
        }
        active.filter { it.localUri !in desired }.forEach { asset ->
            mediaAssetDao.update(
                asset.copy(
                    deletedAt = at,
                    updatedAt = nextSyncUpdatedAt(asset.updatedAt, at),
                    syncDirty = true,
                ),
            )
        }
        // Keep payload photos[] replica aligned for export/compat.
        val plan = carePlanDao.get(carePlanId) ?: return
        carePlanDao.update(
            plan.copy(payloadJson = withLocalPhotoPaths(plan.payloadJson, photoPaths)),
        )
    }

    private suspend fun tombstoneCarePlanPhotos(carePlanId: Long, deletedAt: Long) {
        mediaAssetDao.listActiveForCarePlan(carePlanId)
            .filter { it.kind == "log" }
            .forEach { asset ->
                mediaAssetDao.update(
                    asset.copy(
                        deletedAt = deletedAt,
                        updatedAt = nextSyncUpdatedAt(asset.updatedAt, deletedAt),
                        syncDirty = true,
                    ),
                )
            }
    }

    /**
     * Keep at most one open sleep per baby. Older open intervals are closed at
     * the next open's start and flagged anomaly (covers sync-introduced dups).
     */
    private suspend fun healDuplicateOpenSleeps(babyId: Long) {
        val opens = recordDao.listOpenSleeps(babyId)
        if (opens.size <= 1) return
        val keep = opens.first()
        val stale = opens.drop(1).sortedWith(
            compareBy<RecordEntity> { it.timestamp }.thenBy { it.id },
        )
        val chain = stale + keep
        val now = System.currentTimeMillis()
        for (index in 0 until chain.lastIndex) {
            val current = chain[index]
            val nextStart = chain[index + 1].timestamp
            val end = if (nextStart > current.timestamp) {
                nextStart
            } else {
                current.timestamp + 60_000L
            }
            val flagged = withAnomaly(current.payloadJson, current.schemaVersion)
            updateRecordEntity(
                current.copy(
                    endTimestamp = end,
                    payloadJson = flagged.first,
                    schemaVersion = flagged.second,
                    updatedAt = now,
                ),
            )
        }
    }

    private fun requestLocalSync() {
        syncPort.requestSync(SyncTrigger.LocalWrite)
    }

    private fun pickCurrent(babies: List<BabyEntity>, storedId: Long?): BabyEntity? {
        if (babies.isEmpty()) return null
        return storedId?.let { id -> babies.find { it.id == id } } ?: babies.first()
    }

}

internal fun nextSyncUpdatedAt(previous: Long, candidate: Long): Long =
    if (previous == Long.MAX_VALUE) {
        Long.MAX_VALUE
    } else {
        maxOf(candidate, previous + 1)
    }

private val NURSING_ORDER_ALLOWLIST = setOf("L", "R", "LR", "RL")

private fun validateSleepInterval(
    type: RecordType?,
    timestamp: Long,
    endTimestamp: Long?,
) {
    if (type == RecordType.SLEEP && endTimestamp != null) {
        require(endTimestamp > timestamp) {
            "睡眠结束时间必须晚于开始时间"
        }
    }
}

internal fun BabyEntity.toModel(): Baby =
    Baby(
        id = id,
        familyId = familyId,
        nickname = nickname,
        sex = sex?.let { parseSex(it) },
        birthdayEpochDay = birthdayEpochDay,
        birthWeightGrams = birthWeightGrams,
        avatarPath = avatarPath,
        themeColorArgb = themeColorArgb,
        sortOrder = sortOrder,
        clientUuid = clientUuid,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )

internal fun RecordEntity.toModel(): Record =
    Record(
        id = id,
        clientUuid = clientUuid,
        babyId = babyId,
        type = RecordType.fromKey(type) ?: RecordType.OTHER,
        timestamp = timestamp,
        endTimestamp = endTimestamp,
        note = note,
        createdByUserId = createdByUserId,
        createdByDeviceId = createdByDeviceId,
        payloadJson = payloadJson,
        schemaVersion = schemaVersion,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        syncDirty = syncDirty,
    )

private fun CalendarEventEntity.toModel(): CalendarEvent =
    CalendarEvent(
        id = id,
        clientUuid = clientUuid,
        babyId = babyId,
        title = title,
        note = note,
        eventAt = eventAt,
        remindAt = remindAt,
    )

private fun CustomItemEntity.toModel(): CustomRecordItem =
    CustomRecordItem(
        id = id,
        name = name,
        iconSlot = iconSlot,
        sortOrder = sortOrder,
        clientUuid = clientUuid,
        createdByMembershipId = createdByMembershipId,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )

private fun CarePlanEntity.toModel(): CarePlan =
    CarePlan(
        id = id,
        clientUuid = clientUuid,
        babyId = babyId,
        type = RecordType.fromKey(type) ?: RecordType.OTHER,
        customItemId = customItemId,
        scheduledAt = scheduledAt,
        scheduledZoneId = scheduledZoneId,
        note = note,
        payloadJson = payloadJson,
        schemaVersion = schemaVersion,
        status = CarePlanStatus.fromStorage(status),
        createdByMembershipId = createdByMembershipId,
        fulfilledRecordClientUuid = fulfilledRecordClientUuid,
        fulfilledAt = fulfilledAt,
        sourceRecordClientUuid = sourceRecordClientUuid,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        syncDirty = syncDirty,
    )

private fun FulfillmentCandidateEntity.toModel(): FulfillmentCandidate =
    FulfillmentCandidate(
        id = id,
        clientUuid = clientUuid,
        carePlanClientUuid = carePlanClientUuid,
        recordClientUuid = recordClientUuid,
        actualTimestamp = actualTimestamp,
        confirmedAt = confirmedAt,
        submitterMembershipId = submitterMembershipId,
        submitterRole = submitterRole,
        adoptionStatus = adoptionStatus,
        convertedRecordClientUuid = convertedRecordClientUuid,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        syncDirty = syncDirty,
    )

/**
 * Pure ownership rule for creator-owned family entities (custom item definitions
 * and care plans share the same membership ACL — ADR 0001 / 0006).
 *
 * Empty creator + empty actor → offline single-device local owner.
 * Empty creator + joined non-admin → deny (await admin takeover after upgrade).
 * Non-empty creator match → member may manage own entity.
 * Admin always may manage (including after creator leave).
 */
fun canManageCreatorOwnedFamilyEntity(
    creatorMembershipId: String,
    actorMembershipId: String,
    actorIsAdmin: Boolean,
): Boolean {
    if (actorIsAdmin) return true
    val creator = creatorMembershipId.trim()
    val actor = actorMembershipId.trim()
    if (creator.isEmpty() && actor.isEmpty()) return true
    if (creator.isEmpty()) return false
    return creator == actor
}

/** Historical name — same rule as [canManageCreatorOwnedFamilyEntity]. */
fun canManageCustomItemDefinition(
    creatorMembershipId: String,
    actorMembershipId: String,
    actorIsAdmin: Boolean,
): Boolean = canManageCreatorOwnedFamilyEntity(
    creatorMembershipId = creatorMembershipId,
    actorMembershipId = actorMembershipId,
    actorIsAdmin = actorIsAdmin,
)

/**
 * Merge durable custom definition snapshot fields into a plan/record payload.
 * Prefer live definition values so renames after draft open still stamp correctly.
 */
internal fun stampCustomItemSnapshotIntoPayload(
    payloadJson: String,
    customItemId: Long,
    titleSnapshot: String,
    iconSlot: Int,
): String {
    val document = RecordPayloadCodec.decode(
        RecordType.CUSTOM,
        payloadJson,
        CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
    )
    val existing = document.payload as? CustomPayload
    val stamped = CustomPayload(
        titleSnapshot = titleSnapshot.trim().ifBlank {
            existing?.titleSnapshot.orEmpty()
        },
        detail = existing?.detail,
        customItemId = customItemId,
        iconSlot = iconSlot,
    )
    return RecordPayloadCodec.encode(
        RecordPayloadDocument(
            type = RecordType.CUSTOM,
            payload = stamped,
            schemaVersion = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
            extensions = document.extensions,
        ),
    )
}

/**
 * Write-time name/icon snapshot for a custom definition used by Record / CarePlan payloads.
 * Callers must persist these fields; later renames must not rewrite historical rows.
 */
data class CustomItemFieldSnapshot(
    val titleSnapshot: String,
    val iconSlot: Int?,
    val customItemId: Long?,
)

fun CustomRecordItem.toFieldSnapshot(): CustomItemFieldSnapshot =
    CustomItemFieldSnapshot(
        titleSnapshot = name,
        iconSlot = iconSlot,
        customItemId = id.takeIf { it > 0L },
    )

private fun parseSex(raw: String): Sex =
    runCatching { Sex.valueOf(raw) }.getOrNull()
        ?: when (raw.lowercase()) {
            "male", "m", "男" -> Sex.MALE
            "female", "f", "女" -> Sex.FEMALE
            else -> Sex.UNKNOWN
        }

private fun withAnomaly(payloadJson: String, schemaVersion: Int): Pair<String, Int> {
    val document = RecordPayloadCodec.decode(RecordType.SLEEP, payloadJson, schemaVersion)
    val sleep = document.payload as? SleepPayload ?: return payloadJson to schemaVersion
    val normalized = document.copy(
        payload = sleep.copy(anomaly = true),
        schemaVersion = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
    )
    return RecordPayloadCodec.encode(normalized) to CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION
}

fun babyAgeLabel(birthdayEpochDay: Long, today: LocalDate = LocalDate.now()): String {
    val birth = LocalDate.ofEpochDay(birthdayEpochDay)
    if (birth.isAfter(today)) return "未出生"

    if (today.isBefore(birth.plusYears(1))) {
        val months = completedCalendarMonthsBetween(birth, today)
        val days = ChronoUnit.DAYS.between(birth.plusMonths(months.toLong()), today).toInt()
        return "${months}个月${days}天"
    }

    var years = today.year - birth.year
    var yearAnchor = birth.plusYears(years.toLong())
    if (today.isBefore(yearAnchor)) {
        years -= 1
        yearAnchor = birth.plusYears(years.toLong())
    }
    val months = completedCalendarMonthsBetween(yearAnchor, today)
    return "${years}岁${months}个月"
}

private fun completedCalendarMonthsBetween(start: LocalDate, end: LocalDate): Int {
    var months = (end.year - start.year) * 12 + end.monthValue - start.monthValue
    if (end.isBefore(start.plusMonths(months.toLong()))) months -= 1
    return months
}

fun relativeTimeLabel(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    val delta = (now - timestamp).coerceAtLeast(0L)
    val min = delta / 60_000L
    return when {
        min < 1 -> "刚刚"
        min < 60 -> "${min} 分钟前"
        min < 60 * 24 -> "${min / 60} 小时前"
        else -> "${min / (60 * 24)} 天前"
    }
}

fun formatClock(timestamp: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val t = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalTime()
    return "%02d:%02d".format(t.hour, t.minute)
}

/**
 * Formula/pumped UI values from 30 through 300 at step-sized intervals, plus a
 * missing positive last value.
 */
fun amountCandidates(step: Int, lastMl: Int?): List<Int> {
    val s = step.coerceAtLeast(1)
    val base = (30..300 step s).toMutableList()
    if (lastMl != null && lastMl > 0 && lastMl !in base) {
        base.add(lastMl)
        base.sort()
    }
    return base
}

fun amountCenterIndex(candidates: List<Int>, lastMl: Int?): Int {
    if (candidates.isEmpty()) return 0
    if (lastMl == null) return candidates.indexOf(120).takeIf { it >= 0 }
        ?: candidates.indexOfFirst { it >= 120 }.coerceAtLeast(0)
    val exact = candidates.indexOf(lastMl)
    if (exact >= 0) return exact
    return candidates.indices.minByOrNull { kotlin.math.abs(candidates[it] - lastMl) } ?: 0
}
