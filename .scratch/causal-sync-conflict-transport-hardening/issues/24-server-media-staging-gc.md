# 24 — 回收 server media staging

**What to build:** 为 media receipt/staging 建立 TTL、consumed、orphan、branched/live reachability policy 与有界批次 GC，支持 crash/restart 且不删可达证据。

**Blocked by:** 19、23；[`external 19`](../../repository-dedup-algorithm-audit-20260809/issues/19-resolution-metadata-retention.md)；[`external R20`](../../repository-dedup-algorithm-audit-20260809/issues/20-version-offline-migration-schema-contracts.md)（implemented）

**Status:** ready-for-agent

## Contract slice

Unconsumed expired/orphan staging 可回收；consumed bytes 只有在无 live/branch/version reference 且 replay receipt 不再需要内容时 eligible。GC 状态 durable，批次有硬上限。

## Implementation sequence

1. 冻结 TTL、reachability、audit/replay 保留字段与 batch limit。
2. 从票 17/19 seam 标记 expired/consumed/orphan candidates。
3. 事务标记 GC pending，再删除对象并确认终态。
4. 注入 crash/restart，重试 incomplete batches。

## Acceptance

- [ ] 不删除 live/branched/version-referenced bytes
- [ ] replay/audit evidence 保留，unreachable staging 有界收缩
- [ ] GC crash/restart 幂等，批次不阻塞家庭 commit

## Validation

- [ ] TTL/reachability/batch/crash tests 通过
- [ ] Rust gates 与 isolated GC smoke 通过

## Out of scope

不回收 Android spool 或 conflict metadata。
