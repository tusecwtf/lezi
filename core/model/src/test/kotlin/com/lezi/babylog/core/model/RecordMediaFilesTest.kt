package com.lezi.babylog.core.model

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordMediaFilesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun deleteUnderAllowedRootOnlyRemovesDirectChildrenOfRecordMedia() {
        val filesDir = temporaryFolder.newFolder("files")
        val media = File(filesDir, RecordMediaFiles.DIRECTORY).apply { mkdirs() }
        val keepOutside = temporaryFolder.newFile("outside.jpg").apply {
            writeBytes(byteArrayOf(1))
        }
        val nestedDir = File(media, "nested").apply { mkdirs() }
        val nested = File(nestedDir, "deep.jpg").apply { writeBytes(byteArrayOf(2)) }
        val allowed = File(media, "ok.jpg").apply { writeBytes(byteArrayOf(3)) }
        val missing = File(media, "gone.jpg").absolutePath

        RecordMediaFiles.deleteUnderAllowedRoot(
            filesDir = filesDir,
            paths = listOf(
                allowed.absolutePath,
                nested.absolutePath,
                keepOutside.absolutePath,
                missing,
                "/etc/passwd",
            ),
        )

        assertThat(allowed.exists()).isFalse()
        assertThat(nested.exists()).isTrue()
        assertThat(keepOutside.exists()).isTrue()
    }
}
