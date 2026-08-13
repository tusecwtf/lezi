package com.lezi.babylog.feature.log.timeline

import com.lezi.babylog.core.model.Record
import com.lezi.babylog.domain.carelog.DayChartCategories
import com.lezi.babylog.domain.carelog.DayChartCategory
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToLong

private val LIVE_VIEWPORT_DURATION: Duration = Duration.ofHours(24)

/** Absolute elapsed-time range. Calendar rebasing never changes these instants. */
internal data class TimelineViewport(
    val startInstantMs: Long,
    val endInstantMs: Long,
) {
    init {
        require(endInstantMs > startInstantMs) { "timeline viewport must have positive duration" }
    }

    val durationMs: Long
        get() = endInstantMs - startInstantMs
}

internal enum class TimelineInteractionMode {
    LiveAttached,
    Browsing,
}

internal enum class TimelineFilterResult {
    AllRecords,
    AwaitingRecords,
    Matching,
    Empty,
}

internal data class TimelineInteractionFilter(
    val selection: DayChartCategory? = null,
    val result: TimelineFilterResult = TimelineFilterResult.AllRecords,
)

internal data class TimelineDragState(
    val originSelectedDay: LocalDate,
    val originViewport: TimelineViewport,
    val originMode: TimelineInteractionMode,
    val reachedNowBoundary: Boolean = false,
)

internal data class TimelineInteractionState(
    val selectedDay: LocalDate,
    val babyId: Long?,
    val zoneId: ZoneId,
    val viewport: TimelineViewport,
    val mode: TimelineInteractionMode,
    val filter: TimelineInteractionFilter = TimelineInteractionFilter(),
    val drag: TimelineDragState? = null,
) {
    val workWindow: TimelineViewport
        get() {
            val axis = ThreeDayTimelineAxis(selectedDay, zoneId)
            return TimelineViewport(axis.windowStartMs, axis.windowEndExclusiveMs)
        }
}

internal sealed interface TimelineInteractionEffect {
    data class CommitSelectedDay(val selectedDay: LocalDate) : TimelineInteractionEffect
}

internal data class TimelineInteractionResult(
    val state: TimelineInteractionState,
    val effect: TimelineInteractionEffect? = null,
)

internal sealed interface TimelineInteractionEvent {
    data class Initialize(
        val selectedDay: LocalDate,
        val babyId: Long?,
        val nowMs: Long,
        val zoneId: ZoneId,
    ) : TimelineInteractionEvent

    data class Restore(
        val retainedState: TimelineInteractionState,
        val nowMs: Long,
        val zoneId: ZoneId,
    ) : TimelineInteractionEvent

    data class ClockAdvanced(
        val nowMs: Long,
        val zoneId: ZoneId,
    ) : TimelineInteractionEvent

    data object DragStarted : TimelineInteractionEvent

    data class DragChanged(
        val cumulativeDeltaPx: Double,
        val effectiveWidthPx: Double,
        val nowMs: Long,
    ) : TimelineInteractionEvent

    data class DragEnded(val nowMs: Long) : TimelineInteractionEvent

    data class DragCancelled(val nowMs: Long) : TimelineInteractionEvent

    data class ExternalDaySelected(
        val selectedDay: LocalDate,
        val nowMs: Long,
        val zoneId: ZoneId,
    ) : TimelineInteractionEvent

    data class SelectCategory(
        val categoryKey: String?,
        val dayRecords: List<Record>,
    ) : TimelineInteractionEvent

    data class RecordsRefreshed(val records: List<Record>) : TimelineInteractionEvent

    data class BabyChanged(val babyId: Long?) : TimelineInteractionEvent
}

/**
 * Pure owner of timeline navigation and filter lifecycle.
 *
 * Rendering reports cumulative pointer distance and applies [TimelineInteractionEffect]; it does
 * not choose dates, infer velocity, or keep a second viewport policy.
 */
