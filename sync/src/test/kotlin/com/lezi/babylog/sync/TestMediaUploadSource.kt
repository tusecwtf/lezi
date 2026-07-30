package com.lezi.babylog.sync

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

internal class TestMediaUploadSource(
    private val content: ByteArray,
    override val mime: String? = "image/jpeg",
    override val contentLength: Long = content.size.toLong(),
) : SyncMediaUploadSource {
    var openCount: Int = 0
        private set

    override fun openStream(): InputStream {
        openCount += 1
        return ByteArrayInputStream(content)
    }
}

internal fun testPreparedMedia(
    content: ByteArray,
    mime: String = "image/jpeg",
    width: Int? = null,
    height: Int? = null,
): PreparedMedia {
    val file = File.createTempFile("prepared-media-test-", ".tmp").apply {
        writeBytes(content)
    }
    return PreparedMedia(file = file, mime = mime, width = width, height = height)
}
