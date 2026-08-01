# 16 — 修剪 SyncPort 死公开表面

**What to build:** 公共家庭同步入口不再暴露生产零调用、或接受 `familyId` 却忽略它的
方法；审查者只看到真实会话、endpoint、同步触发与 app-update 路径。

**Source:** `AUDIT-20260801-P2-02` + merged readability 04
**Blocked by:** 01 — 先固定同一 `RealSyncPort` 强制更新 seam
**Status:** ready-for-agent
**Size:** S–M

## Acceptance criteria

- [ ] 从公共 `SyncPort` 及 Real/NoOp/fake/caller 删除 `isEnabled`、`saveServer`、
  `pull(familyId)`、`push(familyId)`。
- [ ] 内部 `SyncBackend` / replica engine 的 `pull`、`push` 行为保持，未用 wrapper 假装消费 familyId。
- [ ] app-update surface 与 `cleanupAppUpdateStaging` 继续留在当前可信家庭服务器 seam。
- [ ] NoOp 默认在可行处收紧为 internal/test scope；不得为删除四个方法新增浅 adapter。
- [ ] Hilt binding、调用方和 tests 编译；仓库无已删 public symbol 的生产引用。
- [ ] 同步触发、CUR、terminal clear 和 foreground gate 行为无变化。

## Validation

运行 `:sync:test`、相关调用模块测试、`:app:assembleDebug` 与 `lintDebug`；
`rg` 证明被删 public symbol 无残留。

## Documentation Gate

若 `docs/prd/tech.md` 列出具体表面，与 Ticket 17 一并改为 current API，不另造第二份边表。

## Out of scope

不拆 `AppUpdatePort`，不移动整个 sync package（Ticket 28），不部署 NAS。
