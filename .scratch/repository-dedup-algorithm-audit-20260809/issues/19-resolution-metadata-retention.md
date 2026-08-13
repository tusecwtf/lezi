# 19 — 限制 resolution 查询并回收冲突 metadata

Status: implemented (review/gates pass)

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

- [x] resolution 查询在固定 budget 内完成或整体失败
- [x] GC 不破坏 closed-conflict 精确 replay/request-drift 检查
- [x] resolved/expired metadata 收缩，审计/provenance 保留
- [x] GC 中断可重试，不删除媒体 staging/spool 或 live branch bytes

## Validation

- [x] query-count/expiry/replay/GC crash tests 通过
- [x] Rust gates 与隔离 retention smoke 通过

## Evidence

- `Store` resolution 在 1/64 branch 下使用同一 SQL statement budget（≤32），选择在一次已加载的
  bounded snapshot 上批量验证；100,000 条 eligible resolution/choice receipt 历史通过 due-marker
  range index 取固定 batch，query plan 不创建临时排序树。
- 24 小时 grace 后，单一 `conflict_retention` owner 先持久标记、再以 8-conflict batch 回收已关闭
  branch rows/version metadata 与 snapshot choice receipt；mark 后崩溃、并发 sweep 与重启均可重试。
- terminal resolution receipt、resolver/branch-set audit、resolved stable version、direct-base chain、
  provenance 以及 media staging/publication 保留；缺失或损坏的 choice material fail closed。
- Standards `0/0`、Spec `0/0/0`；targeted retention 5/5（100,000 eligible receipt fixture
  19.21 秒）、1/64 query budget、长期运行 Router replay/GC fault/retry 与真实 restart smoke 通过。
- `cargo fmt --all -- --check`、`cargo test --locked`（272 lib + 185 API + 1 shared corpus；TLS
  loopback 两例在允许 bind 的隔离环境重跑 2/2）、`cargo clippy --all-targets --all-features --
  -D warnings` 与 `git diff --check` 通过。
- 无 Android source/wire diff；Android/adb/device gates 未运行且不作为本票验收证据。未运行
  NAS/image/package/push/CD。

## Out of scope

媒体 receipt/staging 与 Android spool 生命周期由 hardening 媒体票负责。
