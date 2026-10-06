# 01: 单请求超时以失败离开，不再杀死同步消费者（P0-D）

**What to build:** 一次 pull/commit/handshake 把 elapsed 预算耗尽后，同步以「响应超时」失败
收场，之后回前台、本地写、心跳踢仍会触发新一轮；应用不再出现「悄悄再也不自动同步」。

**Blocked by:** None (can start immediately)

**Status:** done

- [x] `SyncRetryPolicy.execute` 预算耗尽 / 末次尝试的 `TimeoutCancellationException` 换成
      `SyncRetryBudgetExceededException`；外部真取消仍原样透传（既有
      `RetryingSyncBackendTest` 的 outer-timeout 用例不回退）
- [x] `syncInternal` 只把「非超时的 `CancellationException`」当控制流 rethrow
- [x] `syncSignal` 消费者对任何非取消异常兜底并继续循环
- [x] Foreground 轮改用 `withElapsedBudget` 语义，残余 `TimeoutCancellationException` 变
      `FamilyHttpException(SyncTookTooLong)`
- [x] 信号循环级测试：假后端让 Pull 挂满预算 → 失败分类 `ResponseTimedOut`、
      `SyncStatus.Error`；随后 `requestSync(LocalWrite)` 仍产生 commit 请求
- [x] `layers/sync.md` §6 补「预算耗尽以类型化失败离开 retry」；CONTEXT「待发布」`Avoid`
      追加「以取消形态逃逸的超时」

## 证据

- `SyncRetryPolicy.kt:171-178`、`retryOrThrow` 末尾 `throw original`
- `RealSyncPort.kt:1933-1938`、`:505-518`（`processScope = SupervisorJob`，无 catch）
- 临时探针（已删）：假时钟 Pull 挂满 60 s，`execute` 抛出类型为
  `kotlinx.coroutines.TimeoutCancellationException`

## Comments

预算耗尽 / 末次尝试的 `TimeoutCancellationException` 现换成 `SyncRetryBudgetExceededException`
（`ResponseTimedOut`）；`syncInternal` 只把非超时取消当控制流；`syncSignal` 消费者对类型化
失败兜底续跑；Foreground 轮改 `withElapsedBudget`。LocalWrite 10 分钟上限与后台
`Syncing → Idle` 留给 04。

Ran: `./gradlew :sync:testDebugUnitTest --tests …RetryingSyncBackendTest --tests …RealSyncPortForegroundCycleTest --tests …FamilySyncErrorProductCopyTest` — 18 + 3 + 12 passed, including new hang / delay-exceeds-budget type-swap tests and `requestSyncForegroundTimeoutLeavesConsumerAliveForLocalWrite`.
