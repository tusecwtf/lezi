package com.lezi.babylog.feature.family

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.core.model.SyncStatus
import com.lezi.babylog.designsystem.LeziDeviceViewports
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTheme
import com.lezi.babylog.designsystem.PageScaffoldBackground
import com.lezi.babylog.feature.family.components.familyPrimarySurface
import com.lezi.babylog.feature.family.members.MembersDevicesUi
import com.lezi.babylog.feature.family.overview.AccountOverviewUi
import com.lezi.babylog.feature.family.overview.AccountPageContent
import com.lezi.babylog.sync.session.FamilyRole
import java.time.LocalDate
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ElderModeFamilyPageDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun elderAccountPageFits720pTo4k() {
        val baby = Baby(
            id = 1,
            familyId = 1,
            nickname = "乐乐安安美美",
            birthdayEpochDay = LocalDate.of(2025, 9, 12).toEpochDay(),
            themeColorArgb = 0xFF8D6E63.toInt(),
            clientUuid = "baby-1",
            updatedAt = 1L,
        )
        val identity = FamilyIdentityUi(
            displayName = "妈妈",
            enabled = true,
            familyId = "fam-1",
            membershipId = "mem-1",
            role = FamilyRole.Owner,
            familyName = "张家",
        )
        var viewport by mutableStateOf(LeziDeviceViewports.styled().first())
        compose.setContent {
            val current = viewport
            CompositionLocalProvider(LocalDensity provides current.density) {
                LeziTheme(visualStyle = current.style, elderMode = current.elder) {
                    Box(
                        Modifier
                            .requiredSize(current.width, current.height)
                            .clipToBounds()
                            .testTag("elder_family_page"),
                    ) {
                        PageScaffoldBackground {
                            AccountPageContent(
                                overview = AccountOverviewUi(
                                    hydrated = true,
                                    identity = identity,
                                    status = SyncStatus.Idle,
                                    hasLocalBaby = true,
                                    current = baby,
                                    babies = listOf(baby),
                                ),
                                members = MembersDevicesUi(
                                    identity = identity,
                                    membersLoaded = true,
                                ),
                                primary = familyPrimarySurface(
                                    isJoined = true,
                                    role = FamilyRole.Owner,
                                    endpointConfigured = true,
                                ),
                                endpointConfigured = true,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(LeziSpacing.Page)
                                    .testTag("elder_family_content"),
                            )
                        }
                    }
                }
            }
        }

        LeziDeviceViewports.styled().forEach { next ->
            compose.runOnIdle { viewport = next }
            compose.waitForIdle()
            val page = compose.onNodeWithTag("elder_family_page").getUnclippedBoundsInRoot()
            val content = compose.onNodeWithTag("elder_family_content").getUnclippedBoundsInRoot()
            assertTrue(
                "${next.name} account content overflow: $content vs $page",
                content.left.value >= page.left.value - 0.5f &&
                    content.top.value >= page.top.value - 0.5f &&
                    content.right.value <= page.right.value + 0.5f,
            )
            assertTrue("${next.name} account content collapsed: $content", content.height >= 48.dp)
            compose.onNodeWithText("账户").assertExists()
            compose.onNodeWithText("宝宝").performScrollTo().assertExists()
        }
    }
}
