# GF ↔ 0.3.x 对齐 · 票索引

**Status:** open · post-1.0.0 polish campaign  
**Parent:** [spec.md](./spec.md) · inventory [../spec-02-dual-scene-review/uiux-diff-spec.md](../spec-02-dual-scene-review/uiux-diff-spec.md)

| # | Title | Blocked by | Priority |
|---|--------|------------|----------|
| [01](./issues/01-engineering-copy-cleanup.md) | 剩余工程文案清理 | — | P0 |
| [02](./issues/02-composer-formula-field-order.md) | 配方奶 Composer 字段序 + 可选冲调量/耗时 | — | P0 |
| [03](./issues/03-post-write-next-feed-plan.md) | 写后下次喂养 / 计划体验对齐 | — | P0 |
| [04](./issues/04-dock-order-and-timer-entry.md) | 底坞默认序 + 计时入口收敛 | — | P0 |
| [05](./issues/05-day-summary-chips-empty-policy.md) | 日汇总 chips 空态策略 (E4) | — | P0 |
| [06](./issues/06-top-bar-day-age-theme.md) | 顶栏日龄/主题强调 (E7) | 05 | P0 |
| [07](./issues/07-type-icon-language.md) | 类型图标气质 (E3) | — | P0 |
| [08](./issues/08-more-sheet-grouping.md) | 更多 sheet 分组 IA | 07 | P1 |
| [09](./issues/09-dual-install-reverify-matrix.md) | 对齐后双装重验 + 矩阵 | 01–08（已完成者） | P0 |
| [10](./issues/10-section-23-blind-retry.md) | §2.3 盲测重试建议 | 09 | P0 |

## Dependency sketch

```text
01 文案
02 Composer 序/可选字段  ──┐
03 下次喂养/计划        ──┼→ 09 双装重验 → 10 §2.3
04 坞/计时入口          ──┤
05 日汇总 E4 → 06 顶栏 E7 ─┤
07 图标 E3 → 08 更多分组  ─┘
```

## Frontier (can start now)

**01, 02, 03, 04, 05, 07**
