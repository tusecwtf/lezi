package com.lezi.babylog.sync

import com.lezi.babylog.core.database.BabyDao
import com.lezi.babylog.core.database.MediaAssetDao
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.core.database.OutboxDao
import com.lezi.babylog.core.database.RecordDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class LocalReplicaClearScope {
    RecordsOnly,
    AllLocal,
}

/**
 * Owns the post-domain-commit cleanup of the local sync replica.
 *
 * [barrier] is the same mutex used by pull/apply, so the domain callback and
 * every replica cleanup step are observed as one indivisible local operation.
 */
internal class LocalReplicaClearCoordinator(
    private val barrier: Mutex,
    private val preferences: SyncPreferences,
    private val outboxDao: OutboxDao,
    private val recordDao: RecordDao,
    private val babyDao: BabyDao,
    private val mediaDao: MediaAssetDao,
    private val mediaFiles: SyncMediaFileStore,
) {
    private var pendingCommittedSnapshot: ClearSnapshot? = null

    suspend fun clear(
        scope: LocalReplicaClearScope,
        clearLocal: suspend (onCommitted: () -> Unit) -> Unit,
    ): Result<Unit> = runCatching {
        barrier.withLock {
            pendingCommittedSnapshot?.let { finishCommitted(it) }
            val snapshot = snapshot(scope)
            var domainCommitted = false
            try {
                clearLocal { domainCommitted = true }
                check(domainCommitted) { scope.missingCommitMessage }
            } catch (error: Throwable) {
                if (!domainCommitted) throw error
                pendingCommittedSnapshot = snapshot
                try {
                    finish(snapshot)
                    pendingCommittedSnapshot = null
                } catch (cleanupError: Throwable) {
                    error.addSuppressed(cleanupError)
                }
                throw LocalClearCommittedException(
                    familyServerRetained = snapshot.familyServerRetained,
                    cause = error,
                )
            }
            finishCommitted(snapshot)
        }
    }

    private suspend fun snapshot(scope: LocalReplicaClearScope): ClearSnapshot {
        val session = preferences.session.first()
        val media = when (scope) {
            LocalReplicaClearScope.RecordsOnly ->
                mediaDao.listAllIncludingDeleted().filter { it.kind == "log" }
            LocalReplicaClearScope.AllLocal -> mediaDao.listAllIncludingDeleted()
        }
        val paths = buildList {
            addAll(media.map(MediaAssetEntity::localUri))
            recordDao.listAllIncludingDeleted().forEach { record ->
                addAll(localPhotoPaths(record.payloadJson))
            }
            if (scope == LocalReplicaClearScope.AllLocal) {
                babyDao.listAllIncludingDeleted().forEach { baby ->
                    baby.avatarPath?.takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }.filter(String::isNotBlank).distinct()
        return ClearSnapshot(
            scope = scope,
            session = session,
            media = media,
            localMediaPaths = paths,
        )
    }

    private suspend fun finish(snapshot: ClearSnapshot) {
        when (snapshot.scope) {
            LocalReplicaClearScope.RecordsOnly -> finishRecordsOnly(snapshot)
            LocalReplicaClearScope.AllLocal -> finishAllLocal(snapshot)
        }
    }

    private suspend fun finishCommitted(snapshot: ClearSnapshot) {
        pendingCommittedSnapshot = snapshot
        try {
            finish(snapshot)
            pendingCommittedSnapshot = null
        } catch (error: Throwable) {
            try {
                finish(snapshot)
                pendingCommittedSnapshot = null
            } catch (retryError: Throwable) {
                error.addSuppressed(retryError)
            }
            throw LocalClearCommittedException(
                familyServerRetained = snapshot.familyServerRetained,
                cause = error,
            )
        }
    }

    private suspend fun finishRecordsOnly(snapshot: ClearSnapshot) {
        val familyId = snapshot.session.familyId
        if (familyId.isNotBlank()) {
            outboxDao.deleteType(familyId, "record")
            outboxDao.deleteType(familyId, "care_plan")
            outboxDao.deleteType(familyId, "fulfillment_candidate")
            snapshot.media.map(MediaAssetEntity::clientUuid)
                .chunked(OUTBOX_DELETE_CHUNK_SIZE)
                .forEach { chunk ->
                    outboxDao.deleteEntities(familyId, "media", chunk)
                }
        }
        snapshot.localMediaPaths.forEach { mediaFiles.delete(it) }
        mediaDao.deleteLogMedia()
        // Keep the server incarnation as the next mutation's recovery precondition.
        preferences.updateCursor(0, generation = snapshot.session.pullGeneration)
    }

    private suspend fun finishAllLocal(snapshot: ClearSnapshot) {
        outboxDao.deleteAll()
        snapshot.localMediaPaths.forEach { mediaFiles.delete(it) }
        mediaDao.deleteAll()
        // The next join/create starts with no local replica or server incarnation.
        preferences.updateCursor(0, generation = "")
    }

    private data class ClearSnapshot(
        val scope: LocalReplicaClearScope,
        val session: SyncSession,
        val media: List<MediaAssetEntity>,
        val localMediaPaths: List<String>,
    ) {
        val familyServerRetained: Boolean = session.familyId.isNotBlank()
    }
}

private val LocalReplicaClearScope.missingCommitMessage: String
    get() = when (this) {
        LocalReplicaClearScope.RecordsOnly -> "本机记录清除未确认领域事务已提交"
        LocalReplicaClearScope.AllLocal -> "本机数据清除未确认领域事务已提交"
    }

private const val OUTBOX_DELETE_CHUNK_SIZE = 400
