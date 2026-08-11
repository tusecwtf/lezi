package com.lezi.babylog.sync.engine

import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.causal.ConflictSnapshotCacheDao
import com.lezi.babylog.core.database.causal.frozenMediaSpoolCacheKey
import com.lezi.babylog.sync.backend.CausalCommitStatus
import com.lezi.babylog.sync.backend.CausalMediaItem
import com.lezi.babylog.sync.backend.CausalMediaPreimageReceipt
import com.lezi.babylog.sync.backend.CausalMutationUnit
import com.lezi.babylog.sync.backend.CausalProofUnit
import com.lezi.babylog.sync.media.ImmutableMediaSpool
import com.lezi.babylog.sync.media.ImmutableMediaSpoolGroup
import com.lezi.babylog.sync.media.ImmutableMediaSpoolItem
import com.lezi.babylog.sync.media.decodeImmutableMediaSpoolGroup
import com.lezi.babylog.sync.media.encodeImmutableMediaSpoolGroup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Durable H20 lifecycle; `commit_unknown` forbids abandonment and forces exact commit replay. */
internal enum class CausalMediaSettlementPhase(val wireName: String) {
    Pending("pending"),
    CommitUnknown("commit_unknown"),
    CleanupAccepted("cleanup_accepted"),
    CleanupMerged("cleanup_merged"),
    Branched("branched"),
    ;

    val cleanupEligible: Boolean
        get() = this == CleanupAccepted || this == CleanupMerged

    companion object {
        fun parse(raw: String): CausalMediaSettlementPhase = entries.singleOrNull {
            it.wireName == raw
        } ?: error("unknown causal media settlement phase")
    }
}

internal data class CausalMediaSettlementBinding(
    val mutationId: String,
    val entityType: String,
    val clientUuid: String,
    val contentEpoch: Long,
    val requestHash: String,
)

internal data class CausalMediaSettlementJournal(
    val binding: CausalMediaSettlementBinding,
    val mutation: CausalMutationUnit,
    val manifest: ImmutableMediaSpoolGroup,
    val receipts: List<CausalMediaPreimageReceipt>,
    val phase: CausalMediaSettlementPhase,
    val stableVersionId: String? = null,
    val conflictId: String? = null,
    val branchVersionId: String? = null,
)

/**
 * One deep owner for receipt persistence, unknown commit recovery and cleanup eligibility.
 * Product fact settlement stays in [CausalSettlement]'s surrounding Room transaction.
 */
