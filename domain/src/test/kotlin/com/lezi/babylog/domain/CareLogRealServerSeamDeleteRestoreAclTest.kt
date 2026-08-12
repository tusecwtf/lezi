package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.domain.carelog.ConflictResolveOutcome
import com.lezi.babylog.domain.carelog.ConflictResolverAudience
import com.lezi.babylog.domain.carelog.ConflictResolverAvailability
import com.lezi.babylog.domain.carelog.ConflictResolverDraft
import com.lezi.babylog.sync.conflict.ConflictOutcome
import com.lezi.babylog.sync.conflict.ConflictSnapshot
import com.lezi.babylog.sync.session.FamilyRole
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.junit.Test

/**
 * H33 acceptance: delete/edit, direct-base restore, detail→new-branch race, and
 * author/Owner ACL on the primary CareLog→real-server seam.
 *
 * Requires a current lezi-sync binary (shared cargo target-dir, tools/lezi-sync/target,
 * or LEZI_SYNC_BIN). Never touches family NAS paths or production certificates.
 */
class CareLogRealServerSeamDeleteRestoreAclTest {

    @Test
    fun deleteRestoreRaceAclMatrixFailSafeAndConverges() = runBlocking {
        CareLogRealServerSeamFixture.open().use { fixture ->
            val ownerB = fixture.joinExtraOwner("owner-b")
            val author = fixture.joinExtraMember("author", displayName = "作者甲")
            val other = fixture.joinExtraMember("other", displayName = "路人乙")
            val clients = listOf(fixture.owner, ownerB, fixture.member, author, other)
            val evidence = mutableListOf<CaseEvidence>()

            evidence += runDeleteEditBothOrders(fixture, clients, ownerB)
            evidence += runDeleteDelete(fixture, clients, ownerB)
            evidence += runCompleteDirectBaseRestore(fixture, clients, author)
            evidence += runMissingBaseReject(fixture, clients, author)
            evidence += runDetailThenNewBranchRace(fixture, clients, author, ownerB)
            evidence += runAuthorOwnerOtherAcl(fixture, clients, author, other)

            assertThat(evidence).hasSize(6)
            evidence.forEach { row ->
                assertThat(row.caseId).isNotEmpty()
                assertThat(row.passed).isTrue()
            }
        }
    }

