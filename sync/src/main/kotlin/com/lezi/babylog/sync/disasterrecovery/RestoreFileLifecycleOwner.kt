package com.lezi.babylog.sync.disasterrecovery

import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.MediaAssetDao
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.causal.CausalTransportJournalEntity
import com.lezi.babylog.sync.backend.DisasterRestoreStatus
import com.lezi.babylog.sync.session.DisasterRestoreCheckpoint
import com.lezi.babylog.sync.media.SyncMediaFileStore
import com.lezi.babylog.sync.media.ReferenceAwareMediaFileCleanup
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import com.lezi.babylog.core.database.causal.ConflictSnapshotCacheDao
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

internal enum class RestoreFileLifecycleFaultPoint { AfterPruneOwnerCommitted, AfterConfirmedCancellationCommitted }

/** Sole owner of file/Room retirement ordering; no caller-supplied 'safe to delete' flag. */
internal class RestoreFileLifecycleOwner(
    private val cache: ConflictSnapshotCacheDao,
    private val files: RestoreFileSnapshotStore,
    private val transactions: DatabaseTransactionRunner,
    private val media: MediaAssetDao,
    private val mediaFiles: SyncMediaFileStore,
    private val cleanup: ReferenceAwareMediaFileCleanup,
    private val babies: BabyDao,
    private val currentFamilyId: suspend () -> String,
    private val verifiedBytes: (Long) -> Unit = {},
    private val fault: (RestoreFileLifecycleFaultPoint) -> Unit = {},
) {
    /** Serializes authority publication with every prior request's retirement/reclamation. */
    suspend fun <T> withAuthorityTransition(
        snapshot: DisasterRecoverySnapshot,
        block: suspend (Map<String, MediaAssetEntity?>, Map<String, String>) -> T,
    ): T = gate.withLock {
        val fileSnapshot = snapshot.fileSnapshot
        if (fileSnapshot == null) return@withLock block(emptyMap(), emptyMap())
        val rows = transactions.run { fileSnapshot.media.associate { it.clientUuid to media.getByClientUuid(it.clientUuid) } }
        val owned = fileSnapshot.media.associate { it.clientUuid to files.ownedFile(fileSnapshot, it).path }
        cleanup.withOwnedPaths(rows.values.mapNotNull { it?.localUri } + owned.values) {
            val originalEvidence = snapshot.retirementVersions.filter { it.type == "media" }
                .associate { it.clientUuid to it.localEvidence }
            val items = fileSnapshot.media.associateBy { it.clientUuid }
            val acceptedPaths = linkedMapOf<String, String>()
            rows.forEach { (uuid, row) ->
                if (row != null && row.deletedAt == null) {
                    val current = mediaFiles.restoreSourceFile(row.localUri)
                    if (current != null) {
                        require(matchesCurrentBytes(current, items.getValue(uuid))) {
                            "服务器已提交恢复，本机同一照片的内容随后发生变化；原批次和两份照片已保留，请修复后重试本机激活"
                        }
                        acceptedPaths[uuid] = row.localUri
                    } else {
                        require(snapshot.evidenceVersion == 2 && originalEvidence[uuid] == mediaEvidence(row)) {
                            "服务器已提交恢复，本机后续修改的照片来源尚待修复；原恢复批次和照片已保留，请修复后重试"
                        }
                        acceptedPaths[uuid] = owned.getValue(uuid)
                    }
                }
            }
            block(rows, acceptedPaths)
        }
    }

    /** The switched Room target and joined session establish terminal eligibility internally. */
    suspend fun retirePublished(requestId: String) = gate.withLock {
        val key = RestoreSnapshotJournal.key(requestId)
        val captured = requireNotNull(cache.getTransportJournal(key))
        val value = Json.parseToJsonElement(captured.payloadJson).jsonObject
        require(value["format"]?.jsonPrimitive?.int == 2)
        require(value["phase"]?.jsonPrimitive?.content == "switched")
        require(value.getValue("target").jsonObject.getValue("family").jsonPrimitive.content == currentFamilyId())
        val active = RestoreFileSnapshotPointer(requestId, value.getValue("manifest_sha256").jsonPrimitive.content,
            value.getValue("manifest_bytes").jsonPrimitive.long)
        val previous = cache.getTransportJournal(ownerKey(requestId))
        if (previous != null) {
            val selected = decode(Json.parseToJsonElement(previous.payloadJson).jsonObject)
            val evidence = files.readRetirement(selected)
            require(evidence.activePointer == active && evidence.reason == RestoreFileRetirementReason.Published)
            files.finishRetirement(selected) { isCurrent(it, previous.payloadJson) }
            return@withLock
        }
        val retired = files.prepareRetirement(active, RestoreFileRetirementReason.Published)
        val owner = encode(retired, RestoreFileRetirementReason.Published, "retired")
        transactions.run {
            check(cache.getTransportJournal(key) == captured) { "恢复状态已变化" }
            cache.putTransportJournal(ownerKey(requestId), owner, 0)
            // Keep switched target identity until preference completion has crossed its crash boundary.
        }
        files.finishRetirement(retired) { isCurrent(it, owner) }
    }

    /** Explicit local cancellation is allowed only before any Room-bound start dispatch. */
    suspend fun retireUndispatched(requestId: String) = gate.withLock { retireUndispatchedLocked(requestId) }

    suspend fun recoverUndispatchedRetirement(requestId: String): Boolean = gate.withLock {
        if (cache.getTransportJournal(RestoreSnapshotJournal.key(requestId)) != null) return@withLock false
        val selected = cache.getTransportJournal(ownerKey(requestId))
        if (selected != null) {
            val owner = files.readRetirement(decode(Json.parseToJsonElement(selected.payloadJson).jsonObject))
            if (owner.reason != RestoreFileRetirementReason.UndispatchedAbandon) return@withLock false
        } else {
            val pointer = files.completedForLocalRetirement(requestId) ?: return@withLock false
            if (files.preparedRetirement(pointer)?.reason != RestoreFileRetirementReason.UndispatchedAbandon)
                return@withLock false
        }
        retireUndispatchedLocked(requestId)
        true
    }

    private suspend fun retireUndispatchedLocked(requestId: String) {
        require(cache.getTransportJournal(RestoreSnapshotJournal.key(requestId)) == null) {
            "恢复开始请求结果尚未确认，请先重试原开始请求，再取消原批次；原数据已保留"
        }
        val previous = cache.getTransportJournal(ownerKey(requestId))
        if (previous != null) {
            val pointer = decode(Json.parseToJsonElement(previous.payloadJson).jsonObject)
            require(files.readRetirement(pointer).reason == RestoreFileRetirementReason.UndispatchedAbandon)
            files.finishRetirement(pointer) { isCurrent(it, previous.payloadJson) }
            return
        }
        val active = files.completedForLocalRetirement(requestId)
        if (active == null) {
            // The store itself requires exact guarded source equivalence before discarding incomplete bytes.
            files.discard(requestId)
            return
        }
        val content = Json.parseToJsonElement(files.read(active).manifestJson).jsonObject
        val family = content.getValue("source_family").jsonPrimitive.content
        require(currentFamilyId() == family) { "恢复来源家庭已变化，原快照已保留" }
        adoptMissingUnchangedSources(active, family)
        val retired = files.prepareRetirement(active, RestoreFileRetirementReason.UndispatchedAbandon)
        val encoded = encode(retired, RestoreFileRetirementReason.UndispatchedAbandon, "retired")
        transactions.run {
            check(cache.getTransportJournal(RestoreSnapshotJournal.key(requestId)) == null &&
                cache.getTransportJournal(ownerKey(requestId)) == null) { "恢复状态已变化" }
            cache.putTransportJournal(ownerKey(requestId), encoded, 0)
        }
        files.finishRetirement(retired) { isCurrent(it, encoded) }
    }

    suspend fun retirePrepared(requestId: String, reason: RestoreFileRetirementReason) = gate.withLock {
        retirePreparedLocked(requestId, reason)
    }

    /** Only the exact server cancellation can settle a commit whose response was lost. */
    suspend fun retireAfterConfirmedCancellation(
        checkpoint: DisasterRestoreCheckpoint,
        currentCheckpoint: suspend () -> DisasterRestoreCheckpoint?,
        cancel: suspend () -> DisasterRestoreStatus,
    ) = gate.withLock {
        val key = RestoreSnapshotJournal.key(checkpoint.startRequestId)
        val captured = requireNotNull(cache.getTransportJournal(key))
        val value = Json.parseToJsonElement(captured.payloadJson).jsonObject
        require(value["format"]?.jsonPrimitive?.int == 2 &&
            value["request"]?.jsonPrimitive?.content == checkpoint.startRequestId)
        require(value["phase"]?.jsonPrimitive?.content in setOf("prepared", "commit_uncertain") &&
            value["target"] == null && checkpoint.status != "committed") {
            "已提交恢复不能取消本机权威切换"
        }
        require(value["source_family"]?.jsonPrimitive?.content == checkpoint.familyId &&
            currentFamilyId() == checkpoint.familyId && currentCheckpoint() == checkpoint) {
            "恢复来源或批次已变化，原快照已保留"
        }
        val confirmation = buildJsonObject {
            put("request", checkpoint.startRequestId); put("batch", checkpoint.batchId)
            put("manifest_request", checkpoint.manifestRequestId); put("commit_request", checkpoint.commitRequestId)
            put("family", checkpoint.familyId); put("origin", checkpoint.endpoint.origin)
            put("trust", checkpoint.endpoint.trustMode.name)
            put("spki", checkpoint.endpoint.spkiSha256?.let(::JsonPrimitive) ?: JsonNull)
            put("previous_phase", value.getValue("phase"))
        }
        requireCancellationAuthority(value, confirmation)
        val cancelled = cancel()
        require(cancelled.batchId == checkpoint.batchId && cancelled.status == "cancelled") {
            "服务器尚未确认取消原恢复批次，请保留恢复信息"
        }
        transactions.run {
            check(cache.getTransportJournal(key) == captured && currentCheckpoint() == checkpoint &&
                currentFamilyId() == checkpoint.familyId) { "恢复状态已变化，原快照已保留" }
            // Persist the positive result before any file retirement. A crash must not replay commit.
            cache.putTransportJournal(key, JsonObject(value + mapOf(
                "phase" to JsonPrimitive("cancelled"), "confirmed_cancellation" to confirmation,
            )).toString(), captured.contentEpoch)
        }
        fault(RestoreFileLifecycleFaultPoint.AfterConfirmedCancellationCommitted)
        retireConfirmedCancellationLocked(checkpoint.startRequestId)
    }

    /** Positive cancellation and prepared compact receipts are terminal decisions, never permission to recapture. */
    suspend fun recoverPreparedRetirement(requestId: String): Boolean = gate.withLock {
        val row = cache.getTransportJournal(RestoreSnapshotJournal.key(requestId)) ?: return@withLock false
        val value = Json.parseToJsonElement(row.payloadJson).jsonObject
        if (value["format"]?.jsonPrimitive?.int != 2 || value["target"] != null) return@withLock false
        if (value["phase"]?.jsonPrimitive?.content == "cancelled") {
            retireConfirmedCancellationLocked(requestId)
            return@withLock true
        }
        if (value["phase"]?.jsonPrimitive?.content != "prepared") return@withLock false
        val active = RestoreFileSnapshotPointer(requestId, value.getValue("manifest_sha256").jsonPrimitive.content,
            value.getValue("manifest_bytes").jsonPrimitive.long)
        val prepared = files.preparedRetirement(active) ?: return@withLock false
        require(prepared.reason == RestoreFileRetirementReason.Cancelled ||
            prepared.reason == RestoreFileRetirementReason.Unavailable)
        retirePreparedLocked(requestId, prepared.reason)
        true
    }

    private suspend fun retirePreparedLocked(requestId: String, reason: RestoreFileRetirementReason) {
        require(reason == RestoreFileRetirementReason.Cancelled || reason == RestoreFileRetirementReason.Unavailable)
        val key = RestoreSnapshotJournal.key(requestId)
        val captured = requireNotNull(cache.getTransportJournal(key))
        val value = Json.parseToJsonElement(captured.payloadJson).jsonObject
        require(value["format"]?.jsonPrimitive?.int == 2)
        require(value["phase"]?.jsonPrimitive?.content == "prepared" && value["target"] == null) {
            "家庭恢复提交结果尚未确认，请保留原恢复信息并重试"
        }
        retireUncommittedLocked(requestId, reason, captured, value)
    }

    private suspend fun retireConfirmedCancellationLocked(requestId: String) {
        val captured = requireNotNull(cache.getTransportJournal(RestoreSnapshotJournal.key(requestId)))
        val value = Json.parseToJsonElement(captured.payloadJson).jsonObject
        require(value["format"]?.jsonPrimitive?.int == 2 &&
            value["request"]?.jsonPrimitive?.content == requestId &&
            value["phase"]?.jsonPrimitive?.content == "cancelled" && value["target"] == null)
        requireCancellationAuthority(value, value.getValue("confirmed_cancellation").jsonObject)
        retireUncommittedLocked(requestId, RestoreFileRetirementReason.Cancelled, captured, value)
    }

    private fun requireCancellationAuthority(value: JsonObject, confirmation: JsonObject) {
        require(confirmation["request"] == value["request"] &&
            confirmation["family"] == value["source_family"] &&
            confirmation["previous_phase"]?.jsonPrimitive?.content in setOf("prepared", "commit_uncertain"))
        listOf("batch", "manifest_request", "commit_request", "origin", "trust").forEach {
            require(!confirmation[it]?.jsonPrimitive?.content.isNullOrBlank())
        }
        // Older prepared snapshots may not have a start intent; present evidence must match exactly.
        value["start_intent"]?.jsonObject?.let { intent ->
            require(listOf("family", "origin", "trust", "spki").all { intent[it] == confirmation[it] }) {
                "恢复目标与原开始请求不一致，原快照已保留"
            }
        }
    }

    private suspend fun retireUncommittedLocked(
        requestId: String,
        reason: RestoreFileRetirementReason,
        captured: CausalTransportJournalEntity,
        value: JsonObject,
    ) {
        val key = RestoreSnapshotJournal.key(requestId)
        val familyId = value.getValue("source_family").jsonPrimitive.content
        require(currentFamilyId() == familyId) { "恢复来源家庭已变化，原快照已保留" }
        val active = RestoreFileSnapshotPointer(requestId, value.getValue("manifest_sha256").jsonPrimitive.content,
            value.getValue("manifest_bytes").jsonPrimitive.long)
        adoptMissingUnchangedSources(active, familyId)
        val retired = files.prepareRetirement(active, reason)
        val owner = encode(retired, reason, "retired")
        transactions.run {
            check(cache.getTransportJournal(key) == captured) { "恢复状态已变化" }
            cache.putTransportJournal(ownerKey(requestId), owner, 0)
            // A bounded tombstone survives the preference-clear crash boundary.
            cache.putTransportJournal(key, JsonObject(value + ("phase" to JsonPrimitive("retiring"))).toString(), 0)
        }
        files.finishRetirement(retired) { isCurrent(it, owner) }
    }

    /** Exact original evidence permits technical path repair, never a byte-generation guess. */
    private suspend fun adoptMissingUnchangedSources(active: RestoreFileSnapshotPointer, familyId: String) {
        val snapshot = files.read(active)
        val content = Json.parseToJsonElement(snapshot.manifestJson).jsonObject
        require(content["source_family"]?.jsonPrimitive?.content == familyId)
        // Old diagnostics-based equality is readable history, never proof for automatic path adoption.
        if (content["evidence_version"]?.jsonPrimitive?.int != 2) return
        val versions = content.getValue("versions").jsonArray.map { it.jsonObject }
            .filter { it["type"]?.jsonPrimitive?.content == "media" }
        require(versions.map { it.getValue("uuid").jsonPrimitive.content }.distinct().size == versions.size)
        val evidence = versions.associate { it.getValue("uuid").jsonPrimitive.content to
            it["evidence"]?.jsonPrimitive?.contentOrNull }
        for (item in snapshot.media) {
            val captured = media.getByClientUuid(item.clientUuid) ?: continue
            if (captured.deletedAt != null || mediaFiles.readableFile(captured.localUri) != null ||
                evidence[item.clientUuid] != mediaEvidence(captured)) continue
            val owned = files.ownedFile(snapshot, item)
            cleanup.withOwnedPaths(listOf(captured.localUri, owned.path)) {
                if (mediaFiles.readableFile(captured.localUri) != null) return@withOwnedPaths
                transactions.run {
                    if (currentFamilyId() != familyId || media.getByClientUuid(item.clientUuid) != captured) return@run
                    media.update(captured.copy(localUri = owned.path))
                    captured.babyId?.let { id -> babies.get(id)?.let { baby ->
                        if (baby.avatarMediaUuid == captured.clientUuid && baby.avatarPath == captured.localUri)
                            babies.update(baby.copy(avatarPath = owned.path))
                    } }
                }
            }
        }
    }

    private fun mediaEvidence(row: MediaAssetEntity): String? = CapturedRestoreRows(
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), listOf(row),
    ).exactEvidence("media", row.clientUuid)

    /** Snapshot exact ownership before a generic clear; the clear barrier prevents new publication. */
    suspend fun ownedArtifactPaths(): Set<String> = gate.withLock {
        buildSet {
            for (row in cache.listRestoreFileOwners()) {
                val value = Json.parseToJsonElement(row.payloadJson).jsonObject
                val pointer = decode(value)
                require(row.journalKey == ownerKey(pointer.requestId)) { "恢复文件所有权键与请求不一致" }
                if (value["phase"]?.jsonPrimitive?.content == "deleting") continue
                val ownership = files.readRetirement(pointer)
                ownership.originalMedia.forEach { add(ownership.ownedPath(it).path) }
            }
        }
    }

    /** Only terminal events scan retained inventories; ordinary operation recovery resumes unfinished work. */
    suspend fun reclaimRetired(force: Boolean = false) = gate.withLock {
        for (row in cache.listRestoreFileOwners()) {
            val value = Json.parseToJsonElement(row.payloadJson).jsonObject
            val pointer = decode(value)
            require(row.journalKey == ownerKey(pointer.requestId)) { "恢复文件所有权键与请求不一致" }
            val phase = value.getValue("phase").jsonPrimitive.content
            if (phase == "retained" && !force) continue
            if (phase != "deleting") {
                if (!files.finishRetirement(pointer) { isCurrent(it, row.payloadJson) }) continue
                val ownership = files.readRetirement(pointer)
                val capturedRows = transactions.run {
                    ownership.originalMedia.associate { it.clientUuid to media.getByClientUuid(it.clientUuid) }
                }
                val removable = ownership.media.filter { item ->
                    replacementPresent(capturedRows[item.clientUuid], item, ownership.ownedPath(item))
                }.mapTo(mutableSetOf()) { it.clientUuid }
                val remaining = ownership.media.mapTo(mutableSetOf()) { it.clientUuid } - removable
                val next = files.preparePrune(pointer, remaining)
                val encoded = encode(next, ownership.reason, "pruning")
                val committed = transactions.run {
                    if (cache.getTransportJournal(row.journalKey) != row || capturedRows.any { (uuid, expected) ->
                            media.getByClientUuid(uuid) != expected
                        }) false else {
                        cache.putTransportJournal(row.journalKey, encoded, row.contentEpoch)
                        true
                    }
                }
                if (!committed) continue
                fault(RestoreFileLifecycleFaultPoint.AfterPruneOwnerCommitted)
                val originalByUuid = ownership.originalMedia.associateBy { it.clientUuid }
                val removed = files.finishPrune(next, { isCurrent(it, encoded) }) { path, unlink ->
                    val item = originalByUuid.getValue(path.name.removeSuffix(".media"))
                    val expected = media.getByClientUuid(item.clientUuid)
                    cleanup.reclaimOwnedPath(path.path, expected?.localUri,
                        logicalDeletionOfMediaUuid = item.clientUuid.takeIf { expected == null || expected.deletedAt != null },
                        verifyReplacement = {
                        transactions.run { media.getByClientUuid(item.clientUuid) == expected } &&
                            replacementPresent(expected, item, path)
                    }, unlink = unlink)
                }
                if (!removed) continue // The durable pruning phase retries interrupted or holder-blocked omissions.
                transactions.run {
                    if (isCurrent(next, encoded)) cache.putTransportJournal(row.journalKey,
                        encode(next, ownership.reason, if (remaining.isEmpty()) "deleting" else "retained"), row.contentEpoch)
                }
                if (remaining.isNotEmpty()) continue
            }
            val deleting = cache.getTransportJournal(row.journalKey) ?: continue
            val current = Json.parseToJsonElement(deleting.payloadJson).jsonObject
            if (current["phase"]?.jsonPrimitive?.content != "deleting") continue
            val selected = decode(current)
            require(row.journalKey == ownerKey(selected.requestId)) { "恢复文件所有权键与请求不一致" }
            // This durable phase is written only after every owned media file was safely removed.
            files.discard(selected.requestId)
            transactions.run {
                if (cache.getTransportJournal(row.journalKey) == deleting) {
                    cache.deleteTransportJournal(row.journalKey)
                    val activeKey = RestoreSnapshotJournal.key(selected.requestId)
                    val active = cache.getTransportJournal(activeKey)?.payloadJson?.let { Json.parseToJsonElement(it).jsonObject }
                    if (active?.get("phase")?.jsonPrimitive?.content == "retiring") cache.deleteTransportJournal(activeKey)
                }
            }
        }
    }

    private suspend fun matchesCurrentBytes(file: File, item: RestoreFileSnapshotMedia): Boolean = withContext(Dispatchers.IO) {
        var component: java.nio.file.Path? = file.toPath()
        while (component != null) {
            if (java.nio.file.Files.isSymbolicLink(component)) return@withContext false
            component = component.parent
        }
        if (!java.nio.file.Files.isRegularFile(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) ||
            file.length() != item.byteSize) return@withContext false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) { digest.update(buffer, 0, count); verifiedBytes(count.toLong()) }
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) } == item.sha256
    }

    private suspend fun replacementPresent(row: MediaAssetEntity?, item: RestoreFileSnapshotMedia, owned: File): Boolean {
        if (row == null || row.deletedAt != null) return true
        return withContext(Dispatchers.IO) {
            val replacement = mediaFiles.readableFile(row.localUri) ?: return@withContext false
            // Any file inside this request is still this owner's responsibility, never its replacement.
            if (replacement.canonicalFile.toPath().startsWith(requireNotNull(owned.parentFile).canonicalFile.toPath())) return@withContext false
            if (replacement.length() != item.byteSize) return@withContext false
            val digest = MessageDigest.getInstance("SHA-256")
            replacement.inputStream().buffered().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) { digest.update(buffer, 0, count); verifiedBytes(count.toLong()) }
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) } == item.sha256
        }
    }

    private suspend fun isCurrent(pointer: RestoreFileRetirementPointer, encoded: String): Boolean =
        decode(Json.parseToJsonElement(encoded).jsonObject) == pointer &&
            cache.getTransportJournal(ownerKey(pointer.requestId))?.payloadJson == encoded

    private fun encode(pointer: RestoreFileRetirementPointer, reason: RestoreFileRetirementReason, phase: String) =
        buildJsonObject {
            put("format", 1); put("request", pointer.requestId); put("phase", phase)
            put("ownership_sha256", pointer.ownershipSha256); put("ownership_bytes", pointer.ownershipByteSize)
            put("reason", reason.wireName)
        }.toString()

    private fun decode(value: JsonObject): RestoreFileRetirementPointer {
        require(value["format"]?.jsonPrimitive?.int == 1)
        return RestoreFileRetirementPointer(value.getValue("request").jsonPrimitive.content,
            value.getValue("ownership_sha256").jsonPrimitive.content,
            value.getValue("ownership_bytes").jsonPrimitive.long)
    }

    companion object {
        private val gate = Mutex()
        fun ownerKey(requestId: String) = "restore-file-owner-v1:$requestId"
    }
}
