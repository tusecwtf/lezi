package com.lezi.babylog.feature.family.conflict

import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.designsystem.LeziTheme
import com.lezi.babylog.domain.carelog.ConflictInbox
import com.lezi.babylog.domain.carelog.ConflictInboxActor
import com.lezi.babylog.domain.carelog.ConflictInboxBranchTombstone
import com.lezi.babylog.domain.carelog.ConflictInboxItem
import com.lezi.babylog.domain.carelog.ConflictInboxMedia
import com.lezi.babylog.sync.conflict.ConflictRootType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConflictInboxDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun inboxRendersFiveRootsTombstoneMediaActorAndRoutesByConflictId() {
        val opened = mutableListOf<String>()
        val items = ConflictRootType.entries.mapIndexed { index, rootType ->
            ConflictInboxItem(
                conflictId = "conflict-$index",
                rootType = rootType,
                clientUuid = "root-$index",
                rootLabel = listOf("宝宝资料", "护理记录", "护理计划", "自定义项目", "醒来观察")[index],
                title = "标题$index",
                babyLabel = "豆豆".takeUnless { rootType == ConflictRootType.CustomItem },
                actor = if (rootType == ConflictRootType.Baby) {
                    ConflictInboxActor.RequiresDetail
                } else {
                    ConflictInboxActor.Known(
                        membershipId = "member-$index",
                        label = if (index == 3) "member-3" else "家人$index",
                    )
                },
                stableTombstone = index == 0,
                branchTombstone = ConflictInboxBranchTombstone.RequiresDetail,
                media = ConflictInboxMedia.RequiresDetail(index),
                updatedAt = 100L - index,
            )
        }
        composeRule.setContent {
            LeziTheme(visualStyle = "warm") {
                ConflictInboxContent(
                    phase = ConflictInboxPhase.Content(ConflictInbox(items)),
                    onOpenConflict = { opened += it },
                )
            }
        }

        ConflictRootType.entries.forEachIndexed { index, _ ->
            composeRule.onNodeWithTag("conflict_inbox_item_conflict-$index").assertExists()
        }
        composeRule.onNodeWithText("5 项待处理").assertExists()
        composeRule.onNodeWithTag("conflict_inbox_item_conflict-0")
            .assertContentDescriptionContains("当前已删除", substring = true)
        composeRule.onNodeWithTag("conflict_inbox_item_conflict-1")
            .assertContentDescriptionContains("提交者 家人1", substring = true)
        composeRule.onNodeWithTag("conflict_inbox_item_conflict-3")
            .assertContentDescriptionContains("member-3", substring = true)
            .performClick()
        composeRule.runOnIdle { assertThat(opened).containsExactly("conflict-3") }
    }

    @Test
    fun inboxNeverRendersEngineeringPlaceholderWords() {
        composeRule.setContent {
            LeziTheme(visualStyle = "warm") {
                ConflictInboxContent(
                    phase = ConflictInboxPhase.Content(loadedInbox()),
                    onOpenConflict = {},
                )
            }
        }

        // 票 08：未就绪的元数据行不渲染；「待加载」工程词不再出现在任何卡片里。
        loadedInbox().items.forEach { item ->
            val descriptions = composeRule.onNodeWithTag("conflict_inbox_item_${item.conflictId}")
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.ContentDescription)
                .orEmpty()
            assertThat(descriptions.none { it.contains("待加载") }).isTrue()
        }
        composeRule.onNodeWithTag("conflict_inbox_item_card-cold")
            .assertContentDescriptionContains("正在获取详情…", substring = true)
    }

    @Test
    fun completeCachedDetailRendersExactBranchDeletionAndMediaTotal() {
        composeRule.setContent {
            LeziTheme(visualStyle = "warm") {
                ConflictInboxContent(
                    phase = ConflictInboxPhase.Content(loadedInbox()),
                    onOpenConflict = {},
                )
            }
        }

        composeRule.onNodeWithTag("conflict_inbox_item_conflict-cached")
            .assertContentDescriptionContains("提交者 妈妈", substring = true)
            .assertContentDescriptionContains("含删除候选", substring = true)
            .assertContentDescriptionContains("3张照片", substring = true)
    }

    private fun loadedInbox() = ConflictInbox(
        listOf(
            ConflictInboxItem(
                conflictId = "conflict-cold",
                rootType = ConflictRootType.Baby,
                clientUuid = "baby-cold",
                rootLabel = "宝宝资料",
                title = "豆豆",
                babyLabel = "豆豆",
                actor = ConflictInboxActor.RequiresDetail,
                stableTombstone = false,
                branchTombstone = ConflictInboxBranchTombstone.RequiresDetail,
                media = ConflictInboxMedia.RequiresDetail(0),
                updatedAt = 200,
            ),
            ConflictInboxItem(
                conflictId = "conflict-cached",
                rootType = ConflictRootType.Record,
                clientUuid = "record-cached",
                rootLabel = "护理记录",
                title = "配方奶",
                babyLabel = "豆豆",
                actor = ConflictInboxActor.Known("member-stable", "妈妈"),
                stableTombstone = false,
                branchTombstone = ConflictInboxBranchTombstone.Known(true),
                media = ConflictInboxMedia.Known(3),
                updatedAt = 100,
            ),
        ),
    )
}
