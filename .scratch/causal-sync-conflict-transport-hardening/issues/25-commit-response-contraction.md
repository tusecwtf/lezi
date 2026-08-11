# 25 — 收缩 commit response 与 delete settlement

**What to build:** 将 commit batch response 收缩为一个 batch generation 与每 unit 足以 settlement 的 terminal result/stable-or-conflict reference。

**Blocked by:** 12、13、23

**Status:** implemented (review/final gates pass; device Room instrumentation residual)

## Contract slice

Unit 不含冗余 cursor/generation；delete 与 live 使用同一 per-unit settlement。Replay 保留原 accepted/merged/branched status，加独立 marker。

## Implementation sequence

1. 冻结最小 response schema 与 error mapping。
2. 服务端删除 unit cursor/generation 冗余。
3. Android 按 unit settle live/delete/media。
4. 删除旧 response adapters 与分叉成功逻辑。

## Acceptance

- [x] 一个 batch generation，不由 commit 移动 pull cursor
- [x] 每个 live/delete unit 均可完整 settle
- [x] replay marker 不成为第四终态
- [x] partial batch/error 不误清 pending

## Validation

- [x] Kotlin/Rust contract corpus 与 settlement tests 通过
- [x] mixed live/delete/media isolated smoke 通过

## Out of scope

不删除 reconcile routes 或启用 capability。

## Implemented evidence

- Commit success response 现为顶层唯一 `generation` + `results`；unit 只保留
  `accepted|merged|branched`、独立 `replay` marker 与 settlement 所需 stable/conflict references。
  Rust Store 保留原 terminal status 做 exact replay，Android commit decoder 与 legacy reconcile decoder 为两个
  closed DTO，commit 不读写 pull cursor。
- Rust 在单个 `IMMEDIATE` transaction 内处理整批 commit；任一 unit 语义拒绝都整批回滚。
  HTTP 对 auth/client gate、JSON shape/recursive duplicate member、empty/oversized batch、ACL/CAS/domain
  拒绝统一输出 §9.5 closed terminal envelope，内部/传输失败仍保持 retryable 5xx 边界。
- Android 的 direct/replay、live/delete/media 均进入同一 transactional settlement owner；
  Record/Baby/CustomItem/Wake/CarePlan 均落地 server-stamped `deleted_at`。整批 proof 在事务前
  fail closed，pending CAS 和 media journal 在同一 settlement transaction 内，partial/error 不误清。
- Public-seam regressions 覆盖 mixed live/delete/media、lost-response replay、accepted/merged/branched
  tombstone projection、recursive duplicate JSON member 零写、auth Store 故障保持 500，以及 >64 batch
  先消耗 admission 再 terminal reject。H24 expiry matrix 仅排除独立 maintenance 对 expired staging
  row 的合法迁移，仍严格固定 family revision/version/terminal/root/media/publication 零写。

## Review and validation receipts

- Frozen 22-path source/test binary diff SHA-256:
  `40986474623bce110c6c4d21e192de469ccb77cb3a2ea51ae9b7506e546acfeb`。
- Sequential final reviews on that freeze: Standards `Hard 0 / Judgement 0`；Spec
  `Hard 0 / Judgement 0 / Unclear 0`。`git diff --check` 与
  `cargo fmt --all -- --check` 均通过。
- Android focused JVM：
  `HttpSyncBackendReconcileAtomicTest` 13/13、
  `ReplicaSyncEngineCausalSettlementTest` 36/36、
  `ReplicaSyncEngineCausalRootTypesTest` 10/10、
  `ReplicaSyncEngineLocalWriteNoPullTest` 37/37，合计 96/96。
  `./gradlew :sync:testDebugUnitTest --offline --console=plain` 为 738/738；
  `./gradlew :sync:compileDebugAndroidTestKotlin --offline --console=plain` 通过。
- `./gradlew test lintDebug :app:assembleDebug --offline --console=plain` 通过；JUnit XML
  608 files / 4274 testcases / 0 failure / 0 error / 0 skipped。Debug APK 为
  `app/build/outputs/apk/debug/app-debug.apk`，29,392,790 bytes。隔离 worktree 初次因缺少
  gitignored signing files 在 `:app:validateReleaseSigning` 前置 gate 失败；仅建 H25-owned
  temporary symlinks 后原命令通过，未改源码或构建 release 包。
- Rust：`cargo test --locked` 的 300 unit + 223 API + 1 golden 通过；TLS 3 tests 仅因
  sandbox loopback `Operation not permitted` 失败，在开发机自有临时目录和 `127.0.0.1`
  ephemeral ports 窄重跑 3/3 通过。
  `cargo clippy --all-targets --all-features -- -D warnings` 通过，合计 527 tests。
- `adb devices -l` 显示 0 device；因此 instrumentation 只有 Kotlin compile evidence，未执行
  device/Room runtime tests。`RealSyncPortIdentityClearTest` 的 clean-base differential 证明原失败是
  virtual-time scheduler-sensitive；测试仅将原 2s completion wait 放到受限 real dispatcher，
  tight loop 3/3 与 full module 均通过，无 sleep/超时放宽/生产改动。
- 未删除 reconcile route，未启用 H27 capability/schema advertisement；未执行 NAS、image、
  package、push 或 CD。
