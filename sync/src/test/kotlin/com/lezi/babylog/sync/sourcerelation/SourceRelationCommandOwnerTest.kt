package com.lezi.babylog.sync.sourcerelation

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.DatabaseTransactionRunner
import com.lezi.babylog.core.database.causal.SourceRelationDeclarationEntity
import com.lezi.babylog.core.database.causal.SourceRelationEntity
import com.lezi.babylog.core.database.causal.SourceRelationMemberEntity
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import com.lezi.babylog.sync.MemoryConflictSnapshotCacheDao
import com.lezi.babylog.sync.MemorySourceRelationDao
import com.lezi.babylog.sync.backend.FakeSyncBackend
import com.lezi.babylog.sync.backend.CurrentSourceRelationsRequest
import com.lezi.babylog.sync.backend.CurrentSourceRelationsSnapshot
import com.lezi.babylog.sync.backend.CurrentSourceRelationGroup
import com.lezi.babylog.sync.backend.CurrentSourceRelationRecord
import com.lezi.babylog.sync.backend.SourceRelationDeclareRequest
import com.lezi.babylog.sync.backend.SourceRelationResolveGroupRequest
import com.lezi.babylog.sync.backend.SourceRelationResult
import com.lezi.babylog.sync.backend.SyncBackend
import com.lezi.babylog.sync.session.FamilyRole
import com.lezi.babylog.sync.session.SyncSession
import com.lezi.babylog.sync.session.TrustedEndpointProfile
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SourceRelationCommandOwnerTest {
    @Test
    fun acceptedDeclareIsCanonicalAndRetiredBeforeReturning() = runTest {
        val rig = CommandRig()
        rig.onDeclare = { request ->
            assertThat(rig.inTransaction).isFalse()
            assertThat(rig.journal.getTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY))
                .isNotNull()
            assertThat(rig.relations.getDeclaration(request.mutationId)?.status).isEqualTo("pending")
            accepted()
        }
        assertThat(rig.owner().declare(declare()).copy(currentProjection = null)).isEqualTo(accepted())
        assertThat(rig.relations.get("relation")?.mutationId).isEqualTo("operation-original")
        assertThat(rig.relations.listMembers("relation").map { it.recordClientUuid to it.role })
            .containsExactly("display" to "display", "source" to "source")
        assertThat(rig.relations.getDeclaration("operation-original")?.status).isEqualTo("consumed")
        assertThat(rig.journal.getTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY)).isNull()
        assertThat(rig.committedStates.last()).isEqualTo(true to false)
    }

    @Test
    fun confirmedOperationRestartReadsCurrentProjectionWithoutRepostingWrite() = runTest {
        val rig = CommandRig()
        rig.onRead = { throw IOException("projection response lost") }
        val first = runCatching { rig.owner().declare(declare()) }.exceptionOrNull()
        assertThat(first).isInstanceOf(SourceRelationCommandRefreshRequiredException::class.java)
        assertThat(rig.relations.listAll()).isEmpty()
        assertThat(rig.journal.getTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY)?.payloadJson)
            .contains("confirmed_refresh_required")
        rig.onRead = { currentSnapshot(it) }
        rig.owner().declare(declare().copy(mutationId = "new-request"))
        assertThat(rig.declareRequests).containsExactly(declare())
        assertThat(rig.relations.get("relation")?.displayClientUuid).isEqualTo("display")
        assertThat(rig.journal.getTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY)).isNull()
    }

    @Test
    fun normalizedTrustedOriginAcceptsExplicitDefaultPortAndHostCase() = runTest {
        val rig = CommandRig()
        rig.session = rig.session.copy(serverHost = "ORIGINAL.example", serverPort = 443)
        assertThat(rig.owner().declare(declare()).copy(currentProjection = null)).isEqualTo(accepted())
        assertThat(rig.relations.get("relation")).isNotNull()
    }

    @Test
    fun lostResponseThenExplicitSameChoiceUsesOriginalRequestAcrossRestart() = runTest {
        val rig = CommandRig()
        rig.onDeclare = { throw IOException("response lost after remote commit") }
        assertThat(runCatching { rig.owner().declare(declare()) }.exceptionOrNull())
            .isInstanceOf(SourceRelationCommandUnsettledException::class.java)
        val payload = rig.journal.getTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY)!!.payloadJson
        assertThat(payload).doesNotContain("secret-access-token")
        assertThat(payload).doesNotContain("secret-refresh-token")
        assertThat(rig.relations.getDeclaration("operation-original")?.status).isEqualTo("pending")
        rig.onDeclare = { accepted() }
        rig.owner().declare(declare().copy(mutationId = "operation-new"))
        assertThat(rig.declareRequests).containsExactly(declare(), declare()).inOrder()
        assertThat(rig.relations.getDeclaration("operation-new")).isNull()
        assertThat(rig.relations.get("relation")?.mutationId).isEqualTo("operation-original")
    }

    @Test
    fun cancellationAfterRemoteAcceptanceKeepsUnknownChoiceForExactRetry() = runTest {
        val rig = CommandRig()
        rig.onDeclare = {
            currentCoroutineContext().cancel(CancellationException("accepted response interrupted"))
            accepted()
        }
        val call = launch { rig.owner().declare(declare()) }
        call.join()
        assertThat(call.isCancelled).isTrue()
        assertThat(rig.relations.listAll()).isEmpty()
        assertThat(rig.journal.getTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY)).isNotNull()
        assertThat(rig.relations.getDeclaration("operation-original")?.status).isEqualTo("pending")
        rig.onDeclare = { accepted() }
        rig.owner().declare(declare().copy(mutationId = "operation-retry"))
        assertThat(rig.declareRequests.map { it.mutationId })
            .containsExactly("operation-original", "operation-original")
    }

    @Test
    fun cancellationAfterConfirmedCurrentReadRetainsPhaseAndRetriesOnlyRead() = runTest {
        val rig = CommandRig()
        rig.onRead = { request ->
            currentCoroutineContext().cancel(CancellationException("read interrupted before settlement"))
            currentSnapshot(request)
        }
        val call = launch { rig.owner().declare(declare()) }
        call.join()
        assertThat(call.isCancelled).isTrue()
        assertThat(rig.relations.listAllMembers()).isEmpty()
        assertThat(rig.journal.getTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY)?.payloadJson)
            .contains("confirmed_refresh_required")
        rig.onRead = { currentSnapshot(it) }
        rig.owner().declare(declare().copy(mutationId = "retry"))
        assertThat(rig.declareRequests).containsExactly(declare())
        assertThat(rig.readRequests).hasSize(2)
    }

    @Test
    fun retirementFailureRollsBackCanonicalRowsAndRestartRefreshesWithoutReposting() = runTest {
        val rig = CommandRig()
        rig.journal.deleteFailure = IOException("Room commit failed")
        assertThat(runCatching { rig.owner().declare(declare()) }.exceptionOrNull())
            .isInstanceOf(SourceRelationCommandRefreshRequiredException::class.java)
        assertThat(rig.relations.listAll()).isEmpty()
        assertThat(rig.relations.listAllMembers()).isEmpty()
        assertThat(rig.relations.getDeclaration("operation-original")?.status).isEqualTo("pending")
        assertThat(rig.journal.getTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY)).isNotNull()
        assertThat(rig.committedStates).containsExactly(false to true, false to true)
        rig.journal.deleteFailure = null
        rig.owner().declare(declare().copy(mutationId = "operation-new"))
        assertThat(rig.declareRequests.map { it.mutationId })
            .containsExactly("operation-original")
        assertThat(rig.committedStates.last()).isEqualTo(true to false)
    }

    @Test
    fun failureBeforeConfirmedPhaseCommitKeepsOriginalOperationForExactRetry() = runTest {
        val rig = CommandRig()
        rig.onDeclare = {
            rig.failNextCommit = true
            accepted()
        }
        assertThat(runCatching { rig.owner().declare(declare()) }.exceptionOrNull())
            .isInstanceOf(SourceRelationCommandRefreshRequiredException::class.java)
        assertThat(rig.journal.getTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY)?.payloadJson)
            .doesNotContain("confirmed_refresh_required")
        rig.onDeclare = { accepted() }
        rig.owner().declare(declare().copy(mutationId = "new"))
        assertThat(rig.declareRequests).containsExactly(declare(), declare()).inOrder()
    }

    @Test
    fun differentChoiceOrCasVersionsCannotReplaceUnknownCommand() = runTest {
        val rig = CommandRig()
        rig.onDeclare = { throw IOException("lost") }
        runCatching { rig.owner().declare(declare()) }
        val before = rig.journal.getTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY)
        for (request in listOf(
            declare().copy(equivalentToClientUuid = "third"),
            declare().copy(expectedRecordVersion = "new-version"),
        )) {
            assertThat(runCatching { rig.owner().declare(request) }.exceptionOrNull())
                .isInstanceOf(SourceRelationCommandUnsettledException::class.java)
        }
        assertThat(runCatching { rig.owner().resolveGroup(resolve()) }.exceptionOrNull())
            .isInstanceOf(SourceRelationCommandUnsettledException::class.java)
        assertThat(rig.declareRequests).hasSize(1)
        assertThat(rig.resolveRequests).isEmpty()
        assertThat(rig.journal.getTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY)).isEqualTo(before)
    }

    @Test
    fun eachAuthorityChangeBlocksDispatchAndPreservesOriginalJournal() = runTest {
        val mutations: List<(CommandRig) -> Unit> = listOf(
            { it.session = it.session.copy(familyId = "other-family") },
            { it.session = it.session.copy(membershipId = "other-member") },
            { it.session = it.session.copy(deviceId = "other-device") },
            { it.session = it.session.copy(pullGeneration = "other-generation") },
            {
                it.session = it.session.copy(serverHost = "replacement.example")
                it.endpoint = TrustedEndpointProfile.systemPki(it.session.baseUrl)
            },
            { it.endpoint = TrustedEndpointProfile.tofuSpki(it.session.baseUrl,
                java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 1 })) },
        )
        for (mutate in mutations) {
            val rig = CommandRig()
            rig.onDeclare = { throw IOException("lost") }
            runCatching { rig.owner().declare(declare()) }
            val original = rig.journal.getTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY)
            mutate(rig)
            assertThat(runCatching { rig.owner().declare(declare()) }.exceptionOrNull())
                .isInstanceOf(SourceRelationCommandUnsettledException::class.java)
            assertThat(rig.declareRequests).hasSize(1)
            assertThat(rig.journal.getTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY)).isEqualTo(original)
        }
    }

    @Test
    fun authorityChangeWhileResponseReturnsCannotSettleIntoNewReplica() = runTest {
        val rig = CommandRig()
        rig.onDeclare = {
            rig.session = rig.session.copy(pullGeneration = "replacement")
            accepted()
        }
        assertThat(runCatching { rig.owner().declare(declare()) }.exceptionOrNull())
            .isInstanceOf(SourceRelationCommandUnsettledException::class.java)
        assertThat(rig.relations.listAll()).isEmpty()
        assertThat(rig.journal.getTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY)).isNotNull()
    }

    @Test
    fun historicalAcceptedReceiptSettlesFreshNewerGroupWithoutReactivatingOldGroup() = runTest {
        val rig = CommandRig()
        rig.onResolve = { throw IOException("accepted receipt lost") }
        runCatching { rig.owner().resolveGroup(resolve()) }
        rig.session = rig.session.copy(pullCursor = 20)
        val newer = SourceRelationEntity("newer-relation", "source", true, "owner_group_resolve", "newer-choice", "member", 200)
        rig.relations.applyOwnerGroupResolution(newer,
            listOf(SourceRelationMemberEntity("newer-relation", "source", "display"),
                SourceRelationMemberEntity("newer-relation", "display", "source")))
        rig.onResolve = { accepted() }
        rig.onRead = { request -> currentSnapshot(request).copy(
            records = listOf(CurrentSourceRelationRecord("source", "live", "newer-relation"),
                CurrentSourceRelationRecord("display", "live", "newer-relation")),
            sourceRelations = listOf(CurrentSourceRelationGroup("newer-relation", "source", listOf("display"))),
        ) }
        val result = rig.owner().resolveGroup(resolve().copy(mutationId = "retry"))
        assertThat(result.currentProjection?.sourceRelations?.single()?.displayClientUuid).isEqualTo("source")
        assertThat(rig.resolveRequests.map { it.mutationId })
            .containsExactly("operation-original", "operation-original")
        assertThat(rig.relations.get("relation")).isNull()
        assertThat(rig.relations.get("newer-relation")).isEqualTo(newer)
        assertThat(rig.journal.getTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY)).isNull()
    }

    @Test
    fun firstSuccessfulRetryAfterNewerPullUsesFreshReadInsteadOfInferringSupersession() = runTest {
        val rig = CommandRig()
        rig.onResolve = { throw IOException("request outcome unknown before commit") }
        runCatching { rig.owner().resolveGroup(resolve()) }
        rig.session = rig.session.copy(pullCursor = 20)
        rig.relations.applyOwnerGroupResolution(
            SourceRelationEntity("intermediate", "source", true, "owner_group_resolve", "intermediate-choice", "member", 50),
            listOf(SourceRelationMemberEntity("intermediate", "source", "display"),
                SourceRelationMemberEntity("intermediate", "display", "source")))
        rig.onResolve = { accepted() } // The original operation is first committed by this retry.
        val result = rig.owner().resolveGroup(resolve().copy(mutationId = "new"))
        assertThat(result.currentProjection?.headRev).isEqualTo(100L)
        assertThat(rig.relations.listMembers("intermediate")).isEmpty()
        assertThat(rig.relations.get("relation")?.displayClientUuid).isEqualTo("display")
        assertThat(rig.session.pullCursor).isEqualTo(20L)
        assertThat(rig.resolveRequests).containsExactly(resolve(), resolve()).inOrder()
    }

    @Test
    fun freshAuthoritativeAbsenceRemovesOnlyCoveredMembershipAndAutoMarker() = runTest {
        val rig = CommandRig()
        rig.relations.applyPullSummary("old-group", "display", "display", listOf("source"), 1, true)
        rig.onRead = { request -> currentSnapshot(request).copy(
            records = listOf(CurrentSourceRelationRecord("display", "live", null),
                CurrentSourceRelationRecord("source", "missing", null)),
            sourceRelations = emptyList(),
        ) }
        val result = rig.owner().declare(declare())
        assertThat(result.currentProjection?.sourceRelations).isEmpty()
        assertThat(rig.relations.listAllMembers()).isEmpty()
        assertThat(rig.relations.listAutoAlignedDisplayClientUuids()).isEmpty()
        assertThat(rig.relations.getDeclaration("operation-original")?.status).isEqualTo("consumed")
        assertThat(rig.session.pullCursor).isEqualTo(0)
    }

    @Test
    fun tombstonedRecordStillKeepsItsAuthoritativeSourceMembership() = runTest {
        val rig = CommandRig()
        rig.onRead = { request -> currentSnapshot(request).copy(records = listOf(
            CurrentSourceRelationRecord("display", "live", "relation"),
            CurrentSourceRelationRecord("source", "deleted", "relation"),
        )) }
        rig.owner().declare(declare())
        assertThat(rig.relations.listMembers("relation").map { it.recordClientUuid to it.role })
            .containsExactly("display" to "display", "source" to "source")
    }

    @Test
    fun unavailableOldServerKeepsConfirmedPhaseAndExplainsUpgradeWithoutReposting() = runTest {
        val rig = CommandRig()
        rig.onRead = { throw UnsupportedOperationException("not available") }
        repeat(2) {
            val failure = runCatching { rig.owner().declare(declare().copy(mutationId = "attempt-$it")) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(SourceRelationCommandRefreshRequiredException::class.java)
            assertThat(failure?.message).contains("更新原服务器")
        }
        assertThat(rig.declareRequests).hasSize(1)
        assertThat(rig.readRequests).hasSize(2)
        assertThat(rig.relations.listAllMembers()).isEmpty()
        assertThat(rig.journal.getTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY)?.payloadJson)
            .contains("confirmed_refresh_required")
    }

    @Test
    fun incompleteCurrentSnapshotNeverPartiallyReplacesExistingProjection() = runTest {
        val rig = CommandRig()
        rig.relations.applyPullSummary("old", "display", "display", listOf("source"), 1)
        rig.onRead = { request -> currentSnapshot(request).copy(records = listOf(
            CurrentSourceRelationRecord("display", "live", "relation"),
        )) }
        assertThat(runCatching { rig.owner().declare(declare()) }.exceptionOrNull())
            .isInstanceOf(SourceRelationCommandRefreshRequiredException::class.java)
        assertThat(rig.relations.listMembers("old")).hasSize(2)
        assertThat(rig.relations.get("relation")).isNull()
    }

    @Test
    fun continuallyChangingReadClosureStopsWithConfirmedEvidenceAndNoPartialWrites() = runTest {
        val rig = CommandRig()
        var reads = 0
        rig.onRead = { request ->
            val extra = "peer-${++reads}"
            val group = CurrentSourceRelationGroup("current-$reads", "display", listOf("source", extra).sorted())
            val records = (request.recordClientUuids + group.memberClientUuids).distinct().map { uuid ->
                CurrentSourceRelationRecord(uuid, "live", group.relationId.takeIf { uuid in group.memberClientUuids })
            }
            CurrentSourceRelationsSnapshot(request.familyId, request.generation, 100,
                request.recordClientUuids, records, listOf(group))
        }
        assertThat(runCatching { rig.owner().declare(declare()) }.exceptionOrNull())
            .isInstanceOf(SourceRelationCommandRefreshRequiredException::class.java)
        assertThat(reads).isEqualTo(4)
        assertThat(rig.relations.listAllMembers()).isEmpty()
        assertThat(rig.journal.getTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY)?.payloadJson)
            .contains("confirmed_refresh_required")
    }

    @Test
    fun oversizedOldPeerClosureStopsBeforeRemovingAnyMembership() = runTest {
        val rig = CommandRig()
        rig.relations.applyPullSummary("original", "display", "display", listOf("source"), 1)
        val peers = (1..63).map { "old-peer-$it" }
        rig.relations.applyPullSummary("other-old-group", "new-peer", "display", peers, 1)
        rig.onRead = { request ->
            val group = CurrentSourceRelationGroup("new-group", "display", listOf("new-peer", "source"))
            CurrentSourceRelationsSnapshot(request.familyId, request.generation, 100,
                request.recordClientUuids, group.memberClientUuids.map {
                    CurrentSourceRelationRecord(it, "live", group.relationId)
                }, listOf(group))
        }
        val failure = runCatching { rig.owner().declare(declare()) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(SourceRelationCommandRefreshRequiredException::class.java)
        assertThat(failure?.message).contains("完整读取")
        assertThat(rig.relations.listMembers("original")).hasSize(2)
        assertThat(rig.relations.listMembers("other-old-group")).hasSize(64)
        assertThat(rig.relations.get("new-group")).isNull()
    }

    @Test
    fun localCanonicalChangeDuringFreshReadCannotBeOverwritten() = runTest {
        val rig = CommandRig()
        rig.onRead = { request ->
            rig.relations.applyOwnerGroupResolution(
                SourceRelationEntity("newer-relation", "source", true, "owner_group_resolve", "newer-choice", "member", 200),
                listOf(SourceRelationMemberEntity("newer-relation", "source", "display"),
                    SourceRelationMemberEntity("newer-relation", "display", "source")))
            currentSnapshot(request)
        }
        assertThat(runCatching { rig.owner().declare(declare()) }.exceptionOrNull())
            .isInstanceOf(SourceRelationCommandRefreshRequiredException::class.java)
        assertThat(rig.relations.get("newer-relation")?.displayClientUuid).isEqualTo("source")
        assertThat(rig.journal.getTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY)).isNotNull()
    }

    @Test
    fun opaqueProvenanceDelimiterCollisionCannotHideCanonicalChange() = runTest {
        val rig = CommandRig()
        val prior = SourceRelationEntity(
            "base-relation", "display", true, "owner_group_resolve",
            "old, createdByMembershipId=author", "other", 1,
        )
        val changed = prior.copy(mutationId = "old", createdByMembershipId = "author, createdByMembershipId=other")
        // Diagnostic strings collide; exact local evidence must preserve field boundaries.
        assertThat(prior.toString()).isEqualTo(changed.toString())
        val members = listOf(SourceRelationMemberEntity("base-relation", "display", "display"),
            SourceRelationMemberEntity("base-relation", "source", "source"))
        rig.relations.seedRaw(prior, members)
        rig.onResolve = { throw IOException("lost") }
        runCatching { rig.owner().resolveGroup(resolve()) }
        rig.onResolve = { accepted() }
        rig.onRead = { request ->
            rig.relations.seedRaw(changed, members)
            currentSnapshot(request)
        }
        assertThat(runCatching { rig.owner().resolveGroup(resolve()) }.exceptionOrNull())
            .isInstanceOf(SourceRelationCommandRefreshRequiredException::class.java)
        assertThat(rig.relations.get("base-relation")).isEqualTo(changed)
        assertThat(rig.relations.get("relation")).isNull()
        assertThat(rig.journal.getTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY)).isNotNull()
    }

    @Test
    fun matchingCurrentProjectionRetiresHistoricalReceiptWithoutRewritingProvenance() = runTest {
        val rig = CommandRig()
        rig.onDeclare = { throw IOException("lost") }
        runCatching { rig.owner().declare(declare()) }
        rig.session = rig.session.copy(pullCursor = 20)
        val newer = SourceRelationEntity("relation", "display", true, "owner_group_resolve", "newer-choice", "other-member", 200)
        rig.relations.applyOwnerGroupResolution(newer,
            listOf(SourceRelationMemberEntity("relation", "display", "display"),
                SourceRelationMemberEntity("relation", "source", "source")))
        rig.onDeclare = { accepted() }
        rig.owner().declare(declare())
        assertThat(rig.relations.get("relation")).isEqualTo(newer)
        assertThat(rig.relations.getDeclaration("operation-original")?.status).isEqualTo("consumed")
        assertThat(rig.journal.getTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY)).isNull()
    }

    @Test
    fun ownerRepeatWithReorderedMembersReusesOriginalRequest() = runTest {
        val rig = CommandRig()
        rig.onResolve = { throw IOException("lost") }
        runCatching { rig.owner().resolveGroup(resolve()) }
        rig.onResolve = { accepted() }
        rig.owner().resolveGroup(resolve().copy(mutationId = "new", memberClientUuids = listOf("display", "source")))
        assertThat(rig.resolveRequests).containsExactly(resolve(), resolve()).inOrder()
        assertThat(rig.relations.get("relation")?.mutationId).isEqualTo("operation-original")
        assertThat(rig.relations.listPendingDeclarations()).isEmpty()
    }

    @Test
    fun knownRejectionAndCasMismatchRetireJournalWithDefinitiveStatus() = runTest {
        for ((response, status) in listOf(
            SourceRelationResult("rejected", code = "already_related") to "rejected",
            SourceRelationResult("cas_mismatch", code = "cas_mismatch", latestVersions = mapOf("source" to "new")) to "superseded",
        )) {
            val rig = CommandRig()
            rig.onDeclare = { response }
            assertThat(rig.owner().declare(declare())).isEqualTo(response)
            assertThat(rig.relations.getDeclaration("operation-original")?.status).isEqualTo(status)
            assertThat(rig.relations.listUnsettledDeclarations()).isEmpty()
            assertThat(rig.relations.listAll()).isEmpty()
            assertThat(rig.journal.getTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY)).isNull()
        }
    }

    @Test
    fun malformedAcceptedReceiptRetainsUnknownEvidence() = runTest {
        val rig = CommandRig()
        rig.onDeclare = { accepted().copy(sourceClientUuids = listOf("unrequested")) }
        assertThat(runCatching { rig.owner().declare(declare()) }.exceptionOrNull())
            .isInstanceOf(SourceRelationCommandUnsettledException::class.java)
        assertThat(rig.relations.listAll()).isEmpty()
        assertThat(rig.relations.getDeclaration("operation-original")?.status).isEqualTo("pending")
        assertThat(rig.journal.getTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY)).isNotNull()
    }

    @Test
    fun legacyFailedDeclarationCannotBeSilentlyReplacedWithNewCommand() = runTest {
        val rig = CommandRig()
        rig.relations.upsertDeclaration(SourceRelationDeclarationEntity(
            "legacy", "source", "display", "old-source", "old-display", "member", "failed", 1,
        ))
        val error = runCatching { rig.owner().declare(declare()) }.exceptionOrNull()
        assertThat(error).isInstanceOf(SourceRelationCommandUnsettledException::class.java)
        assertThat(error?.message).contains("原家庭服务器")
        assertThat(rig.declareRequests).isEmpty()
        assertThat(rig.relations.getDeclaration("legacy")?.status).isEqualTo("failed")
        assertThat(rig.journal.getTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY)).isNull()
    }

    @Test
    fun oversizedOwnerCommandIsRejectedBeforeJournalOrDispatch() = runTest {
        val rig = CommandRig()
        val members = (1..65).map { "record-$it" }
        val request = SourceRelationResolveGroupRequest("operation", members, members[0],
            members.associateWith { "version-$it" })
        assertThat(runCatching { rig.owner().resolveGroup(request) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(rig.resolveRequests).isEmpty()
        assertThat(rig.journal.getTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY)).isNull()
    }

}

