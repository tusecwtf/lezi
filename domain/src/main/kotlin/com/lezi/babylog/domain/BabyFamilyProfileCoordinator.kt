package com.lezi.babylog.domain

import com.lezi.babylog.core.common.newClientUuid
import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.CarePlanDao
import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.FamilyDao
import com.lezi.babylog.core.database.FamilyEntity
import com.lezi.babylog.core.database.LocalUserDao
import com.lezi.babylog.core.database.LocalUserEntity
import com.lezi.babylog.core.database.MediaAssetDao
import com.lezi.babylog.core.database.MembershipDao
import com.lezi.babylog.core.database.MembershipEntity
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.core.model.CarePlanStatus
import com.lezi.babylog.core.model.birthWeightValidationError
import com.lezi.babylog.core.model.normalizeBabyNickname
import com.lezi.babylog.sync.SyncPort
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

internal class BabyFamilyProfileCoordinator(
    private val babyDao: BabyDao,
    private val recordDao: RecordDao,
    private val carePlanDao: CarePlanDao,
    private val localUserDao: LocalUserDao,
    private val familyDao: FamilyDao,
    private val membershipDao: MembershipDao,
    private val mediaAssetDao: MediaAssetDao,
    private val settings: SettingsStore,
    private val syncPort: SyncPort,
    private val transactionRunner: DatabaseTransactionRunner,
    private val reminderProjection: CarePlanReminderProjection,
    private val sleepMutationMutex: Mutex,
    private val healDuplicateOpenSleeps: suspend (Long) -> Unit,
    private val requestLocalSync: () -> Unit,
) {
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
     * Cache the current membership 家庭称呼 after create/login/claim/self-rename.
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

    internal suspend fun requireActiveBaby(babyId: Long): BabyEntity =
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

    private fun pickCurrent(babies: List<BabyEntity>, storedId: Long?): BabyEntity? {
        if (babies.isEmpty()) return null
        return storedId?.let { id -> babies.find { it.id == id } } ?: babies.first()
    }
}
