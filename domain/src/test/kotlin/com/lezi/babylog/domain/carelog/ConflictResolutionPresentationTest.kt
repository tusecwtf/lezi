package com.lezi.babylog.domain.carelog

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.causal.ConflictSnapshotCacheEntity
import com.lezi.babylog.core.database.causal.ConflictSummaryEntity
import com.lezi.babylog.domain.RecordingTransactionRunner
import com.lezi.babylog.sync.NoOpSyncPort
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.SyncTrigger
import com.lezi.babylog.sync.backend.ConflictResolveRequest
import com.lezi.babylog.sync.backend.ConflictResolveResult
import com.lezi.babylog.sync.backend.ConflictWithdrawRequest
import com.lezi.babylog.sync.backend.ConflictWithdrawResult
import com.lezi.babylog.sync.conflict.ConflictCandidate
import com.lezi.babylog.sync.conflict.ConflictOutcome
import com.lezi.babylog.sync.conflict.ConflictRoot
import com.lezi.babylog.sync.conflict.ConflictRootType
import com.lezi.babylog.sync.conflict.ConflictSnapshot
import com.lezi.babylog.sync.conflict.ConflictSnapshotCodec
import com.lezi.babylog.sync.conflict.ConflictSource
import com.lezi.babylog.sync.conflict.ConflictVersionSnapshot
import com.lezi.babylog.sync.conflict.ConflictingPath
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class ConflictResolutionPresentationTest {
    @Test
    fun loadUsesFreshAuthenticatedSnapshotOrReadOnlyCompleteCache() = runTest {
        val snapshot = recordConflictSnapshot()
        val summaries = FakeConflictSummaryDao()
        val cache = FakeConflictSnapshotCacheDao()
        seedCompleteSnapshot(summaries, cache, snapshot)
        val online = coordinator(
            summaries = summaries,
            cache = cache,
            sync = object : SyncPort by NoOpSyncPort() {
                override suspend fun fetchConflictSnapshot(conflictId: String) = snapshot
            },
        )

        val fresh = online.loadDetail(snapshot.conflictId)
        assertThat(fresh!!.snapshot).isEqualTo(snapshot)
        assertThat(fresh.fetchedOnline).isTrue()

        val offline = coordinator(
            summaries = summaries,
            cache = cache,
            sync = object : SyncPort by NoOpSyncPort() {
                override suspend fun fetchConflictSnapshot(conflictId: String): ConflictSnapshot =
                    throw IOException("offline")
            },
        ).loadDetail(snapshot.conflictId)
        assertThat(offline!!.snapshot).isEqualTo(snapshot)
        assertThat(offline.fetchedOnline).isFalse()
    }

    @Test
    fun loadRejectsCrossConflictResponseBeforeProjection() = runTest {
        val summaries = FakeConflictSummaryDao()
        val cache = FakeConflictSnapshotCacheDao()
        val foreign = recordConflictSnapshot().copy(conflictId = FOREIGN_CONFLICT_UUID)
        val loaded = coordinator(
            summaries = summaries,
            cache = cache,
            sync = object : SyncPort by NoOpSyncPort() {
                override suspend fun fetchConflictSnapshot(conflictId: String) = foreign
            },
        ).loadDetail(TEST_CONFLICT_UUID)

        assertThat(loaded).isNull()
        assertThat(cache.get(TEST_CONFLICT_UUID)).isNull()
        assertThat(cache.get(FOREIGN_CONFLICT_UUID)).isNull()
    }

    @Test
    fun incompleteNetworkPageIsNeverExposedPastTheCompleteSnapshotSeam() = runTest {
        val summaries = FakeConflictSummaryDao()
        val cache = FakeConflictSnapshotCacheDao()
        val partial = recordConflictSnapshot().copy(complete = false, continuation = "next")
        val loaded = coordinator(
            summaries = summaries,
            cache = cache,
            sync = object : SyncPort by NoOpSyncPort() {
                override suspend fun fetchConflictSnapshot(conflictId: String) = partial
            },
        ).loadDetail(TEST_CONFLICT_UUID)

        assertThat(loaded).isNull()
        assertThat(cache.get(TEST_CONFLICT_UUID)).isNull()
    }

    @Test
    fun lostResponseRetryReusesMutationAndChoicesThenDefersProjectionToPull() = runTest {
        val snapshot = recordConflictSnapshot()
        val summaries = FakeConflictSummaryDao()
        val cache = FakeConflictSnapshotCacheDao()
        seedCompleteSnapshot(summaries, cache, snapshot)
        val calls = mutableListOf<ConflictResolveRequest>()
        val triggers = mutableListOf<SyncTrigger>()
        val sync = object : SyncPort by NoOpSyncPort() {
            override suspend fun fetchConflictSnapshot(conflictId: String) = snapshot

            override suspend fun resolveConflict(
                conflictId: String,
                request: ConflictResolveRequest,
            ): ConflictResolveResult {
                calls += request
                if (calls.size == 1) throw IOException("response lost")
                return ConflictResolveResult.Accepted(
                    stableVersionId = "v-resolved",
                    resolutionMutationId = request.resolutionMutationId,
                    stableRootJson = snapshot.stable.root.canonical.toString()
                        .replace("stable", "chosen"),
                    stableMedia = emptyList(),
                    replay = true,
                )
            }

            override fun requestSync(trigger: SyncTrigger) {
                triggers += trigger
            }

            override fun requestAuthoritativeForegroundSync() {
                triggers += SyncTrigger.Foreground
            }
        }
        val coordinator = coordinator(summaries, cache, sync)
        val loaded = requireNotNull(coordinator.loadDetail(TEST_CONFLICT_UUID))
        val command = ConflictResolverDraft.open(
            snapshot = loaded.snapshot,
            audience = ConflictResolverAudience("member-author", true),
            fetchedOnline = loaded.fetchedOnline,
            nowMillis = 1_000,
            resolutionMutationId = RESOLUTION_MUTATION_UUID,
        ).choose("/note", BRANCH_CHOICE_ID).freeze().command()

        val lost = coordinator.resolve(TEST_CONFLICT_UUID, command)
        assertThat(lost).isInstanceOf(ConflictResolveOutcome.TransportFailure::class.java)
        assertThat(cache.get(TEST_CONFLICT_UUID)).isNotNull()

        val accepted = coordinator.resolve(TEST_CONFLICT_UUID, command)
        assertThat(accepted).isInstanceOf(ConflictResolveOutcome.Accepted::class.java)
        assertThat(calls.map { it.resolutionMutationId })
            .containsExactly(RESOLUTION_MUTATION_UUID, RESOLUTION_MUTATION_UUID)
        assertThat(calls[0].choices).isEqualTo(calls[1].choices)
        assertThat(cache.get(TEST_CONFLICT_UUID)).isNotNull()
        assertThat(summaries.get(TEST_CONFLICT_UUID)).isNotNull()
        assertThat(triggers).containsExactly(SyncTrigger.Foreground)
    }

    @Test
    fun staleAndForbiddenTerminalsKeepSnapshotAndReturnTypedOutcomes() = runTest {
        val cases = listOf(
            "snapshot_stale" to ConflictResolveOutcome.RefreshRequired::class.java,
            "snapshot_expired" to ConflictResolveOutcome.RefreshRequired::class.java,
            "invalid_snapshot_token" to ConflictResolveOutcome.RefreshRequired::class.java,
            "cas_mismatch" to ConflictResolveOutcome.RefreshRequired::class.java,
            "forbidden" to ConflictResolveOutcome.Forbidden::class.java,
            "invalid_choice" to ConflictResolveOutcome.Rejected::class.java,
        )
        cases.forEach { (code, expectedType) ->
            val snapshot = recordConflictSnapshot()
            val summaries = FakeConflictSummaryDao()
            val cache = FakeConflictSnapshotCacheDao()
            seedCompleteSnapshot(summaries, cache, snapshot)
            val sync = object : SyncPort by NoOpSyncPort() {
                override suspend fun fetchConflictSnapshot(conflictId: String) = snapshot
                override suspend fun resolveConflict(
                    conflictId: String,
                    request: ConflictResolveRequest,
                ) = ConflictResolveResult.Rejected(
                    code = code,
                    resolutionMutationId = request.resolutionMutationId,
                    retryable = false,
                )
            }
            val coordinator = coordinator(summaries, cache, sync)
            val loaded = requireNotNull(coordinator.loadDetail(TEST_CONFLICT_UUID))
            val command = ConflictResolverDraft.open(
                loaded.snapshot,
                ConflictResolverAudience("member-author", true),
                loaded.fetchedOnline,
                nowMillis = 1_000,
                resolutionMutationId = RESOLUTION_MUTATION_UUID,
            ).choose("/note", STABLE_CHOICE_ID).freeze().command()

            assertThat(coordinator.resolve(TEST_CONFLICT_UUID, command))
                .isInstanceOf(expectedType)
            assertThat(cache.get(TEST_CONFLICT_UUID)).isNotNull()
            assertThat(summaries.get(TEST_CONFLICT_UUID)).isNotNull()
        }
    }

    @Test
    fun malformedAcceptedProjectionFailsClosedAndKeepsH05Receipt() = runTest {
        val snapshot = recordConflictSnapshot()
        val summaries = FakeConflictSummaryDao()
        val cache = FakeConflictSnapshotCacheDao()
        seedCompleteSnapshot(summaries, cache, snapshot)
        val triggers = mutableListOf<SyncTrigger>()
        val sync = object : SyncPort by NoOpSyncPort() {
            override suspend fun fetchConflictSnapshot(conflictId: String) = snapshot
            override suspend fun resolveConflict(
                conflictId: String,
                request: ConflictResolveRequest,
            ) = ConflictResolveResult.Accepted(
                stableVersionId = "v-resolved",
                resolutionMutationId = request.resolutionMutationId,
                stableRootJson = "{}",
                stableMedia = emptyList(),
                replay = false,
            )

            override fun requestSync(trigger: SyncTrigger) {
                triggers += trigger
            }

            override fun requestAuthoritativeForegroundSync() {
                triggers += SyncTrigger.Foreground
            }
        }
        val coordinator = coordinator(summaries, cache, sync)
        coordinator.loadDetail(TEST_CONFLICT_UUID)
        val request = ConflictResolveRequest(
            snapshotToken = snapshot.snapshotToken,
            resolutionMutationId = RESOLUTION_MUTATION_UUID,
            choices = listOf(
                com.lezi.babylog.sync.backend.ConflictResolutionChoice(
                    "/note",
                    STABLE_CHOICE_ID,
                ),
            ),
        )

        val outcome = coordinator.resolve(TEST_CONFLICT_UUID, request)

        assertThat(outcome).isInstanceOf(ConflictResolveOutcome.Rejected::class.java)
        assertThat((outcome as ConflictResolveOutcome.Rejected).code)
            .isEqualTo("transport_mismatch")
        assertThat(cache.get(TEST_CONFLICT_UUID)).isNotNull()
        assertThat(summaries.get(TEST_CONFLICT_UUID)).isNotNull()
        assertThat(triggers).isEmpty()
    }

    @Test
    fun acceptedTombstoneSnapshotStaysCachedUntilPullCarriesDeletedState() = runTest {
        val base = recordConflictSnapshot()
        val snapshot = base.copy(stable = base.stable.copy(deleted = true))
        val summaries = FakeConflictSummaryDao()
        val cache = FakeConflictSnapshotCacheDao()
        seedCompleteSnapshot(summaries, cache, snapshot)
        val triggers = mutableListOf<SyncTrigger>()
        val sync = object : SyncPort by NoOpSyncPort() {
            override suspend fun fetchConflictSnapshot(conflictId: String) = snapshot
            override suspend fun resolveConflict(
                conflictId: String,
                request: ConflictResolveRequest,
            ) = ConflictResolveResult.Accepted(
                stableVersionId = "v-resolved",
                resolutionMutationId = request.resolutionMutationId,
                stableRootJson = snapshot.stable.root.canonical.toString(),
                stableMedia = snapshot.stable.media,
                replay = false,
            )

            override fun requestSync(trigger: SyncTrigger) {
                triggers += trigger
            }

            override fun requestAuthoritativeForegroundSync() {
                triggers += SyncTrigger.Foreground
            }
        }
        val coordinator = coordinator(summaries, cache, sync)
        coordinator.loadDetail(TEST_CONFLICT_UUID)
        val request = ConflictResolveRequest(
            snapshotToken = snapshot.snapshotToken,
            resolutionMutationId = RESOLUTION_MUTATION_UUID,
            choices = listOf(
                com.lezi.babylog.sync.backend.ConflictResolutionChoice(
                    "/note",
                    STABLE_CHOICE_ID,
                ),
            ),
        )

        assertThat(coordinator.resolve(TEST_CONFLICT_UUID, request))
            .isInstanceOf(ConflictResolveOutcome.Accepted::class.java)
        assertThat(cache.get(TEST_CONFLICT_UUID)).isNotNull()
        assertThat(summaries.get(TEST_CONFLICT_UUID)).isNotNull()
        assertThat(triggers).containsExactly(SyncTrigger.Foreground)
    }

    @Test
    fun incompleteChoiceCommandFailsBeforeTransport() = runTest {
        val snapshot = recordConflictSnapshot()
        val summaries = FakeConflictSummaryDao()
        val cache = FakeConflictSnapshotCacheDao()
        seedCompleteSnapshot(summaries, cache, snapshot)
        var submits = 0
        val sync = object : SyncPort by NoOpSyncPort() {
            override suspend fun fetchConflictSnapshot(conflictId: String) = snapshot
            override suspend fun resolveConflict(
                conflictId: String,
                request: ConflictResolveRequest,
            ): ConflictResolveResult {
                submits += 1
                error("must not submit")
            }
        }
        val coordinator = coordinator(summaries, cache, sync)
        coordinator.loadDetail(TEST_CONFLICT_UUID)
        val request = ConflictResolveRequest(
            snapshotToken = snapshot.snapshotToken,
            resolutionMutationId = RESOLUTION_MUTATION_UUID,
            choices = emptyList(),
        )

        val result = coordinator.resolve(TEST_CONFLICT_UUID, request)

        assertThat(result).isInstanceOf(ConflictResolveOutcome.Rejected::class.java)
        assertThat((result as ConflictResolveOutcome.Rejected).code)
            .isEqualTo("incomplete_choices")
        assertThat(submits).isEqualTo(0)
    }

    @Test
    fun withdrawAcceptedWhilePeerRemainsStaysOpen() = runTest {
        val snapshot = recordConflictSnapshot()
        val summaries = FakeConflictSummaryDao()
        val cache = FakeConflictSnapshotCacheDao()
        seedCompleteSnapshot(summaries, cache, snapshot)
        val triggers = mutableListOf<SyncTrigger>()
        val sync = object : SyncPort by NoOpSyncPort() {
            override suspend fun withdrawConflictBranches(
                conflictId: String,
                request: ConflictWithdrawRequest,
            ) = ConflictWithdrawResult.Accepted(
                stableVersionId = snapshot.stable.versionId,
                withdrawalMutationId = request.withdrawalMutationId,
                conflictStatus = "open",
                remainingBranchVersionIds = listOf("v-peer"),
                replay = false,
            )

            override fun requestSync(trigger: SyncTrigger) {
                triggers += trigger
            }

            override fun requestAuthoritativeForegroundSync() {
                triggers += SyncTrigger.Foreground
            }
        }

        val result = coordinator(summaries, cache, sync).withdraw(
            TEST_CONFLICT_UUID,
            withdrawRequest(snapshot),
        )

        assertThat(result).isEqualTo(
            ConflictResolveOutcome.Withdrawn(
                stableVersionId = "v-stable",
                stillOpen = true,
            ),
        )
        assertThat(triggers).containsExactly(SyncTrigger.Foreground)
    }

    @Test
    fun withdrawAcceptedLastBranchClosesConflict() = runTest {
        val snapshot = recordConflictSnapshot()
        val summaries = FakeConflictSummaryDao()
        val cache = FakeConflictSnapshotCacheDao()
        seedCompleteSnapshot(summaries, cache, snapshot)
        val sync = object : SyncPort by NoOpSyncPort() {
            override suspend fun withdrawConflictBranches(
                conflictId: String,
                request: ConflictWithdrawRequest,
            ) = ConflictWithdrawResult.Accepted(
                stableVersionId = snapshot.stable.versionId,
                withdrawalMutationId = request.withdrawalMutationId,
                conflictStatus = "resolved",
                remainingBranchVersionIds = emptyList(),
                replay = false,
            )

            override fun requestSync(trigger: SyncTrigger) = Unit
        }

        val result = coordinator(summaries, cache, sync).withdraw(
            TEST_CONFLICT_UUID,
            withdrawRequest(snapshot),
        )

        assertThat(result).isEqualTo(
            ConflictResolveOutcome.Withdrawn(
                stableVersionId = "v-stable",
                stillOpen = false,
            ),
        )
    }

    @Test
    fun withdrawForbiddenKeepsLocalSnapshot() = runTest {
        val snapshot = recordConflictSnapshot()
        val summaries = FakeConflictSummaryDao()
        val cache = FakeConflictSnapshotCacheDao()
        seedCompleteSnapshot(summaries, cache, snapshot)
        val triggers = mutableListOf<SyncTrigger>()
        val sync = object : SyncPort by NoOpSyncPort() {
            override suspend fun withdrawConflictBranches(
                conflictId: String,
                request: ConflictWithdrawRequest,
            ) = ConflictWithdrawResult.Rejected(
                code = "forbidden",
                withdrawalMutationId = request.withdrawalMutationId,
                retryable = false,
            )

            override fun requestSync(trigger: SyncTrigger) {
                triggers += trigger
            }

            override fun requestAuthoritativeForegroundSync() {
                triggers += SyncTrigger.Foreground
            }
        }

        val result = coordinator(summaries, cache, sync).withdraw(
            TEST_CONFLICT_UUID,
            withdrawRequest(snapshot),
        )

        assertThat(result).isInstanceOf(ConflictResolveOutcome.Forbidden::class.java)
        assertThat((result as ConflictResolveOutcome.Forbidden).message)
            .isEqualTo("只能撤回自己提交的修改")
        assertThat(triggers).isEmpty()
        assertThat(cache.get(TEST_CONFLICT_UUID)).isNotNull()
    }
}

