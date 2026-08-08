package com.lezi.babylog.domain.carelog

import com.lezi.babylog.core.database.causal.ConflictDetailCacheDao
import com.lezi.babylog.core.database.causal.ConflictDetailCacheEntity
import com.lezi.babylog.core.database.causal.ConflictSummaryDao
import com.lezi.babylog.core.database.causal.ConflictSummaryEntity
import com.lezi.babylog.core.database.causal.MediaReferenceDao
import com.lezi.babylog.core.database.causal.MediaReferenceEntity
import com.lezi.babylog.core.database.causal.SourceRelationDao
import com.lezi.babylog.core.database.causal.SourceRelationDeclarationEntity
import com.lezi.babylog.core.database.causal.SourceRelationEntity
import com.lezi.babylog.core.database.causal.SourceRelationMemberEntity
import com.lezi.babylog.core.database.causal.SuspectedDuplicateGroupDao
import com.lezi.babylog.core.database.causal.SuspectedDuplicateGroupEntity
import com.lezi.babylog.core.database.causal.WakeObservationDao
import com.lezi.babylog.core.database.causal.WakeObservationEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Minimal causal DAOs for clear persistence in CareLog unit tests. */
internal class FakeWakeObservationDao : WakeObservationDao {
    private val items = mutableListOf<WakeObservationEntity>()

    override suspend fun getByClientUuid(uuid: String): WakeObservationEntity? =
        items.find { it.clientUuid == uuid }

    override suspend fun listForSleep(sleepRecordClientUuid: String): List<WakeObservationEntity> =
        items.filter { it.sleepRecordClientUuid == sleepRecordClientUuid }

    override suspend fun listActiveForSleep(
        sleepRecordClientUuid: String,
    ): List<WakeObservationEntity> =
        items.filter {
            it.sleepRecordClientUuid == sleepRecordClientUuid &&
                it.deletedAt == null &&
                !it.withdrawn
        }

    override suspend fun listPendingSync(): List<WakeObservationEntity> =
        items.filter { it.syncDirty }

    override suspend fun listOpenConflicts(): List<WakeObservationEntity> =
        items.filter { it.openConflictId != null }

    override suspend fun upsert(entity: WakeObservationEntity): Long {
        items.removeAll { it.clientUuid == entity.clientUuid }
        items += entity
        return 1L
    }

    override suspend fun update(entity: WakeObservationEntity) {
        items.replaceAll { if (it.clientUuid == entity.clientUuid) entity else it }
    }

    override suspend fun deleteAll() {
        items.clear()
    }
}

internal class FakeConflictSummaryDao : ConflictSummaryDao {
    private val items = mutableListOf<ConflictSummaryEntity>()

    override fun observeOpen(): Flow<List<ConflictSummaryEntity>> =
        flowOf(items.filter { it.status == "open" })

    override suspend fun listOpen(): List<ConflictSummaryEntity> =
        items.filter { it.status == "open" }

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
    }

    override suspend fun delete(conflictId: String) {
        items.removeAll { it.conflictId == conflictId }
    }

    override suspend fun deleteAll() {
        items.clear()
    }
}

internal class FakeConflictDetailCacheDao : ConflictDetailCacheDao {
    private val items = mutableListOf<ConflictDetailCacheEntity>()

    override suspend fun get(conflictId: String): ConflictDetailCacheEntity? =
        items.find { it.conflictId == conflictId }

    override suspend fun upsert(entity: ConflictDetailCacheEntity) {
        items.removeAll { it.conflictId == entity.conflictId }
        items += entity
    }

    override suspend fun delete(conflictId: String) {
        items.removeAll { it.conflictId == conflictId }
    }

    override suspend fun deleteAll() {
        items.clear()
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

internal class FakeSourceRelationDao : SourceRelationDao {
    private val relations = mutableListOf<SourceRelationEntity>()
    private val members = mutableListOf<SourceRelationMemberEntity>()
    private val declarations = mutableListOf<SourceRelationDeclarationEntity>()

    override suspend fun get(relationId: String): SourceRelationEntity? =
        relations.find { it.relationId == relationId }

    override suspend fun listAll(): List<SourceRelationEntity> = relations.toList()

    override suspend fun upsert(entity: SourceRelationEntity) {
        relations.removeAll { it.relationId == entity.relationId }
        relations += entity
    }

    override suspend fun upsertMember(member: SourceRelationMemberEntity) {
        members.removeAll {
            it.relationId == member.relationId && it.recordClientUuid == member.recordClientUuid
        }
        members += member
    }

    override suspend fun listMembers(relationId: String): List<SourceRelationMemberEntity> =
        members.filter { it.relationId == relationId }

    override suspend fun listMembersForRecord(
        recordClientUuid: String,
    ): List<SourceRelationMemberEntity> =
        members.filter { it.recordClientUuid == recordClientUuid }

    override suspend fun upsertDeclaration(declaration: SourceRelationDeclarationEntity) {
        declarations.removeAll { it.mutationId == declaration.mutationId }
        declarations += declaration
    }

    override suspend fun getDeclaration(mutationId: String): SourceRelationDeclarationEntity? =
        declarations.find { it.mutationId == mutationId }

    override suspend fun listPendingDeclarations(): List<SourceRelationDeclarationEntity> =
        declarations.filter { it.status == "pending" }

    override suspend fun deleteAllMembers() {
        members.clear()
    }

    override suspend fun deleteAllDeclarations() {
        declarations.clear()
    }

    override suspend fun deleteAll() {
        relations.clear()
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
