package com.lezi.babylog.sync.session
import com.lezi.babylog.core.common.looksTechnicalDetail
import com.lezi.babylog.core.common.productUiError
import com.lezi.babylog.sync.SyncNotEnabledException
import com.lezi.babylog.sync.backend.ClientUpdateRequiredException
import com.lezi.babylog.sync.backend.SyncHttpException
import com.lezi.babylog.sync.backend.clientUpdateRequiredOrNull

/** One product-facing error policy shared by all family-session entry points. */
fun familySyncError(error: Throwable, fallback: String): String {
    if (error is SyncNotEnabledException) {
        return "请先填写并确认家庭服务器地址后加入家庭"
    }
    if (error is ClientUpdateRequiredException) {
        return error.message?.trim().orEmpty().ifEmpty {
            "需要更新乐记后才能继续同步家庭数据"
        }
    }
    // The exception retains HTTP status/body for debug logging, but product surfaces
    // receive only short action copy. Server detail is not a trusted UI string.
    if (error is SyncHttpException) {
        if (error.clientUpdateRequiredOrNull() != null) {
            return "需要更新乐记后才能继续同步家庭数据"
        }
        return when (error.statusCode) {
            401, 403 -> "登录已失效，请重新登录或联系家庭管理员"
            408, 504 -> "家庭服务器响应超时，请检查家庭网络后重试"
            409 -> "家庭状态已变化，请刷新后重试"
            422 -> "提交内容不符合要求，请检查后重试"
            429 -> "操作太频繁，请稍后重试"
            in 500..599 -> "家庭服务器暂时不可用，请稍后重试"
            else -> fallback
        }
    }
    if (looksTechnicalDetail(error.message.orEmpty())) {
        return "家庭同步服务暂未连接，请稍后重试"
    }
    return productUiError(error, fallback)
}
