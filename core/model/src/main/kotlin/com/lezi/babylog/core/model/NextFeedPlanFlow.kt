package com.lezi.babylog.core.model

/** The two fact-entry adapters that deliberately share this reducer. */
enum class NextFeedPlanOrigin {
    RecordComposer,
    NursingTimer,
}

enum class NextFeedPlanPhase {
    Choosing,
    EditingTime,
    Scheduling,
    Failed,
    Scheduled,
    Skipped,
}

/**
 * Post-fact state for the optional next-feed CarePlan.
 *
 * This flow only starts after the Record transaction succeeds. A plan failure therefore never
 * rolls back or disguises the fact; retry keeps the same selected time and the domain's stable
 * next-feed identity supplies write idempotency.
 */
data class NextFeedPlanState(
    val origin: NextFeedPlanOrigin,
    val suggestedAtMillis: Long,
    val selectedAtMillis: Long = suggestedAtMillis,
    val phase: NextFeedPlanPhase = NextFeedPlanPhase.Choosing,
    val validationError: String? = null,
    val scheduleError: String? = null,
    val factPersisted: Boolean = true,
) {
    init {
        require(factPersisted) { "next-feed planning starts only after the fact is persisted" }
    }

    companion object {
        fun initial(
            suggestedAtMillis: Long,
            origin: NextFeedPlanOrigin = NextFeedPlanOrigin.RecordComposer,
        ): NextFeedPlanState = NextFeedPlanState(
            origin = origin,
            suggestedAtMillis = suggestedAtMillis,
        )
    }
}

sealed interface NextFeedPlanEvent {
    data object EditTime : NextFeedPlanEvent
    data class TimeSelected(val atMillis: Long, val nowMillis: Long) : NextFeedPlanEvent
    data object TimeEditCancelled : NextFeedPlanEvent
    data class Schedule(val nowMillis: Long) : NextFeedPlanEvent
    data object ScheduleSucceeded : NextFeedPlanEvent
    data class ScheduleFailed(val message: String) : NextFeedPlanEvent
    data object AcknowledgeScheduled : NextFeedPlanEvent
    data object Skip : NextFeedPlanEvent
}

sealed interface NextFeedPlanEffect {
    data class Schedule(val atMillis: Long) : NextFeedPlanEffect
    data object FinishScheduled : NextFeedPlanEffect
    data object FinishWithoutPlan : NextFeedPlanEffect
}

data class NextFeedPlanTransition(
    val state: NextFeedPlanState,
    val effect: NextFeedPlanEffect? = null,
)

fun nextFeedSuggestedAt(nowMillis: Long, intervalMinutes: Int): Long {
    val delayMillis = intervalMinutes.coerceAtLeast(1).toLong() * 60_000L
    val candidate = nowMillis + delayMillis
    return if (candidate < nowMillis) Long.MAX_VALUE else candidate
}

fun shouldOfferNextFeedPlanForFact(
    type: RecordType,
    createdNewFact: Boolean,
    sourceCarePlanId: Long?,
): Boolean = createdNewFact && sourceCarePlanId == null && type in setOf(
    RecordType.NURSING,
    RecordType.FORMULA,
    RecordType.PUMPED_FEED,
)

