package com.lezi.gf.app

import com.google.common.truth.Truth.assertThat
import com.lezi.gf.app.ui.model.ChartSeries
import com.lezi.gf.app.ui.model.MonthCalendarModel
import com.lezi.gf.app.ui.theme.LeziDensity
import com.lezi.gf.app.ui.theme.LeziTypeGlyph
import com.lezi.gf.care.DaySummary
import com.lezi.gf.care.RecordType
import com.lezi.gf.settings.UiTemplate
import org.junit.Test
import java.io.File
import java.time.YearMonth

/**
 * Criterion 3–4 gates: charts, growth, account stack sources, calendar, search,
 * layout, density tokens — separate evidence from P0 log/composer/timer.
 */
class UiuxP1SecondarySurfacesTest {

    @Test
    fun weekChartSeriesDrawableFromAggregation() {
        val days = (0 until 7).map { i ->
            DaySummary(
                dayStartMs = i.toLong(),
                milkMl = if (i % 2 == 0) 100 else 0,
                sleepMinutes = if (i == 1) 60 else 0,
                peeCount = i,
                poopCount = 0,
            )
        }
        val milk = ChartSeries.weekBars(days, ChartSeries.WeekMetric.MILK_ML)
        assertThat(ChartSeries.isDrawable(milk)).isTrue()
        assertThat(ChartSeries.maxBar(milk)).isEqualTo(100f)
        val growth = ChartSeries.growthWeightDrawable(6, true, emptyList(), 0)
        assertThat(growth.nonEmpty).isTrue()
        assertThat(growth.p3).isNotEmpty()
        assertThat(growth.p50.first().y).isGreaterThan(growth.p3.first().y)
    }

    @Test
    fun monthCalendarGridAndBackToToday() {
        val today = MonthCalendarModel.today()
        val grid = MonthCalendarModel.buildGrid(YearMonth.from(today), today, today, true)
        assertThat(grid.cells).hasSize(42)
        assertThat(grid.cells.any { it.isToday }).isTrue()
        val other = MonthCalendarModel.localDateToDayStartMs(today.minusDays(3))
        val now = MonthCalendarModel.todayStartMs(System.currentTimeMillis()) + 1000
        assertThat(MonthCalendarModel.isNotToday(other, now)).isTrue()
    }

    @Test
    fun warmJournalDensityAndGlyphs() {
        val w = LeziDensity.forTemplate(UiTemplate.WARM)
        val j = LeziDensity.forTemplate(UiTemplate.JOURNAL)
        assertThat(w.cardPad.value).isGreaterThan(j.cardPad.value)
        assertThat(w.useCards).isTrue()
        assertThat(j.useCards).isFalse()
        assertThat(LeziTypeGlyph.glyph(RecordType.PEE.key)).isEqualTo("尿")
        assertThat(LeziTypeGlyph.glyph("__timer__")).isEqualTo("计")
    }

    @Test
    fun p1SourceAnchorsForSecondarySurfaces() {
        val root = File("src/main/java/com/lezi/gf/app/ui")
        fun t(p: String) = File(root, p).readText()
        val charts = t("components/Charts.kt")
        assertThat(charts).contains("WeekBarChart")
        assertThat(charts).contains("GrowthCurveCanvas")
        assertThat(charts).contains("Canvas")
        val account = t("account/AccountStack.kt")
        assertThat(account).contains("AccountRoute")
        assertThat(account).contains("MembersQrScreen")
        assertThat(account).contains("ConnectFamilyWizard")
        assertThat(account).contains("createMemberQr")
        assertThat(account).contains("BabyManagementScreen")
        val search = t("search/SearchScreen.kt")
        assertThat(search).contains("搜索屏")
        assertThat(search).contains("container.care.search")
        val layout = t("screens/LayoutCustomEditor.kt")
        assertThat(layout).contains("LayoutEditorScreen")
        assertThat(layout).contains("上移")
        val screens = t("screens/Screens.kt")
        assertThat(screens).contains("PullToRefreshBox")
        assertThat(screens).contains("MonthCalendarDialog")
        assertThat(screens).contains("SearchScreen")
        assertThat(screens).contains("LayoutEditorScreen")
        assertThat(screens).contains("AccountStack")
        assertThat(screens).contains("WeekBarChart")
        assertThat(screens).contains("GrowthCurveCanvas")
        assertThat(screens).contains("小组件")
    }
}
