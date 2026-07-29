package com.lezi.babylog.feature.log

import com.lezi.babylog.core.model.MAX_RECORD_PHOTOS

/**
 * Owns Composer's transient photo contract independently from persisted MediaAsset ownership.
 *
 * Plan photos in a fulfillment draft are borrowed references. Only paths imported by this draft
 * are candidates for abandon/remove cleanup. Persisted source paths are owned by MediaAsset rows;
 * after commit, reference-aware domain cleanup is their only physical deletion authority.
 */
internal class RecordComposerPhotoLifecycle(
    private val deleteFiles: suspend (Collection<String>) -> Unit,
) {
    fun fulfillmentDraft(
        base: QuickRecordDraft,
        planPhotos: List<String>,
    ): QuickRecordDraft = base.copy(
        photos = planPhotos,
        sourcePhotos = emptyList(),
        borrowedPhotos = planPhotos,
        ownedDraftPhotos = emptyList(),
    )

    fun imported(
        draft: QuickRecordDraft,
        importedPhotos: List<String>,
    ): QuickRecordDraft {
        val visible = (draft.photos + importedPhotos).distinct().take(MAX_RECORD_PHOTOS)
        val protected = draft.sourcePhotos.toSet() + draft.borrowedPhotos.toSet()
        val acceptedOwned = importedPhotos.filter { it in visible && it !in protected }
        return draft.copy(
            photos = visible,
            ownedDraftPhotos = (draft.ownedDraftPhotos + acceptedOwned).distinct(),
        )
    }

    fun removed(draft: QuickRecordDraft, path: String): QuickRecordDraft =
        draft.copy(photos = draft.photos.filterNot { it == path })

    suspend fun cleanupRemoved(before: QuickRecordDraft, after: QuickRecordDraft) {
        deleteFiles(
            ownedCleanupCandidates(before).filter { it !in after.photos },
        )
    }

    suspend fun cleanupAbandoned(draft: QuickRecordDraft) {
        deleteFiles(ownedCleanupCandidates(draft))
    }

    suspend fun cleanupAfterCommit(draft: QuickRecordDraft) {
        val discardedOwned = ownedCleanupCandidates(draft).filter { it !in draft.photos }
        deleteFiles(discardedOwned)
    }

    private fun ownedCleanupCandidates(draft: QuickRecordDraft): List<String> {
        val protected = draft.sourcePhotos.toSet() + draft.borrowedPhotos.toSet()
        return draft.ownedDraftPhotos.distinct().filterNot(protected::contains)
    }
}
