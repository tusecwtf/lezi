# 07 — LogScreen 结构拆分（轨 / 坞 / 宿主）

**Parent:** [../spec.md](../spec.md)

**What to build:** 将记录页超大宿主拆成可审查的文件边界：时间条/日图几何与着色、快捷坞、列表与路由宿主分离。**行为零变更**——筛选、72h 轨、滑动编辑删除、布局编辑入口、Composer 打开路径保持。目的是降低审查噪声与 token 漂移风险（配合 09）。

**Blocked by:** 建议在 09（语义色单源）之后或严格分 PR，避免同 PR 混改颜色与文件搬家。

**Status:** ready-for-agent

**Size:** L  
**Theme:** E（U11 / 审查第二梯队 #9）  
**Seams:** feature/log 记录首页

## Acceptance criteria

- [ ] 原单文件职责拆到 ≥3 个可定位单元（例如：轨/lanes、坞、route/host）；宿主只编排
- [ ] 日图类型筛选、现在线、跨夜睡眠、滑动编辑删除、快捷槽与更多入口人工或自动化主路径不回归
- [ ] 无私有复制第二套业务规则；搬家不改变 public/internal API 语义
- [ ] `:feature:log` 单元测试通过；若缺关键路径测试则补最小回归

## Out of scope

- 改产品 IA 或新交互
- 拆 `CareLog`（08）或 `ReplicaSyncEngine`
