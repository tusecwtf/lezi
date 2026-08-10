package com.lezi.babylog.domain.carelog

import com.lezi.babylog.sync.backend.CausalMediaItem
import com.lezi.babylog.sync.backend.ConflictResolutionChoice
import com.lezi.babylog.sync.backend.ConflictResolveRequest
import com.lezi.babylog.sync.conflict.ConflictOutcome
import com.lezi.babylog.sync.conflict.ConflictRoot
import com.lezi.babylog.sync.conflict.ConflictRootType
import com.lezi.babylog.sync.conflict.ConflictSnapshot
import com.lezi.babylog.sync.conflict.ConflictSnapshotValidation
import com.lezi.babylog.sync.conflict.ConflictSource
import com.lezi.babylog.sync.conflict.ConflictVersionSnapshot
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

data class ConflictResolverAudience(
    val membershipId: String,
    val isOwner: Boolean,
)

enum class ConflictResolverAvailability {
    Current,
    Incomplete,
    Expired,
    Offline,
    Forbidden,
}

enum class ConflictVersionRole { Stable, Branch }

data class ConflictResolverVersion(
    val role: ConflictVersionRole,
    val label: String,
    val deleted: Boolean,
    val media: List<CausalMediaItem>,
    val provenance: String,
)

data class ConflictResolverOption(
    val choiceId: String,
    val value: String,
    val provenance: String,
)

data class ConflictResolverPath(
    val path: String,
    val label: String,
    val media: Boolean,
    val options: List<ConflictResolverOption>,
)

data class ConflictResolverAutoOutcome(
    val path: String,
    val label: String,
    val value: String,
    val provenance: String,
)

/**
 * Complete presentation projection for all five mutable roots. Compose consumes this
 * model and never parses transport JSON or reconstructs a resolved root/media set.
 */
data class ConflictResolverModel(
    val conflictId: String,
    val entityType: ConflictRootType,
    val entityLabel: String,
    val clientUuid: String,
    val snapshotToken: String,
    val expiresAt: Long,
    val paths: List<ConflictResolverPath>,
    val versions: List<ConflictResolverVersion>,
    val autoMerged: List<ConflictResolverAutoOutcome>,
    val availability: ConflictResolverAvailability,
    val readOnlyReason: String?,
) {
    val canResolve: Boolean get() = availability == ConflictResolverAvailability.Current
}

/** Bundle-safe state owned by the resolver interface, not by Compose. */
data class ConflictResolverSavedState(
    val conflictId: String,
    val snapshotToken: String,
    val resolutionMutationId: String,
    val selectedChoiceIds: Map<String, String>,
    val submitted: Boolean = false,
)

sealed interface ConflictResolverChoiceResult {
    data class Selected(val draft: ConflictResolverDraft) : ConflictResolverChoiceResult
    data class ReadOnly(val reason: String) : ConflictResolverChoiceResult
}

/**
 * One resolution attempt. The mutation identity is allocated when the snapshot opens
 * and remains stable across transport loss and process recreation.
 */
