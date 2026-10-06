package com.lezi.babylog.feature.family.conflict

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.domain.carelog.ConflictResolverAudience
import com.lezi.babylog.domain.carelog.ConflictResolverAvailability
import com.lezi.babylog.domain.carelog.ConflictResolverDraft
import com.lezi.babylog.sync.backend.ConflictResolutionChoice
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
import org.junit.Test

class H42ConflictAcceptanceMatrixTest {
    @Test
    fun submitIsEnabledOnlyForCompleteOnlineAuthorizedChosenState() {
        val complete = openDraft()
        val selected = complete.choose("/note", BRANCH_CHOICE_ID)
        val expired = openDraft(nowMillis = 3_000)
        val forbidden = openDraft(membershipId = "member-reader")
        val offline = openDraft(fetchedOnline = false)

        val cases = listOf(
            "offline" to ConflictResolverUiState(
                draft = offline,
                phase = ConflictResolverPhase.Offline("离线快照只读，请联网后重新打开"),
            ),
            "stale" to ConflictResolverUiState(
                draft = expired,
                phase = ConflictResolverPhase.Stale("冲突快照已过期，请联网刷新"),
            ),
            "unauthorized" to ConflictResolverUiState(
                draft = forbidden,
                phase = ConflictResolverPhase.Complete,
            ),
            "terminal-error" to ConflictResolverUiState(
                draft = selected,
                phase = ConflictResolverPhase.Error(
                    "本次解决请求已被拒绝，请关闭后重新打开冲突",
                    ConflictResolverRetry.None,
                ),
            ),
        )

        cases.forEach { (_, state) ->
            assertThat(state.canSubmit).isFalse()
        }
        assertThat(complete.canSubmit).isTrue()
        assertThat(
            ConflictResolverUiState(
                draft = complete,
                phase = ConflictResolverPhase.Complete,
            ).canSubmit,
        ).isTrue()
        assertThat(
            ConflictResolverUiState(
                draft = selected,
                phase = ConflictResolverPhase.Complete,
            ).canSubmit,
        ).isTrue()
    }

    @Test
    fun incompletePagedSnapshotCannotEnterResolver() {
        val incomplete = snapshot().copy(
            complete = false,
            continuation = "c".repeat(43),
        )

        val error = runCatching {
            ConflictResolverDraft.open(
                snapshot = incomplete,
                audience = ConflictResolverAudience("member-self", isOwner = true),
                fetchedOnline = true,
                nowMillis = 1_000,
                resolutionMutationId = RESOLUTION_MUTATION_ID,
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error).hasMessageThat().contains("完整 ConflictSnapshot")
    }

    @Test
    fun selectedChoiceAndMutationIdentitySurviveResolverRecreation() {
        val firstDraft = openDraft().choose("/note", BRANCH_CHOICE_ID)
        val savedStateHandle = SavedStateHandle()
        ConflictResolverSessionState(savedStateHandle).persist(firstDraft.savedState())

        val restoredState = ConflictResolverSessionState(savedStateHandle)
            .restore(CONFLICT_ID)
        val recreated = ConflictResolverDraft.open(
            snapshot = snapshot(),
            audience = ConflictResolverAudience("member-self", isOwner = true),
            fetchedOnline = true,
            nowMillis = 1_000,
            restored = restoredState,
            resolutionMutationId = "00000000-0000-0000-0000-000000000099",
        )

        assertThat(recreated.selectedChoiceIds).containsExactly("/note", BRANCH_CHOICE_ID)
        assertThat(recreated.resolutionMutationId).isEqualTo(RESOLUTION_MUTATION_ID)
        assertThat(recreated.model.availability)
            .isEqualTo(ConflictResolverAvailability.Current)
        assertThat(recreated.canSubmit).isTrue()
        assertThat(recreated.freeze().command().choices).containsExactly(
            ConflictResolutionChoice("/note", BRANCH_CHOICE_ID),
        )
    }

    private fun openDraft(
        membershipId: String = "member-self",
        fetchedOnline: Boolean = true,
        nowMillis: Long = 1_000,
    ): ConflictResolverDraft = ConflictResolverDraft.open(
        snapshot = snapshot(),
        audience = ConflictResolverAudience(
            membershipId,
            isOwner = membershipId == "member-self",
        ),
        fetchedOnline = fetchedOnline,
        nowMillis = nowMillis,
        resolutionMutationId = RESOLUTION_MUTATION_ID,
        clock = { nowMillis },
    )
}

private fun snapshot(): ConflictSnapshot {
    fun root(note: String, author: String) = ConflictRoot.Record(
        babyClientUuid = BABY_ID,
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

    fun source(versionId: String, actorId: String, deviceId: String) = ConflictSource(
        versionId = versionId,
        mutationId = MUTATION_ID,
        actorId = actorId,
        deviceId = deviceId,
        receivedAt = 100,
    )

    val stableSource = source("stable-1", "member-self", "device-self")
    val branchSource = source("branch-1", "member-other", "device-other")
    return ConflictSnapshot(
        conflictId = CONFLICT_ID,
        entityType = ConflictRootType.Record,
        clientUuid = RECORD_ID,
        snapshotToken = "a".repeat(43),
        expiresAt = 2_000,
        stable = ConflictVersionSnapshot(
            versionId = "stable-1",
            baseVersion = null,
            root = root("stable", "member-self"),
            media = emptyList(),
            deleted = false,
            mutationId = stableSource.mutationId,
            actorId = stableSource.actorId,
            deviceId = stableSource.deviceId,
            receivedAt = stableSource.receivedAt,
        ),
        branches = listOf(
            ConflictVersionSnapshot(
                versionId = "branch-1",
                baseVersion = "stable-1",
                root = root("branch", "member-other"),
                media = emptyList(),
                deleted = false,
                mutationId = branchSource.mutationId,
                actorId = branchSource.actorId,
                deviceId = branchSource.deviceId,
                receivedAt = branchSource.receivedAt,
            ),
        ),
        conflicting = listOf(
            ConflictingPath(
                path = "/note",
                candidates = listOf(
                    ConflictCandidate(
                        choiceId = STABLE_CHOICE_ID,
                        outcome = ConflictOutcome.Set(JsonPrimitive("stable")),
                        sources = listOf(stableSource),
                    ),
                    ConflictCandidate(
                        choiceId = BRANCH_CHOICE_ID,
                        outcome = ConflictOutcome.Set(JsonPrimitive("branch")),
                        sources = listOf(branchSource),
                    ),
                ),
            ),
        ),
        autoMerged = emptyList(),
        pageIndex = 0,
        continuation = null,
        complete = true,
    )
}

private const val BABY_ID = "00000000-0000-0000-0000-000000000002"
private const val RECORD_ID = "00000000-0000-0000-0000-000000000001"
private const val CONFLICT_ID = "00000000-0000-0000-0000-000000000010"
private const val MUTATION_ID = "00000000-0000-0000-0000-000000000003"
private const val RESOLUTION_MUTATION_ID = "00000000-0000-0000-0000-000000000020"
private val STABLE_CHOICE_ID = "b".repeat(43)
private val BRANCH_CHOICE_ID = "c".repeat(43)