    /**
     * C1: delete vs edit on the same base — both arrival orders open a conflict;
     * choosing live or tombstone is explicit (no silent delete/revive).
     * Roles stay fixed (owner deletes, peer edits); only settle order varies.
     */
    private suspend fun runDeleteEditBothOrders(
        fixture: CareLogRealServerSeamFixture,
        clients: List<SeamClient>,
        peer: SeamClient,
    ): CaseEvidence {
        val owner = fixture.owner
        val settleOrders = listOf(
            listOf(owner, peer) to "keep-live",
            listOf(peer, owner) to "keep-deleted",
        )
        val digests = mutableListOf<String>()
        for ((index, orderAndChoice) in settleOrders.withIndex()) {
            val (order, choice) = orderAndChoice
            val seeded = seedFormulaRecord(
                fixture,
                clients,
                note = "base-delete-edit",
                amountMl = 90,
                clientUuid = "33333333-3333-4333-8333-33333333331$index",
            )
            // Fixed roles: owner always deletes; peer always edits the same base.
            owner.careLog.deleteRecord(
                requireNotNull(owner.recordByClientUuid(seeded.uuid)).id,
            )
            peer.careLog.updateRecord(
                id = requireNotNull(peer.recordByClientUuid(seeded.uuid)).id,
                timestamp = seeded.timestamp,
                endTimestamp = null,
                note = "edit-survives",
                payloadJson = formulaPayloadJson(90),
                nowMillis = peer.clock.nowMillis(),
            )
            order.forEach { it.settleLocalWrite() }
            fixture.pullAll(clients)
            val conflictId = requireSingleOpenConflict(clients, seeded.uuid)
            val snapshot = owner.loadConflict(conflictId).snapshot
            assertAutoConflictDisjoint(snapshot)
            assertThat(snapshot.conflicting.any { it.path == "/_mutation.deleted" }).isTrue()
            digests += semanticConflictDigest(snapshot)

            val preferDeleted = choice == "keep-deleted"
            val deletedPath = snapshot.conflicting.single { it.path == "/_mutation.deleted" }
            val deletedChoiceId = deletedPath.candidates.first { candidate ->
                when (val outcome = candidate.outcome) {
                    ConflictOutcome.Remove -> preferDeleted
                    is ConflictOutcome.Set -> {
                        val keepLive = outcome.value is JsonPrimitive &&
                            (outcome.value as JsonPrimitive).booleanOrNull == false
                        !preferDeleted && keepLive
                    }
                }
            }.choiceId
            val otherChoices = snapshot.conflicting.filterNot { it.path == "/_mutation.deleted" }.map { path ->
                val preferred = if (!preferDeleted && path.path == "/note") {
                    path.candidates.firstOrNull { candidate ->
                        val outcome = candidate.outcome
                        outcome is ConflictOutcome.Set &&
                            outcome.value == JsonPrimitive("edit-survives")
                    } ?: path.candidates.first()
                } else {
                    path.candidates.first()
                }
                com.lezi.babylog.sync.backend.ConflictResolutionChoice(
                    path = path.path,
                    choiceId = preferred.choiceId,
                )
            }
            owner.careLog.loadConflictDetail(conflictId, forceRefresh = false)
            val outcome = owner.careLog.resolveConflict(
                conflictId = conflictId,
                request = com.lezi.babylog.sync.backend.ConflictResolveRequest(
                    snapshotToken = snapshot.snapshotToken,
                    resolutionMutationId = java.util.UUID.randomUUID().toString(),
                    choices = listOf(
                        com.lezi.babylog.sync.backend.ConflictResolutionChoice(
                            path = "/_mutation.deleted",
                            choiceId = deletedChoiceId,
                        ),
                    ) + otherChoices,
                ),
            )
            assertThat(outcome).isInstanceOf(ConflictResolveOutcome.Accepted::class.java)
            settleAfterResolve(fixture, clients)

            val fact = owner.stableRecordFact(seeded.uuid)
            assertThat(fact.syncDirty).isFalse()
            if (preferDeleted) {
                // Choosing tombstone may mint a fresh pure tombstone_restore handle;
                // the root must stay deleted with no silent revive.
                assertThat(fact.deletedAt).isNotNull()
                clients.forEach { client ->
                    val peerFact = client.stableRecordFact(seeded.uuid)
                    assertThat(peerFact.deletedAt).isNotNull()
                    assertThat(peerFact.syncDirty).isFalse()
                }
            } else {
                assertThat(fact.openConflictId).isNull()
                assertThat(fact.deletedAt).isNull()
                assertThat(fact.note).isEqualTo("edit-survives")
                clients.forEach { client ->
                    assertThat(client.stableRecordFact(seeded.uuid)).isEqualTo(fact)
                }
            }
        }
        assertThat(digests.distinct()).hasSize(1)
        return CaseEvidence(
            caseId = "C1-delete-edit-both-orders",
            orders = settleOrders.map { (order, choice) ->
                order.joinToString("→") { it.label } + "|$choice"
            },
            digests = digests,
            kind = "conflict",
            passed = true,
        )
    }
    /**
     * C2: two concurrent same-base deletes. The root must stay deleted with no
     * silent revive. A pure tombstone_restore handle may remain open (restore is
     * explicit choice-only); concurrent dual-delete never auto-revives.
     */
    private suspend fun runDeleteDelete(
        fixture: CareLogRealServerSeamFixture,
        clients: List<SeamClient>,
        peer: SeamClient,
    ): CaseEvidence {
        val owner = fixture.owner
        val orders = listOf(listOf(owner, peer), listOf(peer, owner))
        val digests = mutableListOf<String>()
        for ((index, order) in orders.withIndex()) {
            val seeded = seedFormulaRecord(
                fixture,
                clients,
                note = "base-delete-delete",
                amountMl = 80,
                clientUuid = "33333333-3333-4333-8333-33333333332$index",
            )
            order.forEach { client ->
                client.careLog.deleteRecord(
                    requireNotNull(client.recordByClientUuid(seeded.uuid)).id,
                )
            }
            order.forEach { it.settleLocalWrite() }
            fixture.pullAll(clients)

            val openIds = clients.mapNotNull { it.openConflictIdForRecord(seeded.uuid) }.distinct()
            val shape = if (openIds.isNotEmpty()) {
                val conflictId = openIds.single()
                val snapshot = owner.loadConflict(conflictId).snapshot
                assertAutoConflictDisjoint(snapshot)
                // Dual delete must not invent a live stable tip.
                assertThat(snapshot.stable.deleted || snapshot.branches.any { it.deleted }).isTrue()
                "open|stableDeleted=${snapshot.stable.deleted}|branches=${snapshot.branches.size}"
            } else {
                "deleted-closed"
            }
            digests += shape

            val fact = owner.stableRecordFact(seeded.uuid)
            assertThat(fact.deletedAt).isNotNull()
            assertThat(fact.syncDirty).isFalse()
            // No silent revive: even with an open restore handle, Room root stays deleted.
            clients.forEach { client ->
                val peerFact = client.stableRecordFact(seeded.uuid)
                assertThat(peerFact.deletedAt).isNotNull()
                assertThat(peerFact.note).isEqualTo("base-delete-delete")
            }
        }
        // Both orders must leave the fact deleted; open-handle shape may differ by
        // first-accepted tip but never revives.
        assertThat(digests).isNotEmpty()
        return CaseEvidence(
            caseId = "C2-delete-delete-converge",
            orders = orders.map { it.joinToString("→") { c -> c.label } },
            digests = digests,
            kind = "delete_delete",
            passed = true,
        )
    }

