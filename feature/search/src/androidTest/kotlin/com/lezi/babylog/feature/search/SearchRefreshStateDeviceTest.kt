package com.lezi.babylog.feature.search

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.designsystem.LeziTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchRefreshStateDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun retainedRowsExposeViewSemanticsWithWarningAndRetryUntilRecovery() {
        val hit = SearchResult(
            record = Record(
                id = 1,
                clientUuid = "record-1",
                babyId = 1,
                type = RecordType.DIARY,
                timestamp = 1_700_000_000_000L,
                note = "旧结果",
                updatedAt = 1_700_000_000_000L,
            ),
            canEdit = true,
        )
        val ui = mutableStateOf(SearchUi(query = "旧结果", results = listOf(hit), failed = true, readOnly = true))
        var retries = 0
        var opened = 0
        compose.setContent {
            LeziTheme {
                SearchResultsList(
                    ui = ui.value,
                    onRetry = { retries += 1 },
                    onOpenRecord = { opened += 1 },
                )
            }
        }

        compose.onNodeWithText("未能刷新。上次结果可能已过期，暂仅供查看；可重试获取最新记录。").assertExists()
        compose.onNodeWithContentDescription("查看日记").performClick()
        compose.onNodeWithText("重试").performClick()
        compose.runOnIdle {
            assertEquals(1, opened)
            assertEquals(1, retries)
            ui.value = ui.value.copy(searching = true, failed = false)
        }
        compose.onNodeWithText("正在重试…").assertIsNotEnabled()
        compose.onNodeWithContentDescription("查看日记").assertExists()

        compose.runOnIdle { ui.value = ui.value.copy(searching = false, readOnly = false) }
        compose.onNodeWithText("未能刷新。上次结果可能已过期，暂仅供查看；可重试获取最新记录。").assertDoesNotExist()
        compose.onNodeWithContentDescription("编辑日记").assertExists()
    }
}
