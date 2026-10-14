package com.lezi.babylog.core.common

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.InputStream
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
    fun hexPreservesEveryUnsignedByteAndLeadingZero() {
        val bytes = ByteArray(256) { it.toByte() }
        val expected =
            "000102030405060708090a0b0c0d0e0f" +
                "101112131415161718191a1b1c1d1e1f" +
                "202122232425262728292a2b2c2d2e2f" +
                "303132333435363738393a3b3c3d3e3f" +
                "404142434445464748494a4b4c4d4e4f" +
                "505152535455565758595a5b5c5d5e5f" +
                "606162636465666768696a6b6c6d6e6f" +
                "707172737475767778797a7b7c7d7e7f" +
                "808182838485868788898a8b8c8d8e8f" +
                "909192939495969798999a9b9c9d9e9f" +
                "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf" +
                "b0b1b2b3b4b5b6b7b8b9babbbcbdbebf" +
                "c0c1c2c3c4c5c6c7c8c9cacbcccdcecf" +
                "d0d1d2d3d4d5d6d7d8d9dadbdcdddedf" +
                "e0e1e2e3e4e5e6e7e8e9eaebecedeeef" +
                "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff"

        assertThat(MediaContentDigest.hex(bytes)).isEqualTo(expected)
        assertThat(bytes).isEqualTo(ByteArray(256) { it.toByte() })
        assertThat(MediaContentDigest.hex(byteArrayOf())).isEmpty()
    }

    @Test
    fun streamDigestHandlesShortAndZeroReadsWithoutClosingCallerStream() {
        var cursor = 0
        var returnZero = true
        var closed = false
        val input = object : InputStream() {
            override fun read(): Int = error("Expected bounded bulk reads")

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (returnZero) {
                    returnZero = false
                    return 0
                }
                if (cursor == 4) return -1
                buffer[offset] = (++cursor).toByte()
                return 1
            }

            override fun close() {
                closed = true
            }
        }

        assertThat(MediaContentDigest.ofStream(input)).isEqualTo(KNOWN_BYTES_1234_SHA256)
        assertThat(closed).isFalse()
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
