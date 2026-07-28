package com.lezi.babylog.sync

import com.lezi.babylog.core.common.looksTechnicalDetail
import com.lezi.babylog.core.common.productUiError

/**
 * One product-facing error policy shared by join-family and other family-sync
 * entry points (settings network save, create/rename family, etc.).
 */
fun familySyncError(error: Throwable, fallback: String): String {
    if (error is SyncNotEnabledException) {
        return "请先填写家庭服务器地址并绑定 Wi‑Fi 名称后加入家庭"
    }
    // Always surface HTTP status + server `detail` for ops (422 validation, 401, …).
    // Message is already product-shaped by [formatSyncHttpFailure]; do not re-filter.
    if (error is SyncHttpException) {
        return error.message?.trim().orEmpty().ifEmpty {
            formatSyncHttpFailure(error.statusCode, error.responseBody)
        }
    }
    if (looksTechnicalDetail(error.message.orEmpty())) {
        return "家庭同步服务暂未连接，请稍后重试"
    }
    return productUiError(error, fallback)
}

/** Join-family entry points share the same policy with a join-specific fallback. */
fun joinFamilyError(error: Throwable): String =
    familySyncError(error, "加入家庭失败，请稍后重试")
