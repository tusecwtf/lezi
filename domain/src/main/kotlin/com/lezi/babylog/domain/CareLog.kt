package com.lezi.babylog.domain

import com.lezi.babylog.core.common.newClientUuid
import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.BabyEntity
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
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.sync.PolicyClock
import com.lezi.babylog.sync.SyncPort
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
import com.lezi.babylog.core.model.MilkPayload
import com.lezi.babylog.core.model.NEXT_FEED_PLAN_MARKER
import com.lezi.babylog.core.model.NextFeedPlanReconciliation
import com.lezi.babylog.core.model.NursingPayload
import com.lezi.babylog.core.model.OpenSleepCandidate
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
import com.lezi.babylog.core.model.normalizeBabyNickname
import com.lezi.babylog.core.model.normalizeOpenSleeps
import java.time.LocalDate
import java.time.ZoneId
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

class BabyProfilePermissionException :
    IllegalStateException("宝宝档案由家庭管理员管理")

class SleepStateChangedException :
    IllegalStateException("睡眠状态已变化，请重新打开睡眠菜单")

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

private val NEXT_FEED_TYPES = setOf(
    RecordType.NURSING,
    RecordType.FORMULA,
    RecordType.PUMPED_FEED,
)

internal fun isNextFeedPlanNote(note: String?): Boolean =
    note?.startsWith(NEXT_FEED_PLAN_MARKER) == true

internal fun visibleCarePlanNote(note: String?): String? = note
    ?.removePrefix(NEXT_FEED_PLAN_MARKER)
    ?.trimStart()
    ?.takeIf(String::isNotBlank)

private fun nextFeedPlanNote(visibleNote: String?): String = buildString {
    append(NEXT_FEED_PLAN_MARKER)
    visibleNote?.trim()?.takeIf(String::isNotBlank)?.let { append(' ').append(it) }
}

internal fun nextFeedPlanClientUuid(babyClientUuid: String, generationSeed: String): String =
    UUID.nameUUIDFromBytes(
        "lezi.next-feed.v1:$babyClientUuid:$generationSeed".toByteArray(StandardCharsets.UTF_8),
    ).toString()

private fun nextFeedPlanGenerationSeed(plans: List<CarePlanEntity>): String =
    plans.maxWithOrNull(compareBy<CarePlanEntity> { it.updatedAt }.thenBy { it.clientUuid })
        ?.let { "${it.clientUuid}:${it.updatedAt}:${it.status}:${it.deletedAt ?: 0L}" }
        ?: "initial"

private fun openNextFeedPlans(
    plans: List<CarePlanEntity>,
    babyId: Long,
): List<CarePlanEntity> = plans
    .filter {
        it.babyId == babyId &&
            it.deletedAt == null &&
            it.status in setOf(
                CarePlanStatus.PENDING.storageKey,
                CarePlanStatus.MISSED.storageKey,
            ) &&
            isNextFeedPlanNote(it.note)
    }
    .sortedWith(compareBy<CarePlanEntity> { it.updatedAt }.thenBy { it.id })

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
    val carePlanCount: Int,
)

private data class BabyMergeWriteResult(
    val reprojectPlanClientUuids: List<String>,
    val discardedNextFeedPlanIds: List<Long>,
    val carePlanIdentityMigrations: List<CarePlanIdentityMigration>,
)

private data class CarePlanIdentityMigration(
    val carePlanId: Long,
    val oldClientUuid: String,
    val newClientUuid: String,
    val oldSystemCalendarEventId: String?,
    val oldSystemCalendarReminderReady: Boolean,
    val oldSystemCalendarProjectionPending: Boolean,
)

