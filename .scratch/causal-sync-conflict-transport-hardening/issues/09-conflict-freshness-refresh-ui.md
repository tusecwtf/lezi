# 09 — 完成冲突 freshness 刷新体验

**What to build:** 将 expired/stale/new-branch 结果映射为 refresh 状态机，重载完整 snapshot，并在 offline、旋转和进程重建后保持诚实可理解的 resolver 状态。

**Blocked by:** 06、07、08；[`external 19`](../../repository-dedup-algorithm-audit-20260809/issues/19-resolution-metadata-retention.md)

**Status:** implemented (review/local gates pass; device interaction residual)

## Contract slice

Stale 只终止当前 resolution request，不终止事实；刷新可生成新 choice IDs，客户端从新 snapshot 重建选择而不猜测旧 ID 等价。

## Implementation sequence

1. 定义 loading/offline/stale/refreshing/complete/error UI state。
2. 对 stale/expired/new branch 触发 fresh receipt 与全页重载。
3. 用新 snapshot 清空或明确重建用户选择。
4. 支持 configuration change 与 process recreation。

## Acceptance

- [x] offline snapshot 可读但不可提交
- [x] stale refresh 不复用旧 choice IDs
- [x] refresh failure 不误报解决成功或丢旧只读证据
- [x] configuration change/process recreation 状态一致

## Validation

- [x] state-machine/Compose/offline/recreation tests 通过
- [x] connected interaction 留至票 42

## Evidence

- Base: `3b4fe28e8ae59869f036ef2e081a2b58446f4d8f`; implementation remained isolated in
  `/var/tmp/zhangtianshu-tmp/lezi-causal-h09` through public-seam TDD, review and final gates.
  The first RED compiled against the removed boolean UI owner and failed on the new host-owned
  `choose`/phase/can-submit seams; subsequent REDs covered expiry refresh, same-receipt loop
  prevention and process recreation before turning green.
- `ConflictResolverHost` now owns one explicit
  `idle/loading/offline/stale/refreshing/complete/error` state machine. Offline, stale, refreshing
  and refresh-error states keep the last complete snapshot visible but read-only; only complete
  online evidence may accept choices or submit. Compose renders tagged, accessible status and retry
  surfaces without a second composition-local freshness owner.
- `snapshot_expired`, `snapshot_stale`, `invalid_snapshot_token` and `cas_mismatch` all terminate
  only the current request and trigger one forced complete-page reload. A changed snapshot receipt
  rebuilds a blank draft with a new mutation identity and never guesses old choice-ID equivalence;
  the same stale receipt remains stale and does not recurse. An already-expired refreshed snapshot
  also stops after that one reload.
- Same-receipt reconnect preserves every valid attempt across offline/process recreation, including
  selected drafts and frozen lost-response commands. Exact frozen retry reuses the mutation ID and
  choices byte-for-byte; only a changed receipt clears them. Forbidden and non-freshness rejected
  outcomes persist as validated terminal dispositions in one atomic SavedState frame, remain
  non-refreshable/non-submittable even at expiry equality, and cannot be reauthorized by recreation.
- Targeted and affected gates passed:
  `:feature:family:testDebugUnitTest`, `:domain:testDebugUnitTest`, and
  `:feature:family:compileDebugAndroidTestKotlin`, including offline read-only, all freshness codes,
  refresh failure, receipt replacement, same-receipt exact restoration, no-loop, terminal,
  process-recreation and Compose status regressions.
- Final Android receipt: `./gradlew test lintDebug :app:assembleDebug :app:assembleRelease
  :app:compileDebugAndroidTestKotlin :core:database:compileDebugAndroidTestKotlin
  :domain:compileDebugAndroidTestKotlin :sync:compileDebugAndroidTestKotlin
  :feature:family:compileDebugAndroidTestKotlin :feature:log:compileDebugAndroidTestKotlin` passed
  (`BUILD SUCCESSFUL`, 1780 tasks; release APK signature verified). A temporary symlink to the
  developer's existing gitignored signing file was used without reading/copying secrets and removed
  after the gate.
- No Rust source changed. Current-tree `cargo fmt --all -- --check`, escalated
  `cargo test --locked` (273 library + 185 API + 1 conflict fixture + 2 isolated TLS tests), and
  `cargo clippy --all-targets --all-features --locked -- -D warnings` passed. The first sandboxed
  Rust run reached only the two local-listener TLS failures (`Operation not permitted`) after all
  other suites passed, then the allowed isolated rerun passed.
- `adb devices -l` returned 0 devices. Instrumentation was compiled but not executed; connected
  resolver interaction remains explicitly owned by H42. No NAS, image, package, push, CD or live
  smoke ran.
- Reviews: Standards fixed point `Hard 0 / Judgement 0`; Spec fixed point
  `Hard 0 / Scope 0 / Judgement 0` after the final gate/device/scope evidence check.

## Out of scope

不改变分页原语或 resolution 语义。
