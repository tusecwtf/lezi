# Ticket 04 · client_update_required 后强制 UI 兜底

- Date: 2026-07-31 (Asia/Shanghai)
- Tracker: `.scratch/app-update-review-residuals`
- Ticket: `issues/04-force-shell-when-metadata-missing.md`
- Validation base (pinned): `a86a904c61b75ebb4e0d8f0d6f13180aa22b33c9`
- Worktree changes (uncommitted at validation; Commit phase owns the atomic commit)

## Disposition

After server `client_update_required`:

1. **Metadata OK + local &lt; min** → `ForcedAppUpdateState.WithPackage` (installable force UI).
2. **Metadata failure or non-forced classification** → `ForcedAppUpdateState.PackageUnknown` force shell +「重试检查更新」; preserve prior `WithPackage` when present.
3. **`SyncStatus`** stays `Idle` (not generic NAS/sync `Error`).
4. **Root overlay** draws above main scaffold **and onboarding**; `BackHandler` consumes system back; `clickable` sink blocks taps under the mask; no「稍后」.
5. **Settings/family** secondary dialogs map CUR failures to `AppUpdateUiOutcome.ForcedUpdatePackageUnknown` (non-dismissible retry), aligned with root shell; both use `checkAppUpdate()` + `checkingAppUpdate` busy UI.
6. **CUR policy single owner** in `RealSyncPort.resolveForceShellAfterClientUpdateRequired` (sync handle + checkAppUpdate recover).

## Automated gates

```bash
./gradlew :sync:testDebugUnitTest \
  --tests "com.lezi.babylog.sync.RealSyncPortTest.syncClientUpdateRequired*" \
  --tests "com.lezi.babylog.sync.RealSyncPortTest.checkAppUpdateClientUpdateRequired*" \
  --tests "com.lezi.babylog.sync.RealSyncPortTest.checkAppUpdateReturnsForced*" \
  --tests "com.lezi.babylog.sync.RealSyncPortTest.forcedUpdateTakesPrecedence*" \
  --tests "com.lezi.babylog.sync.RealSyncPortTest.foregroundSyncDiscoversForced*"

./gradlew :feature:settings:testDebugUnitTest \
  --tests "com.lezi.babylog.feature.settings.AppUpdateCheckCopyTest"

./gradlew :app:compileDebugKotlin
```

Covered cases:

| Case | Assertion |
|------|-----------|
| CUR + metadata OK | `WithPackage`, Idle, not optional |
| CUR + metadata 500 | `PackageUnknown`, Idle, not silent null force |
| checkAppUpdate + wire CUR on metadata | `PackageUnknown`, status stays Idle |
| CUR + metadata classifies optional/up-to-date | keep `PackageUnknown` (no demote to silent Idle) |
| PackageUnknown copy | family-server / 重试 wording; not generic network |

## UI / interaction notes (no device matrix this round)

- Full-screen force overlay: `testTag("forced_app_update_overlay")`, contentDescription「强制更新乐记」.
- `BackHandler(enabled = true) { }` — system back does not leave the shell.
- Surface `clickable` sink — taps do not reach Scaffold/onboarding underneath.
- `PackageUnknown` primary action:「重试检查更新」→ `checkAppUpdate`.
- Install-permission secondary action uses typed `forcedUpdateNeedsInstallPermission` (not message string match).

Device/emulator live raise of `min_supported` + metadata 500 is operator maintenance; semantics locked in PRD §4.2 / sync-trusted-endpoint §7.3–7.4 and unit tests above.
