package com.lezi.babylog.sync.disasterrecovery

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.*
import com.lezi.babylog.core.database.causal.*
import com.lezi.babylog.sync.*
import com.lezi.babylog.sync.backend.*
import com.lezi.babylog.sync.clear.LocalReplicaClearCoordinator
import com.lezi.babylog.sync.clear.LocalClearCommittedException
import com.lezi.babylog.sync.clear.clear
import com.lezi.babylog.sync.engine.*
import com.lezi.babylog.sync.media.*
import com.lezi.babylog.sync.session.*
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Test

class RestoreTerminalSpoolRetirementTest {
    @Test fun acceptedAndMergedDifferentCanonicalBytesReleaseUnchangedTinyQuotaAcrossRestart() = runBlocking<Unit> {
        for (phase in listOf(CausalMediaSettlementPhase.CleanupAccepted, CausalMediaSettlementPhase.CleanupMerged)) {
            for (replacement in listOf("RAW!", "raw-replacement-with-different-size")) Rig(replacement).use { rig ->
                val seals = rig.switch(phase)
                assertThat(seals).hasSize(1)
                assertThat(runCatching { rig.freezeAnother() }.exceptionOrNull()).isInstanceOf(MediaSpoolCapacityException::class.java)
                rig.owner().reclaim()
                assertThat(rig.spool.recoverGroup(MUTATION)).isNull()
                assertThat(rig.raw.readText()).isEqualTo(replacement)
                assertThat(rig.cache.listRestoreTerminalSpoolSeals()).isEmpty()
                assertThat(rig.cache.getFrozenMediaSpoolManifest(MUTATION)).isNull()
                assertThat(rig.freezeAnother().items.single().byteSize).isEqualTo(4)
            }
        }
    }

    @Test fun pendingUnknownBranchedPlainAndStrippedGroupsCannotSeedSeal() = runBlocking<Unit> {
        for (phase in listOf(CausalMediaSettlementPhase.Pending, CausalMediaSettlementPhase.CommitUnknown,
            CausalMediaSettlementPhase.Branched)) Rig().use { rig ->
            assertThat(rig.switch(phase)).isEmpty()
            rig.owner().reclaim()
            assertThat(rig.spool.recoverGroup(MUTATION)).isNotNull()
        }
        Rig().use { rig ->
            assertThat(rig.switch(originalTransform = { it.copy(payloadJson = encodeImmutableMediaSpoolGroup(rig.group)) })).isEmpty()
        }
    }

    @Test fun malformedTerminalReceiptHashAndUnknownFieldsNeverSeedSeal() = runBlocking<Unit> {
        val corruptions: List<(JsonObject) -> JsonObject> = listOf(
            { JsonObject(it + ("receipts" to JsonArray(emptyList()))) },
            { JsonObject(it + ("request_hash" to JsonPrimitive("0".repeat(64)))) },
            { JsonObject(it + ("stable_version_id" to JsonNull)) },
            { JsonObject(it + ("surprise" to JsonPrimitive(true))) },
        )
        for (corrupt in corruptions) Rig().use { rig ->
            assertThat(rig.switch(originalTransform = { it.copy(payloadJson = corrupt(Json.parseToJsonElement(it.payloadJson).jsonObject).toString()) })).isEmpty()
        }
    }

    @Test fun compactSealContainsTypedDigestsAndPointerWithoutOldBodiesOrCredentials() = runBlocking<Unit> {
        Rig().use { rig ->
            val seal = rig.switch().single()
            val row = requireNotNull(rig.cache.getTransportJournal(seal.key))
            assertThat(RestoreTerminalSpoolSeal.decode(row)).isEqualTo(seal)
            assertThat(row.payloadJson.length).isLessThan(5000)
            assertThat(row.payloadJson).doesNotContain("SENSITIVE-OLD-MUTATION-BODY")
            assertThat(row.payloadJson).doesNotContain("expires_at")
            assertThat(row.payloadJson).doesNotContain("accessToken")
            assertThat(row.payloadJson).doesNotContain("refreshToken")
            val extra = JsonObject(Json.parseToJsonElement(row.payloadJson).jsonObject + ("extra" to JsonPrimitive(true)))
            assertThat(runCatching { RestoreTerminalSpoolSeal.decode(row.copy(payloadJson = extra.toString())) }.isFailure).isTrue()
            assertThat(runCatching { RestoreTerminalSpoolSeal.decode(row.copy(journalKey = seal.key + "x")) }.isFailure).isTrue()
        }
    }

    @Test fun sameTimestampNullLiteralNullAttachmentAndMetadataChangesBlockCapture() = runBlocking<Unit> {
        val changes: List<suspend (Rig) -> Unit> = listOf(
            { it.record = it.record.copy(note = "null") },
            { it.rawMedia.seed(requireNotNull(it.media.getByClientUuid(MEDIA)).copy(width = 12)) },
            { it.rawMedia.seed(requireNotNull(it.media.getByClientUuid(MEDIA)).copy(deletedAt = 999)) },
            { it.rawMedia.seed(requireNotNull(it.media.getByClientUuid(MEDIA)).copy(recordId = 999)) },
            { it.rawMedia.seed(requireNotNull(it.media.getByClientUuid(MEDIA)).copy(localUri = File(it.root, "elsewhere").path)) },
        )
        for (change in changes) Rig().use { rig ->
            assertThat(rig.switch(beforeCapture = { change(rig) })).isEmpty()
            assertThat(rig.spool.recoverGroup(MUTATION)).isNotNull()
        }
    }

    @Test fun targetRequestEvidenceVersionAndBaselineDriftBlockCapture() = runBlocking<Unit> {
        Rig().use { rig -> assertThat(rig.switch(journalTransform = {
            JsonObject(it + ("target" to RestoreTerminalTarget.from(rig.session.copy(deviceId = "foreign")).json()))
        })).isEmpty() }
        Rig().use { rig -> assertThat(rig.switch(journalTransform = {
            JsonObject(it + ("request" to JsonPrimitive(OTHER)))
        })).isEmpty() }
        Rig().use { rig -> assertThat(rig.switch(evidenceVersion = 1)).isEmpty() }
        Rig().use { rig -> assertThat(rig.switch(afterInstall = { rig.record = rig.record.copy(baseVersion = "wrong") })).isEmpty() }
    }

