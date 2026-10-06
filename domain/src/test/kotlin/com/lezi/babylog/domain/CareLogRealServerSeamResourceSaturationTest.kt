package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.domain.carelog.ConflictResolveOutcome
import com.lezi.babylog.domain.carelog.ConflictResolverAudience
import com.lezi.babylog.domain.carelog.ConflictResolverAvailability
import com.lezi.babylog.domain.carelog.ConflictResolverDraft
import com.lezi.babylog.sync.backend.ConflictResolutionChoice
import com.lezi.babylog.sync.backend.ConflictResolveRequest
import com.lezi.babylog.sync.conflict.ConflictRootType
import com.lezi.babylog.sync.conflict.ConflictSnapshotCodec
import com.lezi.babylog.sync.conflict.ConflictSnapshotPaging
import com.lezi.babylog.sync.conflict.ConflictSnapshotValidation
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test

/**
 * H36 acceptance: one CareLog → real-server saturation smoke.
 *
 * Consumes external R12/R17/R18/R19 contracts without re-running their unit matrices:
 * - R12 open-branch cap 64 + typed `causal_open_branch_limit_reached`
 * - R17/R18 full-set paged snapshot (16 heads/page) and partial resolution closed
 * - R19 resolution metadata retention after 24h grace with replay/provenance kept
 *
 * External exact-HEAD receipts (implementation landings):
 * - R12 `c1e05d6b3996f93549c3562961ddda356c40b605`
 * - R17 `86c7fab0d37ce3ab7ae6e6a4e261d46d835476f8`
 * - R18 `0e4f429ffbc77851339d0c8b02ce235fea6136ee`
 * - R19 `a5b257d2e529206e5bf42eae18d38bc22efdaa2d`
 *
 * Requires a current lezi-sync binary. Never touches family NAS paths or production certs.
 */
class CareLogRealServerSeamResourceSaturationTest {

