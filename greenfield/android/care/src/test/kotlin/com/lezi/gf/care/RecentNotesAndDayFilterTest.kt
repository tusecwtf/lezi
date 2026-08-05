package com.lezi.gf.care

import com.google.common.truth.Truth.assertThat
import com.lezi.gf.kernel.FixedClock
import com.lezi.gf.kernel.GfResult
import org.junit.Test

/** R-12 recent notes + D-03 day-type filter — shipped CareService. */
class RecentNotesAndDayFilterTest {

    @Test
    fun recentNoteCandidates_scopedToBabyAndType_newestFirstDistinct() {
        val clock = FixedClock(1_000L)
        val care = CareService(clock = clock)
        care.confirmCreate(
            care.openComposer(RecordType.FORMULA, "b1")
                .copy(note = "加了一勺", dirty = true),
        )
        clock.advance(1_000)
        care.confirmCreate(
            care.openComposer(RecordType.FORMULA, "b1")
                .copy(note = "温一点", dirty = true),
        )
        clock.advance(1_000)
        care.confirmCreate(
            care.openComposer(RecordType.FORMULA, "b1")
                .copy(note = "加了一勺", dirty = true), // duplicate, newest
        )
        clock.advance(1_000)
        care.confirmCreate(
            care.openComposer(RecordType.PEE, "b1")
                .copy(note = "尿备注不应出现在配方候选", dirty = true),
        )
        clock.advance(1_000)
        care.confirmCreate(
            care.openComposer(RecordType.FORMULA, "b2")
                .copy(note = "别的宝宝", dirty = true),
        )
        val cands = care.recentNoteCandidates("b1", RecordType.FORMULA.key)
        // Newest first: 加了一勺 (t=3000), 温一点 (t=2000); distinct drops older 加了一勺
        assertThat(cands).containsExactly("加了一勺", "温一点").inOrder()
        assertThat(cands).doesNotContain("尿备注不应出现在配方候选")
        assertThat(cands).doesNotContain("别的宝宝")
    }

    @Test
    fun timelineFiltered_byType_temporary() {
        val clock = FixedClock(1_700_000_000_000L)
        val care = CareService(clock = clock)
        val day = CareAggregation.dayStartMs(clock.nowEpochMs())
        care.confirmCreate(
            care.openComposer(RecordType.PEE, "b", timestampMs = day + 1000).copy(dirty = true),
        )
        care.confirmCreate(
            care.openComposer(RecordType.FORMULA, "b", timestampMs = day + 2000)
                .copy(payloadJson = """{"amount_ml":60,"amount_step_ml":5}""", dirty = true),
        )
        assertThat(care.timelineFiltered("b", day, typeKeyFilter = null)).hasSize(2)
        val onlyPee = care.timelineFiltered("b", day, typeKeyFilter = RecordType.PEE.key)
        assertThat(onlyPee).hasSize(1)
        assertThat(onlyPee.single().typeKey).isEqualTo("pee")
    }
}
