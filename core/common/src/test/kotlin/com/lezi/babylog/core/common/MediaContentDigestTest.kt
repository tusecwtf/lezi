package com.lezi.babylog.core.common

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class MediaContentDigestTest {
    @Test
    fun ofBytesUsesKnownLowercaseHexLiteral() {
        assertThat(MediaContentDigest.ofBytes(byteArrayOf(1, 2, 3, 4)))
            .isEqualTo(KNOWN_BYTES_1234_SHA256)
        assertThat(MediaContentDigest.hex(byteArrayOf(0x9f.toByte(), 0x64)))
            .isEqualTo("9f64")
        assertThat(MediaContentDigest.HEX.matches(KNOWN_BYTES_1234_SHA256)).isTrue()
    }

    @Test
    fun ofReadableFileReturnsNullWhenMissingAndHexWhenPresent() {
        val missing = File.createTempFile("media-sha-missing", ".bin").apply { delete() }
        assertThat(MediaContentDigest.ofReadableFile(missing.path)).isNull()
        assertThat(MediaContentDigest.ofReadableFile("")).isNull()

        val file = File.createTempFile("media-sha", ".bin")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3, 4))
            assertThat(MediaContentDigest.ofReadableFile(file.path))
                .isEqualTo(KNOWN_BYTES_1234_SHA256)
        } finally {
            file.delete()
        }
    }

    companion object {
        const val KNOWN_BYTES_1234_SHA256 =
            "9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a"
    }
}
