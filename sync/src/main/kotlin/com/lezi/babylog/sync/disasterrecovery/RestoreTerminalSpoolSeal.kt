package com.lezi.babylog.sync.disasterrecovery

import com.lezi.babylog.core.database.causal.CausalTransportJournalEntity
import com.lezi.babylog.core.database.causal.frozenMediaSpoolCacheKey
import com.lezi.babylog.sync.engine.CausalMediaSettlementPhase
import com.lezi.babylog.sync.media.*
import com.lezi.babylog.sync.session.SyncSession
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.*

internal data class RestoreTerminalTarget(
    val endpoint: String, val family: String, val membership: String, val device: String, val generation: String,
) {
    fun json() = buildJsonObject {
        put("endpoint", endpoint); put("family", family); put("membership", membership)
        put("device", device); put("generation", generation)
    }
    companion object {
        fun from(session: SyncSession) = RestoreTerminalTarget(session.baseUrl, session.familyId,
            session.membershipId, session.deviceId, session.pullGeneration)
        fun decode(value: JsonObject): RestoreTerminalTarget {
            require(value.keys == setOf("endpoint", "family", "membership", "device", "generation"))
            return RestoreTerminalTarget(value.text("endpoint"), value.text("family"), value.text("membership"),
                value.text("device"), value.text("generation"))
        }
    }
}

internal data class RestoreTerminalReplacement(
    val uuid: String,
    val role: CausalMediaRole,
    val sha256: String,
    val byteSize: Long,
    val mime: String?,
    val width: Int?,
    val height: Int?,
    val path: String,
    val localUri: String,
    val sourceEvidence: String,
    val expectedEvidence: String,
    val markerEpoch: Long,
) {
    val markerKey: String get() = "restored-media-bytes-v1:$uuid"
}

