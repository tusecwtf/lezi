package com.lezi.babylog.sync.disasterrecovery

import com.lezi.babylog.core.database.*
import com.lezi.babylog.core.database.causal.*
import com.lezi.babylog.sync.engine.decodeCausalMediaSettlementOrNull
import com.lezi.babylog.sync.engine.decodeFrozenMediaSpoolManifest
import com.lezi.babylog.sync.media.*
import com.lezi.babylog.sync.session.DisasterRestoreCheckpoint
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.session.matchesOrigin
import java.io.File
import java.security.MessageDigest
import java.util.TreeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

internal enum class TerminalSpoolRetirementFault { AfterDeletingIntent, AfterUnlink }

/** A requested clear must retain its durable authority until an eligible unlink really commits. */
internal class TerminalSpoolCleanupIncompleteException :
    IllegalStateException("本机照片清理期间资料发生变化，清理尚未完成，请重试")

internal class TerminalSpoolRetirementMetrics {
    var captureInventoryReads = 0L; internal set
    var captureRowsIndexed = 0L; internal set
    var captureInventoryPathResolutions = 0L; internal set
    var reclaimInventoryReads = 0L; internal set
    var blockedReplacementAliases = 0L; internal set
}

/**
 * Sole owner of post-restore terminal C cleanup. The caller holds the sync command fence.
 * Lock order: owner -> shared path gates -> spool donor mutex -> short Room transaction.
 * No Room lease spans hashing/unlink, and a complete frozen/reference inventory is rechecked.
 */
