package com.lezi.babylog.sync

/**
 * Signals that the authoritative local transaction committed before a later
 * cleanup barrier failed. Retry may finish cleanup but cannot restore rows.
 */
class LocalClearCommittedException(
    val familyServerRetained: Boolean,
    cause: Throwable,
) : RuntimeException(cause.message ?: "本机清理提交后收尾失败", cause)