private suspend fun seedCompleteSnapshot(
    summaries: FakeConflictSummaryDao,
    cache: FakeConflictSnapshotCacheDao,
    snapshot: ConflictSnapshot,
) {
    cache.upsert(
        ConflictSnapshotCacheEntity(
            conflictId = snapshot.conflictId,
            snapshotJson = ConflictSnapshotCodec.encode(snapshot),
            cachedAt = snapshot.branches.maxOfOrNull { it.receivedAt } ?: snapshot.stable.receivedAt,
        ),
    )
    summaries.upsert(
        ConflictSummaryEntity(
            conflictId = snapshot.conflictId,
            entityType = snapshot.entityType.wireName,
            clientUuid = snapshot.clientUuid,
            stableVersionId = snapshot.stable.versionId,
            status = "open",
            kind = if (snapshot.branches.isEmpty()) "tombstone_restore" else "concurrent",
            branchVersionIdsJson = JsonArray(
                snapshot.branchVersionIds.map(::JsonPrimitive),
            ).toString(),
            updatedAt = snapshot.branches.maxOfOrNull { it.receivedAt }
                ?: snapshot.stable.receivedAt,
        ),
    )
}

private fun withdrawRequest(snapshot: ConflictSnapshot) = ConflictWithdrawRequest(
    withdrawalMutationId = RESOLUTION_MUTATION_UUID,
    expectedStableVersionId = snapshot.stable.versionId,
    expectedBranchVersionIds = snapshot.branches.map { it.versionId }.sorted(),
)

