# 06 — 让共享 resolver 使用完整 snapshot

**What to build:** 将 Record 时间线 resolver 深化为五类 root 共用的 domain resolver，完整展示候选并只提交 token、resolution mutation ID 与 choice IDs；Record 是首个入口。

**Blocked by:** 05

**Status:** implemented (review/local gates pass; device interaction residual)

## Contract slice

显示 provenance、stable/branch media、auto outcomes、deleted/restore 和 freshness；客户端不重建 resolved root/media，不允许 incomplete snapshot submit。

## Implementation sequence

1. 将 resolver state 改为五类 root 共用的单一 domain snapshot 输入。
2. 用每类 root 的字段-label table 映射候选、媒体与 deleted/restore 文案。
3. 构造 choice-only command 并处理 ACL/stale/terminal 结果。
4. 在 process recreation 后恢复选择或安全清空。

## Acceptance

- [x] 五类 root 每个 conflict path 恰选一次才可提交
- [x] incomplete/expired/offline snapshot 只读
- [x] author/Owner affordance 正确，server ACL 仍是权威
- [x] lost response 重试使用同一 resolution mutation ID

## Validation

- [x] domain/state-machine/Compose resolver tests 通过
- [ ] 相关 connected test 留至票 42（已 compile；当前无 device，未执行）

## Evidence

- Base: `691c00fc59128caaf3c386c6273e66afd189fd52`; implementation was isolated in
  `/var/tmp/zhangtianshu-tmp/lezi-causal-h06` through review and final gates.
- One shared domain resolver consumes H05's typed complete snapshot for Baby、Record、CarePlan、
  CustomItem and WakeObservation. It owns labels, provenance/media/deleted/restore/auto outcomes,
  freshness and per-root ACL; incomplete/offline/expired snapshots stay read-only. Baby is
  Owner-only on both Android and Rust, while the other roots retain author-or-Owner authority.
- The resolve wire now sends exactly `snapshot_token`、`resolution_mutation_id` and canonical
  path/choice pairs. The single Android command validator enforces token/UUID/pointer/order/bounds;
  rejected terminals accept only the wire §9.5 19-code closed set with `retryable=false`.
- Accepted roots/media are validated by the H05 five-root codec, but local root/media/deleted
  settlement remains exclusively owned by the next authoritative pull because the accepted envelope
  has no deleted field. The replaced partial raw-map projector and its fallback tests were removed.
- Process restoration is one atomic, strictly validated frame: any torn/malformed token, mutation or
  choice clears the attempt. A frozen lost-response retry reuses the exact mutation and choices.
  Production Record timeline Compose routes through the shared sheet; clock crossing at expiry
  equality returns a typed read-only result without a command or click-time exception.
- Public-seam TDD covered exact loopback wire, unknown/retryable terminal rejection, all five roots,
  canonical invalid-command tables, ACL/freshness/explicit choices, frozen replay, atomic restore,
  malformed accepted projection and production-sheet expiry interaction.
- Current-tree Android gate receipt: `./gradlew test lintDebug :app:assembleDebug
  :app:assembleRelease :app:compileDebugAndroidTestKotlin
  :core:database:compileDebugAndroidTestKotlin :sync:compileDebugAndroidTestKotlin
  :domain:compileDebugAndroidTestKotlin :feature:log:compileDebugAndroidTestKotlin` passed on the
  second complete run (`BUILD SUCCESSFUL`, 1772 tasks). On the first run only the unrelated
  `RealSyncPortIdentityClearTest.failedLogoutPreservesEverythingWhileInterruptedCleanupResumesFromDurableMarker`
  coroutine assertion failed. Exact isolation receipt:
  `./gradlew :sync:testReleaseUnitTest --tests
  'com.lezi.babylog.sync.RealSyncPortIdentityClearTest.failedLogoutPreservesEverythingWhileInterruptedCleanupResumesFromDurableMarker'
  --rerun-tasks` → `BUILD SUCCESSFUL` (50/50 tasks executed), followed by the successful complete run.
- Current-tree Rust gates: `cargo fmt --all -- --check`, `cargo test --locked` (273 library + 185 API
  + 1 conflict fixture + 2 TLS tests), and
  `cargo clippy --all-targets --all-features --locked -- -D warnings` passed.
- `adb devices -l` returned only `List of devices attached` with no rows (0 devices). The real Compose
  device regressions compiled but were not
  executed; no screenshot or connected result is claimed. No NAS, image, package, push, CD, or live
  server smoke was run.
- Review: Standards fixed point `Hard 0 / Judgement 0`; Spec review initially found the closed-code
  and evidence gaps. The closed-code gap is fixed and targeted/full gates are green; final quick Spec
  recheck is recorded before commit.

## Out of scope

不实现全局 inbox 或多页加载。
