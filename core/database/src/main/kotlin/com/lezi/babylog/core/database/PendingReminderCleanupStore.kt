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
    /**
     * Exact nursing timer DataStore value captured under exclusion before Room commit.
     * Finish uses compare-and-remove so a post-commit newer session survives recovery.
     */
    val nursingTimerJson: String? = null,
    /** Stable session token for scoped FGS stop; null when no session was captured. */
    val nursingTimerSessionToken: String? = null,
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
        require(pending.nursingTimerSessionToken?.isBlank() != true) {
            "Pending nursing timer session token must not be blank when present"
        }
        val existing = load(pending.scope)
        // Prefer the first captured timer epoch so a later empty snapshot cannot
        // drop recovery of an older stop+clear that still needs to finish.
        val nursingTimerJson = existing?.nursingTimerJson ?: pending.nursingTimerJson
        val nursingTimerSessionToken =
            existing?.nursingTimerSessionToken ?: pending.nursingTimerSessionToken
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
                nextFeedAt = null,
                nextFeedEpoch = encodeNursingTimerEpoch(
                    nursingTimerJson = nursingTimerJson,
                    nursingTimerSessionToken = nursingTimerSessionToken,
                ),
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
            nursingTimerJson = timerEpoch.json,
            nursingTimerSessionToken = timerEpoch.sessionToken,
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

private data class NursingTimerEpoch(
    val json: String?,
    val sessionToken: String?,
)

/**
 * Persist timer epoch in the legacy `nextFeedEpoch` TEXT column without a Room
 * schema bump. Empty string means no timer was captured (legacy rows stay valid).
 */
private fun encodeNursingTimerEpoch(
    nursingTimerJson: String?,
    nursingTimerSessionToken: String?,
): String {
    if (nursingTimerJson == null && nursingTimerSessionToken == null) return ""
    return buildJsonObject {
        if (nursingTimerJson != null) {
            put("nursingTimerJson", JsonPrimitive(nursingTimerJson))
        } else {
            put("nursingTimerJson", JsonNull)
        }
        if (nursingTimerSessionToken != null) {
            put("nursingTimerSessionToken", JsonPrimitive(nursingTimerSessionToken))
        } else {
            put("nursingTimerSessionToken", JsonNull)
        }
    }.toString()
}

private fun decodeNursingTimerEpoch(
    encoded: String,
    scope: LocalDataClearScope,
    familyServerRetained: Boolean,
): NursingTimerEpoch {
    if (encoded.isEmpty()) {
        return NursingTimerEpoch(json = null, sessionToken = null)
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
    return NursingTimerEpoch(
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
