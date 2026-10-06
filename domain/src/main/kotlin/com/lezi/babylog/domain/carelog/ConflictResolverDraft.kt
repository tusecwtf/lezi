package com.lezi.babylog.domain.carelog

import com.lezi.babylog.sync.backend.CausalMediaItem
import com.lezi.babylog.sync.backend.ConflictResolutionChoice
import com.lezi.babylog.sync.backend.ConflictResolveRequest
import com.lezi.babylog.sync.backend.ConflictWithdrawRequest
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
    Expired,
    Offline,
    Forbidden,
}

enum class ConflictVersionRole { Stable, Branch }

data class ConflictResolverVersion(
    val versionId: String,
    val role: ConflictVersionRole,
    val kind: ConflictResolverVersionKind,
    val label: String,
    val title: String,
    val summary: String,
    val actorLabel: String,
    val whenLabel: String,
    val consequence: String,
    val deleted: Boolean,
    val media: List<CausalMediaItem>,
    val provenance: String,
)

data class ConflictResolverOption(
    val choiceId: String,
    val value: String,
    val provenance: String,
    val sourceVersionIds: Set<String> = emptySet(),
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
    val stableVersionId: String,
    val branchVersionIds: List<String>,
    val paths: List<ConflictResolverPath>,
    val versions: List<ConflictResolverVersion>,
    val autoMerged: List<ConflictResolverAutoOutcome>,
    val availability: ConflictResolverAvailability,
    val readOnlyReason: String?,
    val canWithdrawBranches: Boolean,
    val isFamilyAdmin: Boolean = false,
    val conflictingFieldLabels: List<String> = emptyList(),
) {
    val canResolve: Boolean get() = availability == ConflictResolverAvailability.Current

    val differenceHighlights: List<String>
        get() = productDifferenceLabels(paths)

    val differenceLine: String?
        get() {
            val labels = differenceHighlights
            return when {
                labels.isNotEmpty() -> "不同：" + labels.joinToString("、")
                paths.isNotEmpty() -> "不同：内容细节"
                else -> null
            }
        }

    val canContinueEdit: Boolean
        get() = when (entityType) {
            ConflictRootType.Record,
            ConflictRootType.CarePlan,
            ConflictRootType.Baby,
            -> true
            ConflictRootType.CustomItem,
            ConflictRootType.WakeObservation,
            -> false
        }

    fun differenceTokens(versionId: String): List<String> {
        val tokens = linkedSetOf<String>()
        paths.forEach { path ->
            if (productDifferenceLabel(path.path) == null) return@forEach
            val value = path.options.firstOrNull { versionId in it.sourceVersionIds }
                ?.value
                ?.trim()
                .orEmpty()
            if (value.isNotEmpty()) tokens += value
        }
        return tokens.toList()
    }
    }

/** Bundle-safe state owned by the resolver interface, not by Compose. */
data class ConflictResolverSavedState(
    val conflictId: String,
    val snapshotToken: String,
    val resolutionMutationId: String,
    val selectedChoiceIds: Map<String, String>,
    val submitted: Boolean = false,
    val requiresRefresh: Boolean = false,
    val terminalDisposition: ConflictResolverTerminalDisposition? = null,
    val selectedVersionId: String? = null,
)

