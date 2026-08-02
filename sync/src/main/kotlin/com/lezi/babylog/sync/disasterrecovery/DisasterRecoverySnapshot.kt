package com.lezi.babylog.sync.disasterrecovery

import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.CarePlanDao
import com.lezi.babylog.core.database.CustomItemDao
import com.lezi.babylog.core.database.FulfillmentCandidateDao
import com.lezi.babylog.core.database.MediaAssetDao
import com.lezi.babylog.core.database.RecordDao
import com.lezi.babylog.sync.DisasterRecoverySummary
import com.lezi.babylog.sync.backend.DisasterRestoreMediaSpec
import com.lezi.babylog.sync.backend.SyncEntity
import com.lezi.babylog.sync.engine.SyncWireMapper
import com.lezi.babylog.sync.engine.resolveRecordCustomItemClientUuid
import com.lezi.babylog.sync.media.PreparedMedia
import com.lezi.babylog.sync.media.SyncMediaFileStore
import com.lezi.babylog.sync.session.DisasterRestoreEntityVersion
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class PreparedRestoreMedia(
    val clientUuid: String,
    val source: PreparedMedia,
    val spec: DisasterRestoreMediaSpec,
)

internal class DisasterRecoverySnapshot(
    val entities: List<SyncEntity>,
    val media: List<PreparedRestoreMedia>,
    val summary: DisasterRecoverySummary,
    val retirementVersions: List<DisasterRestoreEntityVersion>,
) : AutoCloseable {
    override fun close() {
        media.forEach { runCatching { it.source.close() } }
    }
}