fun reduceNextFeedPlan(
    state: NextFeedPlanState,
    event: NextFeedPlanEvent,
): NextFeedPlanTransition {
    if (state.phase == NextFeedPlanPhase.Skipped ||
        state.phase == NextFeedPlanPhase.Scheduled &&
        event != NextFeedPlanEvent.AcknowledgeScheduled
    ) {
        return NextFeedPlanTransition(state)
    }
    return when (event) {
        NextFeedPlanEvent.EditTime -> when (state.phase) {
            NextFeedPlanPhase.Choosing, NextFeedPlanPhase.Failed -> NextFeedPlanTransition(
                state.copy(phase = NextFeedPlanPhase.EditingTime, validationError = null),
            )
            else -> NextFeedPlanTransition(state)
        }
        is NextFeedPlanEvent.TimeSelected -> {
            if (state.phase == NextFeedPlanPhase.Scheduling) {
                NextFeedPlanTransition(state)
            } else if (event.atMillis <= event.nowMillis) {
                NextFeedPlanTransition(
                    state.copy(
                        phase = NextFeedPlanPhase.Choosing,
                        validationError = NEXT_FEED_FUTURE_ERROR,
                        scheduleError = null,
                    ),
                )
            } else {
                NextFeedPlanTransition(
                    state.copy(
                        selectedAtMillis = event.atMillis,
                        phase = NextFeedPlanPhase.Choosing,
                        validationError = null,
                        scheduleError = null,
                    ),
                )
            }
        }
        NextFeedPlanEvent.TimeEditCancelled -> when (state.phase) {
            NextFeedPlanPhase.EditingTime -> NextFeedPlanTransition(
                state.copy(phase = NextFeedPlanPhase.Choosing, validationError = null),
            )
            else -> NextFeedPlanTransition(state)
        }
        is NextFeedPlanEvent.Schedule -> when {
            state.phase == NextFeedPlanPhase.Scheduling -> NextFeedPlanTransition(state)
            state.phase == NextFeedPlanPhase.EditingTime -> NextFeedPlanTransition(state)
            state.selectedAtMillis <= event.nowMillis -> NextFeedPlanTransition(
                state.copy(
                    phase = NextFeedPlanPhase.Choosing,
                    validationError = NEXT_FEED_FUTURE_ERROR,
                    scheduleError = null,
                ),
            )
            else -> NextFeedPlanTransition(
                state = state.copy(
                    phase = NextFeedPlanPhase.Scheduling,
                    validationError = null,
                    scheduleError = null,
                ),
                effect = NextFeedPlanEffect.Schedule(state.selectedAtMillis),
            )
        }
        NextFeedPlanEvent.ScheduleSucceeded -> when (state.phase) {
            NextFeedPlanPhase.Scheduling -> NextFeedPlanTransition(
                state.copy(
                    phase = NextFeedPlanPhase.Scheduled,
                    validationError = null,
                    scheduleError = null,
                ),
            )
            else -> NextFeedPlanTransition(state)
        }
        is NextFeedPlanEvent.ScheduleFailed -> when (state.phase) {
            NextFeedPlanPhase.Scheduling -> NextFeedPlanTransition(
                state.copy(
                    phase = NextFeedPlanPhase.Failed,
                    scheduleError = event.message.ifBlank { NEXT_FEED_SCHEDULE_ERROR },
                ),
            )
            else -> NextFeedPlanTransition(state)
        }
        NextFeedPlanEvent.AcknowledgeScheduled -> when (state.phase) {
            NextFeedPlanPhase.Scheduled -> NextFeedPlanTransition(
                state,
                NextFeedPlanEffect.FinishScheduled,
            )
            else -> NextFeedPlanTransition(state)
        }
        NextFeedPlanEvent.Skip -> when (state.phase) {
            NextFeedPlanPhase.Scheduling, NextFeedPlanPhase.Scheduled ->
                NextFeedPlanTransition(state)
            else -> NextFeedPlanTransition(
                state.copy(
                    phase = NextFeedPlanPhase.Skipped,
                    validationError = null,
                    scheduleError = null,
                ),
                NextFeedPlanEffect.FinishWithoutPlan,
            )
        }
    }
}

const val NEXT_FEED_FUTURE_ERROR = "下次喂养须选择未来时刻"
const val NEXT_FEED_SCHEDULE_ERROR = "下次喂养安排失败，请重试或选择不安排"
const val NEXT_FEED_RESTORE_ERROR = "上次安排被中断，请重试或选择不安排"

fun restoreNextFeedPlanState(state: NextFeedPlanState): NextFeedPlanState =
    if (state.phase == NextFeedPlanPhase.Scheduling) {
        state.copy(
            phase = NextFeedPlanPhase.Failed,
            scheduleError = NEXT_FEED_RESTORE_ERROR,
        )
    } else {
        state
    }
