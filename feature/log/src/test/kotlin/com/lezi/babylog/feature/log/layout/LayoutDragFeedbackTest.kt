package com.lezi.babylog.feature.log.layout
import org.junit.Assert.assertEquals
import org.junit.Test
import com.lezi.babylog.feature.log.*
import com.lezi.babylog.feature.log.timeline.*
import com.lezi.babylog.feature.log.dock.*
import com.lezi.babylog.feature.log.composer.*
import com.lezi.babylog.feature.log.photo.*

class LayoutDragFeedbackTest {
    @Test
    fun successfulLongPressPickupStartsOneFeedbackSession() {
        val result = reduceLayoutDragFeedback(
            LayoutDragFeedbackState.Idle,
            LayoutDragFeedbackEvent.PickedUp(token = 7L),
        )

        assertEquals(LayoutDragFeedbackState.Dragging(token = 7L), result.state)
        assertEquals(
            LayoutDragVisualPulse(
                token = 7L,
                kind = LayoutDragVisualPulseKind.Pickup,
            ),
            result.visualPulse,
        )
        assertEquals(LayoutDragHaptic.Pickup, result.haptic)
    }

    @Test
    fun pickupBaselinesTheTargetUnderTheSourceWithoutDoubleFeedback() {
        val initialTarget = LayoutDropTarget.CatalogItem("pee", toIndex = 0)
        val pickup = reduceLayoutDragFeedback(
            LayoutDragFeedbackState.Idle,
            LayoutDragFeedbackEvent.PickedUp(
                token = 8L,
                initialTarget = initialTarget,
            ),
        )
        val unchanged = reduceLayoutDragFeedback(
            pickup.state,
            LayoutDragFeedbackEvent.CurrentTargetChanged(
                token = 8L,
                target = initialTarget,
            ),
        )

        assertEquals(
            LayoutDragFeedbackState.Dragging(8L, initialTarget),
            pickup.state,
        )
        assertEquals(LayoutDragHaptic.Pickup, pickup.haptic)
        assertEquals(null, unchanged.visualPulse)
        assertEquals(null, unchanged.haptic)
    }

    @Test
    fun currentTargetFeedbackFiresOnlyWhenEnteringANewLegalTarget() {
        val pickedUp = reduceLayoutDragFeedback(
            LayoutDragFeedbackState.Idle,
            LayoutDragFeedbackEvent.PickedUp(token = 7L),
        )
        val firstTarget = LayoutDropTarget.QuickSlot(slotIndex = 1)
        val entered = reduceLayoutDragFeedback(
            pickedUp.state,
            LayoutDragFeedbackEvent.CurrentTargetChanged(
                token = 7L,
                target = firstTarget,
            ),
        )

        assertEquals(
            LayoutDragFeedbackState.Dragging(token = 7L, currentTarget = firstTarget),
            entered.state,
        )
        assertEquals(
            LayoutDragVisualPulse(
                token = 7L,
                kind = LayoutDragVisualPulseKind.Target,
                target = firstTarget,
            ),
            entered.visualPulse,
        )
        assertEquals(LayoutDragHaptic.Target, entered.haptic)

        val sameTarget = reduceLayoutDragFeedback(
            entered.state,
            LayoutDragFeedbackEvent.CurrentTargetChanged(
                token = 7L,
                target = firstTarget,
            ),
        )

        assertEquals(entered.state, sameTarget.state)
        assertEquals(null, sameTarget.visualPulse)
        assertEquals(null, sameTarget.haptic)

        val leftTarget = reduceLayoutDragFeedback(
            sameTarget.state,
            LayoutDragFeedbackEvent.CurrentTargetChanged(token = 7L, target = null),
        )

        assertEquals(
            LayoutDragFeedbackState.Dragging(token = 7L, currentTarget = null),
            leftTarget.state,
        )
        assertEquals(null, leftTarget.visualPulse)
        assertEquals(null, leftTarget.haptic)

        val reentered = reduceLayoutDragFeedback(
            leftTarget.state,
            LayoutDragFeedbackEvent.CurrentTargetChanged(
                token = 7L,
                target = firstTarget,
            ),
        )

        assertEquals(LayoutDragHaptic.Target, reentered.haptic)
    }

