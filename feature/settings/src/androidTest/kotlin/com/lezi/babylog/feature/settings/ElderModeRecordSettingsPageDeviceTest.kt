package com.lezi.babylog.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lezi.babylog.core.model.SettingsLocal
import com.lezi.babylog.designsystem.LeziDeviceViewports
import com.lezi.babylog.designsystem.LeziTheme
import com.lezi.babylog.feature.settings.record.RecordSettingsDialog
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ElderModeRecordSettingsPageDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun elderRecordSettingsFits720pTo4k() {
        var viewport by mutableStateOf(LeziDeviceViewports.styled().first())
        compose.setContent {
            val current = viewport
            CompositionLocalProvider(LocalDensity provides current.density) {
                LeziTheme(visualStyle = current.style, elderMode = current.elder) {
                    Box(
                        Modifier
                            .requiredSize(current.width, current.height)
                            .clipToBounds()
                            .testTag("elder_record_settings_page"),
                    ) {
                        RecordSettingsDialog(
                            settings = SettingsLocal(),
                            carePlanRemindersEnabled = true,
                            onCarePlanRemindersEnabled = {},
                            notificationPermissionWarning = null,
                            onOpenNotificationSettings = {},
                            systemCalendarEnabled = false,
                            systemCalendarSummary = "未开启",
                            systemCalendarDisclosureSummary = "宝宝昵称·类型",
                            onConfigureSystemCalendar = {},
                            onTimerEnabled = {},
                            onRecordAt = {},
                            onInterval = {},
                            onAmountStep = {},
                            onFeverAdvice = {},
                            onDismiss = {},
                        )
                    }
                }
            }
        }

        LeziDeviceViewports.styled().forEach { next ->
            compose.runOnIdle { viewport = next }
            compose.waitForIdle()
            val page = compose.onNodeWithTag("elder_record_settings_page").getUnclippedBoundsInRoot()
            assertTrue("${next.name} record settings collapsed: $page", page.height >= 48.dp)
            compose.onNodeWithText("记录设置").assertExists()
            compose.onNodeWithText("完成").assertExists()
        }
    }
}