    @Test
    fun branchCapPagedSnapshotAndRetentionAcrossCareLogSeam() = runBlocking {
        CareLogRealServerSeamFixture.open().use { fixture ->
            val owner = fixture.owner
            val member = fixture.member
            val clients = listOf(owner, member)

            val babyId = owner.careLog.createBaby(
                CreateBabyInput(
                    nickname = "H36饱和",
                    birthdayEpochDay = 19_000L,
                    sex = "female",
                ),
            )
            owner.settleLocalWrite()
            val baby = requireNotNull(owner.fakes.babies.get(babyId))
            fixture.pullAll(clients)

            val recordUuid = "33333333-3333-4333-8333-333333333336"
            val localBabyId = requireNotNull(
                owner.fakes.babies.listAll().firstOrNull { it.clientUuid == baby.clientUuid }?.id,
            )
            owner.clock.now = owner.clock.nowMillis() + 1_000L
            val ts = owner.clock.nowMillis() - 30_000L
            owner.careLog.addRecord(
                babyId = localBabyId,
                type = RecordType.FORMULA,
                timestamp = ts,
                note = "h36-base",
                payloadJson = formulaPayloadJson(amountMl = 90),
                nowMillis = owner.clock.nowMillis(),
                clientUuid = recordUuid,
            )
            owner.settleLocalWrite()
            val created = requireNotNull(owner.recordByClientUuid(recordUuid))
            val createBase = requireNotNull(created.baseVersion)
            fixture.pullAll(clients)

            // Advance stable away from create so concurrent edits fork from one frozen base.
            owner.clock.now = owner.clock.nowMillis() + 1_000L
            owner.careLog.updateRecord(
                id = created.id,
                timestamp = ts,
                endTimestamp = null,
                note = "h36-stable",
                payloadJson = formulaPayloadJson(amountMl = 90),
                nowMillis = owner.clock.nowMillis(),
            )
            owner.settleLocalWrite()
            val stableRow = requireNotNull(owner.recordByClientUuid(recordUuid))
            val stableBase = requireNotNull(stableRow.baseVersion)
            assertThat(stableBase).isNotEqualTo(createBase)
            fixture.pullAll(clients)
            clients.forEach { client ->
                val row = requireNotNull(client.recordByClientUuid(recordUuid))
                assertThat(row.baseVersion).isEqualTo(stableBase)
                assertThat(row.syncDirty).isFalse()
                assertThat(row.openConflictId).isNull()
            }

            // R12: fill the production open-branch cap with sequential same-base forks.
            // Concurrent base is the create version (parent of the advanced stable), matching
            // server seed_concurrent_record / HTTP branch-cap smoke.
            val forkBase = createBase
            val branchNotes = ArrayList<String>(OPEN_BRANCH_CAP)
            for (index in 1..OPEN_BRANCH_CAP) {
                val note = "h36-branch-${index.toString().padStart(2, '0')}"
                branchNotes += note
                owner.clock.now = owner.clock.nowMillis() + 1_000L
                val current = requireNotNull(owner.recordByClientUuid(recordUuid))
                // Pin transport base to the pre-stable create head so each edit is a
                // concurrent branch rather than a linear rewrite of the latest stable.
                owner.fakes.records.update(
                    current.copy(
                        baseVersion = forkBase,
                        openConflictId = null,
                        localBranchVersionId = null,
                        mutationId = null,
                    ),
                )
                owner.careLog.updateRecord(
                    id = current.id,
                    timestamp = ts,
                    endTimestamp = null,
                    note = note,
                    payloadJson = formulaPayloadJson(amountMl = 90),
                    nowMillis = owner.clock.nowMillis(),
                )
                val dirty = requireNotNull(owner.recordByClientUuid(recordUuid))
                assertThat(dirty.syncDirty).isTrue()
                assertThat(dirty.baseVersion).isEqualTo(forkBase)
                owner.settleLocalWrite()
                val settled = requireNotNull(owner.recordByClientUuid(recordUuid))
                assertThat(settled.syncDirty).isFalse()
                assertThat(settled.openConflictId).isNotNull()
                // Branched settlement keeps the server's current stable as baseVersion.
                assertThat(settled.baseVersion).isEqualTo(stableBase)
            }

            val conflictId = requireNotNull(owner.openConflictIdForRecord(recordUuid))
            fixture.pullAll(clients)

            // Cap reached: a 65th concurrent fork stays dirty / honest pending.
            owner.clock.now = owner.clock.nowMillis() + 1_000L
            val beforeOverflow = requireNotNull(owner.recordByClientUuid(recordUuid))
            owner.fakes.records.update(
                beforeOverflow.copy(
                    baseVersion = forkBase,
                    openConflictId = null,
                    localBranchVersionId = null,
                    mutationId = null,
                ),
            )
            owner.careLog.updateRecord(
                id = beforeOverflow.id,
                timestamp = ts,
                endTimestamp = null,
                note = "h36-branch-overflow",
                payloadJson = formulaPayloadJson(amountMl = 90),
                nowMillis = owner.clock.nowMillis(),
            )
            val overflowDirty = requireNotNull(owner.recordByClientUuid(recordUuid))
            assertThat(overflowDirty.syncDirty).isTrue()
            assertThat(overflowDirty.note).isEqualTo("h36-branch-overflow")
            assertThat(overflowDirty.baseVersion).isEqualTo(forkBase)

            val overflow = runCatching { owner.settleLocalWrite() }.exceptionOrNull()
            assertThat(overflow).isNotNull()
            assertSaturationFailure(overflow!!)

            val afterOverflow = requireNotNull(owner.recordByClientUuid(recordUuid))
            assertThat(afterOverflow.syncDirty).isTrue()
            assertThat(afterOverflow.note).isEqualTo("h36-branch-overflow")
            assertThat(afterOverflow.baseVersion).isEqualTo(forkBase)

            // Restore the last accepted branched fact so subsequent detail/resolution
            // is not blocked by the saturated pending edit.
            owner.fakes.records.update(
                afterOverflow.copy(
                    note = branchNotes.last(),
                    syncDirty = false,
                    mutationId = null,
                    baseVersion = stableBase,
                    openConflictId = conflictId,
                    localBranchVersionId = afterOverflow.localBranchVersionId,
                ),
            )
            assertThat(owner.openConflictIdForRecord(recordUuid)).isEqualTo(conflictId)

            fixture.pullAll(clients)
            clients.forEach { client ->
                assertThat(client.openConflictIdForRecord(recordUuid)).isEqualTo(conflictId)
            }

            val inbox = owner.careLog.observeOpenConflictInbox().first()
            assertThat(inbox.count).isAtLeast(1)
            assertThat(inbox.items.map { it.conflictId }).contains(conflictId)
            val inboxItem = inbox.items.single { it.conflictId == conflictId }
            assertThat(inboxItem.clientUuid).isEqualTo(recordUuid)
            assertThat(inboxItem.rootType).isEqualTo(ConflictRootType.Record)

            // R17/R18: full-set pages assemble every durable branch; incomplete draft is closed.
            val complete = try {
                owner.port.fetchConflictSnapshot(conflictId)
            } catch (error: Throwable) {
                throw AssertionError(
                    "fetchConflictSnapshot failed for $conflictId: " +
                        generateSequence(error) { it.cause }
                            .joinToString(" <- ") { "${it::class.simpleName}: ${it.message}" },
                    error,
                )
            }
            assertThat(complete.complete).isTrue()
            assertThat(complete.pageIndex).isEqualTo(0)
            assertThat(complete.continuation).isNull()
            assertThat(complete.branches).hasSize(OPEN_BRANCH_CAP)
            assertThat(complete.branchVersionIds).hasSize(OPEN_BRANCH_CAP)
            assertThat(complete.branchVersionIds.toSet()).hasSize(OPEN_BRANCH_CAP)
            val expectedPages =
                (OPEN_BRANCH_CAP + ConflictSnapshotValidation.MAX_BRANCHES_PER_PAGE - 1) /
                    ConflictSnapshotValidation.MAX_BRANCHES_PER_PAGE
            assertThat(expectedPages).isEqualTo(4)
            assertThat(ConflictSnapshotValidation.MAX_BRANCHES_PER_PAGE).isEqualTo(16)
            assertThat(ConflictSnapshotPaging.MAX_BRANCHES_PER_SNAPSHOT)
                .isEqualTo(OPEN_BRANCH_CAP)

            val incompletePage = complete.copy(
                branches = complete.branches.take(ConflictSnapshotValidation.MAX_BRANCHES_PER_PAGE),
                pageIndex = 0,
                continuation = "c".repeat(43),
                complete = false,
            )
            assertThat(incompletePage.complete).isFalse()
            val incompleteOpen = runCatching {
                ConflictResolverDraft.open(
                    snapshot = incompletePage,
                    audience = ConflictResolverAudience(
                        membershipId = owner.currentSession().membershipId,
                        isOwner = true,
                    ),
                    fetchedOnline = true,
                    nowMillis = owner.clock.nowMillis(),
                    resolutionMutationId = UUID.randomUUID().toString(),
                )
            }
            assertThat(incompleteOpen.isFailure).isTrue()
            assertThat(incompleteOpen.exceptionOrNull()?.message.orEmpty())
                .contains("完整 ConflictSnapshot")

            val completeDraft = ConflictResolverDraft.open(
                snapshot = complete,
                audience = ConflictResolverAudience(
                    membershipId = owner.currentSession().membershipId,
                    isOwner = true,
                ),
                fetchedOnline = true,
                nowMillis = owner.clock.nowMillis(),
                resolutionMutationId = UUID.randomUUID().toString(),
            )
            assertThat(completeDraft.canSubmit).isTrue()
            assertThat(completeDraft.selectedVersionId).isEqualTo(complete.stable.versionId)
            assertThat(runCatching { completeDraft.command() }.isFailure).isTrue()
            assertThat(completeDraft.model.availability)
                .isEqualTo(ConflictResolverAvailability.Current)

            val preferredNote = branchNotes.first()
            val resolutionMutationId = UUID.randomUUID().toString()
            // Prefer the already-assembled complete snapshot: CareLog force-refresh
            // swallows transport errors, and a second 4-page load is unnecessary here.
            val choices = complete.conflicting.map { path ->
                val preferred = path.candidates.firstOrNull { candidate ->
                    val outcome = candidate.outcome
                    outcome is com.lezi.babylog.sync.conflict.ConflictOutcome.Set &&
                        outcome.value == JsonPrimitive(preferredNote)
                } ?: path.candidates.first()
                ConflictResolutionChoice(path = path.path, choiceId = preferred.choiceId)
            }
            val resolveRequest = ConflictResolveRequest(
                snapshotToken = complete.snapshotToken,
                resolutionMutationId = resolutionMutationId,
                choices = choices,
            )
            // Coordinator.resolve re-reads the local complete projection by token.
            check(owner.fakes.conflictSnapshotCache.get(conflictId) != null) {
                "complete snapshot must be promoted before resolve"
            }
            val outcome = owner.careLog.resolveConflict(
                conflictId = conflictId,
                request = resolveRequest,
            )
            assertThat(outcome).isInstanceOf(ConflictResolveOutcome.Accepted::class.java)
            val acceptedVersion =
                (outcome as ConflictResolveOutcome.Accepted).stableVersionId
            fixture.pullAll(clients)

            val resolved = owner.stableRecordFact(recordUuid)
            assertThat(resolved.note).isEqualTo(preferredNote)
            assertThat(resolved.openConflictId).isNull()
            assertThat(resolved.syncDirty).isFalse()
            assertThat(resolved.baseVersion).isEqualTo(acceptedVersion)
            clients.forEach { client ->
                assertThat(client.stableRecordFact(recordUuid)).isEqualTo(resolved)
            }
            assertThat(
                owner.careLog.observeOpenConflictInbox().first()
                    .items.none { it.conflictId == conflictId },
            ).isTrue()

            // R19: advance retention markers past the 24h grace and trigger GC via resolve replay.
            val db = File(fixture.server.dataRoot, "lezi.db")
            assertThat(db.isFile).isTrue()
            val before = queryResourceCounts(db, conflictId)
            assertThat(before.branchRows).isEqualTo(OPEN_BRANCH_CAP.toLong())
            assertThat(before.snapshotReceipts).isEqualTo(1L)
            assertThat(before.resolutionRows).isEqualTo(1L)
            assertThat(before.resolvedConflicts).isEqualTo(1L)
            assertThat(before.pendingMarkers).isEqualTo(1L)

            advanceRetentionMarkers(db)
            // Exact-replay through the public port (same mutation + choices) triggers the
            // resolve-path retention sweep without needing a local complete cache. The
            // server runs the sweep fire-and-forget after responding, so the
            // compaction is awaited here instead of assumed synchronous.
            val replay = owner.port.resolveConflict(conflictId, resolveRequest)
            assertThat(replay).isInstanceOf(
                com.lezi.babylog.sync.backend.ConflictResolveResult.Accepted::class.java,
            )
            val replayAccepted =
                replay as com.lezi.babylog.sync.backend.ConflictResolveResult.Accepted
            assertThat(replayAccepted.stableVersionId).isEqualTo(acceptedVersion)
            assertThat(replayAccepted.replay).isTrue()

            val after = awaitRetentionSweep(db, conflictId)
            assertThat(after.branchRows).isEqualTo(0L)
            assertThat(after.snapshotReceipts).isEqualTo(0L)
            assertThat(after.resolutionRows).isEqualTo(1L)
            assertThat(after.resolvedConflicts).isEqualTo(1L)
            assertThat(after.stableHeads).isEqualTo(1L)
            assertThat(after.completeMarkers).isEqualTo(1L)
            assertThat(after.pendingMarkers).isEqualTo(0L)

            // Stable fact/replay/provenance survive compaction.
            fixture.pullAll(clients)
            clients.forEach { client ->
                assertThat(client.stableRecordFact(recordUuid)).isEqualTo(resolved)
            }
            val thirdReplay = owner.port.resolveConflict(conflictId, resolveRequest)
            assertThat(thirdReplay).isInstanceOf(
                com.lezi.babylog.sync.backend.ConflictResolveResult.Accepted::class.java,
            )
            val thirdAccepted =
                thirdReplay as com.lezi.babylog.sync.backend.ConflictResolveResult.Accepted
            assertThat(thirdAccepted.stableVersionId).isEqualTo(acceptedVersion)
            assertThat(thirdAccepted.replay).isTrue()

            // Content drift after compaction still fail-closed on the preserved audit receipt.
            val drift = owner.port.resolveConflict(
                conflictId,
                ConflictResolveRequest(
                    snapshotToken = complete.snapshotToken,
                    resolutionMutationId = UUID.randomUUID().toString(),
                    choices = complete.conflicting.map { path ->
                        ConflictResolutionChoice(
                            path = path.path,
                            choiceId = "A".repeat(43),
                        )
                    },
                ),
            )
            assertThat(drift).isInstanceOf(
                com.lezi.babylog.sync.backend.ConflictResolveResult.Rejected::class.java,
            )
            val rejected = drift as com.lezi.babylog.sync.backend.ConflictResolveResult.Rejected
            assertThat(rejected.code).isAnyOf(
                "content_drift",
                "invalid_choice",
                "invalid_snapshot_token",
                "snapshot_stale",
                "snapshot_expired",
            )
            clients.forEach { client ->
                assertThat(client.stableRecordFact(recordUuid)).isEqualTo(resolved)
            }
        }
    }

