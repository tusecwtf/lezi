package com.lezi.babylog.sync.disasterrecovery

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class RestoreFileSnapshotStoreTest {
    @Test fun localRetirementLookupFindsPreparedUnboundIntentWithoutAllowingReplay() = runBlocking {
        val directory = Files.createTempDirectory("restore-file-snapshot-local-retirement").toFile()
        try {
            val root = File(directory, "restore")
            val store = fixtureStore(root)
            assertThat(store.completedForLocalRetirement(REQUEST)).isNull()
            val active = store.capture(REQUEST, emptyList(), 1024) { "{}" }
            val compact = store.prepareRetirement(active, RestoreFileRetirementReason.Cancelled)
            val restarted = fixtureStore(root)
            assertThat(runCatching { restarted.completed(REQUEST) }.exceptionOrNull())
                .isInstanceOf(RestoreFileSnapshotRetirementPendingException::class.java)
            assertThat(restarted.completedForLocalRetirement(REQUEST)).isEqualTo(active)
            assertThat(restarted.preparedRetirement(requireNotNull(restarted.completedForLocalRetirement(REQUEST)))?.pointer)
                .isEqualTo(compact)
            restarted.finishRetirement(compact) { it == compact }
            assertThat(restarted.completedForLocalRetirement(REQUEST)).isNull()
            restarted.discard(REQUEST)
            assertThat(runCatching { restarted.completedForLocalRetirement(REQUEST) }.exceptionOrNull())
                .isInstanceOf(RestoreFileSnapshotRetirementPendingException::class.java)
        } finally { directory.deleteRecursively() }
    }

    @Test fun partialCopyRequiresActualMatchingPrefixAndNeverUsesAnOldReceiptGuess() = runBlocking {
        val directory = Files.createTempDirectory("restore-file-snapshot-partial-prefix").toFile()
        try {
            val expected = ByteArray(70_000) { (it % 251).toByte() }
            val source = File(directory, "source.jpg").apply { writeBytes(expected) }
            val root = File(directory, "restore")
            val sources = listOf(RestoreFileSnapshotSource(PHOTO, source, "image/jpeg", null, null))
            val interrupted = fixtureStore(root, faultInjector = {
                if (it == RestoreFileSnapshotFaultPoint.AfterMediaChunk) throw SimulatedProcessDeath()
            })
            assertThat(runCatching { interrupted.capture(REQUEST, sources, 1024) { "{}" } }.exceptionOrNull())
                .isInstanceOf(SimulatedProcessDeath::class.java)
            val owned = File(File(root, REQUEST), "$PHOTO.media")
            val prefixSize = owned.length().toInt()
            assertThat(prefixSize).isGreaterThan(0)
            assertThat(prefixSize).isLessThan(expected.size)
            source.writeBytes(expected.copyOf().apply { this[0] = (this[0].toInt() xor 127).toByte() })
            val restarted = fixtureStore(root)
            assertThat(runCatching { restarted.capture(REQUEST, sources, 1024) { "{}" } }.exceptionOrNull())
                .isInstanceOf(RestoreFileSnapshotUnresolvedException::class.java)
            assertThat(owned.readBytes()).isEqualTo(expected.copyOf(prefixSize))
            source.writeBytes(expected)
            val complete = restarted.read(restarted.capture(REQUEST, sources, 1024) { "{}" })
            assertThat(restarted.ownedFile(complete, complete.media.single()).readBytes()).isEqualTo(expected)
            assertThat(restarted.metrics.incompleteSourceBytesRead).isAtLeast(prefixSize.toLong())
            assertThat(restarted.metrics.incompleteOwnedBytesRead).isAtLeast(prefixSize.toLong())
        } finally { directory.deleteRecursively() }
    }

    @Test fun corruptOrMissingSourceMappingKeepsOwnedBytesEvenWithAProvidedGate() = runBlocking {
        listOf(false, true).forEach { missing ->
            val directory = Files.createTempDirectory("restore-file-snapshot-source-mapping").toFile()
            try {
                val source = File(directory, "source.jpg").apply { writeText("abc") }
                val root = File(directory, "restore")
                val sources = listOf(RestoreFileSnapshotSource(PHOTO, source, "image/jpeg", null, null))
                val interrupted = fixtureStore(root, faultInjector = {
                    if (it == RestoreFileSnapshotFaultPoint.AfterMediaSync) throw SimulatedProcessDeath()
                })
                runCatching { interrupted.capture(REQUEST, sources, 1024) { "{}" } }
                val mapping = File(File(root, REQUEST), "sources.json")
                if (missing) check(mapping.delete()) else mapping.writeText("{}")
                assertThat(runCatching { fixtureStore(root).capture(REQUEST, sources, 1024) { "{}" } }.exceptionOrNull())
                    .isInstanceOf(RestoreFileSnapshotUnresolvedException::class.java)
                assertThat(File(File(root, REQUEST), "$PHOTO.media").readText()).isEqualTo("abc")
                assertThat(source.readText()).isEqualTo("abc")
            } finally { directory.deleteRecursively() }
        }
    }

    @Test fun completedLegacyOwnerRemainsReadableWhileUnmappedLegacyPartialCopyIsRetained() = runBlocking {
        listOf(false, true).forEach { completed ->
            val directory = Files.createTempDirectory("restore-file-snapshot-legacy-owner").toFile()
            try {
                val source = File(directory, "source.jpg").apply { writeText("abc") }
                val root = File(directory, "restore")
                val sources = listOf(RestoreFileSnapshotSource(PHOTO, source, "image/jpeg", 17, 23))
                val store = fixtureStore(root, faultInjector = {
                    if (!completed && it == RestoreFileSnapshotFaultPoint.AfterMediaSync) throw SimulatedProcessDeath()
                })
                val pointer = runCatching { store.capture(REQUEST, sources, 1024) { "{}" } }.getOrNull()
                File(root, "$REQUEST.owner").writeText(buildJsonObject {
                    put("contract", "restore_file_owner_v1"); put("request_id", REQUEST)
                    put("manifest_budget", 1024); put("media_count", 1)
                }.toString())
                check(File(File(root, REQUEST), "sources.json").delete())
                val restarted = fixtureStore(root)
                if (completed) {
                    check(source.delete())
                    assertThat(restarted.completed(REQUEST)).isEqualTo(pointer)
                    val snapshot = restarted.read(requireNotNull(pointer))
                    assertThat(restarted.ownedFile(snapshot, snapshot.media.single()).readText()).isEqualTo("abc")
                } else {
                    assertThat(runCatching { restarted.capture(REQUEST, sources, 1024) { "{}" } }.exceptionOrNull())
                        .isInstanceOf(RestoreFileSnapshotUnresolvedException::class.java)
                    assertThat(File(File(root, REQUEST), "$PHOTO.media").readText()).isEqualTo("abc")
                    assertThat(restarted.completed(REQUEST)).isNull()
                }
            } finally { directory.deleteRecursively() }
        }
    }

    @Test fun unguardedIncompleteCopiesRemainQuarantinedEvenIfOriginalStillExists() = runBlocking {
        val directory = Files.createTempDirectory("restore-file-snapshot-no-source-gate").toFile()
        try {
            val source = File(directory, "source.jpg").apply { writeText("abc") }
            val root = File(directory, "restore")
            val store = RestoreFileSnapshotStore(root, faultInjector = {
                if (it == RestoreFileSnapshotFaultPoint.AfterMediaSync) throw java.io.IOException("capture failed")
            })
            val sources = listOf(RestoreFileSnapshotSource(PHOTO, source, "image/jpeg", null, null))
            assertThat(runCatching { store.capture(REQUEST, sources, 1024) { "{}" } }.exceptionOrNull())
                .isInstanceOf(RestoreFileSnapshotUnresolvedException::class.java)
            assertThat(File(File(root, REQUEST), "$PHOTO.media").readText()).isEqualTo("abc")
            assertThat(runCatching { store.discard(REQUEST) }.exceptionOrNull())
                .isInstanceOf(RestoreFileSnapshotUnresolvedException::class.java)
            assertThat(File(File(root, REQUEST), "$PHOTO.media").readText()).isEqualTo("abc")
            assertThat(store.completed(REQUEST)).isNull()
            assertThat(source.readText()).isEqualTo("abc")
        } finally { directory.deleteRecursively() }
    }

    @Test fun incompleteCleanupLocksRecordedAndCurrentUriAliasesAlongWithActualPaths() = runBlocking {
        val directory = Files.createTempDirectory("restore-file-snapshot-source-keys").toFile()
        try {
            val source = File(directory, "source.jpg").apply { writeText("abc") }
            val root = File(directory, "restore")
            val original = RestoreFileSnapshotSource(PHOTO, source, "image/jpeg", null, null, "avatars/original.jpg")
            val interrupted = fixtureStore(root, faultInjector = {
                if (it == RestoreFileSnapshotFaultPoint.AfterMediaSync) throw SimulatedProcessDeath()
            })
            assertThat(runCatching { interrupted.capture(REQUEST, listOf(original), 1024) { "{}" } }.exceptionOrNull())
                .isInstanceOf(SimulatedProcessDeath::class.java)
            var lockedKeys = emptySet<String>()
            val restarted = RestoreFileSnapshotStore(root, incompleteSourceGuard = { keys, cleanup ->
                lockedKeys = keys.toSet()
                cleanup()
            })
            val current = original.copy(guardPath = "avatars/renamed.jpg")
            restarted.capture(REQUEST, listOf(current), 1024) { "{}" }
            assertThat(lockedKeys).containsAtLeast("avatars/original.jpg", "avatars/renamed.jpg", source.path,
                File(File(root, REQUEST), "$PHOTO.media").path)
            Unit
        } finally { directory.deleteRecursively() }
    }

    @Test fun retryAfterProcessDeathPreservesMissingOrChangedOriginalUntilAnExplicitRepair() = runBlocking {
        listOf(true, false).forEach { missing ->
            val directory = Files.createTempDirectory("restore-file-snapshot-retry-sole-copy").toFile()
            try {
                val source = File(directory, "source.jpg").apply { writeText("abc") }
                val root = File(directory, "restore")
                val sources = listOf(RestoreFileSnapshotSource(PHOTO, source, "image/jpeg", null, null))
                val interrupted = fixtureStore(root, faultInjector = {
                    if (it == RestoreFileSnapshotFaultPoint.AfterMediaSync) throw SimulatedProcessDeath()
                })
                assertThat(runCatching { interrupted.capture(REQUEST, sources, 1024) { "{}" } }.exceptionOrNull())
                    .isInstanceOf(SimulatedProcessDeath::class.java)
                if (missing) check(source.delete()) else source.writeText("xyz")
                val restarted = fixtureStore(root)
                assertThat(runCatching { restarted.capture(REQUEST, sources, 1024) { "{}" } }.exceptionOrNull())
                    .isInstanceOf(RestoreFileSnapshotUnresolvedException::class.java)
                assertThat(File(File(root, REQUEST), "$PHOTO.media").readText()).isEqualTo("abc")
                assertThat(File(root, "$REQUEST.owner").isFile).isTrue()
                assertThat(restarted.completed(REQUEST)).isNull()
                if (!missing) assertThat(source.readText()).isEqualTo("xyz")
                source.writeText("abc")
                val repaired = restarted.read(restarted.capture(REQUEST, sources, 1024) { "{}" })
                assertThat(restarted.ownedFile(repaired, repaired.media.single()).readText()).isEqualTo("abc")
            } finally { directory.deleteRecursively() }
        }
    }

    @Test fun incompleteCaptureKeepsItsOnlyOwnedCopyWhenOriginalDisappearsAfterFileSync() = runBlocking {
        listOf(false, true).forEach { cancellation ->
            val directory = Files.createTempDirectory("restore-file-snapshot-incomplete-sole-copy").toFile()
            try {
                val source = File(directory, "source.jpg").apply { writeText("abc") }
                val root = File(directory, "restore")
                val store = fixtureStore(root, faultInjector = { point ->
                    if (point == RestoreFileSnapshotFaultPoint.AfterMediaSync) {
                        check(source.delete())
                        if (cancellation) throw CancellationException("cancel after source disappearance")
                        throw java.io.IOException("manifest handoff failed after source disappearance")
                    }
                })
                assertThat(runCatching {
                    store.capture(REQUEST,
                        listOf(RestoreFileSnapshotSource(PHOTO, source, "image/jpeg", null, null)), 1024) { "{}" }
                }.isFailure).isTrue()
                val owned = File(File(root, REQUEST), "$PHOTO.media")
                assertThat(owned.isFile).isTrue()
                assertThat(owned.readText()).isEqualTo("abc")
                assertThat(File(root, "$REQUEST.owner").isFile).isTrue()
                assertThat(fixtureStore(root).completed(REQUEST)).isNull()
                assertThat(source.exists()).isFalse()
            } finally { directory.deleteRecursively() }
        }
    }

    @Test fun currentCleanupPreservesPreparedNewerSiblingAndUnrelatedMetadata() = runBlocking {
        val directory = Files.createTempDirectory("restore-file-snapshot-candidates").toFile()
        try {
            val source = File(directory, "source.jpg").apply { writeText("abc") }
            val root = File(directory, "restore")
            val store = fixtureStore(root)
            val uuids = (1L..4L).map { java.util.UUID(0L, it).toString() }
            val active = store.capture(REQUEST, uuids.map {
                RestoreFileSnapshotSource(it, source, "image/jpeg", null, null)
            }, 1024) { "{}" }
            val first = store.prepareRetirement(active, RestoreFileRetirementReason.Cancelled)
            val second = store.preparePrune(first, uuids.drop(1).toSet())
            val newer = store.preparePrune(second, setOf(uuids[2], uuids[3]))
            val sibling = store.preparePrune(second, setOf(uuids[1], uuids[3]))
            val unrelated = File(File(root, REQUEST), "ownership-" + "e".repeat(64) + ".json").apply { writeText("unbound fixture") }
            var committed = second
            assertThat(store.finishPrune(newer, { it == committed }) { _, _ -> error("uncommitted file callback") }).isFalse()
            assertThat(store.finishPrune(second, { it == committed }) { _, _ -> false }).isFalse()
            assertThat(store.readRetirement(newer).media.map { it.clientUuid }).containsExactly(uuids[2], uuids[3])
            assertThat(store.readRetirement(sibling).media.map { it.clientUuid }).containsExactly(uuids[1], uuids[3])
            committed = newer
            assertThat(store.finishPrune(newer, { it == committed }) { _, _ -> false }).isFalse()
            assertThat(runCatching { store.readRetirement(second) }.isFailure).isTrue()
            assertThat(store.readRetirement(sibling).originalMedia).hasSize(4)
            assertThat(store.finishPrune(sibling, { it == committed }) { _, _ -> error("stale sibling callback") }).isFalse()
            assertThat(unrelated.readText()).isEqualTo("unbound fixture")
            val current = store.readRetirement(newer)
            current.originalMedia.forEach { assertThat(store.ownedFile(current, it).readText()).isEqualTo("abc") }
        } finally { directory.deleteRecursively() }
    }

    @Test fun ownerRevocationBetweenAncestorUnlinksPreservesTheRemainingReceiptForRetry() = runBlocking {
        val directory = Files.createTempDirectory("restore-file-snapshot-revoked-cleanup").toFile()
        try {
            val source = File(directory, "source.jpg").apply { writeText("abc") }
            val root = File(directory, "restore")
            val store = fixtureStore(root)
            val active = store.capture(REQUEST,
                listOf(RestoreFileSnapshotSource(PHOTO, source, "image/jpeg", null, null)), 1024) { "{}" }
            val first = store.prepareRetirement(active, RestoreFileRetirementReason.Cancelled)
            val current = store.preparePrune(first, emptySet())
            var authorizationChecks = 0
            assertThat(store.finishPrune(current, { it == current && ++authorizationChecks < 3 }) { _, _ -> false }).isFalse()
            val manifest = File(File(root, REQUEST), "ownership-${first.ownershipSha256}.json")
            val receipt = File(File(root, REQUEST), "ownership-${first.ownershipSha256}.complete.json")
            assertThat(manifest.exists()).isFalse()
            assertThat(receipt.exists()).isTrue()
            assertThat(store.readRetirement(current).omittedMediaUuids).containsExactly(PHOTO)
            assertThat(store.finishPrune(current, { it == current }) { _, _ -> false }).isFalse()
            assertThat(receipt.exists()).isFalse()
        } finally { directory.deleteRecursively() }
    }

    @Test fun corruptOlderAncestorIsDetectedBeforeAnyCollectedMetadataIsRemoved() = runBlocking {
        val directory = Files.createTempDirectory("restore-file-snapshot-corrupt-ancestor").toFile()
        try {
            val source = File(directory, "source.jpg").apply { writeText("abc") }
            val root = File(directory, "restore")
            val store = fixtureStore(root)
            val uuids = (1L..3L).map { java.util.UUID(0L, it).toString() }
            val active = store.capture(REQUEST, uuids.map {
                RestoreFileSnapshotSource(it, source, "image/jpeg", null, null)
            }, 1024) { "{}" }
            val first = store.prepareRetirement(active, RestoreFileRetirementReason.Cancelled)
            val second = store.preparePrune(first, uuids.drop(1).toSet())
            val current = store.preparePrune(second, uuids.drop(2).toSet())
            val corrupt = File(File(root, REQUEST), "ownership-${first.ownershipSha256}.json").apply { writeText("{}") }
            assertThat(runCatching { store.finishPrune(current, { it == current }) { _, _ -> false } }.isFailure).isTrue()
            assertThat(store.readRetirement(second).originalMedia).hasSize(3)
            assertThat(store.readRetirement(current).originalMedia).hasSize(3)
            assertThat(corrupt.readText()).isEqualTo("{}")
        } finally { directory.deleteRecursively() }
    }

    @Test fun selfContainedRevisionRejectsPredecessorWithoutAnyOmission() = runBlocking {
        val directory = Files.createTempDirectory("restore-file-snapshot-invalid-revision").toFile()
        try {
            val root = File(directory, "restore")
            val store = fixtureStore(root)
            val active = store.capture(REQUEST, emptyList(), 1024) { "{}" }
            val initial = store.prepareRetirement(active, RestoreFileRetirementReason.Cancelled)
            val owned = File(root, REQUEST)
            val original = Json.parseToJsonElement(File(owned, "ownership-${initial.ownershipSha256}.json").readText()).jsonObject
            val previous = buildJsonObject {
                put("request_id", REQUEST); put("sha256", initial.ownershipSha256); put("byte_size", initial.ownershipByteSize)
            }
            val invalid = JsonObject(original + ("previous" to previous)).toString().toByteArray()
            val sha = java.security.MessageDigest.getInstance("SHA-256").digest(invalid).joinToString("") { "%02x".format(it) }
            File(owned, "ownership-$sha.json").writeBytes(invalid)
            File(owned, "ownership-$sha.complete.json").writeText(buildJsonObject {
                put("contract", "restore_file_retirement_completion_v1"); put("request_id", REQUEST)
                put("sha256", sha); put("byte_size", invalid.size)
            }.toString())
            assertThat(runCatching { store.readRetirement(RestoreFileRetirementPointer(REQUEST, sha, invalid.size.toLong())) }.isFailure).isTrue()
        } finally { directory.deleteRecursively() }
    }

    @Test fun interruptedAncestorCleanupLeavesTraversableNewerMetadataAndExactRetryableTails() = runBlocking {
        listOf(RestoreFileSnapshotFaultPoint.AfterAncestorManifestDelete,
            RestoreFileSnapshotFaultPoint.AfterAncestorReceiptDelete).forEach { stop ->
            val directory = Files.createTempDirectory("restore-file-snapshot-ancestor-interrupt").toFile()
            try {
                val source = File(directory, "source.jpg").apply { writeText("abc") }
                val root = File(directory, "restore")
                val store = fixtureStore(root)
                val uuids = (1L..3L).map { java.util.UUID(0L, it).toString() }
                val active = store.capture(REQUEST, uuids.map {
                    RestoreFileSnapshotSource(it, source, "image/jpeg", null, null)
                }, 1024) { "{}" }
                val first = store.prepareRetirement(active, RestoreFileRetirementReason.Cancelled)
                val second = store.preparePrune(first, uuids.drop(1).toSet())
                val current = store.preparePrune(second, uuids.drop(2).toSet())
                val interrupted = fixtureStore(root, faultInjector = { if (it == stop) throw SimulatedProcessDeath() })
                assertThat(runCatching {
                    interrupted.finishPrune(current, { it == current }) { _, _ -> false }
                }.exceptionOrNull()).isInstanceOf(SimulatedProcessDeath::class.java)
                val firstManifest = File(File(root, REQUEST), "ownership-${first.ownershipSha256}.json")
                val firstReceipt = File(File(root, REQUEST), "ownership-${first.ownershipSha256}.complete.json")
                assertThat(firstManifest.exists()).isFalse()
                assertThat(firstReceipt.exists()).isEqualTo(stop == RestoreFileSnapshotFaultPoint.AfterAncestorManifestDelete)
                val restarted = fixtureStore(root)
                assertThat(restarted.readRetirement(second).originalMedia).hasSize(3)
                assertThat(restarted.readRetirement(current).media).hasSize(1)
                assertThat(runCatching { restarted.completed(REQUEST) }.exceptionOrNull())
                    .isInstanceOf(RestoreFileSnapshotRetirementPendingException::class.java)
                assertThat(restarted.finishPrune(current, { it == current }) { _, _ -> false }).isFalse()
                assertThat(firstReceipt.exists()).isFalse()
                assertThat(runCatching { restarted.readRetirement(second) }.isFailure).isTrue()
                val reopened = restarted.readRetirement(current)
                reopened.originalMedia.forEach { assertThat(restarted.ownedFile(reopened, it).readText()).isEqualTo("abc") }
            } finally { directory.deleteRecursively() }
        }
    }

    @Test fun staleFinishReturnsWithoutReadingMetadataAlreadyReclaimedByTheCurrentOwner() = runBlocking {
        val directory = Files.createTempDirectory("restore-file-snapshot-stale-finish").toFile()
        try {
            val source = File(directory, "source.jpg").apply { writeText("abc") }
            val store = fixtureStore(File(directory, "restore"))
            val active = store.capture(REQUEST,
                listOf(RestoreFileSnapshotSource(PHOTO, source, "image/jpeg", null, null)), 1024) { "{}" }
            val old = store.prepareRetirement(active, RestoreFileRetirementReason.Cancelled)
            val current = store.preparePrune(old, emptySet())
            assertThat(store.finishPrune(current, { it == current }) { _, _ -> false }).isFalse()
            assertThat(runCatching { store.readRetirement(old) }.isFailure).isTrue()
            assertThat(store.finishPrune(old, { it == current }) { _, _ -> error("stale media callback") }).isFalse()
            assertThat(store.finishRetirement(old) { it == current }).isFalse()
            val reopened = store.readRetirement(current)
            assertThat(store.ownedFile(reopened, reopened.originalMedia.single()).readText()).isEqualTo("abc")
        } finally { directory.deleteRecursively() }
    }

    @Test fun ancestorManifestWithoutReceiptFailsClosedBeforeAnyMetadataCleanup() = runBlocking {
        val directory = Files.createTempDirectory("restore-file-snapshot-invalid-ancestor").toFile()
        try {
            val source = File(directory, "source.jpg").apply { writeText("abc") }
            val root = File(directory, "restore")
            val store = fixtureStore(root)
            val uuids = (1L..3L).map { java.util.UUID(0L, it).toString() }
            val active = store.capture(REQUEST, uuids.map {
                RestoreFileSnapshotSource(it, source, "image/jpeg", null, null)
            }, 1024) { "{}" }
            val first = store.prepareRetirement(active, RestoreFileRetirementReason.Cancelled)
            val second = store.preparePrune(first, uuids.drop(1).toSet())
            val third = store.preparePrune(second, uuids.drop(2).toSet())
            val firstReceipt = File(File(root, REQUEST), "ownership-${first.ownershipSha256}.complete.json")
            check(firstReceipt.delete())
            assertThat(store.readRetirement(third).originalMedia).hasSize(3)
            assertThat(runCatching {
                store.finishPrune(third, { it == third }) { _, _ -> false }
            }.isFailure).isTrue()
            assertThat(store.readRetirement(second).media).hasSize(2)
            assertThat(File(File(root, REQUEST), "ownership-${first.ownershipSha256}.json").exists()).isTrue()
        } finally { directory.deleteRecursively() }
    }

    @Test fun committedPruneRevisionsKeepBoundedMetadataAndReopenEveryPendingOwnedFile() = runBlocking {
        val directory = Files.createTempDirectory("restore-file-snapshot-bounded-revisions").toFile()
        try {
            val source = File(directory, "source.jpg").apply { writeText("abc") }
            val root = File(directory, "restore")
            val store = fixtureStore(root)
            val uuids = (1L..32L).map { java.util.UUID(0L, it).toString() }
            val active = store.capture(REQUEST, uuids.map {
                RestoreFileSnapshotSource(it, source, "image/jpeg", null, null)
            }, 1024) { "{}" }
            var committed = store.prepareRetirement(active, RestoreFileRetirementReason.Cancelled)
            store.finishRetirement(committed) { it == committed }
            for (removed in 1..31) {
                committed = store.preparePrune(committed, uuids.drop(removed).toSet())
                // Every omitted file acquired a live reference; its bytes remain protected.
                assertThat(store.finishPrune(committed, { it == committed }) { _, _ -> false }).isFalse()
            }
            val metadataBytes = root.walkTopDown().filter { it.isFile && !it.name.endsWith(".media") }.sumOf(File::length)
            assertThat(metadataBytes).isAtMost(committed.ownershipByteSize + 4096)
            println("restore-owner-metadata revisions=32 originalMedia=32 metadataBytes=$metadataBytes currentPayloadBytes=${committed.ownershipByteSize}")
            val restarted = fixtureStore(root)
            val current = restarted.readRetirement(committed)
            assertThat(current.originalMedia).hasSize(32)
            assertThat(current.omittedMediaUuids).hasSize(31)
            assertThat(current.media).hasSize(1)
            current.originalMedia.forEach {
                assertThat(restarted.ownedFile(current, it).readText()).isEqualTo("abc")
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun interruptedIncompleteCleanupCanFinishAndRetryWithoutSealingTheRequest() = runBlocking {
        val directory = Files.createTempDirectory("restore-file-snapshot-cleanup-interrupt").toFile()
        try {
            val source = File(directory, "source.jpg").apply { writeText("abc") }
            val root = File(directory, "restore")
            val sources = listOf(RestoreFileSnapshotSource(PHOTO, source, "image/jpeg", null, null))
            val interrupted = fixtureStore(root, faultInjector = {
                if (it == RestoreFileSnapshotFaultPoint.AfterMediaSync) throw CancellationException("cancel")
                if (it == RestoreFileSnapshotFaultPoint.AfterDiscardRename) throw SimulatedProcessDeath()
            })
            assertThat(runCatching { interrupted.capture(REQUEST, sources, 1024) { "{}" } }.exceptionOrNull())
                .isInstanceOf(CancellationException::class.java)
            val restarted = fixtureStore(root)
            assertThat(restarted.completed(REQUEST)).isNull()
            val snapshot = restarted.read(restarted.capture(REQUEST, sources, 1024) { "{}" })
            assertThat(restarted.ownedFile(snapshot, snapshot.media.single()).readText()).isEqualTo("abc")
        } finally { directory.deleteRecursively() }
    }

    @Test fun interruptedFinalDiscardRemainsSealedWhileExactCleanupResumes() = runBlocking {
        val directory = Files.createTempDirectory("restore-file-snapshot-discard-interrupt").toFile()
        try {
            val root = File(directory, "restore")
            fixtureStore(root).capture(REQUEST, emptyList(), 1024) { "{}" }
            val interrupted = fixtureStore(root, faultInjector = {
                if (it == RestoreFileSnapshotFaultPoint.AfterDiscardRename) throw SimulatedProcessDeath()
            })
            assertThat(runCatching { interrupted.discard(REQUEST) }.exceptionOrNull()).isInstanceOf(SimulatedProcessDeath::class.java)
            val restarted = fixtureStore(root)
            assertThat(runCatching { restarted.completed(REQUEST) }.exceptionOrNull())
                .isInstanceOf(RestoreFileSnapshotRetirementPendingException::class.java)
            restarted.discard(REQUEST)
            assertThat(runCatching { restarted.capture(REQUEST, emptyList(), 1024) { "{}" } }.exceptionOrNull())
                .isInstanceOf(RestoreFileSnapshotRetirementPendingException::class.java)
        } finally { directory.deleteRecursively() }
    }

    @Test fun restartFindsTheExactPreparedRetirementReasonBeforeTheRoomTransition() = runBlocking {
        val directory = Files.createTempDirectory("restore-file-snapshot-pending-intent").toFile()
        try {
            val root = File(directory, "restore")
            val store = fixtureStore(root)
            val active = store.capture(REQUEST, emptyList(), 1024) { "{}" }
            assertThat(store.preparedRetirement(active)).isNull()
            val compact = store.prepareRetirement(active, RestoreFileRetirementReason.UndispatchedAbandon)
            val restarted = fixtureStore(root)
            val prepared = requireNotNull(restarted.preparedRetirement(active))
            assertThat(prepared.pointer).isEqualTo(compact)
            assertThat(prepared.activePointer).isEqualTo(active)
            assertThat(prepared.reason).isEqualTo(RestoreFileRetirementReason.UndispatchedAbandon)
            assertThat(restarted.read(active).manifestJson).isEqualTo("{}")
        } finally { directory.deleteRecursively() }
    }

    @Test fun declinedReclaimPreservesCumulativeOwnershipUntilAProtectedRetry() = runBlocking {
        val directory = Files.createTempDirectory("restore-file-snapshot-decline").toFile()
        try {
            val source = File(directory, "source.jpg").apply { writeText("abc") }
            val store = fixtureStore(File(directory, "restore"))
            val active = store.capture(REQUEST,
                listOf(RestoreFileSnapshotSource(PHOTO, source, "image/jpeg", null, null)), 1024) { "{}" }
            val initial = store.prepareRetirement(active, RestoreFileRetirementReason.Cancelled)
            val original = store.readRetirement(initial)
            val empty = store.preparePrune(initial, emptySet())
            assertThat(store.finishPrune(empty, { it == empty }) { _, _ -> false }).isFalse()
            assertThat(original.ownedPath(original.media.single()).readText()).isEqualTo("abc")
            assertThat(store.readRetirement(empty).omittedMediaUuids).containsExactly(PHOTO)
            assertThat(store.finishPrune(empty, { it == empty }) { _, unlink -> unlink(); true }).isTrue()
            assertThat(original.ownedPath(original.media.single()).exists()).isFalse()
        } finally { directory.deleteRecursively() }
    }

    @Test fun interruptedPruneResumesAllRemainingOmissionsWithoutDeletingRetainedMedia() = runBlocking {
        val directory = Files.createTempDirectory("restore-file-snapshot-prune-interrupt").toFile()
        try {
            val source = File(directory, "source.jpg").apply { writeText("abc") }
            val root = File(directory, "restore")
            val store = fixtureStore(root)
            val photo2 = "44444444-4444-4444-8444-444444444444"
            val photo3 = "55555555-5555-4555-8555-555555555555"
            val active = store.capture(REQUEST, listOf(PHOTO, photo2, photo3).map {
                RestoreFileSnapshotSource(it, source, "image/jpeg", null, null)
            }, 1024) { "{}" }
            val initial = store.prepareRetirement(active, RestoreFileRetirementReason.Cancelled)
            val original = store.readRetirement(initial)
            val pruned = store.preparePrune(initial, setOf(photo3))
            val interrupted = fixtureStore(root, faultInjector = {
                if (it == RestoreFileSnapshotFaultPoint.AfterPruneMediaDelete) throw SimulatedProcessDeath()
            })
            assertThat(runCatching {
                interrupted.finishPrune(pruned, { it == pruned }) { _, unlink -> unlink(); true }
            }.exceptionOrNull()).isInstanceOf(SimulatedProcessDeath::class.java)
            assertThat(original.media.count { original.ownedPath(it).exists() }).isEqualTo(2)
            val restarted = fixtureStore(root)
            assertThat(restarted.finishPrune(pruned, { it == pruned }) { _, unlink -> unlink(); true }).isTrue()
            val retained = restarted.readRetirement(pruned)
            assertThat(restarted.ownedFile(retained, retained.media.single()).readText()).isEqualTo("abc")
        } finally { directory.deleteRecursively() }
    }

    @Test fun compactPublicationInterruptionsKeepOriginalReadsAndRetryIdenticalIntent() = runBlocking {
        listOf(RestoreFileSnapshotFaultPoint.AfterRetirementManifestSync,
            RestoreFileSnapshotFaultPoint.AfterRetirementReceiptSync).forEach { stop ->
            val directory = Files.createTempDirectory("restore-file-snapshot-compact-interrupt").toFile()
            try {
                val root = File(directory, "restore")
                val active = fixtureStore(root).capture(REQUEST, emptyList(), 1024) { "{}" }
                val interrupted = fixtureStore(root, faultInjector = { if (it == stop) throw SimulatedProcessDeath() })
                assertThat(runCatching { interrupted.prepareRetirement(active, RestoreFileRetirementReason.Unavailable) }.exceptionOrNull())
                    .isInstanceOf(SimulatedProcessDeath::class.java)
                val restarted = fixtureStore(root)
                assertThat(restarted.read(active).manifestJson).isEqualTo("{}")
                val compact = restarted.prepareRetirement(active, RestoreFileRetirementReason.Unavailable)
                assertThat(restarted.prepareRetirement(active, RestoreFileRetirementReason.Unavailable)).isEqualTo(compact)
                assertThat(runCatching { restarted.prepareRetirement(active, RestoreFileRetirementReason.Cancelled) }.isFailure).isTrue()
                assertThat(restarted.readRetirement(compact).reason).isEqualTo(RestoreFileRetirementReason.Unavailable)
            } finally { directory.deleteRecursively() }
        }
    }

    @Test fun completedUnboundSnapshotRejectsManifestCorruptionWithoutRecapturing() = runBlocking {
        val directory = Files.createTempDirectory("restore-file-snapshot-unbound").toFile()
        try {
            val source = File(directory, "source.jpg").apply { writeText("abc") }
            val root = File(directory, "restore")
            val interrupted = fixtureStore(root, faultInjector = {
                if (it == RestoreFileSnapshotFaultPoint.AfterComplete) throw java.io.IOException("Room handoff unavailable")
            })
            val sources = listOf(RestoreFileSnapshotSource(PHOTO, source, "image/jpeg", null, null))
            assertThat(runCatching { interrupted.capture(REQUEST, sources, 1024) { "{}" } }.isFailure).isTrue()
            val restarted = fixtureStore(root)
            val pointer = requireNotNull(restarted.completed(REQUEST))
            source.writeText("xyz")
            assertThat(restarted.capture(REQUEST, sources, 1024) { error("must not recapture") }).isEqualTo(pointer)
            File(File(root, REQUEST), "manifest.json").writeText("{}")
            assertThat(runCatching { restarted.capture(REQUEST, sources, 1024) { error("must not recapture") } }.isFailure).isTrue()
            assertThat(restarted.metrics.sourceOpens).isEqualTo(0)
            assertThat(source.readText()).isEqualTo("xyz")
        } finally { directory.deleteRecursively() }
    }

    @Test fun symlinkedSourcesAndOwnedMediaNeverEscapeTheDedicatedStore() = runBlocking {
        val directory = Files.createTempDirectory("restore-file-snapshot-symlink").toFile()
        try {
            val source = File(directory, "source.jpg").apply { writeText("abc") }
            val alias = File(directory, "source-alias.jpg")
            Files.createSymbolicLink(alias.toPath(), source.toPath())
            val root = File(directory, "restore")
            val store = fixtureStore(root)
            assertThat(runCatching { store.capture(REQUEST,
                listOf(RestoreFileSnapshotSource(PHOTO, alias, "image/jpeg", null, null)), 1024) { "{}" } }.isFailure).isTrue()
            val pointer = store.capture(REQUEST,
                listOf(RestoreFileSnapshotSource(PHOTO, source, "image/jpeg", null, null)), 1024) { "{}" }
            val snapshot = store.read(pointer)
            val owned = snapshot.ownedPath(snapshot.media.single())
            check(owned.delete())
            Files.createSymbolicLink(owned.toPath(), source.toPath())
            assertThat(runCatching { store.open(snapshot, snapshot.media.single()).openStream().close() }.isFailure).isTrue()
            store.discard(REQUEST)
            assertThat(source.readText()).isEqualTo("abc")
        } finally { directory.deleteRecursively() }
    }

    @Test fun manifestBudgetFailureCleansOnlyIncompleteOwnedFiles() = runBlocking {
        val directory = Files.createTempDirectory("restore-file-snapshot-budget").toFile()
        try {
            val source = File(directory, "source.jpg").apply { writeText("abc") }
            val root = File(directory, "restore")
            val store = fixtureStore(root)
            assertThat(runCatching { store.capture(REQUEST,
                listOf(RestoreFileSnapshotSource(PHOTO, source, "image/jpeg", null, null)), 1) { "{}" } }.isFailure).isTrue()
            assertThat(root.listFiles().orEmpty()).isEmpty()
            assertThat(source.readText()).isEqualTo("abc")
        } finally { directory.deleteRecursively() }
    }

    @Test fun explicitReadRejectsACorruptedOriginalCompletionReceipt() = runBlocking {
        val directory = Files.createTempDirectory("restore-file-snapshot-receipt").toFile()
        try {
            val root = File(directory, "restore")
            val store = fixtureStore(root)
            val pointer = store.capture(REQUEST, emptyList(), 1024) { "{}" }
            File(File(root, REQUEST), "complete.json").writeText("{}")
            assertThat(runCatching { fixtureStore(root).read(pointer) }.isFailure).isTrue()
            assertThat(runCatching { fixtureStore(root).completed(REQUEST) }.isFailure).isTrue()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun laterPruneCleansAllCumulativeOmissionsWhileStaleFinishCannotDeleteAnything() = runBlocking {
        val directory = Files.createTempDirectory("restore-file-snapshot-prune").toFile()
        try {
            val source = File(directory, "source.jpg").apply { writeText("abc") }
            val root = File(directory, "restore")
            val store = fixtureStore(root)
            val photo2 = "44444444-4444-4444-8444-444444444444"
            val photo3 = "55555555-5555-4555-8555-555555555555"
            val sources = listOf(PHOTO, photo2, photo3).map { RestoreFileSnapshotSource(it, source, "image/jpeg", null, null) }
            val active = store.capture(REQUEST, sources, 1024) { "{}" }
            val first = store.prepareRetirement(active, RestoreFileRetirementReason.Cancelled)
            var committed = first
            store.finishRetirement(first) { it == committed }
            val original = store.readRetirement(first)
            val second = store.preparePrune(first, setOf(photo2, photo3))
            committed = second
            // Simulate process death after Room selected B, before any A-only file was removed.
            val restarted = fixtureStore(root)
            val third = restarted.preparePrune(second, setOf(photo3))
            committed = third
            assertThat(restarted.finishPrune(second, { it == committed }) { _, unlink -> unlink(); true }).isFalse()
            assertThat(original.media.all { original.ownedPath(it).exists() }).isTrue()
            assertThat(restarted.finishPrune(third, { it == committed }) { _, unlink -> unlink(); true }).isTrue()
            assertThat(restarted.readRetirement(third).media.map { it.clientUuid }).containsExactly(photo3)
            assertThat(original.ownedPath(original.media[0]).exists()).isFalse()
            assertThat(original.ownedPath(original.media[1]).exists()).isFalse()
            assertThat(original.ownedPath(original.media[2]).readText()).isEqualTo("abc")
            assertThat(runCatching { restarted.readRetirement(first) }.isFailure).isTrue()
            assertThat(runCatching { restarted.readRetirement(second) }.isFailure).isTrue()
            assertThat(restarted.readRetirement(third).originalMedia).hasSize(3)
            assertThat(runCatching { restarted.preparePrune(third, setOf(PHOTO, photo3)) }.isFailure).isTrue()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun uncommittedRetirementCannotUnlinkReplayOrMedia() = runBlocking {
        val directory = Files.createTempDirectory("restore-file-snapshot-uncommitted").toFile()
        try {
            val source = File(directory, "source.jpg").apply { writeText("abc") }
            val store = fixtureStore(File(directory, "restore"))
            val active = store.capture(REQUEST,
                listOf(RestoreFileSnapshotSource(PHOTO, source, "image/jpeg", null, null)), 1024) { "{}" }
            val compact = store.prepareRetirement(active, RestoreFileRetirementReason.Cancelled)
            assertThat(store.finishRetirement(compact) { false }).isFalse()
            assertThat(store.read(active).manifestJson).isEqualTo("{}")
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun processDeathAcrossPreparationBoundariesLeavesOnlyAnExactRetryableAttempt() = runBlocking {
        listOf(RestoreFileSnapshotFaultPoint.AfterOwnershipSync, RestoreFileSnapshotFaultPoint.AfterDirectorySync,
            RestoreFileSnapshotFaultPoint.AfterSourcesSync,
            RestoreFileSnapshotFaultPoint.AfterMediaChunk, RestoreFileSnapshotFaultPoint.AfterMediaSync,
            RestoreFileSnapshotFaultPoint.AfterManifestSync, RestoreFileSnapshotFaultPoint.AfterCompletionTempSync).forEach { stop ->
            val directory = Files.createTempDirectory("restore-file-snapshot-boundary").toFile()
            try {
                val source = File(directory, "source.jpg").apply { writeBytes(ByteArray(70_000) { 7 }) }
                val sources = listOf(RestoreFileSnapshotSource(PHOTO, source, "image/jpeg", null, null))
                val root = File(directory, "restore")
                val interrupted = fixtureStore(root, faultInjector = { if (it == stop) throw SimulatedProcessDeath() })
                assertThat(runCatching { interrupted.capture(REQUEST, sources, 1024) { "{}" } }.exceptionOrNull())
                    .isInstanceOf(SimulatedProcessDeath::class.java)
                val restarted = fixtureStore(root)
                assertThat(restarted.completed(REQUEST)).isNull()
                val snapshot = restarted.read(restarted.capture(REQUEST, sources, 1024) { "{}" })
                assertThat(restarted.ownedFile(snapshot, snapshot.media.single()).length()).isEqualTo(70_000)
                assertThat(source.length()).isEqualTo(70_000)
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test fun tornOwnerWithoutAnyCreatedDirectoryCanRetryItsExactRequest() = runBlocking {
        val directory = Files.createTempDirectory("restore-file-snapshot-owner").toFile()
        try {
            val root = File(directory, "restore").apply { mkdir() }
            File(root, "$REQUEST.owner").writeText("{")
            val store = fixtureStore(root)
            assertThat(store.completed(REQUEST)).isNull()
            val pointer = store.capture(REQUEST, emptyList(), 1024) { "{}" }
            assertThat(store.read(pointer).manifestJson).isEqualTo("{}")
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun exactDiscardPermanentlyRejectsReusingTheRetiredRequest() = runBlocking {
        val directory = Files.createTempDirectory("restore-file-snapshot-terminal").toFile()
        try {
            val root = File(directory, "restore")
            val store = fixtureStore(root)
            store.capture(REQUEST, emptyList(), 1024) { "{}" }
            store.discard(REQUEST)
            val restarted = fixtureStore(root)
            assertThat(runCatching { restarted.capture(REQUEST, emptyList(), 1024) { "{}" } }.exceptionOrNull())
                .isInstanceOf(RestoreFileSnapshotRetirementPendingException::class.java)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun retirementRetainsStableMediaPathsAndBlocksRebindingBeforeFullManifestUnlink() = runBlocking {
        val directory = Files.createTempDirectory("restore-file-snapshot-retirement").toFile()
        try {
            val source = File(directory, "source.jpg").apply { writeText("abc") }
            val root = File(directory, "restore")
            val store = fixtureStore(root)
            val pointer = store.capture(REQUEST,
                listOf(RestoreFileSnapshotSource(PHOTO, source, "image/jpeg", 17, 23)), 1024) { "{\"captured\":true}" }
            val snapshot = store.read(pointer)
            val originalPath = snapshot.ownedPath(snapshot.media.single())
            val compact = store.prepareRetirement(pointer, RestoreFileRetirementReason.Cancelled)
            val restarted = fixtureStore(root)
            assertThat(restarted.read(pointer).manifestJson).isEqualTo("{\"captured\":true}")
            assertThat(restarted.metrics.ownedBytes).isEqualTo(root.walkTopDown().filter(File::isFile).sumOf(File::length))
            assertThat(runCatching { restarted.completed(REQUEST) }.exceptionOrNull())
                .isInstanceOf(RestoreFileSnapshotRetirementPendingException::class.java)
            val retired = restarted.readRetirement(compact)
            assertThat(retired.activePointer).isEqualTo(pointer)
            assertThat(retired.reason).isEqualTo(RestoreFileRetirementReason.Cancelled)
            assertThat(retired.media).isEqualTo(snapshot.media)
            assertThat(retired.ownedPath(retired.media.single())).isEqualTo(originalPath)

            // The caller has now committed its terminal compact pointer in Room.
            restarted.finishRetirement(compact) { true }
            restarted.finishRetirement(compact) { true }
            assertThat(runCatching { restarted.read(pointer) }.isFailure).isTrue()
            source.delete()
            val finished = fixtureStore(root).readRetirement(compact)
            assertThat(restarted.ownedFile(finished, finished.media.single()).readText()).isEqualTo("abc")
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun everyUploadStreamRejectsSameLengthMutationAfterOpeningTheSource() = runBlocking {
        val directory = Files.createTempDirectory("restore-file-snapshot-open").toFile()
        try {
            val source = File(directory, "source.jpg").apply { writeText("abc") }
            val store = fixtureStore(File(directory, "restore"))
            val pointer = store.capture(REQUEST,
                listOf(RestoreFileSnapshotSource(PHOTO, source, "image/jpeg", null, null)), 1024) { "{}" }
            val snapshot = store.read(pointer)
            val upload = store.open(snapshot, snapshot.media.single())
            val owned = snapshot.ownedPath(snapshot.media.single())
            val timestamp = owned.lastModified()
            owned.writeText("xyz")
            check(owned.setLastModified(timestamp))
            assertThat(runCatching { upload.openStream().use { it.readBytes() } }.isFailure).isTrue()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun captureCopiesAndHashesOnceAndExactDiscardReleasesItsOwnedBytes() = runBlocking {
        val directory = Files.createTempDirectory("restore-file-snapshot-cost").toFile()
        try {
            val source = File(directory, "source.jpg").apply { writeBytes(ByteArray(70_000) { 7 }) }
            val store = fixtureStore(File(directory, "restore"))
            store.capture(REQUEST, listOf(RestoreFileSnapshotSource(PHOTO, source, "image/jpeg", null, null)), 1024) { "{}" }
            assertThat(store.metrics.sourceOpens).isEqualTo(1)
            assertThat(store.metrics.sourceBytesRead).isEqualTo(70_000)
            assertThat(store.metrics.copiedBytes).isEqualTo(70_000)
            assertThat(store.metrics.hashedBytes).isEqualTo(70_000)
            assertThat(store.metrics.ownedBytes).isAtLeast(70_000)
            assertThat(store.metrics.peakOwnedBytes).isLessThan(102_768)
            store.discard(REQUEST)
            store.discard(REQUEST)
            assertThat(store.metrics.ownedBytes).isLessThan(1024)
            assertThat(runCatching { store.completed(REQUEST) }.exceptionOrNull())
                .isInstanceOf(RestoreFileSnapshotRetirementPendingException::class.java)
            assertThat(source.length()).isEqualTo(70_000)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun restartRetriesOnlyItsIncompleteAttemptAndPreservesCompletedSibling() = runBlocking {
        val directory = Files.createTempDirectory("restore-file-snapshot-retry").toFile()
        try {
            val source = File(directory, "source.jpg").apply { writeText("abc") }
            val sources = listOf(RestoreFileSnapshotSource(PHOTO, source, "image/jpeg", null, null))
            val root = File(directory, "restore")
            val sibling = fixtureStore(root).capture(OTHER_REQUEST, sources, 1024) { "{}" }
            val interrupted = fixtureStore(root, faultInjector = { point ->
                if (point == RestoreFileSnapshotFaultPoint.AfterMediaSync) throw SimulatedProcessDeath()
            })
            assertThat(runCatching { interrupted.capture(REQUEST, sources, 1024) { "{}" } }.exceptionOrNull())
                .isInstanceOf(SimulatedProcessDeath::class.java)
            val restarted = fixtureStore(root)
            assertThat(restarted.completed(REQUEST)).isNull()
            val retried = restarted.read(restarted.capture(REQUEST, sources, 1024) { "{}" })
            assertThat(restarted.open(retried, retried.media.single()).openStream().use { it.readBytes() })
                .isEqualTo("abc".toByteArray())
            val original = restarted.read(sibling)
            assertThat(restarted.open(original, original.media.single()).openStream().use { it.readBytes() })
                .isEqualTo("abc".toByteArray())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun cancellationRetiresOnlyTheIncompleteOwnedAttemptAndLeavesSources() = runBlocking {
        val directory = Files.createTempDirectory("restore-file-snapshot-cancel").toFile()
        try {
            val source = File(directory, "source.jpg").apply { writeText("abc") }
            val root = File(directory, "restore")
            val store = fixtureStore(root, faultInjector = { point ->
                if (point == RestoreFileSnapshotFaultPoint.AfterMediaSync) throw CancellationException("cancel capture")
            })
            val result = runCatching {
                store.capture(REQUEST, listOf(RestoreFileSnapshotSource(PHOTO, source, "image/jpeg", null, null)), 1024) { "{}" }
            }
            assertThat(result.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
            assertThat(fixtureStore(root).completed(REQUEST)).isNull()
            assertThat(root.listFiles().orEmpty()).isEmpty()
            assertThat(source.readText()).isEqualTo("abc")
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun insufficientSpaceRejectsBeforeReadingOrOwningAnyMedia() = runBlocking {
        val directory = Files.createTempDirectory("restore-file-snapshot-space").toFile()
        try {
            val source = File(directory, "source.jpg").apply { writeText("abc") }
            val root = File(directory, "restore")
            val store = fixtureStore(root, availableSpace = { 2L })
            val result = runCatching {
                store.capture(REQUEST, listOf(RestoreFileSnapshotSource(PHOTO, source, "image/jpeg", null, null)), 1024) {
                    "{}"
                }
            }
            assertThat(result.isFailure).isTrue()
            assertThat(store.metrics.sourceOpens).isEqualTo(0)
            assertThat(store.metrics.copiedBytes).isEqualTo(0)
            assertThat(root.listFiles().orEmpty()).isEmpty()
            assertThat(source.readText()).isEqualTo("abc")
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun completedSnapshotReopensExactBytesWithoutReadingChangedSources() = runBlocking {
        val directory = Files.createTempDirectory("restore-file-snapshot").toFile()
        try {
            val source = File(directory, "source.jpg").apply { writeText("abc") }
            val root = File(directory, "restore")
            val store = fixtureStore(root)
            val pointer = store.capture(REQUEST, listOf(
                RestoreFileSnapshotSource(PHOTO, source, "image/jpeg", 17, 23),
            ), manifestEstimateBytes = 1024) { media ->
                assertThat(media.single().sha256)
                    .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
                "{\"captured\":true}"
            }
            source.writeText("changed after capture")

            val restarted = fixtureStore(root)
            val replay = restarted.capture(REQUEST, emptyList(), manifestEstimateBytes = 1) {
                error("a completed restore must never rebuild its manifest")
            }
            assertThat(replay).isEqualTo(pointer)
            assertThat(restarted.completed(REQUEST)).isEqualTo(pointer)
            val snapshot = restarted.read(pointer)
            assertThat(snapshot.manifestJson).isEqualTo("{\"captured\":true}")
            assertThat(snapshot.media.single().byteSize).isEqualTo(3)
            assertThat(snapshot.media.single().mime).isEqualTo("image/jpeg")
            assertThat(snapshot.media.single().width).isEqualTo(17)
            assertThat(snapshot.media.single().height).isEqualTo(23)
            assertThat(restarted.open(snapshot, snapshot.media.single()).openStream().use { it.readBytes() })
                .isEqualTo("abc".toByteArray())
        } finally {
            directory.deleteRecursively()
        }
    }

    /** Fixture owns its sources; production supplies the shared path/URI gate instead. */
    private fun fixtureStore(
        root: File,
        availableSpace: (File) -> Long = { it.usableSpace },
        faultInjector: RestoreFileSnapshotFaultInjector = RestoreFileSnapshotFaultInjector {},
    ): RestoreFileSnapshotStore = RestoreFileSnapshotStore(root, availableSpace, faultInjector,
        incompleteSourceGuard = { _, cleanup -> cleanup() })

    private companion object {
        const val REQUEST = "11111111-1111-4111-8111-111111111111"
        const val PHOTO = "22222222-2222-4222-8222-222222222222"
        const val OTHER_REQUEST = "33333333-3333-4333-8333-333333333333"
    }

    private class SimulatedProcessDeath : Error("simulated process death")
}