internal object TimelineInteraction {
    fun reduce(
        state: TimelineInteractionState?,
        event: TimelineInteractionEvent,
    ): TimelineInteractionResult = when (event) {
        is TimelineInteractionEvent.Initialize -> {
            require(state == null) { "initialize requires an empty timeline state" }
            TimelineInteractionResult(
                initialize(
                    selectedDay = event.selectedDay,
                    babyId = event.babyId,
                    nowMs = event.nowMs,
                    zoneId = event.zoneId,
                ),
            )
        }

        is TimelineInteractionEvent.Restore -> {
            require(state == null) { "restore requires an empty timeline state" }
            restore(event)
        }

        else -> reduceExisting(requireNotNull(state) { "$event requires timeline state" }, event)
    }

    private fun reduceExisting(
        state: TimelineInteractionState,
        event: TimelineInteractionEvent,
    ): TimelineInteractionResult = when (event) {
        is TimelineInteractionEvent.ClockAdvanced -> advanceClock(state, event)
        TimelineInteractionEvent.DragStarted -> TimelineInteractionResult(startDrag(state))
        is TimelineInteractionEvent.DragChanged -> TimelineInteractionResult(changeDrag(state, event))
        is TimelineInteractionEvent.DragEnded -> endDrag(state, event.nowMs)
        is TimelineInteractionEvent.DragCancelled -> cancelDrag(state, event.nowMs)
        is TimelineInteractionEvent.ExternalDaySelected -> TimelineInteractionResult(
            selectExternalDay(state, event),
        )
        is TimelineInteractionEvent.SelectCategory -> TimelineInteractionResult(
            selectCategory(state, event),
        )
        is TimelineInteractionEvent.RecordsRefreshed -> TimelineInteractionResult(
            refreshRecords(state, event.records),
        )
        is TimelineInteractionEvent.BabyChanged -> TimelineInteractionResult(
            state.copy(
                babyId = event.babyId,
                filter = TimelineInteractionFilter(),
            ),
        )
        is TimelineInteractionEvent.Initialize,
        is TimelineInteractionEvent.Restore,
        -> error("initialization events require an empty timeline state")
    }

    private fun initialize(
        selectedDay: LocalDate,
        babyId: Long?,
        nowMs: Long,
        zoneId: ZoneId,
    ): TimelineInteractionState {
        val today = localDate(nowMs, zoneId)
        return if (selectedDay >= today) {
            liveState(
                selectedDay = today,
                babyId = babyId,
                nowMs = nowMs,
                zoneId = zoneId,
            )
        } else {
            browsingState(
                selectedDay = selectedDay,
                babyId = babyId,
                zoneId = zoneId,
                viewport = naturalDayViewport(selectedDay, zoneId),
            )
        }
    }

    private fun restore(event: TimelineInteractionEvent.Restore): TimelineInteractionResult {
        val retained = event.retainedState
        if (retained.mode == TimelineInteractionMode.Browsing) {
            return TimelineInteractionResult(
                retained.copy(
                    zoneId = event.zoneId,
                    drag = null,
                ),
            )
        }
        return reattachLive(
            state = retained.copy(
                zoneId = event.zoneId,
                drag = null,
            ),
            nowMs = event.nowMs,
            zoneId = event.zoneId,
        )
    }

    private fun advanceClock(
        state: TimelineInteractionState,
        event: TimelineInteractionEvent.ClockAdvanced,
    ): TimelineInteractionResult {
        if (state.drag != null || state.mode == TimelineInteractionMode.Browsing) {
            return TimelineInteractionResult(state.copy(zoneId = event.zoneId))
        }
        return reattachLive(state, event.nowMs, event.zoneId)
    }

    private fun startDrag(state: TimelineInteractionState): TimelineInteractionState {
        if (state.drag != null) return state
        return state.copy(
            drag = TimelineDragState(
                originSelectedDay = state.selectedDay,
                originViewport = state.viewport,
                originMode = state.mode,
            ),
        )
    }

