package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.domain.carelog.SourceRelationOutcome
import com.lezi.babylog.domain.timeline.TimelineWindowRepository
import com.lezi.babylog.domain.timeline.TimelineWindowRequest
import com.lezi.babylog.sync.conflict.ConflictRoot
import com.lezi.babylog.sync.conflict.ConflictRootType
import com.lezi.babylog.sync.session.FamilyEndpointConfig
import com.lezi.babylog.sync.session.SetupFamilyState
import com.lezi.babylog.sync.session.SetupProbeResult
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * US093/R4 + US094/R5 consumer proofs over two independently created ordinary server processes.
 * All family roots, source relations, restore batches and conflicts come from public HTTP flows.
 * The shared seam uses in-memory DAOs, with production CareLog, snapshot files, RealSyncPort,
 * HttpSyncBackend, conflict codec and timeline/stat projections. This is not a Room/device proof.
 */
class CareLogRealServerSeamDisasterRestoreTest {
    @Test(timeout = 180_000)
    fun manualDisplayAndAutomaticGroupSurviveCaptureRestoreAndFreshPeerReadModels() = runBlocking {
        assertSourceRelationsSurviveRestore(DisplayHistory.Live)
    }

    @Test(timeout = 180_000)
    fun tombstonedManualDisplaySurvivesWithoutRevivingItsHiddenSource() = runBlocking {
        assertSourceRelationsSurviveRestore(DisplayHistory.Deleted)
    }

    @Test(timeout = 180_000)
    fun manualDisplayMovedOutsideNeighborWindowKeepsItsHistoricalGroup() = runBlocking {
        assertSourceRelationsSurviveRestore(DisplayHistory.Moved)
    }

    @Test(timeout = 180_000)
    fun restoredBabyAcceptedEditAndStaleBranchDecodeWithNewOwnerCreator() = runBlocking {
        CareLogRealServerSeamFixture.open(RealServerSetupProbeFactory.create()).use { original ->
            IsolatedLeziSyncServer.start().use { replacement ->
                val owner = original.owner
                val babyId = owner.careLog.createBaby(
                    CreateBabyInput("Before disaster", sex = "female", birthdayEpochDay = 20_000L),
                )
                owner.settleLocalWrite()
                val babyUuid = requireNotNull(owner.fakes.babies.get(babyId)).clientUuid
                val previousOwner = owner.currentSession().membershipId
                restoreInto(owner, replacement)
                val restoredOwner = owner.currentSession().membershipId
                assertThat(restoredOwner).isNotEqualTo(previousOwner)
                owner.foreground.setForeground(true)
                owner.pullForeground()
                val peer = joinFreshOwner(replacement, "baby-branch-peer")
                try {
                    val baseline = requireNotNull(owner.babyByClientUuid(babyUuid)?.baseVersion)
                    assertThat(peer.babyByClientUuid(babyUuid)?.baseVersion).isEqualTo(baseline)
                    // Freeze the second consumer before the first edit, then author its stale base
                    // through CareLog while offline. No fabricated root or version enters the DB.
                    peer.foreground.setForeground(false)
                    owner.clock.now += 1_000L
                    owner.careLog.updateBabyProfile(
                        babyId,
                        UpdateBabyInput("Accepted edit", sex = "female", birthdayEpochDay = 20_000L),
                    )
                    owner.settleLocalWrite()
                    val accepted = requireNotNull(owner.babyByClientUuid(babyUuid))
                    assertThat(accepted.baseVersion).isNotEqualTo(baseline)
                    assertThat(accepted.openConflictId).isNull()
                    assertThat(accepted.syncDirty).isFalse()
                    assertThat(peer.babyByClientUuid(babyUuid)?.baseVersion).isEqualTo(baseline)
                    peer.clock.now += 2_000L
                    peer.careLog.updateBabyProfile(
                        requireNotNull(peer.babyByClientUuid(babyUuid)).id,
                        UpdateBabyInput("Stale branch", sex = "female", birthdayEpochDay = 20_000L),
                    )
                    assertThat(peer.babyByClientUuid(babyUuid)?.baseVersion).isEqualTo(baseline)
                    peer.foreground.setForeground(true)
                    peer.settleLocalWrite()
                    owner.pullForeground()
                    peer.pullForeground()
                    val conflictId = requireNotNull(owner.openConflictIdForBaby(babyUuid))
                    assertThat(peer.openConflictIdForBaby(babyUuid)).isEqualTo(conflictId)
                    for (consumer in listOf(owner, peer)) {
                        // CareLog -> RealSyncPort -> public conflict_detail -> strict production
                        // ConflictSnapshotCodec. A missing creator makes this call fail closed.
                        val loaded = consumer.loadConflict(conflictId, forceRefresh = true)
                        assertThat(loaded.fetchedOnline).isTrue()
                        val snapshot = loaded.snapshot
                        assertThat(snapshot.entityType).isEqualTo(ConflictRootType.Baby)
                        assertThat(snapshot.clientUuid).isEqualTo(babyUuid)
                        assertThat(snapshot.complete).isTrue()
                        assertThat(snapshot.branches).hasSize(1)
                        assertThat(snapshot.stable.versionId).isEqualTo(accepted.baseVersion)
                        assertThat((snapshot.stable.root as ConflictRoot.Baby).nickname)
                            .isEqualTo("Accepted edit")
                        assertThat((snapshot.branches.single().root as ConflictRoot.Baby).nickname)
                            .isEqualTo("Stale branch")
                        assertThat(snapshot.branches.single().baseVersion).isEqualTo(baseline)
                        for (version in listOf(snapshot.stable) + snapshot.branches) {
                            val root = version.root as ConflictRoot.Baby
                            assertThat(root.createdByMembershipId).isEqualTo(restoredOwner)
                            assertThat(root.createdByMembershipId).isNotEqualTo(previousOwner)
                        }
                        assertThat(snapshot.conflicting.map { it.path }).contains("/nickname")
                    }
                } finally {
                    peer.foreground.setForeground(false)
                    peer.close()
                }
            }
        }
    }

