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

    @Test
    fun localClearSweepsExclusiveDownloadsAndMarkersButKeepsAdoptedNestedMedia() {
        withMediaRoot { filesDir ->
            val prefix = "sync_11111111-1111-4111-8111-111111111111_22222222-2222-4222-8222-222222222222"
            val orphan = filesDir.file("record-media/$prefix/media.jpg")
            val marker = filesDir.file("record-media/.sync-download-owners/$prefix.jpg.owner")
            val adopted = filesDir.file("record-media/${prefix.replace("22222222-2222", "33333333-3333")}/media.jpg")
            val avatar = filesDir.file("baby_avatars/$prefix/media.jpg")
            sweepUnreferencedProductMedia(filesDir, LocalDataClearScope.RecordsOnly, setOf(adopted.absolutePath))
            assertThat(orphan.exists()).isFalse()
            assertThat(marker.exists()).isFalse()
            assertThat(orphan.parentFile.exists()).isFalse()
            assertThat(adopted.exists()).isTrue()
            assertThat(avatar.exists()).isTrue()
            sweepUnreferencedProductMedia(filesDir, LocalDataClearScope.AllLocalData, emptySet())
            assertThat(adopted.exists()).isFalse()
            assertThat(avatar.exists()).isFalse()
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
