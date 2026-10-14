package com.lezi.babylog.sync.media

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.LocalDataClearScope
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncMediaFileStoreDispatcherTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun defaultDiskStatMovesReadabilityAndLengthOffTheCallerThread() = runTest {
        val file = temporary.newFile().also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        val callerThread = Thread.currentThread()
        var readableThread: Thread? = null
        val store = object : SyncMediaFileStore {
            override fun readableFile(localUri: String): File? {
                readableThread = Thread.currentThread()
                return super.readableFile(localUri)
            }
            override suspend fun inspect(localUri: String): LocalMediaInfo? = error("unused")
            override suspend fun prepareUpload(localUri: String): PreparedMedia = error("unused")
            override suspend fun saveDownloaded(
                clientUuid: String, kind: String, bytes: ByteArray, mime: String?,
            ): String = error("unused")
            override suspend fun delete(localUri: String) = error("unused")
            override suspend fun sweepUnreferenced(scope: LocalDataClearScope, retainedLocalUris: Set<String>) =
                error("unused")
        }

        assertThat(store.statLength(file.absolutePath)).isEqualTo(3L)

        assertThat(readableThread).isNotNull()
        assertThat(readableThread).isNotSameInstanceAs(callerThread)
        assertThat(Thread.currentThread()).isSameInstanceAs(callerThread)
    }
}
