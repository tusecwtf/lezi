package com.lezi.babylog.sync

import com.lezi.babylog.core.common.looksTechnicalDetail
import com.lezi.babylog.core.common.productUiError

/** One product-facing error policy shared by every join-family entry point. */
fun joinFamilyError(error: Throwable): String {
    if (error is SyncNotEnabledException) {
        return "请先填写家庭服务器地址并绑定 Wi‑Fi 名称后加入家庭"
    }
    if (looksTechnicalDetail(error.message.orEmpty())) {
        return "家庭同步服务暂未连接，请稍后重试"
    }
    return productUiError(error, "加入家庭失败，请稍后重试")
}
