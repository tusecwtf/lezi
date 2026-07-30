package com.lezi.babylog.feature.log

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

/** One atomic wall-clock read used by every time-sensitive record-screen label. */
data class RecordScreenTimeSnapshot(
    val instant: Instant,
    val zoneId: ZoneId,
) {
    val epochMillis: Long
        get() = instant.toEpochMilli()

    val localDate: LocalDate
        get() = instant.atZone(zoneId).toLocalDate()

    val zonedDateTime: ZonedDateTime
        get() = instant.atZone(zoneId)
}

/** Time is the only mocked system boundary in record-screen clock tests. */
fun interface RecordScreenClock {
    fun snapshot(): RecordScreenTimeSnapshot
}

object SystemRecordScreenClock : RecordScreenClock {
    override fun snapshot(): RecordScreenTimeSnapshot = RecordScreenTimeSnapshot(
        instant = Instant.now(),
        zoneId = ZoneId.systemDefault(),
    )
}

/** One remembered collector per record-screen composition, active only while RESUMED. */
@Composable
internal fun rememberRecordScreenTime(
    clock: RecordScreenClock,
): State<RecordScreenTimeSnapshot> {
    val initialSnapshot = remember(clock) { clock.snapshot() }
    val minuteTicks = remember(clock) { recordScreenMinuteTicks(clock) }
    return minuteTicks.collectAsStateWithLifecycle(
        initialValue = initialSnapshot,
        minActiveState = Lifecycle.State.RESUMED,
    )
}

/**
 * Cold minute ticker. Each collection emits immediately, waits for the real
 * epoch-minute boundary, then samples both instant and zone again.
 */
internal fun recordScreenMinuteTicks(
    clock: RecordScreenClock,
    awaitBoundary: suspend (Long) -> Unit = { delay(it) },
): Flow<RecordScreenTimeSnapshot> = flow {
    while (currentCoroutineContext().isActive) {
        val snapshot = clock.snapshot()
        emit(snapshot)
        awaitBoundary(millisUntilNextMinuteBoundary(snapshot.epochMillis))
    }
}

internal fun millisUntilNextMinuteBoundary(epochMillis: Long): Long =
    MILLIS_PER_MINUTE - Math.floorMod(epochMillis, MILLIS_PER_MINUTE)

private const val MILLIS_PER_MINUTE = 60_000L