private fun coordinator(
    summaries: FakeConflictSummaryDao,
    cache: FakeConflictSnapshotCacheDao,
    sync: SyncPort,
) = ConflictResolutionCoordinator(
    conflictSummaryDao = summaries,
    conflictSnapshotCacheDao = cache,
    syncPort = sync,
    transactionRunner = RecordingTransactionRunner(),
)

internal fun recordConflictSnapshot(): ConflictSnapshot {
    fun root(note: String) = ConflictRoot.Record(
        babyClientUuid = TEST_BABY_UUID,
        type = "formula",
        customItemClientUuid = null,
        timestamp = 100,
        endTimestamp = null,
        note = note,
        payload = Json.parseToJsonElement("""{"amount_ml":60}""").jsonObject,
        schemaVersion = 2,
        effectiveWakeObservationClientUuid = null,
        createdByMembershipId = "member-author",
        updatedAt = 100,
        canonical = Json.parseToJsonElement(
            """{"baby_client_uuid":"$TEST_BABY_UUID","type":"formula","custom_item_client_uuid":null,"timestamp":100,"end_timestamp":null,"note":"$note","payload_json":{"amount_ml":60},"schema_version":2,"updated_at":100,"created_by_membership_id":"member-author"}""",
        ).jsonObject,
    )
    val stableSource = ConflictSource(
        "v-stable",
        TEST_MUTATION_STABLE,
        "member-author",
        "device-a",
        100,
    )
    val branchSource = ConflictSource(
        "v-branch",
        TEST_MUTATION_ONE,
        "member-other",
        "device-b",
        110,
    )
    return ConflictSnapshot(
        conflictId = TEST_CONFLICT_UUID,
        entityType = ConflictRootType.Record,
        clientUuid = TEST_ROOT_UUID,
        snapshotToken = "a".repeat(43),
        expiresAt = 2_000_000,
        stable = ConflictVersionSnapshot(
            "v-stable",
            null,
            root("stable"),
            emptyList(),
            false,
            TEST_MUTATION_STABLE,
            "member-author",
            "device-a",
            100,
        ),
        branches = listOf(
            ConflictVersionSnapshot(
                "v-branch",
                "v-stable",
                root("branch"),
                emptyList(),
                false,
                TEST_MUTATION_ONE,
                "member-other",
                "device-b",
                110,
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
        autoMerged = emptyList(),
        pageIndex = 0,
        continuation = null,
        complete = true,
    )
}

private const val TEST_CONFLICT_UUID = "00000000-0000-0000-0000-000000000031"
private const val FOREIGN_CONFLICT_UUID = "00000000-0000-0000-0000-000000000039"
private const val TEST_ROOT_UUID = "00000000-0000-0000-0000-000000000032"
private const val TEST_BABY_UUID = "00000000-0000-0000-0000-000000000033"
private const val TEST_MUTATION_STABLE = "00000000-0000-0000-0000-000000000034"
private const val TEST_MUTATION_ONE = "00000000-0000-0000-0000-000000000035"
private const val RESOLUTION_MUTATION_UUID = "00000000-0000-0000-0000-000000000036"
private val STABLE_CHOICE_ID = "b".repeat(43)
private val BRANCH_CHOICE_ID = "c".repeat(43)
