package com.lezi.babylog.feature.family.conflict

import androidx.lifecycle.SavedStateHandle
import com.lezi.babylog.domain.carelog.ConflictResolveOutcome
import com.lezi.babylog.domain.carelog.ConflictResolverLoad
import com.lezi.babylog.sync.conflict.ConflictCandidate
import com.lezi.babylog.sync.conflict.ConflictOutcome
import com.lezi.babylog.sync.conflict.ConflictRoot
import com.lezi.babylog.sync.conflict.ConflictRootType
import com.lezi.babylog.sync.conflict.ConflictSnapshot
import com.lezi.babylog.sync.conflict.ConflictSource
import com.lezi.babylog.sync.conflict.ConflictVersionSnapshot
import com.lezi.babylog.sync.conflict.ConflictingPath
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.SyncSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflictResolverHostRaceTest {
    @Test
    fun lateLoadFromAInstanceCannotOverwriteB() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val loadA = CompletableDeferred<ConflictResolverLoad?>()
            val loadB = CompletableDeferred<ConflictResolverLoad?>()
            val host = host(
                load = { conflictId, _ ->
                    withContext(NonCancellable) {
                        if (conflictId == CONFLICT_A) loadA.await() else loadB.await()
                    }
                },
            )

            host.open(CONFLICT_A)
            runCurrent()
            host.open(CONFLICT_B)
            runCurrent()
            loadA.complete(null)
            runCurrent()

            assertEquals(CONFLICT_B, host.state.value.conflictId)
            assertTrue(host.state.value.loading)
            assertNull(host.state.value.error)

            loadB.complete(null)
            advanceUntilIdle()
            assertEquals(CONFLICT_B, host.state.value.conflictId)
            assertNotNull(host.state.value.error)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun lateSubmitCannotReopenDismissedResolver() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val outcome = CompletableDeferred<ConflictResolveOutcome>()
            val host = host(
                load = { _, _ -> ConflictResolverLoad(snapshot(), fetchedOnline = true) },
                resolve = { _, _ -> withContext(NonCancellable) { outcome.await() } },
            )
            host.open(CONFLICT_A)
            advanceUntilIdle()
            val selected = host.state.value.draft!!.choose("/note", BRANCH_CHOICE_ID)
            host.rememberDraft(selected)
            host.submit()
            runCurrent()
            assertTrue(host.state.value.submitting)

            host.dismiss()
            outcome.complete(ConflictResolveOutcome.Accepted("v-resolved"))
            advanceUntilIdle()

            assertNull(host.state.value.conflictId)
            assertNull(host.state.value.draft)
            assertFalse(host.state.value.submitting)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun host(
        load: suspend (String, Boolean) -> ConflictResolverLoad?,
        resolve: suspend (String, com.lezi.babylog.sync.backend.ConflictResolveRequest) ->
            ConflictResolveOutcome = { _, _ -> error("unexpected submit") },
    ) = ConflictResolverHost(
        loadConflict = load,
        resolveConflict = resolve,
        sessions = flowOf(
            SyncSession(
                role = FamilyRole.Member,
                membershipId = "member-author",
            ),
        ),
        nowMillis = { 1_000 },
        savedStateHandle = SavedStateHandle(),
    )
}

private fun snapshot(): ConflictSnapshot {
    val root = ConflictRoot.Record(
        babyClientUuid = "00000000-0000-0000-0000-000000000003",
        type = "formula",
        customItemClientUuid = null,
        timestamp = 100,
        endTimestamp = null,
        note = "stable",
        payload = Json.parseToJsonElement("""{"amount_ml":60}""").jsonObject,
        schemaVersion = 2,
        effectiveWakeObservationClientUuid = null,
        createdByMembershipId = "member-author",
        updatedAt = 100,
        canonical = Json.parseToJsonElement(
            """{"timestamp":100,"note":"stable","created_by_membership_id":"member-author","updated_at":100}""",
        ).jsonObject,
    )
    val stable = ConflictSource(
        "v-stable",
        "00000000-0000-0000-0000-000000000004",
        "member-author",
        "device-a",
        100,
    )
    val branch = ConflictSource(
        "v-branch",
        "00000000-0000-0000-0000-000000000005",
        "member-other",
        "device-b",
        110,
    )
    return ConflictSnapshot(
        conflictId = CONFLICT_A,
        entityType = ConflictRootType.Record,
        clientUuid = "00000000-0000-0000-0000-000000000006",
        snapshotToken = "a".repeat(43),
        expiresAt = 2_000_000,
        stable = ConflictVersionSnapshot(
            "v-stable", null, root, emptyList(), false,
            stable.mutationId, stable.actorId, stable.deviceId, stable.receivedAt,
        ),
        branches = listOf(
            ConflictVersionSnapshot(
                "v-branch", "v-stable", root.copy(note = "branch"), emptyList(), false,
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
        autoMerged = emptyList(),
        pageIndex = 0,
        continuation = null,
        complete = true,
    )
}

private const val CONFLICT_A = "00000000-0000-0000-0000-000000000001"
private const val CONFLICT_B = "00000000-0000-0000-0000-000000000002"
private val STABLE_CHOICE_ID = "b".repeat(43)
private val BRANCH_CHOICE_ID = "c".repeat(43)
