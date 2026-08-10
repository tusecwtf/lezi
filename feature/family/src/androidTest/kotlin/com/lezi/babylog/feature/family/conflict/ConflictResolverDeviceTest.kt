package com.lezi.babylog.feature.family.conflict

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
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
    fun sharedResolverShowsFullSnapshotAndRequiresExplicitChoice() {
        var draft by mutableStateOf(openDraft(fetchedOnline = true))
        var submitted = false
        composeRule.setContent {
            LeziTheme(visualStyle = "warm") {
                ConflictResolverContent(
                    state = ConflictResolverUiState(draft = draft),
                    onDraftChanged = { draft = it },
                    onSubmit = { submitted = true },
                    onRetry = {},
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
        composeRule.onNodeWithTag("conflict_submit").assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertThat(draft.selectedChoiceIds["/note"]).isEqualTo(BRANCH_CHOICE_ID)
            assertThat(submitted).isTrue()
        }
    }

    @Test
    fun unauthorizedAndOfflineAudienceCanReviewButCannotSubmit() {
        val cases = listOf(
            openDraft(membershipId = "member-reader", fetchedOnline = true) to
                "仅事实作者或家庭管理员可以解决",
            openDraft(fetchedOnline = false) to "离线快照只读，请联网后重新打开",
        )
        cases.forEach { (draft, reason) ->
            composeRule.setContent {
                LeziTheme(visualStyle = "warm") {
                    ConflictResolverContent(
                        state = ConflictResolverUiState(draft = draft),
                        onDraftChanged = { error("read-only choice") },
                        onSubmit = { error("read-only submit") },
                        onRetry = {},
                    )
                }
            }
            composeRule.onNodeWithTag("conflict_version_0").assertExists()
            composeRule.onNodeWithText(reason).assertExists()
            composeRule.onNodeWithTag("conflict_submit").assertIsNotEnabled()
        }
    }

    @Test
    fun crossingExpiryRejectsStaleTapWithoutCrashing() {
        var clockNow = 999L
        var draft by mutableStateOf(
            openDraft(fetchedOnline = true, expiresAt = 1_000, clock = { clockNow }),
        )
        composeRule.setContent {
            LeziTheme(visualStyle = "warm") {
                ConflictResolverContent(
                    state = ConflictResolverUiState(draft = draft),
                    onDraftChanged = { draft = it },
                    onSubmit = { error("expired submit") },
                    onRetry = {},
                )
            }
        }
        composeRule.runOnIdle { clockNow = 1_000 }
        composeRule.onNodeWithTag("conflict_option_/note_$BRANCH_CHOICE_ID").performClick()
        composeRule.onNodeWithText("冲突快照已过期，请联网刷新").assertExists()
        composeRule.onNodeWithTag("conflict_submit").assertIsNotEnabled()
        composeRule.runOnIdle { assertThat(draft.selectedChoiceIds).isEmpty() }
    }

    private fun openDraft(
        membershipId: String = "member-self",
        fetchedOnline: Boolean,
        expiresAt: Long = 2_000_000,
        clock: () -> Long = { 1_000 },
    ) = ConflictResolverDraft.open(
        snapshot = resolverSnapshot().copy(expiresAt = expiresAt),
        audience = ConflictResolverAudience(membershipId, false),
        fetchedOnline = fetchedOnline,
        nowMillis = clock(),
        resolutionMutationId = "00000000-0000-0000-0000-000000000020",
        clock = clock,
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
