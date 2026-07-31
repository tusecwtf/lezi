# Ticket 05 · 合同写回与 E2E 验收

- Date: 2026-07-31 (Asia/Shanghai)
- Validation base HEAD: `520e6edfca265b68fe9ef148c1326dc2ab341b91` (+ this ticket’s doc/evidence edits)
- Tracker: `.scratch/self-hosted-app-update`
- Scope: PRD/runbook write-back, automated gates, packaging fail-closed/success, environment boundaries

## Disposition

Tickets 01–04 already implemented the self-hosted app-update vertical slice (authenticated
metadata/APK, optional install + no residue, minSupported force gate + fullscreen, handshake
optional banner). Ticket 05 locks the product contract into PRD/tech/sync/DEPLOY/README,
re-runs focused gates, and records packaging + environment boundaries so the tracker can close
without inventing device-only features.

## Doc write-back

| Document | What was locked |
|----------|-----------------|
| [`docs/prd/tech.md`](../../../../docs/prd/tech.md) §4.2 + permissions | Self-hosted channel ≠ Play IAU; eligibility; dual-tier; headers; APIs; PackageInstaller; private cache; fail-closed package |
| [`docs/prd/sync-trusted-endpoint.md`](../../../../docs/prd/sync-trusted-endpoint.md) §7.4 + API + failures | `X-Lezi-Client-Version-Code`; `client_update_required`; update routes not gated; fail-open without metadata |
| [`docs/prd/ui.md`](../../../../docs/prd/ui.md) §5.8 | About version line, optional confirm, force fullscreen, banner, PackageInstaller |
| [`docs/prd/README.md`](../../../../docs/prd/README.md) | In-scope self-hosted update; out-of-scope Play IAU / offline upgrade / FCM |
| [`README.md`](../../../../README.md) | `LEZI_RELEASE_APK` / `LEZI_APP_UPDATE_JSON`; package fail-closed; known limits |
| [`tools/lezi-sync/deploy/DEPLOY.md`](../../../../tools/lezi-sync/deploy/DEPLOY.md) | Env vars, minSupported guidance, no anonymous CDN, local package checks |
| Design research note | Status → Implemented; points at PRD |

Deploy metadata sample remains `tools/lezi-sync/deploy/app-update.json`
(`package_name=com.lezi.babylog`, `version_code=6`, `min_supported_version_code=1`).

## Automated gates

### lezi-sync (filter `app_update`)

```bash
cd tools/lezi-sync
cargo test --locked app_update -- --nocapture
# 7 passed (api integration):
#   app_update_metadata_requires_session_and_returns_deploy_file
#   app_update_metadata_missing_file_is_not_found_for_authenticated_session
#   app_update_apk_requires_session_and_matches_metadata_sha256
#   app_update_apk_rejects_sha256_mismatch
#   app_update_apk_missing_file_is_not_found
#   client_version_gate_fail_open_without_app_update_metadata
#   client_update_required_still_allows_authenticated_app_update_download
```

### lezi-sync (filter `client_update`)

```bash
cargo test --locked client_update -- --nocapture
# 2 passed:
#   client_update_required_rejects_pull_when_version_header_missing_or_below_min
#   client_update_required_still_allows_authenticated_app_update_download
```

### clippy

```bash
cargo clippy --all-targets --all-features -- -D warnings
# Finished ok (exit 0)
```

### Android JVM

```bash
./gradlew :sync:testDebugUnitTest \
  --tests 'com.lezi.babylog.sync.RealSyncPortTest' \
  --tests 'com.lezi.babylog.sync.HttpSyncBackendTest' \
  :feature:family:testDebugUnitTest \
  --tests 'com.lezi.babylog.feature.family.OptionalAppUpdateBannerPolicyTest'
# BUILD SUCCESSFUL
```

Covers (among other RealSyncPort cases): NotJoined / UpToDate / Optional / Forced,
handshake discover + session dismiss, install + sha256 reject + missing install permission,
`client_update_required` mapping away from vague network status; HTTP app-update GETs;
optional banner policy.

### assembleDebug

```bash
./gradlew :app:assembleDebug
# BUILD SUCCESSFUL
```

## Packaging

### Fail-closed (missing APK)

```bash
cd tools/lezi-sync
unset LEZI_RELEASE_APK
LEZI_FORCE_PACKAGE=1 LEZI_RELEASE_APK=/nonexistent/app-release.apk ./deploy/package-nas.sh
# exit 1
# error: release APK missing: /nonexistent/app-release.apk
```

### Success (signed release APK + metadata)

```bash
LEZI_FORCE_PACKAGE=1 \
  LEZI_RELEASE_APK=/home/zhangtianshu/lezi/app/build/outputs/apk/release/app-release.apk \
  LEZI_APP_UPDATE_JSON=deploy/app-update.json \
  ./deploy/package-nas.sh
# exit 0
# staged app-update com.lezi.babylog v0.3.0 (6)
# min_supported=1 sha256=10215034bf49f589173877d5904f3b88f3bc6e447470d72b77c19d1151850e83
# package: dist/lezi-sync-0.3.0-nas/app-update/{app-release.apk,app-update.json}
```

APK sha256 matched `deploy/app-update.json` byte-for-byte. Package is gitignored under `dist/`.

## Optional / force paths — environment boundaries

| Path | Automated evidence | Device / live-NAS boundary |
|------|--------------------|----------------------------|
| Optional: check → confirm → download → PackageInstaller | `RealSyncPortTest.installAvailableAppUpdate*`; settings/family UI wired | Emulator has **release** `com.lezi.babylog` installed (non-debuggable). Full UI path needs joined family session + trusted HTTPS server serving a **higher** versionCode APK; system install UI + unknown-sources prompt are platform-owned. Not re-driven end-to-end in this ticket. |
| Force: raise minSupported → fullscreen + sync gate | Server `client_update_required_*`; client ForcedUpdate / status mapping tests | Live raise of `min_supported_version_code` on family NAS + re-sync is operator maintenance; semantics locked in PRD §7.4 and code. |
| No private APK residue | Install path always cleans private stage in unit tests (success / sha mismatch / cancel-class failures) | `adb shell run-as com.lezi.babylog ls -R cache/app-update` **cannot** inspect non-debuggable release package. Debug `com.lezi.babylog.debug` was **not** installed on emulator-5554. Product does **not** promise clearing system PackageInstaller cache. |

Recommended manual device checklist (when a joined release build + server with newer APK are available):

```bash
./gradlew :app:installDebug   # only for debug UI smoke; self-update channel is release-only
# On release install joined to family:
# 菜单 → 关于「版本 …」→ 检查更新 → 确认 → 系统安装 UI
# 账户横幅「稍后」后同会话不再刷屏
# 抬高 min_supported_version_code 后前台同步 → 强制全屏（非「NAS 挂了」）
# After success/fail/cancel (debuggable build only):
adb shell run-as com.lezi.babylog.debug ls -R cache/app-update 2>/dev/null || true
# expect empty / missing
```

## Spec consistency

No open contradictions found between [spec.md](../../spec.md) Implementation Decisions and
current code/PRD:

- Self-hosted PackageInstaller, not Play Core
- Joined-session-only authenticated `/v1/app-update` + `/apk`
- Dual-tier versionCode / minSupported
- package-nas fail-closed APK+metadata
- Sync reject + update still allowed under `client_update_required`
- Private-cache residue cleanup; no public Download
- About line `版本 {versionName}`; marketing tagline removed

## Tracker close

- Issue 05 → `done`
- ISSUES.md 5/5 done; frontier empty
- `.scratch/README.md` marks self-hosted-app-update complete
