package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.domain.carelog.ConflictResolveOutcome
import com.lezi.babylog.sync.backend.ConflictWithdrawRequest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/** US020 ordinary conflict media only: no restore, cancellation, family deletion or policy change. */
class CareLogRealServerSeamMediaConflictTest {
    @Test(timeout = 600_000)
    fun hundredResolvedMediaGroupsDoNotHashHistoricalSpoolsOnQuietRounds() = runBlocking {
        SpoolHashCounter().use { hashes ->
            CareLogRealServerSeamFixture.open(mediaEnabled = true).use { fixture ->
                val peer = fixture.joinExtraOwner("media-branch")
                val babyId = fixture.owner.careLog.createBaby(CreateBabyInput(
                    "Conflict history", birthdayEpochDay = 20_000L,
                ))
                fixture.owner.settleLocalWrite()
                val resolvedHistory = mutableListOf<String>()
                repeat(100) { index ->
                    val uuid = seedAndBranch(fixture, babyId, fixture.owner, listOf(peer), index)
                    val conflictId = requireNotNull(peer.openConflictIdForRecord(uuid))
                    // Alternate resolver location: branch's own device and remote stable device.
                    val resolver = if (index % 2 == 0) peer else fixture.owner
                    val (_, result) = resolver.resolveOpenConflict(
                        conflictId, preferValueByPath = mapOf("/note" to JsonPrimitive("stable-$index")),
                    )
                    assertThat(result).isInstanceOf(ConflictResolveOutcome.Accepted::class.java)
                    fixture.pullAll()
                    assertThat(peer.fakes.conflictSnapshotCache.listFrozenMediaSpoolManifests()).isEmpty()
                    assertThat(peer.recordByClientUuid(uuid)?.openConflictId).isNull()
                    resolvedHistory += conflictId
                }
                assertThat(hashes.count()).isGreaterThan(0L)
                assertThat(resolvedHistory.distinct()).hasSize(100)
                fixture.allClients.forEach {
                    assertThat(requireNotNull(it.mediaFiles).spoolMediaFiles()).isEmpty()
                }
                hashes.reset()
                repeat(3) { fixture.pullAll() }
                assertThat(hashes.count()).isEqualTo(0L)
            }
        }
    }

    @Test(timeout = 180_000)
    fun memberPartialWithdrawalPreservesOtherOpenBranchThenOwnerWithdrawsAll() = runBlocking {
        CareLogRealServerSeamFixture.open(mediaEnabled = true).use { fixture ->
            val otherOwner = fixture.joinExtraOwner("remaining-branch")
            val babyId = fixture.owner.careLog.createBaby(CreateBabyInput(
                "Withdrawals", birthdayEpochDay = 20_000L,
            ))
            fixture.owner.settleLocalWrite()
            val uuid = seedAndBranch(
                fixture, babyId, fixture.owner, listOf(fixture.member, otherOwner), 100,
                author = fixture.member,
            )
            val conflictId = requireNotNull(fixture.member.openConflictIdForRecord(uuid))
            val before = fixture.member.loadConflict(conflictId).snapshot
            assertThat(before.branches).hasSize(2)
            val memberManifest = fixture.member.fakes.conflictSnapshotCache.listFrozenMediaSpoolManifests()
            val otherManifest = otherOwner.fakes.conflictSnapshotCache.listFrozenMediaSpoolManifests()
            assertThat(memberManifest).hasSize(1)
            assertThat(otherManifest).hasSize(1)
            val expectedRemainingBranch = branchVersion(otherManifest.single().payloadJson)
            val remainingFiles = requireNotNull(otherOwner.mediaFiles)
            val originalRemainingBytes = spoolBytes(remainingFiles)
            val result = fixture.member.careLog.withdrawConflictBranches(conflictId, ConflictWithdrawRequest(
                withdrawalMutationId = UUID.randomUUID().toString(),
                expectedStableVersionId = before.stable.versionId,
                expectedBranchVersionIds = before.branches.map { it.versionId }.sorted(),
            ))
            assertThat(result).isInstanceOf(ConflictResolveOutcome.Withdrawn::class.java)
            fixture.pullAll()
            assertThat(fixture.member.fakes.conflictSnapshotCache.listFrozenMediaSpoolManifests()).isEmpty()
            assertThat(otherOwner.fakes.conflictSnapshotCache.listFrozenMediaSpoolManifests()).hasSize(1)
            val remaining = otherOwner.loadConflict(conflictId).snapshot
            assertThat(remaining.branches.map { it.versionId }).containsExactly(expectedRemainingBranch)
            assertThat(otherOwner.fakes.conflictSnapshotCache.listFrozenMediaSpoolManifests())
                .isEqualTo(otherManifest)
            assertThat(spoolBytes(remainingFiles)).isEqualTo(originalRemainingBytes)
            assertThat(otherOwner.careLog.withdrawConflictBranches(conflictId, ConflictWithdrawRequest(
                withdrawalMutationId = UUID.randomUUID().toString(),
                expectedStableVersionId = remaining.stable.versionId,
                expectedBranchVersionIds = remaining.branches.map { it.versionId }.sorted(),
            ))).isInstanceOf(ConflictResolveOutcome.Withdrawn::class.java)
            fixture.pullAll()
            assertThat(otherOwner.fakes.conflictSnapshotCache.listFrozenMediaSpoolManifests()).isEmpty()
            fixture.allClients.forEach { assertThat(it.recordByClientUuid(uuid)?.openConflictId).isNull() }
        }
    }

