# Ticket 02 · 更新安装串行且全程 IO

- Date: 2026-07-31 (Asia/Shanghai)
- Tracker: `.scratch/app-update-review-residuals`
- Ticket: `issues/02-serialize-install-on-io.md`
- Validation base (pinned): `c89c5dad34697bc5c8c197428c492316c26c67d0`
- Worktree changes (uncommitted at validation; Commit phase owns the atomic commit):
  - `sync/src/main/kotlin/com/lezi/babylog/sync/RealSyncPort.kt`
  - `sync/src/main/kotlin/com/lezi/babylog/sync/SyncPort.kt`
  - `sync/src/main/kotlin/com/lezi/babylog/sync/AppUpdateUiCopy.kt`
  - `sync/src/test/kotlin/com/lezi/babylog/sync/RealSyncPortTest.kt`
  - `feature/settings/src/test/kotlin/com/lezi/babylog/feature/settings/AppUpdateCheckCopyTest.kt`

## Disposition

1. **`appUpdateInstallMutex`** serializes install + staging cleanup via `withAppUpdateInstallLockOrElse`.
2. **Busy reject**: concurrent install → `AppUpdateInstallInProgressException` (typed; product copy constants).
3. **Dismiss after lock**: `dismissOptionalAppUpdate` only runs after mutex is held so busy callers do not mutate banner state.
4. **IO**: download + sha256 + staging write + `installFromFile` under `withContext(Dispatchers.IO)`.
5. **UI**: `appUpdateInstallUiOutcome` maps busy to title「更新进行中」not「更新失败」.

## Automated gates

```bash
./gradlew :sync:testDebugUnitTest \
  --tests "com.lezi.babylog.sync.RealSyncPortTest.installAvailableAppUpdate*" \
  --tests "com.lezi.babylog.sync.RealSyncPortTest.checkAppUpdate*"

./gradlew :feature:settings:testDebugUnitTest \
  --tests "com.lezi.babylog.feature.settings.AppUpdateCheckCopyTest"
```

Covered cases:

| Case | Assertion |
|------|-----------|
| concurrent second install | `AppUpdateInstallInProgressException`, one download, no second installer |
| busy reject + optional banner | version-8 banner survives busy install of version 7 |
| mid-install check/cleanup | partial staging survives; cleared after install |
| busy UI mapping | title「更新进行中」+ product body constant |

## Out of scope / deferred

- Shared Family/Settings/MainActivity install-busy launcher or `isAppUpdateInstallInProgress` Flow
  (AC met by SyncPort reject; local UI flags remain per surface).