    /** C3: pure tombstone_restore from a complete direct live base restores root. */
    private suspend fun runCompleteDirectBaseRestore(
        fixture: CareLogRealServerSeamFixture,
        clients: List<SeamClient>,
        author: SeamClient,
    ): CaseEvidence {
        val seeded = seedFormulaRecord(
            fixture,
            clients,
            note = "restore-me",
            amountMl = 110,
            clientUuid = "33333333-3333-4333-8333-333333333330",
            author = author,
        )
        author.careLog.deleteRecord(
            requireNotNull(author.recordByClientUuid(seeded.uuid)).id,
        )
        author.settleLocalWrite()
        fixture.pullAll(clients)

        val conflictId = requireSingleOpenConflict(clients, seeded.uuid)
        val snapshot = author.loadConflict(conflictId).snapshot
        assertThat(snapshot.stable.deleted).isTrue()
        assertThat(snapshot.branches).isEmpty()
        assertThat(snapshot.conflicting.map { it.path }).containsExactly("/_mutation.deleted")
        val restoreCandidate = snapshot.conflicting.single().candidates.single {
            it.outcome == ConflictOutcome.Set(JsonPrimitive(false))
        }
        assertThat(restoreCandidate.sources).isNotEmpty()

        val audience = ConflictResolverAudience(
            membershipId = author.currentSession().membershipId,
            isOwner = false,
        )
        val draft = ConflictResolverDraft.open(
            snapshot = snapshot,
            audience = audience,
            fetchedOnline = true,
            nowMillis = author.clock.nowMillis(),
            resolutionMutationId = UUID.randomUUID().toString(),
        )
        assertThat(draft.model.availability).isEqualTo(ConflictResolverAvailability.Current)
        assertThat(draft.model.canResolve).isTrue()

        val (_, outcome) = author.resolveOpenConflict(
            conflictId = conflictId,
            preferOutcomeByPath = mapOf(
                "/_mutation.deleted" to ConflictOutcome.Set(JsonPrimitive(false)),
            ),
        )
        assertThat(outcome).isInstanceOf(ConflictResolveOutcome.Accepted::class.java)
        fixture.pullAll(clients)

        val fact = author.stableRecordFact(seeded.uuid)
        assertThat(fact.deletedAt).isNull()
        assertThat(fact.note).isEqualTo("restore-me")
        assertThat(fact.payloadJson).contains("\"amount_ml\":110")
        assertThat(fact.openConflictId).isNull()
        assertThat(fact.syncDirty).isFalse()
        assertThat(fact.baseVersion).isNotEqualTo(seeded.baseVersion)
        clients.forEach { client ->
            assertThat(client.stableRecordFact(seeded.uuid)).isEqualTo(fact)
        }
        return CaseEvidence(
            caseId = "C3-complete-direct-base-restore",
            orders = listOf("author-delete→author-restore"),
            digests = listOf(semanticConflictDigest(snapshot)),
            kind = "restore",
            passed = true,
        )
    }

