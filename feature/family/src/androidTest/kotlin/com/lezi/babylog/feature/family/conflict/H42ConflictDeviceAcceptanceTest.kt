package com.lezi.babylog.feature.family.conflict

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.designsystem.LeziTheme
import com.lezi.babylog.domain.carelog.ConflictInbox
import com.lezi.babylog.domain.carelog.ConflictInboxActor
import com.lezi.babylog.domain.carelog.ConflictInboxBranchTombstone
import com.lezi.babylog.domain.carelog.ConflictInboxItem
import com.lezi.babylog.domain.carelog.ConflictInboxMedia
import com.lezi.babylog.feature.family.overview.FamilySyncStatusEntry
import com.lezi.babylog.sync.conflict.ConflictRootType
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
                        inbox = ConflictInbox(items),
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
            .assertContentDescriptionContains("5项待处理", substring = true)
            .performClick()
        composeRule.runOnIdle { assertThat(openedInbox).isTrue() }
        composeRule.onNodeWithText("5 项待处理").assertExists()
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
            .assertContentDescriptionContains("提交者详情待加载", substring = true)
        composeRule.onNodeWithTag("conflict_inbox_item_h42-conflict-1")
            .assertContentDescriptionContains("含删除候选", substring = true)
        composeRule.onNodeWithTag("conflict_inbox_item_h42-conflict-2")
            .assertContentDescriptionContains("2张照片", substring = true)
    }
}

private val rootLabels = listOf("宝宝资料", "护理记录", "护理计划", "自定义项目", "醒来观察")
