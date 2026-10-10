package com.lezi.babylog.domain.carelog

import com.lezi.babylog.core.database.causal.ConflictSnapshotCacheDao
import com.lezi.babylog.core.database.causal.ConflictSnapshotCacheEntity
import com.lezi.babylog.core.database.causal.CausalTransportJournalEntity
import com.lezi.babylog.core.database.causal.ConflictInboxProjectionRow
import com.lezi.babylog.core.database.causal.ConflictSummaryDao
import com.lezi.babylog.core.database.causal.ConflictSummaryEntity
import com.lezi.babylog.core.database.causal.FROZEN_MEDIA_SPOOL_KEY_PREFIX
import com.lezi.babylog.core.database.causal.MediaReferenceDao
import com.lezi.babylog.core.database.causal.MediaReferenceEntity
import com.lezi.babylog.core.database.causal.sourceRelationAutoJournalKey
import com.lezi.babylog.core.database.causal.SourceRelationDao
import com.lezi.babylog.core.database.causal.SourceRelationDeclarationEntity
import com.lezi.babylog.core.database.causal.SourceRelationEntity
import com.lezi.babylog.core.database.causal.SourceRelationMemberEntity
import com.lezi.babylog.core.database.causal.SuspectedDuplicateGroupDao
import com.lezi.babylog.core.database.causal.SuspectedDuplicateGroupEntity
import com.lezi.babylog.core.database.causal.WakeObservationDao
import com.lezi.babylog.core.database.causal.WakeObservationEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/** Minimal causal DAOs for clear persistence in CareLog unit tests. */
internal class FakeWakeObservationDao : WakeObservationDao {
    private val items = mutableListOf<WakeObservationEntity>()
    private var nextId = 1L
    private var transactionSnapshot: List<WakeObservationEntity>? = null
    var upsertFailure: Throwable? = null

    fun beginTx() { transactionSnapshot = items.toList() }
    fun commitTx() { transactionSnapshot = null }
    fun rollbackTx() {
        transactionSnapshot?.let { snapshot ->
            items.clear()
            items.addAll(snapshot)
        }
        transactionSnapshot = null
    }

    /** Invoked after local wake mutations so open-sleep flows can recompute. */
    var onMutation: (() -> Unit)? = null

    fun itemsSnapshot(): List<WakeObservationEntity> = items.toList()

    override suspend fun get(id: Long): WakeObservationEntity? =
        items.find { it.id == id }

    override suspend fun getByIds(ids: List<Long>): List<WakeObservationEntity> =
        items.filter { it.id in ids }

    override suspend fun getByClientUuid(uuid: String): WakeObservationEntity? =
        items.find { it.clientUuid == uuid }

    override suspend fun listForSleep(sleepRecordClientUuid: String): List<WakeObservationEntity> =
        items.filter { it.sleepRecordClientUuid == sleepRecordClientUuid }

    override suspend fun listAllIncludingDeleted(): List<WakeObservationEntity> = items

    override suspend fun listActiveForSleep(
        sleepRecordClientUuid: String,
    ): List<WakeObservationEntity> =
        items.filter {
            it.sleepRecordClientUuid == sleepRecordClientUuid &&
                it.deletedAt == null &&
                !it.withdrawn
        }.sortedWith(
            compareBy(WakeObservationEntity::wakeTimestamp, WakeObservationEntity::clientUuid),
        )

    override suspend fun listPendingSync(): List<WakeObservationEntity> =
        items.filter { it.syncDirty }

    override suspend fun listOpenConflicts(): List<WakeObservationEntity> =
        items.filter { it.openConflictId != null }

