# 01 — 记录页 Snackbar 不遮挡固定快捷坞

**Parent:** [../spec.md](../spec.md)
**Audit ID:** `UIQA-20260730-P2-01`
**Severity:** P2
**Status:** complete
**Blocked by:** none
**Size:** S

## What to build

在展示**固定快捷记录坞**时，全局/根级 Snackbar 必须落在坞**上方**，不得盖住四槽与「更多」热区。

Grill 锁定修法 **A**：

- Snackbar 底部 inset（或等价 `SnackbarHost` padding）= **坞占用高度 + 安全间距**
- **不**以缩短展示时间作为唯一方案
- **不**缩小或上移坞来「躲」Snackbar
- 时长与文案策略保持现有行为（仅位置）

主要接线点（以实现时 HEAD 为准，入口已知）：

- `app/.../MainActivity.kt` · `LeziRoot` · `Scaffold(snackbarHost = { SnackbarHost(snackbar) })`
- 记录页坞：`feature/log/.../LogQuickDock.kt` + `QuickDockVisualSpec`（高度/padding 量）
- 触发示例：composer 保存成功 → `snackbar.showSnackbar`（「已记录…」）

优先实现：在 **记录 Tab 且坞可见** 时抬升 Snackbar；若实现更简单且无副作用，可对所有展示底坞/保留底栏 extent 的路由统一 inset（须说明，避免 timer/search 全屏被多余抬升）。

## Acceptance criteria

- [x] 记录 Tab 保存一条记录后，Snackbar 文案可见，且 **与快捷坞 bounds 不重叠**（截图或 uiautomator hierarchy）。
- [x] Snackbar 展示期间，四槽与「更多」仍可点（或至少热区未被 snackbar 矩形覆盖）。
- [x] 非记录全屏路由（如 timer / search / export / calendar）不出现错误空白底 inset，或与 chrome 策略一致。
- [x] 无回归：其他 `showSnackbar` 调用仍能显示。
- [x] 相关模块可编译；若加单测/结构断言，测的是 **真实 inset 策略或 host 包装**，不硬编码假期望。

## Primary seams

- `app/src/main/kotlin/com/lezi/babylog/MainActivity.kt`
- `feature/log/.../LogQuickDock.kt`
- `feature/log/.../RecordCatalogVisuals.kt` · `QuickDockVisualSpec`
- 可选：designsystem 若抽共享 `SnackbarHost` 包装

## Validation

- 设备：记录页 → 坞点类型 → 确认记录 → 截图/hierarchy 证无重叠。
- 或 Compose 测试：mock 固定 dock 高度 + snackbar host 布局断言。
- `./gradlew :app:assembleDebug` 或最小相关 compile。

## Non-goals

- 改 PRD 坞布局、槽序、手势
- 同步失败文案、家网 push
- 右滑删除（已 residual）

## Comments

- 页验证据：保存后 snackbar 压坞（审计 `204-after-save` 类帧）。
- Grill：修法 A（2026-07-30）。

## Evidence

- [validation.md](../evidence/01/validation.md)
- [snackbar-above-dock.png](../evidence/01/snackbar-above-dock.png)
