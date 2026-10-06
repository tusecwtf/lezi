package com.lezi.babylog.feature.family.conflict

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.designsystem.LeziTheme
import com.lezi.babylog.domain.carelog.ConflictResolverAudience
import com.lezi.babylog.domain.carelog.ConflictResolverDraft
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConflictResolverDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun sharedResolverShowsProductCardsAndAdoptsSelectedVersion() {
        var draft by mutableStateOf(openDraft(fetchedOnline = true, isOwner = true))
        var submitted = false
        composeRule.setContent {
            LeziTheme(visualStyle = "warm") {
                ConflictResolverContent(
                    state = ConflictResolverUiState(
                        draft = draft,
                        phase = ConflictResolverPhase.Complete,
                    ),
                    onSelectVersion = { versionId ->
                        draft = draft.chooseVersion(versionId)
                    },
                    onAdopt = { submitted = true },
                    onAdoptAndEdit = {},
                    onRequestDelete = {},
                    onConfirmDelete = {},
                    onCancelDelete = {},
                    onRefresh = {},
                )
            }
        }

        composeRule.onNodeWithText("解决护理记录冲突").assertExists()
        composeRule.onAllNodesWithText("不同：备注").assertCountEquals(2)
        composeRule.onNodeWithTag("conflict_version_0")
            .assertContentDescriptionContains("当前家里在用的", substring = true)
            .assertContentDescriptionContains("1张照片", substring = true)
            .assertContentDescriptionContains("不同：备注", substring = true)
        composeRule.onNodeWithTag("conflict_version_1")
            .assertContentDescriptionContains("另一版修改", substring = true)
        composeRule.onNodeWithTag("conflict_path_/note").assertDoesNotExist()
        composeRule.onNodeWithTag("conflict_submit").assertIsEnabled()
        composeRule.onNodeWithTag("conflict_submit_edit").assertIsEnabled()

        composeRule.onNodeWithTag("conflict_version_1").performClick()
        composeRule.onNodeWithTag("conflict_submit").assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertThat(draft.selectedVersionId).isEqualTo("branch-1")
            assertThat(draft.selectedChoiceIds["/note"]).isEqualTo(BRANCH_CHOICE_ID)
            assertThat(submitted).isTrue()
        }
    }

    @Test
    fun unauthorizedAndOfflineAudienceCanReviewButCannotSubmit() {
        val cases = listOf(
            openDraft(membershipId = "member-reader", fetchedOnline = true) to
                "分叉后只有家庭管理员能采用新稳定",
            openDraft(fetchedOnline = false) to "离线快照只读，请联网后重新打开",
        )
        var currentDraft by mutableStateOf(cases.first().first)
        composeRule.setContent {
            LeziTheme(visualStyle = "warm") {
                ConflictResolverContent(
                    state = ConflictResolverUiState(
                        draft = currentDraft,
                        phase = if (currentDraft.model.availability ==
                            com.lezi.babylog.domain.carelog.ConflictResolverAvailability.Offline
                        ) {
                            ConflictResolverPhase.Offline(
                                requireNotNull(currentDraft.model.readOnlyReason),
                            )
                        } else {
                            ConflictResolverPhase.Complete
                        },
                    ),
                    onSelectVersion = { error("read-only choice") },
                    onAdopt = { error("read-only submit") },
                    onAdoptAndEdit = { error("read-only submit") },
                    onRequestDelete = { error("read-only submit") },
                    onConfirmDelete = { error("read-only submit") },
                    onCancelDelete = {},
                    onRefresh = {},
                )
            }
        }
        cases.forEach { (caseDraft, reason) ->
            composeRule.runOnIdle { currentDraft = caseDraft }
            composeRule.onNodeWithTag("conflict_version_0").assertExists()
            composeRule.onNodeWithText(reason).assertExists()
            composeRule.onNodeWithTag("conflict_submit").assertIsNotEnabled()
        }
    }

    @Test
    fun freshnessStatesKeepOldEvidenceVisibleAndRefreshable() {
        val draft = openDraft(fetchedOnline = true)
        var state by mutableStateOf(
            ConflictResolverUiState(
                conflictId = draft.model.conflictId,
                draft = draft,
                phase = ConflictResolverPhase.Offline("当前离线；旧快照仍保留为只读证据"),
            ),
        )
        var refreshed = false
        composeRule.setContent {
            LeziTheme(visualStyle = "warm") {
                ConflictResolverContent(
                    state = state,
                    onSelectVersion = { error("read-only choice") },
                    onAdopt = { error("read-only submit") },
                    onAdoptAndEdit = { error("read-only submit") },
                    onRequestDelete = { error("read-only submit") },
                    onConfirmDelete = { error("read-only submit") },
                    onCancelDelete = {},
                    onRefresh = { refreshed = true },
                )
            }
        }

        composeRule.onNodeWithTag("conflict_offline").assertExists()
        composeRule.onNodeWithTag("conflict_version_0").assertExists()
        composeRule.onNodeWithTag("conflict_submit").assertIsNotEnabled()
        composeRule.onNodeWithTag("conflict_refresh").performClick()
        composeRule.runOnIdle {
            assertThat(refreshed).isTrue()
            state = state.copy(
                phase = ConflictResolverPhase.Refreshing("正在刷新完整冲突快照…"),
            )
        }
        composeRule.onNodeWithTag("conflict_refreshing").assertExists()
        composeRule.onNodeWithTag("conflict_version_0").assertExists()
        composeRule.onNodeWithTag("conflict_submit").assertIsNotEnabled()

        composeRule.runOnIdle {
            state = state.copy(
                phase = ConflictResolverPhase.Error(
                    "刷新失败；旧快照仍保留为只读证据",
                    ConflictResolverRetry.Refresh,
                ),
            )
        }
        composeRule.onNodeWithTag("conflict_error").assertExists()
        composeRule.onNodeWithTag("conflict_version_0").assertExists()
        composeRule.onNodeWithTag("conflict_refresh").assertExists()

        composeRule.runOnIdle {
            state = state.copy(
                phase = ConflictResolverPhase.Error(
                    "本次解决请求已被拒绝，请关闭后重新打开冲突",
                    ConflictResolverRetry.None,
                ),
            )
        }
        composeRule.onNodeWithTag("conflict_error").assertExists()
        composeRule.onNodeWithTag("conflict_version_0").assertExists()
        composeRule.onNodeWithTag("conflict_refresh").assertDoesNotExist()
        composeRule.onNodeWithTag("conflict_submit").assertIsNotEnabled()
    }

    @Test
    fun branchAuthorSeesWithdrawActionWithoutInternalIds() {
        val draft = openDraft(membershipId = "member-other", fetchedOnline = true)
        var withdrawn = false
        composeRule.setContent {
            LeziTheme(visualStyle = "warm") {
                ConflictResolverContent(
                    state = ConflictResolverUiState(
                        draft = draft,
                        phase = ConflictResolverPhase.Complete,
                    ),
                    onSelectVersion = {},
                    onAdopt = { error("branch author should not adopt") },
                    onAdoptAndEdit = { error("branch author should not adopt") },
                    onWithdraw = { withdrawn = true },
                    onRequestDelete = {},
                    onConfirmDelete = {},
                    onCancelDelete = {},
                    onRefresh = {},
                )
            }
        }

        composeRule.onNodeWithText("分叉后只有家庭管理员能采用新稳定").assertExists()
        composeRule.onNodeWithTag("conflict_submit").assertIsNotEnabled()
        composeRule.onNodeWithText("撤回我的修改").assertExists()
        composeRule.onNodeWithTag("conflict_withdraw").assertIsEnabled().performClick()
        composeRule.onNodeWithText("stable-1").assertDoesNotExist()
        composeRule.onNodeWithText("branch-1").assertDoesNotExist()
        composeRule.onNodeWithText("device-other").assertDoesNotExist()
        composeRule.onNodeWithText("member-other").assertDoesNotExist()
        composeRule.runOnIdle {
            assertThat(withdrawn).isTrue()
            assertThat(draft.canWithdraw).isTrue()
        }
    }

    @Test
    fun familyAdminCanWithdrawBranchesTheyDidNotAuthor() {
        val draft = openDraft(
            membershipId = "member-admin",
            fetchedOnline = true,
            isOwner = true,
        )
        var withdrawn = false
        composeRule.setContent {
            LeziTheme(visualStyle = "warm") {
                ConflictResolverContent(
                    state = ConflictResolverUiState(
                        draft = draft,
                        phase = ConflictResolverPhase.Complete,
                    ),
                    onSelectVersion = {},
                    onAdopt = {},
                    onAdoptAndEdit = {},
                    onWithdraw = { withdrawn = true },
                    onRequestDelete = {},
                    onConfirmDelete = {},
                    onCancelDelete = {},
                    onRefresh = {},
                )
            }
        }

        composeRule.onNodeWithText("撤回这些修改").assertExists()
        composeRule.onNodeWithText("撤回我的修改").assertDoesNotExist()
        composeRule.onNodeWithTag("conflict_withdraw").assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertThat(withdrawn).isTrue()
            assertThat(draft.canWithdraw).isTrue()
            assertThat(draft.model.isFamilyAdmin).isTrue()
        }
    }

    private fun openDraft(
        membershipId: String = "member-self",
        fetchedOnline: Boolean,
        isOwner: Boolean = false,
    ) = ConflictResolverDraft.open(
        snapshot = resolverSnapshot(),
        audience = ConflictResolverAudience(membershipId, isOwner),
        fetchedOnline = fetchedOnline,
        nowMillis = 1_000,
        resolutionMutationId = "00000000-0000-0000-0000-000000000020",
        clock = { 1_000 },
    )
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
    val stable = source("stable-1", "member-self", "device-self")
    val branch = source("branch-1", "member-other", "device-other")
    return ConflictSnapshot(
        conflictId = "00000000-0000-0000-0000-000000000010",
        entityType = ConflictRootType.Record,
        clientUuid = "00000000-0000-0000-0000-000000000001",
        snapshotToken = "a".repeat(43),
        expiresAt = 2_000_000,
        stable = ConflictVersionSnapshot(
            "stable-1", null, root("stable", "member-self"), listOf(media), false,
            stable.mutationId, stable.actorId, stable.deviceId, stable.receivedAt,
        ),
        branches = listOf(
            ConflictVersionSnapshot(
                "branch-1", "stable-1", root("branch", "member-other"), listOf(media), false,
                branch.mutationId, branch.actorId, branch.deviceId, branch.receivedAt,
            ),
        ),
        conflicting = listOf(
            ConflictingPath(
                "/note",
                listOf(
                    ConflictCandidate(STABLE_CHOICE_ID, ConflictOutcome.Set(JsonPrimitive("stable")), listOf(stable)),
                    ConflictCandidate(BRANCH_CHOICE_ID, ConflictOutcome.Set(JsonPrimitive("branch")), listOf(branch)),
                ),
            ),
        ),
        autoMerged = listOf(
            AutoMergedPath("/timestamp", ConflictOutcome.Set(JsonPrimitive(100)), listOf(stable, branch)),
        ),
        pageIndex = 0,
        continuation = null,
        complete = true,
    )
}

private val STABLE_CHOICE_ID = "b".repeat(43)
private val BRANCH_CHOICE_ID = "c".repeat(43)
