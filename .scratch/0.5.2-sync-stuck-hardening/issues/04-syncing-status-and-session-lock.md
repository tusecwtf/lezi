# 04: `Syncing` 回落、心跳不永久停、sessionMutex 收窄、LocalWrite 兜底上限（P1-C / P1-L）

**What to build:** 没有轮在跑时浅状态不停留在「正在同步」；心跳等待有上限；家人点名册/批准
不必等我的照片传完；LocalWrite 轮有一个只防僵死的 10 分钟上限。

**Blocked by:** None (can start immediately)

**Status:** done

- [x] 消费者后台 `continue` 前 `tryLock` 成功且 `Syncing` 才置 `Idle`
- [x] `updateFailureStatus` 对 `ForegroundSyncBlockedException` 在 `Syncing` 时落 `Idle`
- [x] 心跳循环 `first { it != Syncing }` 加上限（前台整轮上限 + LocalWrite 兜底上限），到期
      重新推导
- [x] `RefreshingSyncBackend.authenticated`：`sessionMutex` 只包凭证读取/刷新，业务请求在锁外；
      401 后刷新重试仍 exactly once
- [x] LocalWrite 轮包 `ElapsedBudgetContext(SyncTookTooLong, 10 min)`；单请求预算不变
- [x] 信号循环级测试：续跑信号到达时前台为 false → `status` 不停留 `Syncing`；心跳在假时钟
      推进上限后继续拍
- [x] `RefreshingSyncBackend` 测试：慢业务请求持有期间另一认证请求不等待其完成
- [x] `layers/sync.md` §7 加 LocalWrite 10 分钟兜底

## 证据

- `RealSyncPort.kt:507`、`:1946-1950`、`:3038`、`:1094-1098`、`:2621`
- `RefreshingSyncBackend.kt:211-242`
- 原「tip-skip 让 `Syncing` 永久残留」不成立：`foregroundRoundSkipVeto` 在 `continue` 前不被
  消费（`:507` 早于 `:515`），下次回前台必跑真轮

## Comments

后台 `continue` 前若浅状态仍是 `Syncing` 则落 `Idle`；`ForegroundSyncBlockedException` 在
`Syncing` 时同样落 `Idle`（`checkAppUpdate` 仍只回 Result、不改 SyncStatus）。心跳
`first { != Syncing }` 包 `120s + 10min` 上限，到期 `continue` 再推导，不杀循环。
`authenticated` 与 APK 下载共用锁外业务请求：mutex 只包 token 读/刷新，401 刷新重试
exactly once。LocalWrite 整轮包 `LOCAL_WRITE_MAX_ELAPSED_MILLIS`（10 min），前台 120s
与 MediaPrepare 240s 不动。`layers/sync.md` §7 加 LocalWrite 行。
Review 收敛：后台 `continue` 与后台续跑落 `Idle` 都用 `tryLock`，避免
`isLocked` 快照与写状态之间误伤仍在跑的 `sync()` / `syncWhenAvailable`。

Ran: `./gradlew :sync:testDebugUnitTest --tests …RefreshingSyncBackendTest --tests …RealSyncPortHeartbeatLoopTest --tests …RealSyncPortForegroundCycleTest --tests …RealSyncPortLocalWriteNoPullTest --tests …RetryingSyncBackendTest.refreshing401OwnerRemainsExactlyOneRefreshAndTwoOriginalRequests` — 19 + 27 + 4 + 7 + 1 passed, including `backgroundContinuationSignalDoesNotLeaveStatusSyncing`、`syncingWaitCapExpiresWithoutKillingTheLoopAndABeatStillFiresAfterTheRound`、`slowAuthenticatedPullDoesNotBlockOtherAuthenticatedRequests`；既有 `unexpectedAccess401RefreshesAndRetriesTheOriginalRequestExactlyOnce`、`refreshing401OwnerRemainsExactlyOneRefreshAndTwoOriginalRequests`、`syncingRoundSkipsDueBeatsWithoutQueueingABurstAfterwards` 仍绿。
