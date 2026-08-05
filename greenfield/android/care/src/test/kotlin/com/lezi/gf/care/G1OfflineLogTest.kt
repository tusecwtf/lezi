package com.lezi.gf.care

import com.google.common.truth.Truth.assertThat
import com.lezi.gf.kernel.FixedClock
import com.lezi.gf.kernel.GfResult
import org.junit.Test

/** G1: offline confirm-log — open does not write; confirm writes; day summary; kill-safe store. */
class G1OfflineLogTest {
    @Test
    fun openComposerDoesNotPersist() {
        val store = InMemoryCareStore()
        val care = CareService(store)
        val draft = care.openComposer(RecordType.FORMULA, "baby-1")
        assertThat(store.allRecords()).isEmpty()
        assertThat(draft.type).isEqualTo(RecordType.FORMULA)
    }

    @Test
    fun confirmWritesFormulaPeeSleep_visibleInDaySummaryAndTimeline() {
        val clock = FixedClock(1_700_000_000_000L)
        val care = CareService(clock = clock)
        val baby = "baby-1"
        val day = CareAggregation.dayStartMs(clock.nowEpochMs())

        fun confirm(type: RecordType, payload: String = "{}") {
            val d = care.openComposer(type, baby, clock.nowEpochMs())
            val r = care.confirmCreate(d.copy(payloadJson = payload.ifBlank { d.payloadJson }, dirty = true))
            assertThat(r).isInstanceOf(GfResult.Ok::class.java)
        }

        confirm(RecordType.FORMULA, """{"amount_ml":120,"amount_step_ml":5}""")
        confirm(RecordType.PEE, """{"pee_amount":2}""")
        confirm(RecordType.SLEEP, """{"duration_minutes":90,"anomaly":false}""")

        val summary = care.daySummary(baby, day)
        assertThat(summary.milkMl).isEqualTo(120)
        assertThat(summary.peeCount).isEqualTo(1)
        assertThat(summary.sleepMinutes).isEqualTo(90)

        val timeline = care.timeline(baby, day)
        assertThat(timeline).hasSize(3)
        // Chinese labels for export
        val txt = care.exportTxt(baby)
        assertThat(txt).contains("配方奶")
        assertThat(txt).contains("尿")
        assertThat(txt).doesNotContain("\tformula\t")
    }

    @Test
    fun killProcessRestart_recordsStillPresent() {
        val store = InMemoryCareStore()
        val care = CareService(store)
        val d = care.openComposer(RecordType.FORMULA, "b")
        care.confirmCreate(d.copy(payloadJson = """{"amount_ml":60,"amount_step_ml":5}""", dirty = true))
        val snap = store.snapshot()

        val store2 = InMemoryCareStore()
        store2.restore(snap)
        val care2 = CareService(store2)
        assertThat(care2.store().allRecords()).hasSize(1)
        assertThat(care2.store().allRecords()[0].typeKey).isEqualTo("formula")
    }

    @Test
    fun futureTimestampBecomesPlanNotFact() {
        val clock = FixedClock(1_000L)
        val care = CareService(clock = clock)
        val d = care.openComposer(RecordType.FORMULA, "b", timestampMs = 1_000_000L)
        val r = care.confirmCreate(d)
        assertThat(r).isInstanceOf(GfResult.Err::class.java)
        assertThat(care.store().allRecords()).isEmpty()
        assertThat(care.store().allPlans()).hasSize(1)
    }
}
