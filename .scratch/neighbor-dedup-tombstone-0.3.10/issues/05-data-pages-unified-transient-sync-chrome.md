# 05 — 记录/汇总/成长统一短暂浅同步 chrome

**What to build:** 记录、汇总、成长三页的同步状态文案改用 **同一 Compose 元素**；用户
**下拉刷新** 触发的同步结束后，结果行在内容区只显示约 **5 秒**，随后 **缩入 root top bar**；
内容区 **不再永久** 挂着 `暂时无法同步 · 下拉重试`（或成功/待同步）整行。属 **0.3.10** 产品面。

**Blocked by:** None — can start immediately（与 01–03、06 独立）。

**Status:** ready-for-agent

## Scope

- 统一元素：替换 `feature/log` `LogTimelineList`、`feature/summary` `SummaryScreen`、
  `feature/growth` `GrowthScreen` 中三份手写 `Text(shallowSync…)` + error 上色。
- 数据源：继续 `ShallowSyncLine`（`projectShallowSyncLine` 文案矩阵不变，含
  `暂时无法同步 · 下拉重试`）。
- 生命周期：
  - **下拉进行中**：同一元素展示同步中态 + 既有 PullToRefresh spinner 合同。
  - **下拉结束**：结果可见 **≈5s**，再动画收起到 **top bar** 次要位。
  - **静默触发**（回前台 / LocalWrite / 进页节流）：不强制在内容区弹出常驻长行。
- 缩入 top bar 后：用户仍能在 header 感知最近一次下拉结果的次要提示；**不得**再占三页
  列表顶部永久一行。
- 账户概览家庭卡上的一句同步状态 **不改**（本票仅三数据页）。

## Acceptance

- [ ] 三页共用同一浅同步 UI 组件（无三份复制粘贴样式/颜色分支）
- [ ] 下拉同步结束后内容区结果行约 5 秒后消失并缩入 top bar
- [ ] 失败态不再在记录/汇总/成长内容区永久显示「暂时无法同步 · 下拉重试」
- [ ] 静默同步不强制弹出与下拉同等的内容区常驻行
- [ ] 无障碍：缩入前后 TalkBack 仍能读到状态含义；testTag 可定位
- [ ] 单元或 Compose/设备测试覆盖：下拉结束 → 可见 → 超时后内容区不可见 / top bar 有次要态
- [ ] warm/journal 与深浅色下不回归主内容挤压

## Notes

- 5 秒为产品固定值；reduce-motion 下可缩短动画但仍应收起，不得永久占位。
- 若 top bar 在某路由隐藏，收起态可落到该页可用的等价 chrome，不得回退为内容区常驻。
- Spec：[`../spec.md`](../spec.md) Solution §3、US 46–48。
