package com.lezi.babylog.sync

import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.CarePlanDao
import com.lezi.babylog.core.database.CustomItemDao
import com.lezi.babylog.core.database.FulfillmentCandidateDao
import com.lezi.babylog.core.database.MediaAssetDao
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.OutboxDao
import com.lezi.babylog.core.database.OutboxEntity
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.model.CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION
import com.lezi.babylog.core.model.RecordType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Classifies outbox rows into ordinary vs atomic publish paths and executes them.
 *
 * Product contract:
 * - Baby / CustomItem / avatar media / fulfillment_candidate → ordinary push (+ media PUT)
 * - Every Record (0–3 photos) and every CarePlan → atomic bundle endpoints
 */
internal class OutboxPushPipeline(
    private val backend: SyncBackend,
    private val outboxDao: OutboxDao,
    private val recordDao: RecordDao,
    private val carePlanDao: CarePlanDao,
    private val babyDao: BabyDao,
    private val mediaDao: MediaAssetDao,
    private val customItemDao: CustomItemDao,
    private val fulfillmentCandidateDao: FulfillmentCandidateDao,
    private val mediaFiles: SyncMediaFileStore,
    private val requireRemoteAllowed: suspend (SyncSession) -> Unit,
) {
    suspend fun pushPending(session: SyncSession) {
        while (pushPendingBatch(session)) {
            // Each acknowledged batch is deleted before the next peek, so rows
            // beyond the bounded request size cannot be starved by re-snapshotting.
        }
    }

    private suspend fun pushPendingBatch(session: SyncSession): Boolean {
        val roots = outboxDao.peek(session.familyId, PUSH_ROOT_BATCH_SIZE)
        if (roots.isEmpty()) return false
        val queued = expandBatchWithDependencies(session, roots)
        val staleAfterHardDelete = mutableListOf<OutboxEntity>()
        queued.forEach { row ->
            if (isStaleAfterHardDelete(row)) staleAfterHardDelete += row
        }
        if (staleAfterHardDelete.isNotEmpty()) {
            // A committed local clear can fail before its outbox cleanup. Never
            // resurrect a hard-deleted entity on a later sync; deleting these
            // rows is safe because ordinary soft deletes retain their DB row.
            outboxDao.deleteIds(staleAfterHardDelete.map(OutboxEntity::id))
        }
        val unauthorizedBabyRows = if (session.role == FamilyRole.Member) {
            buildList {
                for (row in queued - staleAfterHardDelete.toSet()) {
                    if (!memberMayPublish(row)) add(row)
                }
            }
        } else {
            emptyList()
        }
        if (unauthorizedBabyRows.isNotEmpty()) {
            outboxDao.deleteIds(unauthorizedBabyRows.map { it.id })
        }
        val pending = queued - staleAfterHardDelete.toSet() - unauthorizedBabyRows.toSet()
        if (pending.isEmpty()) return true

        val plan = OutboxPushPlanner.classify(
            pending = pending,
            recordDao = recordDao,
            carePlanDao = carePlanDao,
            mediaDao = mediaDao,
        )

        if (plan.recordRows.isNotEmpty() || plan.carePlanRows.isNotEmpty()) {
            requireRemoteAllowed(session)
            // A fresh family has no referenced Baby/CustomItem rows yet. Publish
            // those roots (and cyclic Baby/avatar metadata) before asking the NAS
            // to validate an atomic Record/CarePlan bundle against them.
            val prerequisites = plan.residual.filter { it.isAtomicBundlePrerequisite() }
            pushResidualBatch(session, prerequisites)
            val residual = plan.residual - prerequisites.toSet()
            // Prefer fulfill Record before completed care_plan so pull pages that
            // end mid-set still apply the fact first. Receivers apply records then
            // co-gate completed plans until the linked record is present (same-txn).
            // Every Record (including 0-photo) is an atomic package root.
            for (recordRow in plan.recordRows) {
                pushRecordAtomicBundle(session, recordRow, pending)
            }
            for (planRow in plan.carePlanRows) {
                pushCarePlanAtomicBundle(session, planRow, pending)
            }
            if (residual.isEmpty()) return true
            pushResidualBatch(session, residual)
            return true
        }

        if (plan.residual.isEmpty()) return true
        pushResidualBatch(session, plan.residual)
        return true
    }

    /**
     * Pre-join orphan facts stay local until their Baby is rebound to an authority profile.
     * Deleting a stale outbox row is safe: the dirty entity remains in Room and capture will
     * enqueue its newer rebound version after an automatic or explicit merge.
     */
    private suspend fun memberMayPublish(row: OutboxEntity): Boolean = when (row.entityType) {
        "baby" -> false
        "record" -> recordDao.getByClientUuid(row.clientUuid)
            ?.let { record -> isFamilyAuthorityBaby(record.babyId) }
            ?: false
        "care_plan" -> carePlanDao.getByClientUuid(row.clientUuid)
            ?.let { plan -> isFamilyAuthorityBaby(plan.babyId) }
            ?: false
        "media" -> mediaDao.getByClientUuid(row.clientUuid)?.let { asset ->
            val recordId = asset.recordId
            val carePlanId = asset.carePlanId
            when {
                asset.kind == "avatar" -> false
                recordId != null -> recordDao.getIncludingDeleted(recordId)
                    ?.let { record -> isFamilyAuthorityBaby(record.babyId) }
                    ?: false
                carePlanId != null -> carePlanDao.get(carePlanId)
                    ?.let { plan -> isFamilyAuthorityBaby(plan.babyId) }
                    ?: false
                else -> false
            }
        } ?: false
        "fulfillment_candidate" -> fulfillmentCandidateDao.getByClientUuid(row.clientUuid)
            ?.let { candidate -> carePlanDao.getByClientUuid(candidate.carePlanClientUuid) }
            ?.let { plan -> isFamilyAuthorityBaby(plan.babyId) }
            ?: false
        else -> true
    }

    private suspend fun isFamilyAuthorityBaby(babyId: Long): Boolean =
        babyDao.get(babyId)?.familyAuthority == true

    private suspend fun pushResidualBatch(
        session: SyncSession,
        residual: List<OutboxEntity>,
    ) {
        if (residual.isEmpty()) return
        val uploads = mutableListOf<MediaAssetEntity>()
        val entities = residual
            .map { row ->
                var payload = row.payloadJson
                if (row.entityType == "media" && row.deletedAt == null) {
                    val media = mediaDao.getByClientUuid(row.clientUuid)
                        ?: error("本地媒体元数据不存在")
                    if (!media.hasReceiptFor(session)) {
                        val prepared = mediaFiles.prepareUpload(media.localUri)
                        val updated = media.copy(
                            mime = prepared.mime,
                            width = prepared.width ?: media.width,
                            height = prepared.height ?: media.height,
                            byteSize = prepared.bytes.size.toLong(),
                        )
                        mediaDao.update(updated)
                        uploads += updated
                        val rawObject = Json.parseToJsonElement(payload).jsonObject
                        payload = JsonObject(
                            rawObject +
                                ("mime" to JsonPrimitive(updated.mime)) +
                                ("byte_size" to JsonPrimitive(updated.byteSize)) +
                                listOfNotNull(
                                    updated.width?.let { "width" to JsonPrimitive(it) },
                                    updated.height?.let { "height" to JsonPrimitive(it) },
                                ).toMap(),
                        ).toString()
                    }
                }
                SyncEntity(
                    row.entityType,
                    row.clientUuid,
                    payload,
                    row.updatedAt,
                    row.deletedAt,
                )
            }
            .sortedBy { ENTITY_ORDER.indexOf(it.type).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
        requireRemoteAllowed(session)
        // Ordinary residual never carries Record roots (those are atomic-only).
        backend.push(session, entities)
        uploads.forEach { media ->
            requireRemoteAllowed(session)
            // Metadata needs the compressed byte size, so preparation happens
            // once before the metadata push and again here. Re-preparing one
            // file at a time bounds resident JPEG bytes to a single upload.
            val prepared = mediaFiles.prepareUpload(media.localUri)
            backend.putMedia(session, media.clientUuid, prepared.bytes, prepared.mime)
            mediaDao.update(media.copy(remoteUri = session.receiptFor(media.clientUuid)))
        }
        residual.forEach { row ->
            when (row.entityType) {
                "baby" -> babyDao.markSynced(row.clientUuid, row.updatedAt)
                "media" -> mediaDao.markSynced(row.clientUuid, row.updatedAt)
                "custom_item" -> customItemDao.markSynced(row.clientUuid, row.updatedAt)
                "fulfillment_candidate" ->
                    fulfillmentCandidateDao.markSynced(row.clientUuid, row.updatedAt)
                "record", "care_plan" ->
                    error("record/care_plan must not remain on the ordinary residual path")
            }
        }
        outboxDao.deleteIds(residual.map { it.id })
    }

    private fun OutboxEntity.isAtomicBundlePrerequisite(): Boolean = when (entityType) {
        "baby", "custom_item" -> true
        "media" -> runCatching {
            Json.parseToJsonElement(payloadJson).jsonObject.string("kind") == "avatar"
        }.getOrDefault(false)
        else -> false
    }

    /**
     * Push one record + its 0–3 log photos as an atomic NAS package.
     * [bundleId] is a deterministic UUID derived from root type + record UUID + updatedAt.
     */
    private suspend fun pushRecordAtomicBundle(
        session: SyncSession,
        recordRow: OutboxEntity,
        pending: List<OutboxEntity>,
    ) {
        val record = recordDao.getByClientUuid(recordRow.clientUuid)
            ?: error("本地记录不存在")
        val baby = babyDao.get(record.babyId)
            ?: error("本地宝宝档案不存在")
        val mediaRows = pending.filter { row ->
            if (row.entityType != "media") return@filter false
            val media = mediaDao.getByClientUuid(row.clientUuid) ?: return@filter false
            media.kind == "log" && media.recordId == record.id
        }
        val mediaEntities = mutableListOf<SyncEntity>()
        val mediaBytes = mutableListOf<Pair<MediaAssetEntity, PreparedMedia>>()
        for (row in mediaRows) {
            var payload = row.payloadJson
            val media = mediaDao.getByClientUuid(row.clientUuid)
                ?: error("本地媒体元数据不存在")
            if (row.deletedAt == null && media.localUri.isNotBlank()) {
                val prepared = mediaFiles.prepareUpload(media.localUri)
                val updated = media.copy(
                    mime = prepared.mime,
                    width = prepared.width ?: media.width,
                    height = prepared.height ?: media.height,
                    byteSize = prepared.bytes.size.toLong(),
                )
                mediaDao.update(updated)
                mediaBytes += updated to prepared
                val rawObject = Json.parseToJsonElement(payload).jsonObject
                payload = JsonObject(
                    rawObject +
                        ("mime" to JsonPrimitive(updated.mime)) +
                        ("byte_size" to JsonPrimitive(updated.byteSize)) +
                        listOfNotNull(
                            updated.width?.let { "width" to JsonPrimitive(it) },
                            updated.height?.let { "height" to JsonPrimitive(it) },
                        ).toMap(),
                ).toString()
            }
            mediaEntities += SyncEntity(
                type = "media",
                clientUuid = row.clientUuid,
                payloadJson = payload,
                updatedAt = row.updatedAt,
                deletedAt = row.deletedAt,
            )
        }
        requireRemoteAllowed(session)
        val customItemUuid = recordCustomItemClientUuid(record)
        val mapped = SyncWireMapper.record(
            entity = record,
            babyClientUuid = baby.clientUuid,
            customItemClientUuid = customItemUuid,
        )
        val root = mapped.copy(
            updatedAt = recordRow.updatedAt,
            deletedAt = recordRow.deletedAt,
        )
        val bundleId = AtomicBundleId.forRecord(record.clientUuid, recordRow.updatedAt)
        val stage = backend.stageBundle(
            session,
            AtomicBundleDraft(
                bundleId = bundleId,
                root = root,
                media = mediaEntities,
            ),
        )
        val mediaToUpload = stage.mediaUuidsToUpload(
            mediaBytes.map { it.first.clientUuid }.toSet(),
        )
        for ((media, prepared) in mediaBytes) {
            if (media.clientUuid in mediaToUpload) {
                requireRemoteAllowed(session)
                backend.putBundleMedia(
                    session,
                    bundleId,
                    media.clientUuid,
                    prepared.bytes,
                    prepared.mime,
                )
            }
            mediaDao.update(media.copy(remoteUri = session.receiptFor(media.clientUuid)))
        }
        requireRemoteAllowed(session)
        val commit = backend.commitBundle(session, bundleId)
        mergeCanonicalRecordAuthors(
            authors = commit.recordAuthors,
            expectedUpdatedAt = mapOf(record.clientUuid to recordRow.updatedAt),
        )
        recordDao.markSynced(record.clientUuid, recordRow.updatedAt)
        mediaRows.forEach { mediaDao.markSynced(it.clientUuid, it.updatedAt) }
        outboxDao.deleteIds((listOf(recordRow) + mediaRows).map { it.id })
    }

    /**
     * Push one care plan + its 0–3 plan photos as an atomic NAS package.
     * [bundleId] is a deterministic UUID derived from root type + plan UUID + updatedAt.
     */
    private suspend fun pushCarePlanAtomicBundle(
        session: SyncSession,
        planRow: OutboxEntity,
        pending: List<OutboxEntity>,
    ) {
        val plan = carePlanDao.getByClientUuid(planRow.clientUuid)
            ?: error("本地护理计划不存在")
        val baby = babyDao.get(plan.babyId)
            ?: error("本地宝宝档案不存在")
        val mediaRows = pending.filter { row ->
            if (row.entityType != "media") return@filter false
            val media = mediaDao.getByClientUuid(row.clientUuid) ?: return@filter false
            media.kind == "log" && media.carePlanId == plan.id
        }
        val mediaEntities = mutableListOf<SyncEntity>()
        val mediaBytes = mutableListOf<Pair<MediaAssetEntity, PreparedMedia>>()
        for (row in mediaRows) {
            var payload = row.payloadJson
            val media = mediaDao.getByClientUuid(row.clientUuid)
                ?: error("本地媒体元数据不存在")
            if (row.deletedAt == null && media.localUri.isNotBlank()) {
                val prepared = mediaFiles.prepareUpload(media.localUri)
                val updated = media.copy(
                    mime = prepared.mime,
                    width = prepared.width ?: media.width,
                    height = prepared.height ?: media.height,
                    byteSize = prepared.bytes.size.toLong(),
                )
                mediaDao.update(updated)
                mediaBytes += updated to prepared
                val rawObject = Json.parseToJsonElement(payload).jsonObject
                payload = JsonObject(
                    rawObject +
                        ("mime" to JsonPrimitive(updated.mime)) +
                        ("byte_size" to JsonPrimitive(updated.byteSize)) +
                        listOfNotNull(
                            updated.width?.let { "width" to JsonPrimitive(it) },
                            updated.height?.let { "height" to JsonPrimitive(it) },
                        ).toMap(),
                ).toString()
            }
            mediaEntities += SyncEntity(
                type = "media",
                clientUuid = row.clientUuid,
                payloadJson = payload,
                updatedAt = row.updatedAt,
                deletedAt = row.deletedAt,
            )
        }
        val customItemUuid = plan.customItemId
            ?.let { customItemDao.getById(it)?.clientUuid }
        val mapped = SyncWireMapper.carePlan(
            entity = plan,
            babyClientUuid = baby.clientUuid,
            customItemClientUuid = customItemUuid,
        )
        val root = mapped.copy(
            updatedAt = planRow.updatedAt,
            deletedAt = planRow.deletedAt,
        )
        val bundleId = AtomicBundleId.forCarePlan(plan.clientUuid, planRow.updatedAt)
        requireRemoteAllowed(session)
        val stage = backend.stageBundle(
            session,
            AtomicBundleDraft(
                bundleId = bundleId,
                root = root,
                media = mediaEntities,
            ),
        )
        val mediaToUpload = stage.mediaUuidsToUpload(
            mediaBytes.map { it.first.clientUuid }.toSet(),
        )
        for ((media, prepared) in mediaBytes) {
            if (media.clientUuid in mediaToUpload) {
                requireRemoteAllowed(session)
                backend.putBundleMedia(
                    session,
                    bundleId,
                    media.clientUuid,
                    prepared.bytes,
                    prepared.mime,
                )
            }
            mediaDao.update(media.copy(remoteUri = session.receiptFor(media.clientUuid)))
        }
        requireRemoteAllowed(session)
        backend.commitBundle(session, bundleId)
        carePlanDao.markSynced(plan.clientUuid, planRow.updatedAt)
        mediaRows.forEach { mediaDao.markSynced(it.clientUuid, it.updatedAt) }
        outboxDao.deleteIds((listOf(planRow) + mediaRows).map { it.id })
    }

    private suspend fun isStaleAfterHardDelete(row: OutboxEntity): Boolean = when (row.entityType) {
        "baby" -> babyDao.getByClientUuid(row.clientUuid) == null
        "record" -> recordDao.getByClientUuid(row.clientUuid) == null
        "care_plan" -> carePlanDao.getByClientUuid(row.clientUuid) == null
        "custom_item" -> customItemDao.getByClientUuid(row.clientUuid) == null
        "fulfillment_candidate" ->
            fulfillmentCandidateDao.getByClientUuid(row.clientUuid) == null
        "media" -> {
            val media = mediaDao.getByClientUuid(row.clientUuid)
            val recordId = media?.recordId
            val carePlanId = media?.carePlanId
            media == null || (
                media.kind == "log" && (
                    (recordId != null && recordDao.getIncludingDeleted(recordId) == null) ||
                        (carePlanId != null && carePlanDao.get(carePlanId) == null) ||
                        (recordId == null && carePlanId == null)
                    )
                )
        }
        else -> false
    }

    private suspend fun expandBatchWithDependencies(
        session: SyncSession,
        roots: List<OutboxEntity>,
    ): List<OutboxEntity> {
        val selected = linkedMapOf<Pair<String, String>, OutboxEntity>()
        roots.forEach { row -> selected[row.entityType to row.clientUuid] = row }
        var index = 0
        while (index < selected.size) {
            val row = selected.values.elementAt(index++)
            val payload = runCatching {
                Json.parseToJsonElement(row.payloadJson).jsonObject
            }.getOrNull() ?: continue
            val dependencies = when (row.entityType) {
                "baby" -> listOfNotNull(
                    payload.string("avatar_media_uuid")?.let { "media" to it },
                )
                "record" -> {
                    // Baby for FK + every pending log media row for this record so
                    // atomic packages stage a complete 0–3 photo manifest.
                    val babyDep = payload.string("baby_client_uuid")?.let { "baby" to it }
                    val customDep = payload.string("custom_item_client_uuid")
                        ?.let { "custom_item" to it }
                    val mediaDeps = recordDao.getByClientUuid(row.clientUuid)?.id?.let { recordId ->
                        mediaDao.listForRecord(recordId)
                            .filter { it.kind == "log" }
                            .map { "media" to it.clientUuid }
                    }.orEmpty()
                    listOfNotNull(babyDep, customDep) + mediaDeps
                }
                "care_plan" -> {
                    val babyDep = payload.string("baby_client_uuid")?.let { "baby" to it }
                    val customDep = payload.string("custom_item_client_uuid")
                        ?.let { "custom_item" to it }
                    val fulfilledDep = payload.string("fulfilled_record_client_uuid")
                        ?.let { "record" to it }
                    val mediaDeps = carePlanDao.getByClientUuid(row.clientUuid)?.id?.let { planId ->
                        mediaDao.listForCarePlan(planId)
                            .filter { it.kind == "log" }
                            .map { "media" to it.clientUuid }
                    }.orEmpty()
                    listOfNotNull(babyDep, customDep, fulfilledDep) + mediaDeps
                }
                "fulfillment_candidate" -> listOfNotNull(
                    payload.string("care_plan_client_uuid")?.let { "care_plan" to it },
                    payload.string("record_client_uuid")?.let { "record" to it },
                )
                "media" -> listOfNotNull(
                    payload.string("record_client_uuid")?.let { "record" to it },
                    payload.string("care_plan_client_uuid")?.let { "care_plan" to it },
                    payload.string("baby_client_uuid")?.let { "baby" to it },
                )
                else -> emptyList()
            }
            dependencies.forEach { reference ->
                if (reference !in selected) {
                    outboxDao.find(
                        session.familyId,
                        reference.first,
                        reference.second,
                    )?.let { selected[reference] = it }
                }
            }
        }
        require(selected.size <= MAX_PUSH_BATCH_SIZE) {
            "同步依赖批次过大，请稍后重试"
        }
        return selected.values.toList()
    }

    private suspend fun mergeCanonicalRecordAuthors(
        authors: List<CanonicalRecordAuthor>,
        expectedUpdatedAt: Map<String, Long>,
    ) {
        val authorsByClientUuid = authors.groupBy(CanonicalRecordAuthor::clientUuid)
        val expectedClientUuids = expectedUpdatedAt.keys
        val actualClientUuids = authorsByClientUuid.keys
        val missing = expectedClientUuids - actualClientUuids
        val unexpected = actualClientUuids - expectedClientUuids
        val duplicates = authorsByClientUuid
            .filterValues { acknowledgements -> acknowledgements.size != 1 }
            .keys
        require(missing.isEmpty() && unexpected.isEmpty() && duplicates.isEmpty()) {
            "record_authors 回执必须与请求中的 Record 一一对应；" +
                "缺失=${missing.sorted()}，重复=${duplicates.sorted()}，多余=${unexpected.sorted()}"
        }
        val canonicalMemberships = authors.associate { author ->
            val membershipId = author.createdByMembershipId.trim()
            require(membershipId.isNotEmpty()) {
                "record_authors[${author.clientUuid}] 的 canonical membership 为空"
            }
            author.clientUuid to membershipId
        }
        expectedUpdatedAt.forEach { (clientUuid, updatedAt) ->
            recordDao.mergeCanonicalAuthor(
                clientUuid = clientUuid,
                expectedUpdatedAt = updatedAt,
                membershipId = checkNotNull(canonicalMemberships[clientUuid]),
            )
        }
    }

    private suspend fun recordCustomItemClientUuid(record: RecordEntity): String? {
        val type = SyncWireMapper.requireCurrentRecordType(record.type, "record type")
        if (type != RecordType.CUSTOM) return null
        require(record.schemaVersion == CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION) {
            "custom record schema_version 必须是 $CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION"
        }
        val localId = runCatching {
            Json.parseToJsonElement(record.payloadJson).jsonObject["custom_item_id"]
                ?.jsonPrimitive
                ?.longOrNull
        }.getOrNull()?.takeIf { it > 0L }
        require(localId != null) { "custom record 缺少本地 custom_item_id" }
        val definition = requireNotNull(customItemDao.getById(localId)) {
            "custom record 引用的本地定义不存在"
        }
        return definition.clientUuid.takeIf(String::isNotBlank)
            ?: error("custom record 引用的定义缺少 client_uuid")
    }
}

/**
 * Pure classification of a pending outbox snapshot into ordinary vs atomic lanes.
 */
internal data class OutboxPushPlan(
    /** Every pending Record root (0–3 log photos) publishes as an atomic package. */
    val recordRows: List<OutboxEntity>,
    val carePlanRows: List<OutboxEntity>,
    /** Pending rows not covered by atomic packages (baby/custom/avatar/fulfillment). */
    val residual: List<OutboxEntity>,
)

internal object OutboxPushPlanner {
    suspend fun classify(
        pending: List<OutboxEntity>,
        recordDao: RecordDao,
        carePlanDao: CarePlanDao,
        mediaDao: MediaAssetDao,
    ): OutboxPushPlan {
        // Every Record and every CarePlan use atomic bundles (0-photo records included).
        val recordRows = pending.filter { it.entityType == "record" }
        val carePlanRows = pending.filter { it.entityType == "care_plan" }
        val recordClientUuids = recordRows.map { it.clientUuid }.toSet()
        val carePlanClientUuids = carePlanRows.map { it.clientUuid }.toSet()
        val logMediaForRecords = pending.filter { row ->
            if (row.entityType != "media") return@filter false
            val media = mediaDao.getByClientUuid(row.clientUuid) ?: return@filter false
            val recordId = media.recordId ?: return@filter false
            media.kind == "log" &&
                recordDao.getIncludingDeleted(recordId)?.clientUuid in recordClientUuids
        }
        val logMediaForPlans = pending.filter { row ->
            if (row.entityType != "media") return@filter false
            val media = mediaDao.getByClientUuid(row.clientUuid) ?: return@filter false
            val planId = media.carePlanId ?: return@filter false
            media.kind == "log" &&
                carePlanDao.get(planId)?.clientUuid in carePlanClientUuids
        }
        val atomicPackageRows =
            (recordRows + carePlanRows + logMediaForRecords + logMediaForPlans).toSet()
        return OutboxPushPlan(
            recordRows = recordRows,
            carePlanRows = carePlanRows,
            residual = pending - atomicPackageRows,
        )
    }
}
