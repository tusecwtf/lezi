package com.lezi.babylog.feature.family.wizard

import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Displays server evidence only; the device clock never decides the remote request state. */
internal fun memberApprovalExpiryCopy(
    expiresAtEpochSeconds: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    if (expiresAtEpochSeconds <= 0L) return "申请有效期暂不可用。"
    return try {
        val deadline = Instant.ofEpochSecond(expiresAtEpochSeconds).atZone(zoneId)
        val formatted = deadline.format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm", Locale.CHINA))
        "申请有效期至 $formatted（本机时间）。"
    } catch (_: DateTimeException) {
        "申请有效期暂不可用。"
    }
}