    private suspend fun assertSourceRelationsSurviveRestore(history: DisplayHistory) {
        CareLogRealServerSeamFixture.open(RealServerSetupProbeFactory.create()).use { original ->
            IsolatedLeziSyncServer.start().use { replacement ->
                val owner = original.owner
                val member = original.member
                val babyId = owner.careLog.createBaby(CreateBabyInput("Restored family", birthdayEpochDay = 20_000L))
                owner.settleLocalWrite()
                original.pullAll()
                val babyUuid = requireNotNull(owner.fakes.babies.get(babyId)).clientUuid
                val day = LocalDate.now(ZoneOffset.UTC).minusDays(1)
                val morning = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 6 * HOUR
                val manual = seedAutoPair(original, babyUuid, morning, 120, 90)
                val automatic = seedAutoPair(original, babyUuid, morning + 3 * HOUR, 70, 95)
                val independent = addFormula(owner, babyUuid, morning + 6 * HOUR, 40)
                original.pullAll()
                // The ordinary binary auto-aligns: Owner root wins over the Member root.
                assertThat(owner.careLog.sourceRoleClientUuids()).containsExactly(manual.source, automatic.source)
                assertThat(owner.careLog.autoAlignedDisplayClientUuids())
                    .containsExactly(manual.display, automatic.display)
                val supersededAutoRelationId = owner.fakes.sourceRelations.listAll()
                    .single { it.displayClientUuid == manual.display }.relationId
                val selection = owner.careLog.resolveSuspectedDuplicateGroupAsOwner(
                    listOf(manual.display, manual.source), manual.source,
                )
                assertThat(selection).isInstanceOf(SourceRelationOutcome.Accepted::class.java)
                original.pullAll()
                when (history) {
                    DisplayHistory.Live -> Unit
                    DisplayHistory.Deleted -> {
                        member.careLog.deleteRecord(requireNotNull(member.recordByClientUuid(manual.source)).id)
                        member.settleLocalWrite()
                    }
                    DisplayHistory.Moved -> {
                        val chosen = requireNotNull(member.recordByClientUuid(manual.source))
                        member.clock.now += 1_000L
                        member.careLog.updateRecord(chosen.id, morning + HOUR, null, chosen.note,
                            chosen.payloadJson, nowMillis = member.clock.nowMillis())
                        member.settleLocalWrite()
                    }
                }
                original.pullAll()
                // Canonical replacement moves memberships while retaining the superseded header
                // as local equality evidence. It is deliberately absent from the restore wire.
                val activeBefore = owner.fakes.sourceRelations.listAllMembers().map { it.relationId }.toSet()
                assertThat(owner.fakes.sourceRelations.listAll().filter { it.relationId !in activeBefore }
                    .map { it.relationId }).containsExactly(supersededAutoRelationId)
                val expected = readModel(owner, babyUuid, day, "before restore")
                val expectedDisplay = setOf(automatic.display, independent) +
                    if (history == DisplayHistory.Deleted) emptySet() else setOf(manual.source)
                assertThat(expected.displayUuids).containsExactlyElementsIn(expectedDisplay)
                assertThat(expected.sourceUuids).containsExactly(manual.display, automatic.source)
                assertThat(expected.autoDisplayUuids).containsExactly(automatic.display)
                assertThat(expected.formulaMl).isEqualTo(if (history == DisplayHistory.Deleted) 110 else 200)
                assertThat(expected.groups).hasSize(2)
                assertThat(expected.groups.single { it.display == manual.source }.members)
                    .containsExactly(manual.source to "display", manual.display to "source")
                assertThat(expected.groups.single { it.display == manual.source }.auto).isFalse()
                assertThat(expected.groups.single { it.display == automatic.display }.auto).isTrue()
                val previousOwner = owner.currentSession().membershipId
                restoreInto(owner, replacement)
                assertThat(owner.currentSession().membershipId).isNotEqualTo(previousOwner)
                // Automatic pull is paused: this checks the actual local activation projection.
                assertThat(readModel(owner, babyUuid, day, "local activation")).isEqualTo(expected)
                owner.foreground.setForeground(true)
                owner.pullForeground()
                assertThat(readModel(owner, babyUuid, day, "restored owner pull")).isEqualTo(expected)
                val peer = joinFreshOwner(replacement, "relation-peer")
                try {
                    assertThat(readModel(peer, babyUuid, day, "fresh peer pull")).isEqualTo(expected)
                    for (client in listOf(owner, peer)) {
                        assertThat(client.fakes.sourceRelations.listAll().map { it.relationId }.toSet())
                            .isEqualTo(expected.groups.map { it.id }.toSet())
                        assertThat(client.recordByClientUuid(manual.source)?.deletedAt != null)
                            .isEqualTo(history == DisplayHistory.Deleted)
                        assertThat(client.recordByClientUuid(manual.display)?.deletedAt).isNull()
                        assertThat(client.fakes.records.listAllIncludingDeleted()).hasSize(5)
                        assertThat(client.fakes.records.listPendingSync()).isEmpty()
                        assertThat(client.careLog.listOpenSuspectedDuplicateGroups(
                            client.careLog.observeDayRecords(requireNotNull(client.babyByClientUuid(babyUuid)).id,
                                day, ZoneOffset.UTC).first(),
                        )).isEmpty()
                    }
                } finally {
                    peer.foreground.setForeground(false)
                    peer.close()
                }
            }
        }
    }

