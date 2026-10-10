package com.lezi.babylog.sync.session

import com.lezi.babylog.core.model.SyncStatus
import com.lezi.babylog.core.database.causal.PullDiagnosticReceipt
import com.lezi.babylog.sync.PendingMemberLogin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

private const val SHALLOW_SYNC_TICK_MILLIS = 60_000L

private val processShallowSyncTicks: Flow<Unit> = shallowSyncMinuteTicks()
    .shareIn(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000L),
        replay = 1,
    )

internal fun shallowSyncMinuteTicks(): Flow<Unit> = flow {
    emit(Unit)
    while (true) {
        delay(SHALLOW_SYNC_TICK_MILLIS)
        emit(Unit)
    }
}

enum class ShallowSyncState {
    Unjoined,
    Pending,
    Synced,
    Syncing,
    Error,
    ReauthRequired,
    WaitingForAdmin,
    WaitingForFirstSync,
}

data class ShallowSyncLine(
    val state: ShallowSyncState,
    val text: String,
    val pendingCount: Int = 0,
    val unacceptedEntityType: String? = null,
    val unacceptedClientUuid: String? = null,
    /** Deferred-pull aggregate; warning copy only, never a [state] downgrade. */
    val skippedCount: Int = 0,
    /** 「N 项未收下」 when [skippedCount] > 0, otherwise null. */
    val skippedWarningText: String? = null,
    val skippedItems: List<SkippedPullItem> = emptyList(),
) {
    /** Error chrome for transport failure or expired login; not skip-warning. */
    val isError: Boolean
        get() = state == ShallowSyncState.Error || state == ShallowSyncState.ReauthRequired
}

data class UnacceptedFactPresentation(
    val entityType: String,
    val clientUuid: String,
    val recordedAt: Long,
)

data class BadGroupPresentation(
    val entityType: String,
    val clientUuid: String,
    val recordedAt: Long,
)

/**
 * One family fact the household has not accepted yet, projected for the 0.4.7
 * skip-visibility surfaces. [reasonGateDisplay] is 人话 derived from the
 * persisted DeferredGate name — raw enum names never reach the UI.
 */
data class SkippedPullItem(
    val entityType: String,
    val clientUuid: String,
    val recordedAt: Long,
    val reasonGateDisplay: String,
    /** Short human identity of the missing reference, e.g. 「宝宝 a1b2c3d4」. */
    val missingIdentity: String? = null,
    val code: String? = null,
)

/** Durable census-reconcile receipt code (engine writes it; this file renders it). */
const val LIVE_CENSUS_MISMATCH_CODE = "live_census_mismatch"

/** One local-live extra projected from a census mismatch (clickable inbox row). */
const val LIVE_CENSUS_LOCAL_EXTRA_CODE = "live_census_local_extra"

/** Durable census-reconcile receipt pseudo-type for the skip-visibility surfaces. */
const val LIVE_CENSUS_ENTITY_TYPE = "live_census"

fun isAggregateCensusDiagnostic(entityType: String, clientUuid: String): Boolean =
    entityType == LIVE_CENSUS_ENTITY_TYPE && clientUuid.isBlank()

fun formatEntityTypeChinese(entityType: String): String = when (entityType) {
    "record" -> "护理记录"
    "care_plan" -> "护理计划"
    "wake_observation" -> "醒来观察"
    "baby" -> "宝宝档案"
    "custom_item" -> "自定义项目"
    "media" -> "媒体"
    "family" -> "家庭档案"
    LIVE_CENSUS_ENTITY_TYPE -> "家庭活集"
    else -> "记录"
}

fun formatShortIdentity(clientUuid: String): String =
    if (clientUuid.length <= 8) clientUuid else clientUuid.take(8)

/**
 * 人话 name for a persisted DeferredGate receipt value. Grouped gates keep the
 * line short (plan_*, fulfillment_*, media parents); unknown or pre-verdict
 * receipts fall back to neutral copy instead of a raw enum name.
 */
fun deferredGateDisplayName(reasonGate: String?): String = when (reasonGate) {
    "BabyMissing" -> "宝宝缺失"
    "CustomItemMissing" -> "自定义项目缺失"
    "SleepTypeMismatch" -> "睡眠类型不符"
    "WakeRetarget" -> "醒来改绑"
    "PlanBabyMissing",
    "PlanCustomItemMissing",
    "PlanFulfilledRecordMissing",
    "PlanFulfilledRecordBabyMismatch",
    -> "计划引用不完整"
    "FulfillmentPlanMissing",
    "FulfillmentRecordMissing",
    -> "履行集合不完整"
    "MediaRecordMissing",
    "MediaCarePlanMissing",
    "MediaBabyMissing",
    "MediaWakeMissing",
    -> "媒体父实体缺失"
    "MediaEditGuard" -> "媒体编辑守卫"
    "MediaBytesUnstaged" -> "媒体字节未就绪"
    "FamilyRowMissing" -> "家庭行缺失"
    "BabyLocalDirty" -> "本机宝宝未发表"
    "CustomItemCapacityExceeded" -> "家庭自定义项目已满"
    else -> "暂时未收下"
}

