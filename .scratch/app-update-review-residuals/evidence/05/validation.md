# Ticket 05 · 装前校验 APK 包名/版本/签名

- Date: 2026-07-31 (Asia/Shanghai)
- Tracker: `.scratch/app-update-review-residuals`
- Ticket: `issues/05-verify-apk-identity-before-install.md`
- Validation base (pinned): `221edfe08f9b783fdafaa07b59538cfb82077761`
- Worktree changes (uncommitted at validation; Commit phase owns the atomic commit):
  - `sync/src/main/kotlin/com/lezi/babylog/sync/AppUpdateApkIdentity.kt` (new)
  - `sync/src/main/kotlin/com/lezi/babylog/sync/RealSyncPort.kt`
  - `sync/src/main/kotlin/com/lezi/babylog/sync/SyncModule.kt`
  - `sync/src/main/kotlin/com/lezi/babylog/sync/SyncPort.kt`
  - `sync/src/test/kotlin/com/lezi/babylog/sync/AppUpdateApkIdentityTest.kt` (new)
  - `sync/src/test/kotlin/com/lezi/babylog/sync/RealSyncPortTest.kt`
  - `feature/settings/src/test/kotlin/com/lezi/babylog/feature/settings/AppUpdateCheckCopyTest.kt`

## Disposition

1. **`AppUpdateApkIdentityReader`** platform seam + pure **`verifyStagedApkIdentity`**:
   archive packageName == local applicationId, == metadata packageName;
   archive versionCode == metadata and > local; signing cert digests intersect when known.
2. **`installAvailableAppUpdate`**: after sha256 write, before PackageInstaller commit —
   reject + cleanup private staging; never commit foreign package.
3. **Metadata classify**: `packageName != clientAppVersion.packageName` rejects with
   `APP_UPDATE_METADATA_PACKAGE_MISMATCH_MESSAGE` (no hard-coded-only install check).
4. **UI**: Chinese identity failure passes `productUiError` → 「更新失败」+「更新包无效或不匹配，已取消安装」.
5. Optional package-nas aapt/apksigner check **not** done (ticket optional).
6. Fix round 1: fail-closed empty installed certs; `verifyStagedApkIdentity(local, metadata)`;
   shared `PackageManagerCompat`; KDoc/PRD pipeline; package-mismatch helper; extra install
   tests for version mismatch + unreadable archive.

## Automated gates

```bash
./gradlew :sync:testDebugUnitTest \
  --tests "com.lezi.babylog.sync.AppUpdateApkIdentityTest" \
  --tests "com.lezi.babylog.sync.RealSyncPortTest.installAvailableAppUpdate*" \
  --tests "com.lezi.babylog.sync.RealSyncPortTest.checkAppUpdateRejectsMetadataPackageNotEqualLocalApplicationId" \
  --tests "com.lezi.babylog.sync.RealSyncPortTest.checkAppUpdate*"

./gradlew :feature:settings:testDebugUnitTest \
  --tests "com.lezi.babylog.feature.settings.AppUpdateCheckCopyTest"
```

| Case | Assertion |
|------|-----------|
| pure package mismatch | `APP_UPDATE_PACKAGE_INVALID_MESSAGE` |
| archive packageName ≠ local | no installer, staging cleared, SyncStatus Idle |
| metadata packageName ≠ local (install) | no download |
| metadata packageName ≠ local (check) | no optional/forced publish |
| signing cert mismatch | no installer, staging cleared |
| happy path install | SessionStarted after identity ok |

## Result

Focused gates: **BUILD SUCCESSFUL**.
