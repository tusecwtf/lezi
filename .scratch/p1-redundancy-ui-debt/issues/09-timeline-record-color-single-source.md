# 09 — 时间条与记录语义色单源

**Parent:** [../spec.md](../spec.md)

**What to build:** 日视图时间条色块/圆点与记录类型强调色从**同一 designsystem / 主题扩展色板**读取；去掉构建 lanes 时的硬编码 ARGB 与绘制阶段再覆盖的双轨。warm 与 journal、浅/深色下图例、轨、列表类型色一致（PRD §2.1 两模板同一绘制、仅外壳不同）。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

**Size:** M  
**Theme:** F（U3）  
**Seams:** designsystem 记录/轨色；记录页出图

## Acceptance criteria

- [ ] 时间条 segments 颜色来自主题 token / `leziRecordColor`（或等价单源），无生产路径 `Color(0xFF…)` 喂养/护理语义色表并行
- [ ] 图例、轨标记、快捷类型色在同一模板下一致；切换 journal/warm 不出现轨与图例各用一套 hex
- [ ] 日图类型筛选高亮/弱化仍可读
- [ ] designsystem + feature/log 相关测试或色映射单测通过

## Out of scope

- journal 是否用宝宝色做 primary CTA（10）
- 睡眠装饰 moon/sun 散落色（审查 P2，可顺手仅当同一 token 表自然覆盖）
