package com.lezi.babylog.sync.session
import com.lezi.babylog.core.common.looksTechnicalDetail
import com.lezi.babylog.core.common.productUiError
import com.lezi.babylog.sync.SyncNotEnabledException
import com.lezi.babylog.sync.backend.ClientUpdateRequiredException
import com.lezi.babylog.sync.backend.SyncHttpException
import com.lezi.babylog.sync.backend.clientUpdateRequiredOrNull
import com.lezi.babylog.sync.backend.formatSyncHttpFailure

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
    // Always surface HTTP status + server `detail` for ops (422 validation, 401, …).
    // Message is already product-shaped by [formatSyncHttpFailure]; do not re-filter.
    // client_update_required is remapped so users are not told the NAS is "down".
    if (error is SyncHttpException) {
        if (error.clientUpdateRequiredOrNull() != null) {
            return "需要更新乐记后才能继续同步家庭数据"
        }
        return error.message?.trim().orEmpty().ifEmpty {
            formatSyncHttpFailure(error.statusCode, error.responseBody)
        }
    }
    if (looksTechnicalDetail(error.message.orEmpty())) {
        return "家庭同步服务暂未连接，请稍后重试"
    }
    return productUiError(error, fallback)
}