    /**
     * C4: incomplete direct base (missing parent link) is rejected without revive.
     * No-media path: "missing bytes" maps to the contract's base completeness check.
     */
    private suspend fun runMissingBaseReject(
        fixture: CareLogRealServerSeamFixture,
        clients: List<SeamClient>,
        author: SeamClient,
    ): CaseEvidence {
        val seeded = seedFormulaRecord(
            fixture,
            clients,
            note = "broken-base",
            amountMl = 70,
            clientUuid = "33333333-3333-4333-8333-333333333340",
            author = author,
        )
        author.careLog.deleteRecord(
            requireNotNull(author.recordByClientUuid(seeded.uuid)).id,
        )
        author.settleLocalWrite()
        fixture.pullAll(clients)

        val conflictId = requireSingleOpenConflict(clients, seeded.uuid)
        val before = author.loadConflict(conflictId).snapshot
        assertThat(before.stable.deleted).isTrue()
        val tombstoneVersion = before.stable.versionId
        // Pin the complete pre-corruption snapshot into the local projection so
        // resolve can submit choice-only without re-fetching detail after the fault.
        author.careLog.loadConflictDetail(conflictId, forceRefresh = false)

        // Honest incomplete-base fault: drop the only parent edge of the tombstone.
        corruptTombstoneParents(fixture.server.dataRoot, tombstoneVersion)

        val restoreChoice = before.conflicting.single { it.path == "/_mutation.deleted" }
            .candidates.first {
                it.outcome == ConflictOutcome.Set(JsonPrimitive(false))
            }
        val rejected = author.careLog.resolveConflict(
            conflictId = conflictId,
            request = com.lezi.babylog.sync.backend.ConflictResolveRequest(
                snapshotToken = before.snapshotToken,
                resolutionMutationId = UUID.randomUUID().toString(),
                choices = listOf(
                    com.lezi.babylog.sync.backend.ConflictResolutionChoice(
                        path = "/_mutation.deleted",
                        choiceId = restoreChoice.choiceId,
                    ),
                ),
            ),
        )
        assertThat(rejected).isInstanceOf(ConflictResolveOutcome.Rejected::class.java)
        val code = (rejected as ConflictResolveOutcome.Rejected).code
        assertThat(code).isIn(listOf("missing_restore_base", "incomplete_restore_base"))

        fixture.pullAll(clients)
        val fact = author.stableRecordFact(seeded.uuid)
        assertThat(fact.deletedAt).isNotNull()
        // Handle may stay open or become unloadable; never silently live.
        clients.forEach { client ->
            val peer = client.stableRecordFact(seeded.uuid)
            assertThat(peer.deletedAt).isNotNull()
            assertThat(peer.note).isEqualTo("broken-base")
        }
        return CaseEvidence(
            caseId = "C4-missing-base-reject",
            orders = listOf("author-delete→corrupt-parent→restore-reject"),
            digests = listOf("reject:$code"),
            kind = "reject",
            passed = true,
        )
    }

