package com.lezi.babylog.domain.carelog

import com.lezi.babylog.core.common.MediaContentDigest
import com.lezi.babylog.core.common.newClientUuid
import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.MediaAssetDao
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.ProjectedRecordEntity
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.database.RecordWakeProjectionDao
import com.lezi.babylog.core.database.causal.WakeObservationDao
import com.lezi.babylog.core.database.causal.WakeObservationEntity
import com.lezi.babylog.core.model.MAX_RECORD_PHOTOS
import com.lezi.babylog.core.model.RecordTime
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.SleepIntervalProjection
import com.lezi.babylog.core.model.isWakeShortcutTarget
import com.lezi.babylog.core.model.validateWakeTimestamp
import com.lezi.babylog.domain.RecordPermissionException
import com.lezi.babylog.domain.nextSyncUpdatedAt
import com.lezi.babylog.domain.toModel
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.session.FamilyRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Public domain view of a WakeObservation atomic root.
 */
data class WakeObservation(
    val id: Long = 0,
    val clientUuid: String,
    val sleepRecordClientUuid: String,
    val wakeTimestamp: Long,
    val observerMembershipId: String = "",
    val note: String? = null,
    val withdrawn: Boolean = false,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val syncDirty: Boolean = true,
    val openConflictId: String? = null,
    val photoLocalPaths: List<String> = emptyList(),
)

/**
 * Domain projection for one sleep record: display interval + all visible observations.
 */
data class SleepRecordProjection(
    val sleepClientUuid: String,
    val babyId: Long,
    val recordId: Long,
    val interval: SleepIntervalProjection,
    val observations: List<WakeObservation>,
    val openConflictId: String? = null,
)

/**
 * CareLog-owned wake observation mutations and sleep interval projection.
 *
 * SleepStart end is never rewritten for wake; LocalWrite is notified after commit.
 */
