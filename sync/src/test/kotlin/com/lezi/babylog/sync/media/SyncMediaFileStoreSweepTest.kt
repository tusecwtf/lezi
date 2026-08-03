package com.lezi.babylog.sync.media

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.LocalDataClearScope
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Test

class SyncMediaFileStoreSweepTest {
    @Test
    fun recordsOnlySweepsDraftsButRetainsOwnedRecordAndEntireAvatarRoot() {
        withMediaRoot { filesDir ->
            val draft = filesDir.file("record-media/draft.jpg")
            val retained = filesDir.file("record-media/retained.jpg")
            val avatar = filesDir.file("baby_avatars/unowned-avatar.jpg")

            sweepUnreferencedProductMedia(
                filesDir = filesDir,
                scope = LocalDataClearScope.RecordsOnly,
                retainedLocalUris = setOf(retained.absolutePath),
            )

            assertThat(draft.exists()).isFalse()
            assertThat(retained.exists()).isTrue()
            assertThat(avatar.exists()).isTrue()
        }
    }

    @Test
    fun allLocalDataSweepsBothKnownRootsWithoutTouchingOtherPrivateFiles() {
        withMediaRoot { filesDir ->
            val recordDraft = filesDir.file("record-media/nested/draft.jpg")
            val avatarDraft = filesDir.file("baby_avatars/draft.jpg")
            val unrelated = filesDir.file("documents/keep.txt")

            sweepUnreferencedProductMedia(
                filesDir = filesDir,
                scope = LocalDataClearScope.AllLocalData,
                retainedLocalUris = emptySet(),
            )

            assertThat(recordDraft.exists()).isFalse()
            assertThat(avatarDraft.exists()).isFalse()
            assertThat(unrelated.exists()).isTrue()
            assertThat(File(filesDir, "record-media").isDirectory).isTrue()
            assertThat(File(filesDir, "baby_avatars").isDirectory).isTrue()
        }
    }

    private fun withMediaRoot(block: (File) -> Unit) {
        val root = createTempDirectory("lezi-media-sweep-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun File.file(relativePath: String): File =
        File(this, relativePath).apply {
            checkNotNull(parentFile).mkdirs()
            writeText("private-media")
        }
}
