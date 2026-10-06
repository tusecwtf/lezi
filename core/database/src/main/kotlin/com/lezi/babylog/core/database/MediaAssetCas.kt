package com.lezi.babylog.core.database

/**
 * CAS match keys for atomic media prepare metadata and commit receipts.
 *
 * Authoritative SQL lives on [MediaAssetDao.mergePreparedMetadata] and
 * [MediaAssetDao.writeCommitReceipt] (see [MEDIA_ASSET_CAS_REVISION_WHERE]).
 * Map fakes and pure JVM doubles must call [matchesPublishedRevision] so unit
 * tests cannot green against a drifted predicate.
 */
const val MEDIA_ASSET_CAS_REVISION_WHERE: String =
    "clientUuid + updatedAt + localUri + deletedAt " +
        "(both null or equal; mirrors Room WHERE on prepare and receipt)"

/**
 * Whether [this] row is still the published domain revision for a prepare or
 * receipt write. Mirrors the Room CAS WHERE clause exactly for JVM callers.
 */
fun MediaAssetEntity.matchesPublishedRevision(
    expectedClientUuid: String,
    expectedUpdatedAt: Long,
    expectedLocalUri: String,
    expectedDeletedAt: Long?,
): Boolean =
    clientUuid == expectedClientUuid &&
        updatedAt == expectedUpdatedAt &&
        localUri == expectedLocalUri &&
        // Kotlin null == null matches SQL
        // `(deletedAt IS NULL AND :expectedDeletedAt IS NULL) OR deletedAt = :expectedDeletedAt`.
        deletedAt == expectedDeletedAt
