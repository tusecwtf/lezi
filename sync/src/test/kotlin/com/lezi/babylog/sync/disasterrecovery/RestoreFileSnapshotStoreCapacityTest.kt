package com.lezi.babylog.sync.disasterrecovery

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Deliberate >512 MiB actual-file proof, run only in the validator's exclusive I/O window. */
class RestoreFileSnapshotStoreCapacityTest {
    @Test fun selectedRestoreAboveOrdinarySpoolQuotaUsesOneOwnedCopyPerMedia() = runBlocking {
        val directory = Files.createTempDirectory("restore-file-capacity-").toFile()
        try {
            val source = File(directory, "source.jpg")
            RandomAccessFile(source, "rw").use { it.setLength(8L * 1024 * 1024) }
            val sources = (1..65).map { index ->
                RestoreFileSnapshotSource(UUID(0L, index.toLong()).toString(), source, "image/jpeg", 1, 1)
            }
            val root = File(directory, "restore")
            val store = RestoreFileSnapshotStore(root)
            val pointer = store.capture("11111111-1111-4111-8111-111111111111", sources, 1024) { "{}" }
            val snapshot = store.read(pointer)
            val expectedBytes = 545_259_520L // 65 * 8 MiB = 520 MiB, beyond the ordinary 512 MiB spool.
            val observedOwned = root.walkTopDown().filter(File::isFile).sumOf(File::length)
            assertThat(snapshot.media).hasSize(65)
            assertThat(snapshot.media.sumOf { it.byteSize }).isEqualTo(expectedBytes)
            assertThat(store.metrics.sourceOpens).isEqualTo(65)
            assertThat(store.metrics.sourceBytesRead).isEqualTo(expectedBytes)
            assertThat(store.metrics.copiedBytes).isEqualTo(expectedBytes)
            assertThat(store.metrics.hashedBytes).isEqualTo(expectedBytes)
            assertThat(store.metrics.ownedBytes).isEqualTo(observedOwned)
            assertThat(store.metrics.peakOwnedBytes).isEqualTo(observedOwned)
            assertThat(observedOwned).isLessThan(expectedBytes + 1024 * 1024)
            println("restore-capacity sourceReads=${store.metrics.sourceOpens} sourceBytes=${store.metrics.sourceBytesRead} " +
                "copyBytes=${store.metrics.copiedBytes} hashBytes=${store.metrics.hashedBytes} " +
                "ownedLogicalBytes=$observedOwned peakOwnedLogicalBytes=${store.metrics.peakOwnedBytes}")
            store.discard(pointer.requestId)
            assertThat(root.walkTopDown().filter(File::isFile).sumOf(File::length)).isLessThan(1024)
            assertThat(source.length()).isEqualTo(8L * 1024 * 1024)
        } finally {
            check(directory.deleteRecursively())
        }
    }
}
