package com.lezi.babylog.feature.timer

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class TimerState(
    val babyId: Long? = null,
    /** Stable idempotency key retained until this timer session is cleared. */
    val completionClientUuid: String? = null,
    /**
     * Optional open nursing CarePlan bound to this timer session. Survives
     * process death via DataStore JSON; cleared with the session. Plan stays
     * pending until [com.lezi.babylog.domain.CareLog.completeNursing] succeeds.
     */
    val carePlanId: Long? = null,
    val leftRunning: Boolean = false,
    val rightRunning: Boolean = false,
    val leftAccumMs: Long = 0L,
    val rightAccumMs: Long = 0L,
    val leftStartedElapsed: Long? = null,
    val rightStartedElapsed: Long? = null,
    val sessionStartedAt: Long? = null,
    val lastSide: String? = null,
    val order: String = "",
) {
    fun leftMs(nowElapsed: Long = SystemClock.elapsedRealtime()): Long =
        leftAccumMs + if (leftRunning && leftStartedElapsed != null) nowElapsed - leftStartedElapsed else 0L

    fun rightMs(nowElapsed: Long = SystemClock.elapsedRealtime()): Long =
        rightAccumMs + if (rightRunning && rightStartedElapsed != null) nowElapsed - rightStartedElapsed else 0L

    fun toJson(
        savedElapsed: Long = SystemClock.elapsedRealtime(),
        savedWall: Long = System.currentTimeMillis(),
        savedBootCount: Long? = null,
    ): String = buildJsonObject {
        put("schemaVersion", TIMER_STATE_SCHEMA_VERSION)
        putNullableLong("babyId", babyId)
        if (completionClientUuid == null) {
            put("completionClientUuid", JsonNull)
        } else {
            put("completionClientUuid", completionClientUuid)
        }
        putNullableLong("carePlanId", carePlanId)
        put("leftRunning", leftRunning)
        put("rightRunning", rightRunning)
        put("leftAccumMs", leftAccumMs)
        put("rightAccumMs", rightAccumMs)
        putNullableLong("leftStartedElapsed", leftStartedElapsed)
        putNullableLong("rightStartedElapsed", rightStartedElapsed)
        putNullableLong("sessionStartedAt", sessionStartedAt)
        if (lastSide == null) put("lastSide", JsonNull) else put("lastSide", lastSide)
        put("order", order)
        put("savedElapsed", savedElapsed)
        put("savedWall", savedWall)
        putNullableLong("savedBootCount", savedBootCount)
    }.toString()

    companion object {
        fun fromJson(
            raw: String?,
            nowElapsed: Long = SystemClock.elapsedRealtime(),
            nowWall: Long = System.currentTimeMillis(),
            nowBootCount: Long? = null,
        ): TimerState {
            if (raw.isNullOrBlank()) return TimerState()
            return runCatching {
                val o = Json.parseToJsonElement(raw).jsonObject
                require(o.requiredLong("schemaVersion") == TIMER_STATE_SCHEMA_VERSION.toLong())
                val savedElapsed = o.requiredLong("savedElapsed")
                val savedWall = o.requiredLong("savedWall")
                val savedBootCount = o.requiredNullableLong("savedBootCount")
                    ?.also { require(it >= 0L) }
                val drift = restoredRunningDelta(
                    savedElapsed = savedElapsed,
                    savedWall = savedWall,
                    nowElapsed = nowElapsed,
                    nowWall = nowWall,
                    savedBootCount = savedBootCount,
                    nowBootCount = nowBootCount,
                )
                var leftAccum = o.requiredLong("leftAccumMs").also { require(it >= 0L) }
                var rightAccum = o.requiredLong("rightAccumMs").also { require(it >= 0L) }
                val leftRunning = o.requiredBoolean("leftRunning")
                val rightRunning = o.requiredBoolean("rightRunning")
                // Freeze restored running sides into accumulated time.
                if (leftRunning) leftAccum += drift
                if (rightRunning) rightAccum += drift
                TimerState(
                    babyId = o.requiredNullableLong("babyId")?.also { require(it > 0L) },
                    completionClientUuid = o.requiredNullableString("completionClientUuid")
                        ?.takeIf { it.isNotBlank() },
                    carePlanId = o.requiredNullableLong("carePlanId")?.also { require(it > 0L) },
                    leftRunning = false,
                    rightRunning = false,
                    leftAccumMs = leftAccum,
                    rightAccumMs = rightAccum,
                    leftStartedElapsed = null,
                    rightStartedElapsed = null,
                    sessionStartedAt = o.requiredNullableLong("sessionStartedAt"),
                    lastSide = o.requiredNullableString("lastSide")?.takeIf { it.isNotBlank() },
                    order = o.requiredString("order"),
                ).also { restored ->
                    require(!restored.hasTimerData() || restored.completionClientUuid != null)
                }
            }.getOrDefault(TimerState())
        }
    }
}

