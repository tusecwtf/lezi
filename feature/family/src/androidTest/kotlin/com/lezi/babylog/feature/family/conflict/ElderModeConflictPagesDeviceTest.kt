package com.lezi.babylog.feature.family.conflict

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
import com.lezi.babylog.designsystem.LeziDeviceViewports
import com.lezi.babylog.designsystem.LeziTheme
import com.lezi.babylog.domain.carelog.ConflictInbox
import com.lezi.babylog.domain.carelog.ConflictInboxActor
import com.lezi.babylog.domain.carelog.ConflictInboxBranchTombstone
import com.lezi.babylog.domain.carelog.ConflictInboxItem
import com.lezi.babylog.domain.carelog.ConflictInboxMedia
import com.lezi.babylog.sync.conflict.ConflictRootType
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ElderModeConflictPagesDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun elderConflictInboxFits720pTo4k() {
        var viewport by mutableStateOf(LeziDeviceViewports.styled().first())
        compose.setContent {
            val current = viewport
            CompositionLocalProvider(LocalDensity provides current.density) {
                LeziTheme(visualStyle = current.style, elderMode = current.elder) {
                    Box(
                        Modifier
                            .requiredSize(current.width, current.height)
                            .clipToBounds()
                            .testTag("elder_conflict_inbox_page"),
                    ) {
                        ConflictInboxContent(
                            phase = ConflictInboxPhase.Content(
                                ConflictInbox(
                                    listOf(
                                        ConflictInboxItem(
                                            conflictId = "conflict-1",
                                            rootType = ConflictRootType.Record,
                                            clientUuid = "root-1",
                                            rootLabel = "护理记录",
                                            title = "配方奶",
                                            babyLabel = "乐乐",
                                            actor = ConflictInboxActor.Known("mem-1", "妈妈"),
                                            stableTombstone = false,
                                            branchTombstone = ConflictInboxBranchTombstone.Known(false),
                                            media = ConflictInboxMedia.Known(1),
                                            updatedAt = 100L,
                                        ),
                                    ),
                                ),
                            ),
                            onOpenConflict = {},
                            modifier = Modifier.testTag("elder_conflict_inbox_content"),
                        )
                    }
                }
            }
        }

        LeziDeviceViewports.styled().forEach { next ->
            compose.runOnIdle { viewport = next }
            compose.waitForIdle()
            val page = compose.onNodeWithTag("elder_conflict_inbox_page").getUnclippedBoundsInRoot()
            val content = compose.onNodeWithTag("elder_conflict_inbox_content").getUnclippedBoundsInRoot()
            assertTrue(
                "${next.name} inbox overflow: $content vs $page",
                content.left.value >= page.left.value - 0.5f &&
                    content.top.value >= page.top.value - 0.5f &&
                    content.right.value <= page.right.value + 0.5f,
            )
            assertTrue("${next.name} inbox collapsed: $content", content.height >= 48.dp)
            compose.onNodeWithText("待处理").assertExists()
            compose.onNodeWithText("1 项待处理").assertExists()
        }
    }

    @Test
    fun elderConflictResolverFits720pTo4k() {
        var viewport by mutableStateOf(LeziDeviceViewports.styled().first())
        compose.setContent {
            val current = viewport
            CompositionLocalProvider(LocalDensity provides current.density) {
                LeziTheme(visualStyle = current.style, elderMode = current.elder) {
                    Box(
                        Modifier
                            .requiredSize(current.width, current.height)
                            .clipToBounds()
                            .testTag("elder_conflict_resolver_page"),
                    ) {
                        ConflictResolverContent(
                            state = ConflictResolverUiState(phase = ConflictResolverPhase.Loading),
                            onSelectVersion = {},
                            onAdopt = {},
                            onAdoptAndEdit = {},
                            onRequestDelete = {},
                            onConfirmDelete = {},
                            onCancelDelete = {},
                            onRefresh = {},
                            onDismiss = {},
                        )
                    }
                }
            }
        }

        LeziDeviceViewports.styled().forEach { next ->
            compose.runOnIdle { viewport = next }
            compose.waitForIdle()
            val page = compose.onNodeWithTag("elder_conflict_resolver_page").getUnclippedBoundsInRoot()
            assertTrue("${next.name} resolver collapsed: $page", page.height >= 48.dp)
            compose.onNodeWithText("解决事实冲突").assertExists()
            compose.onNodeWithText("正在取得最新差异…").assertExists()
        }
    }
}