internal class CausalMediaSettlementJournalOwner(
    private val cache: ConflictSnapshotCacheDao,
    private val spool: ImmutableMediaSpool,
    private val transactionRunner: DatabaseTransactionRunner,
) {
    suspend fun bind(
        mutation: CausalMutationUnit,
        contentEpoch: Long,
        requestHash: String,
        manifest: ImmutableMediaSpoolGroup,
    ): CausalMediaSettlementJournal = transactionRunner.run {
        require(mutation.media.isNotEmpty()) { "media settlement requires a non-empty manifest" }
        require(manifest.mutationId == mutation.mutationId) {
            "media settlement manifest mutation identity drift"
        }
        require(manifest.items.map(ImmutableMediaSpoolItem::toManifestIdentity) ==
            mutation.media.map { Triple(it.mediaUuid, it.sha256, it.byteSize) }
        ) { "media settlement mutation manifest drift" }
        val binding = CausalMediaSettlementBinding(
            mutationId = mutation.mutationId,
            entityType = mutation.entityType,
            clientUuid = mutation.clientUuid,
            contentEpoch = contentEpoch,
            requestHash = requestHash,
        ).also(::validateBinding)
        val row = requireNotNull(cache.getFrozenMediaSpoolManifest(mutation.mutationId)) {
            "media settlement lost its Room spool manifest"
        }
        val existing = decodeCausalMediaSettlementOrNull(row.snapshotJson)
        if (existing != null) {
            require(
                existing.binding == binding && existing.mutation == mutation &&
                    existing.manifest == manifest,
            ) {
                "media settlement durable binding drift"
            }
            return@run existing
        }
        require(decodeFrozenMediaSpoolManifest(row.snapshotJson) == manifest) {
            "media settlement Room manifest drift"
        }
        CausalMediaSettlementJournal(
            binding = binding,
            mutation = mutation,
            manifest = manifest,
            receipts = emptyList(),
            phase = CausalMediaSettlementPhase.Pending,
        ).also { journal -> persist(journal) }
    }

    suspend fun restoreUnsettled(
        entityType: String,
        clientUuid: String,
    ): CausalMediaSettlementJournal? {
        val matches = cache.listFrozenMediaSpoolManifests().mapNotNull { row ->
            val journal = decodeCausalMediaSettlementOrNull(row.snapshotJson) ?: return@mapNotNull null
            require(row.conflictId == frozenMediaSpoolCacheKey(journal.binding.mutationId)) {
                "media settlement storage key drift"
            }
            journal.takeIf {
                (it.phase == CausalMediaSettlementPhase.Pending ||
                    it.phase == CausalMediaSettlementPhase.CommitUnknown) &&
                    it.binding.entityType == entityType && it.binding.clientUuid == clientUuid
            }
        }
        require(matches.size <= 1) { "root has multiple unsettled media commits" }
        return matches.singleOrNull()
    }

    suspend fun preparedReceipt(
        mutationId: String,
        item: ImmutableMediaSpoolItem,
    ): CausalMediaPreimageReceipt? {
        val journal = loadRequired(mutationId)
        require(journal.manifest.items.singleOrNull { it.mediaUuid == item.mediaUuid } == item) {
            "media settlement item does not belong to its manifest"
        }
        return journal.receipts.singleOrNull { it.mediaUuid == item.mediaUuid }
            ?.also { validateReceipt(it, item) }
    }

    suspend fun recordPrepared(
        mutationId: String,
        receipt: CausalMediaPreimageReceipt,
    ): CausalMediaSettlementJournal = transactionRunner.run {
        val journal = loadRequired(mutationId)
        require(journal.phase == CausalMediaSettlementPhase.Pending) {
            "media receipt cannot be added after commit became unknown or terminal"
        }
        val item = journal.manifest.items.singleOrNull { it.mediaUuid == receipt.mediaUuid }
            ?: error("media receipt is foreign to its mutation manifest")
        validateReceipt(receipt, item)
        val existing = journal.receipts.singleOrNull { it.mediaUuid == receipt.mediaUuid }
        require(existing == null || existing == receipt) { "media receipt drift" }
        if (existing != null) return@run journal
        journal.copy(
            receipts = (journal.receipts + receipt).sortedBy(CausalMediaPreimageReceipt::mediaUuid),
        ).also { updated -> persist(updated) }
    }

    /** Must commit before the network commit begins; crash after this point replays commit first. */
    suspend fun markCommitUnknown(mutationId: String): CausalMediaSettlementJournal =
        transactionRunner.run {
            val journal = loadRequired(mutationId)
            require(
                journal.phase == CausalMediaSettlementPhase.Pending ||
                    journal.phase == CausalMediaSettlementPhase.CommitUnknown,
            ) { "media mutation is already terminal" }
            require(journal.receipts.map(CausalMediaPreimageReceipt::mediaUuid) ==
                journal.manifest.items.map(ImmutableMediaSpoolItem::mediaUuid)
            ) { "media commit cannot start before every durable receipt" }
            journal.manifest.items.forEach { item ->
                validateReceipt(
                    journal.receipts.single { it.mediaUuid == item.mediaUuid },
                    item,
                )
            }
            if (journal.phase == CausalMediaSettlementPhase.CommitUnknown) return@run journal
            journal.copy(phase = CausalMediaSettlementPhase.CommitUnknown)
                .also { updated -> persist(updated) }
        }

    /** Called inside the same Room transaction that settles the product fact. */
    suspend fun markTerminal(
        mutationId: String,
        result: CausalProofUnit,
    ): CausalMediaSettlementJournal {
        val journal = loadRequired(mutationId)
        require(result.mutationId == mutationId && result.requestHash == journal.binding.requestHash) {
            "media terminal does not bind the frozen mutation"
        }
        val phase = when (result.status) {
            CausalCommitStatus.ACCEPTED -> CausalMediaSettlementPhase.CleanupAccepted
            CausalCommitStatus.MERGED -> CausalMediaSettlementPhase.CleanupMerged
            CausalCommitStatus.BRANCHED -> CausalMediaSettlementPhase.Branched
            else -> error("non-terminal media result cannot settle spool")
        }
        require(
            journal.phase == CausalMediaSettlementPhase.CommitUnknown || journal.phase == phase,
        ) { "media terminal arrived before durable commit-attempt evidence" }
        val updated = journal.copy(
            phase = phase,
            stableVersionId = result.stableVersionId,
            conflictId = result.conflictId,
            branchVersionId = result.branchVersionId,
        )
        if (journal.phase == phase) {
            require(journal == updated) { "media terminal replay drift" }
            return journal
        }
        persist(updated)
        return updated
    }

    /** Filesystem first, then journal deletion; either crash window is idempotently recoverable. */
    suspend fun finishCleanup(mutationId: String): Boolean {
        val before = loadRequired(mutationId)
        if (!before.phase.cleanupEligible) return false
        spool.discardGroup(mutationId)
        transactionRunner.run {
            val current = loadRequired(mutationId)
            require(current == before) { "media cleanup journal changed while bytes were removed" }
            cache.deleteFrozenMediaSpoolManifest(mutationId)
        }
        return true
    }

    suspend fun finishPendingCleanups() {
        cache.listFrozenMediaSpoolManifests().forEach { row ->
            val journal = decodeCausalMediaSettlementOrNull(row.snapshotJson) ?: return@forEach
            if (journal.phase.cleanupEligible) finishCleanup(journal.binding.mutationId)
        }
    }

    private suspend fun loadRequired(mutationId: String): CausalMediaSettlementJournal {
        val row = requireNotNull(cache.getFrozenMediaSpoolManifest(mutationId)) {
            "media settlement journal is missing"
        }
        require(row.cachedAt >= 0L) { "media settlement epoch is invalid" }
        return requireNotNull(decodeCausalMediaSettlementOrNull(row.snapshotJson)) {
            "media settlement journal is not bound"
        }.also { journal ->
            require(journal.binding.mutationId == mutationId) {
                "media settlement storage key drift"
            }
        }
    }

    private suspend fun persist(journal: CausalMediaSettlementJournal) {
        validateJournal(journal)
        cache.putFrozenMediaSpoolManifest(
            mutationId = journal.binding.mutationId,
            canonicalManifestJson = encodeCausalMediaSettlement(journal),
            contentEpoch = journal.binding.contentEpoch,
        )
    }
}

