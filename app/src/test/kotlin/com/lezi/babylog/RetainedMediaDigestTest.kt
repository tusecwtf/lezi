package com.lezi.babylog

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import org.junit.Assert.assertThrows
import org.junit.Test

class RetainedMediaDigestTest {
    @Test
    fun retainedBytesKeepTheirKnownDigest() {
        assertThat(retainedMediaSha256(byteArrayOf(1, 2, 3, 4).inputStream(), "photo.jpg"))
            .isEqualTo("9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a")
    }

    @Test
    fun singleByteIncludingZeroAndUnsignedValuesIsNotDiscarded() {
        for (value in listOf(0, 1, 127, 128, 255)) {
            val bytes = byteArrayOf(value.toByte())
            assertThat(retainedMediaSha256(bytes.inputStream(), "single.jpg"))
                .isEqualTo(referenceDigest(bytes))
        }
    }

    @Test
    fun emptyMediaRetainsFailClosedDiagnostic() {
        val failure = assertThrows(IllegalStateException::class.java) {
            retainedMediaSha256(byteArrayOf().inputStream(), "empty.jpg")
        }
        assertThat(failure).hasMessageThat()
            .isEqualTo("sleep 媒体字节为空，无法计算 wire §11 sha256: empty.jpg")
    }

    @Test
    fun largeMediaIsReadInBoundedChunksAndPreservesTail() {
        val bytes = ByteArray(2 * 1024 * 1024 + 17) { (it * 31).toByte() }
        var largestRead = 0
        var bytesRead = 0
        val input = object : ByteArrayInputStream(bytes) {
            override fun read(): Int = super.read().also { if (it >= 0) bytesRead++ }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                largestRead = maxOf(largestRead, length)
                // Real streams may return fewer bytes than requested.
                return super.read(buffer, offset, minOf(length, 257)).also {
                    if (it > 0) bytesRead += it
                }
            }
        }

        assertThat(retainedMediaSha256(input, "large.jpg")).isEqualTo(referenceDigest(bytes))
        assertThat(bytesRead).isEqualTo(bytes.size)
        assertThat(largestRead).isAtMost(DEFAULT_BUFFER_SIZE)
    }

    @Test
    fun midStreamReadFailureIsNotReplacedWithPartialDigest() {
        val expected = IOException("injected read failure")
        val input = object : InputStream() {
            override fun read(): Int = 1

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw expected
        }

        val actual = assertThrows(IOException::class.java) {
            retainedMediaSha256(input, "broken.jpg")
        }
        assertThat(actual).isSameInstanceAs(expected)
    }

    @Test
    fun callerRetainsStreamOwnership() {
        var closed = false
        val input = object : ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) {
            override fun close() {
                closed = true
                super.close()
            }
        }

        retainedMediaSha256(input, "photo.jpg")
        assertThat(closed).isFalse()
        input.close()
        assertThat(closed).isTrue()
    }

    private fun referenceDigest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
