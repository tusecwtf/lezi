# 10 — 让无媒体 Record 使用 frozen commit-first

**What to build:** 以无媒体 Record tracer 持久冻结 canonical request、mutation ID、base、`contentEpoch` 与 hash，并由 LocalWrite 直接执行一次幂等 commit。

**Blocked by:** 05

**Status:** ready-for-agent

## Contract slice

Room product fact 仍是领域真相；durable envelope 是该 pending mutation 的唯一传输真相。“无媒体”指完整 frozen media manifest 为空，而不是本次无 media diff。accepted/merged/branched 共用 settlement；精确 replay 先保证原终态幂等，显式 replay marker 由票 25 的 response contraction 加入。无 reconcile/pull/cursor move。

## Implementation sequence

1. 在一个 Room 事务冻结 envelope 与 identity/hash。
2. LocalWrite 直接 commit 并删除此 slice 的 reconcile。
3. 三类终态进入一个 settlement transaction，并以 `contentEpoch` CAS 避免覆盖后续本地编辑。
4. 丢响应/进程死亡后重发同一 envelope；旧终态后若 Room 已是新 epoch，保留新 dirty fact 并冻结下一 mutation。

## Acceptance

- [ ] 一次用户动作只有一个 mutation/hash/contentEpoch
- [ ] no reconcile、no pull、cursor unchanged
- [ ] fact 后续变化不改写旧 envelope；旧 settlement 不覆盖新 epoch，且新内容重新排队
- [ ] lost response replay 不重复版本，payload drift 被拒绝

## Validation

- [ ] DAO/engine/recording-backend public-seam tests 通过
- [ ] 隔离真实 server 无媒体 Record smoke 通过

## Out of scope

不迁移其他 roots；已有/冻结 manifest 非空的 Record 留在旧安全路径直到票 21。