enum class ConflictResolverTerminalDisposition { Forbidden, Rejected }

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
    val withdrawalMutationId: String,
    val selectedChoiceIds: Map<String, String>,
    val selectedVersionId: String?,
    val submitted: Boolean,
    private val clock: () -> Long,
) {
    val canChoose: Boolean
        get() = !submitted && currentAvailability() == ConflictResolverAvailability.Current

    val canWithdraw: Boolean
        get() = !submitted && model.canWithdrawBranches && clock() < model.expiresAt

    val selectedVersion: ConflictResolverVersion?
        get() = selectedVersionId?.let { id -> model.versions.singleOrNull { it.versionId == id } }

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
        if (emptyBranchTombstone && isRestoreClassOption(field, choiceId)) {
            return ConflictResolverChoiceResult.ReadOnly(currentReadOnlyReason())
        }
        val nextChoices = selectedChoiceIds + (path to choiceId)
        return ConflictResolverChoiceResult.Selected(
            copyDraft(
                selectedChoiceIds = nextChoices,
                selectedVersionId = versionIdMatching(nextChoices),
            ),
        )
    }

    fun selectVersion(versionId: String): ConflictResolverChoiceResult {
        if (submitted) {
            return ConflictResolverChoiceResult.ReadOnly(
                "已提交的解决选择不可改写；请关闭后重新打开",
            )
        }
        if (!canChoose) {
            return ConflictResolverChoiceResult.ReadOnly(currentReadOnlyReason())
        }
        require(model.versions.any { it.versionId == versionId }) {
            "不是当前快照的版本: $versionId"
        }
        val nextChoices = model.paths.associate { path ->
            val matching = path.options.firstOrNull { versionId in it.sourceVersionIds }
                ?: path.options.first()
            path.path to matching.choiceId
        }
        return ConflictResolverChoiceResult.Selected(
            copyDraft(selectedChoiceIds = nextChoices, selectedVersionId = versionId),
        )
    }

    fun chooseVersion(versionId: String): ConflictResolverDraft = when (
        val result = selectVersion(versionId)
    ) {
        is ConflictResolverChoiceResult.Selected -> result.draft
        is ConflictResolverChoiceResult.ReadOnly -> throw IllegalArgumentException(result.reason)
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
        get() = complete && !emptyBranchTombstone && when {
            submitted -> currentAvailability() in setOf(
                ConflictResolverAvailability.Current,
                ConflictResolverAvailability.Expired,
            )
            else -> currentAvailability() == ConflictResolverAvailability.Current
        }

    private val emptyBranchTombstone: Boolean
        get() = model.branchVersionIds.isEmpty() &&
            model.versions.any { it.deleted }

    fun freeze(): ConflictResolverDraft {
        require(canSubmit) { currentReadOnlyReason() }
        return copyDraft(submitted = true)
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

    fun withdrawCommand(): ConflictWithdrawRequest {
        require(canWithdraw) { "当前身份不能撤回冲突分支" }
        return ConflictWithdrawRequest(
            withdrawalMutationId = withdrawalMutationId,
            expectedStableVersionId = model.stableVersionId,
            expectedBranchVersionIds = model.branchVersionIds,
        ).also { request ->
            ConflictSnapshotValidation.requireUuid(
                request.withdrawalMutationId,
                "resolver withdraw mutation ID",
            )
        }
    }

    fun savedState(
        requiresRefresh: Boolean = false,
        terminalDisposition: ConflictResolverTerminalDisposition? = null,
    ): ConflictResolverSavedState = ConflictResolverSavedState(
        conflictId = model.conflictId,
        snapshotToken = model.snapshotToken,
        resolutionMutationId = resolutionMutationId,
        selectedChoiceIds = selectedChoiceIds.toSortedMap(),
        submitted = submitted,
        requiresRefresh = requiresRefresh,
        terminalDisposition = terminalDisposition,
        selectedVersionId = selectedVersionId,
    )

    private fun copyDraft(
        selectedChoiceIds: Map<String, String> = this.selectedChoiceIds,
        selectedVersionId: String? = this.selectedVersionId,
        submitted: Boolean = this.submitted,
    ) = ConflictResolverDraft(
        model = model,
        resolutionMutationId = resolutionMutationId,
        withdrawalMutationId = withdrawalMutationId,
        selectedChoiceIds = selectedChoiceIds,
        selectedVersionId = selectedVersionId,
        submitted = submitted,
        clock = clock,
    )

    private fun versionIdMatching(choices: Map<String, String>): String? {
        if (choices.isEmpty()) return null
        return model.versions.firstOrNull { version ->
            model.paths.all { path ->
                val selected = choices[path.path] ?: return@all false
                path.options.any { option ->
                    option.choiceId == selected && version.versionId in option.sourceVersionIds
                }
            }
        }?.versionId
    }

    private fun currentAvailability(): ConflictResolverAvailability =
        if (model.availability == ConflictResolverAvailability.Current && clock() >= model.expiresAt) {
            ConflictResolverAvailability.Expired
        } else {
            model.availability
        }

    private fun isRestoreClassOption(field: ConflictResolverPath, choiceId: String): Boolean {
        if (field.path != "/_mutation.deleted") return false
        val option = field.options.singleOrNull { it.choiceId == choiceId } ?: return false
        return option.sourceVersionIds.none { id ->
            model.versions.any { it.versionId == id }
        }
    }

    private fun currentReadOnlyReason(): String = when {
        emptyBranchTombstone -> "已删除且没有冲突分支，不是未解决同步冲突"
        else -> when (currentAvailability()) {
            ConflictResolverAvailability.Current -> "请先点选要采用的一版"
            ConflictResolverAvailability.Expired -> "冲突快照已过期，请联网刷新"
            ConflictResolverAvailability.Offline -> "离线快照只读，请联网后重新打开"
            ConflictResolverAvailability.Forbidden -> "当前身份不能解决这条冲突"
        }
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
            actorNames: Map<String, String> = emptyMap(),
            withdrawalMutationId: String = resolutionMutationId,
        ): ConflictResolverDraft {
            ConflictSnapshotValidation.requireUuid(
                resolutionMutationId,
                "resolution mutation ID",
            )
            ConflictSnapshotValidation.requireUuid(
                withdrawalMutationId,
                "withdrawal mutation ID",
            )
            val model = snapshot.toResolverModel(audience, fetchedOnline, nowMillis, actorNames)
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
                    ) && (
                        saved.selectedVersionId == null ||
                            model.versions.any { it.versionId == saved.selectedVersionId }
                    )
            }
            val opened = ConflictResolverDraft(
                model = model,
                resolutionMutationId = validRestore?.resolutionMutationId ?: resolutionMutationId,
                withdrawalMutationId = withdrawalMutationId,
                selectedChoiceIds = validRestore?.selectedChoiceIds.orEmpty(),
                selectedVersionId = validRestore?.selectedVersionId,
                submitted = validRestore?.submitted ?: false,
                clock = clock,
            )
            if (opened.submitted || opened.selectedChoiceIds.isNotEmpty()) {
                val inferred = opened.selectedVersionId
                    ?: opened.versionIdMatching(opened.selectedChoiceIds)
                return if (inferred != null && inferred != opened.selectedVersionId) {
                    opened.copyDraft(selectedVersionId = inferred)
                } else {
                    opened
                }
            }
            val defaultVersionId = model.versions.firstOrNull {
                it.role == ConflictVersionRole.Stable
            }?.versionId ?: return opened
            return when (val selected = opened.selectVersion(defaultVersionId)) {
                is ConflictResolverChoiceResult.Selected -> selected.draft
                is ConflictResolverChoiceResult.ReadOnly -> opened
            }
        }
    }
}

