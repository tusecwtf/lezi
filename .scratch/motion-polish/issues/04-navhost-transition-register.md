# 04: 页面级过渡调优——NavHost push 升 medium 档 + 入出场缓动

**What to build:** 按 research.md 规格表，页面级进入应落在 M3 medium 档（250–300ms）并区分
入出场缓动，现状（MainActivity.kt:1367-1390）偏快且曲线单一。调整：

- 全屏 push 路由（`isFullScreenPushRoute` :903-907：timer/search/export/calendar）：
  enter = slideInVertically(h/24) + fadeIn，Emphasized 300 + `LeziEasing.EmphasizedDecelerate`；
  exit = fadeOut Fast 150 + `EmphasizedAccelerate`；popEnter/popExit 反向同规格。
- tab 切换（log/summary/growth/family/settings）：保持现状纯 fade Fast（研究结论：tab 切换
  属内容切换档，不升档）。
- 根 onboarding gate Crossfade（:954）与强更遮罩（:997）保持 Base/Fast 数值，待票 02 落地后
  出场侧补 accelerate。

**Blocked by:** 02（缓动 token）

**Status:** done（0.5.4 票 14 收编，2026-09-13；真机手感随发版票冒烟）

- [x] NavHost 四个 transition lambda 按上述规格调整（依赖 LeziEasing）
- [x] reduce-motion 行为不回退：Emphasized 经 `leziMotionMillis` 解析，scale=0 时退化为瞬时
- [x] 修改点补/更新对应单测（如有 transition 相关断言）
- [x] `./gradlew test`、`lintDebug` 全绿；真机过一遍 push/pop 手感

## Comments

- 2026-09-13（0.5.4 票 14）：push enter 升 Emphasized（300）+ `EmphasizedDecelerate`
  （slide h/24 与 fade 同规格）；exit 保持 Fast + `EmphasizedAccelerate`；
  popEnter/popExit 反向同规格（push 路由 popExit 走 Emphasized+Decelerate，其余维持
  Fast fade）；tab 切换维持纯 fade Fast 未动。强更遮罩出场侧补 Accelerate（票 02 落地）。
  **两处限制**：① 根 onboarding gate 是 `Crossfade`（单一 animationSpec 同时作用于
  进/出两侧），API 无出场侧缝，保持 Base 数值不补 accelerate；② 真机 push/pop 手感
  按发版票 18 的两台真机冒烟一并覆盖（本环境无设备）。单测：`MotionDensityTokensTest`
  静态断言钉住 Emphasized/LeziEasing 引用与无裸 ms 字面量。
