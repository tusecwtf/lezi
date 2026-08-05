package com.lezi.gf.care

import com.google.common.truth.Truth.assertThat
import com.lezi.gf.kernel.FixedClock
import com.lezi.gf.kernel.GfResult
import org.junit.Test

/** G2: nursing timer → confirm → next feed plan → fulfill. */
class G2TimerNextFeedTest {
    @Test
    fun timerCompleteRequiresConfirmBeforeWrite() {
        val clock = FixedClock(10_000L)
        val care = CareService(clock = clock)
        care.startTimer("b", "L")
        clock.advance(60_000)
        care.startTimer("b", "R")
        clock.advance(30_000)
        // complete freezes draft — no record yet
        val draft = care.completeTimer()
        assertThat(draft).isInstanceOf(GfResult.Ok::class.java)
        assertThat(care.store().allRecords()).isEmpty()

        val d = (draft as GfResult.Ok).value
        care.confirmCreate(d)
        assertThat(care.store().allRecords()).hasSize(1)
        assertThat(care.store().allRecords()[0].typeKey).isEqualTo("nursing")
    }

    @Test
    fun nextFeedPlanPendingThenFulfill() {
        val clock = FixedClock(100_000L)
        val care = CareService(clock = clock)
        val plan = care.createPlan(
            babyClientUuid = "b",
            typeKey = RecordType.NURSING.key,
            scheduledAtMs = 200_000L,
            isNextFeed = true,
        )
        assertThat(plan).isInstanceOf(GfResult.Ok::class.java)
        val p = (plan as GfResult.Ok).value
        assertThat(care.pendingPlans("b").map { it.clientUuid }).contains(p.clientUuid)

        clock.set(250_000L)
        val fulfilled = care.fulfillPlan(p.clientUuid)
        assertThat(fulfilled).isInstanceOf(GfResult.Ok::class.java)
        val (done, record) = (fulfilled as GfResult.Ok).value
        assertThat(done.status).isEqualTo(PlanStatus.COMPLETED)
        assertThat(record.linkedPlanUuid).isEqualTo(p.clientUuid)
        assertThat(care.pendingPlans("b")).isEmpty()
    }

    @Test
    fun recoverTimerAfterProcessDeath() {
        val store = InMemoryCareStore()
        val care = CareService(store)
        care.startTimer("b", "L")
        val snap = store.snapshot()
        val store2 = InMemoryCareStore()
        store2.restore(snap)
        val care2 = CareService(store2)
        assertThat(care2.recoverTimer()?.activeSide).isEqualTo("L")
    }
}