private fun ConflictSnapshot.toResolverModel(
    audience: ConflictResolverAudience,
    fetchedOnline: Boolean,
    nowMillis: Long,
    actorNames: Map<String, String>,
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
                    provenance = candidate.sources.presentationProvenance(actorNames),
                    sourceVersionIds = candidate.sources.mapTo(linkedSetOf()) { it.versionId },
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

    val liveConcurrentFork = branches.isNotEmpty() && !stable.deleted
    val authorized = audience.isOwner || (
        entityType != ConflictRootType.Baby &&
            stable.root.authorMembershipId() == audience.membershipId &&
            !liveConcurrentFork
    )
    require(complete && pageIndex == 0 && continuation == null) {
        "resolver 只接受完整 ConflictSnapshot"
    }
    val availability = when {
        nowMillis >= expiresAt -> ConflictResolverAvailability.Expired
        !fetchedOnline -> ConflictResolverAvailability.Offline
        !authorized -> ConflictResolverAvailability.Forbidden
        else -> ConflictResolverAvailability.Current
    }
    val readOnlyReason = when (availability) {
        ConflictResolverAvailability.Current -> null
        ConflictResolverAvailability.Expired -> "冲突快照已过期，请联网刷新"
        ConflictResolverAvailability.Offline -> "离线快照只读，请联网后重新打开"
        ConflictResolverAvailability.Forbidden -> when {
            entityType == ConflictRootType.Baby -> "仅家庭管理员可以解决宝宝资料冲突"
            liveConcurrentFork -> "分叉后只有家庭管理员能采用新稳定"
            else -> "仅事实作者或家庭管理员可以解决"
        }
    }
    val entityLabel = entityType.presentationLabel()
    val hasOwnBranch = branches.any { it.actorId == audience.membershipId }
    val canWithdrawBranches = fetchedOnline &&
        nowMillis < expiresAt &&
        branches.isNotEmpty() &&
        (audience.isOwner || (hasOwnBranch && entityType != ConflictRootType.Baby))
    return ConflictResolverModel(
        conflictId = conflictId,
        entityType = entityType,
        entityLabel = entityLabel,
        clientUuid = clientUuid,
        snapshotToken = snapshotToken,
        expiresAt = expiresAt,
        stableVersionId = stable.versionId,
        branchVersionIds = branches.map { it.versionId }.sorted(),
        paths = paths,
        versions = buildVersionCards(entityLabel, actorNames),
        autoMerged = autoMerged.sortedBy { it.path }.map { merged ->
            ConflictResolverAutoOutcome(
                path = merged.path,
                label = fieldLabels.label(merged.path),
                value = merged.outcome.presentationValue(merged.path, stable.deleted),
                provenance = merged.sources.presentationProvenance(actorNames),
            )
        },
        availability = availability,
        readOnlyReason = readOnlyReason,
        canWithdrawBranches = canWithdrawBranches,
        isFamilyAdmin = audience.isOwner,
        conflictingFieldLabels = paths.map { it.label }.distinct(),
    )
}