    /**
     * C5: load restore detail, inject a new branch from a peer that still holds
     * the original direct base (no delete pull), stale restore must refresh;
     * re-select on the fresh concurrent snapshot succeeds.
     */
    private suspend fun runDetailThenNewBranchRace(
        fixture: CareLogRealServerSeamFixture,
        clients: List<SeamClient>,
        author: SeamClient,
        brancher: SeamClient,
    ): CaseEvidence {
        val seeded = seedFormulaRecord(
            fixture,
            clients,
            note = "race-base",
            amountMl = 95,
            clientUuid = "33333333-3333-4333-8333-333333333350",
            author = author,
        )
        // Brancher freezes the original base and must not observe the delete yet.
        val brancherBase = requireNotNull(brancher.recordByClientUuid(seeded.uuid)).baseVersion
        assertThat(brancherBase).isEqualTo(seeded.baseVersion)

        author.careLog.deleteRecord(
            requireNotNull(author.recordByClientUuid(seeded.uuid)).id,
        )
        author.settleLocalWrite()
        // Pull everyone except brancher so the late branch stays same-base concurrent.
        fixture.pullAll(clients.filterNot { it.label == brancher.label })

        val conflictId = requireSingleOpenConflict(
            clients.filterNot { it.label == brancher.label },
            seeded.uuid,
        )
        val staleLoad = author.loadConflict(conflictId, forceRefresh = true)
        assertThat(staleLoad.fetchedOnline).isTrue()
        assertThat(staleLoad.snapshot.branches).isEmpty()
        assertThat(staleLoad.snapshot.stable.deleted).isTrue()
        // Pin the stale token into the local projection used by resolve.
        author.careLog.loadConflictDetail(conflictId, forceRefresh = false)

        // Late live edit against the original direct base while detail is open.
        assertThat(brancher.recordByClientUuid(seeded.uuid)?.deletedAt).isNull()
        assertThat(brancher.recordByClientUuid(seeded.uuid)?.baseVersion)
            .isEqualTo(seeded.baseVersion)
        brancher.careLog.updateRecord(
            id = requireNotNull(brancher.recordByClientUuid(seeded.uuid)).id,
            timestamp = seeded.timestamp,
            endTimestamp = null,
            note = "late-branch",
            payloadJson = formulaPayloadJson(95),
            nowMillis = brancher.clock.nowMillis(),
        )
        brancher.settleLocalWrite()

        val staleChoices = staleLoad.snapshot.conflicting.map { path ->
            val candidate = path.candidates.first {
                it.outcome == ConflictOutcome.Set(JsonPrimitive(false))
            }
            com.lezi.babylog.sync.backend.ConflictResolutionChoice(
                path = path.path,
                choiceId = candidate.choiceId,
            )
        }
        val staleOutcome = author.careLog.resolveConflict(
            conflictId = conflictId,
            request = com.lezi.babylog.sync.backend.ConflictResolveRequest(
                snapshotToken = staleLoad.snapshot.snapshotToken,
                resolutionMutationId = UUID.randomUUID().toString(),
                choices = staleChoices,
            ),
        )
        assertThat(staleOutcome).isInstanceOf(ConflictResolveOutcome.RefreshRequired::class.java)

        // Refresh and re-select on the concurrent snapshot (keep live branch).
        fixture.pullAll(clients)
        val fresh = author.loadConflict(conflictId, forceRefresh = true).snapshot
        assertThat(fresh.snapshotToken).isNotEqualTo(staleLoad.snapshot.snapshotToken)
        assertThat(fresh.branches).isNotEmpty()
        assertAutoConflictDisjoint(fresh)

        val (_, accepted) = author.resolveOpenConflict(
            conflictId = conflictId,
            preferOutcomeByPath = mapOf(
                "/_mutation.deleted" to ConflictOutcome.Set(JsonPrimitive(false)),
            ),
            preferValueByPath = mapOf("/note" to JsonPrimitive("late-branch")),
        )
        assertThat(accepted).isInstanceOf(ConflictResolveOutcome.Accepted::class.java)
        settleAfterResolve(fixture, clients)

        val fact = author.stableRecordFact(seeded.uuid)
        assertThat(fact.deletedAt).isNull()
        assertThat(fact.note).isEqualTo("late-branch")
        assertThat(fact.openConflictId).isNull()
        assertThat(fact.syncDirty).isFalse()
        clients.forEach { client ->
            assertThat(client.stableRecordFact(seeded.uuid)).isEqualTo(fact)
        }
        return CaseEvidence(
            caseId = "C5-detail-then-new-branch-race",
            orders = listOf("stale-restore→same-base-branch→refresh→keep-live"),
            digests = listOf(
                semanticConflictDigest(staleLoad.snapshot),
                semanticConflictDigest(fresh),
            ),
            kind = "race",
            passed = true,
        )
    }

