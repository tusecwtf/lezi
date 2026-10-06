package com.lezi.babylog.sync.media

import java.io.IOException

/** Retryable local prepare failure; the source row remains dirty for a later sync attempt. */
class MediaPrepareException internal constructor(
    message: String,
    cause: Throwable,
) : IOException(message, cause)

internal inline fun <T> translateMediaPrepareOutOfMemory(block: () -> T): T =
    try {
        block()
    } catch (error: OutOfMemoryError) {
        throw MediaPrepareException("本地媒体处理内存不足，请稍后重试", error)
    }