    override suspend fun upsert(entity: WakeObservationEntity): Long {
        upsertFailure?.let { throw it }
        val existingIdx = items.indexOfFirst { it.clientUuid == entity.clientUuid }
        val id = if (existingIdx >= 0) {
            val kept = items[existingIdx].id.takeIf { it > 0L } ?: nextId++
            items[existingIdx] = entity.copy(id = kept)
            kept
        } else {
            val minted = if (entity.id > 0L) entity.id else nextId++
            if (minted >= nextId) nextId = minted + 1L
            items += entity.copy(id = minted)
            minted
        }
        onMutation?.invoke()
        return id
    }

    override suspend fun update(entity: WakeObservationEntity) {
        items.replaceAll { if (it.clientUuid == entity.clientUuid) entity else it }
        onMutation?.invoke()
    }

    override suspend fun deleteAll() {
        items.clear()
        onMutation?.invoke()
    }
}

internal class FakeConflictSummaryDao : ConflictSummaryDao {
    private val items = mutableListOf<ConflictSummaryEntity>()
    private val inboxRows = MutableStateFlow<List<ConflictInboxProjectionRow>>(emptyList())
    private var manualInboxOverride: List<ConflictInboxProjectionRow>? = null

    override fun observeInboxProjection(): Flow<List<ConflictInboxProjectionRow>> = inboxRows

    /**
     * Unit tests that only exercise inbox mapping can inject projection rows without
     * full summary/root joins. Seam fixtures leave this null so open summaries surface.
     */
    fun setInboxRows(rows: List<ConflictInboxProjectionRow>) {
        manualInboxOverride = rows
        inboxRows.value = rows
    }

    override suspend fun get(conflictId: String): ConflictSummaryEntity? =
        items.find { it.conflictId == conflictId }

    override suspend fun listForRoot(
        entityType: String,
        clientUuid: String,
    ): List<ConflictSummaryEntity> =
        items.filter { it.entityType == entityType && it.clientUuid == clientUuid }

    override suspend fun upsert(entity: ConflictSummaryEntity) {
        items.removeAll { it.conflictId == entity.conflictId }
        items += entity
        publishInbox()
    }

    override suspend fun delete(conflictId: String) {
        items.removeAll { it.conflictId == conflictId }
        publishInbox()
    }

    override suspend fun deleteAll() {
        items.clear()
        publishInbox()
    }

    private fun publishInbox() {
        manualInboxOverride?.let {
            inboxRows.value = it
            return
        }
        inboxRows.value = items
            .filter { it.status == "open" && it.branchVersionIdsJson != "[]" }
            .sortedWith(compareBy({ it.updatedAt }, { it.conflictId }))
            .map { summary ->
                ConflictInboxProjectionRow(
                    conflictId = summary.conflictId,
                    entityType = summary.entityType,
                    clientUuid = summary.clientUuid,
                    updatedAt = summary.updatedAt,
                    localTitle = null,
                    babyLabel = null,
                    localActorId = null,
                    localTombstone = null,
                    localMediaCount = 0,
                    snapshotJson = null,
                )
            }
    }
}

internal class FakeConflictSnapshotCacheDao : ConflictSnapshotCacheDao {
    private val items = mutableListOf<ConflictSnapshotCacheEntity>()
    private val transport = mutableListOf<CausalTransportJournalEntity>()

    override suspend fun get(conflictId: String): ConflictSnapshotCacheEntity? =
        items.find { it.conflictId == conflictId }

    override suspend fun upsert(entity: ConflictSnapshotCacheEntity) {
        items.removeAll { it.conflictId == entity.conflictId }
        items += entity
    }

    override suspend fun delete(conflictId: String) {
        items.removeAll { it.conflictId == conflictId }
    }

    override suspend fun deleteAll() {
        items.clear()
    }

    override suspend fun getTransportJournal(journalKey: String): CausalTransportJournalEntity? =
        transport.find { it.journalKey == journalKey }

    override suspend fun putTransportJournal(
        journalKey: String,
        payloadJson: String,
        contentEpoch: Long,
    ) {
        transport.removeAll { it.journalKey == journalKey }
        transport += CausalTransportJournalEntity(
            journalKey = journalKey,
            payloadJson = payloadJson,
            contentEpoch = contentEpoch,
        )
    }

