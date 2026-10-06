package com.lezi.babylog.feature.export

import com.lezi.babylog.core.common.failure.FailureKind
import com.lezi.babylog.core.common.productUiError
import java.io.IOException
import kotlinx.coroutines.TimeoutCancellationException

/**
 * 票 10（T3）：导出反馈文案（Tier B 常量，JVM 测试锁定）与失败归因纯函数
 * （先例 `clearRecordsFailureCopy` / `SummaryEmptyState`）。
 * 术语红线（CONTEXT.md）：对家长说「护理记录」；UI 不出现 "Owner"、
 * 不出现「待加载」类工程词；失败文案不出现套接字/路径/英文异常原文。
 */

/** 空态卡标题：所选导出范围没有任何护理记录（StateContainer(Empty)）。 */
internal const val EXPORT_EMPTY_RANGE_TITLE = "这个范围还没有记录"

/** 空态卡正文：说明该范围没有可导出的内容，指向调整日期范围；不产出近空文件。 */
internal const val EXPORT_EMPTY_RANGE_MESSAGE =
    "这段时间没有护理记录，没有可导出的内容；换个日期范围再试。"

/** 生成期进度说明（不确定进度条下方一句话）。 */
internal const val EXPORT_GENERATION_NOTE = "正在生成，最长约半分钟"

/** 成功终态 Snackbar：已生成可分享，并说明导出文件按龄保留、之后自动清理。 */
internal const val EXPORT_GENERATED_SNACKBAR = "已生成 · 可分享；文件保留约一天，之后自动清理"

/** 取消/未完成分享的终态 Snackbar：文件仍在，可从本页再次导出分享。 */
internal const val EXPORT_SHARE_CANCELLED_SNACKBAR = "文件已生成，可在本页再次分享"

/** 未识别异常的内联兜底文案（productUiError fallback；不再一律「输入无效」）。 */
internal const val EXPORT_UNRECOGNIZED_FAILURE_FALLBACK = "导出没有完成，护理记录不受影响，可以再试一次"

/**
 * 导出失败归因：`dialogKind` 走共享失败说明框（FailureCatalog），为 null 时用
 * [inlineMessage] 内联展示。超时维持 `ExportTookTooLong`；磁盘/IO 类走既有
 * 「写入失败、记录未受影响」档 `LocalSaveFailed`；其余异常经 `productUiError`
 * 兜底，不再一律 `InvalidInput`。
 */
internal data class ExportFailureAttribution(
    val dialogKind: FailureKind?,
    val inlineMessage: String?,
)

internal fun exportFailureAttribution(error: Throwable): ExportFailureAttribution = when (error) {
    is TimeoutCancellationException -> ExportFailureAttribution(
        dialogKind = FailureKind.ExportTookTooLong,
        inlineMessage = null,
    )
    is IOException -> ExportFailureAttribution(
        dialogKind = FailureKind.LocalSaveFailed,
        inlineMessage = null,
    )
    else -> ExportFailureAttribution(
        dialogKind = null,
        inlineMessage = productUiError(error, EXPORT_UNRECOGNIZED_FAILURE_FALLBACK),
    )
}