    @Test fun equivalentDefaultHttpsPortKeepsCommittedTargetBinding() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.session = rig.session.copy(serverPort = 443)
            assertThat(rig.switch()).hasSize(1)
            rig.owner().reclaim()
            assertThat(rig.spool.recoverGroup(MUTATION)).isNull()
        }
    }

    @Test fun changedPostSwitchEvidenceWrongTargetMissingMarkerOrCorruptRRetainsC() = runBlocking<Unit> {
        val changes: List<suspend (Rig) -> Unit> = listOf(
            { it.record = it.record.copy(note = "null") },
            { it.session = it.session.copy(pullGeneration = "foreign") },
            { it.cache.deleteTransportJournal("restored-media-bytes-v1:$MEDIA") },
            { it.raw.writeText("FAIL") },
            { it.raw.delete() },
            { it.rawMedia.seed(requireNotNull(it.media.getByClientUuid(MEDIA)).copy(recordId = 777)) },
        )
        for (change in changes) Rig().use { rig ->
            rig.switch(); change(rig); rig.owner().reclaim()
            assertThat(rig.spool.recoverGroup(MUTATION)).isNotNull()
            assertThat(rig.cache.listRestoreTerminalSpoolSeals()).hasSize(1)
        }
    }

    @Test fun allHolderKindsCompetingGroupAndDifferentUuidPathAliasRetainC() = runBlocking<Unit> {
        for (kind in listOf(MediaReferenceHolderKind.STABLE_ROOT, MediaReferenceHolderKind.LOCAL_MUTATION,
            MediaReferenceHolderKind.CONFLICT_BRANCH, MediaReferenceHolderKind.DUPLICATE_SOURCE)) Rig().use { rig ->
            rig.switch()
            rig.refs.upsert(MediaReferenceEntity(MEDIA, kind, "holder", localUri = rig.raw.path, createdAt = 1))
            rig.owner().reclaim()
            assertThat(rig.spool.recoverGroup(MUTATION)).isNotNull()
        }
        Rig().use { rig ->
            rig.switch(); rig.cache.putFrozenMediaSpoolManifest(OTHER, encodeImmutableMediaSpoolGroup(rig.group.copy(mutationId = OTHER)), 0)
            rig.owner().reclaim(); assertThat(rig.spool.recoverGroup(MUTATION)).isNotNull()
        }
        Rig().use { rig ->
            rig.switch()
            val path = rig.spool.ownedGroupPaths(rig.group).media.single().relativeTo(rig.root).path
            rig.rawMedia.seed(requireNotNull(rig.media.getByClientUuid(MEDIA)).copy(id = 2, clientUuid = OTHER, recordId = 999, localUri = path))
            rig.owner().reclaim(); assertThat(rig.spool.recoverGroup(MUTATION)).isNotNull()
        }
        Rig().use { rig ->
            rig.switch()
            rig.rawRefs.upsert(MediaReferenceEntity(OTHER, MediaReferenceHolderKind.CONFLICT_BRANCH, "legacy",
                localUri = rig.spool.ownedGroupPaths(rig.group).media.single().path, createdAt = 1))
            rig.owner().reclaim(); assertThat(rig.spool.recoverGroup(MUTATION)).isNotNull()
        }
    }

    @Test fun existingRelativeAndSymlinkTombstoneAliasesToReplacementRetainC() = runBlocking<Unit> {
        for (symlink in listOf(false, true)) Rig().use { rig ->
            rig.switch()
            val alias = if (symlink) {
                val link = File(rig.root, "raw-alias.jpg")
                Files.createSymbolicLink(link.toPath(), rig.raw.toPath())
                link.path
            } else rig.raw.relativeTo(rig.root).path
            rig.rawMedia.seed(requireNotNull(rig.media.getByClientUuid(MEDIA)).copy(id = 2,
                clientUuid = OTHER, recordId = 999, localUri = alias, deletedAt = 100))
            val seal = RestoreTerminalSpoolSeal.decode(rig.cache.listRestoreTerminalSpoolSeals().single())
            assertThat(rig.rows().exactEvidence("record", ROOT)).isEqualTo(seal.expectedRootEvidence)
            assertThat(rig.rows().exactEvidence("media", MEDIA)).isEqualTo(seal.replacements.single().expectedEvidence)
            rig.owner().reclaim()
            assertThat(rig.spool.recoverGroup(MUTATION)).isNotNull()
            assertThat(rig.raw.readText()).isEqualTo("RAW!")
        }
    }

    @Test fun siblingPrefixCannotMaskCanonicalDirectoryDescendantAlias() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.switch()
            val paths = rig.spool.ownedGroupPaths(rig.group)
            val template = requireNotNull(rig.media.getByClientUuid(MEDIA))
            rig.rawMedia.seed(template.copy(id = 2, clientUuid = OTHER, recordId = 999,
                localUri = paths.directory.path + "-other/photo.jpg"))
            rig.rawMedia.seed(template.copy(id = 3, clientUuid = REQUEST, recordId = 999, localUri = paths.media.single().path))
            val seal = RestoreTerminalSpoolSeal.decode(rig.cache.listRestoreTerminalSpoolSeals().single())
            assertThat(rig.rows().exactEvidence("record", ROOT)).isEqualTo(seal.expectedRootEvidence)
            assertThat(rig.rows().exactEvidence("media", MEDIA)).isEqualTo(seal.replacements.single().expectedEvidence)
            rig.owner().reclaim()
            assertThat(rig.spool.recoverGroup(MUTATION)).isNotNull()
        }
    }

    @Test fun duplicateFrozenPayloadMutationUnderForeignKeyRetainsCanonicalGroup() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.switch()
            rig.cache.putTransportJournal(frozenMediaSpoolCacheKey(OTHER), encodeImmutableMediaSpoolGroup(rig.group), 0)
            rig.owner().reclaim()
            assertThat(rig.spool.recoverGroup(MUTATION)).isNotNull()
            assertThat(rig.cache.listRestoreTerminalSpoolSeals()).hasSize(1)
        }
    }

    @Test fun completeInventoryDetectsNewFrozenGroupDuringReplacementHash() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.switch()
            rig.onRead = { runBlocking { rig.cache.putFrozenMediaSpoolManifest(OTHER,
                encodeImmutableMediaSpoolGroup(rig.group.copy(mutationId = OTHER)), 0) } }
            rig.owner().reclaim()
            assertThat(rig.spool.recoverGroup(MUTATION)).isNotNull()
            assertThat(RestoreTerminalSpoolSeal.decode(rig.cache.listRestoreTerminalSpoolSeals().single()).deleting).isFalse()
        }
    }

    @Test fun publicationAttemptAfterFinalCheckCannotPublishRelativeAliasOrHolder() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.switch()
            var attempts = 0
            rig.owner { point -> if (point == TerminalSpoolRetirementFault.AfterDeletingIntent) runBlocking {
                val alias = rig.spool.ownedGroupPaths(rig.group).media.single().relativeTo(rig.root).path
                assertThat(runCatching { rig.media.upsert(requireNotNull(rig.media.getByClientUuid(MEDIA))
                    .copy(id = 2, clientUuid = OTHER, localUri = alias)) }.isFailure).isTrue()
                assertThat(runCatching { rig.refs.upsert(MediaReferenceEntity(OTHER, MediaReferenceHolderKind.DUPLICATE_SOURCE,
                    "late", localUri = alias, createdAt = 1)) }.isFailure).isTrue()
                attempts += 2
            } }.reclaim()
            assertThat(attempts).isEqualTo(2)
            assertThat(rig.raw.readText()).isEqualTo("RAW!")
            assertThat(rig.spool.recoverGroup(MUTATION)).isNull()
        }
    }

    @Test fun unguardedDaoCannotAuthorizeCaptureOrDeletion() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.switch()
            val unguarded = RestoreTerminalSpoolRetirementOwner(rig.cache, rig.spool, rig.files, rig.rawMedia,
                rig.babies, rig.refs, rig.transactions, rig.cleanup, rig::rows, { rig.session }, commandFence = rig.commandFence)
            unguarded.reclaim()
            assertThat(rig.spool.recoverGroup(MUTATION)).isNotNull()
        }
    }

    @Test fun unlockedCommandFenceFailsBeforeTerminalRetirement() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.switch()
            rig.commandFence.unlock()
            assertThat(runCatching { rig.owner().reclaim() }.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
            assertThat(rig.spool.recoverGroup(MUTATION)).isNotNull()
            assertThat(rig.cache.listRestoreTerminalSpoolSeals()).hasSize(1)
        }
    }

    @Test fun deletingIntentPartialUnlinkAndPostUnlinkCrashRecoverIdempotently() = runBlocking<Unit> {
        for (boundary in listOf("intent", "partial", "unlinked")) Rig().use { rig ->
            rig.switch()
            val failure = runCatching { rig.owner { point ->
                if (point == TerminalSpoolRetirementFault.AfterDeletingIntent && boundary != "unlinked") {
                    if (boundary == "partial") rig.spool.ownedGroupPaths(rig.group).media.single().delete()
                    throw IOException(boundary)
                }
                if (point == TerminalSpoolRetirementFault.AfterUnlink && boundary == "unlinked") throw IOException(boundary)
            }.reclaim() }.exceptionOrNull()
            assertThat(failure).isInstanceOf(IOException::class.java)
            val seal = RestoreTerminalSpoolSeal.decode(rig.cache.listRestoreTerminalSpoolSeals().single())
            assertThat(seal.deleting).isTrue()
            // Generic orphan recovery neither sweeps the owner nor demands an intact old sidecar.
            rig.spool.recoverRetainingTerminalSeals(rig.cache, emptySet())
            rig.owner().reclaim()
            assertThat(rig.cache.listRestoreTerminalSpoolSeals()).isEmpty()
            assertThat(rig.cache.getFrozenMediaSpoolManifest(MUTATION)).isNull()
            assertThat(rig.raw.readText()).isEqualTo("RAW!")
        }
    }

    @Test fun deletingRestartVerifiesRemainingBytesEvenWithoutSidecar() = runBlocking<Unit> {
        for (changed in listOf("FAIL", "x")) Rig().use { rig ->
            rig.switch()
            runCatching { rig.owner { point -> if (point == TerminalSpoolRetirementFault.AfterDeletingIntent)
                throw IOException("crash") }.reclaim() }
            val paths = rig.spool.ownedGroupPaths(rig.group)
            File(paths.directory, "0.json").delete()
            paths.media.single().writeText(changed)
            assertThat(runCatching { rig.spool.recoverRetainingTerminalSeals(rig.cache, emptySet()) }.isFailure).isTrue()
            assertThat(runCatching { rig.owner().reclaim() }.isFailure).isTrue()
            assertThat(paths.media.single().readText()).isEqualTo(changed)
            assertThat(rig.cache.listRestoreTerminalSpoolSeals()).hasSize(1)
        }
        Rig().use { rig ->
            rig.switch()
            runCatching { rig.owner { point -> if (point == TerminalSpoolRetirementFault.AfterDeletingIntent)
                throw IOException("crash") }.reclaim() }
            File(rig.spool.ownedGroupPaths(rig.group).directory, "0.json").delete()
            rig.owner().reclaim()
            assertThat(rig.cache.listRestoreTerminalSpoolSeals()).isEmpty()
        }
    }

    @Test fun missingSealCannotBeReconstructedAndMarkerReencodingPreservesBlockedSeal() = runBlocking<Unit> {
        Rig().use { rig ->
            val seal = rig.switch().single()
            rig.cache.deleteTransportJournal(seal.key)
            rig.owner().reclaim()
            assertThat(rig.spool.recoverGroup(MUTATION)).isNotNull()
        }
        Rig().use { rig ->
            val seal = rig.switch().single()
            rig.cache.putTransportJournal(RestoreArtifactRetirement.KEY,
                RestoreArtifactRetirement.encode("", listOf(MUTATION)), 0)
            rig.raw.writeText("FAIL")
            rig.owner().reclaim()
            assertThat(RestoreTerminalSpoolSeal.decode(rig.cache.listRestoreTerminalSpoolSeals().single())).isEqualTo(seal)
        }
    }

    @Test fun clearCaptureExcludesAvatarSealsAndRejectsMalformedSeals() = runBlocking<Unit> {
        Rig().use { rig ->
            val seal = rig.switch().single()
            val avatar = seal.copy(rootType = "baby", group = seal.group.copy(items = seal.group.items.map { it.copy(role = CausalMediaRole.Avatar) }),
                replacements = seal.replacements.map { it.copy(role = CausalMediaRole.Avatar) })
            rig.cache.putTransportJournal(avatar.key, avatar.encode(), 0)
            assertThat(rig.owner().prepareCommittedClear(LocalDataClearScope.RecordsOnly).mediaClientUuids).isEmpty()
            assertThat(rig.owner().prepareCommittedClear(LocalDataClearScope.AllLocalData).mediaClientUuids).containsExactly(MEDIA)
            rig.cache.putTransportJournal(avatar.key, "{}", 0)
            assertThat(runCatching { rig.owner().prepareCommittedClear(LocalDataClearScope.AllLocalData) }.isFailure).isTrue()
        }
    }

    @Test fun opaqueRestartRequiresExactPlainRoomOwnerEvenAfterDirectoryWasRemoved() = runBlocking<Unit> {
        for (missing in listOf(false, true)) Rig().use { rig ->
            rig.switch()
            runCatching { rig.owner { point -> if (point == TerminalSpoolRetirementFault.AfterUnlink)
                throw IOException("crash") }.reclaim() }
            if (missing) rig.cache.deleteFrozenMediaSpoolManifest(MUTATION) else {
                val changed = rig.group.copy(items = rig.group.items.map { it.copy(sha256 = "0".repeat(64)) })
                rig.cache.putFrozenMediaSpoolManifest(MUTATION, encodeImmutableMediaSpoolGroup(changed), 0)
            }
            assertThat(runCatching { rig.spool.recoverRetainingTerminalSeals(rig.cache, emptySet()) }.isFailure).isTrue()
            assertThat(rig.cache.listRestoreTerminalSpoolSeals()).hasSize(1)
        }
    }

    @Test fun legacyExactByteRetirementNeverBypassesTerminalOwner() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.switch(); rig.rawMedia.deleteAll()
            RestoreArtifactRetirement(rig.cache, rig.media, rig.files, rig.spool, rig.transactions).reclaim()
            assertThat(rig.spool.recoverGroup(MUTATION)).isNotNull()
            assertThat(rig.cache.listRestoreTerminalSpoolSeals()).hasSize(1)
        }
    }

    @Test fun explicitClearCanRetireAlreadyAbsentRowsButNotNewSealWithSameUuid() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.switch(); rig.rawMedia.deleteAll()
            val capture = rig.owner().prepareCommittedClear(LocalDataClearScope.AllLocalData)
            assertThat(capture.mediaClientUuids).containsExactly(MEDIA)
            rig.clear.value = PendingReplicaCleanup(LocalDataClearScope.AllLocalData, rig.session.familyId,
                rig.session.pullGeneration, capture.mediaClientUuids, emptySet())
            rig.owner().bindCommittedClear(requireNotNull(rig.clear.value), capture)
            rig.owner().reclaimAfterCommittedClear()
            assertThat(rig.spool.recoverGroup(MUTATION)).isNull()
        }
        Rig().use { rig ->
            val seal = rig.switch().single(); rig.rawMedia.deleteAll()
            val capture = rig.owner().prepareCommittedClear(LocalDataClearScope.AllLocalData)
            rig.clear.value = PendingReplicaCleanup(LocalDataClearScope.AllLocalData, rig.session.familyId,
                rig.session.pullGeneration, capture.mediaClientUuids, emptySet())
            rig.owner().bindCommittedClear(requireNotNull(rig.clear.value), capture)
            rig.cache.putTransportJournal(seal.key, seal.copy(stableVersion = "different-terminal").encode(), 0)
            rig.owner().reclaimAfterCommittedClear()
            assertThat(rig.spool.recoverGroup(MUTATION)).isNotNull()
        }
    }

    @Test fun recordsClearNeverCapturesHistoricalLogUuidNowOwnedByCurrentAvatar() = runBlocking<Unit> {
        Rig().use { rig ->
            rig.switch()
            val avatar = requireNotNull(rig.media.getByClientUuid(MEDIA)).copy(
                kind = "avatar", recordId = null, babyId = 42,
            )
            rig.rawMedia.seed(avatar)
            rig.rawBabies.seed(localBaby().copy(id = 42, clientUuid = OTHER,
                avatarMediaUuid = MEDIA, avatarPath = rig.raw.path))
            val capture = rig.owner().prepareCommittedClear(LocalDataClearScope.RecordsOnly)
            assertThat(capture.mediaClientUuids).isEmpty()
            assertThat(capture.seals).isEmpty()
            val pending = PendingReplicaCleanup(LocalDataClearScope.RecordsOnly, rig.session.familyId,
                rig.session.pullGeneration, capture.mediaClientUuids, emptySet())
            rig.clear.value = pending
            rig.owner().bindCommittedClear(pending, capture)
            // Mirror the existing clear finish's captured-ID deletion, which must exclude avatar.
            rig.media.deleteByClientUuids(pending.mediaClientUuids.toList())
            rig.owner().reclaimAfterCommittedClear()
            assertThat(rig.media.getByClientUuid(MEDIA)).isEqualTo(avatar)
            assertThat(rig.spool.recoverGroup(MUTATION)).isNotNull()
            assertThat(rig.raw.readText()).isEqualTo("RAW!")
            assertThat(rig.cache.listRestoreTerminalSpoolSeals()).hasSize(1)
        }
        Rig().use { rig ->
            rig.switch(); rig.rawMedia.deleteAll()
            // Truly absent historical log items remain eligible for explicit records clear.
            assertThat(rig.owner().prepareCommittedClear(LocalDataClearScope.RecordsOnly).mediaClientUuids)
                .containsExactly(MEDIA)
        }
    }

    @Test fun clearRequiresExactMarkerAndProtectsSurvivingAvatarAliases() = runBlocking<Unit> {
        for (change in listOf("marker", "avatar", "holder")) Rig().use { rig ->
            rig.switch()
            val capture = rig.owner().prepareCommittedClear(LocalDataClearScope.RecordsOnly)
            rig.clear.value = PendingReplicaCleanup(LocalDataClearScope.RecordsOnly, rig.session.familyId,
                rig.session.pullGeneration, capture.mediaClientUuids, emptySet())
            rig.owner().bindCommittedClear(requireNotNull(rig.clear.value), capture)
            rig.rawMedia.deleteAll()
            when (change) {
                "marker" -> rig.clear.value = requireNotNull(rig.clear.value).copy(familyId = "foreign")
                "avatar" -> rig.rawBabies.seed(localBaby().copy(id = 2, clientUuid = OTHER,
                    avatarPath = rig.spool.ownedGroupPaths(rig.group).media.single().path))
                else -> rig.refs.upsert(MediaReferenceEntity(MEDIA, MediaReferenceHolderKind.STABLE_ROOT, "kept", createdAt = 1))
            }
            rig.owner().reclaimAfterCommittedClear()
            assertThat(rig.spool.recoverGroup(MUTATION)).isNotNull()
        }
    }

    @Test fun realSpoolCoordinatorRetainsAvatarAliasThenRecoversCommittedAllClearDeletion() = runBlocking<Unit> {
        Rig().use { rig ->
            val seal = rig.switch().single()
            val originalSeal = requireNotNull(rig.cache.getTransportJournal(seal.key))
            val originalManifest = requireNotNull(rig.cache.getFrozenMediaSpoolManifest(MUTATION))
            val owned = rig.spool.ownedGroupPaths(rig.group).media.single()
            val relativeAlias = owned.relativeTo(rig.root).path
            val originalLog = requireNotNull(rig.media.getByClientUuid(MEDIA))
            // Historical rows may predate the publication guard. Their two spellings
            // must not trick generic clear into unlinking the private spool file.
            rig.rawMedia.seed(originalLog.copy(localUri = owned.path))
            val avatar = originalLog.copy(id = 2, clientUuid = OTHER, kind = "avatar",
                recordId = null, babyId = 42, localUri = relativeAlias)
            rig.rawMedia.seed(avatar)
            val baby = localBaby().copy(id = 42, clientUuid = REQUEST,
                avatarMediaUuid = OTHER, avatarPath = relativeAlias)
            rig.rawBabies.seed(baby)
            rig.deleteRealFiles = true
            rig.commandFence.unlock() // The real coordinator now owns fence admission.
            val preferences = MemorySyncPreferences(rig.session)
            var recordsClearRoomCalls = 0
            var allClearRoomCalls = 0
            val outsideRoomDepths = mutableListOf<Int>()

            fun freshSpool() = FileImmutableMediaSpool(rig.files, File(rig.root, "causal-media-spool"), 4, 4)
            fun owner(spool: FileImmutableMediaSpool, fault: (TerminalSpoolRetirementFault) -> Unit = {}) =
                RestoreTerminalSpoolRetirementOwner(rig.cache, spool, rig.files, rig.media, rig.babies,
                    rig.refs, rig.transactions, rig.cleanup, rig::rows, { rig.session }, rig.clear,
                    fault = fault, commandFence = rig.commandFence)
            fun coordinator(
                spool: FileImmutableMediaSpool,
                terminal: RestoreTerminalSpoolRetirementOwner,
                afterOwner: suspend () -> Unit = {},
            ) = LocalReplicaClearCoordinator(
                barrier = rig.commandFence, preferences = preferences, babyDao = rig.babies,
                mediaDao = rig.media, mediaFiles = rig.files,
                mediaSpoolClear = ScopedMediaSpoolClear(rig.babies, rig.cache, MemoryConflictSummaryDao(), spool),
                transactionRunner = rig.transactions, pendingStore = rig.clear,
                terminalSpoolOwner = { terminal }, reclaimRestoreFiles = afterOwner,
            )

            val firstSpool = freshSpool()
            val first = coordinator(firstSpool, owner(firstSpool)) {
                outsideRoomDepths += rig.transactions.depth
                assertThat(rig.media.getByClientUuid(MEDIA)).isNull()
                assertThat(rig.media.getByClientUuid(OTHER)).isEqualTo(avatar)
                assertThat(rig.clear.value).isNotNull()
                throw IOException("restart after retained terminal owner")
            }
            val interrupted = first.clear(LocalDataClearScope.RecordsOnly) {
                recordsClearRoomCalls++
                rig.recordPresent = false
                rig.cache.deleteAllTransportJournals()
                rig.refs.deleteForLogAndWakeMedia()
            }.exceptionOrNull()
            assertThat(interrupted).isInstanceOf(LocalClearCommittedException::class.java)
            assertThat(rig.clear.value?.mediaClientUuids).containsExactly(MEDIA)
            assertThat(rig.cache.getTransportJournal(RestoreTerminalSpoolClear.KEY)).isNotNull()
            assertThat(rig.cache.getTransportJournal(seal.key)).isEqualTo(originalSeal)
            assertThat(rig.cache.getFrozenMediaSpoolManifest(MUTATION)).isEqualTo(originalManifest)
            assertThat(owned.readText()).isEqualTo("JPEG")
            assertThat(rig.files.deleted).doesNotContain(owned.path)
            assertThat(rig.files.deleted).doesNotContain(relativeAlias)

            // Recreate all three file/lifecycle/coordinator owners from the durable state.
            val retainedRestartSpool = freshSpool()
            coordinator(retainedRestartSpool, owner(retainedRestartSpool)) {
                outsideRoomDepths += rig.transactions.depth
            }.recoverPending().getOrThrow()
            assertThat(recordsClearRoomCalls).isEqualTo(1)
            assertThat(rig.clear.value).isNull()
            assertThat(rig.cache.getTransportJournal(RestoreTerminalSpoolClear.KEY)).isNull()
            assertThat(rig.media.getByClientUuid(OTHER)).isEqualTo(avatar)
            assertThat(rig.babies.getByClientUuid(REQUEST)).isEqualTo(baby)
            assertThat(rig.cache.getTransportJournal(seal.key)).isEqualTo(originalSeal)
            assertThat(rig.cache.getFrozenMediaSpoolManifest(MUTATION)).isEqualTo(originalManifest)
            assertThat((retainedRestartSpool.recoverGroup(MUTATION) as? ImmutableMediaSpoolRecovery.Complete)?.group)
                .isEqualTo(rig.group)
            assertThat(owned.readText()).isEqualTo("JPEG")
            assertThat(rig.raw.readText()).isEqualTo("RAW!")

            // A later explicit AllLocalData clear may remove the surviving avatar.
            // The sealed log UUID is now absent and must be captured additively.
            val clearingSpool = freshSpool()
            val deletingOwner = owner(clearingSpool) { point ->
                outsideRoomDepths += rig.transactions.depth
                assertThat(rig.clear.value).isNotNull()
                if (point == TerminalSpoolRetirementFault.AfterDeletingIntent) {
                    assertThat(rig.clear.value?.mediaClientUuids).containsExactly(MEDIA, OTHER)
                    assertThat(owned.isFile).isTrue()
                    throw IOException("restart after deleting intent")
                }
            }
            val deletingFailure = coordinator(clearingSpool, deletingOwner).clear(LocalDataClearScope.AllLocalData) {
                allClearRoomCalls++
                rig.cache.deleteAllTransportJournals()
                rig.babies.deleteAll()
                rig.refs.deleteAll()
            }.exceptionOrNull()
            assertThat(deletingFailure).isInstanceOf(LocalClearCommittedException::class.java)
            assertThat(rig.media.listAllIncludingDeleted()).isEmpty()
            val deletingSeal = RestoreTerminalSpoolSeal.decode(rig.cache.listRestoreTerminalSpoolSeals().single())
            assertThat(deletingSeal).isEqualTo(seal.copy(deleting = true))
            assertThat(rig.cache.getFrozenMediaSpoolManifest(MUTATION)).isEqualTo(originalManifest)
            assertThat(rig.cache.getTransportJournal(RestoreTerminalSpoolClear.KEY)).isNotNull()
            assertThat(rig.clear.value).isNotNull()
            assertThat(owned.readText()).isEqualTo("JPEG")
            assertThat(rig.files.deleted).doesNotContain(owned.path)
            assertThat(rig.files.deleted).doesNotContain(relativeAlias)

            val finalSpool = freshSpool()
            coordinator(finalSpool, owner(finalSpool) { outsideRoomDepths += rig.transactions.depth })
                .recoverPending().getOrThrow()
            assertThat(allClearRoomCalls).isEqualTo(1)
            assertThat(rig.media.listAllIncludingDeleted()).isEmpty()
            assertThat(rig.babies.listAllIncludingDeleted()).isEmpty()
            assertThat(rig.clear.value).isNull()
            assertThat(rig.cache.getTransportJournal(RestoreTerminalSpoolClear.KEY)).isNull()
            assertThat(rig.cache.listRestoreTerminalSpoolSeals()).isEmpty()
            assertThat(rig.cache.getFrozenMediaSpoolManifest(MUTATION)).isNull()
            assertThat(finalSpool.recoverGroup(MUTATION)).isNull()
            assertThat(owned.exists()).isFalse()
            assertThat(outsideRoomDepths).isNotEmpty()
            assertThat(outsideRoomDepths.all { it == 0 }).isTrue()
        }
    }

    @Test fun clearRestartInventoryRaceKeepsExactPendingAuthorityUntilTerminalUnlinkCompletes() = runBlocking<Unit> {
        Rig().use { rig ->
            val seal = rig.switch().single()
            val privateBytes = rig.spool.ownedGroupPaths(rig.group).media.single()
            rig.commandFence.unlock()
            val preferences = MemorySyncPreferences(rig.session)
            var clearRoomCalls = 0
            fun spool() = FileImmutableMediaSpool(rig.files, File(rig.root, "causal-media-spool"), 4, 4)
            fun owner(
                spool: FileImmutableMediaSpool,
                transactions: DatabaseTransactionRunner = rig.transactions,
                fault: (TerminalSpoolRetirementFault) -> Unit = {},
            ) = RestoreTerminalSpoolRetirementOwner(rig.cache, spool, rig.files, rig.media, rig.babies,
                rig.refs, transactions, rig.cleanup, rig::rows, { rig.session }, rig.clear,
                fault = fault, metrics = rig.metrics, commandFence = rig.commandFence)
            fun coordinator(spool: FileImmutableMediaSpool, owner: RestoreTerminalSpoolRetirementOwner) =
                LocalReplicaClearCoordinator(rig.commandFence, preferences, rig.babies, rig.media,
                    rig.files, ScopedMediaSpoolClear(rig.babies, rig.cache, MemoryConflictSummaryDao(), spool),
                    rig.transactions, rig.clear, terminalSpoolOwner = { owner })

            val initialSpool = spool()
            val initial = coordinator(initialSpool, owner(initialSpool, fault = { point ->
                if (point == TerminalSpoolRetirementFault.AfterDeletingIntent)
                    throw IOException("process ended after deleting intent")
            }))
            assertThat(initial.clear(LocalDataClearScope.AllLocalData) {
                clearRoomCalls++
                rig.recordPresent = false
                rig.babies.deleteAll()
                rig.refs.deleteAll()
                rig.cache.deleteAllTransportJournals()
            }.exceptionOrNull()).isInstanceOf(LocalClearCommittedException::class.java)
            val pending = requireNotNull(rig.clear.value)
            val binding = requireNotNull(rig.cache.getTransportJournal(RestoreTerminalSpoolClear.KEY))
            val deletingSeal = requireNotNull(rig.cache.getTransportJournal(seal.key))
            val manifest = requireNotNull(rig.cache.getFrozenMediaSpoolManifest(MUTATION))
            assertThat(privateBytes.readText()).isEqualTo("JPEG")

            // Restart recovery has no domain exclusion: emulate an unrelated valid domain
            // write after the owner's first read transaction releases, before its final CAS.
            val newBaby = localBaby().copy(id = 99, clientUuid = OTHER, nickname = "created after committed clear")
            var inject = true
            var writes = 0
            val readsBeforeRestart = rig.metrics.reclaimInventoryReads
            val interleavingTransactions = object : DatabaseTransactionRunner {
                override suspend fun <T> run(block: suspend () -> T): T {
                    val result = rig.transactions.run(block)
                    if (inject && rig.metrics.reclaimInventoryReads > readsBeforeRestart) {
                        inject = false
                        assertThat(rig.transactions.depth).isEqualTo(0)
                        rig.babies.upsert(newBaby)
                        writes++
                    }
                    return result
                }
            }
            val restartedSpool = spool()
            val interrupted = coordinator(restartedSpool, owner(restartedSpool, interleavingTransactions))
                .recoverPending().exceptionOrNull()
            assertThat(writes).isEqualTo(1)
            assertThat(rig.metrics.reclaimInventoryReads - readsBeforeRestart).isEqualTo(2)
            assertThat(interrupted).isInstanceOf(LocalClearCommittedException::class.java)
            assertThat(interrupted!!.cause).isInstanceOf(TerminalSpoolCleanupIncompleteException::class.java)
            assertThat(rig.clear.value).isEqualTo(pending)
            assertThat(rig.cache.getTransportJournal(RestoreTerminalSpoolClear.KEY)).isEqualTo(binding)
            assertThat(rig.cache.getTransportJournal(seal.key)).isEqualTo(deletingSeal)
            assertThat(rig.cache.getFrozenMediaSpoolManifest(MUTATION)).isEqualTo(manifest)
            assertThat(privateBytes.readText()).isEqualTo("JPEG")
            assertThat(rig.babies.getByClientUuid(OTHER)).isEqualTo(newBaby)

            val finalSpool = spool()
            coordinator(finalSpool, owner(finalSpool)).recoverPending().getOrThrow()
            assertThat(clearRoomCalls).isEqualTo(1)
            assertThat(rig.babies.getByClientUuid(OTHER)).isEqualTo(newBaby)
            assertThat(rig.clear.value).isNull()
            assertThat(rig.cache.getTransportJournal(RestoreTerminalSpoolClear.KEY)).isNull()
            assertThat(rig.cache.getTransportJournal(seal.key)).isNull()
            assertThat(rig.cache.getFrozenMediaSpoolManifest(MUTATION)).isNull()
            assertThat(finalSpool.recoverGroup(MUTATION)).isNull()
        }
    }

    @Test fun captureIndexesWholeInventoryOnceAtRepresentativeScales() = runBlocking<Unit> {
        for (size in listOf(100, 1000)) Rig().use { rig ->
            val template = requireNotNull(rig.media.getByClientUuid(MEDIA))
            repeat(size) { index -> rig.rawMedia.seed(template.copy(id = index + 2L,
                clientUuid = java.util.UUID(9, index + 1L).toString(), recordId = 999,
                localUri = File(rig.root, "other-$index.jpg").path)) }
            // Extra valid terminal envelopes have no selected replacement: they retain, but
            // must not cause another full inventory/path-resolution pass for each envelope.
            val seals = rig.switch(extraTerminalGroups = size)
            assertThat(seals).hasSize(1)
            assertThat(rig.metrics.captureInventoryReads).isEqualTo(1)
            assertThat(rig.metrics.captureRowsIndexed).isEqualTo(size + 1L)
            assertThat(rig.metrics.captureInventoryPathResolutions).isEqualTo(size + 1L)
        }
    }

    private class ClearStore : PendingReplicaCleanupStore {
        var value: PendingReplicaCleanup? = null
        override suspend fun load() = value
        override suspend fun stage(pending: PendingReplicaCleanup) { value = pending }
        override suspend fun delete() { value = null }
    }

    private class Rig(replacement: String = "RAW!") : AutoCloseable {
        val root = Files.createTempDirectory("terminal-spool").toFile()
        val raw = File(root, "raw.jpg").apply { writeText(replacement) }
        val canonical = File(root, "canonical.jpg").apply { writeText("JPEG") }
        val transactions = RecordingTransactionRunner()
        val metrics = TerminalSpoolRetirementMetrics()
        val commandFence = kotlinx.coroutines.sync.Mutex(locked = true)
        val cache = MemoryConflictSnapshotCacheDao()
        val rawMedia = MemoryMediaDao()
        val rawBabies = MemoryBabyDao()
        val rawRefs = MemoryMediaReferenceDao()
        val policy = PrivateSpoolPathPolicy(root)
        val media = policy.guard(rawMedia, transactions)
        val babies = policy.guard(rawBabies, transactions)
        val refs = policy.guard(rawRefs, transactions)
        var onRead: (() -> Unit)? = null
        var deleteRealFiles = false
        val files = object : TestMediaFileStore() {
            override fun readableFile(localUri: String): File? {
                check(transactions.depth == 0) { "filesystem access inside Room" }
                onRead?.also { onRead = null }?.invoke()
                return super.readableFile(localUri)
            }
            override suspend fun delete(localUri: String) {
                super.delete(localUri)
                if (deleteRealFiles) policy.resolvedPath(localUri)?.let { file ->
                    check(file.delete() || !file.exists())
                }
            }
        }.apply { filesRoot = root }
        val spool = FileImmutableMediaSpool(files, File(root, "causal-media-spool"), 4, 4)
        val cleanup = ReferenceAwareMediaFileCleanup(media, refs, files, transactions, MediaLocalPathGate())
        val clear = ClearStore()
        var session = joinedSession("family-a")
        var record = localRecord(1).copy(id = 1, clientUuid = ROOT, note = null, syncDirty = false,
            baseVersion = "stable-old", mutationId = null)
        var recordPresent = true
        lateinit var group: ImmutableMediaSpoolGroup
        init { rawMedia.seed(MediaAssetEntity(id = 1, recordId = 1, clientUuid = MEDIA,
            localUri = raw.path, mime = "image/jpeg", byteSize = raw.length(), createdAt = 10, updatedAt = 10,
            syncDirty = false, sha256 = digest(raw.readBytes()))) }
        suspend fun rows() = CapturedRestoreRows(babies.listAllIncludingDeleted(), if (recordPresent) listOf(record) else emptyList(), emptyList(),
            emptyList(), emptyList(), emptyList(), media.listAllIncludingDeleted())
        fun owner(fault: (TerminalSpoolRetirementFault) -> Unit = {}) = RestoreTerminalSpoolRetirementOwner(
            cache, spool, files, media, babies, refs, transactions, cleanup, ::rows, { session }, clear, fault, metrics, commandFence)
        suspend fun freezeAnother() = spool.freezeGroup(OTHER, listOf(ImmutableMediaSpoolSource(OTHER,
            CausalMediaRole.Log, canonical.path, PublishedMediaIdentity(digest(canonical.readBytes()), 4, "image/jpeg", null, null))))

        suspend fun switch(
            phase: CausalMediaSettlementPhase = CausalMediaSettlementPhase.CleanupAccepted,
            originalTransform: (CausalTransportJournalEntity) -> CausalTransportJournalEntity = { it },
            journalTransform: (JsonObject) -> JsonObject = { it },
            beforeCapture: suspend () -> Unit = {},
            afterInstall: suspend () -> Unit = {},
            evidenceVersion: Int = 2,
            extraTerminalGroups: Int = 0,
        ): List<RestoreTerminalSpoolSeal> {
            group = spool.freezeGroup(MUTATION, listOf(ImmutableMediaSpoolSource(MEDIA, CausalMediaRole.Log,
                canonical.path, PublishedMediaIdentity(digest(canonical.readBytes()), 4, "image/jpeg", null, null))))
            val item = group.items.single()
            val mutation = CausalMutationUnit(MUTATION, "old", "record", ROOT,
                "{\"note\":\"SENSITIVE-OLD-MUTATION-BODY\"}", listOf(CausalMediaItem(MEDIA, "log", item.sha256, item.byteSize, item.mime)))
            val terminal = CausalMediaSettlementJournal(CausalMediaSettlementBinding(MUTATION, "record", ROOT,
                10, causalMutationContentHash(mutation)), mutation, group,
                if (phase == CausalMediaSettlementPhase.Pending) emptyList() else listOf(CausalMediaPreimageReceipt(MEDIA,
                    "staged", item.byteSize, item.sha256, 1000)), phase,
                if (phase.cleanupEligible || phase == CausalMediaSettlementPhase.Branched) "stable-old" else null,
                if (phase == CausalMediaSettlementPhase.Branched) "conflict" else null,
                if (phase == CausalMediaSettlementPhase.Branched) "branch" else null)
            val original = originalTransform(CausalTransportJournalEntity(frozenMediaSpoolCacheKey(MUTATION), encodeCausalMediaSettlement(terminal), 10))
            val originals = listOf(original) + (0 until extraTerminalGroups).map { index ->
                val id = java.util.UUID.nameUUIDFromBytes("mutation-$index".toByteArray()).toString()
                val mediaId = java.util.UUID.nameUUIDFromBytes("media-$index".toByteArray()).toString()
                val otherGroup = group.copy(mutationId = id, items = group.items.map { it.copy(mediaUuid = mediaId) })
                val otherMutation = mutation.copy(mutationId = id, media = mutation.media.map { it.copy(mediaUuid = mediaId) })
                val otherTerminal = terminal.copy(binding = terminal.binding.copy(mutationId = id,
                    requestHash = causalMutationContentHash(otherMutation)), mutation = otherMutation,
                    manifest = otherGroup, receipts = terminal.receipts.map { it.copy(mediaUuid = mediaId) })
                CausalTransportJournalEntity(frozenMediaSpoolCacheKey(id), encodeCausalMediaSettlement(otherTerminal), 10)
            }
            val selected = rows()
            val pointer = RestoreFileSnapshotPointer(REQUEST, "a".repeat(64), 100)
            val selectedMedia = RestoreFileSnapshotMedia(MEDIA, raw.length(), digest(raw.readBytes()), "image/jpeg", null, null)
            val snapshot = DisasterRecoverySnapshot(emptyList(), emptyList(), DisasterRecoverySummary(0, 1, 0, 0, 0, 1, raw.length()),
                listOf(DisasterRestoreEntityVersion("record", ROOT, record.updatedAt, true, selected.exactEvidence("record", ROOT)),
                    DisasterRestoreEntityVersion("media", MEDIA, 10, true, selected.exactEvidence("media", MEDIA))),
                RestoreFileSnapshot(pointer, "{}", listOf(selectedMedia), root.toPath()), evidenceVersion = evidenceVersion)
            val checkpoint = DisasterRestoreCheckpoint(BATCH, TrustedEndpointProfile.systemPki(session.baseUrl), session.familyId,
                REQUEST, OTHER, MUTATION, 1000, "committed", emptyList())
            val journal = journalTransform(buildJsonObject {
                put("format", 2); put("phase", "committed"); put("request", REQUEST)
                put("manifest_sha256", pointer.manifestSha256); put("manifest_bytes", pointer.manifestByteSize)
                put("target", RestoreTerminalTarget.from(session).json())
            })
            beforeCapture()
            val before = rows()
            record = record.copy(baseVersion = RestoreAuthority.baseline(BATCH, "record", ROOT))
            cache.putFrozenMediaSpoolManifest(MUTATION, encodeImmutableMediaSpoolGroup(group), 0)
            cache.putTransportJournal("restored-media-bytes-v1:$MEDIA", raw.path, 10)
            cache.putTransportJournal(RestoreArtifactRetirement.KEY, RestoreArtifactRetirement.encode("", listOf(MUTATION)), 0)
            afterInstall()
            return transactions.run {
                owner().captureAcceptedSwitch(snapshot, journal, checkpoint, session, before, originals, mapOf(MEDIA to raw.path))
                    .also { owner().persistCaptured(it) }
            }
        }
        override fun close() { root.deleteRecursively() }
    }

    companion object {
        private const val MUTATION = "10000000-0000-4000-8000-000000000001"
        private const val MEDIA = "10000000-0000-4000-8000-000000000002"
        private const val ROOT = "10000000-0000-4000-8000-000000000003"
        private const val REQUEST = "10000000-0000-4000-8000-000000000004"
        private const val BATCH = "10000000-0000-4000-8000-000000000005"
        private const val OTHER = "10000000-0000-4000-8000-000000000006"
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
