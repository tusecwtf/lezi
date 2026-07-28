# 01 — 统一横向一日时间条，删除 journal 竖轴

**What to build:** 记录页一日时间条在 **warm / journal** 下共用**同一套横向三轨**绘制与点选命中；journal 不再使用纵向 0–24h 轨道。行为仍是**单日 0–24h**：日图类型筛选、图例、现在线、出图条件与现网一致。模板差异只保留卡片外壳与色票。竖轴专用实现与仅服务竖轴的分支删除，避免后续 72h 维护两套几何。

**Blocked by:** None — can start immediately

**Status:** done

- [x] warm 与 journal 时间条均为横向三轨（睡眠 / 喂养 / 护理），点选筛选语义一致
- [x] journal 纵向 0–24h 专用绘制与命中路径已移除，无死代码分叉
- [x] 单日 0–24h 下：日图类型筛选、图例、现在线、无日图类型则隐藏等现网行为不回归
- [x] 设计系统预览与相关自动化（含 designsystem / feature 侧既有时间条测试）通过
- [x] 两模板切换后时间条能力一致，不得 journal 缺筛选或 warm 回归

## Comments

- Prefactor：为 02+ 的 72h 轴铺路；本票**不**引入邻日数据或平移。
- 2026-07-29 implement：删除 `JournalTimelineRail` 与 `TimelineRailCard` 的 isJournal 早退；两模板共用 `TimelineLane` 横向绘制/命中；journal 仅保留更紧凑的卡片外壳（padding/标题/meta）；PRD `ui.md` 去掉 journal「纵向 0–24h 轨道」表述。