internal fun decodeFrozenMediaSpoolManifest(raw: String): ImmutableMediaSpoolGroup {
    val root = Json.parseToJsonElement(raw).jsonObject
    return if (root["contract"]?.jsonPrimitive?.contentOrNull == SETTLEMENT_CONTRACT) {
        decodeImmutableMediaSpoolGroup(
            requireNotNull(root["manifest"] as? JsonObject) {
                "media settlement manifest is missing"
            }.toString(),
        )
    } else {
        decodeImmutableMediaSpoolGroup(raw)
    }
}

internal fun encodeCausalMediaSettlement(journal: CausalMediaSettlementJournal): String {
    validateJournal(journal)
    return buildJsonObject {
        put("contract", SETTLEMENT_CONTRACT)
        put("mutation_id", journal.binding.mutationId)
        put("entity_type", journal.binding.entityType)
        put("client_uuid", journal.binding.clientUuid)
        put("content_epoch", journal.binding.contentEpoch)
        put("request_hash", journal.binding.requestHash)
        put("mutation", journal.mutation.toJournalJson())
        put("phase", journal.phase.wireName)
        put("stable_version_id", journal.stableVersionId?.let(::JsonPrimitive) ?: JsonNull)
        put("conflict_id", journal.conflictId?.let(::JsonPrimitive) ?: JsonNull)
        put("branch_version_id", journal.branchVersionId?.let(::JsonPrimitive) ?: JsonNull)
        put("manifest", Json.parseToJsonElement(encodeImmutableMediaSpoolGroup(journal.manifest)))
        put(
            "receipts",
            buildJsonArray {
                journal.receipts.forEach { receipt ->
                    add(
                        buildJsonObject {
                            put("media_uuid", receipt.mediaUuid)
                            put("status", receipt.status)
                            put("byte_size", receipt.byteSize)
                            put("sha256", receipt.sha256)
                            put("expires_at", receipt.expiresAtEpochSeconds)
                        },
                    )
                }
            },
        )
    }.toString()
}

