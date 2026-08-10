# 05 — 投影五类 root snapshot 到 Room

**What to build:** 建立 Baby、Record、CarePlan、CustomItem、WakeObservation 共用的严格 ConflictSnapshot parser、domain mapping 与非破坏性 Room schema，以 Record 作为首个原子事务 tracer。

**Blocked by:** 03、04；[`external 19`](../../repository-dedup-algorithm-audit-20260809/issues/19-resolution-metadata-retention.md)

**Status:** implemented (review/local gates pass; real-device Room acceptance residual)

## Contract slice

持久化 stable/branch root/media/deleted/base/provenance、auto/conflict candidates、choice IDs、receipt 与 completeness；Compose 不解析 transport JSON。

## Implementation sequence

1. 实现 fail-closed wire parser 与 domain types。
2. 增加 Room migration，保留现有 facts/session/pending/media。
3. 在一个事务中替换单页 complete snapshot 与对应 root summary；先用 Record tracer，再对四类 root 跑 mapping table。
4. no-conflict pull 原子清除旧 snapshot/summary/receipt。

## Acceptance

- [x] scalar、`set(null)`、nested、delete/restore 与 media 无损
- [x] unknown/missing/type/foreign root fail closed
- [x] 五类 root process restart 后 domain snapshot 等价
- [x] cleanup 不留幽灵 conflict state

## Validation

- [x] parser corpus、production projection transaction 与 repository-restart JVM tests 通过
- [ ] real Room instrumentation reopen/rollback test 执行（已 compile；当前无 device）
- [x] Android JVM module tests 通过

## Evidence

- Base: `a5b257d2e529206e5bf42eae18d38bc22efdaa2d`; implementation is isolated in
  `/var/tmp/zhangtianshu-tmp/lezi-causal-h05` until its single-ticket commit is integrated.
- `sync.conflict` owns the closed codec, typed five-root model and atomic projection. HTTP binds the
  response `conflict_id` to the requested route; Room stores one canonical `snapshotJson` while the
  Room 27 legacy columns carry fixed sentinels. H27 still exclusively owns Room 28/schema activation.
- Public-seam TDD: identifier/token/pointer/cardinality/domain and CAS drift tests failed first, then
  passed after fail-closed validation and single-owner projection. The executed JVM projection test
  round-trips exact base/provenance, scalar/nested/`set(null)`, deleted branch, `remove`, and media
  dimensions; the real Room instrumentation test repeats exact replace/read/reopen/clear/rollback.
- Current-tree Android gates: `./gradlew test` (872 tasks), `lintDebug`, Debug/Release assembly, and
  app/core-database/sync/domain debug androidTest Kotlin compilation all passed.
- Current-tree Rust gates: `cargo fmt --all -- --check`, `cargo test --locked`, and clippy with
  `-D warnings` passed. Rust production code was not changed by H05.
- `adb devices -l` returned no devices. The real Room instrumentation test compiled but was not
  executed; no connected/device result is claimed. No NAS, image, package, push, CD, or live smoke was run.
- Review: Standards fixed point `Hard 0 / Judgement 0`; Spec fixed point
  `Hard 0 / Scope 0 / Judgement 0`. Real Room execution remains an explicit device residual rather
  than a local pass claim.

## Out of scope

不实现分页、resolver UI 或 choice submit。
