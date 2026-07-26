package com.lezi.babylog.domain

/** A local clear committed before its replica/settings cleanup finished. */
class LocalRecordsClearCommittedException(
    val familyServerRetained: Boolean,
    cause: Throwable,
) : RuntimeException(cause.message ?: "本机清理提交后收尾失败", cause)
