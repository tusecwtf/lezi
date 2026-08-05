# Dual APK multi-scene review · 票索引

**Status:** open · post-1.0.0 Spec 02 follow-on  
**Parent:** [spec.md](./spec.md) · freeze [../spec-02-apk-1.0-freeze/spec.md](../spec-02-apk-1.0-freeze/spec.md)  
**Tracker:** local Markdown · `Status: ready-for-agent` on each issue unless noted

| # | Title | Blocked by | Priority |
|---|--------|------------|----------|
| [01](./issues/01-scene-matrix-and-rubric.md) | 场景矩阵 + 统一 rubric | — | P0 |
| [02](./issues/02-dual-capture-harness-and-seed.md) | 双装录制 harness + 种子数据约定 | 01 | P0 |
| [03](./issues/03-log-home-empty-ia.md) | 记录首页 · 空态 IA | 02 | P0 |
| [04](./issues/04-log-home-seeded-row.md) | 记录首页 · 有数据行结构 | 02 | P0 |
| [05](./issues/05-tab-crossfade-motion.md) | Tab 切换动效 (A2) | 02 | P0 |
| [06](./issues/06-day-axis-navigation.md) | 三日轴 / 日期切换 | 02 | P0 |
| [07](./issues/07-composer-formula.md) | Composer · 配方奶 开合+字段 | 02 | P0 |
| [08](./issues/08-composer-sleep.md) | Composer · 睡眠 | 02 | P0 |
| [09](./issues/09-composer-diaper.md) | Composer · 尿/便 | 02 | P0 |
| [10](./issues/10-composer-breast-vs-timer-entry.md) | Composer · 母乳 / 与计时入口 | 02 | P0 |
| [11](./issues/11-swipe-half-reveal-edit.md) | 滑动 · 半程 reveal 编辑 | 02, 04 | P0 |
| [12](./issues/12-swipe-full-commit-edit.md) | 滑动 · 满行程提交编辑 | 02, 04 | P0 |
| [13](./issues/13-swipe-full-delete-confirm.md) | 滑动 · 满行程删除确认 | 02, 04 | P0 |
| [14](./issues/14-more-sheet-four-col.md) | 更多 sheet 四列 | 02 | P0 |
| [15](./issues/15-timer-enter-idle.md) | 计时 · 进入与 idle | 02 | P0 |
| [16](./issues/16-timer-run-complete-confirm.md) | 计时 · 运行→完成→确认单 | 02, 15 | P0 |
| [17](./issues/17-layout-editor-drag-exit.md) | 布局编辑 · 进入/拖拽/退出 | 02 | P0 |
| [18](./issues/18-summary-week-shallow.md) | 汇总 · 有数据周图浅对照 | 02 | P1 |
| [19](./issues/19-growth-curve-shallow.md) | 成长 · 曲线浅对照 | 02 | P1 |
| [20](./issues/20-template-warm-journal.md) | 模板切换 warm↔journal | 02 | P1 |
| [21](./issues/21-rollup-matrix-and-residuals.md) | 总评卷 · 矩阵 + residual + §2.3 | 03–17 (18–20 if done) | P0 |

## Dependency sketch

```text
01 → 02 → 03,05,06,07–10,14–15,17,(18–20 P1)
       ↘ 04 → 11,12,13
       ↘ 15 → 16
03–17 (+ optional 18–20) → 21
```

## Out of batch

- Spec 01 data migration · production NAS CD · pixel CI · reopening S-freeze as fail if chrome differs