internal class RestoreTerminalSpoolRetirementOwner(
    private val cache: ConflictSnapshotCacheDao,
    private val spool: ImmutableMediaSpool,
    private val files: SyncMediaFileStore,
    private val media: MediaAssetDao,
    private val babies: BabyDao,
    private val references: MediaReferenceDao,
    private val transactions: DatabaseTransactionRunner,
    private val cleanup: ReferenceAwareMediaFileCleanup,
    private val readCapturedRows: suspend () -> CapturedRestoreRows,
    private val currentSession: suspend () -> SyncSession,
    private val pendingClear: PendingReplicaCleanupStore? = null,
    private val fault: (TerminalSpoolRetirementFault) -> Unit = {},
    private val metrics: TerminalSpoolRetirementMetrics? = null,
    private val commandFence: Mutex,
) {
    private val gate = Mutex()
    // Fail-fast admission guard, not a coroutine ownership token. Production callers are
    // internal and must be source-reviewed to hold this actual RealSyncPort command mutex.
    private fun requireCommandFence() {
        check(commandFence.isLocked) { "terminal spool retirement requires the sync command fence" }
    }
    private val retirementSpool: TerminalRetirementSpool? get() = spool as? TerminalRetirementSpool
    private fun publicationPolicy(): PrivateSpoolPathPolicy? {
        val policy = (media as? PrivateSpoolPublicationGuarded)?.privateSpoolPathPolicy ?: return null
        return policy.takeIf {
            (babies as? PrivateSpoolPublicationGuarded)?.privateSpoolPathPolicy === it &&
                (references as? PrivateSpoolPublicationGuarded)?.privateSpoolPathPolicy === it
        }
    }

    /**
     * Caller is inside the accepted-switch Room transaction and the verified adoption path scope.
     * Original rows are the complete inventory read in that transaction before stripping envelopes.
     * Call after new row ownership and restored-media markers have been written, before `switched`.
     */
    suspend fun captureAcceptedSwitch(
        snapshot: DisasterRecoverySnapshot,
        committedSnapshotJournal: JsonObject,
        checkpoint: DisasterRestoreCheckpoint,
        restored: SyncSession,
        beforeRows: CapturedRestoreRows,
        originalSpoolRows: List<CausalTransportJournalEntity>,
        adoptedPaths: Map<String, String>,
    ): List<RestoreTerminalSpoolSeal> {
        requireCommandFence()
        val policy = publicationPolicy() ?: return emptyList()
        val ownedSpool = retirementSpool ?: return emptyList()
        val pointer = snapshot.fileSnapshot?.pointer ?: return emptyList()
        if (snapshot.evidenceVersion != 2 || pointer.requestId != checkpoint.startRequestId ||
            committedSnapshotJournal["format"]?.jsonPrimitive?.intOrNull != 2 ||
            committedSnapshotJournal["phase"]?.jsonPrimitive?.content != "committed" ||
            committedSnapshotJournal["request"]?.jsonPrimitive?.content != pointer.requestId ||
            committedSnapshotJournal["manifest_sha256"]?.jsonPrimitive?.content != pointer.manifestSha256 ||
            committedSnapshotJournal["manifest_bytes"]?.jsonPrimitive?.longOrNull != pointer.manifestByteSize ||
            committedSnapshotJournal["target"] != RestoreTerminalTarget.from(restored).json() ||
            restored.familyId != checkpoint.familyId || !checkpoint.endpoint.matchesOrigin(restored.baseUrl)) return emptyList()
        val versions = snapshot.retirementVersions.groupBy { it.type to it.clientUuid }
        val selected = snapshot.fileSnapshot.media.associateBy { it.clientUuid }
        val afterRows = readCapturedRows()
        val holders = references.listAllHolders()
        // Malformed inventory cannot disappear from the competing-group proof.
        val groups = originalSpoolRows.map { decodeFrozenMediaSpoolManifest(it.payloadJson) }
        if (groups.map { it.mutationId }.distinct().size != groups.size || originalSpoolRows.zip(groups).any { (row, group) ->
                row.journalKey != frozenMediaSpoolCacheKey(group.mutationId) || row.contentEpoch < 0
            }) return emptyList()
        metrics?.let { it.captureInventoryReads++ }
        val referencesIndex = referenceIndex(afterRows, holders, groups, policy, capture = true)
        val baselines = afterRows.baselines()
        val sourceEvidence = mutableMapOf<Pair<String, String>, String?>()
        val expectedEvidence = mutableMapOf<Pair<String, String>, String?>()
        fun selectedEvidence(type: String, uuid: String): String? = sourceEvidence.getOrPut(type to uuid) {
            versions[type to uuid]?.singleOrNull()?.takeIf { it.restored }?.localEvidence
                ?.takeIf { it == beforeRows.exactEvidence(type, uuid) }
        }
        fun expected(type: String, uuid: String): String? = expectedEvidence.getOrPut(type to uuid) {
            afterRows.exactEvidence(type, uuid)
        }
        return originalSpoolRows.mapNotNull { original ->
            val terminal = runCatching { decodeCausalMediaSettlementOrNull(original.payloadJson) }.getOrNull()
                ?.takeIf { it.phase.cleanupEligible } ?: return@mapNotNull null
            val binding = terminal.binding
            if (original.journalKey != frozenMediaSpoolCacheKey(binding.mutationId) ||
                original.contentEpoch != binding.contentEpoch || binding.entityType !in RestoreAuthority.rootTypes) return@mapNotNull null
            val sourceRoot = selectedEvidence(binding.entityType, binding.clientUuid) ?: return@mapNotNull null
            val expectedRoot = expected(binding.entityType, binding.clientUuid) ?: return@mapNotNull null
            if (baselines[binding.entityType to binding.clientUuid] !=
                RestoreAuthority.baseline(checkpoint.batchId, binding.entityType, binding.clientUuid)) return@mapNotNull null
            val group = terminal.manifest
            val paths = ownedSpool.ownedGroupPaths(group)
            if (!policy.ownsSpoolRoot(paths.root) || hasReferences(group, paths, referencesIndex)) return@mapNotNull null
            val replacements = group.items.map { item ->
                val selectedItem = selected[item.mediaUuid] ?: return@mapNotNull null
                val source = selectedEvidence("media", item.mediaUuid) ?: return@mapNotNull null
                val row = afterRows.mediaRow(item.mediaUuid) ?: return@mapNotNull null
                val adopted = adoptedPaths[item.mediaUuid] ?: return@mapNotNull null
                val resolved = policy.resolvedPath(adopted) ?: return@mapNotNull null
                val marker = cache.getTransportJournal("restored-media-bytes-v1:${item.mediaUuid}") ?: return@mapNotNull null
                if (row.deletedAt != null || row.localUri != adopted || marker.payloadJson != adopted ||
                    marker.contentEpoch != row.updatedAt || policy.isPrivatePath(adopted) ||
                    row.sha256 != selectedItem.sha256 || row.byteSize != selectedItem.byteSize ||
                    row.mime != selectedItem.mime || row.width != selectedItem.width || row.height != selectedItem.height) return@mapNotNull null
                RestoreTerminalReplacement(item.mediaUuid, item.role, selectedItem.sha256, selectedItem.byteSize,
                    selectedItem.mime, selectedItem.width, selectedItem.height, resolved.path, adopted,
                    source, requireNotNull(expected("media", item.mediaUuid)), marker.contentEpoch)
            }
            if (hasReplacementAliases(replacements, binding.entityType, binding.clientUuid, referencesIndex)) {
                metrics?.let { it.blockedReplacementAliases++ }
                return@mapNotNull null
            }
            RestoreTerminalSpoolSeal(group, original.journalKey, original.contentEpoch,
                terminalEvidenceDigest(original.payloadJson), binding.entityType, binding.clientUuid,
                binding.requestHash, terminal.phase, requireNotNull(terminal.stableVersionId), pointer,
                checkpoint.batchId, RestoreTerminalTarget.from(restored), sourceRoot, expectedRoot, replacements)
                .also { it.encode() }
        }
    }

    /** Same switch Room transaction, after the generic replay-journal reset. */
    suspend fun persistCaptured(seals: List<RestoreTerminalSpoolSeal>) {
        requireCommandFence()
        seals.forEach { seal ->
            val old = cache.getTransportJournal(seal.key)
            val payload = seal.encode()
            require(old == null || old.payloadJson == payload) { "terminal spool seal changed" }
            cache.putTransportJournal(seal.key, payload, 0)
        }
    }

    suspend fun prepareCommittedClear(scope: LocalDataClearScope): TerminalSpoolClearCapture {
        requireCommandFence()
        val captured = RestoreTerminalSpoolClear.capture(cache, scope)
        if (scope == LocalDataClearScope.AllLocalData) return captured
        // Historical terminal role does not authorize deleting a current avatar that reused
        // its UUID. The caller holds the same Room transaction as the pending-clear snapshot.
        val currentByUuid = media.listAllIncludingDeleted().groupBy { it.clientUuid }
        return captured.copy(seals = captured.seals.filter { seal ->
            seal.group.items.all { item ->
                currentByUuid[item.mediaUuid].orEmpty().all { it.kind == "log" || it.kind == "wake" }
            }
        })
    }

    suspend fun bindCommittedClear(pending: PendingReplicaCleanup, capture: TerminalSpoolClearCapture) {
        requireCommandFence()
        RestoreTerminalSpoolClear.bind(cache, pending, capture)
    }

    suspend fun reclaim() = reclaimInternal(clear = null)

    /** Reads the real durable clear authority; callers cannot substitute a boolean or guessed scope. */
    suspend fun reclaimAfterCommittedClear() {
        requireCommandFence()
        val clear = pendingClear?.load() ?: return
        reclaimInternal(clear)
    }

    private suspend fun reclaimInternal(clear: PendingReplicaCleanup?) = gate.withLock {
        requireCommandFence()
        val policy = publicationPolicy() ?: return@withLock
        val ownedSpool = retirementSpool ?: return@withLock
        for (durable in cache.listRestoreTerminalSpoolSeals()) {
            val seal = RestoreTerminalSpoolSeal.decode(durable)
            val paths = ownedSpool.ownedGroupPaths(seal.group)
            if (!policy.ownsSpoolRoot(paths.root)) continue
            val protectedPaths = paths.media.map { it.path } + paths.directory.path +
                seal.replacements.flatMap { listOf(it.localUri, it.path) }
            cleanup.withOwnedPaths(protectedPaths) {
                ownedSpool.withRetirementGroup(seal.group, seal.deleting) { lease ->
                    val before = transactions.run { inventory(seal) }
                    if (!eligible(seal, durable, paths, before, clear, policy)) return@withRetirementGroup
                    if (clear == null && !withContext(Dispatchers.IO) { seal.replacements.all(::verifyReplacement) })
                        return@withRetirementGroup
                    val deleting = seal.copy(deleting = true)
                    val intent = transactions.run {
                        val current = inventory(seal)
                        if (current != before || !eligible(seal, durable, paths, current, clear, policy)) return@run false
                        cache.putTransportJournal(seal.key, deleting.encode(), 0)
                        true
                    }
                    if (!intent) {
                        // Ordinary maintenance may retry later. A committed explicit clear may
                        // not retire its pending marker/binding after losing this final CAS.
                        // First-pass ineligible, intentionally retained RecordsOnly references
                        // remain distinct; only a previously eligible cleanup reaches here.
                        if (clear != null) throw TerminalSpoolCleanupIncompleteException()
                        return@withRetirementGroup
                    }
                    fault(TerminalSpoolRetirementFault.AfterDeletingIntent)
                    lease.discard()
                    fault(TerminalSpoolRetirementFault.AfterUnlink)
                    transactions.run {
                        // Every path publisher rejects new C aliases. The sync fence excludes new
                        // frozen envelopes; only the exact deleting owner can remove the last ref.
                        check(cache.getTransportJournal(seal.key)?.payloadJson == deleting.encode())
                        val manifest = cache.getFrozenMediaSpoolManifest(seal.group.mutationId)
                        check(manifest != null && plainManifest(manifest) == seal.group)
                        cache.deleteFrozenMediaSpoolManifest(seal.group.mutationId)
                        cache.deleteTransportJournal("media-freeze-capture-v1:${seal.group.mutationId}")
                        cache.deleteTransportJournal("media-preparation-sources-v1:${seal.group.mutationId}")
                        removeFromLegacyMarker(seal.group.mutationId)
                        cache.deleteTransportJournal(seal.key)
                    }
                }
            }
        }
    }

    private data class Inventory(
        val seal: CausalTransportJournalEntity?,
        val rows: CapturedRestoreRows,
        val holders: List<MediaReferenceEntity>,
        val frozen: List<CausalTransportJournalEntity>,
        val markers: List<CausalTransportJournalEntity?>,
        val target: RestoreTerminalTarget,
        val clear: PendingReplicaCleanup?,
        val clearBindingMatches: Boolean,
    )

    private suspend fun inventory(seal: RestoreTerminalSpoolSeal): Inventory {
        metrics?.let { it.reclaimInventoryReads++ }
        return Inventory(
            cache.getTransportJournal(seal.key), readCapturedRows(), references.listAllHolders(),
            cache.listFrozenMediaSpoolManifests(), seal.replacements.map { cache.getTransportJournal(it.markerKey) },
            RestoreTerminalTarget.from(currentSession()), pendingClear?.load(),
            pendingClear?.load()?.let { RestoreTerminalSpoolClear.matches(cache, it, seal) } ?: false,
        )
    }

    private fun eligible(
        seal: RestoreTerminalSpoolSeal, durable: CausalTransportJournalEntity, paths: TerminalSpoolPaths,
        current: Inventory, clear: PendingReplicaCleanup?, policy: PrivateSpoolPathPolicy,
    ): Boolean {
        if (current.seal != durable) return false
        val manifest = current.frozen.singleOrNull { it.journalKey == frozenMediaSpoolCacheKey(seal.group.mutationId) }
            ?: return false
        if (plainManifest(manifest) != seal.group) return false
        val groups = current.frozen.map { decodeFrozenMediaSpoolManifest(it.payloadJson) }
        if (groups.map { it.mutationId }.distinct().size != groups.size || current.frozen.zip(groups).any { (row, group) ->
                row.journalKey != frozenMediaSpoolCacheKey(group.mutationId) || row.contentEpoch < 0
            }) return false
        val index = referenceIndex(current.rows, current.holders, groups, policy, capture = false)
        if (hasReferences(seal.group, paths, index)) return false
        if (clear != null) {
            return current.clear == clear && current.clearBindingMatches && seal.group.items.all { item ->
                item.mediaUuid in clear.mediaClientUuids && current.rows.mediaRow(item.mediaUuid) == null
            } && (clear.scope == LocalDataClearScope.AllLocalData || seal.rootType != "baby")
        }
        if (hasReplacementAliases(seal.replacements, seal.rootType, seal.rootUuid, index)) {
            metrics?.let { it.blockedReplacementAliases++ }
            return false
        }
        if (current.target != seal.target || current.rows.baseline(seal.rootType, seal.rootUuid) != seal.baseline ||
            current.rows.exactEvidence(seal.rootType, seal.rootUuid) != seal.expectedRootEvidence) return false
        return seal.replacements.withIndex().all { (index, item) ->
            val marker = current.markers[index]
            current.rows.exactEvidence("media", item.uuid) == item.expectedEvidence &&
                marker?.payloadJson == item.localUri && marker.contentEpoch == item.markerEpoch &&
                policy.resolvedPath(item.localUri)?.path == item.path && !policy.isPrivatePath(item.localUri)
        }
    }

    private data class PathUse(val kind: String, val uuid: String, val avatarUuid: String? = null)
    private data class ReferenceIndex(
        val paths: TreeMap<String, MutableList<PathUse>>,
        val unresolvedPath: Boolean,
        val holderUuids: Set<String>,
        val frozenOwners: Map<String, Set<String>>,
    )

    /** Capture builds this once for the whole batch; reclamation rebuilds at each required CAS. */
    private fun referenceIndex(
        rows: CapturedRestoreRows, holders: List<MediaReferenceEntity>, groups: List<ImmutableMediaSpoolGroup>,
        policy: PrivateSpoolPathPolicy, capture: Boolean,
    ): ReferenceIndex {
        val paths = TreeMap<String, MutableList<PathUse>>()
        var unresolved = false
        fun add(path: String?, use: PathUse) {
            if (path.isNullOrBlank()) return
            if (capture) metrics?.let { it.captureInventoryPathResolutions++ }
            val key = policy.resolvedPath(path)?.path
            if (key == null) unresolved = true else paths.getOrPut(key) { mutableListOf() }.add(use)
        }
        if (capture) metrics?.let { it.captureRowsIndexed += rows.media.size + rows.babies.size + holders.size }
        rows.media.forEach { add(it.localUri, PathUse("media", it.clientUuid)) }
        rows.babies.forEach { add(it.avatarPath, PathUse("baby", it.clientUuid, it.avatarMediaUuid)) }
        holders.forEach { add(it.localUri, PathUse("holder", it.mediaUuid)) }
        val owners = mutableMapOf<String, MutableSet<String>>()
        groups.forEach { group -> group.items.forEach { item -> owners.getOrPut(item.mediaUuid) { hashSetOf() }.add(group.mutationId) } }
        return ReferenceIndex(paths, unresolved, holders.mapTo(hashSetOf()) { it.mediaUuid }, owners)
    }

    private fun hasReferences(group: ImmutableMediaSpoolGroup, paths: TerminalSpoolPaths, index: ReferenceIndex): Boolean {
        if (index.unresolvedPath) return true
        val directory = paths.directory.canonicalPath
        val prefix = directory + File.separator
        val firstDescendant = index.paths.ceilingKey(prefix)
        if (index.paths.containsKey(directory) || firstDescendant?.startsWith(prefix) == true) return true
        return group.items.any { item -> item.mediaUuid in index.holderUuids ||
            index.frozenOwners[item.mediaUuid].orEmpty().any { it != group.mutationId } }
    }

    /** Existing raw-string cleanup must not mistake a different spelling of R for unowned bytes. */
    private fun hasReplacementAliases(
        replacements: List<RestoreTerminalReplacement>, rootType: String, rootUuid: String, index: ReferenceIndex,
    ): Boolean {
        if (replacements.map { it.path }.distinct().size != replacements.size) return true
        return replacements.any { item -> index.paths[item.path].orEmpty().any { use -> when (use.kind) {
            "media" -> use.uuid != item.uuid
            "baby" -> rootType != "baby" || use.uuid != rootUuid || use.avatarUuid != item.uuid
            else -> true
        } } }
    }

    private fun verifyReplacement(item: RestoreTerminalReplacement): Boolean = runCatching {
        val file = files.readableFile(item.localUri) ?: return false
        if (file.canonicalPath != item.path || file.length() != item.byteSize) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; if (count > 0) digest.update(buffer, 0, count) }
        }
        digest.digest().joinToString("") { "%02x".format(it) } == item.sha256
    }.getOrDefault(false)

    private fun plainManifest(row: CausalTransportJournalEntity): ImmutableMediaSpoolGroup? =
        if (row.contentEpoch != 0L || decodeCausalMediaSettlementOrNull(row.payloadJson) != null) null
        else decodeFrozenMediaSpoolManifest(row.payloadJson)

    private suspend fun removeFromLegacyMarker(id: String) {
        val row = cache.getTransportJournal(RestoreArtifactRetirement.KEY) ?: return
        val value = Json.parseToJsonElement(row.payloadJson).jsonObject
        require(value["format"]?.jsonPrimitive?.intOrNull == 1)
        val remaining = value.getValue("groups").jsonArray.map { it.jsonPrimitive.content }.filterNot { it == id }
        cache.putTransportJournal(row.journalKey, JsonObject(value +
            ("groups" to JsonArray(remaining.map(::JsonPrimitive)))).toString(), row.contentEpoch)
    }
}

private fun CapturedRestoreRows.baseline(type: String, uuid: String): String? = when (type) {
    "baby" -> babies.singleOrNull { it.clientUuid == uuid }?.baseVersion
    "record" -> records.singleOrNull { it.clientUuid == uuid }?.baseVersion
    "care_plan" -> plans.singleOrNull { it.clientUuid == uuid }?.baseVersion
    "custom_item" -> customItems.singleOrNull { it.clientUuid == uuid }?.baseVersion
    "wake_observation" -> wakes.singleOrNull { it.clientUuid == uuid }?.baseVersion
    else -> null
}

private fun CapturedRestoreRows.baselines(): Map<Pair<String, String>, String?> = buildMap {
    fun add(type: String, uuid: String, baseline: String?) {
        val key = type to uuid
        put(key, if (containsKey(key)) null else baseline)
    }
    babies.forEach { add("baby", it.clientUuid, it.baseVersion) }
    records.forEach { add("record", it.clientUuid, it.baseVersion) }
    plans.forEach { add("care_plan", it.clientUuid, it.baseVersion) }
    customItems.forEach { add("custom_item", it.clientUuid, it.baseVersion) }
    wakes.forEach { add("wake_observation", it.clientUuid, it.baseVersion) }
}
