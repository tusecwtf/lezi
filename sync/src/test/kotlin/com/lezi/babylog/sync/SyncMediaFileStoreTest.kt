package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class SyncMediaFileStoreTest {
    @Test
    fun existingFileDeleteFailureIsReported() {
        val undeletable = object : File("existing-media.jpg") {
            override fun isFile(): Boolean = true
            override fun delete(): Boolean = false
            override fun exists(): Boolean = true
        }

        val failure = runCatching {
            deleteExistingSyncMediaFile(undeletable)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure).hasMessageThat().contains("无法删除本地媒体文件")
    }

    @Test
    fun missingFileDeletionIsIdempotent() {
        deleteExistingSyncMediaFile(null)
        deleteExistingSyncMediaFile(
            object : File("missing-media.jpg") {
                override fun isFile(): Boolean = false
                override fun delete(): Boolean = error("missing file must not be deleted")
            },
        )
    }
}
