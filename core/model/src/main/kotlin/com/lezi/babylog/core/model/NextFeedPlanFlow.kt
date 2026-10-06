package com.lezi.babylog.core.model

import kotlinx.coroutines.CancellationException

/** The two fact-entry adapters that deliberately share this reducer. */
enum class NextFeedPlanOrigin {
    RecordComposer,
    NursingTimer,
}

/** Durable truth returned by the shared next-feed persistence reconciliation seam. */
sealed interface NextFeedPlanReconciliation {
    data class Found(
        val clientUuid: String,
        val scheduledAtMillis: Long,
    ) : NextFeedPlanReconciliation

    data object Absent : NextFeedPlanReconciliation

    data class Failed(val message: String) : NextFeedPlanReconciliation
}

enum class NextFeedPlanPhase {
    Choosing,
    EditingTime,
    Scheduling,
    ReconciliationRequired,
    Reconciling,
    ReconciliationFailed,
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
    data object ScheduleOutcomeUnknown : NextFeedPlanEvent
    data object Reconcile : NextFeedPlanEvent
    data class ReconciliationCompleted(
        val result: NextFeedPlanReconciliation,
    ) : NextFeedPlanEvent
    data object AcknowledgeScheduled : NextFeedPlanEvent
    data object Skip : NextFeedPlanEvent
}

sealed interface NextFeedPlanEffect {
    data class Schedule(val atMillis: Long) : NextFeedPlanEffect
    data object Reconcile : NextFeedPlanEffect
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

suspend fun runNextFeedPlanReconciliation(
    query: suspend () -> NextFeedPlanReconciliation,
): NextFeedPlanReconciliation = try {
    query()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Throwable) {
    NextFeedPlanReconciliation.Failed(NEXT_FEED_RECONCILIATION_ERROR)
}

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
            if (state.phase in setOf(
                    NextFeedPlanPhase.Scheduling,
                    NextFeedPlanPhase.ReconciliationRequired,
                    NextFeedPlanPhase.Reconciling,
                    NextFeedPlanPhase.ReconciliationFailed,
                )
            ) {
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
            state.phase !in setOf(
                NextFeedPlanPhase.Choosing,
                NextFeedPlanPhase.Failed,
            ) -> NextFeedPlanTransition(state)
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
        NextFeedPlanEvent.ScheduleOutcomeUnknown -> when (state.phase) {
            NextFeedPlanPhase.Scheduling -> NextFeedPlanTransition(
                state.copy(
                    phase = NextFeedPlanPhase.ReconciliationRequired,
                    scheduleError = null,
                ),
            )
            else -> NextFeedPlanTransition(state)
        }
        NextFeedPlanEvent.Reconcile -> when (state.phase) {
            NextFeedPlanPhase.ReconciliationRequired,
            NextFeedPlanPhase.ReconciliationFailed,
            -> NextFeedPlanTransition(
                state.copy(
                    phase = NextFeedPlanPhase.Reconciling,
                    scheduleError = null,
                ),
                NextFeedPlanEffect.Reconcile,
            )
            else -> NextFeedPlanTransition(state)
        }
        is NextFeedPlanEvent.ReconciliationCompleted -> when {
            state.phase != NextFeedPlanPhase.Reconciling -> NextFeedPlanTransition(state)
            event.result is NextFeedPlanReconciliation.Found -> NextFeedPlanTransition(
                state.copy(
                    selectedAtMillis = event.result.scheduledAtMillis,
                    phase = NextFeedPlanPhase.Scheduled,
                    validationError = null,
                    scheduleError = null,
                ),
            )
            event.result == NextFeedPlanReconciliation.Absent -> NextFeedPlanTransition(
                state.copy(
                    phase = NextFeedPlanPhase.Failed,
                    scheduleError = NEXT_FEED_CONFIRMED_ABSENT_ERROR,
                ),
            )
            event.result is NextFeedPlanReconciliation.Failed -> NextFeedPlanTransition(
                state.copy(
                    phase = NextFeedPlanPhase.ReconciliationFailed,
                    scheduleError = event.result.message.ifBlank {
                        NEXT_FEED_RECONCILIATION_ERROR
                    },
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
            NextFeedPlanPhase.Scheduling,
            NextFeedPlanPhase.ReconciliationRequired,
            NextFeedPlanPhase.Reconciling,
            NextFeedPlanPhase.ReconciliationFailed,
            NextFeedPlanPhase.Scheduled,
            ->
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
const val NEXT_FEED_CONFIRMED_ABSENT_ERROR = "未发现已保存的下次喂养安排，请重试或选择不安排"
const val NEXT_FEED_RECONCILIATION_ERROR = "无法确认下次喂养是否已安排，请重新核对"

fun restoreNextFeedPlanState(state: NextFeedPlanState): NextFeedPlanState =
    if (state.phase in setOf(
            NextFeedPlanPhase.Scheduling,
            NextFeedPlanPhase.Reconciling,
        )
    ) {
        state.copy(
            phase = NextFeedPlanPhase.ReconciliationRequired,
            scheduleError = null,
        )
    } else {
        state
    }