internal class WakeObservationCoordinator(
    private val recordDao: RecordDao,
    private val wakeObservationDao: WakeObservationDao,
    private val mediaAssetDao: MediaAssetDao,
    private val transactionRunner: DatabaseTransactionRunner,
    private val syncPort: SyncPort,
    private val sleepMutationMutex: Mutex,
    private val recordWakeProjectionDao: RecordWakeProjectionDao,
    private val currentMembershipActorId: suspend () -> String,
    private val requestLocalSync: () -> Unit,
    private val pathGate: com.lezi.babylog.core.database.MediaLocalPathGate,
    private val sourceRoleClientUuids: suspend () -> Set<String> = { emptySet() },
) {
    suspend fun listForSleep(sleepRecordClientUuid: String): List<WakeObservation> {
        val rows = wakeObservationDao.listForSleep(sleepRecordClientUuid)
            .filter { it.deletedAt == null }
        val photosByWakeId = mediaAssetDao.listActiveForWakeObservations(rows.map { it.id })
            .groupBy { requireNotNull(it.wakeObservationId) }
        return rows.map { row ->
            row.toDomain(
                photoPaths = photosByWakeId[row.id].orEmpty()
                    .map(MediaAssetEntity::localUri)
                    .filter { it.isNotBlank() },
            )
        }
    }

    suspend fun get(clientUuid: String): WakeObservation? {
        val row = wakeObservationDao.getByClientUuid(clientUuid) ?: return null
        if (row.deletedAt != null) return null
        return row.toDomain(photoPaths = listWakePhotoPaths(row.id))
    }

    /**
     * Latest open SleepStart for the dock wake shortcut (excludes provisional/effective/
     * legacy-closed and older overlaps). Returns the full projection so observers
     * can map straight to a domain record without re-reading the root by id.
     */
    suspend fun findProjectedWakeShortcutTarget(babyId: Long): ProjectedRecordEntity? {
        val hidden = sourceRoleClientUuids()
        return visibleOpenSleeps(babyId, hidden)
            .singleOrNull { projected ->
                projected.sleepInterval?.let(::isWakeShortcutTarget) == true
            }
    }

    suspend fun findWakeShortcutTarget(babyId: Long): RecordEntity? =
        findProjectedWakeShortcutTarget(babyId)?.root

    suspend fun listTrulyOpenSleeps(babyId: Long): List<RecordEntity> {
        return visibleOpenSleeps(babyId, sourceRoleClientUuids()).map { it.root }
    }

    /** Projectively open SleepStarts, including hidden source-role rows. */
    suspend fun listProjectedOpenSleeps(babyId: Long): List<RecordEntity> =
        recordWakeProjectionDao.loadOpenSleepProjection(babyId).map { it.root }

    private suspend fun visibleOpenSleeps(
        babyId: Long,
        hidden: Set<String>,
    ): List<ProjectedRecordEntity> =
        recordWakeProjectionDao.loadOpenSleepProjection(babyId)
            .filter { it.root.clientUuid !in hidden }

    /**
     * Record a wake observation against the current wake-shortcut SleepStart (or a
     * specific open sleep). Does **not** rewrite Sleep.endTimestamp.
     *
     * @return local WakeObservation id
     */
    suspend fun recordWake(
        babyId: Long,
        at: Long = System.currentTimeMillis(),
        note: String? = null,
        photoLocalPaths: List<String> = emptyList(),
        nowMillis: Long = System.currentTimeMillis(),
        sleepRecordId: Long? = null,
        clientUuid: String = newClientUuid(),
    ): Long {
        RecordTime.pointError(at, nowMillis)?.let { throw IllegalArgumentException(it) }
        require(clientUuid.isNotBlank()) { "醒来观察标识不能为空" }
        val photos = photoLocalPaths.map(String::trim).filter(String::isNotEmpty).distinct()
        require(photos.size <= MAX_RECORD_PHOTOS) {
            "每条记录最多 $MAX_RECORD_PHOTOS 张照片"
        }
        val id = pathGate.withLocks(photos) {
            val photoDigests = digestReadablePhotoPaths(photos)
            sleepMutationMutex.withLock {
                transactionRunner.run {
                    recordWakeInCallerTransaction(
                        babyId = babyId,
                        at = at,
                        note = note,
                        photoLocalPaths = photos,
                        photoDigests = photoDigests,
                        sleepRecordId = sleepRecordId,
                        clientUuid = clientUuid,
                    )
                }
            }
        }
        requestLocalSync()
        return id
    }

    /**
     * Observer corrects own observation fields (time/note/photos). Not a tombstone.
     */
    suspend fun updateWake(
        clientUuid: String,
        wakeTimestamp: Long,
        note: String?,
        photoLocalPaths: List<String>? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        RecordTime.pointError(wakeTimestamp, nowMillis)?.let {
            throw IllegalArgumentException(it)
        }
        val photos = photoLocalPaths?.map(String::trim)?.filter(String::isNotEmpty)?.distinct()
        if (photos != null) {
            require(photos.size <= MAX_RECORD_PHOTOS) {
                "每条记录最多 $MAX_RECORD_PHOTOS 张照片"
            }
        }
        pathGate.withLocks(photos.orEmpty()) {
            val photoDigests = digestReadablePhotoPaths(photos.orEmpty())
            sleepMutationMutex.withLock {
                transactionRunner.run {
                    updateWakeInCallerTransaction(
                        clientUuid = clientUuid,
                        wakeTimestamp = wakeTimestamp,
                        note = note,
                        photoLocalPaths = photos,
                        photoDigests = photoDigests,
                    )
                }
            }
        }
        requestLocalSync()
    }

    /**
     * Observer withdraws own observation (`withdrawn=true`, root stays live).
     * If this observation is the Sleep's effective selection, clear effective so
     * projection re-enters explicit unconfirmed (provisional/open) state.
     */
    suspend fun withdrawWake(clientUuid: String) {
        sleepMutationMutex.withLock {
            transactionRunner.run {
                val existing = wakeObservationDao.getByClientUuid(clientUuid)
                    ?: throw IllegalArgumentException("醒来观察不存在")
                require(existing.deletedAt == null) { "醒来观察已删除" }
                requireObserverCanEdit(existing)
                val now = System.currentTimeMillis()
                wakeObservationDao.update(
                    existing.copy(
                        withdrawn = true,
                        updatedAt = nextSyncUpdatedAt(existing.updatedAt, now),
                        syncDirty = true,
                    ),
                )
                val sleep = recordDao.getByClientUuid(existing.sleepRecordClientUuid)
                if (
                    sleep != null &&
                    sleep.effectiveWakeObservationClientUuid == existing.clientUuid
                ) {
                    // Same local transaction: clear effective without requiring
                    // author/Owner re-select when the chosen observation is withdrawn.
                    recordDao.update(
                        sleep.copy(
                            effectiveWakeObservationClientUuid = null,
                            updatedAt = nextSyncUpdatedAt(sleep.updatedAt, now),
                            syncDirty = true,
                        ),
                    )
                }
            }
        }
        requestLocalSync()
    }

    /**
     * Sleep author or Owner selects the effective observation (or clears with null).
     * Updates Sleep Record projection pointer only; never deletes other observations.
     */
    suspend fun selectEffectiveWake(
        sleepRecordClientUuid: String,
        wakeObservationClientUuid: String?,
    ) {
        sleepMutationMutex.withLock {
            transactionRunner.run {
                val sleep = recordDao.getByClientUuid(sleepRecordClientUuid)
                    ?: throw IllegalArgumentException("睡眠记录不存在")
                require(sleep.type == RecordType.SLEEP.key && sleep.deletedAt == null) {
                    "只能为睡眠记录选择有效醒来"
                }
                requireSleepAuthorOrOwner(sleep)
                val selected = wakeObservationClientUuid?.trim()?.takeIf { it.isNotEmpty() }
                if (selected != null) {
                    val wake = wakeObservationDao.getByClientUuid(selected)
                        ?: throw IllegalArgumentException("醒来观察不存在")
                    require(wake.sleepRecordClientUuid == sleep.clientUuid) {
                        "醒来观察不属于该睡眠"
                    }
                    require(wake.deletedAt == null && !wake.withdrawn) {
                        "不能选择已撤回的醒来观察"
                    }
                    validateWakeTimestamp(sleep.timestamp, wake.wakeTimestamp)?.let {
                        throw IllegalArgumentException(it)
                    }
                }
                val now = System.currentTimeMillis()
                recordDao.update(
                    sleep.copy(
                        effectiveWakeObservationClientUuid = selected,
                        updatedAt = nextSyncUpdatedAt(sleep.updatedAt, now),
                        syncDirty = true,
                    ),
                )
            }
        }
        requestLocalSync()
    }

    suspend fun canEditWake(clientUuid: String): Boolean {
        val existing = wakeObservationDao.getByClientUuid(clientUuid) ?: return false
        if (existing.deletedAt != null) return false
        return try {
            requireObserverCanEdit(existing)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            false
        }
    }

    suspend fun canSelectEffectiveWake(sleepRecordClientUuid: String): Boolean {
        val sleep = recordDao.getByClientUuid(sleepRecordClientUuid) ?: return false
        return try {
            requireSleepAuthorOrOwner(sleep)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Wake insert inside the caller's [sleepMutationMutex] and transaction.
     * Does not take the path gate or request sync. Permission rules match [recordWake].
     */
    internal suspend fun recordWakeInCallerTransaction(
        babyId: Long,
        at: Long,
        note: String?,
        photoLocalPaths: List<String>,
        photoDigests: Map<String, String>,
        sleepRecordId: Long?,
        clientUuid: String,
    ): Long {
        require(clientUuid.isNotBlank()) { "醒来观察标识不能为空" }
        val replay = wakeObservationDao.getByClientUuid(clientUuid)
        if (replay != null) {
            check(replay.deletedAt == null) { "这次醒来观察已删除，请重新填写" }
            return replay.id
        }
        val target = if (sleepRecordId != null) {
            val entity = recordDao.get(sleepRecordId)
                ?: throw IllegalArgumentException("睡眠记录不存在")
            require(entity.babyId == babyId && entity.type == RecordType.SLEEP.key) {
                "醒来必须关联本宝宝的睡眠记录"
            }
            require(entity.deletedAt == null) { "睡眠记录已删除" }
            entity
        } else {
            findWakeShortcutTarget(babyId)
                ?: throw IllegalStateException("当前没有进行中的睡眠")
        }
        validateWakeTimestamp(target.timestamp, at)?.let {
            throw IllegalArgumentException(it)
        }
        val membershipId = currentMembershipActorId().trim()
        val now = System.currentTimeMillis()
        val wakeId = wakeObservationDao.upsert(
            WakeObservationEntity(
                clientUuid = clientUuid,
                sleepRecordClientUuid = target.clientUuid,
                wakeTimestamp = at,
                observerMembershipId = membershipId,
                note = note,
                withdrawn = false,
                updatedAt = now,
                syncDirty = true,
            ),
        )
        // Room autoGenerate may return rowid; re-read for stable local id.
        val stored = wakeObservationDao.getByClientUuid(clientUuid)
            ?: error("WakeObservation missing after upsert")
        val localId = if (stored.id > 0L) stored.id else wakeId
        reconcileWakePhotos(localId, photoLocalPaths, now, photoDigests)
        return localId
    }

    /**
     * Wake field update inside the caller's [sleepMutationMutex] and transaction.
     * Does not take the path gate or request sync. Permission rules match [updateWake].
     */
    internal suspend fun updateWakeInCallerTransaction(
        clientUuid: String,
        wakeTimestamp: Long,
        note: String?,
        photoLocalPaths: List<String>?,
        photoDigests: Map<String, String>,
    ) {
        val existing = wakeObservationDao.getByClientUuid(clientUuid)
            ?: throw IllegalArgumentException("醒来观察不存在")
        require(existing.deletedAt == null) { "醒来观察已删除" }
        requireObserverCanEdit(existing)
        val sleep = recordDao.getByClientUuid(existing.sleepRecordClientUuid)
            ?: throw IllegalArgumentException("关联睡眠不存在")
        validateWakeTimestamp(sleep.timestamp, wakeTimestamp)?.let {
            throw IllegalArgumentException(it)
        }
        val now = System.currentTimeMillis()
        wakeObservationDao.update(
            existing.copy(
                wakeTimestamp = wakeTimestamp,
                note = note,
                withdrawn = false,
                updatedAt = nextSyncUpdatedAt(existing.updatedAt, now),
                syncDirty = true,
            ),
        )
        if (photoLocalPaths != null) {
            reconcileWakePhotos(existing.id, photoLocalPaths, now, photoDigests)
        }
    }

    /** All rows for one sleep, including withdrawn and tombstoned. */
    internal suspend fun listWakeRowsForSleep(
        sleepRecordClientUuid: String,
    ): List<WakeObservationEntity> = wakeObservationDao.listForSleep(sleepRecordClientUuid)

    /**
     * Same gate as [requireObserverCanEdit]. Denial is false; cancellation still propagates.
     */
    internal suspend fun actorMayEditWake(existing: WakeObservationEntity): Boolean {
        try {
            requireObserverCanEdit(existing)
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RecordPermissionException) {
            return false
        }
    }

    private suspend fun requireObserverCanEdit(existing: WakeObservationEntity) {
        val session = syncPort.session().first()
        val actor = session.membershipId.trim()
        val isOwner = session.role == FamilyRole.Owner
        val isObserver = existing.observerMembershipId.isNotBlank() &&
            existing.observerMembershipId == actor
        // Offline / pre-join: empty observer stamp may edit own local dirty wake.
        val localUnstamped = existing.observerMembershipId.isBlank() &&
            actor.isEmpty() &&
            existing.syncDirty
        if (!isObserver && !isOwner && !localUnstamped) {
            throw RecordPermissionException()
        }
        // Owner may not edit another member's observation content — only select effective.
        if (isOwner && !isObserver && existing.observerMembershipId.isNotBlank()) {
            throw RecordPermissionException()
        }
    }

    private suspend fun requireSleepAuthorOrOwner(sleep: RecordEntity) {
        val session = syncPort.session().first()
        val actor = session.membershipId.trim()
        val isOwner = session.role == FamilyRole.Owner
        val isAuthor = sleep.createdByMembershipId.isNotBlank() &&
            sleep.createdByMembershipId == actor
        val localAuthor = sleep.createdByMembershipId.isBlank() && actor.isEmpty()
        if (!isAuthor && !isOwner && !localAuthor) {
            throw RecordPermissionException()
        }
    }

    private suspend fun listWakePhotoPaths(wakeObservationId: Long): List<String> =
        if (wakeObservationId <= 0L) {
            emptyList()
        } else {
            mediaAssetDao.listActiveForWakeObservation(wakeObservationId)
                .map(MediaAssetEntity::localUri)
                .filter { it.isNotBlank() }
        }

    private fun digestReadablePhotoPaths(paths: Collection<String>): Map<String, String> {
        val digests = linkedMapOf<String, String>()
        paths.forEach { path ->
            if (path in digests) return@forEach
            MediaContentDigest.ofReadableFile(path)?.let { digests[path] = it }
        }
        return digests
    }

    private suspend fun reconcileWakePhotos(
        wakeObservationId: Long,
        photoLocalPaths: List<String>,
        at: Long,
        contentDigests: Map<String, String>,
    ) {
        if (wakeObservationId <= 0L) return
        val existing = mediaAssetDao.listActiveForWakeObservation(wakeObservationId)
            .filter { it.kind == "wake" }
        val desired = photoLocalPaths.toSet()
        photoLocalPaths.forEach { path ->
            if (existing.any { it.localUri == path }) return@forEach
            mediaAssetDao.upsert(
                MediaAssetEntity(
                    wakeObservationId = wakeObservationId,
                    clientUuid = newClientUuid(),
                    kind = "wake",
                    localUri = path,
                    createdAt = at,
                    updatedAt = at,
                    syncDirty = true,
                    sha256 = contentDigests[path],
                ),
            )
        }
        existing.filter { it.localUri !in desired }.forEach { asset ->
            mediaAssetDao.update(
                asset.copy(
                    deletedAt = at,
                    updatedAt = nextSyncUpdatedAt(asset.updatedAt, at),
                    syncDirty = true,
                ),
            )
        }
    }
}

internal fun WakeObservationEntity.toDomain(photoPaths: List<String> = emptyList()): WakeObservation =
    WakeObservation(
        id = id,
        clientUuid = clientUuid,
        sleepRecordClientUuid = sleepRecordClientUuid,
        wakeTimestamp = wakeTimestamp,
        observerMembershipId = observerMembershipId,
        note = note,
        withdrawn = withdrawn,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        syncDirty = syncDirty,
        openConflictId = openConflictId,
        photoLocalPaths = photoPaths,
    )

internal fun ProjectedRecordEntity.toSleepRecordProjection(): SleepRecordProjection? {
    val interval = sleepInterval ?: return null
    val mediaByWake = wakeMedia.groupBy { requireNotNull(it.wakeObservationId) }
    return SleepRecordProjection(
        sleepClientUuid = root.clientUuid,
        babyId = root.babyId,
        recordId = root.id,
        interval = interval,
        observations = wakeObservations
            .filter { it.deletedAt == null }
            .map { wake ->
                wake.toDomain(
                    photoPaths = mediaByWake[wake.id].orEmpty()
                        .map(MediaAssetEntity::localUri)
                        .filter(String::isNotBlank),
                )
            },
        openConflictId = root.openConflictId,
    )
}

/**
 * Map a Sleep [RecordEntity] to domain [com.lezi.babylog.core.model.Record] with
 * projected display end filled into [com.lezi.babylog.core.model.Record.endTimestamp].
 */
internal fun RecordEntity.toProjectedSleepRecord(
    interval: SleepIntervalProjection,
): com.lezi.babylog.core.model.Record {
    val base = toModel()
    if (base.type != RecordType.SLEEP) return base
    return base.copy(
        endTimestamp = interval.endTimestamp,
    )
}
