package com.lezi.babylog.sync.media

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.common.MediaContentDigest
import com.lezi.babylog.core.database.LocalDataClearScope
import com.lezi.babylog.sync.backend.MediaDownloadReceipt
import kotlinx.coroutines.test.runTest
import org.junit.Test

class BufferedMediaDownloadTargetTest {
    @Test
    fun emptyDownloadCannotPublishEvenWhenTheLegacyStoreAcceptsEmptyBytes() = runTest {
        val store = PermissiveStore()
        val target = store.beginDownload("media", "log", "image/jpeg") { }
        val failure = runCatching {
            target.complete(MediaDownloadReceipt(MediaContentDigest.ofBytes(byteArrayOf()), 0))
        }.exceptionOrNull()
        var reclaimed = false
        target.release { reclaimed = true }

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(store.saved).isEmpty()
        assertThat(reclaimed).isFalse()
    }

    @Test
    fun nonemptyDownloadStillPublishesAndReleasesTheSavedPath() = runTest {
        val store = PermissiveStore()
        val target = store.beginDownload("media", "log", "image/jpeg") { }
        val bytes = byteArrayOf(1, 2, 3)
        target.output.write(bytes)
        val uri = target.complete(MediaDownloadReceipt(MediaContentDigest.ofBytes(bytes), bytes.size.toLong()))
        var released: String? = null
        target.release { released = it }

        assertThat(store.saved.single()).isEqualTo(bytes)
        assertThat(uri).isEqualTo("saved/media")
        assertThat(released).isEqualTo(uri)
    }

    private class PermissiveStore : SyncMediaFileStore {
        val saved = mutableListOf<ByteArray>()
        override suspend fun inspect(localUri: String): LocalMediaInfo? = error("unused")
        override suspend fun prepareUpload(localUri: String): PreparedMedia = error("unused")
        override suspend fun saveDownloaded(
            clientUuid: String, kind: String, bytes: ByteArray, mime: String?,
        ): String {
            saved += bytes
            return "saved/$clientUuid"
        }
        override suspend fun delete(localUri: String) = error("unused")
        override suspend fun sweepUnreferenced(scope: LocalDataClearScope, retainedLocalUris: Set<String>) =
            error("unused")
    }
}
