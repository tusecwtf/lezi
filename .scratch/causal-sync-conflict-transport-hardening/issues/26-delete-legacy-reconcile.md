# 26 — 删除 legacy reconcile 路径

**What to build:** 在所有 roots/media 已迁移后删除普通发表 reconcile route、server/client state、adapters、recordings 和旧 lossy conflict cache。

**Blocked by:** 25

**Status:** implemented (review/final gates pass; device Room instrumentation residual)

## Contract slice

只删除已被 commit-first 替代的路径；只读预览不是当前产品合同。不存在 dual-read/dual-write fallback。

## Implementation sequence

1. 枚举所有 route/call/state/cache 引用与观察行为。
2. 删除 server handler/Store surface 与 Android planner/adapter。
3. 删除旧 recordings/tests/cache schema usage。
4. 用 absence tests 证明普通 publish 不能调用 reconcile。

## Acceptance

- [x] 无可达普通 reconcile route/call/state
- [x] 无旧 client-supplied resolved root/media adapter
- [x] no-pull/cursor independence 回归保持
- [x] unrelated reset/full-resync seam 保留

## Validation

- [x] compile、route absence、recording-backend tests 通过
- [x] Android/Rust relevant gates 通过

## Out of scope

不做 schema cleanup migration 或 capability advertise。

## Implemented evidence

- 服务端删除 ordinary/causal reconcile 两条 route、handler、Store reconciliation surface、旧
  recordings 与 client-supplied resolved root/media contract；同时删除迁移后已无合法调用者的
  bundle-media public adapter。absence API test 固定三条旧 URL 均返回 404。
- Android 删除 reconcile DTO/call/planner/state 与旧 atomic media bundle publisher。所有 mutable
  root 仅通过 causal commit-first 发表；唯一保留的 bundle path 只接受 media-free
  `fulfillment_candidate`，server 与 client 都 fail closed。
- LocalWrite causal commit 不执行 pull、不移动 cursor；非 causal fallback 对 mutable roots fail
  closed。Custom Record 保持 Room id 到 definition UUID 映射，Baby/avatar 的 owner/member ACL
  仍由服务端验证。
- reset/full-resync 继续保留本地待发表意图。reset receipt 与 root requeue 在同一 Room transaction
  落盘，进程重建后仍先从 authority 恢复；跨家庭同 UUID 不继承旧家庭 dirty intent，并清除旧家庭
  media receipt，但保留待重新上传的本地 bytes。

## Review and validation receipts

- Frozen implementation binary diff SHA-256：
  `30c73cd35d6c3fe4f1b71809d6ef55ad8939f92045ab7ff440902f2cd982915d`。
  Final Standards review `Hard 0 / Judgement 0`；Final Spec review
  `Hard 0 / Judgement 0 / Unclear 0`；`git diff --check` 与
  `cargo fmt --all -- --check` 通过。
- `./gradlew test lintDebug :app:assembleDebug --no-parallel` 通过；JUnit XML 为
  578 files / 4014 testcases / 0 failure / 0 error / 0 skipped。隔离 worktree 仅为此门禁临时链接
  主 checkout 的 gitignored signing files，完成后已移除；未构建 release APK。
- `./gradlew :sync:compileDebugAndroidTestKotlin` 通过；sync Debug/Release JVM 各 655/655。
  新增真实 Room instrumentation 覆盖 reset transaction rollback 与 reopen recovery，但
  `adb devices -l` 为 0 device，因此没有宣称设备执行。
- Rust `cargo test --locked` 为 277 unit + 164 API + 1 golden + 3 isolated TLS，合计
  445/445；TLS 使用开发机 `127.0.0.1` ephemeral ports。Clippy
  `--all-targets --all-features -- -D warnings` 通过。
- 未启用 H27 schema/capability；未执行 NAS、image、package、push 或 CD。
