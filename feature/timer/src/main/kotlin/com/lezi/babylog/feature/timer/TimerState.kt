package com.lezi.babylog.feature.timer

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.lezi.babylog.core.common.newClientUuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
        savedBootCount?.let { put("savedBootCount", it) }
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
                val savedElapsed = o.optionalLong("savedElapsed")
                val savedWall = o.optionalLong("savedWall")
                val savedBootCount = o.optionalLong("savedBootCount")?.takeIf { it >= 0L }
                val drift = restoredRunningDelta(
                    savedElapsed = savedElapsed,
                    savedWall = savedWall,
                    nowElapsed = nowElapsed,
                    nowWall = nowWall,
                    savedBootCount = savedBootCount,
                    nowBootCount = nowBootCount,
                )
                var leftAccum = o.optionalLong("leftAccumMs") ?: 0L
                var rightAccum = o.optionalLong("rightAccumMs") ?: 0L
                val leftRunning = o.optionalBoolean("leftRunning")
                val rightRunning = o.optionalBoolean("rightRunning")
                // Freeze restored running sides into accumulated time.
                if (leftRunning) leftAccum += drift
                if (rightRunning) rightAccum += drift
                TimerState(
                    babyId = o.optionalLong("babyId"),
                    completionClientUuid = o.optionalString("completionClientUuid")
                        ?.takeIf { it.isNotBlank() },
                    carePlanId = o.optionalLong("carePlanId")?.takeIf { it > 0L },
                    leftRunning = false,
                    rightRunning = false,
                    leftAccumMs = leftAccum,
                    rightAccumMs = rightAccum,
                    leftStartedElapsed = null,
                    rightStartedElapsed = null,
                    sessionStartedAt = o.optionalLong("sessionStartedAt"),
                    lastSide = o.optionalString("lastSide")?.takeIf { it.isNotBlank() },
                    order = o.optionalString("order").orEmpty(),
                )
            }.getOrDefault(TimerState())
        }
    }
}

internal fun TimerState.withStableCompletionId(
    createId: () -> String = ::newClientUuid,
): TimerState = if (hasTimerData() && completionClientUuid == null) {
    copy(completionClientUuid = createId())
} else {
    this
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

private fun JsonObject.optionalLong(key: String): Long? =
    get(key)?.jsonPrimitive?.longOrNull

private fun JsonObject.optionalBoolean(key: String): Boolean =
    get(key)?.jsonPrimitive?.booleanOrNull ?: false

private fun JsonObject.optionalString(key: String): String? =
    get(key)?.jsonPrimitive?.contentOrNull

/**
 * Calculates time accrued after the last persisted timer snapshot.
 *
 * elapsedRealtime is immune to wall-clock edits while the device remains
 * booted. Android's boot count identifies a reboot even when the new uptime is
 * already greater than the persisted uptime. Legacy snapshots without a boot
 * count retain the previous conservative uptime-rewind fallback.
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
        // A pre-boot-count snapshot may already span a reboot even when the
        // new uptime is larger. Its wall pair is the only cross-boot clock
        // available, so migrate it conservatively on this first restore.
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
