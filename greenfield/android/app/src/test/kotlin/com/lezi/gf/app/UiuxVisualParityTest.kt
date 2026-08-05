package com.lezi.gf.app

import com.google.common.truth.Truth.assertThat
import com.lezi.gf.app.ui.model.DockModel
import com.lezi.gf.app.ui.theme.LeziDayAge
import com.lezi.gf.app.ui.theme.LeziDensity
import com.lezi.gf.app.ui.theme.LeziMotion
import com.lezi.gf.app.ui.theme.LeziRelativeTime
import com.lezi.gf.app.ui.theme.SwipeGestureModel
import com.lezi.gf.app.ui.theme.TimeDialModel
import com.lezi.gf.care.LayoutSnapshot
import com.lezi.gf.settings.UiTemplate
import org.junit.Test
import java.io.File

/**
 * Spec 02 — APK visual parity pure logic + source structure anchors (E/T/A).
 */
class UiuxVisualParityTest {

    @Test
    fun motionTokenTableAndReduceMotion() {
        assertThat(LeziMotion.Fast).isEqualTo(150)
        assertThat(LeziMotion.Base).isEqualTo(200)
        assertThat(LeziMotion.Emphasized).isEqualTo(300)
        assertThat(LeziMotion.nonEssentialMillis(reduceMotion = true, base = LeziMotion.Base)).isEqualTo(0)
        assertThat(LeziMotion.millis(reduceMotion = false, LeziMotion.Emphasized)).isEqualTo(300)
        assertThat(LeziMotion.millis(reduceMotion = true, LeziMotion.Fast)).isEqualTo(0)
    }

    @Test
    fun densityWarmVsJournal() {
        val w = LeziDensity.forTemplate(UiTemplate.WARM)
        val j = LeziDensity.forTemplate(UiTemplate.JOURNAL)
        assertThat(w.useCards).isTrue()
        assertThat(j.useCards).isFalse()
        assertThat(w.cardCorner.value).isEqualTo(8f)
        assertThat(j.cardCorner.value).isEqualTo(0f)
        assertThat(w.panelContent.value).isGreaterThan(j.panelContent.value)
    }

    @Test
    fun relativeTimeCopy() {
        val now = 1_700_000_000_000L
        assertThat(LeziRelativeTime.format(now - 30_000, now)).isEqualTo("刚刚")
        assertThat(LeziRelativeTime.format(now - 5 * 60_000, now)).isEqualTo("5 分钟前")
        assertThat(LeziRelativeTime.format(now - 2 * 3_600_000, now)).isEqualTo("2 小时前")
        assertThat(LeziRelativeTime.format(now - 3 * 86_400_000L, now)).isEqualTo("3 天前")
        assertThat(LeziRelativeTime.format(now + 10 * 60_000, now)).isEqualTo("10 分钟后")
    }

    @Test
    fun dayAgeFormat() {
        assertThat(LeziDayAge.format(0, 20_000)).isNull()
        assertThat(LeziDayAge.format(20_000 - 10, 20_000, useDayAgeMode = true)).isEqualTo("生后 10 日")
        assertThat(LeziDayAge.format(20_000 - 45, 20_000, useDayAgeMode = false)).isEqualTo("1 个月 15 天")
        assertThat(LeziDayAge.format(20_001, 20_000)).isEqualTo("未出生")
    }

    @Test
    fun swipeOffsetToActionMapping() {
        assertThat(SwipeGestureModel.settle(0f)).isEqualTo(SwipeGestureModel.Settle.CLOSED)
        assertThat(SwipeGestureModel.settle(-0.30f)).isEqualTo(SwipeGestureModel.Settle.REVEAL_EDIT)
        assertThat(SwipeGestureModel.settle(0.30f)).isEqualTo(SwipeGestureModel.Settle.REVEAL_DELETE)
        assertThat(SwipeGestureModel.settle(-0.60f)).isEqualTo(SwipeGestureModel.Settle.COMMIT_EDIT)
        assertThat(SwipeGestureModel.settle(0.60f)).isEqualTo(SwipeGestureModel.Settle.COMMIT_DELETE)
        assertThat(SwipeGestureModel.clampedOffset(2f)).isEqualTo(1f)
        assertThat(SwipeGestureModel.settleTarget(SwipeGestureModel.Settle.REVEAL_EDIT))
            .isEqualTo(-SwipeGestureModel.REVEAL_FRACTION)
    }

    @Test
    fun timeDialMinutesApply() {
        val base = 1_700_000_000_000L
        assertThat(TimeDialModel.applyMinutes(base, 5)).isEqualTo(base + 5 * 60_000L)
        assertThat(TimeDialModel.applyMinutes(base, -5)).isEqualTo(base - 5 * 60_000L)
        // full circumference drag ≈ 60 minutes
        assertThat(TimeDialModel.minutesFromDragPx(100f, 100f)).isEqualTo(60)
    }

    @Test
    fun dockMoveSlotPure() {
        val moved = DockModel.moveSlot(listOf("pee", "sleep", "nursing", "formula"), 0, 2)
        assertThat(moved).containsExactly("sleep", "nursing", "pee", "formula").inOrder()
    }

    @Test
    fun sourceAnchorsTimeDialSwipeMotionMoreSheet() {
        val root = File("src/main/java/com/lezi/gf/app")
        fun t(p: String) = File(root, p).readText()

        assertThat(File(root, "ui/components/TimeDial.kt").isFile).isTrue()
        assertThat(File(root, "ui/components/SwipeTimelineRow.kt").isFile).isTrue()
        assertThat(File(root, "ui/components/MoreSheet.kt").isFile).isTrue()

        val tokens = t("ui/theme/LeziTokens.kt")
        assertThat(tokens).contains("object LeziMotion")
        assertThat(tokens).contains("object SwipeGestureModel")
        assertThat(tokens).contains("object LeziRelativeTime")
        assertThat(tokens).contains("object LeziDayAge")

        val composer = t("ui/log/ComposerSheet.kt")
        assertThat(composer).contains("TimeDial")
        assertThat(composer).contains("ModalBottomSheet")
        assertThat(composer).contains("fillMaxWidth()")
        assertThat(composer).contains("确认记录")
        assertThat(composer).contains("快捷奶量")

        val shell = t("ui/LeziNavShell.kt")
        assertThat(shell).contains("LeziMotion")
        assertThat(shell).contains("AnimatedContent")
        assertThat(shell).contains("reduceMotion")

        val screens = t("ui/screens/Screens.kt")
        assertThat(screens).contains("SwipeTimelineRow")
        assertThat(screens).contains("MoreSheet")
        assertThat(screens).contains("LeziDayAge")
        assertThat(screens).contains("babyAccent")

        val layout = t("ui/screens/LayoutCustomEditor.kt")
        assertThat(layout).contains("detectDragGesturesAfterLongPress")
        assertThat(layout).contains("拖动手柄")

        val timer = t("ui/log/NursingTimerScreen.kt")
        assertThat(timer).contains("TimerButton")
        assertThat(timer).contains("ModalBottomSheet")

        // more catalog still 4-col contract
        val more = t("ui/components/MoreSheet.kt")
        assertThat(more).contains("GridCells.Fixed(4)")
        assertThat(more).contains("ModalBottomSheet")
    }

    @Test
    fun moreCatalogIncludesTimerAndBuiltins() {
        val cat = DockModel.moreCatalog(LayoutSnapshot(), emptyList(), includeTimer = true)
        assertThat(cat.any { it.bindingKey == "__timer__" }).isTrue()
        assertThat(cat.size).isAtLeast(10)
    }
}
