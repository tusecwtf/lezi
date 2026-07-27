package com.lezi.babylog.core.database

/**
 * Durable reminder-cleanup kinds understood by callers.
 *
 * Room keys and collection encoding stay private to the database adapter.
 */
enum class PendingReminderCleanupOperation {
    RECORDS_CLEAR,
}

data class PendingReminderCleanup(
    val operation: PendingReminderCleanupOperation,
    val calendarEventIds: Set<Long>,
    val carePlanIds: Set<Long> = emptySet(),
    val familyServerRetained: Boolean,
)

interface PendingReminderCleanupStore {
    suspend fun load(operation: PendingReminderCleanupOperation): PendingReminderCleanup?

    /**
     * Merge a new hand-off into pending work.
     *
     * Reminder ids are deduplicated and retention can only be promoted to true.
     */
    suspend fun upsert(pending: PendingReminderCleanup)

    suspend fun delete(operation: PendingReminderCleanupOperation)
}

class CorruptPendingReminderCleanupException internal constructor(
    val operation: PendingReminderCleanupOperation,
    val familyServerRetained: Boolean,
    reminderKind: String,
    invalidToken: String,
) : IllegalStateException(
    "Corrupt pending reminder cleanup ${operation.name}: invalid $reminderKind id " +
        "'${invalidToken.take(MAX_DIAGNOSTIC_TOKEN_LENGTH)}'",
)

internal class RoomPendingReminderCleanupStore(
    private val dao: PendingReminderCleanupDao,
) : PendingReminderCleanupStore {
    override suspend fun load(
        operation: PendingReminderCleanupOperation,
    ): PendingReminderCleanup? =
        dao.get(operation.storageKey)?.toSnapshot(operation)

    override suspend fun upsert(pending: PendingReminderCleanup) {
        require(pending.calendarEventIds.all { it > 0L }) {
            "Pending reminder cleanup calendar event ids must be positive"
        }
        require(pending.carePlanIds.all { it > 0L }) {
            "Pending reminder cleanup care-plan ids must be positive"
        }
        val existing = load(pending.operation)
        dao.upsert(
            PendingReminderCleanupEntity(
                operation = pending.operation.storageKey,
                calendarEventIds =
                    (existing?.calendarEventIds.orEmpty() + pending.calendarEventIds)
                        .sorted()
                        .joinToString(","),
                carePlanIds =
                    (existing?.carePlanIds.orEmpty() + pending.carePlanIds)
                        .sorted()
                        .joinToString(","),
                familyServerRetained =
                    existing?.familyServerRetained == true || pending.familyServerRetained,
            ),
        )
    }

    override suspend fun delete(operation: PendingReminderCleanupOperation) {
        dao.delete(operation.storageKey)
    }

    private fun PendingReminderCleanupEntity.toSnapshot(
        typedOperation: PendingReminderCleanupOperation,
    ): PendingReminderCleanup =
        PendingReminderCleanup(
            operation = typedOperation,
            calendarEventIds = decodeReminderIds(
                encoded = calendarEventIds,
                operation = typedOperation,
                familyServerRetained = familyServerRetained,
                reminderKind = "calendar event",
            ),
            carePlanIds = decodeReminderIds(
                encoded = carePlanIds,
                operation = typedOperation,
                familyServerRetained = familyServerRetained,
                reminderKind = "care-plan",
            ),
            familyServerRetained = familyServerRetained,
        )
}

private val PendingReminderCleanupOperation.storageKey: String
    get() = when (this) {
        PendingReminderCleanupOperation.RECORDS_CLEAR -> "records_clear"
    }

private fun decodeReminderIds(
    encoded: String,
    operation: PendingReminderCleanupOperation,
    familyServerRetained: Boolean,
    reminderKind: String,
): Set<Long> {
    if (encoded.isEmpty()) return emptySet()
    return encoded.split(',').mapTo(linkedSetOf()) { token ->
        val normalized = token.trim()
        val id = normalized.toLongOrNull()
        if (normalized.isEmpty() || id == null || id <= 0L) {
            throw CorruptPendingReminderCleanupException(
                operation = operation,
                familyServerRetained = familyServerRetained,
                reminderKind = reminderKind,
                invalidToken = token,
            )
        }
        id
    }
}

private const val MAX_DIAGNOSTIC_TOKEN_LENGTH = 64
