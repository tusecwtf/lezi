package com.lezi.babylog.sync.disasterrecovery

import com.lezi.babylog.core.database.*
import com.lezi.babylog.core.database.causal.WakeObservationEntity
import java.security.MessageDigest

/** One Room read transaction. Evidence includes attachment-only edits and tombstones. */
internal data class CapturedRestoreRows(
    val babies: List<BabyEntity>,
    val records: List<RecordEntity>,
    val plans: List<CarePlanEntity>,
    val customItems: List<CustomItemEntity>,
    val candidates: List<FulfillmentCandidateEntity>,
    val wakes: List<WakeObservationEntity>,
    val media: List<MediaAssetEntity>,
) {
    private val rowsByType: Map<String, Map<String, Any?>> = mapOf(
        "baby" to babies.uniqueRowsByUuid { it.clientUuid },
        "record" to records.uniqueRowsByUuid { it.clientUuid },
        "care_plan" to plans.uniqueRowsByUuid { it.clientUuid },
        "custom_item" to customItems.uniqueRowsByUuid { it.clientUuid },
        "fulfillment_candidate" to candidates.uniqueRowsByUuid { it.clientUuid },
        "wake_observation" to wakes.uniqueRowsByUuid { it.clientUuid },
        "media" to media.uniqueRowsByUuid { it.clientUuid },
    )
    private val attachmentsByOwner = CapturedRestoreAttachments(media)

    fun mediaRow(uuid: String): MediaAssetEntity? = rowsByType["media"]?.get(uuid) as? MediaAssetEntity

    /** Typed v2 equality evidence for new immutable file captures. */
    fun exactEvidence(type: String, uuid: String): String? {
        val row = rowsByType[type]?.get(uuid) ?: return null
        return exactRestoreEvidence(type, row, attachmentsByOwner.forRow(row))
    }

    /** Historical v1 encoding. Keep these bytes unchanged for existing snapshots. */
    fun evidence(type: String, uuid: String): String? {
        val row = rowsByType[type]?.get(uuid) ?: return null
        val attachments = attachmentsByOwner.forRow(row)
        // Only equality evidence, never a server content hash or authority claim. A transport-only
        // difference may conservatively requeue; it must never mark newer facts clean.
        val values = listOf(row.toString()) + attachments.map { it.toString() }
        val bytes = values.joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it" }
            .toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** A duplicate stays ambiguous even when a third row has the same UUID. */
private inline fun <T> List<T>.uniqueRowsByUuid(clientUuid: (T) -> String): Map<String, T?> = buildMap {
    for (row in this@uniqueRowsByUuid) {
        val uuid = clientUuid(row)
        put(uuid, if (containsKey(uuid)) null else row)
    }
}

/** Immutable, capture-local owner buckets include tombstones and retain equal-UUID input order. */
private class CapturedRestoreAttachments(media: List<MediaAssetEntity>) {
    private val byBaby: Map<Long, List<MediaAssetEntity>>
    private val byRecord: Map<Long, List<MediaAssetEntity>>
    private val byPlan: Map<Long, List<MediaAssetEntity>>
    private val byWake: Map<Long, List<MediaAssetEntity>>

    init {
        val babies = mutableMapOf<Long, MutableList<MediaAssetEntity>>()
        val records = mutableMapOf<Long, MutableList<MediaAssetEntity>>()
        val plans = mutableMapOf<Long, MutableList<MediaAssetEntity>>()
        val wakes = mutableMapOf<Long, MutableList<MediaAssetEntity>>()
        media.forEach { asset ->
            asset.babyId?.let { babies.getOrPut(it) { mutableListOf() }.add(asset) }
            asset.recordId?.let { records.getOrPut(it) { mutableListOf() }.add(asset) }
            asset.carePlanId?.let { plans.getOrPut(it) { mutableListOf() }.add(asset) }
            asset.wakeObservationId?.let { wakes.getOrPut(it) { mutableListOf() }.add(asset) }
        }
        byBaby = babies.sortedByUuid()
        byRecord = records.sortedByUuid()
        byPlan = plans.sortedByUuid()
        byWake = wakes.sortedByUuid()
    }

    fun forRow(row: Any): List<MediaAssetEntity> = when (row) {
        is BabyEntity -> byBaby[row.id]
        is RecordEntity -> byRecord[row.id]
        is CarePlanEntity -> byPlan[row.id]
        is WakeObservationEntity -> byWake[row.id]
        else -> null
    }.orEmpty()

    private fun Map<Long, List<MediaAssetEntity>>.sortedByUuid(): Map<Long, List<MediaAssetEntity>> =
        mapValues { (_, assets) -> assets.sortedBy { it.clientUuid } }
}