internal fun TimerState.hasTimerData(): Boolean =
    leftRunning ||
        rightRunning ||
        leftAccumMs > 0L ||
        rightAccumMs > 0L ||
        sessionStartedAt != null

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullableLong(
    key: String,
    value: Long?,
) {
    if (value == null) put(key, JsonNull) else put(key, value)
}

private fun JsonObject.requiredLong(key: String): Long =
    get(key)?.jsonPrimitive?.longOrNull
        ?: throw IllegalArgumentException("Missing or invalid $key")

private fun JsonObject.requiredNullableLong(key: String): Long? {
    require(key in this) { "Missing $key" }
    val value = get(key)
    if (value === JsonNull) return null
    return value?.jsonPrimitive?.longOrNull
        ?: throw IllegalArgumentException("Invalid $key")
}

private fun JsonObject.requiredBoolean(key: String): Boolean =
    get(key)?.jsonPrimitive?.booleanOrNull
        ?: throw IllegalArgumentException("Missing or invalid $key")

private fun JsonObject.requiredString(key: String): String {
    val value = get(key)?.jsonPrimitive
        ?: throw IllegalArgumentException("Missing or invalid $key")
    require(value.isString) { "Invalid $key" }
    return value.content
}

private fun JsonObject.requiredNullableString(key: String): String? {
    require(key in this) { "Missing $key" }
    val value = get(key)
    if (value === JsonNull) return null
    val primitive = value?.jsonPrimitive
        ?: throw IllegalArgumentException("Invalid $key")
    require(primitive.isString) { "Invalid $key" }
    return primitive.content
}

/**
 * Calculates time accrued after the last persisted timer snapshot.
 *
 * elapsedRealtime is immune to wall-clock edits while the device remains
 * booted. Android's boot count identifies a reboot even when the new uptime is
 * already greater than the persisted uptime. When boot identity is temporarily
 * unavailable, the wall/elapsed pair provides a fail-closed degraded path.
 */
internal fun restoredRunningDelta(
    savedElapsed: Long?,
    savedWall: Long?,
    nowElapsed: Long,
    nowWall: Long,
    savedBootCount: Long? = null,
    nowBootCount: Long? = null,
): Long {
    if (savedBootCount == null && nowBootCount != null) {
        // A snapshot captured while boot identity was unavailable may span a
        // reboot. Its wall pair is the only cross-boot clock available.
        return savedWall?.let { (nowWall - it).coerceAtLeast(0L) } ?: 0L
    }
    if (savedBootCount != null && nowBootCount != null) {
        if (savedBootCount != nowBootCount) {
            return savedWall?.let { (nowWall - it).coerceAtLeast(0L) } ?: 0L
        }
        return savedElapsed?.let { (nowElapsed - it).coerceAtLeast(0L) } ?: 0L
    }

    val elapsedDelta = savedElapsed?.let { nowElapsed - it }
    if (elapsedDelta != null && elapsedDelta >= 0L) return elapsedDelta
    return savedWall?.let { (nowWall - it).coerceAtLeast(0L) } ?: 0L
}

