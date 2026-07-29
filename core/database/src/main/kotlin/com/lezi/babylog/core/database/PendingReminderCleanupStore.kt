package com.lezi.babylog.core.database

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

data class PendingReminderCleanup(
    val scope: LocalDataClearScope,
    val carePlanIds: Set<Long> = emptySet(),
    /** Exact stable plan UUID -> provider event ID; null means recover through UID lookup only. */
    val systemCalendarProjections: Map<String, String?> = emptyMap(),
    val currentBabyId: Long? = null,
    val nextFeedAt: Long? = null,
    val nextFeedEpoch: String = "",
    val familyServerRetained: Boolean,
)

interface PendingReminderCleanupStore {
    suspend fun load(scope: LocalDataClearScope): PendingReminderCleanup?

    /**
     * Merge a new hand-off into pending work.
     *
     * Care-plan reminder ids are deduplicated and retention can only be promoted to true.
     */
    suspend fun upsert(pending: PendingReminderCleanup)

    suspend fun delete(scope: LocalDataClearScope)
}

class CorruptPendingReminderCleanupException internal constructor(
    val scope: LocalDataClearScope,
    val familyServerRetained: Boolean,
    reminderKind: String,
    invalidToken: String,
) : IllegalStateException(
    "Corrupt pending reminder cleanup ${scope.name}: invalid $reminderKind id " +
        "'${invalidToken.take(MAX_DIAGNOSTIC_TOKEN_LENGTH)}'",
)

internal class RoomPendingReminderCleanupStore(
    private val dao: PendingReminderCleanupDao,
) : PendingReminderCleanupStore {
    override suspend fun load(
        scope: LocalDataClearScope,
    ): PendingReminderCleanup? =
        dao.get(scope.reminderOperationKey)?.toSnapshot(scope)

    override suspend fun upsert(pending: PendingReminderCleanup) {
        require(pending.carePlanIds.all { it > 0L }) {
            "Pending reminder cleanup care-plan ids must be positive"
        }
        require(
            pending.systemCalendarProjections.none { (clientUuid, eventId) ->
                clientUuid.isBlank() || eventId?.isBlank() == true
            },
        ) {
            "Pending reminder cleanup projection identities must not be blank"
        }
        val existing = load(pending.scope)
        dao.upsert(
            PendingReminderCleanupEntity(
                operation = pending.scope.reminderOperationKey,
                carePlanIds =
                    (existing?.carePlanIds.orEmpty() + pending.carePlanIds)
                        .sorted()
                        .joinToString(","),
                systemCalendarProjectionsJson = encodeSystemCalendarProjections(
                    existing?.systemCalendarProjections.orEmpty() +
                        pending.systemCalendarProjections,
                ),
                currentBabyId = pending.currentBabyId,
                nextFeedAt = pending.nextFeedAt,
                nextFeedEpoch = pending.nextFeedEpoch,
                familyServerRetained =
                    existing?.familyServerRetained == true || pending.familyServerRetained,
            ),
        )
    }

    override suspend fun delete(scope: LocalDataClearScope) {
        dao.delete(scope.reminderOperationKey)
    }

    private fun PendingReminderCleanupEntity.toSnapshot(
        typedScope: LocalDataClearScope,
    ): PendingReminderCleanup =
        PendingReminderCleanup(
            scope = typedScope,
            carePlanIds = decodeReminderIds(
                encoded = carePlanIds,
                scope = typedScope,
                familyServerRetained = familyServerRetained,
                reminderKind = "care-plan",
            ),
            systemCalendarProjections = decodeSystemCalendarProjections(
                encoded = systemCalendarProjectionsJson,
                scope = typedScope,
                familyServerRetained = familyServerRetained,
            ),
            currentBabyId = currentBabyId,
            nextFeedAt = nextFeedAt,
            nextFeedEpoch = nextFeedEpoch,
            familyServerRetained = familyServerRetained,
        )
}

private fun decodeReminderIds(
    encoded: String,
    scope: LocalDataClearScope,
    familyServerRetained: Boolean,
    reminderKind: String,
): Set<Long> {
    if (encoded.isEmpty()) return emptySet()
    return encoded.split(',').mapTo(linkedSetOf()) { token ->
        val normalized = token.trim()
        val id = normalized.toLongOrNull()
        if (normalized.isEmpty() || id == null || id <= 0L) {
            throw CorruptPendingReminderCleanupException(
                scope = scope,
                familyServerRetained = familyServerRetained,
                reminderKind = reminderKind,
                invalidToken = token,
            )
        }
        id
    }
}

private fun encodeSystemCalendarProjections(values: Map<String, String?>): String =
    buildJsonObject {
        values.toSortedMap().forEach { (clientUuid, eventId) ->
            if (eventId == null) put(clientUuid, JsonNull)
            else put(clientUuid, JsonPrimitive(eventId))
        }
    }.toString()

private fun decodeSystemCalendarProjections(
    encoded: String,
    scope: LocalDataClearScope,
    familyServerRetained: Boolean,
): Map<String, String?> {
    val objectValue = try {
        Json.parseToJsonElement(encoded) as? JsonObject
            ?: throw IllegalArgumentException("not an object")
    } catch (_: Exception) {
        throw CorruptPendingReminderCleanupException(
            scope = scope,
            familyServerRetained = familyServerRetained,
            reminderKind = "system calendar projection",
            invalidToken = encoded,
        )
    }
    return objectValue.entries.associate { (clientUuid, element) ->
        val eventId = when (element) {
            JsonNull -> null
            is JsonPrimitive -> element.takeIf { it.isString }?.content
            else -> null
        }
        if (clientUuid.isBlank() || (element !== JsonNull && eventId.isNullOrBlank())) {
            throw CorruptPendingReminderCleanupException(
                scope = scope,
                familyServerRetained = familyServerRetained,
                reminderKind = "system calendar projection",
                invalidToken = "$clientUuid=$element",
            )
        }
        clientUuid to eventId
    }
}

private const val MAX_DIAGNOSTIC_TOKEN_LENGTH = 64