class ConflictResolverDraft private constructor(
    val model: ConflictResolverModel,
    val resolutionMutationId: String,
    val selectedChoiceIds: Map<String, String>,
    val submitted: Boolean,
    private val clock: () -> Long,
) {
    val canChoose: Boolean
        get() = !submitted && currentAvailability() == ConflictResolverAvailability.Current

    fun select(path: String, choiceId: String): ConflictResolverChoiceResult {
        if (submitted) {
            return ConflictResolverChoiceResult.ReadOnly(
                "已提交的解决选择不可改写；请关闭后重新打开",
            )
        }
        if (!canChoose) {
            return ConflictResolverChoiceResult.ReadOnly(currentReadOnlyReason())
        }
        val field = model.paths.singleOrNull { it.path == path }
            ?: throw IllegalArgumentException("不是当前快照的冲突字段: $path")
        require(field.options.any { it.choiceId == choiceId }) {
            "choice_id 不属于当前快照字段: $path"
        }
        return ConflictResolverChoiceResult.Selected(
            ConflictResolverDraft(
                model = model,
                resolutionMutationId = resolutionMutationId,
                selectedChoiceIds = selectedChoiceIds + (path to choiceId),
                submitted = false,
                clock = clock,
            ),
        )
    }

    fun choose(path: String, choiceId: String): ConflictResolverDraft = when (
        val result = select(path, choiceId)
    ) {
        is ConflictResolverChoiceResult.Selected -> result.draft
        is ConflictResolverChoiceResult.ReadOnly -> throw IllegalArgumentException(result.reason)
    }

    val complete: Boolean
        get() = selectedChoiceIds.keys == model.paths.mapTo(linkedSetOf()) { it.path }

    val canSubmit: Boolean
        get() = complete && when {
            submitted -> currentAvailability() in setOf(
                ConflictResolverAvailability.Current,
                ConflictResolverAvailability.Expired,
            )
            else -> currentAvailability() == ConflictResolverAvailability.Current
        }

    fun freeze(): ConflictResolverDraft {
        require(canSubmit) { currentReadOnlyReason() }
        return ConflictResolverDraft(
            model,
            resolutionMutationId,
            selectedChoiceIds,
            submitted = true,
            clock,
        )
    }

    fun command(): ConflictResolveRequest {
        require(submitted) { "提交前必须冻结 choice-only command" }
        require(canSubmit) { currentReadOnlyReason() }
        return ConflictResolveRequest(
            snapshotToken = model.snapshotToken,
            resolutionMutationId = resolutionMutationId,
            choices = model.paths.sortedBy(ConflictResolverPath::path).map { path ->
                ConflictResolutionChoice(
                    path = path.path,
                    choiceId = requireNotNull(selectedChoiceIds[path.path]),
                )
            },
        ).also { request ->
            ConflictSnapshotValidation.requireResolutionChoices(
                snapshotToken = request.snapshotToken,
                resolutionMutationId = request.resolutionMutationId,
                choices = request.choices.map { it.path to it.choiceId },
                context = "resolver command",
            )
        }
    }

    fun savedState(): ConflictResolverSavedState = ConflictResolverSavedState(
        conflictId = model.conflictId,
        snapshotToken = model.snapshotToken,
        resolutionMutationId = resolutionMutationId,
        selectedChoiceIds = selectedChoiceIds.toSortedMap(),
        submitted = submitted,
    )

    private fun currentAvailability(): ConflictResolverAvailability =
        if (model.availability == ConflictResolverAvailability.Current && clock() >= model.expiresAt) {
            ConflictResolverAvailability.Expired
        } else {
            model.availability
        }

    private fun currentReadOnlyReason(): String = when (currentAvailability()) {
        ConflictResolverAvailability.Current -> "每个冲突字段必须明确选择一次"
        ConflictResolverAvailability.Incomplete -> "冲突快照尚未完整，当前只能查看"
        ConflictResolverAvailability.Expired -> "冲突快照已过期，请联网刷新"
        ConflictResolverAvailability.Offline -> "离线快照只读，请联网后重新打开"
        ConflictResolverAvailability.Forbidden -> "当前身份不能解决这条冲突"
    }

    companion object {
        fun open(
            snapshot: ConflictSnapshot,
            audience: ConflictResolverAudience,
            fetchedOnline: Boolean,
            nowMillis: Long,
            restored: ConflictResolverSavedState? = null,
            resolutionMutationId: String,
            clock: () -> Long = { nowMillis },
        ): ConflictResolverDraft {
            ConflictSnapshotValidation.requireUuid(
                resolutionMutationId,
                "resolution mutation ID",
            )
            val model = snapshot.toResolverModel(audience, fetchedOnline, nowMillis)
            model.paths.flatMap(ConflictResolverPath::options).forEach { option ->
                ConflictSnapshotValidation.requireRuntimeToken(
                    option.choiceId,
                    "resolver choice_id",
                )
            }
            val validRestore = restored?.takeIf { saved ->
                saved.conflictId == model.conflictId &&
                    saved.snapshotToken == model.snapshotToken &&
                    runCatching {
                        ConflictSnapshotValidation.requireUuid(
                            saved.resolutionMutationId,
                            "saved resolution mutation ID",
                        )
                    }.isSuccess &&
                    saved.selectedChoiceIds.all { (path, choiceId) ->
                        model.paths.singleOrNull { it.path == path }
                            ?.options?.any { it.choiceId == choiceId } == true
                    } && (
                        !saved.submitted || saved.selectedChoiceIds.keys ==
                            model.paths.mapTo(linkedSetOf()) { it.path }
                    )
            }
            return ConflictResolverDraft(
                model = model,
                resolutionMutationId = validRestore?.resolutionMutationId ?: resolutionMutationId,
                selectedChoiceIds = validRestore?.selectedChoiceIds.orEmpty(),
                submitted = validRestore?.submitted ?: false,
                clock = clock,
            )
        }
    }
}

