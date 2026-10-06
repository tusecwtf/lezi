package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.domain.carelog.ConflictResolveOutcome
import com.lezi.babylog.domain.carelog.ConflictResolverAudience
import com.lezi.babylog.domain.carelog.ConflictResolverAvailability
import com.lezi.babylog.domain.carelog.ConflictResolverDraft
import com.lezi.babylog.sync.conflict.ConflictOutcome
import com.lezi.babylog.sync.session.FamilyRole
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.junit.Test

/**
 * H33 acceptance: delete/edit, empty-set is not a sync conflict, same-base
 * late branch after delete, and live-fork adopt ACL on the primary
 * CareLog→real-server seam.
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
            evidence += runStableDeleteIsNotASyncConflict(fixture, clients, author)
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
                // Choosing tombstone must not mint a standing restore conflict;
                // the root stays deleted with no silent revive.
                assertThat(fact.deletedAt).isNotNull()
                assertThat(fact.openConflictId).isNull()
                clients.forEach { client ->
                    val peerFact = client.stableRecordFact(seeded.uuid)
                    assertThat(peerFact.deletedAt).isNotNull()
                    assertThat(peerFact.syncDirty).isFalse()
                    assertThat(peerFact.openConflictId).isNull()
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
     * silent revive. Empty-branch tombstone handles are not sync conflicts;
     * concurrent dual-delete never auto-revives.
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

    /** C3: stable delete without concurrent branches is not a sync conflict. */
    private suspend fun runStableDeleteIsNotASyncConflict(
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

        val openIds = clients.mapNotNull { it.openConflictIdForRecord(seeded.uuid) }
        assertThat(openIds).isEmpty()
        val inbox = author.careLog.observeOpenConflictInbox().first()
        assertThat(inbox.items.none { it.clientUuid == seeded.uuid }).isTrue()

        val fact = author.stableRecordFact(seeded.uuid)
        assertThat(fact.deletedAt).isNotNull()
        assertThat(fact.note).isEqualTo("restore-me")
        assertThat(fact.openConflictId).isNull()
        assertThat(fact.syncDirty).isFalse()
        clients.forEach { client ->
            val peer = client.stableRecordFact(seeded.uuid)
            assertThat(peer.deletedAt).isNotNull()
            assertThat(peer.openConflictId).isNull()
            assertThat(peer.note).isEqualTo("restore-me")
        }
        return CaseEvidence(
            caseId = "C3-stable-delete-is-not-sync-conflict",
            orders = listOf("author-delete→pull"),
            digests = listOf("deleted-closed"),
            kind = "delete",
            passed = true,
        )
    }

    /**
     * C4: dropping tombstone parent evidence must not mint a sync conflict or
     * silently revive the root. Authorized restore CAS coverage stays on the
     * server; inbox is not the restore path.
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
        val tombstoneVersion = requireNotNull(author.stableRecordFact(seeded.uuid).baseVersion)
        corruptTombstoneParents(fixture.server.dataRoot, tombstoneVersion)
        fixture.pullAll(clients)

        val openIds = clients.mapNotNull { it.openConflictIdForRecord(seeded.uuid) }
        assertThat(openIds).isEmpty()
        val fact = author.stableRecordFact(seeded.uuid)
        assertThat(fact.deletedAt).isNotNull()
        clients.forEach { client ->
            val peer = client.stableRecordFact(seeded.uuid)
            assertThat(peer.deletedAt).isNotNull()
            assertThat(peer.note).isEqualTo("broken-base")
            assertThat(peer.openConflictId).isNull()
        }
        return CaseEvidence(
            caseId = "C4-missing-base-stays-deleted",
            orders = listOf("author-delete→corrupt-parent→still-deleted"),
            digests = listOf("deleted-closed"),
            kind = "reject",
            passed = true,
        )
    }

    /**
     * C5: delete is accepted without a restore handle. A peer that still holds
     * the original live base can branch; that concurrent conflict is the only
     * sync-conflict entry, and choosing the live branch converges the family.
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
        val brancherBase = requireNotNull(brancher.recordByClientUuid(seeded.uuid)).baseVersion
        assertThat(brancherBase).isEqualTo(seeded.baseVersion)

        author.careLog.deleteRecord(
            requireNotNull(author.recordByClientUuid(seeded.uuid)).id,
        )
        author.settleLocalWrite()
        fixture.pullAll(clients.filterNot { it.label == brancher.label })
        assertThat(
            clients.filterNot { it.label == brancher.label }
                .mapNotNull { it.openConflictIdForRecord(seeded.uuid) },
        ).isEmpty()

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
        fixture.pullAll(clients)

        val conflictId = requireSingleOpenConflict(clients, seeded.uuid)
        val snapshot = author.loadConflict(conflictId).snapshot
        assertThat(snapshot.stable.deleted).isTrue()
        assertThat(snapshot.branches).isNotEmpty()
        assertAutoConflictDisjoint(snapshot)

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
            caseId = "C5-delete-then-same-base-branch",
            orders = listOf("delete→late-branch→keep-live"),
            digests = listOf(semanticConflictDigest(snapshot)),
            kind = "race",
            passed = true,
        )
    }

    /**
     * C6: live-fork adopt is family-admin only; author and unrelated member
     * are denied by UI affordance and server enforcement; 家庭管理员 converges.
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
            .isEqualTo(ConflictResolverAvailability.Forbidden)
        assertThat(authorDraft.model.readOnlyReason)
            .isEqualTo("分叉后只有家庭管理员能采用新稳定")
        assertThat(authorDraft.model.canResolve).isFalse()

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

        val (_, otherOutcome) = other.resolveOpenConflict(
            conflictId = conflictId,
            preferValueByPath = mapOf("/note" to JsonPrimitive("author-note")),
        )
        assertThat(otherOutcome).isInstanceOf(ConflictResolveOutcome.Forbidden::class.java)
        fixture.pullAll(clients)
        assertThat(requireSingleOpenConflict(clients, seeded.uuid)).isEqualTo(conflictId)

        val (_, authorOutcome) = author.resolveOpenConflict(
            conflictId = conflictId,
            preferValueByPath = mapOf("/note" to JsonPrimitive("author-note")),
        )
        assertThat(authorOutcome).isInstanceOf(ConflictResolveOutcome.Forbidden::class.java)
        fixture.pullAll(clients)
        assertThat(requireSingleOpenConflict(clients, seeded.uuid)).isEqualTo(conflictId)

        val (_, ownerOutcome) = owner.resolveOpenConflict(
            conflictId = conflictId,
            preferValueByPath = mapOf("/note" to JsonPrimitive("owner-note")),
        )
        assertThat(ownerOutcome).isInstanceOf(ConflictResolveOutcome.Accepted::class.java)
        fixture.pullAll(clients)
        val ownerFact = owner.stableRecordFact(seeded.uuid)
        assertThat(ownerFact.note).isEqualTo("owner-note")
        assertThat(ownerFact.openConflictId).isNull()
        assertThat(ownerFact.syncDirty).isFalse()
        clients.forEach { client ->
            assertThat(client.stableRecordFact(seeded.uuid)).isEqualTo(ownerFact)
        }

        return CaseEvidence(
            caseId = "C6-author-owner-other-acl",
            orders = listOf("other-forbidden", "author-forbidden", "owner-allowed"),
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