    private fun assertSaturationFailure(error: Throwable) {
        val chain = generateSequence(error) { it.cause }.toList()
        val text = chain.joinToString(" | ") { listOfNotNull(it.message, it.toString()).joinToString(" ") }
        assertThat(text).ignoringCase().contains("fail")
        val mentionsLimit =
            text.contains(OPEN_BRANCH_LIMIT_CODE) ||
                text.contains("\"scope\":\"root\"") ||
                text.contains("429") ||
                text.contains("TOO_MANY") ||
                text.contains("Throttl") ||
                text.contains("操作太频繁") ||
                text.contains("retry") ||
                text.contains("Retry")
        assertThat(mentionsLimit).isTrue()
    }

    /** The resolve-path sweep runs on a spawned background task; poll to its
     *  terminal shape (branches and pending markers compacted, durable
     *  resolution evidence kept) with a bounded deadline. */
    private fun awaitRetentionSweep(db: File, conflictId: String): ResourceCounts {
        val deadline = System.currentTimeMillis() + 10_000L
        var counts = queryResourceCounts(db, conflictId)
        while (
            System.currentTimeMillis() < deadline &&
            (counts.branchRows != 0L || counts.pendingMarkers != 0L)
        ) {
            Thread.sleep(50L)
            counts = queryResourceCounts(db, conflictId)
        }
        return counts
    }

