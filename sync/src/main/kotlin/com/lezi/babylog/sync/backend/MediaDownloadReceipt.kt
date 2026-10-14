package com.lezi.babylog.sync.backend

import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream

/** Same media GET bound used by both byte-array and streaming transports. */
internal const val MAX_SYNC_MEDIA_RESPONSE_BYTES = 10 * 1024 * 1024

data class MediaDownloadReceipt(val sha256: String, val byteCount: Long)

/** Local sink failure must not be classified/retried as a network read failure. */
internal class MediaDownloadWriteException(cause: IOException) :
    IllegalStateException("无法保存下载媒体", cause)

internal fun OutputStream.withMediaDownloadWriteErrors(): OutputStream = object : FilterOutputStream(this) {
    override fun write(value: Int) {
        try {
            out.write(value)
        } catch (failure: IOException) {
            throw MediaDownloadWriteException(failure)
        }
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        try {
            out.write(bytes, offset, length)
        } catch (failure: IOException) {
            throw MediaDownloadWriteException(failure)
        }
    }
}
