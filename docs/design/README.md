# Design notes

中等粒度的交互/实现设计，介于 grill 结论与 `docs/prd/` 写回之间。

**不是** issue tracker：可执行票与 blocking 边只在 [`.scratch/`](../../.scratch/)。  
**不是** ADR：难逆架构决策进 [`docs/adr/`](../adr/)。

## 生命周期

1. **Write** — grill / prototype 后，细节还不能直接塞进 PRD 时，落 `YYYY-MM-DD-<slug>.md`
2. **Active** — 实现进行中；可被 `.scratch` 票与 PR 引用
3. **Folded** — 行为已写进 `docs/prd/`，难逆点已有 ADR 或明确不需要；标 `Status: Folded`，或直接删除
4. **不要** 用本目录堆闭合票、截图或 review dump

## 当前文件

| 文件 | Status | 说明 |
|------|--------|------|
| [2026-08-08-continuous-timeline-navigation.md](./2026-08-08-continuous-timeline-navigation.md) | Active | 今天最近 24 小时、连续历史浏览、全局换日与实时吸附状态机；`ui.md` §3 / §5.2 为产品权威 |
| [2026-07-29-timeline-swipe-edit-delete.md](./2026-07-29-timeline-swipe-edit-delete.md) | Implemented | 时间轴左右滑细则；`ui.md` §5.2 链到本文 |
| [2026-07-30-trusted-sync-onboarding-ui.md](./2026-07-30-trusted-sync-onboarding-ui.md) | Active | 服务器连接、根密码管理员、成员审批/QR、设备与浅同步状态 UI；`ui.md` / `sync-trusted-endpoint` 的 UI companion |

## 命名

`YYYY-MM-DD-<short-slug>.md`。一篇主题一份正文；不要并行维护 summary 副本。
