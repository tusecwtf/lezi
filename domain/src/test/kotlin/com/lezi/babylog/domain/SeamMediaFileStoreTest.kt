package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Test

class SeamMediaFileStoreTest {
    @Test
    fun ownedDownloadReservesExactDestinationBeforeCreatingBytes(): Unit = runBlocking {
        val root = Files.createTempDirectory("seam-download-owner-").toFile()
        try {
            val store = SeamMediaFileStore(root)
            val bytes = byteArrayOf(2, 7, 1)
            var reserved: String? = null
            val path = store.saveDownloadedOwned(UUID, "wake", bytes, "image/jpeg") { destination ->
                assertThat(root.listFiles().orEmpty()).isEmpty()
                assertThat(File(destination).exists()).isFalse()
                reserved = destination
            }
            assertThat(path).isEqualTo(reserved)
            assertThat(File(path).readBytes()).isEqualTo(bytes)
            assertThat(root.listFiles().orEmpty().toList()).containsExactly(File(path))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun failedReservationCannotCreateOrOverwriteDownloadedBytes() = runBlocking {
        val root = Files.createTempDirectory("seam-download-rejected-").toFile()
        try {
            val store = SeamMediaFileStore(root)
            val target = File(root, "wake-$UUID.jpg")
            for (existing in listOf(false, true)) {
                if (existing) target.writeBytes(byteArrayOf(9))
                val failure = runCatching {
                    store.saveDownloadedOwned(UUID, "wake", byteArrayOf(1), "image/jpeg") {
                        throw IOException("synthetic reservation failure")
                    }
                }.exceptionOrNull()
                assertThat(failure).isInstanceOf(IOException::class.java)
                if (existing) assertThat(target.readBytes()).isEqualTo(byteArrayOf(9))
                else assertThat(target.exists()).isFalse()
                assertThat(root.listFiles().orEmpty().filter { it.name.endsWith(".tmp") }).isEmpty()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    companion object {
        private const val UUID = "00000000-0000-4000-8000-000000000001"
    }
}
