# 34 — 验收 timeout、丢响应与退避

**What to build:** 用确定性 fault proxy 证明 connect/response timeout、commit/resolution lost response、429/503、Retry-After 与 full jitter 遵守票 15 预算且不重复事实。

**Blocked by:** 15、31

**Status:** implemented

## Contract slice

只覆盖请求/重试层；使用固定 clock/random seed，分别验证 handshake、commit、resolution 与 media-prepare 预算类别。

## Implementation sequence

1. 脚本化 timeout、post-durable disconnect、429/503 faults。
2. 运行 commit/resolution lost-response replay。
3. 运行合法/非法 Retry-After 与 jitter boundary。
4. 验证 budget exhaustion 后 pending/terminal 状态。

## Acceptance

- [x] durable result replay 无 duplicate/version drift
- [x] delay/attempt/elapsed 符合预算表
- [x] non-idempotent 不盲重试
- [x] UI/telemetry 诚实且不泄漏内容

## Validation

- [x] deterministic fault matrix 通过并记录 seed
- [x] Android/Rust relevant tests 通过

## Out of scope

不覆盖 gzip/page/resource saturation。

## Evidence

- **HEAD:** _(pinned after commit)_
- **Schema / release pins:** server schema `13`; Android `0.4.0` / versionCode `21`; Room `28`; local-data contract `5`; protocol floor `21`
- **Deterministic seed:** `H34_FAULT_SEED = 0x341531` (`0x34_15_31L`)
- **Fixture path:**
  - `sync/src/test/kotlin/com/lezi/babylog/sync/backend/retry/RetryLostResponseAcceptanceTest.kt`
  - production parity: `SyncBackend.withForegroundRetryPolicy()` + CareLog seam wiring in
    `domain/.../CareLogRealServerSeamSupport.kt`
  - H15 anchors: `RetryingSyncBackendTest`, `HttpSyncBackendRetryFaultTest`
- **How to run:**
  ```bash
  ./gradlew :sync:testDebugUnitTest \
    --tests com.lezi.babylog.sync.backend.retry.RetryLostResponseAcceptanceTest \
    --tests com.lezi.babylog.sync.backend.retry.RetryingSyncBackendTest \
    --tests com.lezi.babylog.sync.backend.retry.HttpSyncBackendRetryFaultTest
  # production-parity seam still green under retry decorator:
  (cd tools/lezi-sync && cargo build -p lezi-sync)
  ./gradlew :domain:testDebugUnitTest \
    --tests com.lezi.babylog.domain.CareLogRealServerSeamTest
  ```
- **Case coverage table:**

  | Case ID | Kind | Seed / clock | Assert |
  |---|---|---|---|
  | `C0-seed-budget-table` | pin | `0x341531` | handshake/detail `3s/10s/3/30s`; pull/commit/resolution `3s/20s/3/60s`; media-prepare `5s/90s/3/240s` |
  | `C1-commit-lost-response` | post-durable drop | random=0 | same mutation body twice; `replay=true`; stable version unchanged; Transport event delay 0 |
  | `C2-resolution-lost-response` | post-durable drop | random=0 | same resolve command twice; Accepted `replay=true`; Transport event |
  | `C3-connect-timeout` | SocketTimeout on connect | random=0 | handshake connect/response budgets; Timeout retry then ready |
  | `C4-response-timeout-commit` | SocketTimeout on read | random=0 | commit connect 3s / response 20s; recovers; elapsed < 60s |
  | `C5-valid-retry-after` | 429/503 + seconds | seed documented | handshake/pull/commit/resolution/media-prepare honor header; no jitter; content-free events |
  | `C6-illegal-retry-after-jitter` | invalid header | bound-1 | ceilings 1001/2001 → delays 1000/2000; 3 attempts |
  | `C7-budget-exhaustion` | persistent IOException | random=0 | exactly 3 commit attempts; terminal transport failure; 2 delay events |
  | `C8-elapsed-rejects-retry-after` | remaining 500ms | n/a | Retry-After 1s does not sleep; single attempt |
  | `C9-non-idempotent` | source-relation declare | n/a | 503 once only; zero events/delays |
  | `C10-loopback-429-commit` | real loopback HTTP | n/a | Retry-After 1s then `replay=true`; Throttled telemetry content-free |
  | `C11-idempotent-owner-surface` | source pin | n/a | six `SyncRetryOperation` tokens only; no declare/ownerLogin overrides |

- **Production parity:** CareLog real-server seam now wraps `HttpSyncBackend` with
  `withForegroundRetryPolicy()` matching `SyncModule` so H31+ acceptance includes the H15 owner.
- **Telemetry:** `SyncRetryEvent` carries only operation/category/attempt/delay — no family content.
- **Related green:** H15 retry unit + fault suites; H31 `CareLogRealServerSeamTest` under retry decorator.
- **Isolation:** no NAS/production certs; loopback + injectable `HttpURLConnection` only.
- **Residuals:** gzip/page faults remain H35; resource saturation H36; device instrumentation not required for this transport matrix.
