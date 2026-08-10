# 19 — 限制 resolution 查询并回收冲突 metadata

Status: ready-for-agent

Priority: P1

Audit baseline: `38cfbe7dbcf1f410237dce77b50ec1bd4854d8a6`

Blocked by: —（18 与 [`causal hardening 04`](../../causal-sync-conflict-transport-hardening/issues/04-causal-tombstone-restore.md) 已实现）。

## What to build

让 choice-only resolution 在固定查询预算内消费 snapshot receipt，并有界回收 resolved/expired 的 conflict metadata、snapshot receipts、choice material 与已证明不可达的 branch metadata。

## Implementation sequence

1. 冻结 query budget、retention 状态、审计保留字段和 GC eligibility。
2. 批量验证完整选择，不重新逐 branch 查询。
3. 在 resolution 终态后标记 eligible metadata 为 GC pending。
4. 有界回收并支持 crash/restart；保留 replay receipt 与 durable family fact。

## Acceptance

- [ ] resolution 查询在固定 budget 内完成或整体失败
- [ ] GC 不破坏 closed-conflict 精确 replay/request-drift 检查
- [ ] resolved/expired metadata 收缩，审计/provenance 保留
- [ ] GC 中断可重试，不删除媒体 staging/spool 或 live branch bytes

## Validation

- [ ] query-count/expiry/replay/GC crash tests 通过
- [ ] Rust gates 与隔离 retention smoke 通过

## Out of scope

媒体 receipt/staging 与 Android spool 生命周期由 hardening 媒体票负责。
