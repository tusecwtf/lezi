package com.lezi.babylog.sync.disasterrecovery

import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.CarePlanDao
import com.lezi.babylog.core.database.CustomItemDao
import com.lezi.babylog.core.database.FulfillmentCandidateDao
import com.lezi.babylog.core.database.MediaAssetDao
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.core.database.causal.WakeObservationDao
import com.lezi.babylog.core.database.causal.WakeObservationEntity
import com.lezi.babylog.core.database.causal.SourceRelationDao
import com.lezi.babylog.core.database.causal.ConflictSnapshotCacheDao
import com.lezi.babylog.sync.DisasterRecoverySummary
import com.lezi.babylog.sync.backend.DisasterRestoreMediaSpec
import com.lezi.babylog.sync.backend.DisasterRestoreSourceRelation
import com.lezi.babylog.sync.backend.SyncEntity
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.sync.engine.SyncWireMapper
import com.lezi.babylog.sync.engine.encodeWakeBundleRoot
import com.lezi.babylog.sync.engine.pendingRecordCustomItemId
import com.lezi.babylog.sync.engine.resolveRecordCustomItemClientUuid
import com.lezi.babylog.sync.media.PreparedMedia
import com.lezi.babylog.sync.media.SyncMediaFileStore
import com.lezi.babylog.sync.session.DisasterRestoreEntityVersion
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class PreparedRestoreMedia(
    val clientUuid: String,
    val source: com.lezi.babylog.sync.media.SyncMediaUploadSource,
    val spec: DisasterRestoreMediaSpec,
)

internal class DisasterRecoverySnapshot(
    val entities: List<SyncEntity>,
    val media: List<PreparedRestoreMedia>,
    val summary: DisasterRecoverySummary,
    val retirementVersions: List<DisasterRestoreEntityVersion>,
    val fileSnapshot: RestoreFileSnapshot? = null,
    val sourceRelations: List<DisasterRestoreSourceRelation>? = null,
    val sourceRelationEvidence: String? = null,
    val evidenceVersion: Int = 1,
) : AutoCloseable {
    override fun close() {
        media.forEach { runCatching { (it.source as? AutoCloseable)?.close() } }
    }
}

