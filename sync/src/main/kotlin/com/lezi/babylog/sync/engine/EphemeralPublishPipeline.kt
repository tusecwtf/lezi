package com.lezi.babylog.sync.engine
import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.BabyEntity
import com.lezi.babylog.core.database.CarePlanDao
import com.lezi.babylog.core.database.CustomItemDao
import com.lezi.babylog.core.database.FulfillmentCandidateDao
import com.lezi.babylog.core.database.MediaAssetDao
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.database.RecordEntity
import com.lezi.babylog.core.model.CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION
import com.lezi.babylog.core.model.RecordType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import com.lezi.babylog.sync.backend.AtomicBundleDraft
import com.lezi.babylog.sync.backend.BundleCommitResult
import com.lezi.babylog.sync.backend.CanonicalRecordAuthor
import com.lezi.babylog.sync.backend.SyncBackend
import com.lezi.babylog.sync.backend.SyncEntity
import com.lezi.babylog.sync.media.AtomicMediaBundlePublisher
import com.lezi.babylog.sync.media.SyncMediaFileStore
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.SyncSession

/**
 * Classifies a same-cycle Room snapshot into atomic root packages and executes them.
 *
 * Product contract:
 * - Baby + avatar media → one atomic bundle
 * - CustomItem / FulfillmentCandidate → empty-media atomic bundles
 * - Every Record (0–3 photos) and every CarePlan → atomic bundles
 */
