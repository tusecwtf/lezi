package com.lezi.babylog.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RangeTabsScrollDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectedPillKeepsHeightAndMovesInsideScrollingPage() {
        compose.setContent {
            var selected by remember { mutableStateOf("日") }
            LeziTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    LeziRangeTabs(listOf("日", "周", "月"), selected, { selected = it }, { it })
                }
            }
        }
        val before = compose.onNodeWithTag("range_tab_indicator").fetchSemanticsNode().boundsInRoot
        assertTrue("The indicator must have a visible height in unbounded scroll constraints", before.height > 0f)
        compose.onNodeWithText("月").performClick()
        compose.waitForIdle()
        val after = compose.onNodeWithTag("range_tab_indicator").fetchSemanticsNode().boundsInRoot
        assertEquals(before.height, after.height, 0.5f)
        assertTrue(after.left > before.left)
    }
}
