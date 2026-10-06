package com.lezi.babylog.core.model

/**
 * Database-free sleep interval projection over SleepStart + WakeObservations.
 *
 * Wire §4.2 / §4.5 / ADR-0021: new Sleep roots do not synchronize `end_timestamp`.
 * Display, open-state, and duration come from:
 * 1. Author/Owner-chosen [effectiveWakeObservationClientUuid] when legal
 * 2. Else earliest non-withdrawn legal wake (provisional)
 * 3. Else dual-compat legacy [legacyEndTimestamp] (historical closed migration)
 * 4. Else open (null end, ongoing)
 *
 * Callers must not invent a second projection; timeline/summary/composer use this seam.
 */
data class WakeObservationFact(
    val clientUuid: String,
    val wakeTimestamp: Long,
    val withdrawn: Boolean = false,
    val observerMembershipId: String = "",
    val note: String? = null,
    val deleted: Boolean = false,
)

enum class SleepEndSource {
    /** Author/Owner selected an effective observation. */
    EFFECTIVE,

    /** Unconfirmed: earliest legal non-withdrawn observation. */
    PROVISIONAL,

    /** Pre-causal dual-compat denormalized end (migration / transitional). */
    LEGACY_END,

    /** No end — SleepStart is still open. */
    OPEN,
}

data class SleepIntervalProjection(
    val sleepClientUuid: String,
    val startTimestamp: Long,
    /** Projected end; null only when [endSource] is [SleepEndSource.OPEN]. */
    val endTimestamp: Long?,
    val endSource: SleepEndSource,
    /** Observation that owns the projected end, when source is effective or provisional. */
    val endObservationClientUuid: String? = null,
    /** Non-withdrawn, non-deleted observations with wake >= start, ordered by time then UUID. */
    val visibleObservations: List<WakeObservationFact> = emptyList(),
    /** True when end comes from provisional earliest (UI "暂定" badge). */
    val isProvisional: Boolean = false,
    /** True when this open SleepStart is not the latest among peer opens. */
    val isOverlapPending: Boolean = false,
) {
    val isOpen: Boolean get() = endSource == SleepEndSource.OPEN
}

/**
 * Project one SleepStart row against its wake observations.
 *
 * @param sleepClientUuid Sleep Record client UUID
 * @param startTimestamp SleepStart main time
 * @param effectiveWakeObservationClientUuid chosen effective observation, if any
 * @param observations all known observations for this sleep (any withdrawn/deleted filtered here)
 * @param legacyEndTimestamp dual-compat denormalized end; ignored when wakes/effective decide
 * @param peerOpenSleepStarts other open SleepStart starts (same baby); used only for overlap flag
 */
fun projectSleepInterval(
    sleepClientUuid: String,
    startTimestamp: Long,
    effectiveWakeObservationClientUuid: String? = null,
    observations: Collection<WakeObservationFact> = emptyList(),
    legacyEndTimestamp: Long? = null,
    peerOpenSleepStarts: Collection<Pair<String, Long>> = emptyList(),
): SleepIntervalProjection {
    val legal = observations
        .asSequence()
        .filter { !it.deleted && !it.withdrawn && it.wakeTimestamp >= startTimestamp }
        .sortedWith(compareBy(WakeObservationFact::wakeTimestamp, WakeObservationFact::clientUuid))
        .toList()

    val effectiveUuid = effectiveWakeObservationClientUuid?.trim()?.takeIf { it.isNotEmpty() }
    val effective = effectiveUuid?.let { uuid -> legal.firstOrNull { it.clientUuid == uuid } }

    if (effective != null) {
        return SleepIntervalProjection(
            sleepClientUuid = sleepClientUuid,
            startTimestamp = startTimestamp,
            endTimestamp = effective.wakeTimestamp,
            endSource = SleepEndSource.EFFECTIVE,
            endObservationClientUuid = effective.clientUuid,
            visibleObservations = legal,
            isProvisional = false,
            isOverlapPending = false,
        )
    }

    // Effective pointed at missing/illegal/withdrawn observation: treat as unset.
    val provisional = legal.firstOrNull()
    if (provisional != null) {
        return SleepIntervalProjection(
            sleepClientUuid = sleepClientUuid,
            startTimestamp = startTimestamp,
            endTimestamp = provisional.wakeTimestamp,
            endSource = SleepEndSource.PROVISIONAL,
            endObservationClientUuid = provisional.clientUuid,
            visibleObservations = legal,
            isProvisional = true,
            isOverlapPending = false,
        )
    }

    if (legacyEndTimestamp != null) {
        return SleepIntervalProjection(
            sleepClientUuid = sleepClientUuid,
            startTimestamp = startTimestamp,
            endTimestamp = legacyEndTimestamp,
            endSource = SleepEndSource.LEGACY_END,
            endObservationClientUuid = null,
            visibleObservations = emptyList(),
            isProvisional = false,
            isOverlapPending = false,
        )
    }

    val isLatestOpen = isLatestOpenSleepStart(
        sleepClientUuid = sleepClientUuid,
        startTimestamp = startTimestamp,
        peerOpenSleepStarts = peerOpenSleepStarts,
    )
    return SleepIntervalProjection(
        sleepClientUuid = sleepClientUuid,
        startTimestamp = startTimestamp,
        endTimestamp = null,
        endSource = SleepEndSource.OPEN,
        endObservationClientUuid = null,
        visibleObservations = emptyList(),
        isProvisional = false,
        isOverlapPending = !isLatestOpen,
    )
}

/**
 * Among open SleepStarts (no projected end), the latest start is the wake shortcut target.
 * Equal starts use client UUID as deterministic tie-break (same as historical repair).
 */
fun isLatestOpenSleepStart(
    sleepClientUuid: String,
    startTimestamp: Long,
    peerOpenSleepStarts: Collection<Pair<String, Long>>,
): Boolean {
    val all = peerOpenSleepStarts + (sleepClientUuid to startTimestamp)
    val latest = all.maxWithOrNull(
        compareBy<Pair<String, Long>> { it.second }.thenBy { it.first },
    ) ?: return true
    return latest.first == sleepClientUuid
}

/** Whether this SleepStart should appear as the dock "醒来" target (open, not overlap-only). */
fun isWakeShortcutTarget(projection: SleepIntervalProjection): Boolean =
    projection.isOpen && !projection.isOverlapPending

/**
 * Wire §4.5 / provisional projection: `wake_timestamp >= sleep.timestamp`.
 * Pre-start wakes are rejected; equality is legal (zero-length interval).
 * Future-gate (clock skew vs now) is the caller's [RecordTime] responsibility.
 */
fun validateWakeTimestamp(
    sleepStartTimestamp: Long,
    wakeTimestamp: Long,
): String? {
    if (wakeTimestamp < sleepStartTimestamp) {
        return "醒来时间不能早于入睡时间"
    }
    return null
}
