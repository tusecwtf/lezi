package com.lezi.babylog.feature.log.timeline

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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
import com.lezi.babylog.domain.carelog.ConflictResolverAudience
import com.lezi.babylog.domain.carelog.ConflictResolverDraft
import com.lezi.babylog.domain.carelog.DuplicateGroupAction
import com.lezi.babylog.domain.carelog.SuspectedDuplicateGroup
import com.lezi.babylog.domain.timeline.TimelineMediaSnapshot
import com.lezi.babylog.domain.timeline.TimelineRecordRow
import com.lezi.babylog.domain.timeline.TimelineRowCapabilities
import com.lezi.babylog.domain.timeline.TimelineWakeObservation
import com.lezi.babylog.sync.backend.CausalMediaItem
import com.lezi.babylog.sync.conflict.AutoMergedPath
import com.lezi.babylog.sync.conflict.ConflictCandidate
import com.lezi.babylog.sync.conflict.ConflictOutcome
import com.lezi.babylog.sync.conflict.ConflictRoot
import com.lezi.babylog.sync.conflict.ConflictRootType
import com.lezi.babylog.sync.conflict.ConflictSnapshot
import com.lezi.babylog.sync.conflict.ConflictSource
import com.lezi.babylog.sync.conflict.ConflictVersionSnapshot
import com.lezi.babylog.sync.conflict.ConflictingPath
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
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
    fun conflictResolverShowsFullSnapshotAndRequiresExplicitChoice() {
        val snapshot = resolverSnapshot()
        var draft by mutableStateOf(
            ConflictResolverDraft.open(
                snapshot = snapshot,
                audience = ConflictResolverAudience("member-self", false),
                fetchedOnline = true,
                nowMillis = 1_000,
                resolutionMutationId = "00000000-0000-0000-0000-000000000020",
            ),
        )
        var submitted = false
        composeRule.setContent {
            LeziTheme(visualStyle = "warm") {
                ConflictResolverSheet(
                    loading = false,
                    draft = draft,
                    error = null,
                    submitting = false,
                    onDismiss = {},
                    onDraftChanged = { draft = it },
                    onSubmit = { submitted = true },
                )
            }
        }

        composeRule.onNodeWithText("解决护理记录冲突").assertExists()
        composeRule.onNodeWithTag("conflict_version_0")
            .assertContentDescriptionContains("当前稳定版")
            .assertContentDescriptionContains("1张照片")
            .assertContentDescriptionContains("member-self")
        composeRule.onNodeWithTag("conflict_version_1")
            .assertContentDescriptionContains("候选分支 1")
            .assertContentDescriptionContains("member-other")
        composeRule.onNodeWithTag("conflict_auto_/timestamp").assertExists()
        composeRule.onNodeWithTag("conflict_path_/note").assertExists()
        composeRule.onNodeWithTag("conflict_path_/timestamp").assertDoesNotExist()
        composeRule.onNodeWithTag("conflict_submit").assertIsNotEnabled()
        composeRule.onNodeWithTag("conflict_option_/note_$BRANCH_CHOICE_ID").performClick()
        composeRule.onNodeWithTag("conflict_submit").assertIsEnabled()
        composeRule.onNodeWithTag("conflict_submit").performClick()
        assertThat(composeRule.onNodeWithTag("conflict_resolver_sheet").captureToImage().width)
            .isGreaterThan(0)
        composeRule.runOnIdle {
            assertThat(draft.selectedChoiceIds["/note"]).isEqualTo(BRANCH_CHOICE_ID)
            assertThat(submitted).isTrue()
        }
    }

    @Test
    fun offlineConflictSnapshotIsReadOnly() {
        val draft = ConflictResolverDraft.open(
            snapshot = resolverSnapshot(),
            audience = ConflictResolverAudience("member-self", false),
            fetchedOnline = false,
            nowMillis = 1_000,
            resolutionMutationId = "00000000-0000-0000-0000-000000000021",
        )
        composeRule.setContent {
            LeziTheme(visualStyle = "warm") {
                ConflictResolverSheet(
                    loading = false,
                    draft = draft,
                    error = null,
                    submitting = false,
                    onDismiss = {},
                    onDraftChanged = { error("read-only choice") },
                    onSubmit = { error("read-only submit") },
                )
            }
        }

        composeRule.onNodeWithTag("conflict_read_only").assertExists()
        composeRule.onNodeWithText("离线快照只读，请联网后重新打开").assertExists()
        composeRule.onNodeWithTag("conflict_submit").assertIsNotEnabled()
    }

    @Test
    fun conflictResolverCrossingExpiryRejectsStaleTapWithoutCrashing() {
        var clockNow = 999L
        var draft by mutableStateOf(
            ConflictResolverDraft.open(
                snapshot = resolverSnapshot().copy(expiresAt = 1_000),
                audience = ConflictResolverAudience("member-self", false),
                fetchedOnline = true,
                nowMillis = clockNow,
                resolutionMutationId = "00000000-0000-0000-0000-000000000022",
                clock = { clockNow },
            ),
        )
        composeRule.setContent {
            LeziTheme(visualStyle = "warm") {
                ConflictResolverSheet(
                    loading = false,
                    draft = draft,
                    error = null,
                    submitting = false,
                    onDismiss = {},
                    onDraftChanged = { draft = it },
                    onSubmit = { error("expired submit") },
                )
            }
        }

        composeRule.runOnIdle { clockNow = 1_000 }
        composeRule.onNodeWithTag("conflict_option_/note_$BRANCH_CHOICE_ID").performClick()

        composeRule.onNodeWithTag("conflict_read_only").assertExists()
        composeRule.onNodeWithText("冲突快照已过期，请联网刷新").assertExists()
        composeRule.onNodeWithTag("conflict_submit").assertIsNotEnabled()
        composeRule.runOnIdle {
            assertThat(draft.selectedChoiceIds).isEmpty()
            assertThat(draft.submitted).isFalse()
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

    private fun resolverSnapshot(): ConflictSnapshot {
        val media = CausalMediaItem(
            mediaUuid = "00000000-0000-0000-0000-000000000040",
            role = "log",
            mime = "image/jpeg",
            sha256 = "a".repeat(64),
            byteSize = 12,
        )
        fun root(note: String, author: String) = ConflictRoot.Record(
            babyClientUuid = "00000000-0000-0000-0000-000000000002",
            type = "formula",
            customItemClientUuid = null,
            timestamp = 100,
            endTimestamp = null,
            note = note,
            payload = Json.parseToJsonElement("""{"amount_ml":60}""").jsonObject,
            schemaVersion = 2,
            effectiveWakeObservationClientUuid = null,
            createdByMembershipId = author,
            updatedAt = 100,
            canonical = Json.parseToJsonElement(
                """{"timestamp":100,"note":"$note","created_by_membership_id":"$author","updated_at":100}""",
            ).jsonObject,
        )
        fun source(version: String, actor: String, device: String) = ConflictSource(
            versionId = version,
            mutationId = "00000000-0000-0000-0000-000000000003",
            actorId = actor,
            deviceId = device,
            receivedAt = 100,
        )
        val stableSource = source("stable-1", "member-self", "device-self")
        val branchSource = source("branch-1", "member-other", "device-other")
        return ConflictSnapshot(
            conflictId = "00000000-0000-0000-0000-000000000010",
            entityType = ConflictRootType.Record,
            clientUuid = "00000000-0000-0000-0000-000000000001",
            snapshotToken = "a".repeat(43),
            expiresAt = 2_000_000,
            stable = ConflictVersionSnapshot(
                "stable-1",
                null,
                root("stable", "member-self"),
                listOf(media),
                false,
                stableSource.mutationId,
                stableSource.actorId,
                stableSource.deviceId,
                stableSource.receivedAt,
            ),
            branches = listOf(
                ConflictVersionSnapshot(
                    "branch-1",
                    "stable-1",
                    root("branch", "member-other"),
                    listOf(media),
                    false,
                    branchSource.mutationId,
                    branchSource.actorId,
                    branchSource.deviceId,
                    branchSource.receivedAt,
                ),
            ),
            conflicting = listOf(
                ConflictingPath(
                    "/note",
                    listOf(
                        ConflictCandidate(
                            STABLE_CHOICE_ID,
                            ConflictOutcome.Set(JsonPrimitive("stable")),
                            listOf(stableSource),
                        ),
                        ConflictCandidate(
                            BRANCH_CHOICE_ID,
                            ConflictOutcome.Set(JsonPrimitive("branch")),
                            listOf(branchSource),
                        ),
                    ),
                ),
            ),
            autoMerged = listOf(
                AutoMergedPath(
                    "/timestamp",
                    ConflictOutcome.Set(JsonPrimitive(100)),
                    listOf(stableSource, branchSource),
                ),
            ),
            pageIndex = 0,
            continuation = null,
            complete = true,
        )
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

private val STABLE_CHOICE_ID = "b".repeat(43)
private val BRANCH_CHOICE_ID = "c".repeat(43)
