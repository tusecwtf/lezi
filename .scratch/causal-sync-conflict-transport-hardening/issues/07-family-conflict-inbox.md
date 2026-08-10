# 07 — 建立家庭冲突 inbox 与 badge

**What to build:** 在家庭页同步状态行显示 open-conflict badge/入口，并列出 Baby、Record、CarePlan、CustomItem、WakeObservation 五类根及其 tombstone 状态。

**Blocked by:** 06

**Status:** implemented (review/local gates pass; device interaction residual)

## Contract slice

Badge 计数单位是 open root conflict；列表按 server receipt/update time 降序，再以稳定 conflict ID 打破平局。家庭与 Record 入口进入同一 shared resolver route。

## Implementation sequence

1. 建立跨根 summary projection、count 与稳定排序。
2. 接入家庭同步状态行 badge 和 inbox route。
3. 映射五类根标签、宝宝、actor fallback、media/deleted 摘要。
4. 将列表项与 Record 上下文入口路由到 shared resolver。

## Acceptance

- [x] 五类根/tombstone 均可发现且 count 不重复
- [x] 排序在相同时间下仍稳定
- [x] actor 名称缺失显示稳定 ID
- [x] 无权限用户可审阅但 resolver submit disabled

## Validation

- [x] projection/count/sort/navigation/Compose tests 通过
- [ ] 相关 connected test 留至票 42（已 compile；当前无 device，未执行）

## Evidence

- Base: `8d4bf2942e60380b84541b472f50453ec24d7213`; implementation stayed isolated in
  `/var/tmp/zhangtianshu-tmp/lezi-causal-h07` through TDD, review, and final gates.
- `CareLog.observeOpenConflictInbox()` is the single domain façade. One observable Room query
  projects and canonicalizes Baby、Record、CarePlan、CustomItem and WakeObservation open roots,
  orders by `updatedAt DESC, conflictId ASC`, and joins local baby/actor/tombstone/media plus the
  existing complete-snapshot cache. The old per-root summary DTO/parser/list APIs and the legacy
  `PendingPublishDao.observeOpenConflictCount()` badge owner were removed.
- The real Room regression writes all five root tables and 100 real CustomItem roots, proves same-root
  dedupe/tie order, checks every join field, and measures one SQL statement per initial/root/media/cache
  emission (`1 → 2 → 3 → 4`). Directory-only mutation independently refreshes an actor ID to its
  member name without a Room emission.
- Cold summary/cache absence is closed, not optimistic: actor、branch tombstone and total media use
  `Known` / `RequiresDetail`; a known local membership ID remains the fallback and known local photos
  render only as a lower bound. The UI never turns unavailable branch detail into `false` or exact
  `0`. A complete cached H05 snapshot alone exposes exact branch deletion and distinct media totals.
- Family's shallow-sync row owns one deduplicated badge and inbox entry. Family inbox items and Record
  context pass only `conflictId` into the same app-shell resolver. Resolver load/submit attempts are
  single-flight and generation guarded, so late A→B or post-dismiss completions cannot reopen or
  overwrite the active sheet. Process restoration and author/Owner read-only policy remain H06-owned.
- Public-seam TDD covers real Room five-root projection/query invalidation, `CareLog` cold-cache
  availability, membership-directory invalidation, badge policy, app-shell family/Record navigation,
  resolver races, and Compose cold/complete-cache accessibility plus conflict-ID clicks.
- Final Android receipt: `./gradlew test lintDebug :app:assembleDebug :app:assembleRelease
  :app:compileDebugAndroidTestKotlin :core:database:compileDebugAndroidTestKotlin
  :domain:compileDebugAndroidTestKotlin :sync:compileDebugAndroidTestKotlin
  :feature:family:compileDebugAndroidTestKotlin :feature:log:compileDebugAndroidTestKotlin` passed on
  the second complete run (`BUILD SUCCESSFUL`, 1780 tasks). On the first run only unrelated
  `RealSyncPortDisasterRestoreTest.ownerRestoresCompleteLocalSnapshotBeforeAtomicallyRetiringOldReplica`
  failed its coroutine-time assertion; exact `:sync:testReleaseUnitTest --tests ... --rerun-tasks`
  passed (`50 actionable tasks: 50 executed`) before the successful complete rerun.
- No Rust source changed. Current-tree Rust gates had already passed: `cargo fmt --all -- --check`,
  `cargo test --locked` (273 library + 185 API + 1 conflict fixture + 2 TLS tests), and
  `cargo clippy --all-targets --all-features --locked -- -D warnings`.
- `adb devices -l` returned 0 devices. Room/Compose device regressions compiled but were not executed;
  no connected result or screenshot is claimed. No NAS, image, package, push, CD, or live smoke ran.
- Reviews: Standards fixed point `Hard 0 / Judgement 0`; Spec code fixed point
  `Hard 0 / Scope 0 / Judgement 0`. Production source was frozen after that fixed point; only this
  ticket/tracker evidence changed before final `git diff --check` and evidence recheck.

## Out of scope

不实现多页 snapshot 或改变家庭成员模型。
