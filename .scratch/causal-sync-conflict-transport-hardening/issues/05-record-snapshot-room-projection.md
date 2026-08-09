# 05 — 投影五类 root snapshot 到 Room

**What to build:** 建立 Baby、Record、CarePlan、CustomItem、WakeObservation 共用的严格 ConflictSnapshot parser、domain mapping 与非破坏性 Room schema，以 Record 作为首个原子事务 tracer。

**Blocked by:** 03、04；[`external 19`](../../repository-dedup-algorithm-audit-20260809/issues/19-resolution-metadata-retention.md)

**Status:** ready-for-agent

## Contract slice

持久化 stable/branch root/media/deleted/base/provenance、auto/conflict candidates、choice IDs、receipt 与 completeness；Compose 不解析 transport JSON。

## Implementation sequence

1. 实现 fail-closed wire parser 与 domain types。
2. 增加 Room migration，保留现有 facts/session/pending/media。
3. 在一个事务中替换单页 complete snapshot 与对应 root summary；先用 Record tracer，再对四类 root 跑 mapping table。
4. no-conflict pull 原子清除旧 snapshot/summary/receipt。

## Acceptance

- [ ] scalar、`set(null)`、nested、delete/restore 与 media 无损
- [ ] unknown/missing/type/foreign root fail closed
- [ ] 五类 root process restart 后 domain snapshot 等价
- [ ] cleanup 不留幽灵 conflict state

## Validation

- [ ] parser corpus、migration、DAO transaction 与 restart tests 通过
- [ ] Android JVM module tests 通过

## Out of scope

不实现分页、resolver UI 或 choice submit。
