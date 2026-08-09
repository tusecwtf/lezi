# 20 — 原子结算 Android media mutation

**What to build:** 将 receipt、mutation、pending fact 与 spool manifest 在一个 Android settlement 状态机中收敛，terminal 后安全清理，pending/branched 保留。

**Blocked by:** 18、19

**Status:** ready-for-agent

## Contract slice

Accepted/merged terminal 可删除 spool；branched/pending/unknown 保留；explicit abandon 只有在 mutation 未 durable commit 且用户确认后才 eligible。

## Implementation sequence

1. 将 receipt 状态持久绑定 mutation/manifest。
2. 统一 accepted/merged/branched/replay settlement。
3. 在 terminal transaction 后标记 spool cleanup。
4. 重启时恢复 unknown/pending 并先 replay commit。

## Acceptance

- [ ] terminal cleanup 不早于 durable settlement
- [ ] branched/pending/unknown bytes 永不误删
- [ ] lost response/process death 不 re-upload
- [ ] explicit abandon 不清已 durable mutation

## Validation

- [ ] DAO/engine/crash/cleanup state-machine tests 通过
- [ ] Android JVM media settlement tests 通过

## Out of scope

不迁移具体 media roots 或 server conflict metadata GC。
