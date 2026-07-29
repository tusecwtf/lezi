package com.lezi.babylog.feature.family

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.designsystem.LeziTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FamilyAccountAffordanceSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun offlineSyncEntryNamesStateActionAndUsesFortyEightDpTarget() {
        var openNetworkCount = 0
        compose.setContent {
            LeziTheme {
                FamilySyncStatusEntry(
                    statusLabel = OFFLINE_RESULT,
                    isError = true,
                    onOpenNetwork = { openNetworkCount += 1 },
                    modifier = Modifier.testTag(SYNC_ENTRY),
                )
            }
        }

        val entry = compose.onNodeWithTag(SYNC_ENTRY)
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .assertTextContains(OFFLINE_RESULT)
        val semantics = entry.fetchSemanticsNode().config
        assertThat(semantics[SemanticsProperties.Role]).isEqualTo(Role.Button)
        assertThat(semantics[SemanticsProperties.ContentDescription])
            .containsExactly("同步状态：$OFFLINE_RESULT")
        assertThat(semantics[SemanticsActions.OnClick].label).isEqualTo("打开网络设置")

        entry.performClick()
        compose.runOnIdle { assertThat(openNetworkCount).isEqualTo(1) }
    }

    @Test
    fun focusTraversalMovesFromMemberRosterToSyncStatus() {
        compose.setContent {
            LeziTheme {
                Column {
                    FamilyMemberRosterEntry(
                        label = "3 位家人",
                        onOpenMembers = {},
                        modifier = Modifier.testTag(MEMBER_ENTRY),
                    )
                    FamilySyncStatusEntry(
                        statusLabel = "等待连接家庭网络",
                        isError = false,
                        onOpenNetwork = {},
                        modifier = Modifier.testTag(SYNC_ENTRY),
                    )
                }
            }
        }

        val member = compose.onNodeWithTag(MEMBER_ENTRY).fetchSemanticsNode().config
        val sync = compose.onNodeWithTag(SYNC_ENTRY).fetchSemanticsNode().config
        assertThat(member[SemanticsProperties.TraversalIndex]).isLessThan(
            sync[SemanticsProperties.TraversalIndex],
        )
    }

    private companion object {
        const val MEMBER_ENTRY = "family_member_entry"
        const val SYNC_ENTRY = "family_sync_entry"
        const val OFFLINE_RESULT = "未连接家庭网络，请连接家里 Wi-Fi 后同步"
    }
}