    @Test
    fun acceptedDropConfirmsOnceAndCleansUpTheSession() {
        val target = LayoutDropTarget.QuickSlot(slotIndex = 2)
        val state = LayoutDragFeedbackState.Dragging(
            token = 9L,
            currentTarget = target,
        )

        val dropped = reduceLayoutDragFeedback(
            state,
            LayoutDragFeedbackEvent.DropFinished(token = 9L, accepted = true),
        )

        assertEquals(LayoutDragFeedbackState.Idle, dropped.state)
        assertEquals(
            LayoutDragVisualPulse(
                token = 9L,
                kind = LayoutDragVisualPulseKind.Drop,
                target = target,
            ),
            dropped.visualPulse,
        )
        assertEquals(LayoutDragHaptic.Drop, dropped.haptic)

        val repeated = reduceLayoutDragFeedback(
            dropped.state,
            LayoutDragFeedbackEvent.DropFinished(token = 9L, accepted = true),
        )

        assertEquals(LayoutDragFeedbackState.Idle, repeated.state)
        assertEquals(null, repeated.visualPulse)
        assertEquals(null, repeated.haptic)
    }

    @Test
    fun rejectedDropAndCancelCleanUpWithoutSuccessFeedback() {
        val dragging = LayoutDragFeedbackState.Dragging(
            token = 11L,
            currentTarget = LayoutDropTarget.LocalDeleted,
        )

        val rejected = reduceLayoutDragFeedback(
            dragging,
            LayoutDragFeedbackEvent.DropFinished(token = 11L, accepted = false),
        )

        assertEquals(LayoutDragFeedbackState.Idle, rejected.state)
        assertEquals(null, rejected.visualPulse)
        assertEquals(null, rejected.haptic)
        assertEquals(true, rejected.clearVisualPulse)

        val cancelled = reduceLayoutDragFeedback(
            dragging,
            LayoutDragFeedbackEvent.Cancelled(token = 11L),
        )

        assertEquals(LayoutDragFeedbackState.Idle, cancelled.state)
        assertEquals(null, cancelled.visualPulse)
        assertEquals(null, cancelled.haptic)
        assertEquals(true, cancelled.clearVisualPulse)
    }

    @Test
    fun staleTargetDropAndCancelEventsCannotAffectTheActiveSession() {
        val state = LayoutDragFeedbackState.Dragging(token = 20L)
        val staleTarget = reduceLayoutDragFeedback(
            state,
            LayoutDragFeedbackEvent.CurrentTargetChanged(
                token = 19L,
                target = LayoutDropTarget.QuickSlot(0),
            ),
        )
        val staleDrop = reduceLayoutDragFeedback(
            staleTarget.state,
            LayoutDragFeedbackEvent.DropFinished(token = 19L, accepted = true),
        )
        val staleCancel = reduceLayoutDragFeedback(
            staleDrop.state,
            LayoutDragFeedbackEvent.Cancelled(token = 19L),
        )

        assertEquals(state, staleCancel.state)
        assertEquals(null, staleTarget.haptic)
        assertEquals(null, staleDrop.haptic)
        assertEquals(null, staleCancel.haptic)
    }

    @Test
    fun disabledSystemAnimationUsesImmediateFeedbackWithoutChangingTheReducer() {
        assertEquals(0, layoutDragFeedbackDurationMillis(motionDurationScale = 0f))
        assertEquals(0, layoutDragFeedbackDurationMillis(motionDurationScale = -1f))
        // LeziMotion.Fast — shared micro-feedback tier under normal motion scale.
        assertEquals(150, layoutDragFeedbackDurationMillis(motionDurationScale = 1f))

        val pickup = reduceLayoutDragFeedback(
            LayoutDragFeedbackState.Idle,
            LayoutDragFeedbackEvent.PickedUp(token = 30L),
        )

        assertEquals(LayoutDragFeedbackState.Dragging(token = 30L), pickup.state)
        assertEquals(LayoutDragHaptic.Pickup, pickup.haptic)
    }
}
