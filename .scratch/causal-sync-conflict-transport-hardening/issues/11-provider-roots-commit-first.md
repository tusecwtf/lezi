# 11 — 迁移 Baby 与 CustomItem commit-first

**What to build:** 将无媒体 Baby、CustomItem 及其不含媒体 membership 的 tombstone 迁移到 frozen commit-first，作为 dependent roots 的 provider 前置。

**Blocked by:** 10

**Status:** ready-for-agent

## Contract slice

Eligibility 是完整 frozen media manifest 为空；不是“本次无 media diff”。保留排序、身份和引用语义；带 avatar 的 Baby 留在旧安全路径直到票 22。每个 eligible root 使用同一 envelope/settlement。

## Implementation sequence

1. 为 Baby 与 CustomItem 冻结 canonical envelope。
2. 接入 dependency provider ordering 与 commit-first。
3. 统一 live/tombstone terminal/retryable settlement。
4. 删除两类无媒体 reconcile path。

## Acceptance

- [ ] 两类 eligible live/tombstone 无 reconcile、no pull、cursor unchanged
- [ ] referenced IDs 与 local ordering 不变
- [ ] lost response/replan/payload drift 正确

## Validation

- [ ] 两类 engine/DAO/domain matrix 通过
- [ ] 隔离真实 server 最小 smoke 通过

## Out of scope

不发表 manifest 非空的 Baby、CarePlan 或 WakeObservation。
