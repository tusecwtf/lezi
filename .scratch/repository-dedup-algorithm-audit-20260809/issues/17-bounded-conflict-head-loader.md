# 17 — 批量加载有界 conflict heads

Status: ready-for-agent

Priority: P1

Audit baseline: `38cfbe7dbcf1f410237dce77b50ec1bd4854d8a6`

Blocked by: 12；以及 [`causal hardening 01`](../../causal-sync-conflict-transport-hardening/issues/01-freeze-conflict-v2-contract.md)。

## What to build

提供一个按固定 statement/query budget 批量加载完整 branch、direct base、version、media 和 provenance 的 Store 原语，消除 detail/resolution 的逐 branch N+1。

## Implementation sequence

1. 冻结输入 root/branch-set 与输出完整性、不存在/不完整错误。
2. 以固定批次装载 heads、direct bases、media 和 mutation provenance。
3. 在 Store façade 下复用同一 loader，handler 不自行查询。
4. 对高基数、缺失 parent 与并发 branch 变化 fail closed。

## Acceptance

- [ ] statement count 随固定批次有界，不随 branch 数形成 N+1
- [ ] root/media/deleted/base/provenance 完整或整体失败
- [ ] 已 durable branch 在准入饱和后仍可读取

## Validation

- [ ] high-cardinality/query-count/completeness tests 通过
- [ ] Rust gates 与隔离 Store smoke 通过

## Out of scope

不定义分页、snapshot receipt、choice ID 或 merge 语义。