@Singleton
class CareLog @Inject constructor(
    private val babyDao: BabyDao,
    private val recordDao: RecordDao,
    private val carePlanDao: CarePlanDao,
    private val customItemDao: CustomItemDao,
    private val localUserDao: LocalUserDao,
    private val familyDao: FamilyDao,
    private val membershipDao: MembershipDao,
    private val mediaAssetDao: MediaAssetDao,
    private val settings: SettingsStore,
    private val syncPort: SyncPort,
    private val reminderCleanup: ReminderCleanupPort,
    private val transactionRunner: DatabaseTransactionRunner,
    private val systemCalendar: SystemCalendarPort = NoOpSystemCalendarPort(),
    private val fulfillmentCandidateDao: FulfillmentCandidateDao,
    private val calendarReminderMutationGuard: CalendarReminderMutationGuard,
    private val clock: PolicyClock,
) {
    private val queries = CareLogQueries(
        babyDao = babyDao,
        recordDao = recordDao,
        carePlanDao = carePlanDao,
        fulfillmentCandidateDao = fulfillmentCandidateDao,
    )
    private val photoAttachmentReconciler = PhotoAttachmentReconciler(mediaAssetDao)
    private val reminderProjection = CarePlanReminderProjection(
        carePlanDao = carePlanDao,
        babyDao = babyDao,
        mediaAssetDao = mediaAssetDao,
        settings = settings,
        reminderCleanup = reminderCleanup,
        systemCalendar = systemCalendar,
        calendarReminderMutationGuard = calendarReminderMutationGuard,
    )
    private val conflictAuditQueries = ConflictAuditQueries(
        fulfillmentCandidateDao = fulfillmentCandidateDao,
        carePlanDao = carePlanDao,
        recordDao = recordDao,
        mediaAssetDao = mediaAssetDao,
        syncPort = syncPort,
        listRecordPhotoPaths = ::listRecordPhotoPaths,
    )
    private val nextFeedPlanMutationMutex = Mutex()
    private val customItemCatalog = CustomItemCatalog(
        customItemDao = customItemDao,
        transactionRunner = transactionRunner,
        syncPort = syncPort,
        resolveFamilyId = {
            getCurrentBaby()?.familyId
                ?: listBabies().firstOrNull()?.familyId
                ?: error("请先添加宝宝")
        },
        requestLocalSync = ::requestLocalSync,
    )

    /**
     * Serializes record create, update, delete, and confirm operations that may
     * change active sleep state. Room transactions provide atomic writes; this
     * lock makes read-check-write sequences deterministic inside this process.
     */
    private val sleepMutationMutex = Mutex()

    fun observeHasBaby(): Flow<Boolean> =
        combine(babyDao.observeAll(), syncPort.session()) { babies, session ->
            visibleBabyEntities(babies, session.role).isNotEmpty()
        }

    fun observeBabies(): Flow<List<Baby>> =
        combine(babyDao.observeAll(), syncPort.session()) { babies, session ->
            visibleBabyEntities(babies, session.role).map { it.toModel() }
        }

    fun observeMemberLocalBabyOrphans(): Flow<List<Baby>> =
        combine(babyDao.observeAll(), syncPort.session()) { babies, session ->
            if (session.role == com.lezi.babylog.sync.FamilyRole.Member) {
                babies.filterNot(BabyEntity::familyAuthority).map { it.toModel() }
            } else {
                emptyList()
            }
        }

    fun observeCurrentBaby(): Flow<Baby?> =
        combine(babyDao.observeAll(), settings.currentBabyId, syncPort.session()) {
                babies,
                storedId,
                session,
            ->
            pickCurrent(visibleBabyEntities(babies, session.role), storedId)
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
        requireCanManageBabyProfiles()
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
                    sex = normalizeBabySexForStorage(input.sex),
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
        requireCanManageBabyProfiles()
        val changed = transactionRunner.run {
            val existing = babyDao.get(babyId) ?: return@run false
            val nickname = normalizeNickname(input.nickname)
            ensureNicknameAvailable(nickname, excludeId = babyId)
            babyDao.update(
                existing.copy(
                    nickname = nickname,
                    sex = normalizeBabySexForStorage(input.sex),
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
        requireCanManageBabyProfiles()
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
        val role = syncPort.session().first().role
        val babies = visibleBabyEntities(babyDao.listAll(), role)
        val stored = settings.currentBabyId.first()
        val entity = pickCurrent(babies, stored) ?: return null
        return entity.toModel()
    }

    suspend fun listBabies(): List<Baby> {
        val role = syncPort.session().first().role
        return visibleBabyEntities(babyDao.listAll(), role).map { it.toModel() }
    }

    /** Read-only family identity seam for feature modules; never creates rows. */
    suspend fun localFamilyIdentity(): LocalFamilyIdentity {
        val user = localUserDao.get()
        val family = familyDao.listAll().firstOrNull()
        return LocalFamilyIdentity(
            deviceId = user?.deviceId ?: "—",
            // Cache of membership 家庭称呼 when joined; local-only placeholder otherwise.
            displayName = user?.displayName?.takeIf { it.isNotBlank() }
                ?: com.lezi.babylog.sync.LOCAL_DEVICE_DISPLAY_NAME,
            familyId = family?.id ?: 1L,
        )
    }

    /**
     * Cache the current membership 家庭称呼 on [LocalUserEntity] after create/join/self-rename.
     * Blank / local placeholder clear the cache so UI falls back to the default.
     */
    suspend fun updateLocalDisplayName(displayName: String?) {
        val existing = localUserDao.get() ?: return
        val normalized = displayName?.trim().orEmpty()
        val stored = normalized.takeIf {
            it.isNotEmpty() && it != com.lezi.babylog.sync.LOCAL_DEVICE_DISPLAY_NAME
        }
        localUserDao.upsert(existing.copy(displayName = stored))
    }

    suspend fun setCurrentBaby(babyId: Long) {
        val baby = babyDao.get(babyId) ?: return
        if (
            syncPort.session().first().role == com.lezi.babylog.sync.FamilyRole.Member &&
            !baby.familyAuthority
        ) {
            throw BabyProfilePermissionException()
        }
        settings.setCurrentBabyId(baby.id)
    }

    /** Member-safe local appearance/order mutation; never advances family LWW data. */
    suspend fun updateBabyLocalPreferences(
        babyId: Long,
        themeColorArgb: Int? = null,
        sortOrder: Int? = null,
    ) {
        val baby = babyDao.get(babyId) ?: return
        if (
            syncPort.session().first().role == com.lezi.babylog.sync.FamilyRole.Member &&
            !baby.familyAuthority
        ) {
            throw BabyProfilePermissionException()
        }
        themeColorArgb?.let { babyDao.updateLocalTheme(baby.id, it) }
        sortOrder?.let { babyDao.updateLocalSortOrder(baby.id, it) }
    }

    /** Reorder only the locally visible Baby list; family profile revisions stay untouched. */
    suspend fun updateBabyLocalOrder(orderedBabyIds: List<Long>) {
        val role = syncPort.session().first().role
        val visible = visibleBabyEntities(babyDao.listAll(), role)
        require(
            orderedBabyIds.size == visible.size &&
                orderedBabyIds.toSet() == visible.mapTo(linkedSetOf(), BabyEntity::id),
        ) {
            "宝宝顺序与当前可见档案不一致"
        }
        val byId = visible.associateBy(BabyEntity::id)
        transactionRunner.run {
            orderedBabyIds.forEachIndexed { index, babyId ->
                val baby = requireNotNull(byId[babyId])
                if (baby.sortOrder != index) {
                    babyDao.updateLocalSortOrder(baby.id, index)
                }
            }
        }
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
    ): Flow<List<Record>> = queries.observeRecords(babyId, startDayInclusive, endDayExclusive, zone)

    fun observeDayRecords(
        babyId: Long,
        day: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Flow<List<Record>> = queries.observeDayRecords(babyId, day, zone)

    /** Observe the baby's single active sleep independently of the viewed date. */
    fun observeOpenSleep(babyId: Long): Flow<Record?> =
        queries.observeOpenSleep(babyId)

    /**
     * All non-deleted care plans in an absolute window for the lezi calendar.
     * Independent of system-calendar projection flags.
     */
    fun observeCarePlansInRange(
        babyId: Long,
        startInclusive: Long,
        endExclusive: Long,
    ): Flow<List<CarePlan>> = queries.observeCarePlansInRange(babyId, startInclusive, endExclusive)

    fun observeCustomItems(): Flow<List<CustomRecordItem>> =
        customItemCatalog.observeCustomItems()

    suspend fun addCustomItem(name: String, iconSlot: Int): Long =
        customItemCatalog.addCustomItem(name, iconSlot)

    suspend fun updateCustomItem(item: CustomRecordItem) =
        customItemCatalog.updateCustomItem(item)

    suspend fun moveCustomItem(id: Long, delta: Int) =
        customItemCatalog.moveCustomItem(id, delta)

    suspend fun deleteCustomItem(id: Long) =
        customItemCatalog.deleteCustomItem(id)

    fun canManageCustomItem(
        item: CustomRecordItem,
        actorMembershipId: String,
        actorIsAdmin: Boolean,
    ): Boolean = customItemCatalog.canManageCustomItem(item, actorMembershipId, actorIsAdmin)

    suspend fun canManageCustomItem(item: CustomRecordItem): Boolean =
        customItemCatalog.canManageCustomItem(item)

    private suspend fun currentMembershipActorId(): String =
        syncPort.session().first().membershipId.trim()

    suspend fun dayRecords(
        babyId: Long,
        day: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Record> = queries.dayRecords(babyId, day, zone)

    suspend fun daySummary(
        babyId: Long,
        day: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
        now: Long = System.currentTimeMillis(),
    ): DailySummary = queries.daySummary(babyId, day, zone, now)

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
        val photos = photoLocalPaths
        val persistedPayload = requireCurrentPayloadJson(
            type = type,
            payloadJson = payloadJson,
            schemaVersion = schemaVersion,
        )
        val now = System.currentTimeMillis()
        val clientUuid = newClientUuid()
        val record = RecordEntity(
            clientUuid = clientUuid,
            babyId = babyId,
            type = type.key,
            timestamp = timestamp,
            endTimestamp = endTimestamp,
            note = note,
            payloadJson = persistedPayload,
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
                    photoAttachmentReconciler.reconcile(
                        PhotoAttachmentOwner.Record(inserted),
                        photos,
                        now,
                    )
                    inserted
                }
            }
        } else {
            transactionRunner.run {
                requireActiveBaby(babyId)
                val inserted = insertRecord(record)
                photoAttachmentReconciler.reconcile(
                    PhotoAttachmentOwner.Record(inserted),
                    photos,
                    now,
                )
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
        /** Null preserves current photos; an explicit empty list clears them. */
        photoLocalPaths: List<String>? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        // Future time must use [convertRecordToCarePlan] after explicit UI confirm.
        RecordTime.pointError(timestamp, nowMillis)?.let {
            throw IllegalArgumentException(it)
        }
        val photos = photoLocalPaths
        val now = System.currentTimeMillis()
        val cleanupCandidates = sleepMutationMutex.withLock {
            transactionRunner.run {
                val existing = recordDao.get(id) ?: return@run emptySet<String>()
                requireActiveBaby(existing.babyId)
                val type = RecordType.fromKey(existing.type) ?: error("未知记录类型")
                requireCurrentPayloadDocument(type, existing.payloadJson, existing.schemaVersion)
                val persistedPayload = requireCurrentPayloadJson(
                    type = type,
                    payloadJson = payloadJson,
                    schemaVersion = schemaVersion,
                )
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
                        payloadJson = persistedPayload,
                        schemaVersion = schemaVersion,
                        updatedAt = now,
                    ),
                )
                photos?.let {
                    photoAttachmentReconciler.reconcile(
                        PhotoAttachmentOwner.Record(id),
                        it,
                        now,
                    )
                }?.tombstonedClientUuids.orEmpty()
            }
        }
        cleanupCommittedPhotoTombstones(cleanupCandidates)
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
        val photos = photoLocalPaths
        val peek = recordDao.get(recordId) ?: error("记录不存在")
        if (peek.deletedAt != null) error("记录已删除")
        val type = RecordType.fromKey(peek.type) ?: error("未知记录类型")
        requireCurrentPayloadDocument(type, peek.payloadJson, peek.schemaVersion)
        require(type.isPlanableCarePlanType || type == RecordType.CUSTOM) {
            "该项目不可转为护理计划"
        }

        suspend fun writeConvert(): Pair<Long, Set<String>> = transactionRunner.run {
            val existing = recordDao.get(recordId) ?: error("记录不存在")
            if (existing.deletedAt != null) error("记录已删除")
            requireActiveBaby(existing.babyId)
            val resolvedType = RecordType.fromKey(existing.type) ?: error("未知记录类型")
            requireCurrentPayloadDocument(
                resolvedType,
                existing.payloadJson,
                existing.schemaVersion,
            )
            require(resolvedType.isPlanableCarePlanType || resolvedType == RecordType.CUSTOM) {
                "该项目不可转为护理计划"
            }
            val nextPayload = payloadJson ?: existing.payloadJson
            requireCurrentPayloadDocument(resolvedType, nextPayload, schemaVersion)
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
            val persistedPayload = requireCurrentPayloadJson(
                type = resolvedType,
                payloadJson = stampedPayload,
                schemaVersion = schemaVersion,
            )

            val at = nextSyncUpdatedAt(existing.updatedAt, System.currentTimeMillis())
            recordDao.softDelete(recordId, at)
            val recordPhotoMutation = photoAttachmentReconciler.tombstone(
                PhotoAttachmentOwner.Record(recordId),
                at,
            )

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
                    payloadJson = persistedPayload,
                    schemaVersion = schemaVersion,
                    status = CarePlanStatus.PENDING.storageKey,
                    createdByMembershipId = currentMembershipActorId(),
                    sourceRecordClientUuid = existing.clientUuid,
                    updatedAt = at,
                    syncDirty = true,
                    systemCalendarProjectionEnabled = projectToSystemCalendar,
                ),
            )
            photoAttachmentReconciler.reconcile(
                PhotoAttachmentOwner.CarePlan(planId),
                photos,
                at,
            )
            planId to recordPhotoMutation.tombstonedClientUuids
        }

        val (planId, cleanupCandidates) = if (type == RecordType.SLEEP) {
            // Same mutex as soft-delete/open-sleep so convert cannot leave half-live intervals.
            sleepMutationMutex.withLock { writeConvert() }
        } else {
            writeConvert()
        }
        // The converted family data is publishable before optional device-local projection.
        cleanupCommittedPhotoTombstones(cleanupCandidates)
        requestLocalSync()
        // Creator keeps full local plan + projection immediately.
        carePlanDao.get(planId)?.toModel()?.let { plan ->
            reminderProjection.projectOrScheduleCarePlanReminder(
                plan,
                projectToSystemCalendar = projectToSystemCalendar,
            )
        }
        return planId
    }

    suspend fun deleteRecord(id: Long): Boolean {
        val (deleted, cleanupCandidates) = sleepMutationMutex.withLock {
            transactionRunner.run {
                val existing = recordDao.get(id)
                if (existing != null && existing.deletedAt == null) {
                    val deletedAt = nextSyncUpdatedAt(
                        existing.updatedAt,
                        System.currentTimeMillis(),
                    )
                    recordDao.softDelete(id, deletedAt)
                    val tombstones = photoAttachmentReconciler.tombstone(
                        PhotoAttachmentOwner.Record(id),
                        deletedAt,
                    ).tombstonedClientUuids
                    true to tombstones
                } else {
                    false to emptySet()
                }
            }
        }
        if (!deleted) return false
        cleanupCommittedPhotoTombstones(cleanupCandidates)
        requestLocalSync()
        return true
    }

    /** Active record photo paths. MediaAsset is the sole current photo source. */
    suspend fun listRecordPhotoPaths(recordId: Long): List<String> {
        val active = mediaAssetDao.listActiveForRecord(recordId)
            .map(MediaAssetEntity::localUri)
            .filter { it.isNotBlank() }
        return active
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
            reminderProjection.cancelCarePlanReminderBestEffort(carePlanId)
            reminderProjection.removeSystemCalendarProjection(carePlanId)
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
        val photos = photoLocalPaths
        val persistedPayload = requireCurrentPayloadJson(
            type = RecordType.SLEEP,
            payloadJson = payloadJson,
            schemaVersion = schemaVersion,
        )
        val (id, cleanupCandidates) = sleepMutationMutex.withLock {
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
                            payloadJson = persistedPayload,
                            schemaVersion = schemaVersion,
                            updatedAt = now,
                        ),
                    )
                    val photoMutation = photoAttachmentReconciler.reconcile(
                        PhotoAttachmentOwner.Record(inserted),
                        photos,
                        now,
                    )
                    inserted to photoMutation.tombstonedClientUuids
                } else {
                    if (currentOpen?.id != expectedOpenSleepId) {
                        throw SleepStateChangedException()
                    }
                    requireCurrentPayloadDocument(
                        RecordType.SLEEP,
                        currentOpen.payloadJson,
                        currentOpen.schemaVersion,
                    )
                    val now = System.currentTimeMillis()
                    updateRecordEntity(
                        currentOpen.copy(
                            timestamp = timestamp,
                            endTimestamp = endTimestamp,
                            note = note,
                            payloadJson = persistedPayload,
                            schemaVersion = schemaVersion,
                            updatedAt = now,
                        ),
                    )
                    val photoMutation = photoAttachmentReconciler.reconcile(
                        PhotoAttachmentOwner.Record(expectedOpenSleepId),
                        photos,
                        now,
                    )
                    expectedOpenSleepId to photoMutation.tombstonedClientUuids
                }
            }
        }
        cleanupCommittedPhotoTombstones(cleanupCandidates)
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
                        payloadJson = RecordPayloadCodec.encode(
                            RecordPayloadDocument(
                                type = RecordType.SLEEP,
                                payload = SleepPayload(),
                                schemaVersion = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
                            ),
                        ),
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

    suspend fun getRecord(id: Long): Record? = queries.getRecord(id)

    suspend fun getCarePlan(id: Long): CarePlan? = queries.getCarePlan(id)

    /**
     * Pending/missed plans for the selected local day (non-today views).
     * Never mixed into record list/summary/search/export.
     */
    fun observeDayPendingPlans(
        babyId: Long,
        day: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Flow<List<CarePlan>> = queries.observeDayPendingPlans(babyId, day, zone)

    /**
     * Today: all overdue open plans plus today's not-yet-due plans, by scheduledAt ASC.
     */
    fun observeTodayPendingPlans(
        babyId: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        nowMillis: Long = System.currentTimeMillis(),
    ): Flow<List<CarePlan>> = queries.observeTodayPendingPlans(babyId, zone, nowMillis)

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
        require(schemaVersion == CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION) {
            "仅支持当前 payload schema"
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
        val photos = photoLocalPaths
        val persistedPayload = requireCurrentPayloadJson(
            type = type,
            payloadJson = stampedPayload,
            schemaVersion = schemaVersion,
        )
        requireCustomPayloadMatches(type, persistedPayload, schemaVersion, resolvedCustomItemId)
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
                    payloadJson = persistedPayload,
                    schemaVersion = schemaVersion,
                    status = CarePlanStatus.PENDING.storageKey,
                    createdByMembershipId = currentMembershipActorId(),
                    updatedAt = now,
                    syncDirty = true,
                    systemCalendarProjectionEnabled = projectToSystemCalendar,
                ),
            )
            photoAttachmentReconciler.reconcile(
                PhotoAttachmentOwner.CarePlan(planId),
                photos,
                now,
            )
            planId
        }
        // Shared CarePlan is committed and publishable before optional device-local projection.
        requestLocalSync()
        // Creator: full local plan + own reminders/calendar immediately (amber until publish).
        carePlanDao.get(id)?.toModel()?.let { plan ->
            reminderProjection.projectOrScheduleCarePlanReminder(
                plan,
                projectToSystemCalendar = projectToSystemCalendar,
            )
        }
        return id
    }

    /**
     * Create or move the one open, family-shared next-feed CarePlan for [babyId].
     * The marker is stable sync data but is stripped from every model/UI surface.
     * Intent-only feed payloads deliberately contain no fabricated amount/duration.
     */
    suspend fun reconcileNextFeedPlan(babyId: Long): NextFeedPlanReconciliation =
        nextFeedPlanMutationMutex.withLock {
            requireActiveBaby(babyId)
            openNextFeedPlans(carePlanDao.listAllIncludingDeleted(), babyId)
                .firstOrNull()
                ?.let {
                    NextFeedPlanReconciliation.Found(
                        clientUuid = it.clientUuid,
                        scheduledAtMillis = it.scheduledAt,
                    )
                }
                ?: NextFeedPlanReconciliation.Absent
        }

    suspend fun scheduleNextFeedCarePlan(
        babyId: Long,
        feedType: RecordType,
        scheduledAt: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        nowMillis: Long = System.currentTimeMillis(),
    ): Long {
        require(feedType in NEXT_FEED_TYPES) { "仅喂养记录可安排下次喂养" }
        require(scheduledAt > nowMillis) { "下次喂养须选择未来时刻" }
        val baby = requireActiveBaby(babyId)
        val payload = when (feedType) {
            RecordType.NURSING -> NursingPayload()
            RecordType.FORMULA, RecordType.PUMPED_FEED -> MilkPayload(feedType)
            else -> error("unsupported next-feed type")
        }
        val payloadJson = RecordPayloadCodec.encode(
            RecordPayloadDocument(
                type = feedType,
                payload = payload,
                schemaVersion = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
            ),
        )
        val (id, duplicateIds) = nextFeedPlanMutationMutex.withLock {
            transactionRunner.run {
                val allPlans = carePlanDao.listAllIncludingDeleted()
                val open = openNextFeedPlans(allPlans, babyId)
                val existing = open.firstOrNull()
                if (existing != null) {
                    requireCanManageCarePlan(existing)
                    open.drop(1).forEach { requireCanManageCarePlan(it) }
                }
                val at = nowMillis.coerceAtLeast((existing?.updatedAt ?: 0L) + 1L)
                val plan = if (existing == null) {
                    val generationSeed = nextFeedPlanGenerationSeed(
                        allPlans.filter {
                            it.babyId == babyId && isNextFeedPlanNote(it.note)
                        },
                    )
                    CarePlanEntity(
                        clientUuid = nextFeedPlanClientUuid(baby.clientUuid, generationSeed),
                        babyId = babyId,
                        type = feedType.key,
                        scheduledAt = scheduledAt,
                        scheduledZoneId = zone.id,
                        note = nextFeedPlanNote(null),
                        payloadJson = payloadJson,
                        schemaVersion = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
                        status = CarePlanStatus.PENDING.storageKey,
                        createdByMembershipId = currentMembershipActorId(),
                        updatedAt = at,
                        syncDirty = true,
                        systemCalendarProjectionEnabled = true,
                    )
                } else {
                    existing.copy(
                        type = feedType.key,
                        scheduledAt = scheduledAt,
                        scheduledZoneId = zone.id,
                        note = nextFeedPlanNote(visibleCarePlanNote(existing.note)),
                        payloadJson = payloadJson,
                        schemaVersion = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
                        status = CarePlanStatus.PENDING.storageKey,
                        fulfilledRecordClientUuid = null,
                        fulfilledAt = null,
                        updatedAt = at,
                        syncDirty = true,
                        systemCalendarProjectionEnabled = true,
                        systemCalendarReminderReady = false,
                    )
                }
                val planId = carePlanDao.upsert(plan)
                // Defensive healing for old concurrent duplicates: preserve the oldest
                // stable identity and tombstone every other open marker row.
                open.drop(1).forEach { duplicate ->
                    carePlanDao.softDelete(
                        duplicate.id,
                        at.coerceAtLeast(duplicate.updatedAt + 1L),
                    )
                }
                planId to open.drop(1).map(CarePlanEntity::id)
            }
        }
        duplicateIds.forEach { duplicateId ->
            reminderProjection.cancelCarePlanReminderBestEffort(duplicateId)
            reminderProjection.removeSystemCalendarProjection(duplicateId)
        }
        // Shared next-feed plan is publishable before optional device-local projection.
        requestLocalSync()
        carePlanDao.get(id)?.toModel()?.let { plan ->
            reminderProjection.projectOrScheduleCarePlanReminder(
                plan,
                projectToSystemCalendar = true,
            )
        }
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
        val photos = photoLocalPaths
        // Freeze confirm time once for the candidate; wall clock for writer bookkeeping.
        val confirmedAt = System.currentTimeMillis()
        val now = confirmedAt

        // Peek type to decide whether sleep mutex is required (fail closed on races).
        val planPeek = carePlanDao.get(carePlanId) ?: error("护理计划不存在")
        val planType = RecordType.fromKey(planPeek.type) ?: error("未知记录类型")
        requireCurrentPayloadDocument(planType, planPeek.payloadJson, planPeek.schemaVersion)
        requireCustomPayloadMatches(
            planType,
            planPeek.payloadJson,
            planPeek.schemaVersion,
            planPeek.customItemId,
        )

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
            requireCurrentPayloadDocument(type, plan.payloadJson, plan.schemaVersion)
            requireCustomPayloadMatches(
                type,
                plan.payloadJson,
                plan.schemaVersion,
                plan.customItemId,
            )
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
            val nextPayload = payloadJson ?: plan.payloadJson
            val persistedPayload = requireCurrentPayloadJson(
                type = type,
                payloadJson = nextPayload,
                schemaVersion = schemaVersion,
            )
            requireCustomPayloadMatches(
                type,
                persistedPayload,
                schemaVersion,
                plan.customItemId,
            )
            val recordClientUuid = newClientUuid()
            val record = RecordEntity(
                clientUuid = recordClientUuid,
                babyId = plan.babyId,
                type = type.key,
                timestamp = actualTimestamp,
                endTimestamp = resolvedEnd,
                note = note ?: plan.note,
                payloadJson = persistedPayload,
                schemaVersion = schemaVersion,
                updatedAt = now,
            )
            val inserted = insertRecord(record)
            photoAttachmentReconciler.reconcile(
                PhotoAttachmentOwner.Record(inserted),
                photos,
                now,
            )
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
        reminderProjection.cancelCarePlanReminderBestEffort(carePlanId)
        reminderProjection.removeSystemCalendarProjection(carePlanId)
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
        requireCurrentPayloadDocument(expectedType, plan.payloadJson, plan.schemaVersion)
        requireCustomPayloadMatches(
            expectedType,
            plan.payloadJson,
            plan.schemaVersion,
            plan.customItemId,
        )
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
     * Timeline/export surface filter: drop records that lost multi-candidate
     * fulfillment. Loser rows and photos remain stored for audit conversion.
     *
     * Not related to ordinary `/v1/push` transport.
     */
    suspend fun filterSurfaceRecords(records: List<Record>): List<Record> =
        queries.filterSurfaceRecords(records)

    /** True when the record is visible on normal care surfaces (not a conflict loser). */
    suspend fun isSurfaceRecord(clientUuid: String): Boolean =
        queries.isSurfaceRecord(clientUuid)

    /** True when the joined session is family owner/admin. */
    suspend fun isFamilyAdmin(): Boolean {
        val session = syncPort.session().first()
        return session.role == com.lezi.babylog.sync.FamilyRole.Owner
    }


    suspend fun listConflictNotAdoptedAudits(
        carePlanClientUuid: String? = null,
        babyId: Long? = null,
    ): List<ConflictNotAdoptedAudit> =
        conflictAuditQueries.listConflictNotAdoptedAudits(carePlanClientUuid, babyId)

    suspend fun getConflictNotAdoptedAudit(
        candidateClientUuid: String,
    ): ConflictNotAdoptedAudit? =
        conflictAuditQueries.getConflictNotAdoptedAudit(candidateClientUuid)

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
            // MediaAsset is the sole current photo source, including tombstoned facts.
            val photos = if (source.deletedAt == null) {
                listRecordPhotoPaths(source.id)
            } else {
                mediaAssetDao.listForRecord(source.id)
                    .map(MediaAssetEntity::localUri)
                    .filter(String::isNotBlank)
                    .distinct()
            }
            val targetUuid = existingPointer.ifEmpty { newClientUuid() }
            val at = nowMillis.coerceAtLeast(source.updatedAt + 1)
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
                payloadJson = source.payloadJson,
                schemaVersion = source.schemaVersion,
                updatedAt = at,
                deletedAt = null,
                syncDirty = true,
            )
            val inserted = insertRecord(newRecord)
            photoAttachmentReconciler.reconcile(
                PhotoAttachmentOwner.Record(inserted),
                photos,
                at,
            )

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
        return canManageCreatorOwnedFamilyEntity(
            creatorMembershipId = plan.createdByMembershipId,
            actorMembershipId = session.membershipId.trim(),
            actorIsAdmin = session.role == com.lezi.babylog.sync.FamilyRole.Owner,
            creatorAcknowledgementPending = session.isCreatorAcknowledgementPending(
                entityType = "care_plan",
                clientUuid = plan.clientUuid,
            ),
        )
    }

    private suspend fun actorCanManageCarePlan(plan: CarePlanEntity): Boolean {
        val session = syncPort.session().first()
        return canManageCreatorOwnedFamilyEntity(
            creatorMembershipId = plan.createdByMembershipId,
            actorMembershipId = session.membershipId.trim(),
            actorIsAdmin = session.role == com.lezi.babylog.sync.FamilyRole.Owner,
            creatorAcknowledgementPending = session.isCreatorAcknowledgementPending(
                entityType = "care_plan",
                clientUuid = plan.clientUuid,
            ),
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
        projectToSystemCalendar: Boolean? = null,
    ) {
        val photos = photoLocalPaths
        val cleanupCandidates = transactionRunner.run {
            val plan = carePlanDao.get(carePlanId) ?: error("护理计划不存在")
            if (plan.deletedAt != null) error("护理计划已删除")
            val status = CarePlanStatus.fromStorage(plan.status)
            require(status == CarePlanStatus.PENDING || status == CarePlanStatus.MISSED) {
                "已完成或已跳过的计划不可编辑"
            }
            requireCanManageCarePlan(plan)
            requireActiveBaby(plan.babyId)
            val type = RecordType.fromKey(plan.type) ?: error("未知记录类型")
            requireCurrentPayloadDocument(type, plan.payloadJson, plan.schemaVersion)
            requireCustomPayloadMatches(
                type,
                plan.payloadJson,
                plan.schemaVersion,
                plan.customItemId,
            )
            val at = nowMillis.coerceAtLeast(plan.updatedAt + 1)
            val nextSchemaVersion = schemaVersion ?: plan.schemaVersion
            val rawNextPayload = payloadJson ?: plan.payloadJson
            val nextPayload = if (photos != null) {
                requireCurrentPayloadJson(
                    type = type,
                    payloadJson = rawNextPayload,
                    schemaVersion = nextSchemaVersion,
                )
            } else {
                requireCurrentPayloadDocument(type, rawNextPayload, nextSchemaVersion)
                rawNextPayload
            }
            requireCustomPayloadMatches(
                type,
                nextPayload,
                nextSchemaVersion,
                plan.customItemId,
            )
            val nextZoneId = zone?.id ?: plan.scheduledZoneId
            val desiredProjection =
                projectToSystemCalendar ?: plan.systemCalendarProjectionEnabled
            val persistedNote = if (isNextFeedPlanNote(plan.note)) {
                nextFeedPlanNote(note)
            } else {
                note
            }
            val sharedChanged =
                plan.scheduledAt != scheduledAt ||
                    plan.scheduledZoneId != nextZoneId ||
                    plan.note != persistedNote ||
                    plan.payloadJson != nextPayload ||
                    plan.schemaVersion != nextSchemaVersion ||
                    plan.status != CarePlanStatus.PENDING.storageKey
            // Photo-only edits are still atomic CarePlan bundle mutations. Reconcile first so an
            // identical explicit list remains a no-op; the enclosing transaction owns both writes.
            val photoMutation = photos?.let {
                photoAttachmentReconciler.reconcile(
                    PhotoAttachmentOwner.CarePlan(carePlanId),
                    it,
                    at,
                )
            }
            val photosChanged = photoMutation?.changed == true
            if (sharedChanged || photosChanged) {
                // Persist stored status as pending; missed is always derived from clock.
                carePlanDao.update(plan.copy(
                    scheduledAt = scheduledAt,
                    scheduledZoneId = nextZoneId,
                    note = persistedNote,
                    payloadJson = nextPayload,
                    schemaVersion = nextSchemaVersion,
                    status = CarePlanStatus.PENDING.storageKey,
                    updatedAt = at,
                    syncDirty = true,
                    systemCalendarProjectionEnabled = desiredProjection,
                    systemCalendarReminderReady = false,
                ))
            } else if (desiredProjection != plan.systemCalendarProjectionEnabled) {
                carePlanDao.updateSystemCalendarProjectionEnabled(
                    clientUuid = plan.clientUuid,
                    enabled = desiredProjection,
                )
            }
            photoMutation?.tombstonedClientUuids.orEmpty()
        }
        // Shared update is committed and publishable before optional local side effects.
        cleanupCommittedPhotoTombstones(cleanupCandidates)
        requestLocalSync()
        calendarReminderMutationGuard.withLock {
            carePlanDao.get(carePlanId)?.toModel()?.let { plan ->
                if (plan.scheduledAt <= nowMillis) {
                    reminderProjection.cancelCarePlanReminderBestEffort(plan.id)
                    reminderProjection.removeSystemCalendarProjectionLocked(plan.id)
                } else {
                    reminderProjection.projectOrScheduleCarePlanReminderLocked(
                        plan,
                        projectToSystemCalendar = plan.systemCalendarProjectionEnabled,
                    )
                }
            }
        }
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
        reminderProjection.cancelCarePlanReminderBestEffort(carePlanId)
        reminderProjection.removeSystemCalendarProjection(carePlanId)
        requestLocalSync()
    }

    /**
     * Soft-delete a plan (tombstone). Removes it from pending views; no Record created.
     */
    suspend fun deleteCarePlan(
        carePlanId: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        val (deleted, cleanupCandidates) = transactionRunner.run {
            val plan = carePlanDao.get(carePlanId)
                ?: return@run false to emptySet<String>()
            if (plan.deletedAt != null) return@run false to emptySet<String>()
            requireCanManageCarePlan(plan)
            val deletedAt = nowMillis.coerceAtLeast(plan.updatedAt + 1)
            carePlanDao.softDelete(carePlanId, deletedAt)
            val tombstones = photoAttachmentReconciler.tombstone(
                PhotoAttachmentOwner.CarePlan(carePlanId),
                deletedAt,
            ).tombstonedClientUuids
            true to tombstones
        }
        if (!deleted) return false
        cleanupCommittedPhotoTombstones(cleanupCandidates)
        reminderProjection.cancelCarePlanReminderBestEffort(carePlanId)
        reminderProjection.removeSystemCalendarProjection(carePlanId)
        requestLocalSync()
        return true
    }


    suspend fun onFamilyCarePlansApplied(
        planClientUuids: List<String>,
        nowMillis: Long = System.currentTimeMillis(),
    ) = reminderProjection.onFamilyCarePlansApplied(planClientUuids, nowMillis)

    suspend fun setCarePlanLocalRemindersEnabled(
        enabled: Boolean,
        nowMillis: Long = System.currentTimeMillis(),
    ) = reminderProjection.setCarePlanLocalRemindersEnabled(enabled, nowMillis)

    suspend fun projectOrScheduleCarePlanReminder(
        plan: CarePlan,
        projectToSystemCalendar: Boolean = plan.systemCalendarProjectionEnabled,
    ): Boolean = reminderProjection.projectOrScheduleCarePlanReminder(
        plan,
        projectToSystemCalendar,
    )

    suspend fun reprojectOpenFutureSystemCalendarCopies(
        nowMillis: Long = System.currentTimeMillis(),
    ) = reminderProjection.reprojectOpenFutureSystemCalendarCopies(nowMillis)

    suspend fun isCarePlanSystemCalendarUnsynced(carePlanId: Long): Boolean =
        reminderProjection.isCarePlanSystemCalendarUnsynced(carePlanId)

    suspend fun disableSystemCalendarProjection() =
        reminderProjection.disableSystemCalendarProjection()

    suspend fun rescheduleCarePlanReminders(
        nowMillis: Long = System.currentTimeMillis(),
    ) = reminderProjection.rescheduleCarePlanReminders(nowMillis)

    suspend fun shouldDeliverCarePlanReminder(
        carePlanId: Long,
        clientUuid: String,
        expectedScheduledAt: Long,
    ): Boolean = reminderProjection.shouldDeliverCarePlanReminder(
        carePlanId,
        clientUuid,
        expectedScheduledAt,
    )

    suspend fun getCarePlanByClientUuid(clientUuid: String): CarePlan? =
        queries.getCarePlanByClientUuid(clientUuid)

    /** Active plan photo paths. MediaAsset is authoritative. */
    suspend fun listCarePlanPhotoPaths(carePlanId: Long): List<String> {
        val active = mediaAssetDao.listActiveForCarePlan(carePlanId)
            .map(MediaAssetEntity::localUri)
            .filter { it.isNotBlank() }
        return active
    }

    suspend fun weekSummary(
        babyId: Long,
        weekStart: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
        now: Long = System.currentTimeMillis(),
    ): WeekSummary = queries.weekSummary(babyId, weekStart, zone, now)

    suspend fun search(babyId: Long, query: String): List<Record> =
        queries.search(babyId, query)

    suspend fun recentCareSummary(
        babyId: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): WidgetSummaryDto = queries.recentCareSummary(babyId, zone)

    fun observeMeasurements(babyId: Long, type: RecordType): Flow<List<Record>> =
        queries.observeMeasurements(babyId, type)

    suspend fun recentMilkAmounts(
        babyId: Long,
        type: RecordType,
        limit: Int = 3,
    ): List<Int> = queries.recentMilkAmounts(babyId, type, limit)

    suspend fun recentNotes(
        babyId: Long,
        type: RecordType,
        limit: Int = 5,
    ): List<String> = queries.recentNotes(babyId, type, limit)

    suspend fun renameBaby(babyId: Long, nickname: String) {
        requireCanManageBabyProfiles()
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
        requireAllowedBabyMerge(source, target)
        return BabyMergePreview(
            sourceBabyId = source.id,
            sourceNickname = source.nickname,
            targetBabyId = target.id,
            targetNickname = target.nickname,
            recordCount = recordDao.listForBaby(source.id).size,
            carePlanCount = carePlanDao.listAllIncludingDeleted().count {
                it.babyId == source.id && it.deletedAt == null
            },
        )
    }

    /**
     * Apply a merge only after the UI has shown [previewBabyMerge].
     * The target profile stays intact; source records and care plans (including
     * tombstones), plus avatar media rows, are re-bound to the target baby.
     */
    suspend fun mergeBabyProfiles(sourceBabyId: Long, targetBabyId: Long): Boolean =
        mergeBabyProfiles(sourceBabyId, targetBabyId, forceMemberRules = false)

    private suspend fun mergeBabyProfiles(
        sourceBabyId: Long,
        targetBabyId: Long,
        forceMemberRules: Boolean,
    ): Boolean {
        if (sourceBabyId == targetBabyId) return false
        val now = System.currentTimeMillis()
        val writeResult = sleepMutationMutex.withLock {
            transactionRunner.run {
                val source = babyDao.get(sourceBabyId) ?: return@run null
                val target = babyDao.get(targetBabyId) ?: return@run null
                if (source.familyId != target.familyId) return@run null
                val memberMerge = requireAllowedBabyMerge(source, target, forceMemberRules)
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
                val allCarePlans = carePlanDao.listAllIncludingDeleted()
                val sourceCarePlans = allCarePlans
                    .filter { it.babyId == source.id }
                val targetMarkerPlans = allCarePlans.filter {
                    it.babyId == target.id && isNextFeedPlanNote(it.note)
                }
                var targetOpenNextFeed = targetMarkerPlans.firstOrNull {
                    it.deletedAt == null &&
                        it.status in setOf(
                            CarePlanStatus.PENDING.storageKey,
                            CarePlanStatus.MISSED.storageKey,
                        )
                }
                val reprojectPlanUuids = mutableListOf<String>()
                val discardedNextFeedIds = mutableListOf<Long>()
                val identityMigrations = mutableListOf<CarePlanIdentityMigration>()
                sourceCarePlans.forEach { plan ->
                    val isOpenNextFeed = plan.deletedAt == null &&
                        plan.status in setOf(
                            CarePlanStatus.PENDING.storageKey,
                            CarePlanStatus.MISSED.storageKey,
                        ) &&
                        isNextFeedPlanNote(plan.note)
                    when {
                        isOpenNextFeed && targetOpenNextFeed != null -> {
                            val discardedAt = nextSyncUpdatedAt(plan.updatedAt, now)
                            // Keep the target profile's stable plan identity. A member-local
                            // orphan was never family data, while an owner merge publishes a
                            // normal tombstone so every peer converges on one open plan.
                            carePlanDao.update(
                                plan.copy(
                                    deletedAt = discardedAt,
                                    updatedAt = discardedAt,
                                    syncDirty = !memberMerge,
                                ),
                            )
                            discardedNextFeedIds += plan.id
                        }
                        memberMerge && isOpenNextFeed -> {
                            val canonicalUuid = nextFeedPlanClientUuid(
                                target.clientUuid,
                                nextFeedPlanGenerationSeed(targetMarkerPlans),
                            )
                            val moved = plan.copy(
                                clientUuid = canonicalUuid,
                                babyId = target.id,
                                updatedAt = nextSyncUpdatedAt(plan.updatedAt, now),
                                syncDirty = true,
                            )
                            carePlanDao.update(moved)
                            targetOpenNextFeed = moved
                            if (canonicalUuid == plan.clientUuid) {
                                reprojectPlanUuids += canonicalUuid
                            } else {
                                identityMigrations += CarePlanIdentityMigration(
                                    carePlanId = plan.id,
                                    oldClientUuid = plan.clientUuid,
                                    newClientUuid = canonicalUuid,
                                    oldSystemCalendarEventId = plan.systemCalendarEventId,
                                    oldSystemCalendarReminderReady =
                                        plan.systemCalendarReminderReady,
                                    oldSystemCalendarProjectionPending =
                                        plan.systemCalendarProjectionPending,
                                )
                            }
                        }
                        else -> {
                            carePlanDao.update(
                                plan.copy(
                                    babyId = target.id,
                                    updatedAt = nextSyncUpdatedAt(plan.updatedAt, now),
                                    syncDirty = true,
                                ),
                            )
                            reprojectPlanUuids += plan.clientUuid
                        }
                    }
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
                            // A member-local orphan never becomes a family tombstone.
                            syncDirty = !memberMerge,
                        )
                    },
                )
                BabyMergeWriteResult(
                    reprojectPlanClientUuids = reprojectPlanUuids,
                    discardedNextFeedPlanIds = discardedNextFeedIds,
                    carePlanIdentityMigrations = identityMigrations,
                )
            }
        }
        if (writeResult == null) return false
        if (settings.currentBabyId.first() == sourceBabyId) {
            settings.setCurrentBabyId(targetBabyId)
        }
        writeResult.discardedNextFeedPlanIds.forEach { planId ->
            reminderProjection.cancelCarePlanReminderBestEffort(planId)
            reminderProjection.removeSystemCalendarProjection(planId)
        }
        writeResult.carePlanIdentityMigrations.forEach { migration ->
            reminderProjection.migrateCarePlanIdentity(
                carePlanId = migration.carePlanId,
                oldClientUuid = migration.oldClientUuid,
                newClientUuid = migration.newClientUuid,
                oldSystemCalendarEventId = migration.oldSystemCalendarEventId,
                oldSystemCalendarReminderReady = migration.oldSystemCalendarReminderReady,
                oldSystemCalendarProjectionPending = migration.oldSystemCalendarProjectionPending,
            )
        }
        reminderProjection.reprojectMergedBabySystemCalendarCopies(
            writeResult.reprojectPlanClientUuids,
        )
        requestLocalSync()
        return true
    }

    /**
     * After a member applies the authority Baby set, deterministically rebind
     * every local orphan only when there is exactly one authority target.
     */
    suspend fun reconcileMemberLocalBabies(): Int =
        reconcileMemberLocalBabies(forceMemberRules = false)

    internal suspend fun reconcileMemberLocalBabiesAfterFamilyApply(): Int =
        reconcileMemberLocalBabies(forceMemberRules = true)

    private suspend fun reconcileMemberLocalBabies(forceMemberRules: Boolean): Int {
        if (
            !forceMemberRules &&
            syncPort.session().first().role != com.lezi.babylog.sync.FamilyRole.Member
        ) return 0
        val active = babyDao.listAll()
        val authorities = active.filter(BabyEntity::familyAuthority)
        if (authorities.size != 1) return 0
        val target = authorities.single()
        var merged = 0
        active.filterNot(BabyEntity::familyAuthority).forEach { source ->
            if (mergeBabyProfiles(source.id, target.id, forceMemberRules = true)) merged++
        }
        settings.setCurrentBabyId(target.id)
        return merged
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
        requireNotNull(babyDao.get(babyId)) { "宝宝档案不存在，请返回后重试" }.also { baby ->
            if (
                syncPort.session().first().role == com.lezi.babylog.sync.FamilyRole.Member &&
                !baby.familyAuthority
            ) {
                throw BabyProfilePermissionException()
            }
        }

    private suspend fun requireCanManageBabyProfiles() {
        if (syncPort.session().first().role == com.lezi.babylog.sync.FamilyRole.Member) {
            throw BabyProfilePermissionException()
        }
    }

    /** @return true only for the special member orphan -> authority merge path. */
    private suspend fun requireAllowedBabyMerge(
        source: BabyEntity,
        target: BabyEntity,
        forceMemberRules: Boolean = false,
    ): Boolean {
        val member = forceMemberRules ||
            syncPort.session().first().role == com.lezi.babylog.sync.FamilyRole.Member
        if (member && (source.familyAuthority || !target.familyAuthority)) {
            throw BabyProfilePermissionException()
        }
        return member
    }

    private fun visibleBabyEntities(
        babies: List<BabyEntity>,
        role: com.lezi.babylog.sync.FamilyRole,
    ): List<BabyEntity> = if (role == com.lezi.babylog.sync.FamilyRole.Member) {
        babies.filter(BabyEntity::familyAuthority)
    } else {
        babies
    }

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


    private suspend fun insertRecord(record: RecordEntity): Long {
        val type = RecordType.fromKey(record.type) ?: error("未知记录类型")
        requireCurrentPayloadDocument(type, record.payloadJson, record.schemaVersion)
        val membershipId = currentMembershipActorId()
        return recordDao.upsert(
            if (record.createdByMembershipId.isBlank() && membershipId.isNotEmpty()) {
                record.copy(createdByMembershipId = membershipId)
            } else {
                record
            },
        )
    }

    private suspend fun updateRecordEntity(record: RecordEntity) {
        val type = RecordType.fromKey(record.type) ?: error("未知记录类型")
        requireCurrentPayloadDocument(type, record.payloadJson, record.schemaVersion)
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

    /**
     * Keep at most one open sleep per baby. Older open intervals are closed at
     * the next open's start and flagged anomaly (covers sync-introduced dups).
     */
    private suspend fun healDuplicateOpenSleeps(babyId: Long) {
        val opens = recordDao.listOpenSleeps(babyId)
        if (opens.size <= 1) return
        val now = clock.nowMillis()
        val decision = normalizeOpenSleeps(
            candidates = opens.map { open ->
                OpenSleepCandidate(
                    stableKey = open.clientUuid,
                    startedAtMillis = open.timestamp,
                )
            },
            repairAtMillis = now,
        )
        val byClientUuid = opens.associateBy(RecordEntity::clientUuid)
        for (closure in decision.closures) {
            val current = byClientUuid.getValue(closure.candidate.stableKey)
            val flagged = withAnomaly(current.payloadJson, current.schemaVersion)
            updateRecordEntity(
                current.copy(
                    endTimestamp = closure.closedAtMillis,
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

    /** Logical writes stay committed when best-effort physical GC must retry. */
    private suspend fun cleanupCommittedPhotoTombstones(clientUuids: Set<String>) {
        if (clientUuids.isEmpty()) return
        syncPort.cleanupTombstonedMedia(clientUuids)
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
        type = RecordType.fromKey(type) ?: error("Unknown record type: $type"),
        timestamp = timestamp,
        endTimestamp = endTimestamp,
        note = note,
        createdByMembershipId = createdByMembershipId,
        payloadJson = payloadJson,
        schemaVersion = schemaVersion,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        syncDirty = syncDirty,
        familyPublishedUpdatedAt = familyPublishedUpdatedAt,
    )

internal fun CustomItemEntity.toModel(): CustomRecordItem =
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

internal fun CarePlanEntity.toModel(): CarePlan =
    CarePlan(
        id = id,
        clientUuid = clientUuid,
        babyId = babyId,
        type = RecordType.fromKey(type) ?: error("Unknown care plan type: $type"),
        customItemId = customItemId,
        scheduledAt = scheduledAt,
        scheduledZoneId = scheduledZoneId,
        note = visibleCarePlanNote(note),
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
        systemCalendarProjectionEnabled = systemCalendarProjectionEnabled,
        familyPublishedUpdatedAt = familyPublishedUpdatedAt,
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
 * Empty creator + exact local pending acknowledgement → temporarily allow.
 * Any other empty creator + joined non-admin → deny (await authoritative data).
 * Non-empty creator match → member may manage own entity.
 * Admin always may manage (including after creator leave).
 */
fun canManageCreatorOwnedFamilyEntity(
    creatorMembershipId: String,
    actorMembershipId: String,
    actorIsAdmin: Boolean,
    creatorAcknowledgementPending: Boolean = false,
): Boolean {
    if (actorIsAdmin) return true
    val creator = creatorMembershipId.trim()
    val actor = actorMembershipId.trim()
    if (creator.isEmpty() && actor.isEmpty()) return true
    if (creator.isEmpty() && creatorAcknowledgementPending) return true
    if (creator.isEmpty()) return false
    return creator == actor
}

private fun requireCurrentPayloadDocument(
    type: RecordType,
    payloadJson: String,
    schemaVersion: Int,
): RecordPayloadDocument {
    require(schemaVersion == CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION) {
        "仅支持当前 payload schema"
    }
    val document = RecordPayloadCodec.decode(type, payloadJson, schemaVersion)
    require(!document.isUnknown) { "payload 与当前 $type 类型不匹配或格式损坏" }
    require(document.type == type && document.payload.type == type) {
        "payload 类型与记录类型不匹配"
    }
    if (type == RecordType.CUSTOM) {
        require(document.payload is CustomPayload) { "CUSTOM 必须携带具体项目身份" }
    }
    return document
}

private fun requireCurrentPayloadJson(
    type: RecordType,
    payloadJson: String,
    schemaVersion: Int,
): String {
    requireCurrentPayloadDocument(type, payloadJson, schemaVersion)
    return payloadJson
}

private fun requireCustomPayloadMatches(
    type: RecordType,
    payloadJson: String,
    schemaVersion: Int,
    expectedCustomItemId: Long?,
) {
    if (type != RecordType.CUSTOM) {
        require(expectedCustomItemId == null) { "内置项目不得携带 customItemId" }
        return
    }
    val expected = expectedCustomItemId?.takeIf { it > 0L }
        ?: throw IllegalArgumentException("CUSTOM 计划必须携带具体项目身份")
    val payload = requireCurrentPayloadDocument(type, payloadJson, schemaVersion).payload
        as CustomPayload
    require(payload.customItemId == expected) { "CUSTOM payload 与计划项目身份不匹配" }
}

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
    val existing = if (payloadJson.trim() == "{}") {
        null
    } else {
        val document = requireCurrentPayloadDocument(
            RecordType.CUSTOM,
            payloadJson,
            CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
        )
        document.payload as CustomPayload
    }
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

private fun parseSex(raw: String): Sex = com.lezi.babylog.core.model.parseBabySex(raw)

/**
 * Canonical Room storage for baby sex: Home-LAN wire values only
 * (`female` / `male` / null). Delegates to [com.lezi.babylog.core.model.normalizeBabySex].
 */
internal fun normalizeBabySexForStorage(raw: String?): String? =
    com.lezi.babylog.core.model.normalizeBabySex(raw)

private fun withAnomaly(payloadJson: String, schemaVersion: Int): Pair<String, Int> {
    val document = RecordPayloadCodec.decode(RecordType.SLEEP, payloadJson, schemaVersion)
    val sleep = document.payload as? SleepPayload ?: return payloadJson to schemaVersion
    val normalized = document.copy(
        payload = sleep.copy(anomaly = true),
        schemaVersion = CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION,
    )
    return RecordPayloadCodec.encode(normalized) to CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION
}
