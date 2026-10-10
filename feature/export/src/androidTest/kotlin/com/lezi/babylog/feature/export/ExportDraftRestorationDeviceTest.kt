package com.lezi.babylog.feature.export

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExportDraftRestorationDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun historicalRangeAndNoPhotosSurviveSavedStateRestoration() {
        val restoration = StateRestorationTester(compose)
        var change: (() -> Unit)? = null
        restoration.setContent {
            var draft by rememberExportRequestDraft()
            change = {
                draft = ExportRequestDraft(LocalDate.of(2025, 1, 30), LocalDate.of(2025, 2, 2), false)
            }
            Text("${draft.from} / ${draft.to} / ${draft.includePhotos}")
        }
        compose.runOnIdle { change!!.invoke() }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("2025-01-30 / 2025-02-02 / false").assertExists()
    }
}