internal fun decodeCausalMediaSettlementOrNull(raw: String): CausalMediaSettlementJournal? {
    val root = Json.parseToJsonElement(raw).jsonObject
    if (root["contract"]?.jsonPrimitive?.contentOrNull != SETTLEMENT_CONTRACT) return null
    require(root.keys == SETTLEMENT_KEYS) { "media settlement has unknown or missing fields" }
    val binding = CausalMediaSettlementBinding(
        mutationId = root.requiredString("mutation_id"),
        entityType = root.requiredString("entity_type"),
        clientUuid = root.requiredString("client_uuid"),
        contentEpoch = root.requiredLong("content_epoch"),
        requestHash = root.requiredString("request_hash"),
    )
    val manifest = decodeImmutableMediaSpoolGroup(root.requiredObject("manifest").toString())
    val mutation = root.requiredObject("mutation").toJournalMutation()
    val receipts = (root["receipts"] as? JsonArray)?.map { element ->
        val receipt = element as? JsonObject ?: error("media settlement receipt is not an object")
        require(receipt.keys == RECEIPT_KEYS) { "media settlement receipt shape drift" }
        CausalMediaPreimageReceipt(
            mediaUuid = receipt.requiredString("media_uuid"),
            status = receipt.requiredString("status"),
            byteSize = receipt.requiredLong("byte_size"),
            sha256 = receipt.requiredString("sha256"),
            expiresAtEpochSeconds = receipt.requiredLong("expires_at"),
        )
    } ?: error("media settlement receipts are missing")
    return CausalMediaSettlementJournal(
        binding = binding,
        mutation = mutation,
        manifest = manifest,
        receipts = receipts,
        phase = CausalMediaSettlementPhase.parse(root.requiredString("phase")),
        stableVersionId = root.optionalString("stable_version_id"),
        conflictId = root.optionalString("conflict_id"),
        branchVersionId = root.optionalString("branch_version_id"),
    ).also(::validateJournal)
}

