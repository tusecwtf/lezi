# 28 — sync 按能力分包并保留 Sync façade

**What to build:** sync 内部按 engine、backend、session、media、app-update 等能力形成 locality，
根 package 只保留 `SyncPort`、`RealSyncPort`、`SyncModule` 等协调入口。

**Source:** merged directory C3
**Blocked by:** 01、03、06、15、16、19 — 先闭合同一 sync seam 的行为/协议票
**Status:** ready-for-agent
**Size:** M–L

## Acceptance criteria

- [ ] 建立 `engine/`、`backend/`、`session/`、`media/`、`appupdate/`、`qr/`、`clear/`；
  小型 preference/runtime 类型按最少跳跃原则统一归属。
- [ ] `SyncPort`、`RealSyncPort`、`SyncModule` 保持根 deep façade/DI；共享 `syncMutex` 与协调者仍单一 owner。
- [ ] 本票不继续拆 `ReplicaSyncEngine` 文件，不新增 capability port，也不恢复 Ticket 16 删除的表面。
- [ ] test package/import、Hilt bindings 与所有 feature callers 可编译。
- [ ] diff 只包含移动、package/import 与必要可见性调整；wire、会话、媒体、CUR、清理行为不变。

## Validation

运行 `:sync:compileDebugKotlin`、`:sync:test`、所有直接依赖 sync 的 feature tests、
`:app:assembleDebug` 与 `lintDebug`。

## Documentation Gate

实际落地形状由 Ticket 33 汇总。

## Out of scope

不提取 AppUpdatePort，不部署 NAS，不改 endpoint/session 产品合同。
