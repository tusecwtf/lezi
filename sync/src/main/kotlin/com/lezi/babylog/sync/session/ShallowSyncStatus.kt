package com.lezi.babylog.sync.session

import com.lezi.babylog.core.model.SyncStatus
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
)

data class ShallowSyncFacts(
    val transportStatus: SyncStatus,
    val joined: Boolean,
    val retainedFamilyIdentity: Boolean,
    val waitingForAdmin: Boolean,
    val pendingPublishCount: Int,
    val lastSuccessAtMillis: Long?,
    val nowMillis: Long,
)

fun projectShallowSyncLine(facts: ShallowSyncFacts): ShallowSyncLine {
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
                "暂时无法同步 · 待同步 $pendingCount 项 · 下拉重试"
            } else {
                "暂时无法同步 · 下拉重试"
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
    nowMillis: () -> Long = System::currentTimeMillis,
    clockTicks: Flow<Unit> = processShallowSyncTicks,
): Flow<ShallowSyncLine> = combine(
    transportStatus,
    session,
    pendingMemberLogin,
    pendingPublishCount,
    clockTicks,
) { status, currentSession, pendingLogin, publishCount, _ ->
    projectShallowSyncLine(
        ShallowSyncFacts(
            transportStatus = status,
            joined = currentSession.isJoined,
            retainedFamilyIdentity = currentSession.reauthRequired &&
                currentSession.familyId.isNotBlank(),
            waitingForAdmin = pendingLogin != null,
            pendingPublishCount = publishCount,
            lastSuccessAtMillis = currentSession.lastSuccessAt,
            nowMillis = nowMillis(),
        ),
    )
}.distinctUntilChanged()

private fun relativeSyncTime(lastSuccessAtMillis: Long, nowMillis: Long): String {
    val elapsed = (nowMillis - lastSuccessAtMillis).coerceAtLeast(0L)
    return when {
        elapsed < 60_000L -> "刚刚"
        elapsed < 3_600_000L -> "${elapsed / 60_000L} 分钟前"
        elapsed < 86_400_000L -> "${elapsed / 3_600_000L} 小时前"
        else -> "${elapsed / 86_400_000L} 天前"
    }
}
