# V1.5 交付 · 票单索引

Parent: [spec.md](./spec.md)  
Prerequisite: **V1 交付完成**（[../v1-delivery/](../v1-delivery/)）  
审查: [REVIEW.md](./REVIEW.md)

## 依赖

```text
V1 done
 ├── 01 周汇总四图
 ├── 02 身长体重 + 成长曲线     ← 可与 01 并行
 ├── 03 搜索                   ← 可与 01/02 并行
 ├── 04 TXT 导出（ExportPort） ← 可与 01–03 并行
 └── 05 桌面小组件（只读 CareLog）
         ↑ 硬依赖：V1 done
         ↑ 软依赖：日/最近查询稳定即可；不硬等 01 周聚合
              └── 06 V1.5 冒烟交付    ← blocked by 01–05
```

| # | 文件 | Status | Blocked by |
|---|------|--------|------------|
| 01 | [issues/01-week-summary.md](./issues/01-week-summary.md) | ready-for-agent | V1 done |
| 02 | [issues/02-growth-curve.md](./issues/02-growth-curve.md) | ready-for-agent | V1 done |
| 03 | [issues/03-search.md](./issues/03-search.md) | ready-for-agent | V1 done（含编辑页） |
| 04 | [issues/04-txt-export.md](./issues/04-txt-export.md) | ready-for-agent | V1 done |
| 05 | [issues/05-widget.md](./issues/05-widget.md) | ready-for-agent | V1 done（软：最近查询稳定；**不**硬等 01） |
| 06 | [issues/06-v15-smoke.md](./issues/06-v15-smoke.md) | ready-for-agent | 01–05 |

## 开工顺序（建议）

- **可并行：** 01 ∥ 02 ∥ 03 ∥ 04  
- **05：** V1 后即可；与 01 无硬阻塞，仅共享 CareLog 查询习惯时注意接口稳定  
- **06：** 01–05 验收勾选后收口  

Frontier：V1 关门后 01–04（及 05）均可 `/implement`。

## Seams（跨票）

| Seam | 主责票 | 说明 |
|------|--------|------|
| **CareLog 扩展** | 01 / 02 / 03 /（05 只读） | `weekSummary`、height/weight、`search`、最近摘要查询 |
| **ExportPort** | 04 | `exportTxt`；UI 不直连 Room |
| **Widget 只读** | 05 | Glance 消费与 CareLog 同一 usecase，无第二业务源 |

## PRD §6.2 覆盖

| PRD V1.5 项 | 票 |
|-------------|----|
| 周汇总四类图 | 01 |
| 身长体重 + 至少一套曲线 | 02 |
| 搜索 | 03 |
| TXT 导出 | 04 |
| Widget | 05 |
| 可安装验收 / 不回退 V1 | 06 |