/** Terminal cleanup only: never contains old mutation bodies, receipts, credentials or authority. */
internal data class RestoreTerminalSpoolSeal(
    val group: ImmutableMediaSpoolGroup,
    val originalJournalKey: String,
    val originalEpoch: Long,
    val originalDigest: String,
    val rootType: String,
    val rootUuid: String,
    val requestHash: String,
    val terminalPhase: CausalMediaSettlementPhase,
    val stableVersion: String,
    val pointer: RestoreFileSnapshotPointer,
    val batchId: String,
    val target: RestoreTerminalTarget,
    val sourceRootEvidence: String,
    val expectedRootEvidence: String,
    val replacements: List<RestoreTerminalReplacement>,
    val deleting: Boolean = false,
) {
    val key: String get() = PREFIX + group.mutationId
    val baseline: String get() = RestoreAuthority.baseline(batchId, rootType, rootUuid)

    fun encode(): String {
        validate()
        return buildJsonObject {
            put("contract", CONTRACT); put("phase", if (deleting) "deleting" else "retained")
            put("original_key", originalJournalKey); put("original_epoch", originalEpoch)
            put("original_digest", originalDigest); put("root_type", rootType); put("root_uuid", rootUuid)
            put("request_hash", requestHash); put("terminal_phase", terminalPhase.wireName)
            put("stable_version", stableVersion); put("manifest", Json.parseToJsonElement(encodeImmutableMediaSpoolGroup(group)))
            put("request", pointer.requestId); put("manifest_sha256", pointer.manifestSha256)
            put("manifest_bytes", pointer.manifestByteSize); put("batch", batchId); put("target", target.json())
            put("source_evidence", sourceRootEvidence); put("expected_evidence", expectedRootEvidence)
            put("replacements", JsonArray(replacements.map { item -> buildJsonObject {
                put("uuid", item.uuid); put("role", item.role.wireName); put("sha256", item.sha256)
                put("bytes", item.byteSize); put("mime", item.mime?.let(::JsonPrimitive) ?: JsonNull)
                put("width", item.width?.let(::JsonPrimitive) ?: JsonNull)
                put("height", item.height?.let(::JsonPrimitive) ?: JsonNull)
                put("path", item.path); put("local_uri", item.localUri); put("source_evidence", item.sourceEvidence)
                put("expected_evidence", item.expectedEvidence); put("marker_epoch", item.markerEpoch)
            } }))
        }.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) }
    }

    private fun validate() {
        decodeImmutableMediaSpoolGroup(encodeImmutableMediaSpoolGroup(group))
        require(originalJournalKey == frozenMediaSpoolCacheKey(group.mutationId) && originalEpoch >= 0)
        require(rootType in RestoreAuthority.rootTypes)
        require(group.items.all { it.role == CausalMediaPolicy.roleForEntityType(rootType) }); uuid(rootUuid); uuid(batchId); uuid(pointer.requestId)
        require(pointer.manifestByteSize > 0 && pointer.manifestByteSize <= Int.MAX_VALUE.toLong())
        listOf(originalDigest, requestHash, pointer.manifestSha256, sourceRootEvidence, expectedRootEvidence).forEach(::digest)
        require(terminalPhase.cleanupEligible && stableVersion.isNotBlank() && stableVersion.length <= 512)
        RestoreTerminalTarget.decode(target.json())
        require(replacements.map { it.uuid } == group.items.map { it.mediaUuid })
        replacements.forEach { item ->
            require(item.role == group.items.single { it.mediaUuid == item.uuid }.role)
            listOf(item.sha256, item.sourceEvidence, item.expectedEvidence).forEach(::digest)
            require(item.byteSize > 0 && item.markerEpoch >= 0)
            requireCanonicalMediaMime(item.mime)
            require(item.width == null || item.width > 0); require(item.height == null || item.height > 0)
            require(File(item.path).isAbsolute && item.path.length <= 4096)
        }
    }

    companion object {
        const val PREFIX = "restore-terminal-spool-cleanup-v1:"
        private const val CONTRACT = "restore_terminal_spool_cleanup_v1"
        private const val MAX_BYTES = 128 * 1024
        fun decode(row: CausalTransportJournalEntity): RestoreTerminalSpoolSeal {
            require(row.payloadJson.toByteArray(Charsets.UTF_8).size <= MAX_BYTES && row.contentEpoch == 0L)
            val value = Json.parseToJsonElement(row.payloadJson).jsonObject
            require(value.keys == setOf("contract", "phase", "original_key", "original_epoch", "original_digest",
                "root_type", "root_uuid", "request_hash", "terminal_phase", "stable_version", "manifest",
                "request", "manifest_sha256", "manifest_bytes", "batch", "target", "source_evidence", "expected_evidence", "replacements"))
            require(value.text("contract") == CONTRACT)
            val phase = value.text("phase"); require(phase in setOf("retained", "deleting"))
            val seal = RestoreTerminalSpoolSeal(
                decodeImmutableMediaSpoolGroup(value.getValue("manifest").toString()),
                value.text("original_key"), value.long("original_epoch"), value.text("original_digest"),
                value.text("root_type"), value.text("root_uuid"), value.text("request_hash"),
                CausalMediaSettlementPhase.parse(value.text("terminal_phase")), value.text("stable_version"),
                RestoreFileSnapshotPointer(value.text("request"), value.text("manifest_sha256"), value.long("manifest_bytes")),
                value.text("batch"), RestoreTerminalTarget.decode(value.getValue("target").jsonObject),
                value.text("source_evidence"), value.text("expected_evidence"),
                value.getValue("replacements").jsonArray.map { raw -> raw.jsonObject.let { item ->
                    require(item.keys == setOf("uuid", "role", "sha256", "bytes", "mime", "width", "height", "path", "local_uri",
                        "source_evidence", "expected_evidence", "marker_epoch"))
                    RestoreTerminalReplacement(item.text("uuid"), CausalMediaRole.entries.single { it.wireName == item.text("role") },
                        item.text("sha256"), item.long("bytes"), parseCanonicalMediaMime(item["mime"]),
                        item.nullableInt("width"), item.nullableInt("height"), item.text("path"), item.text("local_uri"),
                        item.text("source_evidence"), item.text("expected_evidence"), item.long("marker_epoch"))
                } }, phase == "deleting",
            )
            seal.validate(); require(row.journalKey == seal.key)
            return seal
        }
    }
}

internal fun terminalEvidenceDigest(raw: String): String = MessageDigest.getInstance("SHA-256")
    .digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

private fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.let {
    require(it.isString && it.content.isNotBlank() && it.content.length <= 4096); it.content
} ?: error("terminal spool seal missing $key")
private fun JsonObject.long(key: String): Long = (get(key) as? JsonPrimitive)?.let {
    require(!it.isString); requireNotNull(it.longOrNull)
} ?: error("terminal spool seal missing $key")
private fun JsonObject.nullableInt(key: String): Int? = when (val value = get(key)) {
    JsonNull -> null
    is JsonPrimitive -> { require(!value.isString); requireNotNull(value.intOrNull) }
    else -> error("terminal spool seal missing $key")
}
private fun uuid(value: String) { require(runCatching { UUID.fromString(value).toString() }.getOrNull() == value) }
private fun digest(value: String) { require(value.matches(Regex("[0-9a-f]{64}"))) }
