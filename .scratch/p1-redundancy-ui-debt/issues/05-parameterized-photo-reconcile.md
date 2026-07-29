# 05 — 照片 reconcile/tombstone 按所有者参数化

**Parent:** [../spec.md](../spec.md)

**What to build:** 护理记录与护理计划的附件 reconcile / soft-delete 使用同一算法，仅所有者外键（记录 vs 计划）参数化。附件上限、dirty 戳、按路径 revive 行为两边一致，且不打破 ADR-0003 所有权 XOR。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

**Size:** M  
**Theme:** C（R8）  
**Seams:** CareLog 附件写入

## Acceptance criteria

- [ ] 记录与计划照片写入/替换/清空走共享实现（参数化 owner），无两份复制算法体
- [ ] 既有照片上限（每条最多三张）、tombstone、syncDirty 行为有回归
- [ ] 禁止出现同时挂 record 与 carePlan 的歧义附件行
- [ ] domain 目标测试通过

## Out of scope

- 预览 UI（03）
- 原子推送管道（04）
- 整文件拆分 CareLog（08，可依赖本票结果）
