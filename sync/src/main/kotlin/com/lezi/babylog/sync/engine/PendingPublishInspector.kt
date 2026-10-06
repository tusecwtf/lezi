package com.lezi.babylog.sync.engine

import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.CarePlanDao
import com.lezi.babylog.core.database.CarePlanEntity
import com.lezi.babylog.core.database.CustomItemDao
import com.lezi.babylog.core.database.FulfillmentCandidateDao
import com.lezi.babylog.core.database.MediaAssetDao
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.database.causal.ConflictSnapshotCacheDao
import com.lezi.babylog.core.database.causal.WakeObservationDao
import com.lezi.babylog.core.database.causal.WakeObservationEntity

/**
 * Publish preflight and referenced-media capture for one replica cycle.
 *
 * Receipt/dirty semantics stay identical to the former
 * [ReplicaSyncEngine] helpers; this type only batches holder and media reads.
 */
internal class PendingPublishInspector(
    private val babyDao: BabyDao,
    private val recordDao: RecordDao,
    private val carePlanDao: CarePlanDao,
    private val customItemDao: CustomItemDao,
    private val wakeObservationDao: WakeObservationDao,
    private val fulfillmentCandidateDao: FulfillmentCandidateDao,
    private val mediaDao: MediaAssetDao,
    private val conflictSnapshotCacheDao: ConflictSnapshotCacheDao?,
) {
    private var cachedHasPendingPublishUnits: Boolean? = null

    /** Drops the round memo. Call after a publish mutation or a census rewalk. */
    fun invalidatePendingPublishCache() {
        cachedHasPendingPublishUnits = null
    }

    suspend fun hasPendingPublishUnits(): Boolean {
        cachedHasPendingPublishUnits?.let { return it }
        return computeHasPendingPublishUnits().also { cachedHasPendingPublishUnits = it }
    }

    private suspend fun computeHasPendingPublishUnits(): Boolean {
        val activeReceipts = conflictSnapshotCacheDao
            ?.listTerminalReceipts()
            ?.associateBy { it.entityType to it.clientUuid }
            .orEmpty()
        fun isReceiptActive(entityType: String, clientUuid: String, updatedAt: Long): Boolean {
            val receipt = activeReceipts[entityType to clientUuid] ?: return false
            return receipt.contentEpoch == updatedAt
        }

        if (babyDao.listPendingSync().any { !isReceiptActive("baby", it.clientUuid, it.updatedAt) }) {
            return true
        }
        if (recordDao.listPendingSync().any { !isReceiptActive("record", it.clientUuid, it.updatedAt) }) {
            return true
        }
        if (carePlanDao.listPendingSync().any {
                !isReceiptActive("care_plan", it.clientUuid, it.updatedAt)
            }
        ) {
            return true
        }
        if (customItemDao.listPendingSync().any {
                !isReceiptActive("custom_item", it.clientUuid, it.updatedAt)
            }
        ) {
            return true
        }
        if (wakeObservationDao.listPendingSync().any {
                !isReceiptActive("wake_observation", it.clientUuid, it.updatedAt)
            }
        ) {
            return true
        }
        if (fulfillmentCandidateDao.listPendingSync().any {
                !isReceiptActive("fulfillment_candidate", it.clientUuid, it.updatedAt)
            }
        ) {
            return true
        }
        val pendingMedia = mediaDao.listPendingSync()
        if (pendingMedia.isEmpty()) return false
        val holders = loadMediaHolders(pendingMedia)
        val unacceptedMedia = pendingMedia.filterNot { media ->
            val record = media.recordId?.let(holders.records::get)
            if (record != null && isReceiptActive("record", record.clientUuid, record.updatedAt)) {
                return@filterNot true
            }
            val plan = media.carePlanId?.let(holders.plans::get)
            if (plan != null && isReceiptActive("care_plan", plan.clientUuid, plan.updatedAt)) {
                return@filterNot true
            }
            val baby = media.babyId?.let(holders.babies::get)
            if (baby != null && isReceiptActive("baby", baby.clientUuid, baby.updatedAt)) {
                return@filterNot true
            }
            val wake = media.wakeObservationId?.let(holders.wakes::get)
            if (wake != null && isReceiptActive("wake_observation", wake.clientUuid, wake.updatedAt)) {
                return@filterNot true
            }
            false
        }
        return unacceptedMedia.any { media ->
            val record = media.recordId?.let(holders.records::get)
            val plan = media.carePlanId?.let(holders.plans::get)
            val baby = media.babyId?.let(holders.babies::get)
            val wake = media.wakeObservationId?.let(holders.wakes::get)
            (record?.syncDirty == true) ||
                (plan?.syncDirty == true) ||
                (baby?.syncDirty == true) ||
                (wake?.syncDirty == true)
        }
    }

    suspend fun captureMedia(
        babies: List<BabyEntity>,
        records: List<RecordEntity>,
        carePlans: List<CarePlanEntity>,
        wakeObservations: List<WakeObservationEntity>,
    ): Pair<List<MediaAssetEntity>, List<MediaAssetEntity>> {
        val directlyChangedMedia = mediaDao.listPendingSync()
        val liveBabyIds = babies.mapNotNull { baby ->
            baby.id.takeIf { baby.deletedAt == null }
        }
        val avatarsByBaby = mediaDao.listActiveAvatarsForBabies(liveBabyIds)
            .groupBy { it.babyId }
            .mapNotNull { (babyId, rows) ->
                babyId?.let { id -> id to rows.first() }
            }
            .toMap()
        val recordMedia = mediaDao.listForRecords(records.map(RecordEntity::id))
            .filter { media -> media.deletedAt == null || media.syncDirty }
        val planMedia = mediaDao.listForCarePlans(carePlans.map(CarePlanEntity::id))
            .filter { media -> media.deletedAt == null || media.syncDirty }
        val wakeMedia = mediaDao.listActiveForWakeObservations(
            wakeObservations.map(WakeObservationEntity::id),
        ).filter { media -> media.deletedAt == null || media.syncDirty }
        val referencedMedia = buildList {
            liveBabyIds.forEach { babyId ->
                avatarsByBaby[babyId]?.let(::add)
            }
            addAll(recordMedia)
            addAll(planMedia)
            addAll(wakeMedia)
        }
        return directlyChangedMedia to referencedMedia
    }

    suspend fun deletedBabiesHaveLiveAvatars(babies: List<BabyEntity>): Boolean {
        val deletedIds = babies.mapNotNull { baby ->
            baby.id.takeIf { baby.deletedAt != null }
        }
        return mediaDao.listActiveAvatarsForBabies(deletedIds).isNotEmpty()
    }

    private suspend fun loadMediaHolders(pendingMedia: List<MediaAssetEntity>): MediaHolders {
        val records = recordDao.getIncludingDeleted(
            pendingMedia.mapNotNull(MediaAssetEntity::recordId).distinct(),
        ).associateBy(RecordEntity::id)
        val plans = carePlanDao.get(
            pendingMedia.mapNotNull(MediaAssetEntity::carePlanId).distinct(),
        ).associateBy(CarePlanEntity::id)
        val babies = babyDao.getIncludingDeleted(
            pendingMedia.mapNotNull(MediaAssetEntity::babyId).distinct(),
        ).associateBy(BabyEntity::id)
        val wakes = wakeObservationDao.get(
            pendingMedia.mapNotNull(MediaAssetEntity::wakeObservationId).distinct(),
        ).associateBy(WakeObservationEntity::id)
        return MediaHolders(records, plans, babies, wakes)
    }

    private data class MediaHolders(
        val records: Map<Long, RecordEntity>,
        val plans: Map<Long, CarePlanEntity>,
        val babies: Map<Long, BabyEntity>,
        val wakes: Map<Long, WakeObservationEntity>,
    )
}