private fun validateJournal(journal: CausalMediaSettlementJournal) {
    validateBinding(journal.binding)
    require(journal.manifest.mutationId == journal.binding.mutationId) {
        "media settlement manifest mutation drift"
    }
    require(
        journal.mutation.mutationId == journal.binding.mutationId &&
            journal.mutation.entityType == journal.binding.entityType &&
            journal.mutation.clientUuid == journal.binding.clientUuid &&
            causalMutationContentHash(journal.mutation) == journal.binding.requestHash,
    ) { "media settlement frozen mutation binding drift" }
    require(
        journal.mutation.media ==
            journal.manifest.items.map(ImmutableMediaSpoolItem::toCausalMediaItem),
    ) { "media settlement frozen mutation manifest drift" }
    require(journal.receipts.map(CausalMediaPreimageReceipt::mediaUuid).distinct().size ==
        journal.receipts.size
    ) { "media settlement contains duplicate receipts" }
    require(journal.receipts.map(CausalMediaPreimageReceipt::mediaUuid) ==
        journal.receipts.map(CausalMediaPreimageReceipt::mediaUuid).sorted()
    ) { "media settlement receipts are not canonical" }
    journal.receipts.forEach { receipt ->
        val item = journal.manifest.items.singleOrNull { it.mediaUuid == receipt.mediaUuid }
            ?: error("media settlement receipt is foreign to manifest")
        validateReceipt(receipt, item)
    }
    when (journal.phase) {
        CausalMediaSettlementPhase.Pending,
        -> require(
            journal.stableVersionId == null && journal.conflictId == null &&
                journal.branchVersionId == null,
        ) { "non-terminal media journal carries terminal references" }
        CausalMediaSettlementPhase.CommitUnknown -> {
            requireExactReceiptSet(journal)
            require(
                journal.stableVersionId == null && journal.conflictId == null &&
                    journal.branchVersionId == null,
            ) { "unknown media journal carries terminal references" }
        }
        CausalMediaSettlementPhase.CleanupAccepted,
        CausalMediaSettlementPhase.CleanupMerged,
        -> {
            requireExactReceiptSet(journal)
            require(
                !journal.stableVersionId.isNullOrBlank() && journal.conflictId == null &&
                    journal.branchVersionId == null,
            ) { "accepted/merged media journal references are invalid" }
        }
        CausalMediaSettlementPhase.Branched -> {
            requireExactReceiptSet(journal)
            require(
                !journal.stableVersionId.isNullOrBlank() && !journal.conflictId.isNullOrBlank() &&
                    !journal.branchVersionId.isNullOrBlank(),
            ) { "branched media journal references are incomplete" }
        }
    }
}

private fun requireExactReceiptSet(journal: CausalMediaSettlementJournal) {
    require(
        journal.receipts.map(CausalMediaPreimageReceipt::mediaUuid) ==
            journal.manifest.items.map(ImmutableMediaSpoolItem::mediaUuid).sorted(),
    ) { "media settlement phase requires the exact durable receipt set" }
}

private fun CausalMutationUnit.toJournalJson(): JsonObject = buildJsonObject {
    put("mutation_id", mutationId)
    put("base_version", baseVersion?.let(::JsonPrimitive) ?: JsonNull)
    put("entity_type", entityType)
    put("client_uuid", clientUuid)
    put("root", Json.parseToJsonElement(rootJson))
    put(
        "media",
        buildJsonArray {
            media.sortedBy(CausalMediaItem::mediaUuid).forEach { item ->
                add(
                    buildJsonObject {
                        put("media_uuid", item.mediaUuid)
                        put("role", item.role)
                        put("sha256", item.sha256)
                        put("byte_size", item.byteSize)
                        put("mime", item.mime)
                        put("width", item.width?.let(::JsonPrimitive) ?: JsonNull)
                        put("height", item.height?.let(::JsonPrimitive) ?: JsonNull)
                    },
                )
            }
        },
    )
    put("deleted", deleted)
}

private fun JsonObject.toJournalMutation(): CausalMutationUnit {
    require(keys == MUTATION_KEYS) { "media settlement mutation shape drift" }
    val media = (get("media") as? JsonArray)?.map { element ->
        val item = element as? JsonObject ?: error("media settlement media item is invalid")
        require(item.keys == MEDIA_KEYS) { "media settlement media item shape drift" }
        CausalMediaItem(
            mediaUuid = item.requiredString("media_uuid"),
            role = item.requiredString("role"),
            sha256 = item.requiredString("sha256"),
            byteSize = item.requiredLong("byte_size"),
            mime = item.requiredString("mime"),
            width = item.optionalLong("width"),
            height = item.optionalLong("height"),
        )
    } ?: error("media settlement media is invalid")
    require(media == media.sortedBy(CausalMediaItem::mediaUuid)) {
        "media settlement mutation media is not canonical"
    }
    return CausalMutationUnit(
        mutationId = requiredString("mutation_id"),
        baseVersion = optionalString("base_version"),
        entityType = requiredString("entity_type"),
        clientUuid = requiredString("client_uuid"),
        rootJson = requiredObject("root").toString(),
        media = media,
        deleted = (get("deleted") as? JsonPrimitive)?.booleanOrNull
            ?: error("media settlement deleted is invalid"),
    )
}

