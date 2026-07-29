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
| [2026-07-28-qr-join-one-tap-prefill.md](./2026-07-28-qr-join-one-tap-prefill.md) | Draft | 扫码加入一键预填；实现前主设计文 |
| [2026-07-28-owner-remove-member.md](./2026-07-28-owner-remove-member.md) | Folded | 规则已在 `docs/prd/ui.md` §5.7 / sync PRD |
| [2026-07-29-layout-edit-android-desktop.md](./2026-07-29-layout-edit-android-desktop.md) | Spec + Implementation | 布局编辑态；PRD `ui.md` 已有摘要，细则仍可参考 |
| [2026-07-29-timeline-swipe-edit-delete.md](./2026-07-29-timeline-swipe-edit-delete.md) | Spec | 时间轴左右滑；`ui.md` 已链到本文 |

## 命名

`YYYY-MM-DD-<short-slug>.md`。一篇主题一份正文；不要并行维护 summary 副本。
