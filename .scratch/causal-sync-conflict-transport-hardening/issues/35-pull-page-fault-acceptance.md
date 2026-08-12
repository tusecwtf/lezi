# 35 — 验收 gzip、分页与 cursor 原子性

**What to build:** 用 fault proxy 证明 truncated/corrupt/over-budget gzip 与 duplicate/skipped/non-monotonic pull pages 不会推进 cursor 或写入 partial facts。

**Blocked by:** 16、31

**Status:** implemented

## Contract slice

Cases：identity/gzip happy path、truncated JSON、corrupt gzip、decoded bomb、item/page over budget、重复/跳页/倒退 continuation、Room transaction crash。

## Implementation sequence

1. 建立上述固定 response fixtures。
2. 逐例运行 decode/budget/continuation validation。
3. 在 page Room transaction 前后注入 crash。
4. 恢复后比较 facts/cursor/continuation。

## Acceptance

- [x] 坏页整体 fail closed，无 partial cursor/facts
- [x] committed page crash recovery 单调且不跳事实
- [x] gzip/identity stable projection 等价

## Validation

- [x] fixed fault case table 通过
- [x] 记录 encoded/decoded/count/page receipts

## Out of scope

不覆盖 ConflictSnapshot pagination 或 retry jitter。

## Evidence

- **HEAD:** _(pinned after commit)_
- **Schema / release pins:** server schema `13`; Android `0.4.0` / versionCode `21`; Room `28`; local-data contract `5`; protocol floor `21`
- **Deterministic seed:** `H35_FAULT_SEED = 0x351631` (`0x35_16_31L`)
- **Frozen budgets (H16):** entities `200`; encoded `9 MiB`; decoded `8 MiB`; pages `500`
- **Fixture path:**
  - `sync/src/test/kotlin/com/lezi/babylog/sync/backend/PullPageFaultAcceptanceTest.kt`
  - H16 anchors: `HttpSyncBackendPullWireTest`, `ReplicaSyncEnginePullCheckpointTest`
  - device Room residual: `sync/src/androidTest/.../PullCheckpointRoomReplayTest.kt`
- **How to run:**
  ```bash
  ./gradlew :sync:testDebugUnitTest \
    --tests com.lezi.babylog.sync.backend.PullPageFaultAcceptanceTest \
    --tests com.lezi.babylog.sync.backend.HttpSyncBackendPullWireTest \
    --tests com.lezi.babylog.sync.engine.ReplicaSyncEnginePullCheckpointTest
  ```
- **Case coverage table:**

  | Case ID | Kind | Seam | Assert / receipts |
  |---|---|---|---|
  | `C0-seed-budget-table` | pin | unit | seed `0x351631`; budget 200 / 9MiB / 8MiB / 500 |
  | `C1-identity-happy` | happy | HTTP loopback | cursor=7; page=0; entity `baby-h35`; encoded=decoded; `Accept-Encoding: identity` |
  | `C2-gzip-happy` | happy | HTTP loopback | gzip projection ≡ identity (cursor/entities/generation/family); encoded < decoded; `Accept-Encoding: gzip` |
  | `C3-truncated-json` | fail-closed | HTTP loopback | truncated body never yields `PullResult`; no parse success |
  | `C4-corrupt-gzip` | fail-closed | HTTP loopback | `IllegalArgumentException` contains `gzip` before JSON parse |
  | `C5-decoded-bomb` | fail-closed | HTTP loopback | `SyncResponseTooLargeException` kind `pull decoded JSON` (decoded cap 128) |
  | `C6-item-over-budget` | fail-closed | HTTP loopback | `item 上限` with maxEntities=1 and 2 entities |
  | `C7-encoded-over-budget` | fail-closed | HTTP loopback | `pull encoded JSON` with/without `Content-Length`; cap 128 |
  | `C8-duplicate-page-index` | fail-closed | engine checkpoint | page1 claims page_index=0; cursor stays 5; first baby kept; `baby-dup` absent |
  | `C9-skipped-page` | fail-closed | engine checkpoint | page_index=1 on request 0; cursor stays 4; no remote baby |
  | `C10-non-monotonic` | fail-closed | engine checkpoint | backward cursor → `倒退`; stalled has_more → `cursor 未推进`; cursor unchanged |
  | `C11-committed-page-recovery` | monotonic | engine checkpoint | page2 unknown entity rolls back apply; cursor=1; retry pullCursors `0,1,1`; final cursor=2; first baby retained |

- **Related green:** H16 `HttpSyncBackendPullWireTest` + `ReplicaSyncEnginePullCheckpointTest` re-run with the matrix (0 failures).
- **Isolation:** no NAS/production certs; loopback HTTP + in-memory engine rig only.
- **Residuals:** real Room page-transaction crash/reopen remains
  `PullCheckpointRoomReplayTest` device instrumentation (ADB devices = 0). JVM C11 proves
  checkpoint monotonicity and no fact skip on the engine owner; H16 already shipped the
  Room instrumentation source.