    override suspend fun deleteTransportJournal(journalKey: String) {
        transport.removeAll { it.journalKey == journalKey }
    }

    override suspend fun deleteAllTransportJournals() {
        transport.clear()
    }

    override suspend fun deleteAllPullDiagnostics() {
        transport.removeAll {
            it.journalKey.startsWith(
                com.lezi.babylog.core.database.causal.PULL_DIAGNOSTIC_KEY_PREFIX,
            )
        }
    }

    override suspend fun deleteAllTerminalReceipts() {
        transport.removeAll {
            it.journalKey.startsWith(
                com.lezi.babylog.core.database.causal.TERMINAL_RECEIPT_KEY_PREFIX,
            )
        }
    }

    override suspend fun listRestoreTerminalSpoolSeals(): List<CausalTransportJournalEntity> =
        transport.filter { it.journalKey.startsWith("restore-terminal-spool-cleanup-v1:") }.sortedBy { it.journalKey }

    override suspend fun listRestoreFileOwners(): List<CausalTransportJournalEntity> =
        transport.filter { it.journalKey.startsWith("restore-file-owner-v1:") }.sortedBy { it.journalKey }

    override suspend fun listFrozenMediaSpoolManifests(): List<CausalTransportJournalEntity> =
        transport.filter { it.journalKey.startsWith(FROZEN_MEDIA_SPOOL_KEY_PREFIX) }
            .sortedBy(CausalTransportJournalEntity::journalKey)
    override suspend fun listTerminalReceiptJournals(): List<
        CausalTransportJournalEntity
    > = transport.filter {
        it.journalKey.startsWith(
            com.lezi.babylog.core.database.causal.TERMINAL_RECEIPT_KEY_PREFIX,
        )
    }.sortedWith(compareBy({ it.contentEpoch }, { it.journalKey }))

    override fun observeTerminalReceiptJournals(): kotlinx.coroutines.flow.Flow<
        List<CausalTransportJournalEntity>
    > = kotlinx.coroutines.flow.flow {
        emit(listTerminalReceiptJournals())
    }

    override suspend fun listPullDiagnosticJournals(): List<
        CausalTransportJournalEntity
    > = transport.filter {
        it.journalKey.startsWith(
            com.lezi.babylog.core.database.causal.PULL_DIAGNOSTIC_KEY_PREFIX,
        )
    }.sortedWith(compareBy({ it.contentEpoch }, { it.journalKey }))

    override fun observePullDiagnosticJournals(): kotlinx.coroutines.flow.Flow<
        List<CausalTransportJournalEntity>
    > = kotlinx.coroutines.flow.flow {
        emit(listPullDiagnosticJournals())
    }

    override suspend fun listDismissedEntityJournals(): List<CausalTransportJournalEntity> =
        transport.filter {
            it.journalKey.startsWith(
                com.lezi.babylog.core.database.causal.DISMISSED_ENTITY_KEY_PREFIX,
            )
        }.sortedBy(CausalTransportJournalEntity::journalKey)

    override fun observeDismissedEntityJournals(): kotlinx.coroutines.flow.Flow<
        List<CausalTransportJournalEntity>
    > = kotlinx.coroutines.flow.flow {
        emit(listDismissedEntityJournals())
    }
}

internal class FakeSuspectedDuplicateGroupDao : SuspectedDuplicateGroupDao {
    private val items = mutableListOf<SuspectedDuplicateGroupEntity>()

    override fun observeOpen(): Flow<List<SuspectedDuplicateGroupEntity>> =
        flowOf(items.filter { it.status == "open" })

    override suspend fun get(groupId: String): SuspectedDuplicateGroupEntity? =
        items.find { it.groupId == groupId }

