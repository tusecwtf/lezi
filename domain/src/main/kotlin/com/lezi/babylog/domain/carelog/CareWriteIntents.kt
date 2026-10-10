package com.lezi.babylog.domain.carelog

import com.lezi.babylog.core.common.newClientUuid
import com.lezi.babylog.core.model.CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION
import com.lezi.babylog.core.model.RecordPayload
import com.lezi.babylog.core.model.RecordPayloadCodec
import com.lezi.babylog.core.model.RecordPayloadDocument
import com.lezi.babylog.core.model.SleepPayload
import com.lezi.babylog.core.model.UnknownPayload

@JvmInline value class CareBabyId(val value: Long)
@JvmInline value class CareRecordId(val value: Long)
@JvmInline value class CareSleepId(val value: Long)
@JvmInline value class CarePlanId(val value: Long)
@JvmInline value class CareWakeId(val value: String)
@JvmInline value class CareWriteId(val value: String) {
    companion object { fun new(): CareWriteId = CareWriteId(newClientUuid()) }
}

/** Keep is meaningful only when editing an existing attachment owner. */
sealed interface CareAttachments {
    data object Keep : CareAttachments
    data object RemoveAll : CareAttachments
    data class Replace(val paths: List<String>) : CareAttachments {
        init { require(paths.isNotEmpty()) { "请使用 RemoveAll 明确移除全部照片" } }
    }
    companion object {
        fun replaceWith(paths: List<String>): CareAttachments =
            if (paths.isEmpty()) RemoveAll else Replace(paths.toList())
    }
}

internal fun CareAttachments.forEdit(): List<String>? = when (this) {
    CareAttachments.Keep -> null
    CareAttachments.RemoveAll -> emptyList()
    is CareAttachments.Replace -> paths
}

internal fun CareAttachments.forNew(): List<String> =
    requireNotNull(forEdit()) { "新护理事实没有可保留的照片，请指定 RemoveAll 或 Replace" }

/** Typed content uses the existing codec only at the domain/storage boundary. */
data class CareFactContent(
    val timestamp: Long,
    val payload: RecordPayload,
    val note: String? = null,
    val endTimestamp: Long? = null,
)

internal fun RecordPayload.encodeCarePayload(): String {
    require(this !is UnknownPayload) { "未知护理内容不可写入" }
    return RecordPayloadCodec.encode(
        RecordPayloadDocument(type, this, CURRENT_RECORD_PAYLOAD_SCHEMA_VERSION),
    )
}

data class CreateCareRecord(
    val baby: CareBabyId,
    val content: CareFactContent,
    val attachments: CareAttachments = CareAttachments.RemoveAll,
    val writeId: CareWriteId = CareWriteId.new(),
)
data class EditCareRecord(
    val target: CareRecordId,
    val content: CareFactContent,
    val attachments: CareAttachments = CareAttachments.Keep,
)
data class StartCareSleep(
    val baby: CareBabyId,
    val at: Long,
    val payload: SleepPayload = SleepPayload(),
    val note: String? = null,
    val attachments: CareAttachments = CareAttachments.RemoveAll,
    val writeId: CareWriteId = CareWriteId.new(),
)
data class BackfillCareSleep(
    val baby: CareBabyId,
    val start: Long,
    val end: Long,
    val payload: SleepPayload = SleepPayload(),
    val note: String? = null,
    val attachments: CareAttachments = CareAttachments.RemoveAll,
    val writeId: CareWriteId = CareWriteId.new(),
)
data class EditOpenCareSleep(
    val baby: CareBabyId,
    val target: CareSleepId,
    val start: Long,
    val payload: SleepPayload = SleepPayload(),
    val note: String? = null,
    val attachments: CareAttachments = CareAttachments.Keep,
)
data class CloseCareSleep(
    val baby: CareBabyId,
    val target: CareSleepId,
    val start: Long,
    val wakeAt: Long,
    val wakeNote: String? = null,
    val attachments: CareAttachments = CareAttachments.RemoveAll,
    val writeId: CareWriteId = CareWriteId.new(),
)
data class CorrectCareWake(
    val target: CareWakeId,
    val at: Long,
    val note: String?,
    val attachments: CareAttachments = CareAttachments.Keep,
)
data class CreateCarePlan(
    val baby: CareBabyId,
    val content: CareFactContent,
    val customItemId: Long? = null,
    val attachments: CareAttachments = CareAttachments.RemoveAll,
    val projectToSystemCalendar: Boolean = true,
    val writeId: CareWriteId = CareWriteId.new(),
)
data class EditCarePlan(
    val target: CarePlanId,
    val content: CareFactContent,
    val attachments: CareAttachments = CareAttachments.Keep,
    val projectToSystemCalendar: Boolean? = null,
)
data class FulfillCarePlan(
    val target: CarePlanId,
    val content: CareFactContent,
    val attachments: CareAttachments = CareAttachments.RemoveAll,
    val writeId: CareWriteId = CareWriteId.new(),
)
data class ConvertCareRecordToPlan(
    val target: CareRecordId,
    val content: CareFactContent,
    val attachments: CareAttachments = CareAttachments.RemoveAll,
    val projectToSystemCalendar: Boolean = true,
    val writeId: CareWriteId = CareWriteId.new(),
)


internal fun requireExpectedCareType(
    expected: com.lezi.babylog.core.model.RecordType?,
    actualKey: String,
) {
    require(expected == null || expected.key == actualKey) { "护理内容类型与目标不匹配" }
}
