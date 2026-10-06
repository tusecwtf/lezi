package com.lezi.babylog.feature.family.conflict

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.designsystem.LeziTheme
import com.lezi.babylog.domain.carelog.ConflictInbox
import com.lezi.babylog.domain.carelog.ConflictInboxActor
import com.lezi.babylog.domain.carelog.ConflictInboxBranchTombstone
import com.lezi.babylog.domain.carelog.ConflictInboxItem
import com.lezi.babylog.domain.carelog.ConflictInboxMedia
import com.lezi.babylog.feature.family.overview.FamilySyncStatusEntry
import com.lezi.babylog.sync.conflict.ConflictRootType
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** H42 gap source: badge and the already-shared inbox must expose one root count. */
@RunWith(AndroidJUnit4::class)
class H42ConflictDeviceAcceptanceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun badgeCountAndInboxListShareFiveRootTombstoneMatrix() {
        val items = ConflictRootType.entries.mapIndexed { index, rootType ->
            ConflictInboxItem(
                conflictId = "h42-conflict-$index",
                rootType = rootType,
                clientUuid = "h42-root-$index",
                rootLabel = rootLabels[index],
                title = "H42-$index",
                babyLabel = "豆豆".takeUnless { rootType == ConflictRootType.CustomItem },
                actor = if (rootType == ConflictRootType.Baby) {
                    ConflictInboxActor.RequiresDetail
                } else {
                    ConflictInboxActor.Known("member-$index", "家人$index")
                },
                stableTombstone = index == 0,
                branchTombstone = if (index == 1) {
                    ConflictInboxBranchTombstone.Known(true)
                } else {
                    ConflictInboxBranchTombstone.RequiresDetail
                },
                media = if (index == 2) {
                    ConflictInboxMedia.Known(2)
                } else {
                    ConflictInboxMedia.RequiresDetail(index)
                },
                updatedAt = 500L - index,
            )
        }
        val opened = mutableListOf<String>()
        var openedInbox by mutableStateOf(false)
        composeRule.setContent {
            LeziTheme(visualStyle = "warm") {
                if (openedInbox) {
                    ConflictInboxContent(
                        phase = ConflictInboxPhase.Content(ConflictInbox(items)),
                        onOpenConflict = { opened += it },
                    )
                } else {
                    FamilySyncStatusEntry(
                        statusLabel = "已连接",
                        isError = false,
                        openConflictCount = 5,
                        isJoined = true,
                        onOpenConflictInbox = { openedInbox = true },
                    )
                }
            }
        }

        composeRule.onNodeWithTag("family_conflict_inbox_badge")
            .assertContentDescriptionContains("家庭待处理，5项，打开待处理列表", substring = true)
        captureEvidence("h42-family-conflict-badge.png")
        composeRule.onNodeWithTag("family_conflict_inbox_badge")
            .performClick()
        composeRule.runOnIdle { assertThat(openedInbox).isTrue() }
        composeRule.onNodeWithText("5 项待处理").assertExists()
        captureEvidence("h42-five-root-conflict-inbox.png")
        items.forEachIndexed { index, item ->
            composeRule.onNodeWithTag("conflict_inbox_item_${item.conflictId}")
                .assertContentDescriptionContains(rootLabels[index], substring = true)
                .performClick()
        }
        composeRule.runOnIdle {
            assertThat(opened).containsExactlyElementsIn(items.map(ConflictInboxItem::conflictId))
        }
        composeRule.onNodeWithTag("conflict_inbox_item_h42-conflict-0")
            .assertContentDescriptionContains("当前已删除", substring = true)
        // 票 08：未就绪的元数据行不渲染，「待加载」工程词不再出现。
        val descriptions = composeRule.onNodeWithTag("conflict_inbox_item_h42-conflict-0")
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.ContentDescription)
            .orEmpty()
        assertThat(descriptions.none { it.contains("待加载") }).isTrue()
        composeRule.onNodeWithTag("conflict_inbox_item_h42-conflict-1")
            .assertContentDescriptionContains("含删除候选", substring = true)
        composeRule.onNodeWithTag("conflict_inbox_item_h42-conflict-2")
            .assertContentDescriptionContains("2张照片", substring = true)
    }

    private fun captureEvidence(fileName: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "h42-evidence").apply {
            check(exists() || mkdirs()) { "Cannot create H42 evidence directory" }
        }
        File(directory, fileName).outputStream().use { output ->
            check(
                composeRule.onRoot().captureToImage().asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, output),
            ) { "Cannot write H42 screenshot evidence" }
        }
    }
}

private val rootLabels = listOf("宝宝资料", "护理记录", "护理计划", "自定义项目", "醒来观察")
