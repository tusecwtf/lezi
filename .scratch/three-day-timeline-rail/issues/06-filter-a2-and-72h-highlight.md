# 06 — 多日轴筛选：A2 门闩 + 72h 高亮

**What to build:** 在 72h 轴上点选日图类型时：明细、图例、可筛集合仍**只看选中日 D**。点轴上某类（含点在邻日标记上）时，若该类在 **D 上 0 条 → 不进入筛选（A2）**，状态不变。若 D 上有该类，则进入筛选：列表只含 D 的该类；图上 **72h 内该类标记全部高亮**，其它弱化。图例仍只列 D 当日有数据的类。换日清空筛选；`reconcileSelection` 仍以 D 的记录为准。摘要条与时间条共用同一筛选态（现网约定保留）。

**Blocked by:** 02 — 72h 内容轴 + 非今日默认视口 + 跨夜连续睡  
**Recommended after:** 03（变浅）、05（空 D 出图）— 不硬阻塞，但更易验收

**Status:** done

- [x] 图例 / 可筛集合仅来自 D 的日图类型
- [x] 点邻日或 D 上某类，若 D 无该类 → 不进入筛选、列表不变
- [x] 点 D 有数据的类 → 列表只含 D 该类；72h 内该类（含邻日）全部高亮
- [x] 再点同类 / 空白 / 提示条 / 换日 → 取消筛选（现网路径保留）
- [x] 筛选不改选中日 D；日汇总仍全日
- [x] A2 与 72h 高亮范围有自动化覆盖；warm / journal 语义一致

## Comments

- Grill：Q6=A（点邻日认类型）、Q8=B（72h 全亮）、Q12=A2（D 上 0 条不进筛选）。
- 2026-07-29：A2 门闩落在 domain `DayChartCategories.commitSelection` + feature `Select(dayRecords=D)`；邻日-only 不进筛选；选中后靠既有 `selectedCategoryKey` 在 72h 全亮；图例/列表/reconcile 仍绑 D；单测覆盖 domain / wiring / lanes / hit-key。