    private data class ResourceCounts(
        val branchRows: Long,
        val snapshotReceipts: Long,
        val resolutionRows: Long,
        val resolvedConflicts: Long,
        val stableHeads: Long,
        val pendingMarkers: Long,
        val completeMarkers: Long,
    )

    private fun queryResourceCounts(db: File, conflictId: String): ResourceCounts {
        fun scalar(sql: String): Long {
            val process = ProcessBuilder(
                "sqlite3",
                db.absolutePath,
                sql,
            ).redirectErrorStream(true).start()
            val out = process.inputStream.readBytes().toString(Charsets.UTF_8).trim()
            check(process.waitFor() == 0) { "sqlite3 failed: $out sql=$sql" }
            return out.toLong()
        }
        val safeId = conflictId.replace("'", "''")
        return ResourceCounts(
            branchRows = scalar(
                "SELECT COUNT(*) FROM conflict_branches WHERE conflict_id='$safeId';",
            ),
            snapshotReceipts = scalar(
                "SELECT COUNT(*) FROM mutation_receipts " +
                    "WHERE membership_id='__conflict_snapshot_v2__' AND conflict_id='$safeId';",
            ),
            resolutionRows = scalar(
                "SELECT COUNT(*) FROM conflict_resolutions WHERE conflict_id='$safeId';",
            ),
            resolvedConflicts = scalar(
                "SELECT COUNT(*) FROM conflicts " +
                    "WHERE conflict_id='$safeId' AND status='resolved';",
            ),
            stableHeads = scalar(
                "SELECT COUNT(*) FROM entity_stable_heads " +
                    "WHERE entity_type='record' AND client_uuid=" +
                    "(SELECT client_uuid FROM conflicts WHERE conflict_id='$safeId' LIMIT 1);",
            ),
            pendingMarkers = scalar(
                "SELECT COUNT(*) FROM mutation_receipts " +
                    "WHERE membership_id='__conflict_retention_v2__' " +
                    "AND conflict_id='$safeId' " +
                    "AND mutation_id LIKE 'retention-pending:%';",
            ),
            completeMarkers = scalar(
                "SELECT COUNT(*) FROM mutation_receipts " +
                    "WHERE membership_id='__conflict_retention_v2__' " +
                    "AND conflict_id='$safeId' " +
                    "AND mutation_id LIKE 'retention-complete:%';",
            ),
        )
    }

