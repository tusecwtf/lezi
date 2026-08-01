# 04 — 修剪 SyncPort 死表面

**What to build:** 公共家庭同步入口不再暴露生产零调用的 isEnabled、saveServer、以及带 familyId 却被忽略的 pull/push；审查与调用方只看见真实路径（会话、endpoint 配置、requestSync/sync、app-update 等）。内部引擎仍可 pull/push。与全库审计票 16 once-only。

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] 公共 SyncPort（及 Real/NoOp/fake）已去掉 isEnabled、saveServer、pull(familyId)、push(familyId)
- [ ] 内部 SyncBackend / 副本引擎 pull/push 行为保持
- [ ] app-update 表面与 cleanupAppUpdateStaging 仍保留在家庭服务器 seam 上
- [ ] NoOp 默认收紧为 internal 和/或测试源，DI 仍只绑真实 adapter
- [ ] 与 `full-codebase-audit-20260801-remediation` 16 协调为只做一次，另一侧留言 superseded/duplicate
- [ ] sync 及相关模块测试通过