private fun ConflictSnapshot.toResolverModel(
    audience: ConflictResolverAudience,
    fetchedOnline: Boolean,
    nowMillis: Long,
): ConflictResolverModel {
    val fieldLabels = ConflictFieldLabels.forRoot(entityType)
    val paths = conflicting.map { conflict ->
        ConflictResolverPath(
            path = conflict.path,
            label = fieldLabels.label(conflict.path),
            media = conflict.path.startsWith("/media/"),
            options = conflict.candidates.map { candidate ->
                ConflictResolverOption(
                    choiceId = candidate.choiceId,
                    value = candidate.outcome.presentationValue(
                        path = conflict.path,
                        restoringDeletedRoot = stable.deleted,
                    ),
                    provenance = candidate.sources.presentationProvenance(),
                )
            },
        )
    }.sortedBy(ConflictResolverPath::path)
    require(paths.map { it.path }.distinct().size == paths.size) {
        "ConflictSnapshot path 重复"
    }
    require(paths.all { path ->
        path.options.size >= 2 &&
            path.options.map { it.choiceId }.distinct().size == path.options.size
    }) { "ConflictSnapshot candidates 不完整" }

    val authorized = audience.isOwner || (
        entityType != ConflictRootType.Baby &&
            stable.root.authorMembershipId() == audience.membershipId
    )
    val availability = when {
        !complete || pageIndex != 0 || continuation != null ->
            ConflictResolverAvailability.Incomplete
        nowMillis >= expiresAt -> ConflictResolverAvailability.Expired
        !fetchedOnline -> ConflictResolverAvailability.Offline
        !authorized -> ConflictResolverAvailability.Forbidden
        else -> ConflictResolverAvailability.Current
    }
    val readOnlyReason = when (availability) {
        ConflictResolverAvailability.Current -> null
        ConflictResolverAvailability.Incomplete -> "冲突快照尚未完整，当前只能查看"
        ConflictResolverAvailability.Expired -> "冲突快照已过期，请联网刷新"
        ConflictResolverAvailability.Offline -> "离线快照只读，请联网后重新打开"
        ConflictResolverAvailability.Forbidden -> if (entityType == ConflictRootType.Baby) {
            "仅家庭管理员可以解决宝宝资料冲突"
        } else {
            "仅事实作者或家庭管理员可以解决"
        }
    }
    return ConflictResolverModel(
        conflictId = conflictId,
        entityType = entityType,
        entityLabel = entityType.presentationLabel(),
        clientUuid = clientUuid,
        snapshotToken = snapshotToken,
        expiresAt = expiresAt,
        paths = paths,
        versions = buildList {
            add(stable.toPresentation(ConflictVersionRole.Stable, "当前稳定版"))
            branches.forEachIndexed { index, branch ->
                add(branch.toPresentation(ConflictVersionRole.Branch, "候选分支 ${index + 1}"))
            }
        },
        autoMerged = autoMerged.sortedBy { it.path }.map { merged ->
            ConflictResolverAutoOutcome(
                path = merged.path,
                label = fieldLabels.label(merged.path),
                value = merged.outcome.presentationValue(merged.path, stable.deleted),
                provenance = merged.sources.presentationProvenance(),
            )
        },
        availability = availability,
        readOnlyReason = readOnlyReason,
    )
}

private fun ConflictVersionSnapshot.toPresentation(
    role: ConflictVersionRole,
    label: String,
): ConflictResolverVersion = ConflictResolverVersion(
    role = role,
    label = label,
    deleted = deleted,
    media = media,
    provenance = "${actorId.ifBlank { "未知作者" }} · ${deviceId.ifBlank { "未知设备" }} · $receivedAt",
)

private fun List<ConflictSource>.presentationProvenance(): String =
    joinToString(separator = "；") { source ->
        "${source.actorId.ifBlank { "未知作者" }} · " +
            "${source.deviceId.ifBlank { "未知设备" }} · ${source.receivedAt}"
    }

