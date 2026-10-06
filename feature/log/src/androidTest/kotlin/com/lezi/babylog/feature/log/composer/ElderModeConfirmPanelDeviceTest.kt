package com.lezi.babylog.feature.log.composer

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
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.designsystem.LeziDeviceViewports
import com.lezi.babylog.designsystem.LeziTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ElderModeConfirmPanelDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun elderConfirmPanelDoesNotClipAcrossStyles() {
        var config by mutableStateOf(ConfirmConfigs.first())
        compose.setContent {
            val current = config
            CompositionLocalProvider(LocalDensity provides current.density) {
                LeziTheme(visualStyle = current.style, elderMode = current.elder) {
                    Box(
                        Modifier
                            .requiredSize(current.width, current.height)
                            .clipToBounds()
                            .testTag("elder_confirm_viewport"),
                    ) {
                        QuickRecordSheet(
                            draft = QuickRecordDraft.create(RecordType.SLEEP, 1_000L),
                            interactionKey =
                                "elder-confirm-panel-${current.style}-${current.elder}",
                            amountStepMl = 5,
                            timeStepMin = 1,
                            saving = false,
                            deleting = false,
                            saveError = null,
                            canStartNursingTimer = false,
                            onDraftChange = {},
                            onDismiss = {},
                            onDelete = {},
                            onConfirm = {},
                            onStartNursingTimer = {},
                            onImportPhotos = {},
                            onImportCapturedPhoto = { _, done -> done(false) },
                            onRemovePhoto = {},
                        )
                    }
                }
            }
        }

        ConfirmConfigs.forEach { next ->
            compose.runOnIdle { config = next }
            compose.waitForIdle()
            compose.onNodeWithTag("quick_record_confirm_save")
                .assertHeightIsAtLeast(60.dp)
            val viewport = compose.onNodeWithTag("elder_confirm_viewport").getUnclippedBoundsInRoot()
            listOf(
                "quick_record_confirm_sheet",
                "quick_record_confirm_header",
                "quick_record_confirm_save",
            ).forEach { tag ->
                val node = compose.onNodeWithTag(tag)
                val clipped = node.getBoundsInRoot()
                val unclipped = node.getUnclippedBoundsInRoot()
                assertEquals(
                    "${next.name} $tag width",
                    unclipped.width.value,
                    clipped.width.value,
                    0.5f,
                )
                assertEquals(
                    "${next.name} $tag height",
                    unclipped.height.value,
                    clipped.height.value,
                    0.5f,
                )
                assertTrue(
                    "${next.name} $tag overflows viewport: $clipped vs $viewport",
                    clipped.left.value >= viewport.left.value - 0.5f &&
                        clipped.top.value >= viewport.top.value - 0.5f &&
                        clipped.right.value <= viewport.right.value + 0.5f &&
                        clipped.bottom.value <= viewport.bottom.value + 0.5f,
                )
            }
        }
    }

    private companion object {
        val ConfirmConfigs = LeziDeviceViewports.styled(elders = listOf("l1", "l3"))
    }
}
