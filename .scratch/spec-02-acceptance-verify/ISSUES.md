# Acceptance verification · 票索引

**Status:** open · post-inventory evidence cleanup  
**Parent:** [spec.md](./spec.md) · inventory [../spec-02-dual-scene-review/uiux-diff-spec.md](../spec-02-dual-scene-review/uiux-diff-spec.md)

| # | Title | Blocked by | Priority |
|---|--------|------------|----------|
| [01](./issues/01-harness-midframe-and-legacy-seed.md) | Harness：中途截帧 + legacy 下次喂养关闭 + seed fail-closed | — | P0 |
| [02](./issues/02-reverify-weak-composers.md) | 重验 08 睡眠 / 09 尿 / 10 喂奶·计时 双端 sheet | 01 | P0 |
| [03](./issues/03-reverify-swipe-midframes.md) | 重验 11–13 滑动半程/满编/满删 中途静帧 | 01 | P0 |
| [04](./issues/04-reverify-timer-layout.md) | 重验 15 计时 idle / 16 运行 / 17 布局编辑 | 01 | P0 |
| [05](./issues/05-polish-debug-copy.md) | P0 polish：sleep epoch / summary dump / PENDING 中文 | — | P0 |
| [06](./issues/06-matrix-verified-partial-rollup.md) | 矩阵改为 verified/partial + 总评与 §2.3 建议 | 02–05 | P0 |
| [07](./issues/07-optional-p1-surfaces.md) | 可选 P1：汇总有数据 / 成长 / 模板 | 01 | P1 |

## Dependency sketch

```text
01 harness
  → 02 composers · 03 swipe · 04 timer/layout
05 polish (parallel with 01–04)
02–05 → 06 rollup
07 optional
```
