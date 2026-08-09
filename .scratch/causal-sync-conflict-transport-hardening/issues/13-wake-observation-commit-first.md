# 13 — 迁移 WakeObservation commit-first

**What to build:** 将 WakeObservation 及 tombstone 迁移到 frozen commit-first，保留 Record source relation、事实语义与 aggregation 行为。

**Blocked by:** 11

**Status:** ready-for-agent

## Contract slice

WakeObservation 是已发生事实，不是计划；本票只替换 transport path，不改变 effective Wake、overlap 或统计规则。

## Implementation sequence

1. 冻结 Wake canonical envelope 与 Record source identity。
2. 在 source dependency 满足后 commit-first/replan。
3. 统一 live/tombstone settlement 与 terminal errors。
4. 删除 Wake reconcile path。

## Acceptance

- [ ] source relation 不丢失且无缺引用 commit
- [ ] effective Wake/aggregation/domain projection 不变
- [ ] lost response、branch、tombstone 与 contentEpoch CAS 正确

## Validation

- [ ] Wake domain/engine/DAO matrix 通过
- [ ] isolated real-server smoke 通过

## Out of scope

不改变 Wake 产品语义或汇总 bounds。
