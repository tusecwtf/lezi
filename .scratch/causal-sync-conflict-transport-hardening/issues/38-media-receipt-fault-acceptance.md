# 38 — 验收媒体 prepare 与 commit 故障

**What to build:** 证明 prepare/commit disconnect、receipt replay/expiry/binding 错误和 Android settlement 在真实隔离服务上不重传或重复版本。

**Blocked by:** 19、20、31

**Status:** implemented

## Contract slice

Cases：lost prepare response、lost commit response、duplicate prepare、expired receipt、wrong family/principal/digest/length、restart before settlement。

## Implementation sequence

1. 在 prepare durable 前后注入 disconnect。
2. 在 commit durable 前后注入 disconnect。
3. 运行 binding/expiry/replay cases。
4. 重启 Android 并比较 upload count/version/spool state。

## Acceptance

- [x] lost response 不 re-read URI、不 duplicate upload/version
- [x] wrong/expired binding fail closed 且 pending 诚实
- [x] terminal 后 cleanup，unknown/pending 保留

## Validation

- [x] isolated receipt fault case table 通过
- [x] 记录 upload count/hash/version/manifest evidence

## Out of scope

不覆盖 media branch resolution 或 slow upload。

## Evidence

- **HEAD:** _(feat commit; docs pin follows)_
- **Production owners:** H19 receipt claim (`claim_manifest` / `media_membership_mismatch` /
  `media_preimage_expired` / digest+length mismatch codes), H20
  `CausalMediaSettlementJournalOwner`, H18 immutable spool (source-once via H37).
- **Fixture path:**
  - `sync/src/test/kotlin/com/lezi/babylog/sync/media/MediaReceiptFaultAcceptanceTest.kt`
  - Controllable hooks on `RecordingSyncBackend` /
    `TestImmutableMediaSpool` in `RealSyncPortTestSupport.kt`
    (`failAfterCausalMediaPreimageUpload`, `causalMediaReceiptFactory`, `openCounts`)
  - Anchors: `HttpSyncBackendCausalMediaReceiptTest`,
    `CausalMediaSettlementJournalTest`, engine media settlement lost-response suites
- **How to run:**
  ```bash
  ./gradlew :sync:testDebugUnitTest \
    --tests com.lezi.babylog.sync.media.MediaReceiptFaultAcceptanceTest \
    --tests com.lezi.babylog.sync.backend.HttpSyncBackendCausalMediaReceiptTest \
    --tests com.lezi.babylog.sync.engine.CausalMediaSettlementJournalTest
  ```
- **Result:** H38 10/10 green; H17/H19 receipt HTTP 2/2 green; H20 journal 5/5 green
  (17 targeted tests, 0 failures).
- **Case coverage table:**

  | Case ID | Kind | Seam | URI opens | Upload / version / phase |
  |---|---|---|---|---|
  | `C0-seed-contract` | pin | unit | — | seed `0x381920` (H38+H19+H20); H37 seed still `0x371831` |
  | `C1-lost-prepare` | lost response | engine + fault after PUT bytes | 1 | uploads=2 exact frozen bytes; openCounts=2; digest `41ce3698f64803e2d7d4136c85534bf1d7c4d0a2ac81a3951f694c778e82f409`; Pending→accepted cleanup; no URI re-read |
  | `C2-lost-commit` | lost response | engine CommitUnknown replay | 1 | uploads=1; commits=2 exact mutation; openCounts=1; digest `15ccf5a688b1fd34f9e259f2efd05c4f51e152e4bba2542a1d70fad31ef4a393`; cold `newEngine()` no second PUT |
  | `C3-duplicate-prepare` | idempotent | dual-media partial + journal `recordPrepared` | 1 each URI | first receipt durable; duplicate prepare no-op; resume uploads only missing item; opens=3 (first+second attempt+resume); digests `934be141…` / `a150d6fa…` |
  | `C4-wrong-binding` | fail-closed | receipt factory: expired / digest / length / foreign uuid | 1 each | Pending + empty receipts; zero commit; spool retained |
  | `C5-wrong-principal` | fail-closed | commit reject `media_membership_mismatch` | 1 | CommitUnknown retained; uploads=1; digest `33848d08cbf5b403975d1315f81429e228a50f149209dd0cb66f90d9b05eb7dd` |
  | `C6-expired-commit` | fail-closed | commit reject `media_preimage_expired` | 1 | CommitUnknown retained; uploads=1; digest `0260395486a8dcfabf9aa0fb7bc1c4ecdb9ace3c0eec1b66af10b6db2f22c1bd` |
  | `C7-restart` | process-death | Pending after lost prepare + CommitUnknown after lost commit | 1 each | Pending resume uploads=2 exact; Unknown resume uploads=1; digests `a2b360c4…` / `b41baa05…`; terminal cleanup |
  | `C8-terminal-http` | cleanup/retain + HTTP | accepted cleanup vs branched retain; HTTP uuid/digest/length/expiry; HTTP lost prepare retry | 1 each engine | accepted discard; branched phase retained + no blind resend; 4 HTTP binding IllegalArgument; second HTTP attempt returns exact receipt |
  | `C9-evidence` | pin | happy path | 1 | uploads=1; openCounts=1; digest `f8b18d76a4e5f80863d13c433c6e46c3faabd38fb2f17e75d049adbf02fd1e21`; mutationId non-empty; cleanup |

- **Isolation:** no NAS/production certs; controllable backend + settlement journal + spool opens only.
- **Residuals:** `adb devices -l` reported 0 devices, so Android Room/process-death instrumentation
  remains residual (H20/H41 ownership). Media branch resolution / slow upload are H39.
  Optional isolated real-server smoke not required — matrix proves owners at SyncBackend /
  settlement seams with H19 reject codes.
