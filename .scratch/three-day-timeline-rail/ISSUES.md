# 三日时间条窗口（72h 窥视 + 平移）— 票索引

**Spec:** [spec.md](./spec.md)  
**Status:** ready-for-agent  

## Tickets

| # | Title | Blocked by | Status | File |
|---|--------|------------|--------|------|
| 01 | 统一横向一日时间条，删除 journal 竖轴 | — | done | [issues/01-unify-horizontal-rail.md](./issues/01-unify-horizontal-rail.md) |
| 02 | 72h 内容轴 + 非今日默认视口 + 跨夜连续睡 | 01 | done | [issues/02-three-day-axis-and-viewport.md](./issues/02-three-day-axis-and-viewport.md) |
| 03 | 邻日变浅 + 午夜日界线（无字） | 02 | done | [issues/03-dim-neighbors-and-day-boundaries.md](./issues/03-dim-neighbors-and-day-boundaries.md) |
| 04 | 「现在」线（窗口内）+ 今日视口对准现在 | 02 | done | [issues/04-now-line-and-today-viewport.md](./issues/04-now-line-and-today-viewport.md) |
| 05 | 出图条件：三天任一有日图类型 | 02 | done | [issues/05-show-when-any-of-three-days.md](./issues/05-show-when-any-of-three-days.md) |
| 06 | 多日轴筛选：A2 门闩 + 72h 高亮 | 02（建议 03、05 后） | done | [issues/06-filter-a2-and-72h-highlight.md](./issues/06-filter-a2-and-72h-highlight.md) |
| 07 | 72h 手势平移（不改选中日） | 02、04 | done | [issues/07-pan-within-72h.md](./issues/07-pan-within-72h.md) |

## Graph

```
01 统一横轴（删竖轴）
 └── 02  72h 内容 + 非今日窥视视口 + 跨夜连续睡
      ├── 03  邻日变浅 + 日界线          ┐
      ├── 04  现在线 + 今日视口          ├── 可并行
      └── 05  三天出图条件              ┘
           └── 06  筛选 A2 + 72h 高亮   （建议 03、05 后）
                └── 07  手势平移（硬依赖 02、04）
```

## Frontier

- **全部票已完成**（01–07）。

## Comments

- 2026-07-29：`/to-tickets` 初稿 3 票过粗，改为 7 票后用户确认发布。
- 2026-07-29：01 done — journal 竖轴删除，warm/journal 共用横向三轨。
- 2026-07-29：02 done — 72h 内容轴 + D 为主窥视视口 + 跨夜连续睡；列表/汇总仍绑 D。
- 2026-07-29：03/04/05 done — 邻日变浅+日界线；现在线+今日居中视口；三天任一出图。
- 2026-07-29：06 done — A2 门闩（D 上 0 条不进筛选）+ 72h 同类全亮；列表/图例/reconcile 仍绑 D。
- 2026-07-29：07 done — 72h 横拖平移（日键 viewport、夹紧、点选可区分、不改 D）。