private fun validateBinding(binding: CausalMediaSettlementBinding) {
    require(binding.mutationId.matches(CANONICAL_UUID)) { "media settlement mutation id is invalid" }
    require(binding.entityType in CAUSAL_ROOT_TYPES) { "media settlement entity type is invalid" }
    require(binding.clientUuid.isNotBlank()) { "media settlement client UUID is blank" }
    require(binding.contentEpoch >= 0L) { "media settlement content epoch is invalid" }
    require(binding.requestHash.matches(CANONICAL_SHA256)) { "media settlement request hash is invalid" }
}

private fun validateReceipt(
    receipt: CausalMediaPreimageReceipt,
    item: ImmutableMediaSpoolItem,
) {
    require(receipt.status == "staged" || receipt.status == "consumed") {
        "media preimage receipt status is invalid"
    }
    require(
        receipt.mediaUuid == item.mediaUuid && receipt.sha256 == item.sha256 &&
            receipt.byteSize == item.byteSize,
    ) { "media preimage receipt does not bind its manifest item" }
    require(receipt.expiresAtEpochSeconds > 0L) { "media preimage receipt expiry is invalid" }
}

private fun ImmutableMediaSpoolItem.toManifestIdentity() = Triple(mediaUuid, sha256, byteSize)

private fun ImmutableMediaSpoolItem.toCausalMediaItem() = CausalMediaItem(
    mediaUuid = mediaUuid,
    role = role.wireName,
    sha256 = sha256,
    byteSize = byteSize,
    mime = mime,
    width = width,
    height = height,
)

private fun JsonObject.requiredString(key: String): String =
    (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
        ?: error("media settlement $key is invalid")

private fun JsonObject.requiredLong(key: String): Long =
    (get(key) as? JsonPrimitive)?.longOrNull
        ?: error("media settlement $key is invalid")

private fun JsonObject.requiredObject(key: String): JsonObject =
    get(key) as? JsonObject ?: error("media settlement $key is invalid")

private fun JsonObject.optionalString(key: String): String? = when (val value = get(key)) {
    JsonNull -> null
    is JsonPrimitive -> value.contentOrNull?.takeIf(String::isNotBlank)
        ?: error("media settlement $key is invalid")
    else -> error("media settlement $key is invalid")
}

private fun JsonObject.optionalLong(key: String): Long? = when (val value = get(key)) {
    JsonNull -> null
    is JsonPrimitive -> value.longOrNull ?: error("media settlement $key is invalid")
    else -> error("media settlement $key is invalid")
}

private const val SETTLEMENT_CONTRACT = "causal-media-settlement-v1"
private val CANONICAL_UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
private val CANONICAL_SHA256 = Regex("^[0-9a-f]{64}$")
private val SETTLEMENT_KEYS = setOf(
    "contract",
    "mutation_id",
    "entity_type",
    "client_uuid",
    "content_epoch",
    "request_hash",
    "mutation",
    "phase",
    "stable_version_id",
    "conflict_id",
    "branch_version_id",
    "manifest",
    "receipts",
)
private val RECEIPT_KEYS = setOf("media_uuid", "status", "byte_size", "sha256", "expires_at")
private val MUTATION_KEYS = setOf(
    "mutation_id",
    "base_version",
    "entity_type",
    "client_uuid",
    "root",
    "media",
    "deleted",
)
private val MEDIA_KEYS = setOf(
    "media_uuid",
    "role",
    "sha256",
    "byte_size",
    "mime",
    "width",
    "height",
)
