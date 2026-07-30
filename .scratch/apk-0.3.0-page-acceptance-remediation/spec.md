# Spec · 0.3.0 APK 逐页验收整改

**Status:** complete

Audit (local only): `docs/reviews/2026-07-30-apk-page-acceptance-audit.md`
Scratch entry: `.scratch/apk-0.3.0-page-acceptance-audit.md`
APK anchor: `dist/lezi-0.3.0-release.apk` · `versionName=0.3.0`
Workspace HEAD at tracker open: `cd57b5a7f18f21eacd8f78828215cbc3aa832929`

## Problem statement

0.3.0 设备逐页验收确认主路径可用、关键写路径 REAL。Grill 锁定本 tracker **仅**处理页验中确认的 UI 修项。

- **In scope:** Snackbar 压住记录页固定快捷坞（`UIQA-20260730-P2-01`）。
- **Out of scope / residual:** 右滑删除半程层（`UIQA-20260730-P2-05`）经子 agent 复合为 **B-evidence**，grill **D residual 不立票**（报告 `.scratch/apk-0.3.0-page-acceptance-swipe-delete-compound.md`）。
- **另轨:** 家网 push 422（client 0.3.0 × server 0.2.6 skew）、母乳/自定义双轨、next-feed CR P0（post-0.3.0 tracker）。

## Locked grilling decisions

1. **Snackbar 修法 A：** 记录 Tab（及任何展示固定快捷坞的表面）下，Snackbar 底部 inset = 坞占用高度 + 安全间距，始终浮在坞**上方**；不缩短时长作唯一方案；不改动坞触达。
2. **右滑删除：** 子 agent 复合 **B-evidence** → **D residual，不立票**。
3. **拆票顺序 B：** 只先开/实现 01；02 不存在。
4. **Tracker slug A：** 独立 `.scratch/apk-0.3.0-page-acceptance-remediation/`，不并入 post-0.3.0 code-review tracker。

## Delivery shape

| 审计项 | 处置 | Tickets |
|--------|------|---------|
| P2-01 Snackbar 压坞 | 立即修 | 01 |
| P2-05 右滑半程 | residual | 无 |

## Global acceptance gates

- 01 必须有设备或 Compose 证据：保存后 snackbar bounds 与坞 bounds **不重叠**，且四槽+更多仍可点。
- 相关模块单测（若新增）+ `:app:assembleDebug` 或 targeted compile；不扩大 nav/sync 范围。
- 不回改已 complete 的 0.3.0 审计票。

## Out of scope

- 同步 wire / lezi-sync 版本门
- NavHost 转场动画、邀请 FLAG_SECURE、结构债拆文件
- 将 B-evidence 的滑动手势当产品 bug 重开
