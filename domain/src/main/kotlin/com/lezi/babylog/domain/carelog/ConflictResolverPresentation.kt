package com.lezi.babylog.domain.carelog

import com.lezi.babylog.core.model.CarePlan
import com.lezi.babylog.core.model.CarePlanStatus
import com.lezi.babylog.core.model.Record
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.displayLabel
import com.lezi.babylog.core.model.parseBabySex
import com.lezi.babylog.core.model.payloadSummary
import com.lezi.babylog.sync.conflict.ConflictRoot
import com.lezi.babylog.sync.conflict.ConflictVersionSnapshot
import java.time.ZoneId

enum class ConflictResolverVersionKind { Live, Deleted, Restore }

internal fun actorDisplayName(
    membershipId: String,
    actorNames: Map<String, String>,
): String {
    val id = membershipId.trim()
    if (id.isEmpty()) return "家人"
    return actorNames[id]?.takeIf(String::isNotBlank) ?: "家人"
}

internal fun formatConflictTime(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    if (epochMs <= 0L) return ""
    return com.lezi.babylog.core.model.ProductDateTime.monthDayTime(epochMs, zone)
}

internal fun ConflictRoot.productTitle(clientUuid: String): String = presentationTitle(clientUuid)

internal fun ConflictRoot.productSummary(): String {
    val parts = when (this) {
        is ConflictRoot.Baby -> listOfNotNull(
            nickname.takeIf(String::isNotBlank),
            sexLabel(sex),
            birthday?.takeIf(String::isNotBlank),
        )
        is ConflictRoot.Record -> recordProductLines()
        is ConflictRoot.CarePlan -> carePlanProductLines()
        is ConflictRoot.CustomItem -> listOfNotNull(name.takeIf(String::isNotBlank))
        is ConflictRoot.WakeObservation -> listOfNotNull(
            formatConflictTime(wakeTimestamp).takeIf(String::isNotBlank)?.let { "醒来 $it" },
            note?.trim()?.takeIf(String::isNotBlank),
            if (withdrawn) "已撤回" else null,
        )
    }
    return parts.filter(String::isNotBlank).joinToString(" · ")
}

internal fun ConflictVersionSnapshot.cardLabel(
    role: ConflictVersionRole,
    kind: ConflictResolverVersionKind,
): String = when (kind) {
    ConflictResolverVersionKind.Deleted ->
        if (role == ConflictVersionRole.Stable) "当前已删除" else "删除这条"
    ConflictResolverVersionKind.Restore -> "恢复删除前"
    ConflictResolverVersionKind.Live ->
        if (role == ConflictVersionRole.Stable) "当前家里在用的" else "另一版修改"
}

internal fun ConflictResolverVersionKind.consequence(entityLabel: String): String = when (this) {
    ConflictResolverVersionKind.Live -> "所有设备将按这一版显示这条$entityLabel"
    ConflictResolverVersionKind.Deleted -> "所有设备时间轴上都不再显示这条$entityLabel"
    ConflictResolverVersionKind.Restore -> "所有设备将恢复删除前的这条$entityLabel"
}

private fun ConflictRoot.Record.recordProductLines(): List<String> {
    val type = RecordType.fromKey(type)
    val record = type?.let {
        Record(
            clientUuid = babyClientUuid,
            babyId = 0L,
            type = it,
            timestamp = timestamp,
            endTimestamp = endTimestamp,
            note = note,
            payloadJson = payload.toString(),
            schemaVersion = schemaVersion,
            updatedAt = updatedAt,
        )
    }
    return listOfNotNull(
        formatConflictTime(timestamp).takeIf(String::isNotBlank),
        record?.payloadSummary()?.takeIf(String::isNotBlank),
        note?.trim()?.takeIf(String::isNotBlank),
    )
}

private fun ConflictRoot.CarePlan.carePlanProductLines(): List<String> {
    val type = RecordType.fromKey(type)
    val plan = type?.let {
        CarePlan(
            clientUuid = babyClientUuid,
            babyId = 0L,
            type = it,
            scheduledAt = scheduledAt,
            scheduledZoneId = scheduledZoneId,
            note = note,
            payloadJson = payload.toString(),
            schemaVersion = schemaVersion,
            status = CarePlanStatus.fromStorage(status),
            createdByMembershipId = createdByMembershipId,
            fulfilledRecordClientUuid = fulfilledRecordClientUuid,
            fulfilledAt = fulfilledAt,
            sourceRecordClientUuid = sourceRecordClientUuid,
            updatedAt = updatedAt,
        )
    }
    return listOfNotNull(
        formatConflictTime(scheduledAt).takeIf(String::isNotBlank)?.let { "计划 $it" },
        plan?.displayLabel()?.takeIf(String::isNotBlank),
        note?.trim()?.takeIf(String::isNotBlank),
        when (CarePlanStatus.fromStorage(status)) {
            CarePlanStatus.COMPLETED -> "已完成"
            CarePlanStatus.SKIPPED -> "已跳过"
            CarePlanStatus.MISSED -> "已错过"
            CarePlanStatus.PENDING -> null
        },
    )
}

private fun sexLabel(raw: String?): String? = when (raw?.let(::parseBabySex)) {
    com.lezi.babylog.core.model.Sex.FEMALE -> "女"
    com.lezi.babylog.core.model.Sex.MALE -> "男"
    else -> null
}
