package com.lezi.babylog.validation

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import com.lezi.babylog.MainActivity
import com.lezi.babylog.core.model.ProductDateTime
import dagger.hilt.android.EntryPointAccessors
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** US-090: actual MainActivity navigation/ExportRoute plus a real queued export read.
 * Empty synthetic range deliberately avoids renderer/process observation and external sharing.
 * Does not claim process death, successful file request identity, or failure/share return proof.
 */
class ProductionExportDraftRecreationDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun historicalNoPhotoDraftSurvivesActivityRecreationDuringQueuedExportAndCanRetry() {
        val fixture = ProductionAppFixture()
        fixture.seedBaby()
        val owners = EntryPointAccessors.fromApplication(fixture.app, ProductionAcceptanceEntryPoint::class.java)
        runBlocking { owners.settingsStore().setCurrentBabyId(fixture.babyId) }
        val from = LocalDate.of(2025, 1, 30)
        val to = LocalDate.of(2025, 2, 2)
        val expectedRange = "${ProductDateTime.yearMonthDay(from)} — ${ProductDateTime.yearMonthDay(to)}"
        val exportDir = java.io.File(fixture.context.cacheDir, "export")
        val beforeFiles = exportDir.listFiles().orEmpty().map { it.name }.toSet()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitText("菜单")
            compose.onNode(hasText("菜单") and hasClickAction()).performClick()
            awaitText("导出数据")
            compose.onNodeWithText("导出数据").performScrollTo().performClick()
            awaitText("选择开始日期")
            selectDate("选择开始日期", from)
            selectDate("选择结束日期", to)
            compose.onNode(isToggleable()).assertIsOn().performClick().assertIsOff()
            compose.onNodeWithText(expectedRange).assertExists()
            scenario.recreate()
            awaitText(expectedRange)
            compose.onNode(isToggleable()).assertIsOff()

            ProductionRoomTransactionLease(owners.database()).use { lease ->
                compose.onNodeWithText("导出 PDF 并分享").performScrollTo().performClick()
                awaitText("正在生成…")
                compose.onNodeWithText("选择开始日期").assertIsNotEnabled()
                compose.onNodeWithText("选择结束日期").assertIsNotEnabled()
                compose.onNode(isToggleable()).assertIsOff().assertIsNotEnabled()
                scenario.recreate()
                awaitText("正在生成…")
                compose.onNodeWithText(expectedRange).assertExists()
                compose.onNode(isToggleable()).assertIsOff().assertIsNotEnabled()
                compose.onNodeWithText("正在生成…").assertIsNotEnabled().performClick()
                lease.releaseAndAwait()
            }
            awaitTag("export_empty_range")
            compose.onNodeWithText(expectedRange).assertExists()
            compose.onNode(isToggleable()).assertIsOff().assertIsEnabled()
            assertEquals(beforeFiles, exportDir.listFiles().orEmpty().map { it.name }.toSet())
            // Empty is a retryable terminal state, not a permanently locked old task.
            compose.onNodeWithText("导出 PDF 并分享").performScrollTo().performClick()
            awaitTag("export_empty_range")
            compose.onNodeWithText("导出 PDF 并分享").assertIsEnabled()
            scenario.recreate()
            awaitText(expectedRange)
            compose.onNode(isToggleable()).assertIsOff().assertIsEnabled()
            assertEquals(beforeFiles, exportDir.listFiles().orEmpty().map { it.name }.toSet())
        }
    }

    private fun selectDate(button: String, date: LocalDate) {
        compose.onNodeWithText(button).performScrollTo().performClick()
        // Production MainActivity pins zh-CN; at large fonts LeziDatePicker is
        // already in input mode. The normal-size toggle is Material3's resource.
        if (compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithContentDescription("切换到文本字段输入模式").performClick()
        }
        compose.onNode(hasSetTextAction()).performTextReplacement(
            "%04d%02d%02d".format(date.year, date.monthValue, date.dayOfMonth),
        )
        compose.onNodeWithText("确定").performClick()
    }
    private fun awaitText(text: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
    private fun awaitTag(tag: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1
    }
}
