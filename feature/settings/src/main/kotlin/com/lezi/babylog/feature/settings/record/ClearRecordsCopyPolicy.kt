package com.lezi.babylog.feature.settings.record

internal data class ClearRecordsConfirmationCopy(
    val firstPrompt: String,
    val finalPrompt: String,
)

internal fun clearRecordsConfirmationCopy(
    isFamilyJoined: Boolean,
): ClearRecordsConfirmationCopy = if (isFamilyJoined) {
    ClearRecordsConfirmationCopy(
        firstPrompt =
            "只清除这台设备上的喂养、睡眠等记录；宝宝档案和家庭服务器上的记录仍保留。",
        finalPrompt =
            "真的要清除本机全部记录吗？宝宝不会被删除；" +
                "下次家庭同步时，服务器上的记录可能重新下载。",
    )
} else {
    ClearRecordsConfirmationCopy(
        firstPrompt = "只清除这台设备上的喂养、睡眠等记录；宝宝档案会保留。",
        finalPrompt = "真的要清除本机全部记录吗？宝宝档案会保留。",
    )
}
