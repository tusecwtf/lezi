# 照护记录架构深化 · 票单索引

Parent: [spec.md](./spec.md)
Tracker: `.scratch/architecture-deepening-care-records/issues/`

## 依赖

```text
01 Summary 日投影
 └── 02 Summary 滚动窗口
      └── 03 Summary Widget / cleanup

04 Payload 兼容 tracer
 └── 05 Payload Composer 写路径
      ├── 06 Payload Summary / Timeline 读取 ← 03
      └── 07 Growth Weight 生命周期
           └── 08 Growth 参考事实
                └── 09 Growth caller 并轨
                     ├── 10 Payload 剩余读取 / cleanup ← 06
                     └── 12 Time Clock / Log / Composer ← 05, 11

11 Time 决策合同 / Clock tracer
 └── 12 Time Clock / Log / Composer
      └── 13 Time Growth / Calendar cleanup ← 09
```

Initial frontier：**01 ∥ 04 ∥ 11**。

## 票列表

| # | 文件 | Tracer slice | Blocked by | Status |
|---|------|--------------|------------|--------|
| 01 | [01-care-summary-day-log-tracer.md](./issues/01-care-summary-day-log-tracer.md) | 当日投影 → Log | — | done |
| 02 | [02-care-summary-rolling-summary.md](./issues/02-care-summary-rolling-summary.md) | 7/30 日投影 → Summary | 01 | done |
| 03 | [03-care-summary-widget-cleanup.md](./issues/03-care-summary-widget-cleanup.md) | Widget 迁移 + 旧聚合删除 | 02 | done |
| 04 | [04-care-payload-compatibility-tracer.md](./issues/04-care-payload-compatibility-tracer.md) | 兼容读写 → Presentation tracer | — | done |
| 05 | [05-care-payload-composer-writes.md](./issues/05-care-payload-composer-writes.md) | 全 RecordType → Composer 写路径 | 04 | done |
| 06 | [06-care-payload-summary-timeline-readers.md](./issues/06-care-payload-summary-timeline-readers.md) | Payload 含义 → Summary / Timeline | 03, 05 | done |
| 07 | [07-growth-weight-lifecycle-tracer.md](./issues/07-growth-weight-lifecycle-tracer.md) | Weight 生命周期 → Growth UI | 05 | done |
| 08 | [08-growth-reference-facts.md](./issues/08-growth-reference-facts.md) | Height/Head + 修正月龄/参考带 | 07 | done |
| 09 | [09-growth-caller-migration.md](./issues/09-growth-caller-migration.md) | Growth / Composer 并轨 | 08 | done |
| 10 | [10-care-payload-remaining-readers-cleanup.md](./issues/10-care-payload-remaining-readers-cleanup.md) | Search/Presentation/Export + parser cleanup | 06, 09 | done |
| 11 | [11-record-time-clock-tracer.md](./issues/11-record-time-clock-tracer.md) | DST/时钟合同 → Clock tracer | — | done |
| 12 | [12-record-time-clock-log-composer.md](./issues/12-record-time-clock-log-composer.md) | Clock UI / Log / Composer 迁移 | 05, 09, 11 | done |
| 13 | [13-record-time-growth-calendar-cleanup.md](./issues/13-record-time-growth-calendar-cleanup.md) | Growth / Calendar 迁移 + cleanup | 09, 12 | done |

## Cross-ticket rules

- 保持 `RecordComposer` 单一新增/编辑 seam。
- 每票只建立一个主要 seam 决策与一条可观察 tracer。
- 先写 interface 行为测试，再迁移目标 caller；旧路径只允许存在到对应 cleanup ticket。
- 不为 in-process 计算创建 port；local adapter 仅留在 implementation 内。
- 03、09、10、13 是 epic closure，负责 full regression 与旧路径删除门禁。
- 每票完成后更新本索引 Status，并在 ticket Comments 附验证证据。
