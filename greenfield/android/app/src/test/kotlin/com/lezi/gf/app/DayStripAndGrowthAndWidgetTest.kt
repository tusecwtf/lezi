package com.lezi.gf.app

import com.google.common.truth.Truth.assertThat
import com.lezi.gf.care.CareAggregation
import com.lezi.gf.care.CareService
import com.lezi.gf.care.GrowthCurves
import com.lezi.gf.care.RecordType
import com.lezi.gf.kernel.FixedClock
import com.lezi.gf.kernel.GfResult
import com.lezi.gf.app.MainActivity
import org.junit.Test

class DayStripAndGrowthAndWidgetTest {

    @Test
    fun selectedDayBindsSummaryAndTimelineIndependently() {
        val clock = FixedClock(1_700_000_000_000L) // fixed
        val care = CareService(clock = clock)
        val today = CareAggregation.dayStartMs(clock.nowEpochMs())
        val dayMs = 86_400_000L
        // write on today and yesterday
        care.confirmCreate(
            care.openComposer(RecordType.PEE, "b", timestampMs = today + 3_600_000).copy(dirty = true),
        )
        care.confirmCreate(
            care.openComposer(RecordType.FORMULA, "b", timestampMs = today - dayMs + 3_600_000)
                .copy(payloadJson = """{"amount_ml":80,"amount_step_ml":5}""", dirty = true),
        )
        val todaySummary = care.daySummary("b", today)
        val ydaySummary = care.daySummary("b", today - dayMs)
        assertThat(todaySummary.peeCount).isEqualTo(1)
        assertThat(todaySummary.milkMl).isEqualTo(0)
        assertThat(ydaySummary.milkMl).isEqualTo(80)
        assertThat(care.timeline("b", today)).hasSize(1)
        assertThat(care.timeline("b", today - dayMs)).hasSize(1)
        assertThat(care.timeline("b", today + dayMs)).isEmpty()
    }

    @Test
    fun growthCurvesBandUsedForDisplayValues() {
        val band = GrowthCurves.weightBandKg(ageMonths = 6, male = true)
        assertThat(band.p3).isLessThan(band.p50)
        assertThat(band.p50).isLessThan(band.p97)
        assertThat(GrowthCurves.NON_DIAGNOSIS_DISCLAIMER).contains("不构成医疗诊断")
    }

    @Test
    fun widgetDeepLinkConstantsOpenComposerWithoutWriteSemantics() {
        // Widget PendingIntent targets MainActivity OPEN_COMPOSER + type extra
        assertThat(MainActivity.ACTION_OPEN_COMPOSER).isEqualTo("com.lezi.gf.app.OPEN_COMPOSER")
        assertThat(MainActivity.EXTRA_COMPOSER_TYPE).isEqualTo("composer_type")
        // openComposer still does not persist
        val care = CareService()
        care.openComposer(RecordType.FORMULA, "b")
        assertThat(care.store().allRecords()).isEmpty()
    }

    @Test
    fun reminderPolicyGradedAndValuesBuilderContract() {
        val policy = com.lezi.gf.care.ReminderPolicy()
        assertThat(policy.gradedDisclosureCopy()).isNotEmpty()
        val plan = (CareService().createPlan("b", "nursing", 9_999) as GfResult.Ok).value
        assertThat(policy.shouldFireLocalReminder(plan)).isTrue()
        assertThat(policy.shouldProjectToSystemCalendar(plan)).isTrue()
        // OS mutation must not reverse into Lezi
        val reverse: Any? = policy.applyOsCalendarMutation("os-1")
        assertThat(reverse).isNull()
    }
}
