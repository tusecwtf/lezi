# 09 · Sync UX hygiene

Status: ready-for-agent

## Findings

1. `sync/.../RealSyncPort.kt:1100`:`runCatching { syncMutex.withLock { ... } }` 吞
   `CancellationException`,页面销毁取消被当失败 → 全局 `SyncStatus.Error` 误报。
2. `sync/.../RealSyncPort.kt:155-157,956`:`memberLoginCheckEvents`(buffer=1, SUSPEND)
   慢订阅者会让 `checkMemberLogin()`(前台 sync 路径调用)无限挂起。
3. `feature/growth/.../GrowthScreen.kt:291`、`feature/summary/.../SummaryScreen.kt:288`:
   下拉刷新 spinner 绑全局 `SyncStatus.Syncing`,本地写触发的后台同步也转圈。
   (与 family-sync-hang tracker 票 09 浅状态重叠,本票只做触发源最小修复。)
4. `domain/.../family/FamilyWizardController.kt:973-990`:`checkMemberApproval` 无
   `withTimeout`,与同文件 948 行 `submitMemberRequest` 的 20s 不一致。

## Fix

1. 显式捕获并 rethrow `CancellationException`(对齐项目其余处写法)。
2. `memberLoginCheckEvents` 改 `onBufferOverflow = DROP_OLDEST`(或 `tryEmit`)。
3. spinner 只在 `PullToRefresh` 触发的同步期间转圈(自有 refreshing 标志或 trigger
   过滤,取改动小者)。
4. `checkMemberApproval` 补 20s `withTimeout`。

## Validation

- `./gradlew :sync:test :domain:test`;feature 改动随 `lintDebug` 核对。
