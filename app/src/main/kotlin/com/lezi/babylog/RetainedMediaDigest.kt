package com.lezi.babylog

import com.lezi.babylog.core.common.MediaContentDigest
import java.io.InputStream
import java.io.PushbackInputStream

/** Hash retained upgrade bytes with bounded memory; the caller owns [input]. */
internal fun retainedMediaSha256(input: InputStream, localUri: String): String {
    // Probe the same open stream that is hashed, rather than statting the path
    // separately. Empty/unreadable media must still fail closed during upgrade.
    val readable = PushbackInputStream(input, 1)
    val firstByte = readable.read()
    check(firstByte >= 0) {
        "sleep 媒体字节为空，无法计算 wire §11 sha256: $localUri"
    }
    readable.unread(firstByte)
    return MediaContentDigest.ofStream(readable)
}
