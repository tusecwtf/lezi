# 28 — sync 按能力分包并保留 Sync façade

**What to build:** sync 内部按 engine、backend、session、media、app-update 等能力形成 locality，
根 package 只保留 `SyncPort`、`RealSyncPort`、`SyncModule` 等协调入口。

**Source:** merged directory C3
**Blocked by:** 01、03、06、15、16、19 — 先闭合同一 sync seam 的行为/协议票
**Status:** done
**Size:** M–L

## Acceptance criteria

- [x] 建立 `engine/`、`backend/`、`session/`、`media/`、`appupdate/`、`qr/`、`clear/`；
  小型 preference/runtime 类型按最少跳跃原则统一归属。
- [x] `SyncPort`、`RealSyncPort`、`SyncModule` 保持根 deep façade/DI；共享 `syncMutex` 与协调者仍单一 owner。
- [x] 本票不继续拆 `ReplicaSyncEngine` 文件，不新增 capability port，也不恢复 Ticket 16 删除的表面。
- [x] test package/import、Hilt bindings 与所有 feature callers 可编译。
- [x] diff 只包含移动、package/import 与必要可见性调整；wire、会话、媒体、CUR、清理行为不变。

## Validation

运行 `:sync:compileDebugKotlin`、`:sync:test`、所有直接依赖 sync 的 feature tests、
`:app:assembleDebug` 与 `lintDebug`。

**Evidence (this worktree):**

- `:sync:compileDebugKotlin` green
- `:sync:testDebugUnitTest` green
- `:domain:compileDebugKotlin` + `:domain:testDebugUnitTest` green
- `:feature:family:compileDebugKotlin` + `:feature:family:testDebugUnitTest` green
- `:feature:onboarding:compileDebugKotlin` green
- `:feature:settings:compileDebugKotlin` + `:feature:settings:testDebugUnitTest` green
- `:feature:log:compileDebugKotlin` green
- `:app:assembleDebug` green
- `:sync:lintDebug` / `:app:lintDebug` green
- Root main: `SyncPort`, `RealSyncPort`, `SyncModule` only
- Subpackages under `com.lezi.babylog.sync.{engine,backend,session,media,appupdate,qr,clear}`
- Manifest receiver FQCN updated to `…sync.appupdate.AppUpdateInstallStatusReceiver`;
  broadcast action string remains `com.lezi.babylog.sync.APP_UPDATE_INSTALL_STATUS`
- No capability ports, no ReplicaSyncEngine file split, no typealias migration façade

## Public seams (TDD / design notes)

Contract is existing behavior tests + compile of public entry points (no new StructureTest):

| Seam | Location after partition |
|------|--------------------------|
| `SyncPort` / `NoOpSyncPort` / façade DTOs (FamilyMember, AppUpdate*, clear gates) | root |
| `RealSyncPort` (owns `syncMutex`, app-update install mutex, engine + clear coordinators) | root |
| `SyncModule` Hilt bindings | root |
| `ReplicaSyncEngine`, outbox push, wire mapper, foreground gate/retry, family-applied listeners | `engine` |
| `SyncBackend` / HTTP / refresh wrappers + wire DTOs used by session APIs | `backend` |
| Endpoint/trust, preferences/session, device name, uploader labels, `PolicyClock`/`ForegroundState` | `session` |
| Media file store, atomic bundle publisher, reference-aware cleanup | `media` |
| Installer, APK identity, UI copy, PackageManager compat | `appupdate` |
| Member-login QR codec + payload | `qr` |
| Local replica clear coordinator + committed-failure helpers | `clear` |

Shared test helpers (`MemorySyncPreferences`, memory DAOs, `RecordingSyncBackend`) remain in root test package (`RealSyncPortTest.kt`) and are imported by capability tests.

## Documentation Gate

实际落地形状由 Ticket 33 汇总，不在本票把 `.scratch` 目标写进长期 PRD。

## Out of scope

不提取 AppUpdatePort，不部署 NAS，不改 endpoint/session 产品合同。