    private fun changeDrag(
        state: TimelineInteractionState,
        event: TimelineInteractionEvent.DragChanged,
    ): TimelineInteractionState {
        val drag = state.drag ?: return state
        if (!event.cumulativeDeltaPx.isFinite() ||
            !event.effectiveWidthPx.isFinite() ||
            event.effectiveWidthPx <= 0.0
        ) {
            return state
        }
        // One width is the direct-manipulation budget for one adjacent-day gesture. Saturating at
        // that boundary keeps the preview inside the three-day window after its single-day rebase.
        val dayFraction = (event.cumulativeDeltaPx / event.effectiveWidthPx).coerceIn(-1.0, 1.0)
        val translationMs = (dayFraction * drag.originViewport.durationMs.toDouble()).roundToLong()
        val requestedStart = drag.originViewport.startInstantMs - translationMs
        val requestedEnd = requestedStart + drag.originViewport.durationMs
        val reachedNowBoundary = requestedEnd >= event.nowMs
        val clampedEnd = minOf(requestedEnd, event.nowMs)
        val viewport = TimelineViewport(
            startInstantMs = clampedEnd - drag.originViewport.durationMs,
            endInstantMs = clampedEnd,
        )
        return state.copy(
            viewport = viewport,
            drag = drag.copy(reachedNowBoundary = reachedNowBoundary),
        )
    }

    private fun endDrag(
        state: TimelineInteractionState,
        nowMs: Long,
    ): TimelineInteractionResult {
        val drag = state.drag ?: return TimelineInteractionResult(state)
        val today = localDate(nowMs, state.zoneId)
        if (drag.originMode == TimelineInteractionMode.LiveAttached) {
            val originDayStart = drag.originSelectedDay
                .atStartOfDay(state.zoneId)
                .toInstant()
                .toEpochMilli()
            if (state.viewport.endInstantMs > originDayStart) {
                return reattachLive(state, nowMs, state.zoneId)
            }
            val target = drag.originSelectedDay.minusDays(1)
            return browsingDayResult(state, target)
        }

        if (drag.reachedNowBoundary) {
            return reattachLive(state, nowMs, state.zoneId)
        }

        val centerInstantMs = state.viewport.startInstantMs + state.viewport.durationMs / 2L
        val centerDay = localDate(centerInstantMs, state.zoneId)
        val lower = drag.originSelectedDay.minusDays(1)
        val upper = minOf(drag.originSelectedDay.plusDays(1), today)
        val target = centerDay.coerceIn(lower, upper)
        return browsingDayResult(state, target)
    }

    private fun browsingDayResult(
        state: TimelineInteractionState,
        target: LocalDate,
    ): TimelineInteractionResult {
        val changed = target != state.selectedDay
        val next = state.copy(
            selectedDay = target,
            mode = TimelineInteractionMode.Browsing,
            filter = updateFilterForDayChange(state.filter, changed),
            drag = null,
        )
        return TimelineInteractionResult(next, if (changed) commitDay(target) else null)
    }

    private fun cancelDrag(
        state: TimelineInteractionState,
        nowMs: Long,
    ): TimelineInteractionResult {
        val drag = state.drag ?: return TimelineInteractionResult(state)
        if (drag.originMode == TimelineInteractionMode.LiveAttached) {
            return reattachLive(state, nowMs, state.zoneId)
        }
        return TimelineInteractionResult(
            state.copy(
                selectedDay = drag.originSelectedDay,
                viewport = drag.originViewport,
                mode = drag.originMode,
                drag = null,
            ),
        )
    }

    private fun selectExternalDay(
        state: TimelineInteractionState,
        event: TimelineInteractionEvent.ExternalDaySelected,
    ): TimelineInteractionState {
        val today = localDate(event.nowMs, event.zoneId)
        val target = minOf(event.selectedDay, today)
        val filter = updateFilterForDayChange(state.filter, target != state.selectedDay)
        return if (target == today) {
            liveState(
                selectedDay = today,
                babyId = state.babyId,
                nowMs = event.nowMs,
                zoneId = event.zoneId,
                filter = filter,
            )
        } else {
            browsingState(
                selectedDay = target,
                babyId = state.babyId,
                zoneId = event.zoneId,
                viewport = naturalDayViewport(target, event.zoneId),
                filter = filter,
            )
        }
    }

