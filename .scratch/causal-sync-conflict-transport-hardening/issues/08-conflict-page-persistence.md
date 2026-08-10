# 08 — 持久化冲突分页完整性

**What to build:** 让 Android 按 external 18 合同有界加载并持久化 snapshot pages，只有完整、无重复、无跳页的 snapshot 才标记 complete。

**Blocked by:** 05；[`external 18`](../../repository-dedup-algorithm-audit-20260809/issues/18-conflict-snapshot-receipt-pagination.md)

**Status:** implemented (review/local gates pass; device Room residual)

## Contract slice

每页保存 receipt、continuation、page ordinal、count/byte evidence；resolver 只读 complete snapshot。异常页不污染现有 complete cache。

## Implementation sequence

1. 扩展 Room page/completeness 状态与恢复查询。
2. 校验 receipt、continuation 单调性、重复/跳页和预算。
3. 在逐页事务中暂存，最后原子 promote complete snapshot。
4. crash 后从最后 committed page 恢复或安全重启 snapshot。

## Acceptance

- [x] large snapshot 不重不漏且 complete 前不可提交
- [x] duplicate/skipped/non-monotonic/over-budget fail closed
- [x] 旧 complete snapshot 在新加载失败时仍可离线读
- [x] process restart 不丢 continuation/completeness

## Validation

- [x] DAO/paging/fault/restart tests 通过
- [x] Android JVM sync tests 通过

## Evidence

- Base: `7584ee74b123f80554712ffa2ce41951985a6b35`; implementation stayed isolated in
  `/var/tmp/zhangtianshu-tmp/lezi-causal-h08` through public-seam TDD, review, and final gates.
- `SyncPort.fetchConflictSnapshot(conflictId)` is the complete-only public seam. `SyncBackend`
  fetches one bounded page with exact encoded HTTP byte evidence; `ConflictSnapshotProjection`
  durably records receipt, continuation, ordinal, branch count, encoded bytes and every accepted
  page in the existing Room 27 cache, then atomically promotes only a complete page-0 snapshot.
  The private staging key preserves the previous complete row and does not activate H27's Room 28,
  versionCode, protocol floor or capability work.
- Page validation closes identity/common-view drift, duplicate/skipped ordinals, repeated opaque
  continuations, duplicate/non-monotonic branch IDs, empty continuation progress, 16-branch/128-KiB
  page limits and 64-branch total bounds. Wire-page decoding remains capped at 16 while the distinct
  persisted-complete codec round-trips exact-64 snapshots; total 65 fails closed with the old complete
  snapshot readable.
- A durable unique empty lease is created before network access. Each stage write and final promote
  exact-CASes the owned row inside the Room transaction. The short persistence section also shares
  `syncMutex` and directly rechecks family/device/endpoint/membership/pull-generation/joined identity;
  network paging never holds that barrier. Same-conflict keyed locks serialize resume, unrelated A/B
  conflicts page concurrently, and queued pre-clear loads cannot recreate cleared state.
- Crash/restart tests resume the last committed continuation and real Room instrumentation closes and
  reopens the database before final promotion. First-fetch/validation/cancellation failures CAS-delete
  only an owned empty lease; cleanup errors are suppressed without replacing the original transport or
  cancellation failure. Receipt-expired continuation restarts from page zero once; committed page
  evidence remains resumable for other failures.
- Pull no-conflict/root cleanup deletes complete and staged state together. Resolver/domain code reads
  only canonical complete snapshots; the obsolete domain post-`SyncPort` persistence owner, partial
  availability state, single-page backend DTO path and duplicate HTTP response reader were removed.
- Final Android receipt: `./gradlew test lintDebug :app:assembleDebug :app:assembleRelease
  :app:compileDebugAndroidTestKotlin :core:database:compileDebugAndroidTestKotlin
  :domain:compileDebugAndroidTestKotlin :sync:compileDebugAndroidTestKotlin
  :feature:family:compileDebugAndroidTestKotlin :feature:log:compileDebugAndroidTestKotlin` passed
  (`BUILD SUCCESSFUL`, 1780 tasks). The first two starts stopped only because the isolated worktree
  lacked gitignored release-signing links; temporary links to the developer's existing local files
  were used for the successful run and then removed without reading/copying/committing secrets.
- No Rust source changed. Current-tree `cargo fmt --all -- --check`, escalated
  `cargo test --locked` (273 library + 185 API + 1 conflict fixture + 2 isolated TLS tests), and
  `cargo clippy --all-targets --all-features -- -D warnings` passed. The first sandboxed Rust test run
  reached the two TLS tests after all other suites passed, then was correctly rerun outside the socket
  sandbox because local listener creation returned `Operation not permitted`.
- `adb devices -l` returned 0 devices. The Room device regression compiled but was not executed; no
  connected/instrumentation result is claimed. No NAS, image, package, push, CD, or live smoke ran.
- Reviews: Standards fixed point `Hard 0 / Judgement 0`; Spec fixed point
  `Hard 0 / Scope 0 / Judgement 0` after closing the legal 16+1/exact-64 persisted-complete gap.

## Out of scope

不实现 freshness UI 或服务端分页。