internal fun safeBootCount(readBootCount: () -> Int): Long? =
    try {
        readBootCount().toLong().takeIf { it >= 0L }
    } catch (_: Exception) {
        null
    }

internal fun currentBootCount(context: Context): Long? =
    safeBootCount {
        Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.BOOT_COUNT,
        )
    }

internal const val TIMER_STATE_SCHEMA_VERSION = 1

/**
 * Pure left-side toggle transition used by [TimerViewModel.toggleLeft].
 *
 * When starting the left side while [TimerState.babyId] is null, [babyIdForStart]
 * must be supplied; otherwise this returns null so the caller can abort.
 * Callers that serialize concurrent toggles (mutex / single queue) must re-read
 * the latest state before each application so neither side's accum is lost.
 */
internal fun TimerState.withToggleLeft(
    nowElapsed: Long,
    nowWall: Long,
    babyIdForStart: Long? = null,
    completionClientUuidForStart: String? = null,
): TimerState? {
    var cur = this
    if (!cur.leftRunning && cur.babyId == null) {
        val babyId = babyIdForStart ?: return null
        cur = cur.copy(babyId = babyId)
    }
    if (cur.completionClientUuid == null && completionClientUuidForStart != null) {
        cur = cur.copy(completionClientUuid = completionClientUuidForStart)
    }
    return if (cur.leftRunning) {
        cur.copy(
            leftRunning = false,
            leftAccumMs = cur.leftMs(nowElapsed),
            leftStartedElapsed = null,
            lastSide = "L",
        )
    } else {
        val pausedRight = if (cur.rightRunning) {
            cur.copy(
                rightRunning = false,
                rightAccumMs = cur.rightMs(nowElapsed),
                rightStartedElapsed = null,
                lastSide = "R",
            )
        } else {
            cur
        }
        pausedRight.copy(
            leftRunning = true,
            leftStartedElapsed = nowElapsed,
            sessionStartedAt = pausedRight.sessionStartedAt ?: nowWall,
            order = when {
                pausedRight.order.isEmpty() -> "L"
                pausedRight.order == "R" -> "RL"
                else -> pausedRight.order
            },
            lastSide = "L",
        )
    }
}

/**
 * Pure right-side toggle transition used by [TimerViewModel.toggleRight].
 *
 * See [withToggleLeft] for baby-id and concurrency notes.
 */
internal fun TimerState.withToggleRight(
    nowElapsed: Long,
    nowWall: Long,
    babyIdForStart: Long? = null,
    completionClientUuidForStart: String? = null,
): TimerState? {
    var cur = this
    if (!cur.rightRunning && cur.babyId == null) {
        val babyId = babyIdForStart ?: return null
        cur = cur.copy(babyId = babyId)
    }
    if (cur.completionClientUuid == null && completionClientUuidForStart != null) {
        cur = cur.copy(completionClientUuid = completionClientUuidForStart)
    }
    return if (cur.rightRunning) {
        cur.copy(
            rightRunning = false,
            rightAccumMs = cur.rightMs(nowElapsed),
            rightStartedElapsed = null,
            lastSide = "R",
        )
    } else {
        val pausedLeft = if (cur.leftRunning) {
            cur.copy(
                leftRunning = false,
                leftAccumMs = cur.leftMs(nowElapsed),
                leftStartedElapsed = null,
                lastSide = "L",
            )
        } else {
            cur
        }
        pausedLeft.copy(
            rightRunning = true,
            rightStartedElapsed = nowElapsed,
            sessionStartedAt = pausedLeft.sessionStartedAt ?: nowWall,
            lastSide = "R",
            order = when {
                pausedLeft.order.isEmpty() -> "R"
                pausedLeft.order == "L" -> "LR"
                else -> pausedLeft.order
            },
        )
    }
}
