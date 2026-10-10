package com.lezi.babylog.sync.disasterrecovery

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Lookup must retain the active snapshot and exact retirement-receipt validation boundaries. */
class RestorePreparedRetirementLookupTest {
    @Test fun absenceStillValidatesActiveReceiptOwnerAndManifest() = runBlocking {
        for (name in listOf("complete.json", "manifest.json", "../$REQUEST.owner")) {
            for (missing in listOf(false, true)) {
                withFixture { root, store, active ->
                    val metadata = File(File(root, REQUEST), name).canonicalFile
                    check(metadata.isFile) { "fixture metadata missing: $name" }
                    if (missing) check(metadata.delete()) else metadata.writeText("{}")
                    assertThat(runCatching { store.preparedRetirement(active) }.isFailure).isTrue()
                    assertThat(File(File(root, REQUEST), "$PHOTO.media").readText()).isEqualTo("abc")
                }
            }
        }
    }

    @Test fun emptyLookupDoesNotCacheAbsenceAcrossPublicationOrRestart() = runBlocking {
        for (reason in RestoreFileRetirementReason.entries) {
            withFixture { root, store, active ->
                assertThat(store.preparedRetirement(active)).isNull()
                val pointer = store.prepareRetirement(active, reason)
                for (reader in listOf(store, RestoreFileSnapshotStore(root))) {
                    val prepared = requireNotNull(reader.preparedRetirement(active))
                    assertThat(prepared.pointer).isEqualTo(pointer)
                    assertThat(prepared.activePointer).isEqualTo(active)
                    assertThat(prepared.reason).isEqualTo(reason)
                    assertThat(prepared.previousPointer).isNull()
                    assertThat(prepared.media.single().clientUuid).isEqualTo(PHOTO)
                }
            }
        }
    }

    @Test fun existingMatchingReceiptOrInventoryCorruptionFailsClosed() = runBlocking {
        for (suffix in listOf("complete.json", "json")) {
            withFixture { root, store, active ->
                val pointer = store.prepareRetirement(active, RestoreFileRetirementReason.Cancelled)
                File(File(root, REQUEST), "ownership-${pointer.ownershipSha256}.$suffix").writeText("{}")
                assertThat(runCatching { store.preparedRetirement(active) }.isFailure).isTrue()
                assertThat(File(File(root, REQUEST), "$PHOTO.media").readText()).isEqualTo("abc")
            }
        }
    }

    @Test fun missingRetirementInventoryWithReceiptStillFailsClosed() = runBlocking {
        withFixture { root, store, active ->
            val pointer = store.prepareRetirement(active, RestoreFileRetirementReason.Cancelled)
            check(File(File(root, REQUEST), "ownership-${pointer.ownershipSha256}.json").delete())
            assertThat(runCatching { store.preparedRetirement(active) }.isFailure).isTrue()
            assertThat(File(File(root, REQUEST), "$PHOTO.media").readText()).isEqualTo("abc")
        }
    }

    @Test fun withdrawnReceiptCannotBeReusedFromEarlierLookup() = runBlocking {
        withFixture { root, store, active ->
            val pointer = store.prepareRetirement(active, RestoreFileRetirementReason.Cancelled)
            assertThat(store.preparedRetirement(active)?.pointer).isEqualTo(pointer)
            check(File(File(root, REQUEST), "ownership-${pointer.ownershipSha256}.complete.json").delete())
            assertThat(store.preparedRetirement(active)).isNull()
            assertThat(runCatching { store.readRetirement(pointer) }.isFailure).isTrue()
            assertThat(File(File(root, REQUEST), "$PHOTO.media").readText()).isEqualTo("abc")
        }
    }

    @Test fun interruptedUnpublishedInventoryIsNotACompletedRetirement() = runBlocking {
        withFixture { root, _, active ->
            val interrupted = RestoreFileSnapshotStore(root, faultInjector = {
                if (it == RestoreFileSnapshotFaultPoint.AfterRetirementManifestSync) throw SimulatedDeath()
            })
            assertThat(runCatching {
                interrupted.prepareRetirement(active, RestoreFileRetirementReason.Unavailable)
            }.exceptionOrNull()).isInstanceOf(SimulatedDeath::class.java)
            val restarted = RestoreFileSnapshotStore(root)
            assertThat(restarted.preparedRetirement(active)).isNull()
            val pointer = restarted.prepareRetirement(active, RestoreFileRetirementReason.Unavailable)
            assertThat(restarted.preparedRetirement(active)?.pointer).isEqualTo(pointer)
        }
    }

    @Test fun symlinkedMatchingReceiptCannotAuthorizeRetirement() = runBlocking {
        withFixture { root, store, active ->
            val pointer = store.prepareRetirement(active, RestoreFileRetirementReason.Cancelled)
            val receipt = File(File(root, REQUEST), "ownership-${pointer.ownershipSha256}.complete.json")
            val target = File(root.parentFile, "receipt-copy.json").apply { writeBytes(receipt.readBytes()) }
            check(receipt.delete())
            Files.createSymbolicLink(receipt.toPath(), target.toPath())
            assertThat(runCatching { store.preparedRetirement(active) }.isFailure).isTrue()
            assertThat(target.isFile).isTrue()
            assertThat(File(File(root, REQUEST), "$PHOTO.media").readText()).isEqualTo("abc")
        }
    }

    @Test fun nonmatchingReceiptDoesNotBecomeAnInitialIntent() = runBlocking {
        withFixture { root, store, active ->
            val unknown = File(File(root, REQUEST), "ownership-invalid.complete.json").apply { writeText("{}") }
            assertThat(store.preparedRetirement(active)).isNull()
            assertThat(unknown.readText()).isEqualTo("{}")
            assertThat(File(File(root, REQUEST), "$PHOTO.media").readText()).isEqualTo("abc")
        }
    }

    @Test fun competingInitialReceiptsRemainRejected() = runBlocking {
        withFixture { root, store, active ->
            store.prepareRetirement(active, RestoreFileRetirementReason.Cancelled)
            withFixture { otherRoot, otherStore, otherActive ->
                assertThat(otherActive).isEqualTo(active)
                val other = otherStore.prepareRetirement(otherActive, RestoreFileRetirementReason.Unavailable)
                for (suffix in listOf("json", "complete.json")) {
                    val name = "ownership-${other.ownershipSha256}.$suffix"
                    File(File(otherRoot, REQUEST), name).copyTo(File(File(root, REQUEST), name))
                }
            }
            assertThat(runCatching { store.preparedRetirement(active) }.isFailure).isTrue()
            assertThat(File(File(root, REQUEST), "$PHOTO.media").readText()).isEqualTo("abc")
        }
    }

    private suspend fun withFixture(
        block: suspend (File, RestoreFileSnapshotStore, RestoreFileSnapshotPointer) -> Unit,
    ) {
        val directory = Files.createTempDirectory("restore-prepared-retirement-lookup").toFile()
        try {
            val source = File(directory, "source.jpg").apply { writeText("abc") }
            val root = File(directory, "restore")
            val store = RestoreFileSnapshotStore(root)
            val active = store.capture(REQUEST,
                listOf(RestoreFileSnapshotSource(PHOTO, source, "image/jpeg", null, null)), 1024) { "{}" }
            block(root, store, active)
        } finally { directory.deleteRecursively() }
    }

    private class SimulatedDeath : Error("simulated process death")

    private companion object {
        const val REQUEST = "11111111-1111-4111-8111-111111111111"
        const val PHOTO = "22222222-2222-4222-8222-222222222222"
    }
}