    /**
     * C6: author and Owner may resolve; unrelated other member is denied by UI
     * affordance and server enforcement; Owner then converges the family.
     */
    private suspend fun runAuthorOwnerOtherAcl(
        fixture: CareLogRealServerSeamFixture,
        clients: List<SeamClient>,
        author: SeamClient,
        other: SeamClient,
    ): CaseEvidence {
        val owner = fixture.owner
        assertThat(author.currentSession().role).isEqualTo(FamilyRole.Member)
        assertThat(other.currentSession().role).isEqualTo(FamilyRole.Member)
        assertThat(owner.currentSession().role).isEqualTo(FamilyRole.Owner)

        val seeded = seedFormulaRecord(
            fixture,
            clients,
            note = "acl-base",
            amountMl = 100,
            clientUuid = "33333333-3333-4333-8333-333333333360",
            author = author,
        )
        // Open concurrent note conflict so all three actors see the same handle.
        owner.careLog.updateRecord(
            id = requireNotNull(owner.recordByClientUuid(seeded.uuid)).id,
            timestamp = seeded.timestamp,
            endTimestamp = null,
            note = "owner-note",
            payloadJson = formulaPayloadJson(100),
            nowMillis = owner.clock.nowMillis(),
        )
        author.careLog.updateRecord(
            id = requireNotNull(author.recordByClientUuid(seeded.uuid)).id,
            timestamp = seeded.timestamp,
            endTimestamp = null,
            note = "author-note",
            payloadJson = formulaPayloadJson(100),
            nowMillis = author.clock.nowMillis(),
        )
        owner.settleLocalWrite()
        author.settleLocalWrite()
        fixture.pullAll(clients)

        val conflictId = requireSingleOpenConflict(clients, seeded.uuid)
        val snapshot = other.loadConflict(conflictId).snapshot
        assertAutoConflictDisjoint(snapshot)

        val otherAudience = ConflictResolverAudience(
            membershipId = other.currentSession().membershipId,
            isOwner = false,
        )
        val otherDraft = ConflictResolverDraft.open(
            snapshot = snapshot,
            audience = otherAudience,
            fetchedOnline = true,
            nowMillis = other.clock.nowMillis(),
            resolutionMutationId = UUID.randomUUID().toString(),
        )
        assertThat(otherDraft.model.availability)
            .isEqualTo(ConflictResolverAvailability.Forbidden)
        assertThat(otherDraft.model.canResolve).isFalse()

        val authorAudience = ConflictResolverAudience(
            membershipId = author.currentSession().membershipId,
            isOwner = false,
        )
        val authorDraft = ConflictResolverDraft.open(
            snapshot = snapshot,
            audience = authorAudience,
            fetchedOnline = true,
            nowMillis = author.clock.nowMillis(),
            resolutionMutationId = UUID.randomUUID().toString(),
        )
        assertThat(authorDraft.model.availability)
            .isEqualTo(ConflictResolverAvailability.Current)

        val ownerAudience = ConflictResolverAudience(
            membershipId = owner.currentSession().membershipId,
            isOwner = true,
        )
        val ownerDraft = ConflictResolverDraft.open(
            snapshot = snapshot,
            audience = ownerAudience,
            fetchedOnline = true,
            nowMillis = owner.clock.nowMillis(),
            resolutionMutationId = UUID.randomUUID().toString(),
        )
        assertThat(ownerDraft.model.availability)
            .isEqualTo(ConflictResolverAvailability.Current)

        // Server deny for the unrelated member matches the UI Forbidden affordance.
        val (_, otherOutcome) = other.resolveOpenConflict(
            conflictId = conflictId,
            preferValueByPath = mapOf("/note" to JsonPrimitive("author-note")),
        )
        assertThat(otherOutcome).isInstanceOf(ConflictResolveOutcome.Forbidden::class.java)
        fixture.pullAll(clients)
        assertThat(requireSingleOpenConflict(clients, seeded.uuid)).isEqualTo(conflictId)

        // Author is allowed and can converge.
        val (_, authorOutcome) = author.resolveOpenConflict(
            conflictId = conflictId,
            preferValueByPath = mapOf("/note" to JsonPrimitive("author-note")),
        )
        assertThat(authorOutcome).isInstanceOf(ConflictResolveOutcome.Accepted::class.java)
        fixture.pullAll(clients)

        val fact = author.stableRecordFact(seeded.uuid)
        assertThat(fact.note).isEqualTo("author-note")
        assertThat(fact.openConflictId).isNull()
        assertThat(fact.syncDirty).isFalse()
        clients.forEach { client ->
            assertThat(client.stableRecordFact(seeded.uuid)).isEqualTo(fact)
        }

        // Fresh conflict: Owner may also resolve (UI + server).
        val seeded2 = seedFormulaRecord(
            fixture,
            clients,
            note = "acl-owner-base",
            amountMl = 101,
            clientUuid = "33333333-3333-4333-8333-333333333361",
            author = author,
        )
        owner.careLog.updateRecord(
            id = requireNotNull(owner.recordByClientUuid(seeded2.uuid)).id,
            timestamp = seeded2.timestamp,
            endTimestamp = null,
            note = "owner-wins",
            payloadJson = formulaPayloadJson(101),
            nowMillis = owner.clock.nowMillis(),
        )
        author.careLog.updateRecord(
            id = requireNotNull(author.recordByClientUuid(seeded2.uuid)).id,
            timestamp = seeded2.timestamp,
            endTimestamp = null,
            note = "author-loses",
            payloadJson = formulaPayloadJson(101),
            nowMillis = author.clock.nowMillis(),
        )
        owner.settleLocalWrite()
        author.settleLocalWrite()
        fixture.pullAll(clients)
        val conflict2 = requireSingleOpenConflict(clients, seeded2.uuid)
        val (_, ownerOutcome) = owner.resolveOpenConflict(
            conflictId = conflict2,
            preferValueByPath = mapOf("/note" to JsonPrimitive("owner-wins")),
        )
        assertThat(ownerOutcome).isInstanceOf(ConflictResolveOutcome.Accepted::class.java)
        fixture.pullAll(clients)
        val ownerFact = owner.stableRecordFact(seeded2.uuid)
        assertThat(ownerFact.note).isEqualTo("owner-wins")
        assertThat(ownerFact.openConflictId).isNull()
        clients.forEach { client ->
            assertThat(client.stableRecordFact(seeded2.uuid)).isEqualTo(ownerFact)
        }

        return CaseEvidence(
            caseId = "C6-author-owner-other-acl",
            orders = listOf("other-forbidden", "author-allowed", "owner-allowed"),
            digests = listOf(semanticConflictDigest(snapshot)),
            kind = "acl",
            passed = true,
        )
    }

