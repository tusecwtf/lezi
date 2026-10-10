package com.lezi.babylog.sync.disasterrecovery

import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.causal.ConflictSnapshotCacheDao
import com.lezi.babylog.sync.DisasterRecoverySummary
import com.lezi.babylog.sync.backend.DisasterRestoreMediaSpec
import com.lezi.babylog.sync.backend.SyncEntity
import com.lezi.babylog.sync.media.*
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.session.DisasterRestoreEntityVersion
import java.util.UUID
import kotlinx.serialization.json.*

/** No credentials. Pins immutable bytes and request content before the first manifest request. */
internal class RestoreSnapshotJournal(
    private val cache: ConflictSnapshotCacheDao,
    private val spool: ImmutableMediaSpool,
    private val transactions: DatabaseTransactionRunner,
    private val files: RestoreFileSnapshotStore? = null,
) {
    suspend fun exists(requestId: String) = cache.getTransportJournal(key(requestId)) != null

    suspend fun pin(requestId: String, snapshot: DisasterRecoverySnapshot) {
        if (cache.getTransportJournal(key(requestId)) != null) return
        val groups = snapshot.media.map { media ->
            val entity = snapshot.entities.single { it.type == "media" && it.clientUuid == media.clientUuid }
            val payload = Json.parseToJsonElement(entity.payloadJson).jsonObject
            val role = when (payload.getValue("kind").jsonPrimitive.content) {
                "avatar" -> CausalMediaRole.Avatar
                "wake" -> CausalMediaRole.Wake
                else -> if (payload["care_plan_client_uuid"] != null && payload["care_plan_client_uuid"] != JsonNull)
                    CausalMediaRole.Plan else CausalMediaRole.Log
            }
            val source = media.source as? PreparedMedia ?: error("restore source must be pinned once")
            spool.freezeGroup(UUID.randomUUID().toString(), listOf(ImmutableMediaSpoolSource(
                mediaUuid = media.clientUuid, role = role, localUri = source.file.path,
                publishedIdentity = PublishedMediaIdentity(media.spec.sha256, media.spec.byteSize,
                    parseCanonicalMediaMime(payload["mime"]), payload["width"]?.jsonPrimitive?.intOrNull,
                    payload["height"]?.jsonPrimitive?.intOrNull),
            )))
        }
        val payload = buildJsonObject {
            put("format", 1)
            put("request", requestId)
            put("phase", "prepared")
            put("groups", JsonArray(groups.map { Json.parseToJsonElement(encodeImmutableMediaSpoolGroup(it)) }))
            put("entities", JsonArray(snapshot.entities.map { entity -> buildJsonObject {
                put("type", entity.type); put("uuid", entity.clientUuid)
                put("payload", Json.parseToJsonElement(entity.payloadJson)); put("updated", entity.updatedAt)
                put("deleted", entity.deletedAt?.let(::JsonPrimitive) ?: JsonNull)
            } }))
            put("versions", JsonArray(snapshot.retirementVersions.map { version -> buildJsonObject {
                put("type", version.type); put("uuid", version.clientUuid); put("updated", version.updatedAt)
                put("restored", version.restored); put("evidence", version.localEvidence?.let(::JsonPrimitive) ?: JsonNull)
            } }))
        }
        transactions.run {
            groups.forEach { cache.putFrozenMediaSpoolManifest(it.mutationId, encodeImmutableMediaSpoolGroup(it), 0) }
            cache.putTransportJournal(key(requestId), payload.toString(), 0)
        }
    }

    suspend fun load(requestId: String): DisasterRecoverySnapshot {
        val value = read(requestId)
        require(value["phase"]?.jsonPrimitive?.content != "retiring") { "恢复批次已在本机退休，请重新开始" }
        if (value.getValue("format").jsonPrimitive.int == 2) {
            val store = requireNotNull(files) { "file-backed restore storage is required" }
            val snapshot = store.read(filePointer(value))
            val content = Json.parseToJsonElement(snapshot.manifestJson).jsonObject
            require(content["format"]?.jsonPrimitive?.int == 2)
            require(content["source_family"] == value["source_family"]) { "restore source family changed" }
            val media = snapshot.media.map { item ->
                // Reopen verifies actual bytes before a start/manifest/commit can be dispatched.
                // Upload sources independently reverify their own stream later.
                store.ownedFile(snapshot, item)
                PreparedRestoreMedia(item.clientUuid, store.open(snapshot, item),
                    DisasterRestoreMediaSpec(item.clientUuid, item.byteSize, item.sha256))
            }
            return decodeRestoreSnapshotContent(content, media, snapshot)
        }
        requireSchema13RestoreEntities(value.getValue("entities").jsonArray.map { raw -> raw.jsonObject.let {
            SyncEntity(it.getValue("type").jsonPrimitive.content, it.getValue("uuid").jsonPrimitive.content,
                it.getValue("payload").toString(), it.getValue("updated").jsonPrimitive.long,
                it["deleted"]?.jsonPrimitive?.longOrNull)
        } })
        val groups = value.getValue("groups").jsonArray.map { decodeImmutableMediaSpoolGroup(it.toString()) }
        val media = groups.flatMap { group -> group.items.map { item ->
            PreparedRestoreMedia(item.mediaUuid, spool.open(group.mutationId, item),
                DisasterRestoreMediaSpec(item.mediaUuid, item.byteSize, item.sha256))
        } }
        return decodeRestoreSnapshotContent(value, media)
    }

    suspend fun completedSummary(pointer: RestoreFileSnapshotPointer): DisasterRecoverySummary {
        val store = requireNotNull(files)
        val snapshot = store.read(pointer)
        val media = snapshot.media.map { item -> PreparedRestoreMedia(item.clientUuid, store.open(snapshot, item),
            DisasterRestoreMediaSpec(item.clientUuid, item.byteSize, item.sha256)) }
        return decodeRestoreSnapshotContent(Json.parseToJsonElement(snapshot.manifestJson).jsonObject, media, snapshot)
            .use { it.summary }
    }

    /** Existing unknown starts may only replay the exact pre-dispatch nonsecret intent. */
    suspend fun requireStartIntent(requestId: String, intent: JsonObject) {
        val prior = read(requestId)["start_intent"]
        require(prior != null) {
            "旧开始请求缺少固定目标和参数，结果尚未确认；原快照已保留，需要受控修复"
        }
        require(prior == intent) {
            "恢复开始请求结果尚未确认，请使用原服务器、管理员称呼和设备称呼继续"
        }
    }

    suspend fun bind(pointer: RestoreFileSnapshotPointer, familyId: String, startIntent: JsonObject? = null) {
        val snapshot = requireNotNull(files).read(pointer)
        val content = Json.parseToJsonElement(snapshot.manifestJson).jsonObject
        require(content["source_family"]?.jsonPrimitive?.content == familyId) { "恢复来源家庭发生变化" }
        val value = buildJsonObject {
            put("format", 2); put("request", pointer.requestId); put("phase", "prepared")
            put("source_family", familyId); put("manifest_sha256", pointer.manifestSha256)
            put("manifest_bytes", pointer.manifestByteSize)
            startIntent?.let { put("start_intent", it) }
        }
        transactions.run {
            val old = cache.getTransportJournal(key(pointer.requestId))
            if (old == null) cache.putTransportJournal(key(pointer.requestId), value.toString(), 0)
            else require(filePointer(Json.parseToJsonElement(old.payloadJson).jsonObject) == pointer) {
                "restore snapshot binding changed"
            }
        }
    }

    private fun filePointer(value: JsonObject) = RestoreFileSnapshotPointer(
        value.getValue("request").jsonPrimitive.content,
        value.getValue("manifest_sha256").jsonPrimitive.content,
        value.getValue("manifest_bytes").jsonPrimitive.long,
    )

    suspend fun read(requestId: String): JsonObject {
        val row = requireNotNull(cache.getTransportJournal(key(requestId))) {
            "旧恢复批次缺少不可变快照，请取消后重新开始；已提交批次需要受控修复"
        }
        return Json.parseToJsonElement(row.payloadJson).jsonObject.also {
            require(it["format"]?.jsonPrimitive?.int in setOf(1, 2) && it["request"]?.jsonPrimitive?.content == requestId)
        }
    }

    suspend fun cancel(requestId: String) = enqueueRetirement(requestId, unavailable = false)

    /** An unavailable capability is not evidence that a dispatched commit was rejected. */
    suspend fun retireUnavailable(requestId: String) = enqueueRetirement(requestId, unavailable = true)

    private suspend fun enqueueRetirement(requestId: String, unavailable: Boolean) {
        if (!exists(requestId)) return // Legacy checkpoint: no invented snapshot ownership.
        transactions.run {
            val value = read(requestId)
            require(value["target"] == null) { "已激活恢复不能取消本机权威切换" }
            if (unavailable) {
                require(value["phase"]?.jsonPrimitive?.content in setOf("prepared", "retiring")) {
                    "家庭恢复提交结果尚未确认，请保留原恢复信息并重试"
                }
            }
            val ids = value.getValue("groups").jsonArray.map {
                decodeImmutableMediaSpoolGroup(it.toString()).mutationId
            }
            val previous = cache.getTransportJournal(RestoreArtifactRetirement.KEY)?.let {
                Json.parseToJsonElement(it.payloadJson).jsonObject
            }
            val previousIds = previous?.get("groups")?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            previous?.get("snapshot_key")?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() && it != key(requestId) }
                ?.let { cache.deleteTransportJournal(it) }
            // This local terminal decision and artifact ownership share one Room commit.
            // A crash before preference cleanup must never make this snapshot resumable again.
            cache.putTransportJournal(key(requestId),
                JsonObject(value + ("phase" to JsonPrimitive("retiring"))).toString(), 0)
            cache.putTransportJournal(RestoreArtifactRetirement.KEY,
                RestoreArtifactRetirement.encode(key(requestId), ids + previousIds), 0)
        }
    }

    suspend fun isRetiring(requestId: String): Boolean =
        cache.getTransportJournal(key(requestId))?.let {
            Json.parseToJsonElement(it.payloadJson).jsonObject["phase"]?.jsonPrimitive?.content == "retiring"
        } ?: false

    suspend fun committed(requestId: String, session: SyncSession) {
        val value = read(requestId)
        val target = buildJsonObject {
            put("family", session.familyId); put("membership", session.membershipId)
            put("device", session.deviceId); put("generation", session.pullGeneration)
            put("endpoint", session.baseUrl)
        }
        val previous = value["target"]
        require(previous == null || previous == target) { "restore committed authority changed on replay" }
        cache.putTransportJournal(key(requestId), JsonObject(value + mapOf(
            "target" to target,
            "phase" to (if (value["phase"]?.jsonPrimitive?.content == "switched") JsonPrimitive("switched") else JsonPrimitive("committed")),
        )).toString(), 0)
    }

    suspend fun matchesTarget(requestId: String, session: SyncSession): Boolean {
        val target = read(requestId)["target"] as? JsonObject ?: return false
        return target["family"]?.jsonPrimitive?.content == session.familyId &&
            target["membership"]?.jsonPrimitive?.content == session.membershipId &&
            target["device"]?.jsonPrimitive?.content == session.deviceId &&
            target["generation"]?.jsonPrimitive?.content == session.pullGeneration &&
            target["endpoint"]?.jsonPrimitive?.content == session.baseUrl
    }

    suspend fun phase(requestId: String, phase: String) {
        val value = read(requestId)
        cache.putTransportJournal(key(requestId), JsonObject(value + ("phase" to JsonPrimitive(phase))).toString(), 0)
    }

    suspend fun isSwitched(requestId: String): Boolean = read(requestId)["phase"]?.jsonPrimitive?.content == "switched"

    companion object { fun key(requestId: String) = "restore-authority-v1:$requestId" }
}
