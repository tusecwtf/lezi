package com.lezi.babylog.feature.log.timeline

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.RootPublicationState
import com.lezi.babylog.core.model.SleepEndSource
import com.lezi.babylog.core.model.SleepIntervalProjection
import com.lezi.babylog.designsystem.LeziTheme
import com.lezi.babylog.domain.carelog.DuplicateGroupAction
import com.lezi.babylog.domain.carelog.SuspectedDuplicateGroup
import com.lezi.babylog.domain.timeline.TimelineMediaSnapshot
import com.lezi.babylog.domain.timeline.TimelineRecordRow
import com.lezi.babylog.domain.timeline.TimelineRowCapabilities
import com.lezi.babylog.domain.timeline.TimelineWakeObservation
import java.time.ZoneId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CausalProductSurfacesDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun sleepSheetRendersProvisionalOverlapProvenanceAndAclActions() {
        val selected = mutableListOf<String?>()
        val withdrawn = mutableListOf<String>()
        val row = timelineRow(
            sleepInterval = SleepIntervalProjection(
                sleepClientUuid = "sleep-1",
                startTimestamp = 1_000,
                endTimestamp = 3_000,
                endSource = SleepEndSource.PROVISIONAL,
                endObservationClientUuid = "wake-self",
                isProvisional = true,
                isOverlapPending = true,
            ),
            wakes = listOf(
                TimelineWakeObservation(
                    clientUuid = "wake-self",
                    wakeTimestamp = 3_000,
                    observerLabel = "本人",
                    observerMembershipId = "member-self",
                    note = "客厅醒来",
                    photoPaths = listOf("/tmp/wake.jpg"),
                    provisional = true,
                    effective = false,
                    canEdit = true,
                ),
                TimelineWakeObservation(
                    clientUuid = "wake-peer",
                    wakeTimestamp = 4_000,
                    observerLabel = "妈妈",
                    observerMembershipId = "member-peer",
                    note = null,
                    photoPaths = emptyList(),
                    provisional = false,
                    effective = false,
                    canEdit = false,
                ),
            ),
            canSelect = true,
        )
        composeRule.setContent {
            LeziTheme(visualStyle = "warm") {
                SleepObservationSheet(
                    row = row,
                    zone = ZoneId.of("Asia/Shanghai"),
                    onDismiss = {},
                    onAddWake = {},
                    onUpdate = { _, _, _ -> },
                    onWithdraw = { withdrawn += it.clientUuid },
                    onSelect = { selected += it?.clientUuid },
                )
            }
        }

        composeRule.onNodeWithTag("sleep_overlap_pending_message").assertExists()
        composeRule.onNodeWithTag("wake_observation_wake-self")
            .assertContentDescriptionContains("本人")
            .assertContentDescriptionContains("暂定采用")
            .assertContentDescriptionContains("1张照片")
        composeRule.onNodeWithTag("wake_edit_wake-self").assertExists()
        composeRule.onNodeWithTag("wake_withdraw_wake-self").performClick()
        composeRule.onNodeWithTag("wake_edit_wake-peer").assertDoesNotExist()
        composeRule.onNodeWithTag("wake_select_wake-peer").performClick()
        assertThat(composeRule.onNodeWithTag("sleep_observation_sheet").captureToImage().width)
            .isGreaterThan(0)
        composeRule.runOnIdle {
            assertThat(withdrawn).containsExactly("wake-self")
            assertThat(selected).containsExactly("wake-peer")
        }
    }

    @Test
    fun duplicateGroupDefaultsExpandedAndExposesAuthorAndOwnerActions() {
        val first = formulaRecord(1, "r1", "member-self", 1_000, 90, "本机")
        val second = formulaRecord(2, "r2", "member-peer", 1_100, 120, "家人")
        val group = SuspectedDuplicateGroup(
            groupId = "g1",
            babyId = 1,
            recordType = RecordType.FORMULA,
            memberClientUuids = listOf("r1", "r2"),
        )
        var expanded by mutableStateOf(true)
        val actions = mutableListOf<DuplicateGroupAction>()
        composeRule.setContent {
            LeziTheme(visualStyle = "warm") {
                DuplicateGroupCard(
                    group = group,
                    recordsByUuid = listOf(first, second).associateBy(Record::clientUuid),
                    recordRowsById = emptyMap(),
                    currentMembershipId = "member-self",
                    isOwner = true,
                    expanded = expanded,
                    onToggle = { expanded = !expanded },
                    onAction = { actions += it },
                )
            }
        }

        composeRule.onNodeWithTag("duplicate_group_g1")
            .assertContentDescriptionContains("2个来源")
            .assertContentDescriptionContains("已展开")
        composeRule.onNodeWithTag("duplicate_source_r1").assertExists()
        composeRule.onNodeWithText("声明我的记录与另一来源相同").performClick()
        composeRule.onAllNodesWithText("以 配方奶 展示", useUnmergedTree = true)[0]
            .performClick()
        composeRule.onNodeWithTag("duplicate_toggle_g1").performClick()
        composeRule.onNodeWithTag("duplicate_source_r1").assertDoesNotExist()
        composeRule.runOnIdle {
            assertThat(actions.filterIsInstance<DuplicateGroupAction.AuthorDeclare>()).hasSize(1)
            assertThat(actions.filterIsInstance<DuplicateGroupAction.OwnerResolve>()).hasSize(1)
        }
    }

    private fun timelineRow(
        sleepInterval: SleepIntervalProjection,
        wakes: List<TimelineWakeObservation>,
        canSelect: Boolean,
    ) = TimelineRecordRow(
        revision = 1,
        record = Record(
            id = 1,
            clientUuid = "sleep-1",
            babyId = 1,
            type = RecordType.SLEEP,
            timestamp = 1_000,
            payloadJson = """{"is_nap":false,"anomaly_flag":false}""",
            updatedAt = 1_000,
        ),
        publicationState = RootPublicationState.CURRENT_VERSION_PUBLISHED,
        media = TimelineMediaSnapshot.empty(1),
        uploaderLabel = "本人",
        capabilities = TimelineRowCapabilities(1, true, true, false, false),
        sleepInterval = sleepInterval,
        wakeObservations = wakes,
        canSelectEffectiveWakeObservation = canSelect,
    )

    private fun formulaRecord(
        id: Long,
        uuid: String,
        author: String,
        timestamp: Long,
        amount: Int,
        note: String,
    ) = Record(
        id = id,
        clientUuid = uuid,
        babyId = 1,
        type = RecordType.FORMULA,
        timestamp = timestamp,
        note = note,
        payloadJson = """{"amount_ml":$amount}""",
        updatedAt = timestamp,
        createdByMembershipId = author,
    )
}
