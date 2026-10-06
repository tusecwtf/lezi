package com.lezi.babylog.designsystem

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 0.5.4 ticket 11 (T4): the anomaly「!」marker must enter the semantics tree as a
 * row-level stateDescription across every RecordRow variant, and normal rows must
 * stay free of any stateDescription.
 */
@RunWith(AndroidJUnit4::class)
class RecordRowAnomalyStateDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun anomalyFlatListRowSpeaksTheStateAndKeepsItsTap() {
        var clicks = 0
        compose.setContent {
            LeziTheme(visualStyle = "journal") {
                RecordRow(
                    time = "15:41",
                    title = "睡眠",
                    summary = "进行中 · 有异常",
                    relative = "刚刚",
                    anomaly = true,
                    onClick = { clicks += 1 },
                    modifier = Modifier.testTag(ANOMALY_ROW),
                )
            }
        }

        assertAnomalyState(ANOMALY_ROW)
        compose.onNodeWithTag(ANOMALY_ROW).performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun anomalyCardRowSpeaksTheState() {
        compose.setContent {
            LeziTheme(visualStyle = "warm") {
                RecordRow(
                    time = "15:41",
                    title = "睡眠",
                    summary = "进行中 · 有异常",
                    relative = "刚刚",
                    anomaly = true,
                    onClick = {},
                    modifier = Modifier.testTag(ANOMALY_ROW),
                )
            }
        }

        assertAnomalyState(ANOMALY_ROW)
    }

    @Test
    fun anomalyElderRowsSpeakTheStateInBothHosts() {
        listOf("warm", "journal").forEach { style ->
            compose.setContent {
                LeziTheme(visualStyle = style, elderMode = "l1") {
                    RecordRow(
                        time = "15:41",
                        title = "睡眠",
                        summary = "进行中 · 有异常",
                        relative = "刚刚",
                        anomaly = true,
                        onClick = {},
                        modifier = Modifier.testTag(ANOMALY_ROW),
                    )
                }
            }

            assertAnomalyState(ANOMALY_ROW)
        }
    }

    @Test
    fun normalRowsCarryNoStateDescription() {
        compose.setContent {
            LeziTheme(visualStyle = "warm") {
                RecordRow(
                    time = "15:41",
                    title = "睡眠",
                    summary = "进行中",
                    relative = "刚刚",
                    onClick = {},
                    modifier = Modifier.testTag(NORMAL_ROW),
                )
            }
        }

        val config = compose.onNodeWithTag(NORMAL_ROW).fetchSemanticsNode().config
        assertFalse(config.contains(SemanticsProperties.StateDescription))
    }

    private fun assertAnomalyState(tag: String) {
        val config = compose.onNodeWithTag(tag).fetchSemanticsNode().config
        assertTrue(config.contains(SemanticsProperties.StateDescription))
        assertEquals(
            "有异常",
            config[SemanticsProperties.StateDescription].single(),
        )
    }

    private companion object {
        const val ANOMALY_ROW = "anomaly_record_row"
        const val NORMAL_ROW = "normal_record_row"
    }
}
