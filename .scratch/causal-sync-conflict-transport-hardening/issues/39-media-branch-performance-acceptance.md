# 39 — 验收媒体分支、慢上传与清理

**What to build:** 证明 media add merge、delete/edit branch、choice resolution、slow upload 并发和终态 cleanup 保持精确 bytes 且不阻塞家庭小提交。

**Blocked by:** 21、22、23、24、31、37、38

**Status:** implemented

## Contract slice

Cases：独立 add/add、同 media delete/edit、选择各 branch、slow large upload + concurrent small commit、resolved/abandoned cleanup。

## Implementation sequence

1. 构造 Record/Baby/CarePlan media branches 并记录 digest。
2. 经 choice-only resolver 选择各类 media candidate。
3. 慢传大对象同时提交一个小无媒体 Record。
4. 核对 client spool/server receipt/conflict metadata cleanup。

## Acceptance

- [x] selected bytes 与原 digest 完全相同
- [x] delete/edit 不静默丢媒体
- [x] 小 commit 在慢上传期间可前进
- [x] terminal cleanup 不删 pending/branched evidence

## Validation

- [x] isolated media branch/performance matrix 通过
- [x] 记录 timing/digest/cleanup receipts

## Out of scope

不运行家庭 NAS 或真实家庭照片。

## Evidence

- **HEAD:** `c5791b579e59098c42e0b791ade19d3e0c08fa78`
- **Production fix:** Baby has one avatar slot. Before the fix, the
  choice-only snapshot exposed only `/avatar_media_uuid`; selecting the
  incoming avatar rebuilt `root.avatar_media_uuid=B` while retaining media
  `[A, B]`. `validate_mutation_content` correctly returned
  `media_limit_exceeded`, which surfaced as `rejected / invalid_domain` with
  `stable_media=[]`. A counterfactual run with only the Baby snapshot media
  paths removed reproduced that exact RED. The fix emits `/media/<uuid>` Set or
  Remove candidates for the complete Baby media union, so one coherent avatar
  branch is rebuilt and the validator remains strict.
- **Fixture correction:** the Baby root has no `note` field; the H39 test now
  asserts `note` only for Record/CarePlan and keeps the Baby media assertions
  on the actual domain contract.
- **Fixture paths:**
  - `tools/lezi-sync/src/store/tests/causal_tests.rs`
  - `tools/lezi-sync/src/store/conflict_snapshots.rs`
  - `sync/src/test/kotlin/com/lezi/babylog/sync/engine/MediaBranchPerformanceAcceptanceTest.kt`
  - H38 receipt/settlement and H37 spool matrices remain the cleanup/source
    regression owners.
- **How to run Rust:**
  ```bash
  cd tools/lezi-sync
  cargo fmt --all -- --check
  cargo check --locked
  cargo test --locked
  cargo clippy --all-targets --all-features -- -D warnings
  cargo test --locked h39_media_branch_matrix -- --nocapture
  ```
- **How to run JVM acceptance/regression:**
  ```bash
  ./gradlew :sync:testDebugUnitTest --rerun-tasks \
    --tests com.lezi.babylog.sync.engine.MediaBranchPerformanceAcceptanceTest \
    --tests com.lezi.babylog.sync.media.MediaReceiptFaultAcceptanceTest \
    --tests com.lezi.babylog.sync.backend.HttpSyncBackendCausalMediaReceiptTest \
    --tests com.lezi.babylog.sync.engine.CausalMediaSettlementJournalTest \
    --tests com.lezi.babylog.sync.media.MediaSourceSpoolFaultAcceptanceTest \
    --tests com.lezi.babylog.sync.media.ImmutableMediaSpoolTest
  ```
- **Result:** Rust H39 matrix 1/1; full Rust suite 286 lib + 165 API + 1
  contract + 3 TLS, all green; Clippy and format/check green. The fresh JVM
  run was 40/40: H39 1, H38 receipt 11 + HTTP 2 + journal 5, and H37 spool
  10 + immutable spool 11.
- **Case coverage:**

  | Case | Seam | Evidence |
  |---|---|---|
  | Record/Baby/CarePlan independent add/add | Store causal commit + choice-only resolution | Record/CarePlan merge both media; Baby branches, selects one avatar candidate, and preserves selected bytes exactly |
  | Record/Baby/CarePlan same-media delete/edit | Store causal commit + choice-only resolution | All three branch; selecting edit preserves the shared media UUID and exact stored bytes, without silent deletion |
  | Slow large upload + small commit | `ReplicaSyncEngine` + shared `RecordingSyncBackend` | 256 KiB upload is held after spool entry; independent media-free Record commits within 1 s; upload order and SHA-256 remain exact; accepted terminal discards spool |
  | Server slow stream + small commit | real isolated Axum/SQLite Router | `slow_causal_media_prepare_streams_to_temp_without_blocking_a_small_commit` holds a partial streamed body while a small same-family commit completes within 500 ms; `causal_media_promotion_runs_outside_the_same_family_commit_lock` separately blocks promotion while another same-family commit completes within 500 ms |
  | Pending/branched retention and terminal cleanup | H38 settlement journal/receipt + H37 spool regressions | Pending/CommitUnknown/branched remain retained; accepted terminal cleanup is durable and idempotent; source-once/restart evidence remains green |

- **Isolation:** Rust uses `TempDir` SQLite/media roots; JVM uses in-memory
  test doubles and isolated spool fixtures. No family NAS, production CD,
  production certificates, or real family photos were used.
- **Final focused rerun (2026-08-13, committed `27fe6cae`):** both real-server
  critical-section tests above passed individually from `/tmp/lezi-h43-cargo-target`; this closes
  the server-critical-section evidence independently of the JVM timing fixture.
- **Residuals:** no ADB/device run was performed in this turn, so
  Room/process-death instrumentation remains a device residual owned by
  H41/H18/H20. H39 is locally implemented and accepted at Rust and JVM
  seams; H40–H43 remain open.