/** Projects one pull diagnostic receipt onto the skip-visibility presentation. */
fun PullDiagnosticReceipt.toSkippedPullItem(): SkippedPullItem = SkippedPullItem(
    entityType = entityType,
    clientUuid = clientUuid,
    recordedAt = recordedAt,
    reasonGateDisplay = when (code) {
        LIVE_CENSUS_MISMATCH_CODE -> "家庭活集与服务器不一致"
        LIVE_CENSUS_LOCAL_EXTRA_CODE -> "本机有、家里没有"
        else -> deferredGateDisplayName(reasonGate)
    },
    missingIdentity = missingClientUuid?.takeIf { it.isNotBlank() }?.let { uuid ->
        "${missingEntityType?.let(::formatEntityTypeChinese) ?: "关联内容"} ${formatShortIdentity(uuid)}"
    },
    code = code,
)

data class ShallowSyncFacts(
    val transportStatus: SyncStatus,
    val joined: Boolean,
    val retainedFamilyIdentity: Boolean,
    val waitingForAdmin: Boolean,
    val pendingPublishCount: Int,
    val pendingGenerationResync: Boolean = false,
    val lastSuccessAtMillis: Long?,
    val nowMillis: Long,
    val unacceptedFact: UnacceptedFactPresentation? = null,
    val badGroup: BadGroupPresentation? = null,
    val skippedPullCount: Int = 0,
    val skippedPullItems: List<SkippedPullItem> = emptyList(),
)

/**
 * Projected 同步状态 line. Skip receipts (家庭事实未收下) ride along as an
 * independent warning: they never downgrade [ShallowSyncLine.state] — the 0.4.5
 * panic loop mapped pull deferrals onto Error + 「家里没收下」. The main text
 * keeps answering 「已同步 · 刚刚」 while skips are pending.
 */
fun projectShallowSyncLine(facts: ShallowSyncFacts): ShallowSyncLine {
    val base = projectMainSyncLine(facts)
    val skippedCount = facts.skippedPullCount.coerceAtLeast(0)
    if (skippedCount <= 0) {
        return base
    }
    return base.copy(
        skippedCount = skippedCount,
        skippedWarningText = "$skippedCount 项未收下",
        skippedItems = facts.skippedPullItems,
    )
}

private fun projectMainSyncLine(facts: ShallowSyncFacts): ShallowSyncLine {
    val pendingCount = facts.pendingPublishCount.coerceAtLeast(0)
    if (
        facts.transportStatus == SyncStatus.ReauthRequired ||
        (!facts.joined && facts.retainedFamilyIdentity)
    ) {
        return ShallowSyncLine(
            state = ShallowSyncState.ReauthRequired,
            text = "登录已失效 · 请重新登录或申请",
        )
    }
    if (!facts.joined && facts.waitingForAdmin) {
        return ShallowSyncLine(
            state = ShallowSyncState.WaitingForAdmin,
            text = "等待管理员确认",
        )
    }
    if (!facts.joined) {
        return ShallowSyncLine(
            state = ShallowSyncState.Unjoined,
            text = "尚未加入家庭 · 数据仅保存在本机",
        )
    }
    if (facts.joined && facts.unacceptedFact != null) {
        val typeName = formatEntityTypeChinese(facts.unacceptedFact.entityType)
        val shortId = formatShortIdentity(facts.unacceptedFact.clientUuid)
        return ShallowSyncLine(
            state = ShallowSyncState.Error,
            text = "$typeName $shortId · 家里没收下",
            pendingCount = pendingCount,
            unacceptedEntityType = facts.unacceptedFact.entityType,
            unacceptedClientUuid = facts.unacceptedFact.clientUuid,
        )
    }
    if (facts.joined && facts.badGroup != null) {
        val typeName = formatEntityTypeChinese(facts.badGroup.entityType)
        val shortId = formatShortIdentity(facts.badGroup.clientUuid)
        return ShallowSyncLine(
            state = ShallowSyncState.Error,
            text = "$typeName $shortId · 家里没收下",
            pendingCount = pendingCount,
            unacceptedEntityType = facts.badGroup.entityType,
            unacceptedClientUuid = facts.badGroup.clientUuid,
        )
    }
    if (facts.joined && facts.transportStatus == SyncStatus.Syncing) {
        return ShallowSyncLine(
            state = ShallowSyncState.Syncing,
            text = if (pendingCount > 0) {
                "正在同步 · 待同步 $pendingCount 项"
            } else {
                "正在同步"
            },
            pendingCount = pendingCount,
        )
    }
    if (facts.joined && facts.transportStatus == SyncStatus.Error) {
        return ShallowSyncLine(
            state = ShallowSyncState.Error,
            text = if (pendingCount > 0) {
                "暂时无法同步 · 待同步 $pendingCount 项 · $SHALLOW_SYNC_RETRY_HINT"
            } else {
                "暂时无法同步 · $SHALLOW_SYNC_RETRY_HINT"
            },
            pendingCount = pendingCount,
        )
    }
    if (facts.joined && pendingCount > 0) {
        return ShallowSyncLine(
            state = ShallowSyncState.Pending,
            text = "已保存在本机 · 待同步 $pendingCount 项",
            pendingCount = pendingCount,
        )
    }
    if (facts.joined && facts.pendingGenerationResync) {
        return ShallowSyncLine(
            state = ShallowSyncState.Pending,
            text = "已保存在本机 · 待同步",
        )
    }
    if (facts.joined && facts.lastSuccessAtMillis != null) {
        return ShallowSyncLine(
            state = ShallowSyncState.Synced,
            text = "已同步 · ${relativeSyncTime(facts.lastSuccessAtMillis, facts.nowMillis)}",
        )
    }
    return ShallowSyncLine(
        state = ShallowSyncState.WaitingForFirstSync,
        text = "已加入家庭 · 等待首次同步",
    )
}