/** Builds one complete, tombstone-free care dataset from Room without any network dependency. */
internal class DisasterRecoverySnapshotBuilder(
    private val babyDao: BabyDao,
    private val recordDao: RecordDao,
    private val carePlanDao: CarePlanDao,
    private val customItemDao: CustomItemDao,
    private val fulfillmentCandidateDao: FulfillmentCandidateDao,
    private val mediaDao: MediaAssetDao,
    private val mediaFiles: SyncMediaFileStore,
) {
    suspend fun build(): DisasterRecoverySnapshot {
        val allBabies = babyDao.listAllIncludingDeleted()
        val allRecords = recordDao.listAllIncludingDeleted()
        val allPlans = carePlanDao.listAllIncludingDeleted()
        val allCustomItems = customItemDao.listAllIncludingDeleted()
        val allCandidates = fulfillmentCandidateDao.listAllIncludingDeleted()
        val allMedia = mediaDao.listAllIncludingDeleted()
        val babies = allBabies.filter { it.deletedAt == null }
        require(babies.isNotEmpty()) { "本机没有可恢复的宝宝档案" }
        val babiesById = babies.associateBy { it.id }
        val customItems = allCustomItems.filter { it.deletedAt == null }
        val customItemsById = customItems.associateBy { it.id }
        val records = allRecords.filter { record ->
            record.deletedAt == null && requireNotNull(babiesById[record.babyId]) {
                "记录引用了已删除的宝宝，无法安全恢复"
            }.deletedAt == null
        }
        val recordsById = records.associateBy { it.id }
        val recordsByUuid = records.associateBy { it.clientUuid }
        val plans = allPlans.filter { plan ->
            plan.deletedAt == null && requireNotNull(babiesById[plan.babyId]) {
                "护理计划引用了已删除的宝宝，无法安全恢复"
            }.deletedAt == null
        }
        plans.forEach { plan ->
            plan.customItemId?.let { customId ->
                require(customItemsById[customId] != null) {
                    "护理计划引用了已删除的自定义项目，无法安全恢复"
                }
            }
        }
        val plansById = plans.associateBy { it.id }
        val plansByUuid = plans.associateBy { it.clientUuid }
        val candidates = allCandidates
            .filter { it.deletedAt == null }
            .onEach { candidate ->
                require(plansByUuid[candidate.carePlanClientUuid] != null) {
                    "履行关系缺少护理计划，无法安全恢复"
                }
                require(recordsByUuid[candidate.recordClientUuid] != null) {
                    "履行关系缺少事实记录，无法安全恢复"
                }
            }
        val mediaRows = allMedia
            .filter { it.deletedAt == null }
            .onEach { media ->
                when (media.kind) {
                    "log" -> require(
                        (media.recordId?.let(recordsById::containsKey) == true) xor
                            (media.carePlanId?.let(plansById::containsKey) == true),
                    ) { "照片缺少有效的记录或护理计划，无法安全恢复" }
                    "avatar" -> require(media.babyId?.let(babiesById::containsKey) == true) {
                        "头像缺少有效的宝宝档案，无法安全恢复"
                    }
                }
            }

        val prepared = mutableListOf<PreparedRestoreMedia>()
        try {
            for (row in mediaRows) {
                val source = mediaFiles.prepareUpload(row.localUri)
                prepared += PreparedRestoreMedia(
                    clientUuid = row.clientUuid,
                    source = source,
                    spec = DisasterRestoreMediaSpec(
                        clientUuid = row.clientUuid,
                        byteSize = source.contentLength,
                        sha256 = sha256(source),
                    ),
                )
            }
            val preparedByUuid = prepared.associateBy { it.clientUuid }
            val mediaEntities = mediaRows.map { row ->
                val source = preparedByUuid.getValue(row.clientUuid).source
                SyncWireMapper.media(
                    entity = row.copy(
                        byteSize = source.contentLength,
                        mime = source.mime,
                        width = source.width,
                        height = source.height,
                    ),
                    recordClientUuid = row.recordId?.let(recordsById::get)?.clientUuid,
                    carePlanClientUuid = row.carePlanId?.let(plansById::get)?.clientUuid,
                    babyClientUuid = row.babyId?.let(babiesById::get)?.clientUuid,
                )
            }
            val activeAvatarByBaby = mediaRows
                .filter { it.kind == "avatar" }
                .groupBy { it.babyId }
            val entities = buildList {
                addAll(customItems.map(SyncWireMapper::customItem))
                addAll(babies.map { baby ->
                    val avatars = activeAvatarByBaby[baby.id].orEmpty()
                    val avatarUuid = baby.avatarMediaUuid
                        ?.takeIf { pointer -> avatars.any { it.clientUuid == pointer } }
                        ?: avatars.maxWithOrNull(
                            compareBy<com.lezi.babylog.core.database.MediaAssetEntity> {
                                it.updatedAt
                            }.thenBy { it.id },
                        )?.clientUuid
                    SyncWireMapper.baby(baby, avatarUuid)
                })
                addAll(records.map { record ->
                    val customUuid = resolveRecordCustomItemClientUuid(record, customItemDao)
                    if (customUuid != null) {
                        require(customItems.any { it.clientUuid == customUuid }) {
                            "记录引用了已删除的自定义项目，无法安全恢复"
                        }
                    }
                    SyncWireMapper.record(
                        record,
                        babiesById.getValue(record.babyId).clientUuid,
                        customUuid,
                    )
                })
                addAll(plans.map { plan ->
                    SyncWireMapper.carePlan(
                        plan,
                        babiesById.getValue(plan.babyId).clientUuid,
                        plan.customItemId?.let(customItemsById::get)?.clientUuid,
                    )
                })
                addAll(candidates.map(SyncWireMapper::fulfillmentCandidate))
                addAll(mediaEntities)
            }
            return DisasterRecoverySnapshot(
                entities = entities,
                media = prepared,
                summary = DisasterRecoverySummary(
                    babies = babies.size,
                    records = records.size,
                    carePlans = plans.size,
                    fulfillmentRelations = candidates.size,
                    customItems = customItems.size,
                    photos = prepared.size,
                    mediaBytes = prepared.sumOf { it.source.contentLength },
                ),
                retirementVersions = buildList {
                    allBabies.forEach {
                        add(version("baby", it.clientUuid, it.updatedAt, it.deletedAt == null))
                    }
                    allRecords.forEach {
                        add(version("record", it.clientUuid, it.updatedAt, it.deletedAt == null))
                    }
                    allPlans.forEach {
                        add(version("care_plan", it.clientUuid, it.updatedAt, it.deletedAt == null))
                    }
                    allCustomItems.forEach {
                        add(
                            version(
                                "custom_item",
                                it.clientUuid,
                                it.updatedAt,
                                it.deletedAt == null,
                            ),
                        )
                    }
                    allCandidates.forEach {
                        add(
                            version(
                                "fulfillment_candidate",
                                it.clientUuid,
                                it.updatedAt,
                                it.deletedAt == null,
                            ),
                        )
                    }
                    allMedia.forEach {
                        add(version("media", it.clientUuid, it.updatedAt, it.deletedAt == null))
                    }
                },
            )
        } catch (error: Throwable) {
            prepared.forEach { runCatching { it.source.close() } }
            throw error
        }
    }

    private fun version(
        type: String,
        clientUuid: String,
        updatedAt: Long,
        restored: Boolean,
    ) = DisasterRestoreEntityVersion(type, clientUuid, updatedAt, restored)

    private suspend fun sha256(source: PreparedMedia): String = withContext(Dispatchers.IO) {
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