    /**
     * Rewrite due markers so [eligible_at] is now, without waiting real 24h wall time.
     * Content hash is recomputed with the same framed SHA-256 owner as production.
     */
    private fun advanceRetentionMarkers(db: File) {
        val listProcess = ProcessBuilder(
            "sqlite3",
            "-json",
            db.absolutePath,
            "SELECT mutation_id, receipt_json, conflict_id FROM mutation_receipts " +
                "WHERE membership_id='__conflict_retention_v2__' " +
                "AND mutation_id LIKE 'retention-pending:%';",
        ).redirectErrorStream(true).start()
        val listOut = listProcess.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        check(listProcess.waitFor() == 0) { "list markers failed: $listOut" }
        if (listOut.isEmpty() || listOut == "[]") {
            error("expected retention-pending markers before GC, got: $listOut")
        }
        val rows = when (val parsed = Json.parseToJsonElement(listOut)) {
            is JsonArray -> parsed
            else -> error("expected JSON array, got $listOut")
        }
        check(rows.isNotEmpty()) { "no retention-pending markers: $listOut" }

        // pending_marker() rebuilds eligible_at = conflicts.resolved_at + 24h and requires
        // byte-equal marker fields. Move resolved_at into the past, then rewrite the marker.
        val nowSecs = System.currentTimeMillis() / 1000L
        val resolvedAt = nowSecs - RETENTION_GRACE_SECONDS - 120L
        val eligibleAt = resolvedAt + RETENTION_GRACE_SECONDS

        for (row in rows) {
            val obj = row.jsonObject
            val oldKey = obj.getValue("mutation_id").jsonPrimitive.content
            val receiptRaw = obj.getValue("receipt_json").jsonPrimitive.content
            val conflictId = obj.getValue("conflict_id").jsonPrimitive.content
            val receipt = Json.parseToJsonElement(receiptRaw).jsonObject
            val oldEligible = receipt.getValue("eligible_at").jsonPrimitive.content.toLong()
            val rewrittenText = receiptRaw.replace(
                Regex(""""eligible_at"\s*:\s*$oldEligible"""),
                """"eligible_at":$eligibleAt""",
            )
            check(rewrittenText != receiptRaw) {
                "failed to rewrite eligible_at in $receiptRaw"
            }
            val newKey = "retention-pending:${sortableTime(eligibleAt)}:$conflictId"
            val hash = migrationContentHash(
                listOf("conflict-retention-v2", "marker", rewrittenText),
            )
            val escapedJson = rewrittenText.replace("'", "''")
            val escapedOld = oldKey.replace("'", "''")
            val safeConflict = conflictId.replace("'", "''")
            val sql =
                "UPDATE conflicts SET resolved_at=$resolvedAt " +
                    "WHERE conflict_id='$safeConflict';" +
                    "UPDATE mutation_receipts SET mutation_id='$newKey', " +
                    "content_hash='$hash', receipt_json='$escapedJson' " +
                    "WHERE membership_id='__conflict_retention_v2__' " +
                    "AND mutation_id='$escapedOld';"
            val update = ProcessBuilder(
                "sqlite3",
                db.absolutePath,
                sql,
            ).redirectErrorStream(true).start()
            val updateOut = update.inputStream.readBytes().toString(Charsets.UTF_8).trim()
            check(update.waitFor() == 0) { "update marker failed: $updateOut sql=$sql" }
        }
    }
    private fun sortableTime(value: Long): String {
        val mixed = value.toULong() xor (1uL shl 63)
        return mixed.toString(16).padStart(16, '0')
    }

    private fun migrationContentHash(parts: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (part in parts) {
            digest.update(part.toByteArray(Charsets.UTF_8))
            digest.update(0xff.toByte())
        }
        return digest.digest().joinToString("") { b -> "%02x".format(b) }
    }

    companion object {
        /** R12 production open-branch cap. */
        const val OPEN_BRANCH_CAP = 64
        const val OPEN_BRANCH_LIMIT_CODE = "causal_open_branch_limit_reached"
        /** R19 resolved-metadata grace (documented pin; test advances markers). */
        const val RETENTION_GRACE_SECONDS = 24L * 60L * 60L
    }
}
