package com.lezi.babylog.feature.family.conflict

import com.lezi.babylog.domain.carelog.ConflictInbox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * 冲突收件箱对外相位（0.5.4 票 08 / T1）：区分「未加载 / 加载中 / 空 / 错误 / 内容」。
 *
 * 初值 [NotLoaded] 不携带数据，也永远不允许被渲染成空态——打开收件箱的首帧是加载
 * 占位，而不是「目前没有待处理项」；[Error] 携带原地重试入口。相位只从既有 observe
 * 流 + 加载状态推导，不新增轮询或数据面。
 */
sealed interface ConflictInboxPhase {
    /** 还没有拿到第一份 observe 投影；渲染为加载占位。 */
    data object NotLoaded : ConflictInboxPhase

    /** 一次显式重试正在进行。 */
    data object Loading : ConflictInboxPhase

    /** 投影已到达，且确实没有待处理项。 */
    data object Empty : ConflictInboxPhase

    /** observe 流失败；重试会重新订阅，失败期间不显示静止的旧列表。 */
    data object Error : ConflictInboxPhase

    /** 投影已到达且有卡片。 */
    data class Content(val inbox: ConflictInbox) : ConflictInboxPhase
}

/** 纯映射：observe 投影 → 相位。「确实为空」与「未加载」在此明确分流。 */
internal fun ConflictInbox.toPhase(): ConflictInboxPhase =
    if (items.isEmpty()) ConflictInboxPhase.Empty else ConflictInboxPhase.Content(this)

/**
 * 相位状态机（仓内范本：search 页五相位）。失败后上游不再自动复活——重试是唯一
 * 路径，因此失败态是可信的，而不是一张静止的列表。
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ConflictInboxPhaseFlow(
    upstream: Flow<ConflictInbox>,
    scope: CoroutineScope,
    started: SharingStarted = SharingStarted.WhileSubscribed(5_000),
) {
    private val retryGeneration = MutableStateFlow(0)

    val phases: StateFlow<ConflictInboxPhase> = retryGeneration
        .flatMapLatest { generation -> attemptPhases(upstream, generation) }
        .stateIn(scope, started, ConflictInboxPhase.NotLoaded)

    /** 原地重试：重新订阅 observe 流；下一次尝试先进入加载相位。 */
    fun retry() {
        retryGeneration.value += 1
    }
}

private fun attemptPhases(
    upstream: Flow<ConflictInbox>,
    generation: Int,
): Flow<ConflictInboxPhase> = flow {
    // 只有显式重试（generation > 0）先宣告加载相位；首次订阅保持 NotLoaded，
    // stateIn 由此持有初值直到第一份真实投影到达——首帧不可能被渲染成空态。
    if (generation > 0) emit(ConflictInboxPhase.Loading)
    emitAll(
        upstream.map<ConflictInbox, ConflictInboxPhase>(ConflictInbox::toPhase).catch { error ->
            if (error is CancellationException) throw error
            emit(ConflictInboxPhase.Error)
        },
    )
}
