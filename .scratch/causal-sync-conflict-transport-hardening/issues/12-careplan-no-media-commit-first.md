# 12 — 迁移无媒体 CarePlan commit-first

**What to build:** 将完整 frozen media manifest 为空的 CarePlan 及 tombstone 迁移到 commit-first，保留 Baby/CustomItem/fulfilled Record dependency、fulfillment 与 replan。

**Blocked by:** 11

**Status:** ready-for-agent

## Contract slice

带 attachment 的 CarePlan 留在旧安全路径直到票 23；不重开事实/未来意图、fulfillment authority 或 source relation。

## Implementation sequence

1. 冻结 CarePlan canonical envelope 与依赖集合。
2. 在 providers/fulfilled facts settled 后 commit-first/replan。
3. 统一 live/tombstone settlement 与 terminal errors。
4. 删除 eligible CarePlan reconcile path。

## Acceptance

- [ ] dependency/replan 不产生缺引用 commit
- [ ] fulfillment/source relation 结果不变
- [ ] lost response、branch、tombstone 与 contentEpoch CAS 正确

## Validation

- [ ] CarePlan domain/engine/DAO dependency matrix 通过
- [ ] isolated real-server no-media smoke 通过

## Out of scope

不迁移 attachment 或改变 CarePlan 产品语义。
