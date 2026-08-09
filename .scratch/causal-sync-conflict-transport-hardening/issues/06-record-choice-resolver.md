# 06 — 让共享 resolver 使用完整 snapshot

**What to build:** 将 Record 时间线 resolver 深化为五类 root 共用的 domain resolver，完整展示候选并只提交 token、resolution mutation ID 与 choice IDs；Record 是首个入口。

**Blocked by:** 05

**Status:** ready-for-agent

## Contract slice

显示 provenance、stable/branch media、auto outcomes、deleted/restore 和 freshness；客户端不重建 resolved root/media，不允许 incomplete snapshot submit。

## Implementation sequence

1. 将 resolver state 改为五类 root 共用的单一 domain snapshot 输入。
2. 用每类 root 的字段-label table 映射候选、媒体与 deleted/restore 文案。
3. 构造 choice-only command 并处理 ACL/stale/terminal 结果。
4. 在 process recreation 后恢复选择或安全清空。

## Acceptance

- [ ] 五类 root 每个 conflict path 恰选一次才可提交
- [ ] incomplete/expired/offline snapshot 只读
- [ ] author/Owner affordance 正确，server ACL 仍是权威
- [ ] lost response 重试使用同一 resolution mutation ID

## Validation

- [ ] domain/state-machine/Compose resolver tests 通过
- [ ] 相关 connected test 留至票 42，票内记录未运行 gate

## Out of scope

不实现全局 inbox 或多页加载。