fun shallowSyncLineFlow(
    transportStatus: Flow<SyncStatus>,
    session: Flow<SyncSession>,
    pendingMemberLogin: Flow<PendingMemberLogin?>,
    pendingPublishCount: Flow<Int>,
    pendingGenerationResync: Flow<Boolean> = kotlinx.coroutines.flow.flowOf(false),
    unacceptedFact: Flow<UnacceptedFactPresentation?> = kotlinx.coroutines.flow.flowOf(null),
    badGroup: Flow<BadGroupPresentation?> = kotlinx.coroutines.flow.flowOf(null),
    skippedPullItems: Flow<List<SkippedPullItem>> = kotlinx.coroutines.flow.flowOf(emptyList()),
    nowMillis: () -> Long = System::currentTimeMillis,
    clockTicks: Flow<Unit> = processShallowSyncTicks,
): Flow<ShallowSyncLine> = shallowSyncLinePresentationFlow(
    transportStatus = transportStatus,
    session = session.map(SyncSession::toPresentation),
    pendingMemberLogin = pendingMemberLogin,
    pendingPublishCount = pendingPublishCount,
    pendingGenerationResync = pendingGenerationResync,
    unacceptedFact = unacceptedFact,
    badGroup = badGroup,
    skippedPullItems = skippedPullItems,
    nowMillis = nowMillis,
    clockTicks = clockTicks,
)

/** Product status needs only the owner-coherent, credential-free read model. */
internal fun shallowSyncLinePresentationFlow(
    transportStatus: Flow<SyncStatus>,
    session: Flow<SyncSessionPresentation>,
    pendingMemberLogin: Flow<PendingMemberLogin?>,
    pendingPublishCount: Flow<Int>,
    pendingGenerationResync: Flow<Boolean> = kotlinx.coroutines.flow.flowOf(false),
    unacceptedFact: Flow<UnacceptedFactPresentation?> = kotlinx.coroutines.flow.flowOf(null),
    badGroup: Flow<BadGroupPresentation?> = kotlinx.coroutines.flow.flowOf(null),
    skippedPullItems: Flow<List<SkippedPullItem>> = kotlinx.coroutines.flow.flowOf(emptyList()),
    nowMillis: () -> Long = System::currentTimeMillis,
    clockTicks: Flow<Unit> = processShallowSyncTicks,
): Flow<ShallowSyncLine> {
    val stateFlow = combine(transportStatus, session, pendingMemberLogin) { status, s, login ->
        Triple(status, s, login)
    }
    val countFlow = combine(pendingPublishCount, pendingGenerationResync, ::Pair)
    val diagnosticsFlow = combine(unacceptedFact, badGroup, skippedPullItems, ::Triple)
    return combine(stateFlow, countFlow, diagnosticsFlow, clockTicks) { state, counts, diagnostics, _ ->
        val (status, currentSession, pendingLogin) = state
        val (publishCount, generationResync) = counts
        val (unaccepted, bad, skipped) = diagnostics
        projectShallowSyncLine(
            ShallowSyncFacts(
                transportStatus = status,
                joined = currentSession.isJoined,
                retainedFamilyIdentity = currentSession.reauthRequired &&
                    currentSession.familyId.isNotBlank(),
                waitingForAdmin = pendingLogin != null,
                pendingPublishCount = publishCount,
                pendingGenerationResync = generationResync,
                lastSuccessAtMillis = currentSession.lastSuccessAt,
                nowMillis = nowMillis(),
                unacceptedFact = unaccepted,
                badGroup = bad,
                skippedPullCount = skipped.size,
                skippedPullItems = skipped,
            ),
        )
    }.distinctUntilChanged()
}

fun relativeSyncTime(lastSuccessAtMillis: Long, nowMillis: Long): String {
    val elapsed = (nowMillis - lastSuccessAtMillis).coerceAtLeast(0L)
    return when {
        elapsed < 60_000L -> "刚刚"
        elapsed < 3_600_000L -> "${elapsed / 60_000L} 分钟前"
        elapsed < 86_400_000L -> "${elapsed / 3_600_000L} 小时前"
        else -> "${elapsed / 86_400_000L} 天前"
    }
}

/** Retry hint inside the shallow sync line; timeline has pull-to-refresh. */
const val SHALLOW_SYNC_RETRY_HINT = "下拉重试"
