package com.lezi.babylog.validation

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import com.lezi.babylog.MainActivity
import com.lezi.babylog.core.model.GrowthMeasurementFacts
import com.lezi.babylog.core.model.MeasurementPayload
import com.lezi.babylog.core.model.RecordType
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** US-052 regression guard. The withdrawn system-locale-is-app-locale premise stays withdrawn. */
class ProductionGrowthLocaleDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun actualStartupForcesChineseAndWeightAddEditRoundTrips() = roundTrips(
        RecordType.WEIGHT, "公斤", listOf("5", "5.50", "0.01", "100"), "100.01",
    )

    @Test fun actualStartupForcesChineseAndHeightAddEditRoundTrips() = roundTrips(
        RecordType.HEIGHT, "厘米", listOf("50", "50.5", "0.1", "250"), "250.1",
    )

    private fun roundTrips(type: RecordType, label: String, values: List<String>, aboveLimit: String) {
        val fixture = ProductionAppFixture()
        fixture.seedBaby()
        val diagnostics = ProductionUiDiagnostics(fixture, compose, "growth-${type.name}")
        val oldDefault = Locale.getDefault()
        // Adverse pre-Activity process default is input, not a claim about the app locale.
        Locale.setDefault(Locale.GERMANY)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                diagnostics.beforeTeardown(scenario) {
                    diagnostics.phase("assert production locale and navigate growth")
                    scenario.onActivity { activity ->
                        assertEquals(Locale.CHINA, Locale.getDefault())
                        assertEquals("zh-CN", activity.resources.configuration.locales[0].toLanguageTag())
                    }
                    awaitText("成长")
                    compose.onNode(hasText("成长") and hasClickAction()).performClick()
                    if (type == RecordType.HEIGHT) compose.onNodeWithText("身长/身高").performClick()
                    values.forEachIndexed { index, value ->
                        diagnostics.phase("add $type $value: open actual growth dialog")
                        scrollTo("新增测量")
                        compose.onNodeWithText("新增测量").performClick()
                        diagnostics.phase("await actual editable unit $label after asynchronous openDraft")
                        awaitEditableUnit(label)
                        compose.onNode(hasText(label) and hasSetTextAction()).performTextReplacement(value)
                        val marker = "AppGuard-${type.name}-$index"
                        compose.onNode(hasText("备注（可选）") and hasSetTextAction()).performTextReplacement(marker)
                        compose.onNodeWithText("保存").performClick()
                        compose.waitUntil(10_000) { fixture.records().any { it.note == marker } }
                        diagnostics.phase("verify stored value and open actual edit for $marker")
                        val added = fixture.records().single { it.note == marker }
                        assertEquals(value.toDouble(), GrowthMeasurementFacts.displayValue(
                            added.payload.payload as MeasurementPayload,
                        ), 0.000001)
                        // Open the real history row, leave the production-prefilled number untouched.
                        scrollTo(marker)
                        compose.onNodeWithText(marker).performClick()
                        awaitEditableUnit(label)
                        val expectedText = value.toDouble().toBigDecimal().stripTrailingZeros().toPlainString()
                        compose.onNode(hasText(label) and hasSetTextAction()).assertTextContains(expectedText)
                        compose.onNode(hasText("备注（可选）") and hasSetTextAction())
                            .performTextReplacement("$marker-edited")
                        compose.onNodeWithText("保存修改").performClick()
                        compose.waitUntil(10_000) { fixture.records().any { it.note == "$marker-edited" } }
                        val edited = fixture.records().single { it.id == added.id }
                        assertEquals(added.payloadJson, edited.payloadJson)
                        assertEquals(added.timestamp, edited.timestamp)
                        assertEquals(index + 1, fixture.records().size)
                    }
                    diagnostics.phase("reject boundary and trailing-garbage inputs")
                    val before = fixture.records()
                    scrollTo("新增测量")
                    compose.onNodeWithText("新增测量").performClick()
                    awaitEditableUnit(label)
                    listOf("0", aboveLimit, "5.5kg", "5.5xyz", "5,5").forEach { rejected ->
                        compose.onNode(hasText(label) and hasSetTextAction()).performTextReplacement(rejected)
                        compose.onNodeWithText("保存").performClick()
                        compose.waitForIdle()
                        compose.onNode(hasText(label) and hasSetTextAction()).assertIsDisplayed()
                        assertEquals("Rejected input must not create a record: $rejected", before, fixture.records())
                    }
                    compose.onNodeWithText("取消").performClick()
                }
            }
        } finally {
            Locale.setDefault(oldDefault)
        }
    }

    private fun awaitEditableUnit(label: String) = compose.waitUntil(15_000) {
        compose.onAllNodes(hasText(label) and hasSetTextAction()).fetchSemanticsNodes().size == 1
    }

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
    }

    private fun awaitText(text: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
}