    private suspend fun restoreInto(owner: SeamClient, replacement: IsolatedLeziSyncServer) {
        val before = owner.currentSession()
        val endpoint = TrustedEndpointProfile.tofuSpki(replacement.origin, replacement.spkiSha256Base64)
        assertThat(endpoint.origin).isNotEqualTo(before.baseUrl)
        assertThat(owner.preferences.currentEndpoint()?.spkiSha256).isNotEqualTo(endpoint.spkiSha256)
        val probe = owner.port.verifyEndpoint(endpoint).getOrThrowReady()
        assertThat(probe.familyState).isEqualTo(SetupFamilyState.Empty)
        assertThat(probe.capabilities).contains("restore_authority_v1")
        val prepared = owner.port.startDisasterRecovery(endpoint, "Recovered owner", "Restored phone",
            replacement.bootstrapSecret).getOrThrow()
        assertThat(prepared.status).isEqualTo("ready_to_commit")
        val batch = requireNotNull(owner.preferences.disasterRestoreCheckpoint.first())
        assertThat(batch.familyId).isEqualTo(before.familyId)
        owner.foreground.setForeground(false)
        val committed = owner.port.commitDisasterRecovery(replacement.bootstrapSecret).getOrThrow()
        assertThat(committed.sessionPresentation.familyId).isEqualTo(before.familyId)
        assertThat(owner.currentSession().baseUrl).isEqualTo(replacement.origin)
        assertThat(owner.currentSession().isJoined).isTrue()
        assertThat(owner.preferences.disasterRestoreCheckpoint.first()).isNull()
    }

    private suspend fun joinFreshOwner(server: IsolatedLeziSyncServer, label: String): SeamClient {
        val endpoint = TrustedEndpointProfile.tofuSpki(server.origin, server.spkiSha256Base64)
        val peer = SeamClient.create(label, endpoint, "restore-$label-${UUID.randomUUID()}",
            setupProbeOverride = RealServerSetupProbeFactory.create())
        try {
            assertThat(peer.fakes.records.listAllIncludingDeleted()).isEmpty()
            assertThat(peer.fakes.sourceRelations.listAll()).isEmpty()
            peer.port.saveEndpointConfig(FamilyEndpointConfig.fromBaseUrl(server.origin)).getOrThrow()
            peer.port.ownerLogin("Fresh peer phone", server.bootstrapSecret, takeover = false).getOrThrow()
            peer.awaitIdle()
            peer.seedLocalFamilyAnchor()
            peer.pullForeground()
            return peer
        } catch (error: Throwable) {
            peer.foreground.setForeground(false)
            peer.close()
            throw error
        }
    }

