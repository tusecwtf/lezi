package com.lezi.babylog.core.model

/**
 * Database-free input for repairing the invariant "at most one open sleep per baby".
 *
 * [stableKey] must be the family-stable record identity (the client UUID), never a
 * device-local row id. That makes equal-start decisions converge on every replica.
 */
data class OpenSleepCandidate(
    val stableKey: String,
    val startedAtMillis: Long,
)

data class OpenSleepClosure(
    val candidate: OpenSleepCandidate,
    val closedAtMillis: Long,
)

data class OpenSleepNormalization(
    val kept: OpenSleepCandidate? = null,
    val closures: List<OpenSleepClosure> = emptyList(),
)

/**
 * Pure, order-independent open-sleep repair decision.
 *
 * The latest start remains open. Equal starts use [OpenSleepCandidate.stableKey]
 * as a deterministic tie-break. A stale interval normally closes at the next
 * interval's start; equal/abnormal starts close at the later of the injected
 * repair clock and a one-minute saturating fallback. The fallback never wraps
 * below the start, including at [Long.MAX_VALUE].
 */
fun normalizeOpenSleeps(
    candidates: Collection<OpenSleepCandidate>,
    repairAtMillis: Long,
): OpenSleepNormalization {
    if (candidates.isEmpty()) return OpenSleepNormalization()
    require(candidates.all { it.stableKey.isNotBlank() }) {
        "开放睡眠缺少稳定标识"
    }
    require(candidates.map(OpenSleepCandidate::stableKey).distinct().size == candidates.size) {
        "开放睡眠稳定标识重复"
    }

    val ordered = candidates.sortedWith(
        compareBy<OpenSleepCandidate> { it.startedAtMillis }
            .thenBy(OpenSleepCandidate::stableKey),
    )
    val kept = ordered.last()
    if (ordered.size == 1) return OpenSleepNormalization(kept = kept)

    val closures = ordered.dropLast(1).mapIndexed { index, current ->
        val nextStart = ordered[index + 1].startedAtMillis
        val closedAt = if (nextStart > current.startedAtMillis) {
            nextStart
        } else {
            maxOf(
                repairAtMillis,
                current.startedAtMillis.saturatingPlus(OPEN_SLEEP_REPAIR_FALLBACK_MILLIS),
                current.startedAtMillis,
            )
        }
        OpenSleepClosure(candidate = current, closedAtMillis = closedAt)
    }
    return OpenSleepNormalization(kept = kept, closures = closures)
}

private const val OPEN_SLEEP_REPAIR_FALLBACK_MILLIS = 60_000L

private fun Long.saturatingPlus(increment: Long): Long =
    if (this > Long.MAX_VALUE - increment) Long.MAX_VALUE else this + increment
