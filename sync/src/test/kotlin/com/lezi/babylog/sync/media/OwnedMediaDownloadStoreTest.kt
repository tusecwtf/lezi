package com.lezi.babylog.sync.media

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.common.MediaContentDigest
import com.lezi.babylog.sync.backend.MAX_SYNC_MEDIA_RESPONSE_BYTES
import com.lezi.babylog.sync.backend.MediaDownloadReceipt
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OwnedMediaDownloadStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val uuid = "11111111-1111-4111-8111-111111111111"
    private val bytes = byteArrayOf(0, 1, 127, -128, -1)
    private val receipt get() = MediaDownloadReceipt(MediaContentDigest.ofBytes(bytes), bytes.size.toLong())

    @Test fun sameMediaUuidGetsExclusiveFilesWithoutOverwritingPreviousBytes() = runTest {
        val store = OwnedMediaDownloadStore(temporary.root)
        val first = store.begin(uuid, "log", "image/jpeg")
        val second = store.begin(uuid, "log", "image/jpeg")
        first.output.write(bytes)
        val firstUri = first.complete(receipt)
        second.output.write(byteArrayOf(9))
        val secondUri = second.complete(MediaDownloadReceipt(MediaContentDigest.ofBytes(byteArrayOf(9)), 1))
        assertThat(firstUri).isNotEqualTo(secondUri)
        assertThat(File(firstUri).readBytes()).isEqualTo(bytes)
        assertThat(File(secondUri).readBytes()).isEqualTo(byteArrayOf(9))
        first.release { }
        second.release { }
        assertThat(store.pending()).isEmpty()
    }

    @Test fun activeLeasesAreNotReturnedForRecoveryEvenAfterOutputIsClosed() = runTest {
        val store = OwnedMediaDownloadStore(temporary.root)
        val target = store.begin(uuid, "log", null)
        assertThat(ownerMarkers()).hasSize(1)
        assertThat(store.pending()).isEmpty()
        target.output.write(bytes)
        target.complete(receipt)
        assertThat(store.pending()).isEmpty()
        target.release { File(it).delete() }
        assertThat(ownerMarkers()).isEmpty()
    }

    @Test fun restartReclaimsOnlyMarkedIncompleteNewFileAndPreservesLegacyFile() = runTest {
        val root = File(temporary.root, "record-media").apply { mkdirs() }
        val legacy = File(root, "sync_$uuid.jpg").apply { writeBytes(bytes) }
        val first = OwnedMediaDownloadStore(temporary.root).begin(uuid, "log", null)
        first.output.write(byteArrayOf(4, 5))
        first.close() // Simulate all process file descriptors being closed, with marker retained.
        val restarted = OwnedMediaDownloadStore(temporary.root)
        val recovered = restarted.pending()
        assertThat(recovered).hasSize(1)
        var reclaimed: String? = null
        recovered.single().release { path ->
            reclaimed = path
            assertThat(File(path).readBytes()).isEqualTo(byteArrayOf(4, 5))
            check(File(path).delete())
        }
        assertThat(reclaimed).isNotEqualTo(legacy.path)
        assertThat(legacy.readBytes()).isEqualTo(bytes)
        assertThat(restarted.pending()).isEmpty()
    }

    @Test fun restartAfterRoomAdoptionKeepsBytesAndOnlyDropsMarker() = runTest {
        val target = OwnedMediaDownloadStore(temporary.root).begin(uuid, "log", null)
        target.output.write(bytes)
        val uri = target.complete(receipt)
        val restarted = OwnedMediaDownloadStore(temporary.root)
        restarted.pending().single().release { path -> assertThat(path).isEqualTo(uri) }
        assertThat(File(uri).readBytes()).isEqualTo(bytes)
        assertThat(restarted.pending()).isEmpty()
    }

    @Test fun reclaimFailureRetainsMarkerAndRelinquishesActiveLeaseForRetry() = runTest {
        val store = OwnedMediaDownloadStore(temporary.root)
        val target = store.begin(uuid, "log", null)
        target.output.write(bytes)
        val uri = target.complete(receipt)
        val expected = IOException("injected disk or Room failure")
        val failure = runCatching { target.release { throw expected } }.exceptionOrNull()
        assertThat(failure).isSameInstanceAs(expected)
        assertThat(File(uri).readBytes()).isEqualTo(bytes)
        assertThat(store.pending()).hasSize(1)
        store.pending().single().release { check(File(it).delete()) }
        assertThat(store.pending()).isEmpty()
    }

    @Test fun ownershipCheckKeepsCallerDispatcherWhileFileRetirementUsesIo() = runTest {
        val store = OwnedMediaDownloadStore(temporary.root)
        val target = store.begin(uuid, "log", null)
        target.output.write(bytes)
        val uri = target.complete(receipt)
        val callerDispatcher = currentCoroutineContext()[ContinuationInterceptor]
        var checked = false

        target.release { path ->
            assertThat(currentCoroutineContext()[ContinuationInterceptor]).isSameInstanceAs(callerDispatcher)
            assertThat(path).isEqualTo(uri)
            checked = true
        }

        assertThat(checked).isTrue()
        assertThat(File(uri).readBytes()).isEqualTo(bytes)
        assertThat(store.pending()).isEmpty()
    }

    @Test fun emptyWrongLengthAndInvalidDigestCannotCompleteButCanBeReclaimed() = runTest {
        val store = OwnedMediaDownloadStore(temporary.root)
        for (bad in listOf(
            MediaDownloadReceipt(receipt.sha256, 0),
            MediaDownloadReceipt(receipt.sha256, bytes.size + 1L),
            MediaDownloadReceipt("invalid", bytes.size.toLong()),
        )) {
            val target = store.begin(uuid, "log", null)
            target.output.write(bytes)
            assertThat(runCatching { target.complete(bad) }.isFailure).isTrue()
            target.release { check(File(it).delete()) }
        }
        assertThat(store.pending()).isEmpty()
    }

    @Test fun avatarUriRemainsRelativeToFilesRoot() = runTest {
        val store = OwnedMediaDownloadStore(temporary.root)
        val target = store.begin(uuid, "avatar", "image/png")
        target.output.write(bytes)
        val uri = target.complete(receipt)
        assertThat(File(uri).isAbsolute).isFalse()
        assertThat(uri).startsWith("baby_avatars/")
        assertThat(File(temporary.root, uri).readBytes()).isEqualTo(bytes)
        target.release { check(File(temporary.root, it).delete()) }
    }

    @Test fun capacityCountsActiveAndPendingOwnershipWithoutDeletingAnyFile() = runTest {
        val store = OwnedMediaDownloadStore(temporary.root, maximumPendingPerRoot = 1)
        val target = store.begin(uuid, "log", null)
        assertThrows(IllegalStateException::class.java) { store.begin(uuid, "log", null) }
        target.release { File(it).delete() }
        store.begin(uuid, "log", null).release { File(it).delete() }
    }

    @Test fun invalidMarkerCannotAuthorizeAnyPath() {
        val owners = File(temporary.root, "record-media/.sync-download-owners").apply { mkdirs() }
        File(owners, "legacy.jpg.owner").writeText("")
        assertThrows(IllegalStateException::class.java) {
            OwnedMediaDownloadStore(temporary.root).pending()
        }
    }

    @Test fun productRootSymlinkIsRejected() {
        val external = temporary.newFolder("external")
        Files.createSymbolicLink(File(temporary.root, "record-media").toPath(), external.toPath())
        assertThrows(IllegalStateException::class.java) {
            OwnedMediaDownloadStore(temporary.root).begin(uuid, "log", null)
        }
        assertThat(external.listFiles()).isEmpty()
    }

    @Test fun namespaceCollisionCannotCreateDeletionAuthorityForExistingDirectory() = runTest {
        val id = java.util.UUID.fromString("22222222-2222-4222-8222-222222222222")
        val store = OwnedMediaDownloadStore(temporary.root, newDirectoryId = { id })
        val first = store.begin(uuid, "log", null)
        first.output.write(bytes)
        val uri = first.complete(receipt)
        first.release { } // Room adopted the first file; it no longer has a marker.

        assertThrows(java.nio.file.FileAlreadyExistsException::class.java) {
            store.begin(uuid, "log", null)
        }
        assertThat(store.pending()).isEmpty()
        assertThat(ownerMarkers()).isEmpty()
        assertThat(File(uri).readBytes()).isEqualTo(bytes)
    }

    @Test fun forgedTargetSymlinkCannotDeleteReferencedOutsideBytes() {
        val store = OwnedMediaDownloadStore(temporary.root)
        store.begin(uuid, "log", null).close()
        val target = File(temporary.root, "record-media").walkTopDown()
            .single { it.name == "media.jpg" }
        val outside = temporary.newFile("outside.jpg").apply { writeBytes(bytes) }
        check(target.delete())
        Files.createSymbolicLink(target.toPath(), outside.toPath())

        assertThrows(IllegalStateException::class.java) {
            OwnedMediaDownloadStore(temporary.root).pending()
        }
        assertThat(outside.readBytes()).isEqualTo(bytes)
    }

    @Test fun adoptedFileDirectoryConvergesAfterLaterOrdinaryDeletion() = runTest {
        val store = OwnedMediaDownloadStore(temporary.root)
        val target = store.begin(uuid, "log", null)
        target.output.write(bytes)
        val file = File(target.complete(receipt))
        target.release { }
        check(file.delete())
        removeEmptyOwnedDownloadDirectory(file)
        assertThat(file.parentFile!!.exists()).isFalse()
        assertThat(File(temporary.root, "record-media").isDirectory).isTrue()
    }

    @Test fun actualWritesAreBoundedEvenWhenTransportDoesNotEnforceItsOwnLimit() = runTest {
        val store = OwnedMediaDownloadStore(temporary.root)
        val target = store.begin(uuid, "log", null)
        val chunk = ByteArray(8_192) { 7 }
        repeat(MAX_SYNC_MEDIA_RESPONSE_BYTES / chunk.size) { target.output.write(chunk) }
        assertThrows(IllegalArgumentException::class.java) { target.output.write(1) }
        target.release { path ->
            assertThat(File(path).length()).isEqualTo(MAX_SYNC_MEDIA_RESPONSE_BYTES.toLong())
            check(File(path).delete())
        }
        assertThat(store.pending()).isEmpty()
        assertThat(ownerMarkers()).isEmpty()
    }

    @Test fun rearmedAdoptedFileSurvivesRestartUntilReferenceAwareReclaimSucceeds() = runTest {
        val store = OwnedMediaDownloadStore(temporary.root)
        val target = store.begin(uuid, "log", null)
        target.output.write(bytes)
        val uri = target.complete(receipt)
        target.release { }
        val retirement = requireNotNull(store.retainForReplacement(uri))
        assertThat(store.pending()).isEmpty() // Active page cannot be recovered concurrently.
        assertThat(runCatching { retirement.release { throw IOException("delete failed") } }.isFailure).isTrue()
        assertThat(File(uri).readBytes()).isEqualTo(bytes)
        val restarted = OwnedMediaDownloadStore(temporary.root)
        restarted.pending().single().release { check(File(it).delete()) }
        assertThat(File(uri).exists()).isFalse()
        assertThat(restarted.pending()).isEmpty()
    }

    @Test fun restartBeforeReplacementCommitPreservesStillReferencedBytes() = runTest {
        val store = OwnedMediaDownloadStore(temporary.root)
        val target = store.begin(uuid, "log", null)
        target.output.write(bytes)
        val uri = target.complete(receipt)
        target.release { }
        requireNotNull(store.retainForReplacement(uri))
        val restarted = OwnedMediaDownloadStore(temporary.root)
        restarted.pending().single().release { path -> assertThat(path).isEqualTo(uri) }
        assertThat(File(uri).readBytes()).isEqualTo(bytes)
        assertThat(restarted.pending()).isEmpty()
    }

    @Test fun replacementCapacityAllowsOldAndNewAndReusesExistingMarkerAtCapacity() = runTest {
        val store = OwnedMediaDownloadStore(temporary.root, maximumPendingPerRoot = 1)
        val old = store.begin(uuid, "log", null)
        old.output.write(bytes)
        val uri = old.complete(receipt)
        old.release { }
        val fresh = store.begin(uuid, "log", null)
        val retirement = requireNotNull(store.retainForReplacement(uri))
        assertThat(ownerMarkers()).hasSize(2)
        assertThat(runCatching { retirement.release { throw IOException("failed") } }.isFailure).isTrue()
        val retry = requireNotNull(store.retainForReplacement(uri))
        assertThat(ownerMarkers()).hasSize(2)
        assertThrows(IllegalStateException::class.java) { store.begin(uuid, "log", null) }
        val restarted = OwnedMediaDownloadStore(temporary.root, maximumPendingPerRoot = 1)
        assertThat(restarted.pending()).hasSize(2) // No truncation at the fresh-download limit.
        retry.release { }
        fresh.release { check(File(it).delete()) }
        assertThat(File(uri).readBytes()).isEqualTo(bytes)
        assertThat(ownerMarkers()).isEmpty()
    }

    @Test fun replacementNeverMarksLegacyOutsideOrAliasedPaths() = runTest {
        val store = OwnedMediaDownloadStore(temporary.root)
        val legacy = temporary.newFile("legacy.jpg")
        assertThat(store.retainForReplacement(legacy.path)).isNull()
        assertThat(store.retainForReplacement("../outside.jpg")).isNull()
        val target = store.begin(uuid, "log", null)
        target.output.write(bytes)
        val uri = target.complete(receipt)
        target.release { }
        assertThat(store.retainForReplacement(uri.replace("/media.jpg", "/../${File(uri).parentFile!!.name}/media.jpg"))).isNull()
        assertThat(ownerMarkers()).isEmpty()
    }

    @Test fun replacementRejectsSymlinkTargetAndPreservesOutsideBytes() = runTest {
        val store = OwnedMediaDownloadStore(temporary.root)
        val download = store.begin(uuid, "log", null)
        download.output.write(bytes)
        val target = File(download.complete(receipt))
        download.release { }
        val outside = temporary.newFile("outside.jpg").apply { writeBytes(bytes) }
        check(target.delete())
        Files.createSymbolicLink(target.toPath(), outside.toPath())
        assertThrows(IllegalStateException::class.java) { store.retainForReplacement(target.path) }
        assertThat(ownerMarkers()).isEmpty()
        assertThat(outside.readBytes()).isEqualTo(bytes)
    }

    @Test fun retirementOverflowFailsClosedWithoutTruncatingRecovery() = runTest {
        val store = OwnedMediaDownloadStore(temporary.root, maximumPendingPerRoot = 1)
        val uris = (1..3).map {
            val target = store.begin(uuid, "log", null)
            target.output.write(bytes)
            target.complete(receipt).also { target.release { } }
        }
        val first = requireNotNull(store.retainForReplacement(uris[0]))
        val second = requireNotNull(store.retainForReplacement(uris[1]))
        assertThrows(IllegalStateException::class.java) { store.retainForReplacement(uris[2]) }
        val restarted = OwnedMediaDownloadStore(temporary.root, maximumPendingPerRoot = 1)
        assertThat(restarted.pending()).hasSize(2)
        uris.forEach { assertThat(File(it).readBytes()).isEqualTo(bytes) }
        first.release { }
        second.release { }
    }

    @Test fun rearmedAvatarRetainsExactRelativeReferenceKey() = runTest {
        val store = OwnedMediaDownloadStore(temporary.root)
        val target = store.begin(uuid, "avatar", "image/png")
        target.output.write(bytes)
        val uri = target.complete(receipt)
        target.release { }
        requireNotNull(store.retainForReplacement(uri))
        OwnedMediaDownloadStore(temporary.root).pending().single().release { path ->
            assertThat(path).isEqualTo(uri)
            check(File(temporary.root, path).delete())
        }
        assertThat(File(temporary.root, uri).exists()).isFalse()
    }

    private fun ownerMarkers(): List<File> =
        File(temporary.root, "record-media/.sync-download-owners").listFiles()?.toList().orEmpty()
}