    private suspend fun seedAutoPair(
        fixture: CareLogRealServerSeamFixture, babyUuid: String, timestamp: Long,
        ownerAmount: Int, memberAmount: Int,
    ): AutoPair {
        val display = addFormula(fixture.owner, babyUuid, timestamp, ownerAmount)
        val source = addFormula(fixture.member, babyUuid, timestamp + 10_000L, memberAmount)
        fixture.pullAll()
        return AutoPair(display, source)
    }

    private suspend fun addFormula(client: SeamClient, babyUuid: String, timestamp: Long, amount: Int): String {
        val uuid = UUID.randomUUID().toString()
        client.clock.now += 1_000L
        client.careLog.addRecord(
            babyId = requireNotNull(client.babyByClientUuid(babyUuid)).id,
            type = RecordType.FORMULA, timestamp = timestamp, note = "restore-$amount",
            payloadJson = formulaPayloadJson(amount), nowMillis = client.clock.nowMillis(), clientUuid = uuid,
        )
        client.settleLocalWrite()
        return uuid
    }

    private suspend fun readModel(
        client: SeamClient, babyUuid: String, day: LocalDate, stage: String,
    ): ReadModel {
        val babyId = requireNotNull(client.babyByClientUuid(babyUuid)).id
        val timeline = TimelineWindowRepository(client.fakes.timelineWindow, client.port, Dispatchers.Unconfined)
            .observe(TimelineWindowRequest(babyId, day, ZoneOffset.UTC, client.clock.nowMillis())).first()
        val rows = client.careLog.observeDayRecords(babyId, day, ZoneOffset.UTC).first()
        val ordinary = client.careLog.projectOrdinaryRecords(rows)
        val sources = client.careLog.sourceRecordsByDisplay(rows).mapValues { (_, values) ->
            values.map { it.clientUuid }.toSet()
        }
        assertThat(timeline.recordRows.map { it.record.clientUuid }.toSet())
            .isEqualTo(ordinary.map { it.clientUuid }.toSet())
        assertThat(timeline.sourceRecordsByDisplay.mapValues { (_, values) -> values.map { it.clientUuid }.toSet() })
            .isEqualTo(sources)
        assertThat(timeline.autoAlignedDisplayClientUuids).isEqualTo(client.careLog.autoAlignedDisplayClientUuids())
        val stats = client.careLog.daySummaryBounds(rows, day, ZoneOffset.UTC, client.clock.nowMillis())
        assertThat(timeline.summaryBounds).isEqualTo(stats)
        assertThat(stats.hasUncertainty).isFalse()
        val headers = client.fakes.sourceRelations.listAll()
        val members = client.fakes.sourceRelations.listAllMembers()
        assertWithMessage("$stage: distinct relation headers").that(headers.map { it.relationId })
            .containsNoDuplicates()
        assertWithMessage("$stage: a record has one canonical membership").that(members.map { it.recordClientUuid })
            .containsNoDuplicates()
        val byId = headers.associateBy { it.relationId }
        // Memberships define current components; headers without members are historical evidence.
        // Keep getValue for every active group so an orphan member is still a hard failure.
        val groups = members.groupBy { it.relationId }.map { (id, component) ->
            assertWithMessage("$stage: active group $id has a header").that(byId).containsKey(id)
            val group = byId.getValue(id)
            assertWithMessage("$stage: active group $id is complete").that(component.size).isAtLeast(2)
            assertWithMessage("$stage: active group $id has the selected display")
                .that(component.filter { it.role == "display" }.map { it.recordClientUuid })
                .containsExactly(group.displayClientUuid)
            assertWithMessage("$stage: active group $id has only display/source roles")
                .that(component.map { it.role }.toSet()).containsExactly("display", "source")
            Group(id, group.displayClientUuid, component.map { it.recordClientUuid to it.role }.toSet(),
                group.displayClientUuid in client.careLog.autoAlignedDisplayClientUuids())
        }.toSet()
        return ReadModel(ordinary.map { it.clientUuid }.toSet(), client.careLog.sourceRoleClientUuids(),
            client.careLog.autoAlignedDisplayClientUuids(), sources, groups, stats.formulaMl.max)
    }

    private fun SetupProbeResult.getOrThrowReady(): SetupProbeResult.Ready =
        this as? SetupProbeResult.Ready ?: error("isolated server setup probe failed: $this")

    private enum class DisplayHistory { Live, Deleted, Moved }
    private data class AutoPair(val display: String, val source: String)
    private data class Group(val id: String, val display: String, val members: Set<Pair<String, String>>, val auto: Boolean)
    private data class ReadModel(
        val displayUuids: Set<String>, val sourceUuids: Set<String>, val autoDisplayUuids: Set<String>,
        val sourceRecords: Map<String, Set<String>>, val groups: Set<Group>, val formulaMl: Int,
    )

    companion object {
        private const val HOUR = 3_600_000L
    }
}
