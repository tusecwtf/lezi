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
| [2026-07-28-qr-join-one-tap-prefill.md](./2026-07-28-qr-join-one-tap-prefill.md) | Implemented 0.3 / Superseded | 旧 QR v1/SSID 交互历史；下一版由 trusted-sync onboarding 设计取代 |
| [2026-07-28-owner-remove-member.md](./2026-07-28-owner-remove-member.md) | Superseded | 0.3 `left_at` 历史；下一版由成员硬删除与作者匿名化取代 |
| [2026-07-29-layout-edit-android-desktop.md](./2026-07-29-layout-edit-android-desktop.md) | Implemented | 布局编辑态；PRD `ui.md` 已有摘要，细则仍可参考 |
| [2026-07-29-timeline-swipe-edit-delete.md](./2026-07-29-timeline-swipe-edit-delete.md) | Implemented | 时间轴左右滑；`ui.md` 已链到本文 |
| [2026-07-30-sync-network-auth-architecture-research.md](./2026-07-30-sync-network-auth-architecture-research.md) | Folded / Partly superseded | 原始网络研究；最终身份、QR 与迁移结论以 ADR-0011/PRD 为准 |
| [2026-07-30-trusted-sync-onboarding-ui.md](./2026-07-30-trusted-sync-onboarding-ui.md) | Active | 服务器连接、根密码管理员、成员审批/QR、成员设备与浅同步状态设计 |
| [2026-07-31-android-apk-in-app-update-research.md](./2026-07-31-android-apk-in-app-update-research.md) | Active · tracker | 应用内 APK 升级调研；实现见 [`.scratch/self-hosted-app-update/`](../../.scratch/self-hosted-app-update/spec.md) |

## 命名

`YYYY-MM-DD-<short-slug>.md`。一篇主题一份正文；不要并行维护 summary 副本。
