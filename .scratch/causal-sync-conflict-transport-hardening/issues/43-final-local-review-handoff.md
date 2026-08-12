# 43 — 完成本地复审与发版移交

**What to build:** 在固定最终 HEAD 重跑必要 gates，做 Standards/Spec 双轴复审，并把 H01–H42 与 external R12/R17/R18/R19 的可复验证据移交 0.4.0 release 09。

**Blocked by:** 32、33、34、35、36、37、38、39、40、41、42

**Status:** blocked — final local review recorded P1 evidence/gate blockers; no speculative fix made

## Contract slice

本票只复审/汇总。新 P0/P1 必须另建 blocker 并保持本票未完成，不在此无限扩张修复；生产部署仍需 release 09 与新维护窗口确认。

## Implementation sequence

1. 固定 HEAD/status/version/schema/capability 并重跑最终 gates。
2. 分别复审 repo standards 与 spec compliance。
3. 核对 H01–H42 和 R12/R17/R18/R19 receipts/residuals。
4. 形成 release 09 的证据、风险与 unrun-gate 清单。

## Acceptance

- [x] 固定 code HEAD、status、release/schema/capability pins 并逐票核对 H01–H42 与 R12/R17/R18/R19
- [ ] 所有 Must 有固定 HEAD 的代码/测试/设备或 isolated evidence；设备项仍是明确 residual
- [ ] P0/P1 为零；当前 P1 blocker 已在本票记录，本票保持 blocked
- [x] 报告区分 committed code、unrelated WIP、local evidence、unrun device/NAS
- [x] 未宣称 CD、生产发布或 cutover 成功

## Validation

- [x] Android/Rust/local isolated E2E gates 按最终 code tree 重跑或准确记录失败
- [x] tracker status/frontier/handoff 已更新并保持 H43 blocked

## Evidence (2026-08-12)

### Fixed review basis and pins

- **Code review HEAD:** `4927596e43b8adb0eacc57605d8bc9a141d12fa8` (`docs(scratch): pin H42 acceptance evidence`).
  The H43 commit changes only this handoff and `ISSUES.md`; the code under review remains this exact
  HEAD. The final repository HEAD after the docs-only commit is reported separately in the handoff.
- **Working tree at review start:** clean; branch `master`; no unrelated WIP. No production source,
  test source, APK metadata, H38 ticket, image, package archive, or NAS file was changed by H43.
- **Release pins:** Android/server `0.4.0`; Android `versionCode=21`; Room `28`; local-data contract
  `5`; server schema `13`; protocol/min-supported floor `21`; capability `causal_sync_v2`.
  Live sources agree: `config/android-release-compatibility.json`, `app/build.gradle.kts`,
  `tools/lezi-sync/Cargo.toml`, `tools/lezi-sync/src/store/schema.rs`, and
  `tools/lezi-sync/deploy/app-update.json`.
- **External receipts:** R12 `c1e05d6b3996f93549c3562961ddda356c40b605`; R17
  `86c7fab0d37ce3ab7ae6e6a4e261d46d835476f8`; R18
  `0e4f429ffbc77851339d0c8b02ce235fea6136ee`; R19
  `a5b257d2e529206e5bf42eae18d38bc22efdaa2d`. Their ownership remains external;
  H36 consumed their limits and isolated smoke evidence.

### H01–H42 tracker and commit reconciliation

- H01–H30 are marked implemented in the tracker with their per-ticket gates and known device/CD
  residuals. Their recorded source commits are reachable from the review HEAD. H28/H29/H30 cover
  schema 11/12→13 copy-out, guarded cutover orchestration, and isolated rollback rehearsal only;
  none is production cutover evidence.
- H31–H42 feature commits are all reachable from the review HEAD: H31 `1b3270d4`, H32
  `527820b5`, H33 `10855c39`, H34 `d7ede603`, H35 `186dd762`, H36 `b55a93b0`, H37
  `86827f27`, H38 `9442bfd0` plus tightening `6ee3351c`, H39 `c5791b57`, H40 `6c311f07`,
  H41 `3c0e10cd`, H42 `0d81dbb6`; their following docs pins are present through H42.
- H38 Evidence has an invalid exact SHA pin: it records
  `6ee3351c9c591542b690df7e7fd6070620834bc8`, which does not resolve. The live commit is
  `6ee3351cc29f1912bba662bbd46e390cd5f781d9`. This is a P1 evidence-integrity blocker; H43
  does not silently repair the H38 ticket.
