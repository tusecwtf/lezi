# US-087 media lifecycle fixture: budgets and evidence limits

`tools/lezi-sync/tests/media_metadata_lifecycle.rs` exercises validation,
commit/merge/resolution/replay, exact media bytes, process restart/readiness,
and an unchanged schema. Its original universal curl `--connect-timeout 1
--max-time 5` was a fixture-local fail-fast pressure guard, introduced in
`e0ee5ff`; it did not come from a product latency SLO. US-087 does not prescribe
a five-second response SLO. This correction changes the test transport guards,
not server performance, Android policy, HTTP assertions, or retry behavior.

## Endpoint-specific source of the numbers

The H15 table in [causal-sync-wire.md §1.3](../spec/contracts/causal-sync-wire.md#13-前台传输重试h15)
(lines 83–105 at `ea266550`) is authoritative for commit/detail/resolution and
H17 media preparation. Its implementation is
`sync/src/main/kotlin/com/lezi/babylog/sync/backend/retry/SyncRetryPolicy.kt:47–53`.
Other endpoint values come from
`sync/src/main/kotlin/com/lezi/babylog/sync/backend/deadline/FamilyHttpBudget.kt`.
Do not substitute the session 12s cap or the full-cycle 120s cap for H15.

All numbers below are seconds. The read column is the Android configuration,
not a curl total-transfer limit. Routing line numbers are for `ea266550`;
`HttpSyncBackend` means the file in `sync/.../backend/`.

| Fixture request | Android operation | Connect | Read | Operation elapsed | Routing source |
|---|---|---:|---:|---:|---|
| GET `/v1/setup-status` | Probe | 3 | 5 | 8 | `session/TrustedEndpoint.kt:275–307,470` |
| POST `/v1/family/create` | Session | 3 | 8 | 12 | `HttpSyncBackend.kt:416–435` |
| POST `/v1/member/requests` | SessionWrite | 3 | 8 | 12 | `HttpSyncBackend.kt:609–626` |
| POST `/v1/member/requests/{id}/approve-new` | SessionWrite | 3 | 8 | 12 | `HttpSyncBackend.kt:717–723,1542–1557` |
| POST `/v1/member/requests/claim` | Session | 3 | 8 | 12 | `HttpSyncBackend.kt:673–693` |
| POST `/v1/causal/commit` | H15 Commit | 3 | 20 | 60 | `HttpSyncBackend.kt:862–872` |
| GET `/v1/conflicts/{id}` | H15 ConflictDetail | 3 | 10 | 30 | `HttpSyncBackend.kt:969–977` |
| POST `/v1/conflicts/{id}/resolve` | H15 Resolution | 3 | 20 | 60 | `HttpSyncBackend.kt:1017–1023` |
| PUT `/v1/causal/media/{id}` | H17 MediaPrepare | 5 | 90 | 240 | `HttpSyncBackend.kt:1094–1113` |
| GET `/v1/media/{id}` | MediaGet | 3 | 8 | 30 | `HttpSyncBackend.kt:1452–1458` |

`FamilyHttpBudget.kt:35–43,56–76,88–96` defines Probe, Session/SessionWrite,
and MediaGet respectively. Owner resolve, member forbidden controls and explicit
idempotency replay assertions use the same resolution budget. Unknown fixture
endpoints fail closed instead of inheriting a broad default.

## Transport semantics: what curl can and cannot reproduce

The production factory calls `URL.openConnection() as HttpURLConnection`
(`HttpSyncBackend.kt:109–118,176–191`). Setup uses `HttpsURLConnection`
(`TrustedEndpoint.kt:294–295`). There is no app-configured OkHttpClient
`callTimeout` in these paths; a particular Android runtime's underlying
implementation is not established by this source audit.

`HttpSyncBackend.kt:2279–2290` assigns the response value to `readTimeout`.
[Android's URLConnection API](https://developer.android.com/reference/java/net/URLConnection#setReadTimeout(int))
defines this as a wait for data during a read, rather than a total call duration.
An independent elapsed watchdog bounds the operation at
`HttpSyncBackend.kt:2020–2055`. The H15 operation deadline is shared by up to
three attempts in production; this fixture still performs only one attempt per
explicit call. Session request write-stall bounds (5s) and media upload
write-stall bounds (30s) are separate Android mechanisms.

The test's `support/media_lifecycle_budget.rs` uses the table as follows:

- `--connect-timeout` uses the connect number. Curl includes DNS and TCP/TLS
  establishment in this phase, so this is not exact Android transport parity.
- `--speed-limit 1 --speed-time <read>` is a **low-transfer-rate guard** derived
  from the read number. Curl measures transfer progress in both directions; it
  does not reset a socket timer on each read and does not reproduce the separate
  Android write-stall watchdogs. Slow trickle/upload progress can behave
  differently. Passing this fixture is not proof of Android read-timeout policy.
- `--max-time` uses the operation elapsed ceiling as an outer single-transfer
  fixture guard. For resolve this is 60s, never a relabeling of the 20s read
  timeout. Curl's timer is not an exact wall-clock limit for process spawning,
  scheduling or cleanup, and it does not exercise the production retry budget.
- There is no `--retry`. `--disable` is the first curl option, so personal
  curl configuration cannot silently introduce retries or override the fixture.

These curl distinctions are documented in the official
[connect-timeout](https://curl.se/docs/manpage.html#--connect-timeout),
[speed-time](https://curl.se/docs/manpage.html#--speed-time), and
[max-time](https://curl.se/docs/manpage.html#--max-time) references. Functional
boundary acceptance and exact Android timeout-policy tests are different proofs.

## Startup deadline correction

The process-lifecycle startup window remains 15s. Internal HTTP `/ready` retains
its 1s connect / 2s transfer probe bounds, shortened to the remaining startup
window. This is not Android's trusted-origin Probe path. Public HTTPS
`/v1/setup-status` uses its Probe budget, also shortened to the startup remainder.
The loop checks the deadline before dispatch, after `/ready` returns, and after
successful setup validation. Exactly-at-deadline and late success both fail.
Previously only an unsuccessful ready branch rechecked the deadline, allowing
a late ready/setup success to escape the 15s guard. The pure boundary regression
uses supplied instants without sleeping, starting a process, or using a socket.
Both ready and setup run through that same before/after-checking helper, and its
regression supplies an operation that returns success exactly at/after expiry.
Curl timeout arguments are floored to whole milliseconds; a remainder below
1ms fails closed instead of becoming curl's unlimited zero timeout.
This is a deterministic test-harness defect, not evidence explaining the old
resolution stall or a production startup performance fix.

## Preserve the previous evidence

The independent samples remain separate:

- Original member-resolution fixture failure exposed an incorrect test role;
  owner-only resolution remains enforced and the member refusal is a negative
  control.
- The earlier 1s HTTPS connection timeout remains a failure, with no established
  cause.
- The `1dc1778` full Rust run failed under its original 5s transfer guard during
  a care-plan resolution: DNS 0.000395s, TCP 0.001230s, TLS 0.459149s, total
  5.556349s, zero response bytes, server child still running. Complete original
  log: `backend-acceptance-clean-rust-tests.log`, SHA-256
  `0db7efc7746be2402f3ee6b67d35d17159471fd80c0e45e2401b638183f2ad37`.
- The exclusive `ed92af3` diagnostic later passed with the same original guards.
  Its completed response-creation spans cannot explain the failed sample, and
  do not distinguish authentication, mutex, blocking-pool, SQLite or file work.

The exact original 5s cause is still unlocated. A pass after this budget
correction must not be described as a performance fix, a reproduced/closed
stall, an Android timeout-equivalence proof, or a retroactive green full run.

The server has a 300s HTTP timeout (`tools/lezi-sync/src/lib.rs:80,1036–1042`)
and a 10s SQLite busy handler (`src/store/mod.rs:604,616`). Neither promises a
5s response SLO, nor establishes that the failed request actually waited on
SQLite. The resolution path awaits a family mutex and `run_blocking`
(`src/handlers/sync.rs:758–772`), enters an IMMEDIATE transaction
(`src/store/causal.rs:3535–3547`), commits and promotes consumed media before
returning (`:4002–4003`). The best-effort retention task is not awaited, but its
own IMMEDIATE transaction (`src/store/conflict_retention.rs:484–504`) can still
contend with a later writer. These are candidate waiting stages, not findings
that any one caused the timeout. No production locking, durability, ACL, schema,
or timeout setting changes in this test correction.
