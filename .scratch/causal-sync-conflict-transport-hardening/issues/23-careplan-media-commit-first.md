# 23 — 迁移 CarePlan attachments commit-first

**What to build:** 将 CarePlan 的 0–3 attachments/membership 迁移到 spool/receipt commit-first，同时保留 Baby/CustomItem/fulfilled fact dependencies 与 DAO settlement。

**Blocked by:** 22

**Status:** ready-for-agent

## Contract slice

一个 CarePlan mutation 的全部 attachments 作为一个 frozen media group；部分 prepare 不可发表，replan 保留同一 contentEpoch 意图。

## Implementation sequence

1. 收集 0–3 attachments 并冻结 group envelope。
2. 等待 dependencies 与全部 receipts 后一次 commit。
3. 统一 terminal/branch settlement 与 cleanup。
4. 删除 CarePlan media reconcile path。

## Acceptance

- [ ] 0/1/3 attachment 与 partial prepare 行为明确
- [ ] dependency/replan/lost response 不重复版本或上传
- [ ] branch/tombstone 保留精确 attachment bytes

## Validation

- [ ] CarePlan engine/DAO/server matrix 通过
- [ ] isolated multi-media byte-equality smoke 通过

## Out of scope

不改变 CarePlan 产品语义。
