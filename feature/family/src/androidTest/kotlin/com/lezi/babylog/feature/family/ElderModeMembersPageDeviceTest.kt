package com.lezi.babylog.feature.family

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
import com.lezi.babylog.feature.family.members.FamilyMembersListSheet
import com.lezi.babylog.feature.family.members.MembersDevicesUi
import com.lezi.babylog.sync.FamilyDevice
import com.lezi.babylog.sync.FamilyMember
import com.lezi.babylog.sync.session.FamilyRole
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ElderModeMembersPageDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun elderMembersSheetFits720pTo4k() {
        var viewport by mutableStateOf(LeziDeviceViewports.styled().first())
        compose.setContent {
            val current = viewport
            CompositionLocalProvider(LocalDensity provides current.density) {
                LeziTheme(visualStyle = current.style, elderMode = current.elder) {
                    Box(
                        Modifier
                            .requiredSize(current.width, current.height)
                            .clipToBounds()
                            .testTag("elder_members_page"),
                    ) {
                        FamilyMembersListSheet(
                            ui = MembersDevicesUi(
                                identity = FamilyIdentityUi(
                                    displayName = "妈妈",
                                    enabled = true,
                                    familyId = "fam-1",
                                    membershipId = "owner-self",
                                    role = FamilyRole.Owner,
                                    familyName = "张家",
                                ),
                                members = listOf(
                                    FamilyMember(
                                        "妈妈",
                                        FamilyRole.Owner,
                                        isSelf = true,
                                        membershipId = "owner-self",
                                        devices = listOf(
                                            FamilyDevice("phone", "我的手机", 1_700_000_000L, true),
                                        ),
                                    ),
                                    FamilyMember(
                                        "奶奶",
                                        FamilyRole.Member,
                                        isSelf = false,
                                        membershipId = "member-nainai",
                                        devices = emptyList(),
                                    ),
                                ),
                                membersLoaded = true,
                            ),
                            onRefreshMembers = {},
                            onEditMyDisplayName = {},
                            onDismiss = {},
                        )
                    }
                }
            }
        }

        LeziDeviceViewports.styled().forEach { next ->
            compose.runOnIdle { viewport = next }
            compose.waitForIdle()
            val page = compose.onNodeWithTag("elder_members_page").getUnclippedBoundsInRoot()
            assertTrue("${next.name} members page collapsed: $page", page.height >= 48.dp)
            compose.onNodeWithText("家庭成员与设备").assertExists()
            compose.onNodeWithTag("members_roster_list").assertExists()
        }
    }
}