private fun ConflictOutcome.presentationValue(
    path: String,
    restoringDeletedRoot: Boolean,
): String = when (this) {
    ConflictOutcome.Remove -> if (path.startsWith("/media/")) "不保留照片" else "移除"
    is ConflictOutcome.Set -> when {
        path == "/_mutation.deleted" -> when (value.booleanValue()) {
            true -> "删除"
            false -> if (restoringDeletedRoot) "恢复并保留" else "保留"
            null -> "无效删除状态"
        }
        path.startsWith("/media/") -> {
            val mediaUuid = (value as? JsonObject)
                ?.get("media_uuid")
                ?.let { it as? JsonPrimitive }
                ?.contentOrNull
                ?: path.substringAfterLast('/')
            "保留照片 ${mediaUuid.take(8)}"
        }
        value is JsonNull -> "清空"
        value is JsonPrimitive -> (value as JsonPrimitive).content
        else -> value.toString()
    }
}

private fun kotlinx.serialization.json.JsonElement.booleanValue(): Boolean? =
    (this as? JsonPrimitive)?.booleanOrNull

private fun ConflictRoot.authorMembershipId(): String = when (this) {
    is ConflictRoot.Baby -> createdByMembershipId
    is ConflictRoot.Record -> createdByMembershipId
    is ConflictRoot.CarePlan -> createdByMembershipId
    is ConflictRoot.CustomItem -> createdByMembershipId
    is ConflictRoot.WakeObservation -> observerMembershipId
}

private class ConflictFieldLabels private constructor(
    private val labels: Map<String, String>,
) {
    fun label(path: String): String = when {
        path == "/_mutation.deleted" -> "删除状态"
        path.startsWith("/media/") -> "照片 ${path.substringAfterLast('/').take(8)}"
        path.startsWith("/payload_json/") ->
            "详情 · ${path.substringAfterLast('/').replace('_', ' ')}"
        else -> labels[path] ?: path.substringAfterLast('/').replace('_', ' ')
    }

    companion object {
        private val common = mapOf(
            "/updated_at" to "更新时间",
            "/created_by_membership_id" to "事实作者",
        )
        private val baby = ConflictFieldLabels(
            common + mapOf(
                "/nickname" to "宝宝昵称",
                "/sex" to "性别",
                "/birthday" to "出生日期",
                "/avatar_media_uuid" to "头像",
            ),
        )
        private val record = ConflictFieldLabels(
            common + mapOf(
                "/baby_client_uuid" to "宝宝",
                "/type" to "记录类型",
                "/custom_item_client_uuid" to "自定义项目",
                "/timestamp" to "发生时间",
                "/end_timestamp" to "结束时间",
                "/note" to "备注",
                "/schema_version" to "详情版本",
                "/effective_wake_observation_client_uuid" to "有效醒来观察",
            ),
        )
        private val carePlan = ConflictFieldLabels(
            common + mapOf(
                "/baby_client_uuid" to "宝宝",
                "/type" to "计划类型",
                "/scheduled_at" to "计划时间",
                "/scheduled_zone_id" to "计划时区",
                "/note" to "备注",
                "/schema_version" to "详情版本",
                "/status" to "履行状态",
                "/fulfilled_record_client_uuid" to "履行记录",
                "/fulfilled_at" to "履行时间",
                "/source_record_client_uuid" to "来源记录",
                "/custom_item_client_uuid" to "自定义项目",
            ),
        )
        private val customItem = ConflictFieldLabels(
            common + mapOf(
                "/name" to "项目名称",
                "/icon_slot" to "项目图标",
            ),
        )
        private val wake = ConflictFieldLabels(
            common + mapOf(
                "/sleep_record_client_uuid" to "睡眠记录",
                "/wake_timestamp" to "醒来时间",
                "/note" to "备注",
                "/withdrawn" to "撤回状态",
                "/observer_membership_id" to "观察者",
            ),
        )

        fun forRoot(type: ConflictRootType): ConflictFieldLabels = when (type) {
            ConflictRootType.Baby -> baby
            ConflictRootType.Record -> record
            ConflictRootType.CarePlan -> carePlan
            ConflictRootType.CustomItem -> customItem
            ConflictRootType.WakeObservation -> wake
        }
    }
}