/** Captures live care facts plus the exact historical dependencies of canonical source relations. */
internal class DisasterRecoverySnapshotBuilder(
    private val babyDao: BabyDao,
    private val recordDao: RecordDao,
    private val carePlanDao: CarePlanDao,
    private val customItemDao: CustomItemDao,
    private val fulfillmentCandidateDao: FulfillmentCandidateDao,
    private val mediaDao: MediaAssetDao,
    private val wakeObservationDao: WakeObservationDao,
    private val mediaFiles: SyncMediaFileStore,
    private val transactions: DatabaseTransactionRunner,
    private val sourceRelationDao: SourceRelationDao? = null,
    private val conflictSnapshotCacheDao: ConflictSnapshotCacheDao? = null,
) {
    suspend fun build(): DisasterRecoverySnapshot = assemble { rows, finish ->
        val prepared = mutableListOf<PreparedRestoreMedia>()
        try {
            rows.forEach { row ->
                val source = mediaFiles.preparePublishedUpload(row.localUri,
                    com.lezi.babylog.sync.media.PublishedMediaIdentity(row.sha256,
                        row.byteSize.takeIf { it > 0 } ?: requireNotNull(mediaFiles.statLength(row.localUri)),
                        row.mime, row.width, row.height))
                prepared += PreparedRestoreMedia(row.clientUuid, source,
                    DisasterRestoreMediaSpec(row.clientUuid, source.contentLength, sha256(source)))
            }
            finish(prepared)
        } catch (error: Throwable) {
            prepared.forEach { runCatching { (it.source as? AutoCloseable)?.close() } }
            throw error
        }
    }

    /** New authority only: old receipt digests are evidence, never the selected source identity. */
    suspend fun capture(requestId: String, familyId: String, store: RestoreFileSnapshotStore): RestoreFileSnapshotPointer {
        store.completed(requestId)?.let { return it }
        var sources = emptyList<RestoreFileSnapshotSource>()
        val snapshot = assemble(evidenceVersion = 2) { rows, finish ->
            sources = rows.map { row ->
                RestoreFileSnapshotSource(row.clientUuid,
                    requireNotNull(mediaFiles.restoreSourceFile(row.localUri)) { "本地恢复照片不可读" },
                    row.mime, row.width, row.height, guardPath = row.localUri)
            }
            finish(sources.map { source -> metadataOnly(source) })
        }
        val content = encodeRestoreSnapshotContent(snapshot, familyId)
        val sizes = snapshot.media.associate { it.clientUuid to it.spec.byteSize }
        return store.capture(requestId, sources, content.toByteArray(Charsets.UTF_8).size.toLong()) { copied ->
            require(copied.size == sizes.size && copied.all { sizes[it.clientUuid] == it.byteSize }) {
                "恢复来源在快照准备期间发生变化，请重试"
            }
            content
        }
    }

    suspend fun summary(): DisasterRecoverySummary = assemble { rows, finish ->
        finish(rows.map { row -> metadataOnly(RestoreFileSnapshotSource(row.clientUuid,
            requireNotNull(mediaFiles.readableFile(row.localUri)) { "本地恢复照片不可读" },
            row.mime, row.width, row.height)) })
    }.summary

    private fun metadataOnly(source: RestoreFileSnapshotSource): PreparedRestoreMedia {
        val size = source.file.length()
        require(size in 1..com.lezi.babylog.core.model.RecordPhotoResourcePolicy.maxUploadBytes) {
            "恢复照片大小超出当前无损上传范围"
        }
        val descriptor = object : com.lezi.babylog.sync.media.SyncMediaUploadSource {
            override val contentLength = size
            override val mime = source.mime
            override fun openStream(): java.io.InputStream = error("metadata descriptor has no upload bytes")
        }
        return PreparedRestoreMedia(source.clientUuid, descriptor,
            DisasterRestoreMediaSpec(source.clientUuid, size, "0".repeat(64)))
    }

    private suspend fun assemble(
        evidenceVersion: Int = 1,
        prepare: suspend (List<MediaAssetEntity>, (List<PreparedRestoreMedia>) -> DisasterRecoverySnapshot) -> DisasterRecoverySnapshot,
    ): DisasterRecoverySnapshot {
        val (captured, capturedRelations) = transactions.run {
            require(conflictSnapshotCacheDao?.getTransportJournal("source-relation-command-v1") == null) {
                "来源关系操作尚未完成，无法安全恢复"
            }
            require(sourceRelationDao?.listUnsettledDeclarations().isNullOrEmpty()) {
                "来源关系操作尚未完成，无法安全恢复"
            }
            val rows = CapturedRestoreRows(
                babyDao.listAllIncludingDeleted(), recordDao.listAllIncludingDeleted(),
                carePlanDao.listAllIncludingDeleted(), customItemDao.listAllIncludingDeleted(),
                fulfillmentCandidateDao.listAllIncludingDeleted(), wakeObservationDao.listAllIncludingDeleted(),
                mediaDao.listAllIncludingDeleted(),
            )
            rows to sourceRelationDao?.let { captureRestoreSourceRelations(it) }
        }
        val dependencies = restoreRelationDependencies(captured, capturedRelations?.sourceRelations.orEmpty())
        val allBabies = captured.babies
        val allRecords = captured.records
        val allPlans = captured.plans
        val allCustomItems = captured.customItems
        val allCandidates = captured.candidates
        val allWakes = captured.wakes
        val allMedia = captured.media
        val babies = allBabies.filter { it.deletedAt == null || it.id in dependencies.babies }
        require(babies.any { it.deletedAt == null }) { "本机没有可恢复的宝宝档案" }
        val babiesById = babies.associateBy { it.id }
        val customItems = allCustomItems.filter { it.deletedAt == null || it.id in dependencies.customItems }
        val customItemsById = customItems.associateBy { it.id }
        // A custom row whose definition was tombstoned cannot ride the wire
        // (custom_item_client_uuid is required); deleting a definition does
        // not rewrite referencing history, so this is the same converged
        // replica state as the wake-over-tombstoned-sleep case. Such rows are
        // excluded — with their photos and fulfillment evidence — instead of
        // failing the whole family export. A referenced local id with no row
        // at all is corruption and still fails closed in the wire mapper.
        fun customDefinitionTombstoned(type: String, localId: Long?): Boolean {
            if (type != RecordType.CUSTOM.key || localId == null) return false
            return allCustomItems.firstOrNull { it.id == localId }?.deletedAt != null
        }
        val excludedRecordIds = allRecords
            .filter { record ->
                record.deletedAt == null && record.clientUuid !in dependencies.records &&
                    customDefinitionTombstoned(record.type, pendingRecordCustomItemId(record))
            }
            .mapTo(mutableSetOf()) { it.id }
        val excludedPlanIds = allPlans
            .filter { plan ->
                plan.deletedAt == null &&
                    customDefinitionTombstoned(plan.type, plan.customItemId)
            }
            .mapTo(mutableSetOf()) { it.id }
        val excludedRecordUuids = allRecords
            .filter { it.id in excludedRecordIds }
            .mapTo(mutableSetOf()) { it.clientUuid }
        val excludedPlanUuids = allPlans
            .filter { it.id in excludedPlanIds }
            .mapTo(mutableSetOf()) { it.clientUuid }
        val records = allRecords.filter { record ->
            record.clientUuid in dependencies.records ||
                (record.deletedAt == null && record.id !in excludedRecordIds &&
                requireNotNull(babiesById[record.babyId]) {
                    "记录引用了已删除的宝宝，无法安全恢复"
                }.deletedAt == null)
        }
        val recordsById = records.associateBy { it.id }
        val recordsByUuid = records.associateBy { it.clientUuid }
        val plans = allPlans.filter { plan ->
            plan.deletedAt == null && plan.id !in excludedPlanIds &&
                requireNotNull(babiesById[plan.babyId]) {
                    "护理计划引用了已删除的宝宝，无法安全恢复"
                }.deletedAt == null
        }
        val plansById = plans.associateBy { it.id }
        val plansByUuid = plans.associateBy { it.clientUuid }
        // Deleting a sleep tombstones only the record; replicas legitimately
        // converge to a live wake over a tombstoned sleep. Such a wake cannot
        // ride the restore payload, so it is excluded (with its media) instead
        // of failing the whole family export. A wake pointing at a live
        // non-sleep record is corruption and still fails closed.
        val wakes = allWakes.filter { wake ->
            if (wake.clientUuid in dependencies.wakes) return@filter true
            if (wake.deletedAt != null) return@filter false
            val sleep = recordsByUuid[wake.sleepRecordClientUuid] ?: return@filter false
            require(sleep.type == "sleep") { "醒来观察引用的不是睡眠记录，无法安全恢复" }
            true
        }
        val wakesById = wakes.associateBy { it.id }
        val excludedCandidateUuids = allCandidates
            .filter { candidate ->
                candidate.deletedAt == null && (
                    candidate.recordClientUuid in excludedRecordUuids ||
                        candidate.carePlanClientUuid in excludedPlanUuids
                    )
            }
            .mapTo(mutableSetOf()) { it.clientUuid }
        val candidates = allCandidates
            .filter { it.deletedAt == null && it.clientUuid !in excludedCandidateUuids }
            .onEach { candidate ->
                require(plansByUuid[candidate.carePlanClientUuid] != null) {
                    "履行关系缺少护理计划，无法安全恢复"
                }
                require(recordsByUuid[candidate.recordClientUuid] != null) {
                    "履行关系缺少事实记录，无法安全恢复"
                }
            }
        val mediaRows = allMedia
            .filter { media ->
                if (media.deletedAt != null) return@filter false
                // Historical tombstones retain their payload pointers, but their bytes need not
                // survive. Every live attachment of an included live root still rides the snapshot.
                if (media.kind == "avatar" && media.babyId?.let(babiesById::get)?.deletedAt != null) {
                    return@filter false
                }
                if (media.kind == "wake") {
                    // Wake media rides only with an included wake (whose sleep
                    // is live by the wakes filter); an orphaned or excluded
                    // wake excludes its photo instead of failing the export.
                    return@filter media.wakeObservationId?.let(wakesById::get)?.deletedAt == null &&
                        media.wakeObservationId?.let(wakesById::containsKey) == true
                }
                if (media.kind == "log") {
                    if (media.recordId?.let(recordsById::get)?.deletedAt != null) return@filter false
                    // Photos of rows excluded for a tombstoned custom
                    // definition ride nothing; exclude them the same way.
                    if (media.recordId != null && media.recordId in excludedRecordIds) {
                        return@filter false
                    }
                    if (media.carePlanId != null && media.carePlanId in excludedPlanIds) {
                        return@filter false
                    }
                }
                true
            }
            .onEach { media ->
                when (media.kind) {
                    "log" -> require(
                        (media.recordId?.let(recordsById::containsKey) == true) xor
                            (media.carePlanId?.let(plansById::containsKey) == true),
                    ) { "照片缺少有效的记录或护理计划，无法安全恢复" }
                    "avatar" -> require(media.babyId?.let(babiesById::containsKey) == true) {
                        "头像缺少有效的宝宝档案，无法安全恢复"
                    }
                    // "wake" validity was decided by the filter above.
                }
            }

        // Reject a representational boundary before opening/preparing any file or uploading.
        mediaRows.forEach { requireSchema13RestoreMime(it.clientUuid, it.mime) }
        return prepare(mediaRows) { prepared ->
            val preparedByUuid = prepared.associateBy { it.clientUuid }
            val mediaEntities = mediaRows.map { row ->
                val source = preparedByUuid.getValue(row.clientUuid).source
                // Wake wire reuses record_client_uuid for the WakeObservation, the
                // same field pull already parses. The sleep record is checked above.
                val recordClientUuid = if (row.kind == "wake") {
                    wakesById.getValue(requireNotNull(row.wakeObservationId)).clientUuid
                } else {
                    row.recordId?.let(recordsById::get)?.clientUuid
                }
                SyncWireMapper.media(
                    entity = row.copy(
                        byteSize = source.contentLength,
                        mime = row.mime,
                        width = row.width,
                        height = row.height,
                    ),
                    recordClientUuid = recordClientUuid,
                    carePlanClientUuid = row.carePlanId?.let(plansById::get)?.clientUuid,
                    babyClientUuid = row.babyId?.let(babiesById::get)?.clientUuid,
                    allowWake = true,
                )
            }
            val activeAvatarByBaby = mediaRows
                .filter { it.kind == "avatar" }
                .groupBy { it.babyId }
            val entities = buildList {
                addAll(customItems.map(SyncWireMapper::customItem))
                addAll(babies.map { baby ->
                    val avatars = activeAvatarByBaby[baby.id].orEmpty()
                    val avatarUuid = if (baby.deletedAt != null) {
                        baby.avatarMediaUuid
                    } else {
                        baby.avatarMediaUuid
                            ?.takeIf { pointer -> avatars.any { it.clientUuid == pointer } }
                            ?: avatars.maxWithOrNull(
                                compareBy<com.lezi.babylog.core.database.MediaAssetEntity> {
                                    it.updatedAt
                                }.thenBy { it.id },
                            )?.clientUuid
                    }
                    SyncWireMapper.baby(baby, avatarUuid)
                })
                addAll(records.map { record ->
                    SyncWireMapper.record(
                        record,
                        babiesById.getValue(record.babyId).clientUuid,
                        pendingRecordCustomItemId(record)?.let { requireNotNull(customItemsById[it]).clientUuid },
                    )
                })
                addAll(wakes.map { wake -> wakeEntity(wake) })
                addAll(plans.map { plan ->
                    // A fulfilled plan whose record was excluded (tombstoned
                    // custom definition) cannot carry the pointer: the server
                    // rejects an unresolvable fulfilled_record_client_uuid and
                    // forbids completed without the pair. It rides as missed
                    // with the fulfillment stripped — the record is gone.
                    val mapped = if (
                        plan.fulfilledRecordClientUuid != null &&
                        recordsByUuid[plan.fulfilledRecordClientUuid] == null
                    ) {
                        plan.copy(
                            status = "missed",
                            fulfilledRecordClientUuid = null,
                            fulfilledAt = null,
                        )
                    } else {
                        plan
                    }
                    SyncWireMapper.carePlan(
                        mapped,
                        babiesById.getValue(mapped.babyId).clientUuid,
                        mapped.customItemId?.let(customItemsById::get)?.clientUuid,
                    )
                })
                addAll(candidates.map(SyncWireMapper::fulfillmentCandidate))
                addAll(mediaEntities)
            }
            DisasterRecoverySnapshot(
                entities = entities,
                media = prepared,
                summary = DisasterRecoverySummary(
                    babies = babies.count { it.deletedAt == null },
                    records = records.count { it.deletedAt == null },
                    carePlans = plans.size,
                    fulfillmentRelations = candidates.size,
                    customItems = customItems.count { it.deletedAt == null },
                    photos = prepared.size,
                    mediaBytes = prepared.sumOf { it.source.contentLength },
                ),
                // restored=true claims the server published exactly this
                // revision; excluded live rows (tombstoned custom definition,
                // wake over tombstoned sleep) never ride the manifest, so
                // their receipts must not fabricate publication state.
                retirementVersions = buildList {
                    val restoredBabyUuids = babies.mapTo(mutableSetOf()) { it.clientUuid }
                    allBabies.forEach {
                        add(version("baby", it.clientUuid, it.updatedAt, it.clientUuid in restoredBabyUuids))
                    }
                    val restoredRecordUuids = records.mapTo(mutableSetOf()) { it.clientUuid }
                    allRecords.forEach {
                        add(
                            version(
                                "record",
                                it.clientUuid,
                                it.updatedAt,
                                it.clientUuid in restoredRecordUuids,
                            ),
                        )
                    }
                    val restoredWakeUuids = wakes.mapTo(mutableSetOf()) { it.clientUuid }
                    allWakes.forEach {
                        add(
                            version(
                                "wake_observation",
                                it.clientUuid,
                                it.updatedAt,
                                it.clientUuid in restoredWakeUuids,
                            ),
                        )
                    }
                    val restoredPlanUuids = plans.mapTo(mutableSetOf()) { it.clientUuid }
                    allPlans.forEach {
                        add(
                            version(
                                "care_plan",
                                it.clientUuid,
                                it.updatedAt,
                                it.deletedAt == null && it.clientUuid in restoredPlanUuids,
                            ),
                        )
                    }
                    val restoredCustomItemUuids = customItems.mapTo(mutableSetOf()) { it.clientUuid }
                    allCustomItems.forEach {
                        add(
                            version(
                                "custom_item",
                                it.clientUuid,
                                it.updatedAt,
                                it.clientUuid in restoredCustomItemUuids,
                            ),
                        )
                    }
                    val restoredCandidateUuids =
                        candidates.mapTo(mutableSetOf()) { it.clientUuid }
                    allCandidates.forEach {
                        add(
                            version(
                                "fulfillment_candidate",
                                it.clientUuid,
                                it.updatedAt,
                                it.deletedAt == null && it.clientUuid in restoredCandidateUuids,
                            ),
                        )
                    }
                    val restoredMediaUuids = mediaRows.mapTo(mutableSetOf()) { it.clientUuid }
                    allMedia.forEach {
                        add(
                            version(
                                "media",
                                it.clientUuid,
                                it.updatedAt,
                                it.deletedAt == null && it.clientUuid in restoredMediaUuids,
                            ),
                        )
                    }
                }.map {
                    it.copy(localEvidence = if (evidenceVersion == 2) {
                        captured.exactEvidence(it.type, it.clientUuid)
                    } else {
                        captured.evidence(it.type, it.clientUuid)
                    })
                },
                sourceRelations = capturedRelations?.sourceRelations,
                sourceRelationEvidence = capturedRelations?.localEvidence,
                evidenceVersion = evidenceVersion,
            )
        }
    }

    private fun wakeEntity(wake: WakeObservationEntity) = SyncEntity(
        type = "wake_observation",
        clientUuid = wake.clientUuid,
        payloadJson = encodeWakeBundleRoot(wake),
        updatedAt = wake.updatedAt,
        deletedAt = wake.deletedAt,
    )

    private fun version(
        type: String,
        clientUuid: String,
        updatedAt: Long,
        restored: Boolean,
    ) = DisasterRestoreEntityVersion(type, clientUuid, updatedAt, restored)

    private suspend fun sha256(source: com.lezi.babylog.sync.media.SyncMediaUploadSource): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        source.openStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}
