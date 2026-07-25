# V1 交付 · 票单索引

Parent: [spec.md](./spec.md) · 审查: [REVIEW.md](./REVIEW.md)  
Tracker: `.scratch/v1-delivery/issues/`

> **2026-07-22 增量：** PRD 新增排泄图标/尿量档、配方奶 5ml 步进 → Spec 需求 A/B；票 **04** 覆盖排泄；新票 **13** 覆盖配方奶步进 UI。

## 依赖

```text
01 骨架+首个宝宝
 ├── 02 快记+时间轴+日汇总
 │    ├── 03 睡眠
 │    ├── 04 排泄图标+尿量档     ★ 增量 A
 │    ├── 05 母乳计时
 │    ├── 06 时间条+跳日
 │    ├── 07 编辑删除
 │    ├── 08 设置+下次喂奶
 │    └── 13 配方奶 5ml 步进 UI  ★ 增量 B（硬依赖 02）
 ├── 09 多宝宝+深色
 ├── 10 其余类型+日记
 └── 11 账户 Stub
              └── 12 APK 冒烟     ← 含 04 + 13
```

`03–07`、`13` 在 **02 后可并行**（13 与 08 联调步进设置入口）。

## 票列表

| # | 文件 | 主题 | Blocked by |
|---|------|------|------------|
| 01 | [01-scaffold-first-baby.md](./issues/01-scaffold-first-baby.md) | 骨架+宝宝 | — |
| 02 | [02-quick-log-timeline-summary.md](./issues/02-quick-log-timeline-summary.md) | 快记主干 | 01 |
| 03 | [03-sleep-soft-check.md](./issues/03-sleep-soft-check.md) | 睡眠 | 02 |
| 04 | [04-poop-structured.md](./issues/04-poop-structured.md) | **排泄分档+图标+尿大中小** | 02 |
| 05 | [05-nursing-timer-fgs.md](./issues/05-nursing-timer-fgs.md) | 计时 | 02 |
| 06 | [06-timebar-day-nav.md](./issues/06-timebar-day-nav.md) | 时间条 | 02 |
| 07 | [07-edit-delete-record.md](./issues/07-edit-delete-record.md) | 编删 | 02 |
| 08 | [08-settings-next-feed.md](./issues/08-settings-next-feed.md) | 设置+提醒 | 02（联调 05） |
| 09 | [09-multi-baby-theme-dark.md](./issues/09-multi-baby-theme-dark.md) | 多宝宝深色 | 02 |
| 10 | [10-remaining-types-diary.md](./issues/10-remaining-types-diary.md) | 其余类型 | 07 |
| 11 | [11-account-syncport-stub.md](./issues/11-account-syncport-stub.md) | 账户 Stub | 01 |
| **13** | [13-formula-step-5ml-ui.md](./issues/13-formula-step-5ml-ui.md) | **配方奶 5ml 步进+UI 同步** | **02** |
| 12 | [12-apk-smoke-delivery.md](./issues/12-apk-smoke-delivery.md) | 冒烟关门 | 02–11,**13** |

## Spec ↔ 增量映射

| Spec 章节 | PRD | Ticket |
|-----------|-----|--------|
| 需求 A 排泄分档与图标 | §3.1 | **04** |
| 需求 B 配方奶 5ml 步进 | §4.1.1 | **13** |
| US 33–36 / 26 | 同上 | 04 / 13 |

## 单人推荐序

`01 → 02 → 05 → 03 → 04 → 13 → 07 → 06 → 08 → 09 → 10 → 11 → 12`

## 默认产品语义

| 语义 | 约定 | 主票 |
|------|------|------|
| pee 默认 | pee_amount=2（中） | 04 |
| both_diaper | +1 尿 +1 便 | 04 |
| amount_step_ml 默认 | **5** | 13 |
| pumped_feed | 计入 feedMl | 10 |
| pump_express | 不计入 feedMl | 10 |
