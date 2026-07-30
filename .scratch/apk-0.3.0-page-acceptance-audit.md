# 0.3.0 APK 逐页验收 · 兼容入口

**Status:** grill-closed · tracker **ready-for-agent**

权威审计（本地 only）：

[`docs/reviews/2026-07-30-apk-page-acceptance-audit.md`](../docs/reviews/2026-07-30-apk-page-acceptance-audit.md)

## Tracker

[`.scratch/apk-0.3.0-page-acceptance-remediation/`](./apk-0.3.0-page-acceptance-remediation/spec.md)

| 票 | 内容 | Status |
|----|------|--------|
| [01](./apk-0.3.0-page-acceptance-remediation/issues/01-snackbar-above-quick-dock.md) | Snackbar inset 浮坞上（修法 A） | ready-for-agent |

## Grill 决策锁定

| # | 题 | 锁 |
|---|----|----|
| 1 | Snackbar 修法 | **A** inset 浮坞上 |
| 2–6 | 右滑删除 | 子 agent **B-evidence** → **D residual 不立票** |
| 7 | 拆票顺序 | **B** 只开 01 |
| 8 | Tracker | **A** `apk-0.3.0-page-acceptance-remediation` |

## Residual

- [P2-05 右滑复合](./apk-0.3.0-page-acceptance-swipe-delete-compound.md)

## 一句话

页验 grill 收口：修 **Snackbar 压坞** 一张票；右滑删除 residual。
