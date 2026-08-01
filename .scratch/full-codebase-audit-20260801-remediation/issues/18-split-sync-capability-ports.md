# 18 — 全面拆 SyncPort/RealSyncPort（grill rejected）

**Disposition:** 审计观察到宽接口与大文件属实，但 2026-08-01 architecture grill + adversarial review 判定现有对象仍是有深度的 façade；当前没有足够 shotgun-change 证据支持能力端口大拆。只实施 Ticket 16 的死表面修剪。

**Source:** `AUDIT-20260801-P2-04`  
**Blocked by:** Not executable
**Status:** wontfix
**Size:** —

## Reopen criteria

- [ ] 至少三次独立产品变化必须修改互不相关能力并反复触碰同一 façade，形成可引用的 shotgun-change 证据。
- [ ] 新 grill 证明拆分后的端口拥有不同调用者、生命周期或策略 owner，而不是一对一浅 adapter。
- [ ] 新方案保持共享 `syncMutex`、terminal clear、CUR 与 foreground gate 的单一协调者。
- [ ] 用户明确重新选择全面能力拆分范围；否则保持 `wontfix`。

## Current evidence

- 统一 `spec.md` 的 Locked boundaries 保留 grill Q4：只删除死公开表面，保留 app-update seam。
- Ticket 16 承担 prune-only；Tickets 28/31/32 只改善私有 locality，不增加 capability port。

## Documentation Gate

无；本文件只保留审计 disposition，不能作为实现票领取。

## Out of scope

不实施 AppUpdatePort 或其它能力拆分；P2-02 由 canonical Ticket 16 修剪。
