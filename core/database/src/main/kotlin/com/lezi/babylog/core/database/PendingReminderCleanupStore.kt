package com.lezi.babylog.core.database

import com.lezi.babylog.core.model.NursingTimerClearEpoch
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
    /**
     * Nursing timer clear epoch (JSON + session token) captured under exclusion.
     * Encoded and merged as one unit so partial pairs cannot drift on recovery.
     */
    val nursingTimer: NursingTimerClearEpoch = NursingTimerClearEpoch.EMPTY,
    val familyServerRetained: Boolean,
) {
    val nursingTimerJson: String? get() = nursingTimer.json
    val nursingTimerSessionToken: String? get() = nursingTimer.sessionToken
}

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
        require(pending.nursingTimerSessionToken?.isBlank() != true) {
            "Pending nursing timer session token must not be blank when present"
        }
        dao.mergeUpsert(pending.scope.reminderOperationKey) { existingEntity ->
            val existing = existingEntity?.toSnapshot(pending.scope)
            // Prefer the first non-empty captured timer epoch as one unit so a later
            // empty snapshot cannot drop recovery, and json/token stay paired.
            val nursingTimer = when {
                existing != null && !existing.nursingTimer.isEmpty -> existing.nursingTimer
                else -> pending.nursingTimer
            }
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
                nextFeedAt = null,
                nextFeedEpoch = encodeNursingTimerEpoch(nursingTimer),
                familyServerRetained =
                    existing?.familyServerRetained == true || pending.familyServerRetained,
            )
        }
    }

    override suspend fun delete(scope: LocalDataClearScope) {
        dao.delete(scope.reminderOperationKey)
    }

    private fun PendingReminderCleanupEntity.toSnapshot(
        typedScope: LocalDataClearScope,
    ): PendingReminderCleanup {
        val timerEpoch = decodeNursingTimerEpoch(
            encoded = nextFeedEpoch,
            scope = typedScope,
            familyServerRetained = familyServerRetained,
        )
        return PendingReminderCleanup(
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
            nursingTimer = timerEpoch,
            familyServerRetained = familyServerRetained,
        )
    }
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

/**
 * Persist timer epoch in the legacy `nextFeedEpoch` TEXT column without a Room
 * schema bump. Empty string means no timer was captured (legacy rows stay valid).
 */
private fun encodeNursingTimerEpoch(epoch: NursingTimerClearEpoch): String {
    if (epoch.isEmpty) return ""
    return buildJsonObject {
        if (epoch.json != null) {
            put("nursingTimerJson", JsonPrimitive(epoch.json))
        } else {
            put("nursingTimerJson", JsonNull)
        }
        if (epoch.sessionToken != null) {
            put("nursingTimerSessionToken", JsonPrimitive(epoch.sessionToken))
        } else {
            put("nursingTimerSessionToken", JsonNull)
        }
    }.toString()
}

private fun decodeNursingTimerEpoch(
    encoded: String,
    scope: LocalDataClearScope,
    familyServerRetained: Boolean,
): NursingTimerClearEpoch {
    if (encoded.isEmpty()) {
        return NursingTimerClearEpoch.EMPTY
    }
    val objectValue = try {
        Json.parseToJsonElement(encoded) as? JsonObject
            ?: throw IllegalArgumentException("not an object")
    } catch (_: Exception) {
        throw CorruptPendingReminderCleanupException(
            scope = scope,
            familyServerRetained = familyServerRetained,
            reminderKind = "nursing timer epoch",
            invalidToken = encoded,
        )
    }
    fun readOptionalString(key: String): String? {
        val element = objectValue[key] ?: return null
        return when (element) {
            JsonNull -> null
            is JsonPrimitive -> {
                if (!element.isString || element.content.isBlank()) {
                    throw CorruptPendingReminderCleanupException(
                        scope = scope,
                        familyServerRetained = familyServerRetained,
                        reminderKind = "nursing timer epoch",
                        invalidToken = "$key=$element",
                    )
                }
                element.content
            }
            else -> throw CorruptPendingReminderCleanupException(
                scope = scope,
                familyServerRetained = familyServerRetained,
                reminderKind = "nursing timer epoch",
                invalidToken = "$key=$element",
            )
        }
    }
    return NursingTimerClearEpoch(
        json = readOptionalString("nursingTimerJson"),
        sessionToken = readOptionalString("nursingTimerSessionToken"),
    )
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
