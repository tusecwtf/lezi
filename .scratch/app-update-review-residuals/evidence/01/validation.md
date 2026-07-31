# Ticket 01 · 安装/校验失败不污染同步状态

- Date: 2026-07-31 (Asia/Shanghai)
- Tracker: `.scratch/app-update-review-residuals`
- Ticket: `issues/01-install-failure-not-sync-error.md`
- Validation base (pinned): `ecd5f9b1762011cdd1fab347189464e2ba80fa44`
- Worktree changes (uncommitted at validation; Commit phase owns the atomic commit):
  - `sync/src/main/kotlin/com/lezi/babylog/sync/RealSyncPort.kt`
  - `sync/src/test/kotlin/com/lezi/babylog/sync/RealSyncPortTest.kt`
  - `feature/family/src/main/kotlin/com/lezi/babylog/feature/family/FamilyViewModel.kt`
  - `feature/settings/src/test/kotlin/com/lezi/babylog/feature/settings/AppUpdateCheckCopyTest.kt`

## Disposition

Update install/check/handshake paths must not present as family sync/NAS failure:

1. **`installAvailableAppUpdate`** no longer calls `onFailure(::updateFailureStatus)`.
2. **Install foreground gate** throws `ForegroundSyncBlockedException` without writing
   `currentStatus` (mirrors `checkAppUpdate`; does not call `requireAllowed`).
3. **Family optional install UI** maps failures with `productUiError(..., "下载或安装失败，请稍后重试")`
   — same as Settings / forced install — not `familySyncError` (which rewrites technical
   network errors to 「家庭同步服务暂未连接」).
4. **UI** still owns failure via `appUpdateInstallUiOutcome` → 「更新失败」 dialog.

## Automated gates

```bash
./gradlew :sync:testDebugUnitTest \
  --tests "com.lezi.babylog.sync.RealSyncPortTest.installAvailableAppUpdate*" \
  --tests "com.lezi.babylog.sync.RealSyncPortTest.optionalAppUpdateCheckFailureDoesNotPoisonSyncStatus"

./gradlew :feature:settings:testDebugUnitTest \
  --tests "com.lezi.babylog.feature.settings.AppUpdateCheckCopyTest"
```

Covered cases:

| Case | Assertion |
|------|-----------|
| sha256 mismatch | `SyncStatus.Idle`, no installer, staging cleared |
| download failure (`downloadAppUpdateApkFailure`) | `SyncStatus.Idle`, no installer, staging cleared |
| PackageInstaller throw | `SyncStatus.Idle`, staging cleared |
| foreground gate blocked | `SyncStatus.Idle`, no download/install |
| check + handshake piggyback failure | `SyncStatus.Idle` (existing) |
| install technical network copy | product fallback, not 「家庭同步…」 |

## Out of scope / deferred

- Shared Family/Settings optional-update VM orchestration extract (nit; copy policy now aligned).
- Hard-coded `com.lezi.babylog` package gate — ticket **05** identity verify supersedes.
