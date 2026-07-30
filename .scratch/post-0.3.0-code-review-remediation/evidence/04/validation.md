# 04 validation — CareLog query seam

## Fixed point and scope

- Implementation base: `d25e645`.
- Ticket-start host size after correctness 01: `CareLog.kt` 2,864 lines.
- Result: `CareLog.kt` 2,760 lines; `CareLogQueries.kt` 232 lines.
- Product behavior, public `CareLog` signatures, DAO/schema/wire contracts and PRD are unchanged.

## TDD receipt

- RED: `CareLogQueriesStructureTest` failed because `CareLogQueries.kt` did not exist.
- GREEN: all 19 named records/plans observation, day/week summary, search, recent summary/milk/notes,
  measurements and fulfillment-surface entry points delegate through the one query seam.
- The structure contract also rejects record-range SQL, search candidate selection, aggregation and surface
  filtering returning to `CareLog.kt`.

## Equivalence and regression

- `./gradlew :domain:testDebugUnitTest` — PASS; `CareLogTest` 137/137 and query structure 1/1.
- `./gradlew :feature:summary:testDebugUnitTest :feature:search:testDebugUnitTest
  :feature:widget:testDebugUnitTest :domain:lintDebug` — PASS.
- `./gradlew :app:assembleDebug` — PASS on the frozen combined structural worktree.
- Existing public-facade contracts cover half-open/DST-safe windows, overlapping/open sleep, pending/missed
  plan order, fulfillment-surface losers, search escaping/visible text and recent cross-day facts.
- `CareAggregation`, `RecordSearch`, `ConflictAuditQueries` and DAO queries remain the only rule/SQL sources;
  the move introduced no second implementation.

## Evidence boundary

- This is a domain-only structural move with no UI behavior or PRD change; it adds no device-specific claim.
- Physical phone, camera, spoken TalkBack and NAS deployment are outside ticket 04.
