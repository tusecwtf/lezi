# 21 — 迁移 Record media commit-first

**What to build:** 将 Record media membership 与 Record tombstone 的媒体证据迁移到 spool/receipt commit-first，并保持 add/add 自动合并与 delete/edit 分支。

**Blocked by:** 20

**Status:** implemented (review/final gates pass; device Room instrumentation residual)

## Contract slice

Record root 与 media metadata/receipt 在同一 frozen mutation；branch/resolution 继续引用精确 bytes。

## Implementation sequence

1. 冻结 Record root+media envelope。
2. 依次 prepare receipts 后执行一次 commit。
3. 用统一 settlement 处理 accepted/merged/branched。
4. 删除 Record media reconcile path。

## Acceptance

- [x] media add/add、delete/edit 与 tombstone evidence 正确
- [x] no reconcile、no cursor move、lost response no re-upload
- [x] branch media 在 conflict detail/resolution 可发现

## Validation

- [x] Record media engine/DAO/server matrix 通过
- [x] isolated byte-equality smoke 通过

## Implementation evidence

- Record root、Record tombstone 与其 media candidates 现在统一走 H17/H18/H20 receipt-bound
  commit-first：`commitPreparedCausalUnits` 是 prepare、`commit_unknown`、commit/proof validation、
  Room transaction 与 terminal cleanup 的唯一 ordering owner。Record source reconcile 明确 fail
  closed；LocalWrite 仍不 pull、不推进 cursor。
- 并发 media add/delete 会 refreeze 当前 direct mutation，不会退回 reconcile。lost commit response
  使用同一 mutation 与 durable receipts 精确 replay，后续 source URI bytes 改变不影响 immutable
  spool；partial receipt 在 engine recreation 后只补传缺失 media。Record root 与 media tombstone 可在
  同一次 commit 结算。
- accepted/merged 只结算 Record media；Baby avatar、WakeObservation、CarePlan attachment 保持 pending，
  留给 H22/H23。branched 结果保留 spool/receipt、conflict summary 与 detail/resolution 可发现证据，
  未提前 cleanup。
- Public seam RED 证明旧实现仍对 Record media 使用 reconcile；最终 focused
  `ReplicaSyncEngineCausalSettlementTest` 28/28 通过。最终 `:sync:testDebugUnitTest` 722/722 通过，
  `:sync:compileDebugAndroidTestKotlin` 通过。gate 还暴露既有 reconnect 测试与真实
  `Dispatchers.IO` actor 的竞态；使用既有 `RecordingSyncBackend.handshakeGate` 固定公共 seam 后，
  目标测试及 `RealSyncPortReconnectTest` 12/12 通过，未修改生产同步时序。
- Fresh Android final matrix：`test lintDebug :app:assembleDebug --rerun-tasks` 通过，1377 tasks
  全部执行；JUnit XML 608 suites / 4242 tests，0 failure/error/skip，debug APK 成功生成。隔离
  worktree 前两次分别因缺少 gitignored `keystore.properties` 与相对
  `app/lezi-release.jks` fail closed；补两个只读 H21 临时 symlink 后新鲜完整重跑通过。未读取、
  复制或提交签名内容。
- Rust final gates：`cargo fmt --all -- --check`、`cargo test --locked`（282 lib + 210 API +
  1 fixture + 3 isolated TLS，共 496 tests）与
  `cargo clippy --all-targets --all-features -- -D warnings` 通过。TLS 只使用开发机隔离
  loopback；未访问 family NAS。
- Review：Standards final Hard 0 / Judgement 0；Spec code final Hard 0 / Judgement 0 /
  Unclear 0；ticket/tracker evidence final 0 finding。`adb devices -l` 返回 0 device，故 real Room
  instrumentation 仅编译、未执行。未运行 NAS、image、package、push 或 CD；未实现
  Baby/CarePlan/Wake/GC/response contraction/schema activation。

## Out of scope

不迁移 Baby avatar 或 CarePlan attachment。