    override suspend fun upsert(entity: SuspectedDuplicateGroupEntity) {
        items.removeAll { it.groupId == entity.groupId }
        items += entity
    }

    override suspend fun delete(groupId: String) {
        items.removeAll { it.groupId == groupId }
    }

    override suspend fun deleteAll() {
        items.clear()
    }
}

internal class FakeSourceRelationDao : SourceRelationDao() {
    private val relations = mutableListOf<SourceRelationEntity>()
    private val members = mutableListOf<SourceRelationMemberEntity>()
    private val declarations = mutableListOf<SourceRelationDeclarationEntity>()
    private val transportJournals = mutableListOf<CausalTransportJournalEntity>()
    private val memberFlow = MutableStateFlow<List<SourceRelationMemberEntity>>(emptyList())
    private val relationFlow = MutableStateFlow<List<SourceRelationEntity>>(emptyList())
    private val autoDisplayFlow = MutableStateFlow<List<String>>(emptyList())

    private fun autoDisplays(): List<String> = relations.filter { relation ->
        relation.displayClientUuid.isNotBlank() &&
            members.any {
                it.relationId == relation.relationId &&
                    it.recordClientUuid == relation.displayClientUuid &&
                    it.role == "display"
            } &&
            (
                transportJournals.any { it.journalKey == sourceRelationAutoJournalKey(relation.relationId) } ||
                    (relation.reason != "pull_summary" && relation.mutationId.startsWith("auto-near-neighbor:"))
                )
    }.map { it.displayClientUuid }.distinct().sorted()

    override suspend fun listAutoAlignedDisplayClientUuids(): List<String> = autoDisplays()

    override fun observeAutoAlignedDisplayClientUuids(): Flow<List<String>> = autoDisplayFlow

    private fun publishMembers() {
        memberFlow.value = members.toList()
        autoDisplayFlow.value = autoDisplays()
    }

    private fun publishRelations() {
        relationFlow.value = relations.toList()
        autoDisplayFlow.value = autoDisplays()
    }

    override suspend fun get(relationId: String): SourceRelationEntity? =
        relations.find { it.relationId == relationId }

    override suspend fun listAll(): List<SourceRelationEntity> = relations.toList()

    override fun observeAll(): Flow<List<SourceRelationEntity>> = relationFlow

    override suspend fun upsertRelationRow(entity: SourceRelationEntity) {
        relations.removeAll { it.relationId == entity.relationId }
        relations += entity
        publishRelations()
    }

    override suspend fun upsertMemberRow(member: SourceRelationMemberEntity) {
        members.removeAll {
            it.relationId == member.relationId && it.recordClientUuid == member.recordClientUuid
        }
        members += member
        publishMembers()
    }

    override suspend fun listMembers(relationId: String): List<SourceRelationMemberEntity> =
        members.filter { it.relationId == relationId }

    override suspend fun listAllMembers(): List<SourceRelationMemberEntity> = members.toList()

    override fun observeAllMembers(): Flow<List<SourceRelationMemberEntity>> = memberFlow

    override suspend fun listMembersForRecord(
        recordClientUuid: String,
    ): List<SourceRelationMemberEntity> =
        members.filter { it.recordClientUuid == recordClientUuid }

    override suspend fun listMembersForRecords(recordClientUuids: List<String>) =
        members.filter { it.recordClientUuid in recordClientUuids }

    override suspend fun deleteMembershipsForRecords(recordClientUuids: List<String>) {
        members.removeAll { it.recordClientUuid in recordClientUuids }
        publishMembers()
    }

    override suspend fun deleteOtherMemberships(
        relationId: String,
        recordClientUuids: List<String>,
    ) {
        members.removeAll {
            it.recordClientUuid in recordClientUuids && it.relationId != relationId
        }
        publishMembers()
    }