- H39 Evidence reports the Rust/JVM matrix green, but its Acceptance checklist remains unchecked.
  This tracker/evidence inconsistency is recorded as a P1 handoff blocker; no product fix is
  inferred.

### Standards review

- **Standards hard finding P1:** `cargo clippy --all-targets --all-features --locked -- -D warnings`
  fails at `tools/lezi-sync/src/store/tests/causal_tests.rs:2735-2744` because
  `filter(...).next()` triggers `clippy::filter-next`. H43 does not edit the test or production
  code.
- **Standards finding P2:** `git diff --check 641a769b^...4927596e` reports one trailing blank
  line at `domain/src/test/kotlin/com/lezi/babylog/domain/CareLogRealServerSeamNwaySupport.kt:248`.
  It is outside H43's docs-only scope and is not misreported as a clean code diff.
- No additional repository-standard violation or speculative production abstraction was found in
  the H31–H42 stack. The public retry factory, isolated CareLog seam, capability pins, and feature
  package locality match the documented architecture. The code-review skill's parallel worker
  surface was unavailable in this runtime, so Standards/Spec were performed as a direct fixed-HEAD
  review against `AGENTS.md`, `CONTEXT.md`, PRD, ADR, and ticket contracts.

### Spec review and P1 findings

- H31–H39 local acceptance follows the hardening contracts at the CareLog/SyncPort/isolated-server,
  transport, media, and Rust Store seams. The explicitly documented fake-DAO/Room and device
  residuals are not converted into production claims.
- **P1 release-contract finding:** after the final `:app:assembleRelease`, the signed APK is
  `app/build/outputs/apk/release/app-release.apk` with SHA-256
  `16748e09e31edcd457a636f2c6e4eafb129e9f25c43cba701ca19a9011656938`, while
  `tools/lezi-sync/deploy/app-update.json` still contains `712bc58c...cf625`.
  `LEZI_PACKAGE_APP_UPDATE_CHECK_ONLY=1 ./tools/lezi-sync/deploy/package-nas.sh` therefore
  failed closed. Signer verification itself passed with pinned certificate
  `ce1438c8c50fe75f04f89ae2092631a46660480764cd52071cc5c08707462211`.
- The H40/H41/H42 device Musts are not falsely closed: ADB has no devices, so Room migration,
  process-death reopen, connected Compose interaction, installation/launch, API-level receipt,
  and screenshots remain unrun. This is an explicit device residual, not a production release
  claim.

### Gates run at the fixed code HEAD

| Gate | Result | Receipt |
|---|---|---|
| `cargo fmt --all -- --check` | PASS | clean |
| `cargo check --locked` | PASS | `lezi-sync v0.4.0` |
| `cargo test --locked` | PASS | 286 lib + 165 API + 1 contract + 3 TLS; 0 failures |
| `cargo clippy --all-targets --all-features --locked -- -D warnings` | FAIL | one `filter-next` finding above |
| H31–H36 domain focused suites | PASS | `BUILD SUCCESSFUL`; isolated loopback server/temp roots |
| H34–H39/H41 sync focused suites | PASS | 50 tests; `BUILD SUCCESSFUL` |
| H40/H42 JVM focused suites | PASS | `BUILD SUCCESSFUL`; H40 update/catalog + H42 matrix |
| app unit + AndroidTest Kotlin compile | PASS | `BUILD SUCCESSFUL` |
| `:app:lintDebug` + `:app:assembleRelease` | PASS | `BUILD SUCCESSFUL`; Gradle verified release signature |
| APK `apksigner verify --verbose --print-certs` | PASS | signer pin matches |
| package check-only | FAIL | metadata APK SHA mismatch; no NAS package created |
| full `./gradlew test --no-parallel --no-daemon` | FAIL | 706 completed, 4 failures listed below |
| `adb devices -l` | RESIDUAL | `List of devices attached` with no devices |
| `:app:connectedDebugAndroidTest` | RESIDUAL/FAIL | `DeviceException: No connected devices!` |
| `git diff --check` on H31–H42 stack | FAIL | one trailing blank line above |

Full Gradle failures, all in the pre-existing H40 sync residual and outside H43-owned files:

1. `ReplicaSyncEngineCausalSettlementTest.lostCarePlanMediaResponseReplaysExactGroupBeforeReplanningLaterEdit`
   (`ReplicaSyncEngineCausalSettlementTest.kt:639`) — duplicate durable bytes assertion.
2. `ReplicaSyncEngineLocalWriteNoPullTest.ownerBabyWithActiveAvatarPreparesReceiptThenCommitsWithoutReconcile`
   (`ReplicaSyncEngineLocalWriteNoPullTest.kt:832`) — unexpected `causal_media_preimage_started` event.
