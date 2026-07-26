# 日视图时间条 · 日图类型筛选 — 票索引

**Spec:** [spec.md](./spec.md)  
**Status:** partial  

## Tickets

| # | Title | Blocked by | Status | File |
|---|--------|------------|--------|------|
| 01 | 无日图类型则隐藏时间条 | — | done | [issues/01-hide-chart-without-day-types.md](./issues/01-hide-chart-without-day-types.md) |
| 02 | 日图类型筛选（点选 · 高亮 · 明细 · 图例 · 提示条） | 01 | partial | [issues/02-day-chart-type-filter-ux.md](./issues/02-day-chart-type-filter-ux.md) |

## Graph

```
01 无日图类型则隐藏时间条
 └── 02 日图类型筛选（端到端）
```

## Frontier

- **可立即继续：** 02（修复同一时刻多类型标记的命中判定）  
- **已完成：** 01  

## Comments

- 2026-07-26：`/to-tickets` 两票竖切已发布。
- 2026-07-26 并行审查（01 / 02 / 图）：
  - **图：approve graph（保持 2 票，不合并不拆 3）**；真依赖是日图类型模型，hide UI 与 filter 可串行交给 agent 队列。
  - **01：approve-with-nits** → 已写回：空日隐藏、负向范围（不动 tip/列表/页级 selected）、模型全量单测但产品仅接 `shouldShowDayChart`。
  - **02：approve-with-nits** → 已写回：刷新后类仍在则保持筛选、selected 非持久、图例用日图类型名、提示条条数=filter 条数、非今日一致。
  - 已知 rework：02 将把 designsystem 内段级 `selected` 升为页级日图类型；01 **禁止**提前改选中 API，以免白做。
- 2026-07-26 implement wave daychart-w1-01：01 done（`DayChartCategories` + LogScreen 出图条件）。
- 2026-07-26 implement wave daychart-w2-02：02 主路径已实现（页级日图类型筛选 · 高亮 · 明细 · 图例 · 类型提示条）。
- 2026-07-26 只读审计：`BOTH_DIAPER` 的尿/便标记同一时刻绘制时，warm/journal 命中逻辑只按时间距离取第一个并列项，无法可靠点中第二个类型；02 重开为 `partial`。
