package com.lezi.babylog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lezi.babylog.designsystem.LeziDeviceViewports
import com.lezi.babylog.designsystem.LeziTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ElderModeHeaderDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private companion object {
        val ElderHeaderClusterMinGap = 8.dp
        val HeaderViewports = LeziDeviceViewports.styled()
    }

    @Test
    fun l3PlusMaxFontScaleKeepsNicknameAndAgeReadable() {
        val today = LocalDate.of(2026, 8, 19)
        setElderHeader(babyName = "年年", babyAge = "0月0天", selectedDate = today, today = today)

        listOf("年年", "0月0天", "今天", "周三").forEach { label ->
            val node = compose.onNodeWithText(label)
            node.assertIsDisplayed()
            val clipped = node.getBoundsInRoot()
            val unclipped = node.getUnclippedBoundsInRoot()
            assertEquals(label, unclipped.width.value, clipped.width.value, 0.5f)
            assertEquals(label, unclipped.height.value, clipped.height.value, 0.5f)
        }
    }

    @Test
    fun l3KeepsSleepingNicknameUnclipped() {
        val today = LocalDate.of(2026, 8, 19)
        setElderHeader(
            babyName = "年年",
            babyAge = "0月0天",
            selectedDate = today,
            today = today,
            sleeping = true,
        )

        val nickname = compose.onNodeWithText("年年")
        nickname.assertIsDisplayed()
        val clipped = nickname.getBoundsInRoot()
        val unclipped = nickname.getUnclippedBoundsInRoot()
        assertEquals("年年 width", unclipped.width.value, clipped.width.value, 0.5f)
        assertEquals("年年 height", unclipped.height.value, clipped.height.value, 0.5f)
        compose.onAllNodesWithText("年年睡觉中").assertCountEquals(0)
        compose.onNodeWithContentDescription("年年睡觉中").assertExists()
    }

    @Test
    fun dateTextLengthDoesNotMoveHeaderSlots() {
        val today = LocalDate.of(2026, 8, 19)
        var selectedDate by mutableStateOf(today)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides LeziDeviceViewports.Fhd1080) {
                LeziTheme(elderMode = "l3") {
                    Box(Modifier.requiredWidth(360.dp)) {
                        AppHeaderBar(
                            babyName = "年年",
                            babyAge = "0月0天",
                            avatarPath = null,
                            sleeping = false,
                            selectedDate = selectedDate,
                            today = today,
                            canCycleBaby = false,
                            canGoNext = selectedDate.isBefore(today),
                            dark = false,
                            onCycleBaby = {},
                            onJumpSiblingSameDayAge = {},
                            onPreviousDate = {},
                            onNextDate = {},
                            onOpenDatePicker = {},
                            onSearch = {},
                        )
                    }
                }
            }
        }

        fun slot(label: String) = compose.onNodeWithContentDescription(label).getBoundsInRoot()
        val beforePrev = slot("前一天")
        val beforeNext = slot("后一天")
        val beforeSearch = slot("搜索")
        val beforeName = compose.onNodeWithText("年年").getBoundsInRoot()

        compose.runOnIdle { selectedDate = today.minusDays(12) }

        assertEquals("前一天 left", beforePrev.left.value, slot("前一天").left.value, 0.5f)
        assertEquals("前一天 width", beforePrev.width.value, slot("前一天").width.value, 0.5f)
        assertEquals("后一天 left", beforeNext.left.value, slot("后一天").left.value, 0.5f)
        assertEquals("搜索 left", beforeSearch.left.value, slot("搜索").left.value, 0.5f)
        val afterName = compose.onNodeWithText("年年").getBoundsInRoot()
        assertEquals("昵称 left", beforeName.left.value, afterName.left.value, 0.5f)
        assertEquals("昵称 width", beforeName.width.value, afterName.width.value, 0.5f)
        compose.onNodeWithText("8月7日").assertIsDisplayed()
    }

    @Test
    fun elderLongCopyKeepsHeaderRulesAcrossConfigs() {
        val today = LocalDate.of(2026, 8, 21)
        val selected = LocalDate.of(2026, 8, 19)
        var viewport by mutableStateOf(HeaderViewports.first())
        compose.setContent {
            val current = viewport
            CompositionLocalProvider(LocalDensity provides current.density) {
                LeziTheme(visualStyle = current.style, elderMode = current.elder) {
                    Box(
                        Modifier
                            .requiredSize(current.width, current.height)
                            .clipToBounds()
                            .testTag("elder_header_viewport"),
                    ) {
                        AppHeaderBar(
                            babyName = "乐乐安安美美",
                            babyAge = "11月29天",
                            avatarPath = null,
                            sleeping = false,
                            selectedDate = selected,
                            today = today,
                            canCycleBaby = false,
                            canGoNext = selected.isBefore(today),
                            dark = false,
                            onCycleBaby = {},
                            onJumpSiblingSameDayAge = {},
                            onPreviousDate = {},
                            onNextDate = {},
                            onOpenDatePicker = {},
                            onSearch = {},
                            modifier = Modifier.testTag("elder_header"),
                        )
                    }
                }
            }
        }

        HeaderViewports.forEach { next ->
            compose.runOnIdle { viewport = next }
            compose.waitForIdle()
            val label = next.name
            val viewportBox = compose.onNodeWithTag("elder_header_viewport").getBoundsInRoot()
            val header = compose.onNodeWithTag("elder_header").getBoundsInRoot()
            val baby = compose.onNodeWithContentDescription("乐乐安安美美").getBoundsInRoot()
            val date = compose.onNodeWithContentDescription("选择日期", substring = true).getBoundsInRoot()
            val prev = compose.onNodeWithContentDescription("前一天").getBoundsInRoot()
            val nextDay = compose.onNodeWithContentDescription("后一天").getBoundsInRoot()
            val search = compose.onNodeWithContentDescription("搜索").getBoundsInRoot()

            assertMinGap("$label baby vs date", baby, date)
            assertMinGap("$label baby vs prev", baby, prev)
            assertMinGap("$label date vs search", date, search)
            assertMinGap("$label next vs search", nextDay, search)
            assertTrue(
                "$label header overflows width: $header vs $viewportBox",
                header.right.value <= viewportBox.right.value + 0.5f,
            )
            assertTrue(
                "$label header overflows height: $header vs $viewportBox",
                header.bottom.value <= viewportBox.bottom.value + 0.5f,
            )
            compose.onNodeWithText("8月19日").assertExists()
            if (next.physicalWidthPx <= 1080) {
                listOf("乐乐安安美美", "11月29天", "8月19日").forEach { text ->
                    val node = compose.onNodeWithText(text)
                    node.assertIsDisplayed()
                    val clipped = node.getBoundsInRoot()
                    val unclipped = node.getUnclippedBoundsInRoot()
                    assertEquals("$label $text width", unclipped.width.value, clipped.width.value, 0.5f)
                    assertEquals("$label $text height", unclipped.height.value, clipped.height.value, 0.5f)
                }
            }
        }
    }

    private fun assertMinGap(
        label: String,
        left: DpRect,
        right: DpRect,
        minGap: Dp = ElderHeaderClusterMinGap,
    ) {
        val gap = right.left - left.right
        assertTrue(
            "$label: expected ≥$minGap, was $gap (left=$left right=$right)",
            gap.value + 0.5f >= minGap.value,
        )
    }

    private fun setElderHeader(
        babyName: String,
        babyAge: String,
        selectedDate: LocalDate,
        today: LocalDate,
        sleeping: Boolean = false,
        width: Dp = 390.dp,
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides LeziDeviceViewports.Fhd1080) {
                LeziTheme(elderMode = "l3") {
                    Box(Modifier.requiredWidth(width)) {
                        AppHeaderBar(
                            babyName = babyName,
                            babyAge = babyAge,
                            avatarPath = null,
                            sleeping = sleeping,
                            selectedDate = selectedDate,
                            today = today,
                            canCycleBaby = false,
                            canGoNext = selectedDate.isBefore(today),
                            dark = false,
                            onCycleBaby = {},
                            onJumpSiblingSameDayAge = {},
                            onPreviousDate = {},
                            onNextDate = {},
                            onOpenDatePicker = {},
                            onSearch = {},
                        )
                    }
                }
            }
        }
    }
}