3. `ReplicaSyncEngineLocalWriteNoPullTest.localWriteWithLogPhotoPreparesReceiptThenCommitsWithoutReconcile`
   (`ReplicaSyncEngineLocalWriteNoPullTest.kt:1043`) — same known event residual.
4. `ReplicaSyncEngineLocalWriteNoPullTest.carePlanAttachmentPreparesReceiptThenCommitsWithoutReconcile`
   (`ReplicaSyncEngineLocalWriteNoPullTest.kt:616`) — same known event residual.

### Committed code, local evidence, and deployment boundary

- **Committed code:** H31–H42 stack is committed and reachable from the fixed review HEAD; H43
  adds only tracker/handoff documentation. The review includes no uncommitted unrelated WIP.
- **Local/isolated evidence:** Rust full suite, H31–H39 isolated JVM/real-server matrices, H40/H41
  JVM acceptance, H42 JVM/Compose source compilation, release signing/metadata identity checks,
  and external R12/R17/R18/R19 receipts are recorded above. H31–H39 use developer-owned loopback
  or temp roots; no family data or production certificate was used.
- **Device residual:** no ADB device; no Room instrumentation, process-death device reopen,
  migration fixture execution, connected UI, screenshot, install, or launch.
- **NAS/CD residual:** no family NAS probe, image build, NAS package archive, `push-and-deploy.sh`,
  stop/rm/replace, schema cutover, TLS mutation, or production client smoke. Release 09 remains
  the sole production owner and is blocked by this H43 result plus a fresh maintenance-window
  confirmation.

### H43 disposition

H43 is intentionally **blocked**, not released or closed. Resolve the H38 exact receipt pin, the
H39 checklist/evidence inconsistency, the Clippy gate, and the APK metadata/hash mismatch in their
own owned tickets or release preparation; then rerun this handoff from a newly fixed code HEAD.
No speculative production repair was made here.

## Re-review and release preparation (2026-08-13)

- **Fixed committed basis:** `27fe6cae425f3cbbfd7c7a4448b8cb5e0d41fd84`; the main worktree also
  contained unrelated UI/design-token WIP, so all release work used `/tmp/lezi-release-final` at
  that detached committed HEAD.
- **Previously recorded blockers cleared:** H38 exact SHA, H39 checklist, Clippy, diff whitespace,
  four JVM failures, H40/H41/H42 API 35 device matrices, same-signer code 6→21 and code 20→21
  upgrades, and H42 screenshots are all fixed/passed in the commits following the old review.
- **Final APK:** `:app:assembleRelease --rerun-tasks --no-daemon` passed (682 tasks). Signed
  `com.lezi.babylog` 0.4.0/code 21 SHA-256 is
  `876a904fbc54097a7d44fba8b9a6f88af226fa554b537e2d0b162348abbebef5`; signer certificate is
  `ce1438c8c50fe75f04f89ae2092631a46660480764cd52071cc5c08707462211`.
  `LEZI_PACKAGE_APP_UPDATE_CHECK_ONLY=1` passed with min-supported 21 and local-data contract 5.1.
- **Standards re-review:** no P0/P1. Package-locality duplication/naming findings remain P2/P3 and
  do not change runtime behavior or the H43 P0/P1 gate.
- **Spec re-review:** H39 server critical-section P1 was disproved by the real isolated Axum/SQLite
  tests `slow_causal_media_prepare_streams_to_temp_without_blocking_a_small_commit` and
  `causal_media_promotion_runs_outside_the_same_family_commit_lock`; both passed on this basis.
  One P1 remains: H38's Android settlement fault matrix and real-server fault matrix are separate,
  not one injected Android→real-server chain. H44 now owns that exact gap. Therefore H43 remains
  **blocked** and release 09/CD cannot be declared eligible.
- **Production read-only preflight:** `https://192.168.50.4:8765/health` answered version `0.3.12`;
  plaintext HTTP returned 502. The dedicated 0.4.0 cutover accepts only exact live
  `lezi-sync:0.3.13`/schema 12. The SSH user is not in the Docker group, `/var/run/docker.sock` is
  `root:docker 660`, and `sudo -n docker` requires a password. Consequently no schema/container/TLS
  inventory could be collected through uid 10001, and no NAS write, stop, rm, package push or
  replacement was attempted.

## Out of scope

不构建 image、不打包/推送、不 stop/rm/replace 家庭 NAS。
