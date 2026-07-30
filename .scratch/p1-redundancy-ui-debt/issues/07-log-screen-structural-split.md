# 07 — LogScreen 结构拆分（轨 / 坞 / 宿主）

**Parent:** [../spec.md](../spec.md)

**What to build:** 将记录页超大宿主拆成可审查的文件边界：时间条/日图几何与着色、快捷坞、列表与路由宿主分离。**行为零变更**——筛选、72h 轨、滑动编辑删除、布局编辑入口、Composer 打开路径保持。目的是降低审查噪声与 token 漂移风险（配合 09）。

**Blocked by:** 建议在 09（语义色单源）之后或严格分 PR，避免同 PR 混改颜色与文件搬家。

**Status:** complete

**Size:** L  
**Theme:** E（U11 / 审查第二梯队 #9）  
**Seams:** feature/log 记录首页

## Acceptance criteria

- [x] 原单文件职责拆到 ≥3 个可定位单元（例如：轨/lanes、坞、route/host）；宿主只编排
- [x] 日图类型筛选、现在线、跨夜睡眠、滑动编辑删除、快捷槽与更多入口人工或自动化主路径不回归
- [x] 无私有复制第二套业务规则；搬家不改变 public/internal API 语义
- [x] `:feature:log` 单元测试通过；若缺关键路径测试则补最小回归

## Completion notes

- `LogScreen.kt` 由 2366 行降为 1362 行，只保留 `LogRoute`、列表与弹窗宿主编排；
  `LogViewModel.kt`、`LogTimeline.kt`、`LogQuickDock.kt` 分别承接状态/写入、时间线几何与筛选、
  快捷坞与更多目录。
- 新增结构契约测试，锁定四个职责入口只出现一次并防止 ViewModel、lanes、dock 回流宿主。
- 搬迁代码逐段与原文件做 SHA-256/文本比对；唯一可见性调整是跨文件调用所需的
  `MoreSheet private -> internal`，无业务规则复制或行为修改。
- `:feature:log` 全量单测、模块/应用 Lint、Debug APK 以及 API 35 设备测试 45/45 通过。
  完整回执见 [`../evidence/07/validation.md`](../evidence/07/validation.md)。

## Out of scope

- 改产品 IA 或新交互
- 拆 `CareLog`（08）或 `ReplicaSyncEngine`
