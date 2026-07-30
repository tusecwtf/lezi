# 0.3.0 APK 逐页验收整改 · 票索引

Spec: [spec.md](./spec.md)
Status: complete
Source audit: `docs/reviews/2026-07-30-apk-page-acceptance-audit.md`

- Executable tickets: **1**
- Immediate ready-for-agent: **0**
- Accepted residuals without ticket: **1**

## Tickets

| ID | Title | Audit ID | Blocked by | Size | Status |
|----|-------|----------|------------|------|--------|
| [01](./issues/01-snackbar-above-quick-dock.md) | 记录页 Snackbar 不遮挡固定快捷坞 | UIQA-20260730-P2-01 | — | S | complete |

## Accepted residuals

| Audit ID | Disposition | Evidence |
|----------|-------------|---------|
| UIQA-20260730-P2-05 | residual；半程绿/红层符合 PRD，页验漏检为行程/时序 | [swipe-delete compound](../apk-0.3.0-page-acceptance-swipe-delete-compound.md) |

## Dependency graph

```text
01 Snackbar 压坞 inset（complete）
```