    private fun selectCategory(
        state: TimelineInteractionState,
        event: TimelineInteractionEvent.SelectCategory,
    ): TimelineInteractionState {
        val proposed = resolveDayChartSelection(event.categoryKey)
        val selection = DayChartCategories.commitSelection(
            current = state.filter.selection,
            proposed = proposed,
            dayRecords = event.dayRecords,
        )
        return state.copy(filter = filterForRecords(selection, event.dayRecords))
    }

    private fun refreshRecords(
        state: TimelineInteractionState,
        records: List<Record>,
    ): TimelineInteractionState = state.copy(
        filter = filterForRecords(state.filter.selection, records),
    )

    private fun filterForRecords(
        selection: DayChartCategory?,
        records: List<Record>,
    ): TimelineInteractionFilter {
        if (selection == null) return TimelineInteractionFilter()
        val result = if (DayChartCategories.isPresentOnDay(selection, records)) {
            TimelineFilterResult.Matching
        } else {
            TimelineFilterResult.Empty
        }
        return TimelineInteractionFilter(selection, result)
    }

    private fun updateFilterForDayChange(
        filter: TimelineInteractionFilter,
        changed: Boolean,
    ): TimelineInteractionFilter = if (changed && filter.selection != null) {
        filter.copy(result = TimelineFilterResult.AwaitingRecords)
    } else {
        filter
    }

    private fun reattachLive(
        state: TimelineInteractionState,
        nowMs: Long,
        zoneId: ZoneId,
    ): TimelineInteractionResult {
        val today = localDate(nowMs, zoneId)
        val attached = liveState(
            selectedDay = today,
            babyId = state.babyId,
            nowMs = nowMs,
            zoneId = zoneId,
            filter = updateFilterForDayChange(state.filter, today != state.selectedDay),
        )
        val effect = if (today != state.selectedDay) commitDay(today) else null
        return TimelineInteractionResult(attached, effect)
    }

    private fun liveState(
        selectedDay: LocalDate,
        babyId: Long?,
        nowMs: Long,
        zoneId: ZoneId,
        filter: TimelineInteractionFilter = TimelineInteractionFilter(),
    ): TimelineInteractionState = TimelineInteractionState(
        selectedDay = selectedDay,
        babyId = babyId,
        zoneId = zoneId,
        viewport = TimelineViewport(
            startInstantMs = nowMs - LIVE_VIEWPORT_DURATION.toMillis(),
            endInstantMs = nowMs,
        ),
        mode = TimelineInteractionMode.LiveAttached,
        filter = filter,
    )

    private fun browsingState(
        selectedDay: LocalDate,
        babyId: Long?,
        zoneId: ZoneId,
        viewport: TimelineViewport,
        filter: TimelineInteractionFilter = TimelineInteractionFilter(),
    ): TimelineInteractionState = TimelineInteractionState(
        selectedDay = selectedDay,
        babyId = babyId,
        zoneId = zoneId,
        viewport = viewport,
        mode = TimelineInteractionMode.Browsing,
        filter = filter,
    )

    private fun naturalDayViewport(
        selectedDay: LocalDate,
        zoneId: ZoneId,
    ): TimelineViewport = TimelineViewport(
        startInstantMs = selectedDay.atStartOfDay(zoneId).toInstant().toEpochMilli(),
        endInstantMs = selectedDay.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
    )

    private fun localDate(
        instantMs: Long,
        zoneId: ZoneId,
    ): LocalDate = Instant.ofEpochMilli(instantMs).atZone(zoneId).toLocalDate()

    private fun commitDay(day: LocalDate): TimelineInteractionEffect =
        TimelineInteractionEffect.CommitSelectedDay(day)
}
