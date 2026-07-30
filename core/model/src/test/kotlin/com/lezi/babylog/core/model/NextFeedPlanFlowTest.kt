package com.lezi.babylog.core.model

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class NextFeedPlanFlowTest {
    private val now = 1_000_000L
    private val suggestedAt = now + 180 * 60_000L

    @Test
    fun defaultSuggestionUsesTheConfiguredIntervalWithoutOverflow() {
        assertThat(nextFeedSuggestedAt(now, intervalMinutes = 180)).isEqualTo(suggestedAt)
        assertThat(nextFeedSuggestedAt(Long.MAX_VALUE - 30_000L, intervalMinutes = 1))
            .isEqualTo(Long.MAX_VALUE)
    }

    @Test
    fun onlyAStandaloneNewFeedFactOffersAnotherPlan() {
        assertThat(shouldOfferNextFeedPlanForFact(RecordType.NURSING, true, null)).isTrue()
        assertThat(shouldOfferNextFeedPlanForFact(RecordType.FORMULA, true, null)).isTrue()
        assertThat(shouldOfferNextFeedPlanForFact(RecordType.PUMPED_FEED, true, null)).isTrue()
        assertThat(shouldOfferNextFeedPlanForFact(RecordType.SLEEP, true, null)).isFalse()
        assertThat(shouldOfferNextFeedPlanForFact(RecordType.NURSING, false, null)).isFalse()
        assertThat(shouldOfferNextFeedPlanForFact(RecordType.NURSING, true, 42L)).isFalse()
    }

    @Test
    fun userCanFinishThePersistedFactWithoutCreatingAPlan() {
        val initial = NextFeedPlanState.initial(suggestedAt)

        val result = reduceNextFeedPlan(initial, NextFeedPlanEvent.Skip)

        assertThat(result.state.factPersisted).isTrue()
        assertThat(result.state.phase).isEqualTo(NextFeedPlanPhase.Skipped)
        assertThat(result.effect).isEqualTo(NextFeedPlanEffect.FinishWithoutPlan)
    }

    @Test
    fun invalidTimeDoesNotScheduleAndCanBeCorrectedWithTheSharedClock() {
        val editing = reduceNextFeedPlan(
            NextFeedPlanState.initial(suggestedAt),
            NextFeedPlanEvent.EditTime,
        ).state

        val invalid = reduceNextFeedPlan(
            editing,
            NextFeedPlanEvent.TimeSelected(atMillis = now, nowMillis = now),
        )

        assertThat(invalid.effect).isNull()
        assertThat(invalid.state.phase).isEqualTo(NextFeedPlanPhase.Choosing)
        assertThat(invalid.state.validationError).isEqualTo("下次喂养须选择未来时刻")

        val corrected = reduceNextFeedPlan(
            invalid.state,
            NextFeedPlanEvent.TimeSelected(atMillis = suggestedAt, nowMillis = now),
        )
        assertThat(corrected.state.selectedAtMillis).isEqualTo(suggestedAt)
        assertThat(corrected.state.validationError).isNull()
    }

    @Test
    fun duplicateSubmitWhileSchedulingProducesOnlyOneWriteEffect() {
        val initial = NextFeedPlanState.initial(suggestedAt)

        val first = reduceNextFeedPlan(initial, NextFeedPlanEvent.Schedule(now))
        val duplicate = reduceNextFeedPlan(first.state, NextFeedPlanEvent.Schedule(now))

        assertThat(first.state.phase).isEqualTo(NextFeedPlanPhase.Scheduling)
        assertThat(first.effect).isEqualTo(NextFeedPlanEffect.Schedule(suggestedAt))
        assertThat(duplicate.state).isEqualTo(first.state)
        assertThat(duplicate.effect).isNull()
    }

    @Test
    fun processRestoreMustReconcileBeforeRetryOrSkip() {
        val scheduling = reduceNextFeedPlan(
            NextFeedPlanState.initial(suggestedAt),
            NextFeedPlanEvent.Schedule(now),
        ).state

        val restored = restoreNextFeedPlanState(scheduling)

        assertThat(restored.phase).isEqualTo(NextFeedPlanPhase.ReconciliationRequired)
        assertThat(restored.selectedAtMillis).isEqualTo(suggestedAt)
        assertThat(restored.factPersisted).isTrue()

        val prematureSkip = reduceNextFeedPlan(restored, NextFeedPlanEvent.Skip)
        assertThat(prematureSkip.state).isEqualTo(restored)
        assertThat(prematureSkip.effect).isNull()

        val reconcile = reduceNextFeedPlan(restored, NextFeedPlanEvent.Reconcile)
        assertThat(reconcile.state.phase).isEqualTo(NextFeedPlanPhase.Reconciling)
        assertThat(reconcile.effect).isEqualTo(NextFeedPlanEffect.Reconcile)
        assertThat(restoreNextFeedPlanState(reconcile.state).phase)
            .isEqualTo(NextFeedPlanPhase.ReconciliationRequired)
    }

    @Test
    fun committedMarkerReconciliationUsesDurableTimeAndFinishesScheduled() {
        val requestedAt = suggestedAt
        val durableAt = suggestedAt + 15 * 60_000L
        val reconciling = reduceNextFeedPlan(
            restoreNextFeedPlanState(
                reduceNextFeedPlan(
                    NextFeedPlanState.initial(requestedAt),
                    NextFeedPlanEvent.Schedule(now),
                ).state,
            ),
            NextFeedPlanEvent.Reconcile,
        ).state

        val found = reduceNextFeedPlan(
            reconciling,
            NextFeedPlanEvent.ReconciliationCompleted(
                NextFeedPlanReconciliation.Found(
                    clientUuid = "next-feed-plan",
                    scheduledAtMillis = durableAt,
                ),
            ),
        )

        assertThat(found.state.phase).isEqualTo(NextFeedPlanPhase.Scheduled)
        assertThat(found.state.selectedAtMillis).isEqualTo(durableAt)
        assertThat(found.state.scheduleError).isNull()
        assertThat(
            reduceNextFeedPlan(found.state, NextFeedPlanEvent.AcknowledgeScheduled).effect,
        ).isEqualTo(NextFeedPlanEffect.FinishScheduled)
    }

    @Test
    fun confirmedAbsentMarkerIsTheOnlyRestorePathThatCanSkip() {
        val reconciling = reduceNextFeedPlan(
            restoreNextFeedPlanState(
                reduceNextFeedPlan(
                    NextFeedPlanState.initial(suggestedAt),
                    NextFeedPlanEvent.Schedule(now),
                ).state,
            ),
            NextFeedPlanEvent.Reconcile,
        ).state

        val absent = reduceNextFeedPlan(
            reconciling,
            NextFeedPlanEvent.ReconciliationCompleted(NextFeedPlanReconciliation.Absent),
        )

        assertThat(absent.state.phase).isEqualTo(NextFeedPlanPhase.Failed)
        assertThat(absent.state.scheduleError).isEqualTo(NEXT_FEED_CONFIRMED_ABSENT_ERROR)
        assertThat(reduceNextFeedPlan(absent.state, NextFeedPlanEvent.Skip).effect)
            .isEqualTo(NextFeedPlanEffect.FinishWithoutPlan)
    }

    @Test
    fun failedTruthQueryCanOnlyRetryReconciliation() {
        val reconciling = reduceNextFeedPlan(
            restoreNextFeedPlanState(
                reduceNextFeedPlan(
                    NextFeedPlanState.initial(suggestedAt),
                    NextFeedPlanEvent.Schedule(now),
                ).state,
            ),
            NextFeedPlanEvent.Reconcile,
        ).state
        val failed = reduceNextFeedPlan(
            reconciling,
            NextFeedPlanEvent.ReconciliationCompleted(
                NextFeedPlanReconciliation.Failed("持久化查询失败"),
            ),
        )

        assertThat(failed.state.phase).isEqualTo(NextFeedPlanPhase.ReconciliationFailed)
        assertThat(failed.state.scheduleError).isEqualTo("持久化查询失败")
        assertThat(reduceNextFeedPlan(failed.state, NextFeedPlanEvent.Skip).effect).isNull()

        val retry = reduceNextFeedPlan(failed.state, NextFeedPlanEvent.Reconcile)
        assertThat(retry.state.phase).isEqualTo(NextFeedPlanPhase.Reconciling)
        assertThat(retry.effect).isEqualTo(NextFeedPlanEffect.Reconcile)
    }

    @Test
    fun failedScheduleCallbackIsUnknownUntilPersistenceReconciliation() {
        val scheduling = reduceNextFeedPlan(
            NextFeedPlanState.initial(suggestedAt),
            NextFeedPlanEvent.Schedule(now),
        ).state

        val callbackFailed = reduceNextFeedPlan(
            scheduling,
            NextFeedPlanEvent.ScheduleOutcomeUnknown,
        )

        assertThat(callbackFailed.state.phase)
            .isEqualTo(NextFeedPlanPhase.ReconciliationRequired)
        assertThat(reduceNextFeedPlan(callbackFailed.state, NextFeedPlanEvent.Skip).effect)
            .isNull()
        assertThat(reduceNextFeedPlan(callbackFailed.state, NextFeedPlanEvent.Schedule(now)).effect)
            .isNull()
    }

    @Test
    fun sharedReconciliationAdapterPreservesTruthAndCancellation() = runTest {
        val found = NextFeedPlanReconciliation.Found(
            clientUuid = "next-feed-plan",
            scheduledAtMillis = suggestedAt,
        )
        assertThat(runNextFeedPlanReconciliation { found }).isEqualTo(found)

        assertThat(
            runNextFeedPlanReconciliation { error("database unavailable") },
        ).isEqualTo(NextFeedPlanReconciliation.Failed(NEXT_FEED_RECONCILIATION_ERROR))

        val cancellation = CancellationException("recreated")
        val thrown = runCatching {
            runNextFeedPlanReconciliation { throw cancellation }
        }.exceptionOrNull()
        assertThat(thrown).isSameInstanceAs(cancellation)
    }

    @Test
    fun confirmedAbsentPlanKeepsTheFactAndRetriesTheSameTimeWithoutDuplicatingIt() {
        val scheduling = reduceNextFeedPlan(
            NextFeedPlanState.initial(suggestedAt),
            NextFeedPlanEvent.Schedule(now),
        ).state
        val unknown = reduceNextFeedPlan(
            scheduling,
            NextFeedPlanEvent.ScheduleOutcomeUnknown,
        )
        val reconciling = reduceNextFeedPlan(unknown.state, NextFeedPlanEvent.Reconcile)
        val failed = reduceNextFeedPlan(
            reconciling.state,
            NextFeedPlanEvent.ReconciliationCompleted(NextFeedPlanReconciliation.Absent),
        )

        assertThat(failed.state.factPersisted).isTrue()
        assertThat(failed.state.phase).isEqualTo(NextFeedPlanPhase.Failed)
        assertThat(failed.state.selectedAtMillis).isEqualTo(suggestedAt)
        assertThat(failed.state.scheduleError).isEqualTo(NEXT_FEED_CONFIRMED_ABSENT_ERROR)

        val retry = reduceNextFeedPlan(failed.state, NextFeedPlanEvent.Schedule(now))
        assertThat(retry.effect).isEqualTo(NextFeedPlanEffect.Schedule(suggestedAt))
        assertThat(retry.state.scheduleError).isNull()
        val completed = reduceNextFeedPlan(retry.state, NextFeedPlanEvent.ScheduleSucceeded)
        assertThat(completed.state.phase).isEqualTo(NextFeedPlanPhase.Scheduled)
        assertThat(completed.state.scheduleError).isNull()
        assertThat(completed.effect).isNull()
        assertThat(reduceNextFeedPlan(completed.state, NextFeedPlanEvent.Schedule(now)).effect)
            .isNull()
        assertThat(
            reduceNextFeedPlan(completed.state, NextFeedPlanEvent.AcknowledgeScheduled).effect,
        ).isEqualTo(NextFeedPlanEffect.FinishScheduled)
    }

    @Test
    fun skippingAfterConfirmedAbsentClearsPlanErrorsButKeepsThePersistedFact() {
        val scheduling = reduceNextFeedPlan(
            NextFeedPlanState.initial(suggestedAt),
            NextFeedPlanEvent.Schedule(now),
        ).state
        val unknown = reduceNextFeedPlan(
            scheduling,
            NextFeedPlanEvent.ScheduleOutcomeUnknown,
        ).state
        val reconciling = reduceNextFeedPlan(unknown, NextFeedPlanEvent.Reconcile).state
        val failed = reduceNextFeedPlan(
            reconciling,
            NextFeedPlanEvent.ReconciliationCompleted(NextFeedPlanReconciliation.Absent),
        ).state

        val skipped = reduceNextFeedPlan(failed, NextFeedPlanEvent.Skip)

        assertThat(skipped.state.phase).isEqualTo(NextFeedPlanPhase.Skipped)
        assertThat(skipped.state.scheduleError).isNull()
        assertThat(skipped.state.factPersisted).isTrue()
        assertThat(skipped.effect).isEqualTo(NextFeedPlanEffect.FinishWithoutPlan)
    }

    @Test
    fun composerAndTimerOriginsShareTheSameDurableReconciliationOutputs() {
        fun run(origin: NextFeedPlanOrigin): List<NextFeedPlanTransition> {
            var state = NextFeedPlanState.initial(suggestedAt, origin)
            return listOf(
                NextFeedPlanEvent.EditTime,
                NextFeedPlanEvent.TimeSelected(suggestedAt + 60_000L, now),
                NextFeedPlanEvent.Schedule(now),
                NextFeedPlanEvent.ScheduleOutcomeUnknown,
                NextFeedPlanEvent.Reconcile,
                NextFeedPlanEvent.ReconciliationCompleted(NextFeedPlanReconciliation.Absent),
                NextFeedPlanEvent.Schedule(now),
                NextFeedPlanEvent.ScheduleSucceeded,
                NextFeedPlanEvent.AcknowledgeScheduled,
            ).map { event ->
                reduceNextFeedPlan(state, event).also { state = it.state }
            }
        }

        val composer = run(NextFeedPlanOrigin.RecordComposer)
        val timer = run(NextFeedPlanOrigin.NursingTimer)

        assertThat(composer.map { it.state.copy(origin = NextFeedPlanOrigin.NursingTimer) })
            .isEqualTo(timer.map(NextFeedPlanTransition::state))
        assertThat(composer.map(NextFeedPlanTransition::effect))
            .isEqualTo(timer.map(NextFeedPlanTransition::effect))
    }
}
