package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreparedMediaStreamingTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun preparedMediaIsARepeatableFileSourceThatOwnsItsCleanup() {
        val file = temporaryFolder.newFile("normalized.jpg").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val prepared = PreparedMedia(
            file = file,
            mime = "image/jpeg",
            width = 40,
            height = 30,
        )

        assertThat(prepared.contentLength).isEqualTo(4L)
        assertThat(prepared.openStream().use { it.readBytes() })
            .isEqualTo(byteArrayOf(1, 2, 3, 4))
        assertThat(prepared.openStream().use { it.readBytes() })
            .isEqualTo(byteArrayOf(1, 2, 3, 4))

        prepared.close()
        prepared.close()

        assertThat(file.exists()).isFalse()
    }
}