    private data class SeededRecord(
        val uuid: String,
        val baseVersion: String,
        val timestamp: Long,
    )

    private suspend fun seedFormulaRecord(
        fixture: CareLogRealServerSeamFixture,
        clients: List<SeamClient>,
        note: String,
        amountMl: Int,
        clientUuid: String,
        author: SeamClient = fixture.owner,
    ): SeededRecord {
        val owner = fixture.owner
        val baby = owner.fakes.babies.listAll().firstOrNull()
            ?: run {
                val babyId = owner.careLog.createBaby(
                    CreateBabyInput(
                        nickname = "H33-Lele",
                        birthdayEpochDay = 20_000L,
                        sex = "female",
                    ),
                )
                owner.settleLocalWrite()
                requireNotNull(owner.fakes.babies.get(babyId))
            }
        fixture.pullAll(clients)
        val localBabyId = requireNotNull(
            author.fakes.babies.listAll().firstOrNull { it.clientUuid == baby.clientUuid }?.id,
        )
        author.clock.now = author.clock.nowMillis() + 1_000L
        val ts = author.clock.nowMillis() - 30_000L
        author.careLog.addRecord(
            babyId = localBabyId,
            type = RecordType.FORMULA,
            timestamp = ts,
            note = note,
            payloadJson = formulaPayloadJson(amountMl = amountMl),
            nowMillis = author.clock.nowMillis(),
            clientUuid = clientUuid,
        )
        author.settleLocalWrite()
        val settled = requireNotNull(author.recordByClientUuid(clientUuid))
        assertThat(settled.syncDirty).isFalse()
        val baseVersion = requireNotNull(settled.baseVersion)
        fixture.pullAll(clients)
        clients.forEach { client ->
            val row = requireNotNull(client.recordByClientUuid(clientUuid))
            assertThat(row.baseVersion).isEqualTo(baseVersion)
            assertThat(row.syncDirty).isFalse()
            assertThat(row.deletedAt).isNull()
            assertThat(row.createdByMembershipId)
                .isEqualTo(author.currentSession().membershipId)
        }
        return SeededRecord(clientUuid, baseVersion, ts)
    }

