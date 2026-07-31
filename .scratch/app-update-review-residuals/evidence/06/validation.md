# Ticket 06 · 文档诚实门槛 + 关键门禁测试

- Date: 2026-07-31 (Asia/Shanghai)
- Tracker: `.scratch/app-update-review-residuals`
- Ticket: `issues/06-docs-and-gate-test-gaps.md`
- Validation base (pinned): `080d504a29eeaf830bc1506ce8fabf416ec699e2`
- Worktree changes (uncommitted at validation; Commit phase owns the atomic commit):
  - `docs/prd/tech.md`
  - `docs/prd/sync-trusted-endpoint.md`
  - `tools/lezi-sync/tests/api.rs`
  - `sync/src/test/kotlin/com/lezi/babylog/sync/HttpSyncBackendTest.kt`
  - `tools/lezi-sync/deploy/package-nas.sh`
  - `tools/lezi-sync/deploy/test-package-nas-app-update.sh` (new)
  - `tools/lezi-sync/deploy/DEPLOY.md`
  - `.github/workflows/lezi-sync.yml` (fix round 1: CI smoke step)
  - `AGENTS.md` (fix round 1: CHECK_ONLY env row)

## Disposition

1. **文档诚实门槛**：`tech.md` §4.2 与 `sync-trusted-endpoint.md` §7.4 写明
   `X-Lezi-Client-Version-Code` / minSupported 是对**诚实官方 App** 的兼容闸，
   **不是**防篡改安全根；真协议硬闸靠 capabilities / wire / 会话鉴权。
2. **服务端门禁**：既有 pull 低/缺 version → `client_update_required`；补 **media GET** 同闸。
3. **客户端权威请求头**：`HttpSyncBackend.pull` 断言携带 `X-Lezi-Client-Version-Code`
   （app-update 路径原有断言保留）。
4. **安装/下载失败清理**：01/02 已覆盖（sha 失败、download 失败清理暂存且不污染 SyncStatus）—
   本票回归复跑，不重复造测。
5. **package-nas 轻量 fail-closed**：`LEZI_PACKAGE_APP_UPDATE_CHECK_ONLY=1` +
   `deploy/test-package-nas-app-update.sh`（缺 APK / sha 错失败；匹配输入成功；无 docker save）。

## Automated gates

```bash
cd tools/lezi-sync
cargo test --locked --test api client_update_required

./deploy/test-package-nas-app-update.sh

# from repo root
./gradlew :sync:testDebugUnitTest \
  --tests "com.lezi.babylog.sync.HttpSyncBackendTest.pullSendsClientVersionCodeHeaderOnAuthoritativeSync" \
  --tests "com.lezi.babylog.sync.HttpSyncBackendTest.getAppUpdateMetadataUsesAuthenticatedGetAndParsesWireFields" \
  --tests "com.lezi.babylog.sync.RealSyncPortTest.installAvailableAppUpdateDownloadFailureDoesNotPoisonSyncStatus" \
  --tests "com.lezi.babylog.sync.RealSyncPortTest.installAvailableAppUpdateRejectsSha256MismatchWithoutInstalling"
```

| Case | Assertion |
|------|-----------|
| pull missing / low version | `403` + `code=client_update_required` |
| media GET missing / low version | `403` + `code=client_update_required` |
| media GET at min, unknown media | `404`（非 force-update） |
| client pull request | header `X-Lezi-Client-Version-Code: 6` |
| package-nas missing APK | non-zero exit |
| package-nas wrong sha | non-zero exit |
| package-nas matching inputs (check-only) | exit 0; logs contain `app-update check only` + `app-update inputs OK`; no full-package markers |
| install/download failure staging | cleaned; SyncStatus Idle (01/02) |

## Fix round 1 (review residuals)

1. CI: `lezi-sync.yml` runs `./deploy/test-package-nas-app-update.sh` after clippy.
2. DRY: `seed_client_update_gate` shared by pull/media/allowlist API tests.
3. PRD §7 quality gates name media GET / pull version header / package-nas check-only.
4. Media GET matrix: invalid header + above-min parity with pull.
5. Honesty wording: full statement in tech.md §4.2; sync §7.4 cross-links only.
6. Shell smoke: capture logs; assert `release APK missing` / `sha256 does not match`.
7. AGENTS.md documents `LEZI_PACKAGE_APP_UPDATE_CHECK_ONLY` + smoke script path.
8. `write_meta` helper for good/bad JSON templates.

## Fix round 2 (review residuals)

1. Success path of `test-package-nas-app-update.sh` captures logs and greps for
   `app-update check only` + `app-update inputs OK`; rejects log lines that look like
   full packaging (`docker save` / `saving image` / `package complete`).
2. `docs/prd/tech.md` §7 cites fully qualified
   `tools/lezi-sync/deploy/test-package-nas-app-update.sh`.
3. CI invokes `bash deploy/test-package-nas-app-update.sh` so a non-executable file
   mode still runs on Actions.
4. `.scratch/README.md` tracker row: complete · 6/6 (was stale ready-for-agent · 1/6).

## Result

Focused cargo API tests: **3 passed**.  
Gradle focused unit tests: **BUILD SUCCESSFUL**.  
`test-package-nas-app-update.sh`: **package-nas app-update fail-closed smoke passed**
(with success-path CHECK_ONLY log asserts).