    override suspend fun deleteMembersOutsideCanonicalSet(
        relationId: String,
        recordClientUuids: List<String>,
    ) {
        members.removeAll {
            it.relationId == relationId && it.recordClientUuid !in recordClientUuids
        }
        publishMembers()
    }

    override suspend fun upsertDeclaration(declaration: SourceRelationDeclarationEntity) {
        declarations.removeAll { it.mutationId == declaration.mutationId }
        declarations += declaration
    }

    override suspend fun getDeclaration(mutationId: String): SourceRelationDeclarationEntity? =
        declarations.find { it.mutationId == mutationId }

    override suspend fun listPendingDeclarations(): List<SourceRelationDeclarationEntity> =
        declarations.filter { it.status == "pending" }

    override suspend fun listUnsettledDeclarations(): List<SourceRelationDeclarationEntity> =
        declarations.filter { it.status == "pending" || it.status == "failed" }

    override suspend fun deleteAllMembers() {
        members.clear()
        publishMembers()
    }

    override suspend fun deleteAllDeclarations() {
        declarations.clear()
    }

    override suspend fun putTransportJournalRow(
        journalKey: String,
        payloadJson: String,
        contentEpoch: Long,
    ) {
        transportJournals.removeAll { it.journalKey == journalKey }
        transportJournals += CausalTransportJournalEntity(journalKey, payloadJson, contentEpoch)
        autoDisplayFlow.value = autoDisplays()
    }

    override suspend fun deleteAutoAlignedJournal(relationId: String) {
        transportJournals.removeAll { it.journalKey == sourceRelationAutoJournalKey(relationId) }
        autoDisplayFlow.value = autoDisplays()
    }

    override suspend fun deleteAutoAlignedJournalsSupersededBy(
        relationId: String,
        recordClientUuids: List<String>,
    ) {
        val superseded = members
            .filter { it.recordClientUuid in recordClientUuids && it.relationId != relationId }
            .mapTo(mutableSetOf()) { it.relationId }
        transportJournals.removeAll { it.journalKey in superseded.map(::sourceRelationAutoJournalKey) }
        autoDisplayFlow.value = autoDisplays()
    }

    override suspend fun deleteAllRelationRows() {
        relations.clear()
        publishRelations()
    }

    override suspend fun deleteAllAutoAlignedJournals() {
        transportJournals.clear()
        autoDisplayFlow.value = autoDisplays()
    }
}

internal class FakeMediaReferenceDao : MediaReferenceDao {
    private val items = mutableListOf<MediaReferenceEntity>()

    override suspend fun upsert(ref: MediaReferenceEntity) {
        items.removeAll {
            it.mediaUuid == ref.mediaUuid &&
                it.holderKind == ref.holderKind &&
                it.holderId == ref.holderId
        }
        items += ref
    }

    override suspend fun remove(mediaUuid: String, holderKind: String, holderId: String) {
        items.removeAll {
            it.mediaUuid == mediaUuid && it.holderKind == holderKind && it.holderId == holderId
        }
    }

    override suspend fun listAllHolders(): List<com.lezi.babylog.core.database.causal.MediaReferenceEntity> =
        items.sortedWith(compareBy({ it.mediaUuid }, { it.holderKind }, { it.holderId }))

    override suspend fun listForMedia(mediaUuid: String): List<MediaReferenceEntity> =
        items.filter { it.mediaUuid == mediaUuid }

    override suspend fun countHoldersForLocalUri(localUri: String): Int =
        items.count { it.localUri == localUri }

    override suspend fun countHoldersForMedia(mediaUuid: String): Int =
        items.count { it.mediaUuid == mediaUuid }

    override suspend fun deleteForMedia(mediaUuid: String) {
        items.removeAll { it.mediaUuid == mediaUuid }
    }

    override suspend fun deleteForLogAndWakeMedia() {
        // Unit fakes do not join media_assets; clear all care-media holders.
        items.clear()
    }

    override suspend fun deleteAll() {
        items.clear()
    }
}