private fun declare() = SourceRelationDeclareRequest(
    mutationId = "operation-original",
    recordClientUuid = "source",
    equivalentToClientUuid = "display",
    expectedRecordVersion = "source-version",
    expectedOtherVersion = "display-version",
)

private fun resolve() = SourceRelationResolveGroupRequest(
    mutationId = "operation-original", memberClientUuids = listOf("source", "display"),
    displayClientUuid = "display", expectedVersions = mapOf("source" to "source-version", "display" to "display-version"),
)

private fun accepted() = SourceRelationResult(
    status = "accepted",
    relationId = "relation",
    displayClientUuid = "display",
    sourceClientUuids = listOf("source"),
    mediaRetained = true,
)

private fun currentSnapshot(request: CurrentSourceRelationsRequest) = CurrentSourceRelationsSnapshot(
    familyId = request.familyId, generation = request.generation, headRev = 100,
    requestedRecordClientUuids = request.recordClientUuids,
    records = listOf(CurrentSourceRelationRecord("display", "live", "relation"),
        CurrentSourceRelationRecord("source", "live", "relation")),
    sourceRelations = listOf(CurrentSourceRelationGroup("relation", "display", listOf("source"))),
)

private class CommandRig {
    val relations = MemorySourceRelationDao()
    val journal = MemoryConflictSnapshotCacheDao()
    var session = SyncSession(
        familyId = "family", membershipId = "member", deviceId = "device",
        pullGeneration = "generation", role = FamilyRole.Owner,
        accessToken = "secret-access-token", refreshToken = "secret-refresh-token",
        serverHost = "original.example", serverPort = 443, serverScheme = "https",
    )
    var endpoint: TrustedEndpointProfile? = TrustedEndpointProfile.systemPki(session.baseUrl)
    var inTransaction = false
    var failNextCommit = false
    val committedStates = mutableListOf<Pair<Boolean, Boolean>>()
    val declareRequests = mutableListOf<SourceRelationDeclareRequest>()
    val resolveRequests = mutableListOf<SourceRelationResolveGroupRequest>()
    val readRequests = mutableListOf<CurrentSourceRelationsRequest>()
    var onRead: suspend (CurrentSourceRelationsRequest) -> CurrentSourceRelationsSnapshot = { currentSnapshot(it) }
    var onDeclare: suspend (SourceRelationDeclareRequest) -> SourceRelationResult = { accepted() }
    var onResolve: suspend (SourceRelationResolveGroupRequest) -> SourceRelationResult = { accepted() }
    val backend = object : SyncBackend by FakeSyncBackend() {
        override suspend fun readCurrentSourceRelations(
            session: SyncSession,
            request: CurrentSourceRelationsRequest,
        ): CurrentSourceRelationsSnapshot {
            assertThat(inTransaction).isFalse()
            readRequests += request
            return onRead(request)
        }
        override suspend fun declareSourceRelation(
            session: SyncSession,
            request: SourceRelationDeclareRequest,
        ): SourceRelationResult {
            assertThat(inTransaction).isFalse()
            declareRequests += request
            return onDeclare(request)
        }
        override suspend fun resolveSourceRelationGroup(
            session: SyncSession,
            request: SourceRelationResolveGroupRequest,
        ): SourceRelationResult {
            assertThat(inTransaction).isFalse()
            resolveRequests += request
            return onResolve(request)
        }
    }
    val transactions = object : DatabaseTransactionRunner {
        override suspend fun <T> run(block: suspend () -> T): T {
            check(!inTransaction)
            val savedRelations = relations.listAll()
            val savedMembers = relations.listAllMembers()
            val savedDeclarations = (relations.listUnsettledDeclarations() +
                (declareRequests.map { it.mutationId } + "operation-original")
                    .distinct().mapNotNull { relations.getDeclaration(it) }).distinctBy { it.mutationId }
            val savedJournal = journal.getTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY)
            inTransaction = true
            try {
                val result = block()
                if (failNextCommit) {
                    failNextCommit = false
                    throw IOException("injected Room commit failure")
                }
                committedStates += (relations.get("relation") != null) to
                    (journal.getTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY) != null)
                return result
            } catch (failure: Throwable) {
                // Model Room rollback, including failures after canonical writes before retirement.
                relations.deleteAllMembers()
                relations.deleteAll()
                relations.deleteAllDeclarations()
                for (relation in savedRelations) {
                    relations.seedRaw(relation, savedMembers.filter { it.relationId == relation.relationId })
                }
                savedDeclarations.forEach { relations.upsertDeclaration(it) }
                if (savedJournal != null) {
                    journal.putTransportJournal(savedJournal.journalKey, savedJournal.payloadJson, savedJournal.contentEpoch)
                } else {
                    val deleteFailure = journal.deleteFailure
                    journal.deleteFailure = null
                    journal.deleteTransportJournal(SOURCE_RELATION_COMMAND_JOURNAL_KEY)
                    journal.deleteFailure = deleteFailure
                }
                throw failure
            } finally {
                inTransaction = false
            }
        }
    }
    fun owner() = SourceRelationCommandOwner(
        sourceRelationDao = relations,
        journalDao = journal,
        transactionRunner = transactions,
        backend = backend,
        currentSession = { session },
        currentEndpoint = { endpoint },
        nowMillis = { 123L },
    )
}
