# 04 · APK download outside sessionMutex

Status: ready-for-agent

## Findings

- `sync/src/main/kotlin/com/lezi/babylog/sync/backend/RefreshingSyncBackend.kt:135-136,166-197`:
  `authenticated()` 用 `sessionMutex.withLock` 包住刷新+操作+401 重试+`saveSession`;
  `downloadAppUpdateApk`(100MB 上限、read 120s/次,慢 trickle 可无限占锁)也在其中
  → 所有已认证请求排队,连锁锁死家庭功能。

## Fix

- `downloadAppUpdateApk` 移出 `sessionMutex`:锁内仅取/刷新 token,出锁后执行下载;
  401 整流重试不适用于大文件流,下载失败按既有错误语义上报。
- 其余操作维持现状;不改 `appUpdateInstallMutex` 语义。

## Validation

- `./gradlew :sync:test`;既有 401 刷新测试保持绿。