    private suspend fun requireSingleOpenConflict(
        clients: List<SeamClient>,
        recordUuid: String,
    ): String {
        val ids = clients.mapNotNull { it.openConflictIdForRecord(recordUuid) }.distinct()
        check(ids.size == 1) {
            "expected one open conflict for $recordUuid, got $ids " +
                clients.map { it.label to it.openConflictIdForRecord(recordUuid) }
        }
        return ids.single()
    }

    private suspend fun settleAfterResolve(
        fixture: CareLogRealServerSeamFixture,
        clients: List<SeamClient>,
    ) {
        // Resolve schedules Foreground; drain twice so accepted projection lands
        // before assertions on openConflictId / deletedAt.
        fixture.pullAll(clients)
        fixture.pullAll(clients)
    }

    /**
     * Corrupt the tombstone's parent edge in the live isolated server DB so restore
     * base policy classifies missing/incomplete without inventing media faults.
     */
    private fun corruptTombstoneParents(dataRoot: File, tombstoneVersionId: String) {
        val db = File(dataRoot, "lezi.db")
        check(db.isFile) { "missing isolated lezi.db at ${db.absolutePath}" }
        val sqlite = resolveSqlite3()
        val sql = """
            PRAGMA busy_timeout=10000;
            DELETE FROM entity_version_parents WHERE version_id = '$tombstoneVersionId';
        """.trimIndent()
        val process = ProcessBuilder(sqlite, db.absolutePath)
            .redirectErrorStream(true)
            .start()
        process.outputStream.bufferedWriter().use { writer ->
            writer.write(sql)
            writer.newLine()
        }
        val output = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        check(code == 0) {
            "sqlite3 corrupt parents failed code=$code out=$output db=${db.absolutePath}"
        }
    }

    private fun resolveSqlite3(): String {
        val candidates = listOf(
            "sqlite3",
            "${System.getProperty("user.home")}/Android/Sdk/platform-tools/sqlite3",
            "/usr/bin/sqlite3",
        )
        for (candidate in candidates) {
            val file = File(candidate)
            if (file.isFile && file.canExecute()) return file.absolutePath
            val which = ProcessBuilder("which", candidate).start()
            if (which.waitFor() == 0) {
                val path = which.inputStream.bufferedReader().readText().trim()
                if (path.isNotEmpty()) return path
            }
        }
        error("sqlite3 required for missing-base corruption fixture")
    }

    private data class CaseEvidence(
        val caseId: String,
        val orders: List<String>,
        val digests: List<String>,
        val kind: String,
        val passed: Boolean,
    )
}