private fun ConflictSnapshot.buildVersionCards(
    entityLabel: String,
    actorNames: Map<String, String>,
): List<ConflictResolverVersion> = buildList {
    add(stable.toPresentation(ConflictVersionRole.Stable, actorNames, entityLabel, clientUuid))
    branches.forEach { branch ->
        add(branch.toPresentation(ConflictVersionRole.Branch, actorNames, entityLabel, clientUuid))
    }
}

private fun ConflictVersionSnapshot.toPresentation(
    role: ConflictVersionRole,
    actorNames: Map<String, String>,
    entityLabel: String,
    clientUuid: String,
): ConflictResolverVersion {
    val kind = if (deleted) {
        ConflictResolverVersionKind.Deleted
    } else {
        ConflictResolverVersionKind.Live
    }
    val actor = actorDisplayName(actorId, actorNames)
    val whenLabel = formatConflictTime(root.eventTimeMillis())
    return ConflictResolverVersion(
        versionId = versionId,
        role = role,
        kind = kind,
        label = cardLabel(role, kind),
        title = root.productTitle(clientUuid),
        summary = root.productSummary(),
        actorLabel = actor,
        whenLabel = whenLabel,
        consequence = kind.consequence(entityLabel),
        deleted = kind == ConflictResolverVersionKind.Deleted,
        media = media,
        provenance = actor,
    )
}

private fun ConflictRoot.eventTimeMillis(): Long = when (this) {
    is ConflictRoot.Record -> timestamp
    is ConflictRoot.CarePlan -> scheduledAt
    is ConflictRoot.WakeObservation -> wakeTimestamp
    is ConflictRoot.Baby, is ConflictRoot.CustomItem -> 0L
}

private fun List<ConflictSource>.presentationProvenance(
    actorNames: Map<String, String> = emptyMap(),
): String = joinToString(separator = "；") { source ->
    actorDisplayName(source.actorId, actorNames)
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

private fun productDifferenceLabels(paths: List<ConflictResolverPath>): List<String> {
    val labels = linkedSetOf<String>()
    paths.forEach { path ->
        productDifferenceLabel(path.path)?.let(labels::add)
    }
    return labels.toList()
}

private fun productDifferenceLabel(path: String): String? = when {
    path == "/note" -> "备注"
    path == "/timestamp" -> "发生时间"
    path == "/end_timestamp" -> "结束时间"
    path == "/scheduled_at" -> "计划时间"
    path == "/wake_timestamp" -> "醒来时间"
    path == "/nickname" -> "宝宝昵称"
    path == "/sex" -> "性别"
    path == "/birthday" -> "出生日期"
    path == "/avatar_media_uuid" -> "头像"
    path == "/name" -> "项目名称"
    path == "/icon_slot" -> "项目图标"
    path == "/type" -> "类型"
    path == "/baby_client_uuid" -> "宝宝"
    path == "/custom_item_client_uuid" -> "自定义项目"
    path == "/_mutation.deleted" -> "删除状态"
    path == "/status" -> "履行状态"
    path == "/withdrawn" -> "撤回状态"
    path.startsWith("/media/") -> "照片"
    path == "/payload_json/amount_ml" -> "奶量"
    else -> null
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