    @Test(timeout = 360_000)
    fun ordinaryResolutionAndWithdrawalRecoverBeforeAndAfterSpoolUnlink() = runBlocking {
        for (action in listOf("local-resolve", "remote-resolve", "partial-withdraw", "all-withdraw")) {
            for (afterUnlink in listOf(false, true)) {
                CareLogRealServerSeamFixture.open(mediaEnabled = true).use { fixture ->
                    val other = fixture.joinExtraOwner("window-branch")
                    val target = fixture.member
                    val babyId = fixture.owner.careLog.createBaby(CreateBabyInput(
                        "Cleanup window", birthdayEpochDay = 20_000L,
                    ))
                    fixture.owner.settleLocalWrite()
                    val uuid = seedAndBranch(
                        fixture, babyId, fixture.owner, listOf(target, other), 200,
                        author = target,
                    )
                    val conflictId = requireNotNull(target.openConflictIdForRecord(uuid))
                    val files = requireNotNull(target.mediaFiles)
                    assertThat(files.spoolMediaFiles()).hasSize(1)
                    val preservedManifest = other.fakes.conflictSnapshotCache.listFrozenMediaSpoolManifests()
                    assertThat(preservedManifest).hasSize(1)
                    val preservedBranch = branchVersion(preservedManifest.single().payloadJson)
                    val preservedFiles = requireNotNull(other.mediaFiles)
                    val preservedBytes = spoolBytes(preservedFiles)
                    target.foreground.setForeground(false)
                    var observedWindow = false
                    files.onSpoolDiscard = { mutationId, after ->
                        if (after == afterUnlink) {
                            // Ordinary authoritative pull has committed removal of the
                            // branch reference before any irreversible local file cleanup.
                            assertThat(target.fakes.conflictSnapshotCache
                                .getFrozenMediaSpoolManifest(mutationId)).isNull()
                            assertThat(files.spoolMediaFiles()).hasSize(if (after) 0 else 1)
                            observedWindow = true
                            files.onSpoolDiscard = null
                            throw java.io.IOException("synthetic ordinary branch unlink interruption")
                        }
                    }
                    val actor = if (action == "local-resolve" || action == "partial-withdraw") target
                        else fixture.owner
                    actor.foreground.setForeground(true)
                    // The injected interruption can surface during the command's local
                    // apply or its subsequent pull. The authoritative server result is
                    // verified below after the normal recovery round.
                    runCatching {
                        if (action.endsWith("resolve")) {
                            actor.resolveOpenConflict(conflictId)
                        } else {
                            val snapshot = actor.loadConflict(conflictId).snapshot
                            actor.careLog.withdrawConflictBranches(conflictId, ConflictWithdrawRequest(
                                withdrawalMutationId = UUID.randomUUID().toString(),
                                expectedStableVersionId = snapshot.stable.versionId,
                                expectedBranchVersionIds = snapshot.branches.map { it.versionId }.sorted(),
                            ))
                        }
                    }
                    target.foreground.setForeground(true)
                    runCatching { target.pullForeground() }
                    assertThat(observedWindow).isTrue()
                    target.pullForeground()
                    assertThat(files.spoolMediaFiles()).isEmpty()
                    assertThat(target.fakes.conflictSnapshotCache.listFrozenMediaSpoolManifests()).isEmpty()
                    if (action == "partial-withdraw") {
                        assertThat(other.loadConflict(conflictId).snapshot.branches.map { it.versionId })
                            .containsExactly(preservedBranch)
                        assertThat(other.fakes.conflictSnapshotCache.listFrozenMediaSpoolManifests())
                            .isEqualTo(preservedManifest)
                        assertThat(spoolBytes(preservedFiles)).isEqualTo(preservedBytes)
                    } else {
                        fixture.pullAll()
                        assertThat(target.openConflictIdForRecord(uuid)).isNull()
                        assertThat(requireNotNull(other.mediaFiles).spoolMediaFiles()).isEmpty()
                    }
                }
            }
        }
    }

    private fun branchVersion(manifest: String): String = Json.parseToJsonElement(manifest)
        .jsonObject.getValue("branch_version_id").jsonPrimitive.content

    private fun spoolBytes(files: SeamMediaFileStore): Map<String, List<Byte>> =
        files.spoolMediaFiles().associate { it.relativeTo(files.root).path to it.readBytes().toList() }

    private suspend fun seedAndBranch(
        fixture: CareLogRealServerSeamFixture,
        babyId: Long,
        stable: SeamClient,
        branches: List<SeamClient>,
        index: Int,
        author: SeamClient = stable,
    ): String {
        fixture.pullAll()
        val babyUuid = requireNotNull(fixture.owner.fakes.babies.get(babyId)).clientUuid
        val uuid = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis() - 60_000L
        author.careLog.addRecord(
            babyId = requireNotNull(author.babyByClientUuid(babyUuid)).id,
            type = RecordType.FORMULA,
            timestamp = timestamp,
            note = "base-$index",
            payloadJson = formulaPayloadJson(90),
            clientUuid = uuid,
        )
        author.settleLocalWrite()
        fixture.pullAll()
        val writers = listOf(stable) + branches
        writers.forEach { it.foreground.setForeground(false) }
        writers.forEachIndexed { ordinal, client ->
            client.careLog.updateRecord(
                id = requireNotNull(client.recordByClientUuid(uuid)).id,
                timestamp = timestamp,
                endTimestamp = null,
                note = if (ordinal == 0) "stable-$index" else "branch-$ordinal-$index",
                payloadJson = formulaPayloadJson(90),
                photoLocalPaths = listOf(requireNotNull(client.mediaFiles).syntheticPhoto()),
            )
        }
        writers.forEach {
            it.foreground.setForeground(true)
            it.settleLocalWrite()
        }
        fixture.pullAll()
        branches.forEach { assertThat(it.openConflictIdForRecord(uuid)).isNotNull() }
        return uuid
    }
}