internal class EphemeralPublishPipeline(
    private val backend: SyncBackend,
    private val recordDao: RecordDao,
    private val carePlanDao: CarePlanDao,
    private val babyDao: BabyDao,
    private val mediaDao: MediaAssetDao,
    private val customItemDao: CustomItemDao,
    private val fulfillmentCandidateDao: FulfillmentCandidateDao,
    private val mediaFiles: SyncMediaFileStore,
    private val requireRemoteAllowed: suspend (SyncSession) -> Unit,
) {
    private val atomicMediaBundlePublisher = AtomicMediaBundlePublisher(
        backend = backend,
        mediaFiles = mediaFiles,
        loadMedia = mediaDao::getByClientUuid,
        mergePreparedMetadata = mediaDao::mergePreparedMetadata,
        writeCommitReceipt = mediaDao::writeCommitReceipt,
        requireRemoteAllowed = requireRemoteAllowed,
    )

    suspend fun pushPending(
        session: SyncSession,
        candidates: List<PublishCandidate>,
    ) {
        val plan = EphemeralPublishPlan(candidates)
        while (pushPendingBatch(session, plan)) {
            // Acknowledged candidates leave only this in-memory plan. The next
            // sync cycle always snapshots Room again after reconcile.
        }
    }

    private suspend fun pushPendingBatch(
        session: SyncSession,
        ephemeral: EphemeralPublishPlan,
    ): Boolean {
        val roots = ephemeral.peek(PUSH_ROOT_BATCH_SIZE)
        if (roots.isEmpty()) return false
        val queued = expandBatchWithDependencies(roots, ephemeral)
        val staleAfterHardDelete = mutableListOf<PublishCandidate>()
        queued.forEach { row ->
            if (isStaleAfterHardDelete(row)) staleAfterHardDelete += row
        }
        if (staleAfterHardDelete.isNotEmpty()) {
            // A committed local clear can leave an already-captured candidate behind.
            // Never resurrect a hard-deleted entity later in the same cycle.
            ephemeral.consume(staleAfterHardDelete)
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
            ephemeral.consume(unauthorizedBabyRows)
        }
        val pending = queued - staleAfterHardDelete.toSet() - unauthorizedBabyRows.toSet()
        if (pending.isEmpty()) return !ephemeral.isEmpty

        val plan = PublishPlanner.classify(
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
            var mayContinue = pushAtomicResiduals(session, prerequisites, ephemeral)
            val residual = plan.residual - prerequisites.toSet()
            // Publish CarePlan before its fulfillment Record. The NAS uses the
            // persisted completed plan as the authority that permits a new fact
            // to retain a tombstoned custom definition. Receivers co-gate a
            // completed plan until the linked Record arrives, so a pull page may
            // safely end between the two atomic packages.
            for (planRow in plan.carePlanRows) {
                mayContinue =
                    pushCarePlanAtomicBundle(session, planRow, pending, ephemeral) && mayContinue
            }
            // Every Record (including 0-photo) is an atomic package root.
            for (recordRow in plan.recordRows) {
                mayContinue =
                    pushRecordAtomicBundle(
                        session,
                        recordRow,
                        pending,
                        ephemeral,
                    ) && mayContinue
            }
            if (residual.isNotEmpty()) {
                mayContinue = pushAtomicResiduals(session, residual, ephemeral) && mayContinue
            }
            return mayContinue && !ephemeral.isEmpty
        }

        if (plan.residual.isEmpty()) return !ephemeral.isEmpty
        return pushAtomicResiduals(session, plan.residual, ephemeral) && !ephemeral.isEmpty
    }

    /**
     * Pre-join orphan facts stay local until their Baby is rebound to an authority profile.
     * Removing an unauthorized candidate only affects this cycle. The dirty entity remains in
     * Room and a later cycle can republish it after an automatic or explicit authority merge.
     */
    private suspend fun memberMayPublish(row: PublishCandidate): Boolean = when (row.entityType) {
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
        babyDao.getIncludingDeleted(babyId)?.familyAuthority == true

    private suspend fun pushAtomicResiduals(
        session: SyncSession,
        residual: List<PublishCandidate>,
        ephemeral: EphemeralPublishPlan,
    ): Boolean {
        if (residual.isEmpty()) return true
        var mayContinue = true
        val consumed = mutableSetOf<Long>()
        val avatarRows = residual.filter { row -> row.isAvatarMedia() }
        for (babyRow in residual.filter { it.entityType == "baby" }) {
            val baby = babyDao.getByClientUuid(babyRow.clientUuid)
                ?: error("本地宝宝档案不存在")
            val babyAvatarRows = avatarRows.filter { row ->
                mediaDao.getByClientUuid(row.clientUuid)?.let { media ->
                    media.babyId == baby.id &&
                        // Tombstone packages only attach already-deleted avatar media.
                        (baby.deletedAt == null || media.deletedAt != null)
                } == true
            }
            mayContinue =
                pushBabyAtomicBundle(session, baby, babyRow, babyAvatarRows, ephemeral) &&
                mayContinue
            consumed += babyRow.planId
            consumed += babyAvatarRows.map(PublishCandidate::planId)
        }
        for (avatarRow in avatarRows.filterNot { it.planId in consumed }) {
            val media = mediaDao.getByClientUuid(avatarRow.clientUuid)
                ?: error("本地媒体元数据不存在")
            val baby = media.babyId?.let { babyDao.getIncludingDeleted(it) }
                ?: error("头像缺少本地宝宝根")
            // Do not package a live avatar against a deleted baby root (orphan / bypass defense).
            // Keep media.syncDirty for domain repair, but do not let this invalid local shape
            // abort otherwise valid publication work in the current plan.
            if (baby.deletedAt != null && media.deletedAt == null) {
                ephemeral.consume(listOf(avatarRow))
                consumed += avatarRow.planId
                continue
            }
            mayContinue =
                pushBabyAtomicBundle(
                    session,
                    baby,
                    babyRow = null,
                    listOf(avatarRow),
                    ephemeral,
                ) &&
                mayContinue
            consumed += avatarRow.planId
        }

        val standaloneLogRows = residual.filter { row ->
            row.entityType == "media" && row.planId !in consumed
        }
        pushStandaloneLogMediaBundles(session, standaloneLogRows, ephemeral)
        consumed += standaloneLogRows.map(PublishCandidate::planId)

        for (row in residual.filterNot { it.planId in consumed }) {
            when (row.entityType) {
                "custom_item", "fulfillment_candidate" ->
                    pushEmptyMediaRoot(session, row, ephemeral)
                "record", "care_plan" ->
                    error("record/care_plan must be classified as an atomic root package")
                else -> error("不支持的同步根类型：${row.entityType}")
            }
            consumed += row.planId
        }
        return mayContinue
    }

    private fun PublishCandidate.isAvatarMedia(): Boolean =
        entityType == "media" && runCatching {
            Json.parseToJsonElement(payloadJson).jsonObject.string("kind") == "avatar"
        }.getOrDefault(false)

    private suspend fun pushBabyAtomicBundle(
        session: SyncSession,
        baby: BabyEntity,
        babyRow: PublishCandidate?,
        avatarRows: List<PublishCandidate>,
        ephemeral: EphemeralPublishPlan,
    ): Boolean {
        if (babyRow != null) {
            require(baby.updatedAt == babyRow.updatedAt) {
                "本地宝宝资料在同步打包期间已更新，请重试"
            }
        }
        // Content epoch packaged on the wire; synthetic packages may elevate above this.
        val expectedLocalUpdatedAt = baby.updatedAt
        val rootUpdatedAt = maxOf(
            babyRow?.updatedAt ?: nextPackageVersion(baby.updatedAt),
            avatarRows.maxOfOrNull(PublishCandidate::updatedAt) ?: baby.updatedAt,
        )
        val deletedAt = babyRow?.deletedAt ?: baby.deletedAt
        val root = SyncWireMapper.baby(
            entity = baby,
            // Deleted roots never republish a live avatar pointer (defense vs pre-fix orphans).
            avatarMediaUuid = if (deletedAt != null) null else baby.avatarMediaUuid,
        ).copy(
            updatedAt = rootUpdatedAt,
            deletedAt = deletedAt,
        )
        publishRootWithMedia(
            session = session,
            bundleId = AtomicBundleId.forBaby(baby.clientUuid, rootUpdatedAt),
            root = root,
            mediaRows = avatarRows,
        )
        // Align local baby revision to the exact rootUpdatedAt published to NAS so
        // the next local edit is strictly newer (avatar-only has no baby candidate).
        val confirmed = babyDao.acknowledgeSyntheticRootPublication(
            clientUuid = baby.clientUuid,
            expectedLocalUpdatedAt = expectedLocalUpdatedAt,
            publishedUpdatedAt = rootUpdatedAt,
        )
        if (babyRow != null && confirmed) {
            ephemeral.consume(listOf(babyRow))
        }
        acknowledgeMediaRows(avatarRows, ephemeral)
        return babyRow == null || confirmed
    }

    private suspend fun pushEmptyMediaRoot(
        session: SyncSession,
        row: PublishCandidate,
        ephemeral: EphemeralPublishPlan,
    ) {
        val bundleId = when (row.entityType) {
            "custom_item" -> AtomicBundleId.forCustomItem(row.clientUuid, row.updatedAt)
            "fulfillment_candidate" ->
                AtomicBundleId.forFulfillmentCandidate(row.clientUuid, row.updatedAt)
            else -> error("不支持的空媒体同步根：${row.entityType}")
        }
        requireRemoteAllowed(session)
        backend.stageBundle(
            session,
            AtomicBundleDraft(
                bundleId = bundleId,
                root = row.toSyncEntity(),
                media = emptyList(),
            ),
        )
        requireRemoteAllowed(session)
        backend.commitBundle(session, bundleId)
        when (row.entityType) {
            "custom_item" -> customItemDao.markSynced(row.clientUuid, row.updatedAt)
            "fulfillment_candidate" ->
                fulfillmentCandidateDao.markSynced(row.clientUuid, row.updatedAt)
        }
        ephemeral.consume(listOf(row))
    }

    private suspend fun pushStandaloneLogMediaBundles(
        session: SyncSession,
        rows: List<PublishCandidate>,
        ephemeral: EphemeralPublishPlan,
    ) {
        val recordGroups = linkedMapOf<Long, MutableList<PublishCandidate>>()
        val planGroups = linkedMapOf<Long, MutableList<PublishCandidate>>()
        for (row in rows) {
            val media = mediaDao.getByClientUuid(row.clientUuid)
                ?: error("本地媒体元数据不存在")
            require(media.kind == "log") { "独立媒体必须是 log 或 avatar" }
            val recordId = media.recordId
            val carePlanId = media.carePlanId
            when {
                recordId != null -> recordGroups.getOrPut(recordId) { mutableListOf() } += row
                carePlanId != null -> planGroups.getOrPut(carePlanId) { mutableListOf() } += row
                else -> error("日志媒体缺少 Record/CarePlan 根")
            }
        }
        for ((recordId, mediaRows) in recordGroups) {
            val record = recordDao.getIncludingDeleted(recordId)
                ?: error("日志媒体对应的本地记录不存在")
            val baby = babyDao.getIncludingDeleted(record.babyId)
                ?: error("本地宝宝档案不存在")
            val expectedLocalUpdatedAt = record.updatedAt
            val rootUpdatedAt = maxOf(
                nextPackageVersion(record.updatedAt),
                mediaRows.maxOf(PublishCandidate::updatedAt),
            )
            val root = SyncWireMapper.record(
                entity = record,
                babyClientUuid = baby.clientUuid,
                customItemClientUuid = recordCustomItemClientUuid(record),
            ).copy(updatedAt = rootUpdatedAt, deletedAt = record.deletedAt)
            publishRootWithMedia(
                session,
                AtomicBundleId.forRecord(record.clientUuid, rootUpdatedAt),
                root,
                mediaRows,
            )
            // Standalone media elevates root updatedAt on NAS; local root receipt and
            // revision must match that rootUpdatedAt so LWW / next edit stay aligned.
            recordDao.acknowledgeSyntheticRootPublication(
                clientUuid = record.clientUuid,
                expectedLocalUpdatedAt = expectedLocalUpdatedAt,
                publishedUpdatedAt = rootUpdatedAt,
            )
            acknowledgeMediaRows(mediaRows, ephemeral)
        }
        for ((planId, mediaRows) in planGroups) {
            val plan = carePlanDao.get(planId)
                ?: error("日志媒体对应的本地护理计划不存在")
            val baby = babyDao.getIncludingDeleted(plan.babyId)
                ?: error("本地宝宝档案不存在")
            val expectedLocalUpdatedAt = plan.updatedAt
            val rootUpdatedAt = maxOf(
                nextPackageVersion(plan.updatedAt),
                mediaRows.maxOf(PublishCandidate::updatedAt),
            )
            val root = SyncWireMapper.carePlan(
                entity = plan,
                babyClientUuid = baby.clientUuid,
                customItemClientUuid = plan.customItemId?.let { customItemDao.getById(it)?.clientUuid },
            ).copy(updatedAt = rootUpdatedAt, deletedAt = plan.deletedAt)
            publishRootWithMedia(
                session,
                AtomicBundleId.forCarePlan(plan.clientUuid, rootUpdatedAt),
                root,
                mediaRows,
            )
            carePlanDao.acknowledgeSyntheticRootPublication(
                clientUuid = plan.clientUuid,
                expectedLocalUpdatedAt = expectedLocalUpdatedAt,
                publishedUpdatedAt = rootUpdatedAt,
            )
            acknowledgeMediaRows(mediaRows, ephemeral)
        }
    }

    private suspend fun publishRootWithMedia(
        session: SyncSession,
        bundleId: String,
        root: SyncEntity,
        mediaRows: List<PublishCandidate>,
    ): BundleCommitResult = atomicMediaBundlePublisher.publish(
        session = session,
        bundleId = bundleId,
        root = root,
        mediaRows = mediaRows,
    )

    private suspend fun acknowledgeMediaRows(
        rows: List<PublishCandidate>,
        ephemeral: EphemeralPublishPlan,
    ) {
        rows.forEach { mediaDao.markSynced(it.clientUuid, it.updatedAt) }
        ephemeral.consume(rows)
    }

    private fun PublishCandidate.toSyncEntity(): SyncEntity = SyncEntity(
        type = entityType,
        clientUuid = clientUuid,
        payloadJson = payloadJson,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )

    private fun nextPackageVersion(updatedAt: Long): Long =
        if (updatedAt == Long.MAX_VALUE) updatedAt else updatedAt + 1

    private fun PublishCandidate.isAtomicBundlePrerequisite(): Boolean = when (entityType) {
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
        recordRow: PublishCandidate,
        pending: List<PublishCandidate>,
        ephemeral: EphemeralPublishPlan,
    ): Boolean {
        val record = recordDao.getByClientUuid(recordRow.clientUuid)
            ?: error("本地记录不存在")
        require(record.updatedAt == recordRow.updatedAt) {
            "本地记录在同步打包期间已更新，请重试"
        }
        val baby = babyDao.getIncludingDeleted(record.babyId)
            ?: error("本地宝宝档案不存在")
        val mediaRows = pending.filter { row ->
            if (row.entityType != "media") return@filter false
            val media = mediaDao.getByClientUuid(row.clientUuid) ?: return@filter false
            media.kind == "log" && media.recordId == record.id
        }
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
        val commit = publishRootWithMedia(session, bundleId, root, mediaRows)
        mergeCanonicalRecordAuthors(
            authors = commit.recordAuthors,
            expectedUpdatedAt = mapOf(record.clientUuid to recordRow.updatedAt),
        )
        val confirmed = recordDao.acknowledgeFamilyPublishedVersion(
            record.clientUuid,
            recordRow.updatedAt,
        )
        if (confirmed) {
            mediaRows.forEach { mediaDao.markSynced(it.clientUuid, it.updatedAt) }
            ephemeral.consume(listOf(recordRow) + mediaRows)
        }
        return confirmed
    }

    /**
     * Push one care plan + its 0–3 plan photos as an atomic NAS package.
     * [bundleId] is a deterministic UUID derived from root type + plan UUID + updatedAt.
     */
    private suspend fun pushCarePlanAtomicBundle(
        session: SyncSession,
        planRow: PublishCandidate,
        pending: List<PublishCandidate>,
        ephemeral: EphemeralPublishPlan,
    ): Boolean {
        val plan = carePlanDao.getByClientUuid(planRow.clientUuid)
            ?: error("本地护理计划不存在")
        require(plan.updatedAt == planRow.updatedAt) {
            "本地护理计划在同步打包期间已更新，请重试"
        }
        val baby = babyDao.getIncludingDeleted(plan.babyId)
            ?: error("本地宝宝档案不存在")
        val mediaRows = pending.filter { row ->
            if (row.entityType != "media") return@filter false
            val media = mediaDao.getByClientUuid(row.clientUuid) ?: return@filter false
            media.kind == "log" && media.carePlanId == plan.id
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
        publishRootWithMedia(session, bundleId, root, mediaRows)
        val confirmed = carePlanDao.acknowledgeFamilyPublishedVersion(
            plan.clientUuid,
            planRow.updatedAt,
        )
        if (confirmed) {
            mediaRows.forEach { mediaDao.markSynced(it.clientUuid, it.updatedAt) }
            ephemeral.consume(listOf(planRow) + mediaRows)
        }
        return confirmed
    }

    private suspend fun isStaleAfterHardDelete(row: PublishCandidate): Boolean =
        when (row.entityType) {
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
        roots: List<PublishCandidate>,
        ephemeral: EphemeralPublishPlan,
    ): List<PublishCandidate> {
        val selected = linkedMapOf<Pair<String, String>, PublishCandidate>()
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
                    ephemeral.find(reference.first, reference.second)
                        ?.let { selected[reference] = it }
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

    private suspend fun recordCustomItemClientUuid(record: RecordEntity): String? =
        resolveRecordCustomItemClientUuid(record, customItemDao)

}

/**
 * Pure classification of one ephemeral publish snapshot into package roots and dependent rows.
 */
internal data class PublishPlan(
    /** Every pending Record root (0–3 log photos) publishes as an atomic package. */
    val recordRows: List<PublishCandidate>,
    val carePlanRows: List<PublishCandidate>,
    /** Pending rows not covered by atomic packages (baby/custom/avatar/fulfillment). */
    val residual: List<PublishCandidate>,
)

internal object PublishPlanner {
    suspend fun classify(
        pending: List<PublishCandidate>,
        recordDao: RecordDao,
        carePlanDao: CarePlanDao,
        mediaDao: MediaAssetDao,
    ): PublishPlan {
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
        return PublishPlan(
            recordRows = recordRows,
            carePlanRows = carePlanRows,
            residual = pending - atomicPackageRows,
        )
    }
}
