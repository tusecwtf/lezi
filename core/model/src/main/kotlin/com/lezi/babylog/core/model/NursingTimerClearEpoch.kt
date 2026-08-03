package com.lezi.babylog.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Stable JSON key for the nursing timer session epoch inside persisted timer JSON
 * ([TimerState.completionClientUuid] wire field). Owned next to the clear-epoch
 * contract so core:datastore does not hard-code feature-owned field names alone.
 */
const val NURSING_TIMER_SESSION_TOKEN_JSON_KEY = "completionClientUuid"

/**
 * Captured nursing timer clear epoch: exact DataStore JSON plus the stable session
 * token used for FGS stop / ABA protection.
 *
 * Always capture, encode, merge, and CAS-remove as one unit so json/token pairs
 * cannot drift independently across pending recovery merges.
 */
data class NursingTimerClearEpoch(
    val json: String? = null,
    val sessionToken: String? = null,
) {
    val isEmpty: Boolean get() = json == null && sessionToken == null

    companion object {
        val EMPTY = NursingTimerClearEpoch()

        /** Capture from the live nursing timer preference value. */
        fun captureFromJson(raw: String?): NursingTimerClearEpoch {
            val json = raw?.takeIf { it.isNotBlank() } ?: return EMPTY
            return NursingTimerClearEpoch(
                json = json,
                sessionToken = nursingTimerSessionToken(json),
            )
        }
    }
}

/**
 * Best-effort extraction of the stable nursing timer session token from persisted
 * timer JSON. Malformed or token-less snapshots yield null.
 */
fun nursingTimerSessionToken(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        val root = Json.parseToJsonElement(raw) as? JsonObject ?: return null
        val value = root[NURSING_TIMER_SESSION_TOKEN_JSON_KEY] ?: return null
        if (value is JsonNull) return null
        val primitive = value as? JsonPrimitive ?: return null
        primitive.content.takeIf { it.isNotBlank() && primitive.isString }
    }.getOrNull()
}

/**
 * Whether [currentJson] still belongs to [captured] and may be compare-and-removed.
 *
 * Primary match is session token equality so same-session rewrites
 * (`savedElapsed` / transitions) still clear. Secondary match is exact JSON
 * equality for the rare token-less legacy edge only when capture stored both.
 */
fun shouldCasRemoveNursingTimerJson(
    captured: NursingTimerClearEpoch,
    currentJson: String?,
): Boolean {
    if (captured.isEmpty) return false
    if (currentJson.isNullOrBlank()) return false
    val capturedToken = captured.sessionToken
    if (!capturedToken.isNullOrBlank()) {
        val currentToken = nursingTimerSessionToken(currentJson)
        if (currentToken == capturedToken) return true
    }
    return currentJson == captured.json
}

/**
 * Session-scoped FGS stop decision for local-clear finalization.
 *
 * Only when the process witness [activeSession] exactly equals [capturedSession]
 * may cleanup request a token-scoped stop. Callers pass their latest
 * STARTING-or-RUNNING witness; unknown (`null`) remains fail-closed.
 */
fun shouldStopCapturedNursingTimerSession(
    activeSession: String?,
    capturedSession: String?,
): Boolean {
    if (capturedSession.isNullOrBlank()) return false
    return activeSession == capturedSession
}
