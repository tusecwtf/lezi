# 05 — 将 source relation 收敛为一个有界 canonical graph

Status: implemented

Priority: P1

Audit baseline: `38cfbe7dbcf1f410237dce77b50ec1bd4854d8a6`

Blocked by: 04，以及 `post-0.3.13-review-remediation/01` 的重叠产品实现稳定。

## Findings

- `store/source_relations.rs:576-738` 跳过非白名单 named record（`:612-614`）；全被跳过时
  `:637-639` 视为完整。发现 disconnected extra（`:731-736`）也只 `continue`，最终接受非法组。
- request/member/candidate 无上限；逐 member/窗口 SQL 后对候选全 pair 建图，最坏约
  `O(M·F + K²)`，且位于 family lock + `BEGIN IMMEDIATE` 内。
- author half-edge 与 Owner full relation 可重叠；schema 只以
  `(family_id, relation_id, record_uuid)` 为主键。summary 在 `:338-380` 用无排序 `LIMIT 1`，同一
  record 可能任取旧半组或新完整组。
- `insert_relation`（`:502-548`）不推进受影响 record rev。已在当前 cursor 的 peer 不会再得到
  `source_relation_summary`。
- Android `ReplicaSyncEngine.applyRecord` 只在完整 record apply 后处理 relation sidecar；同 stable
  version/拒绝 apply 的 early return 会漏 sidecar。Room relation DAO 无 observable Flow，Log/Summary
  又只随 record snapshot 重算，关系成功后本机产品面也不一定刷新。

## Interface boundary

一个 source-relation Module 拥有 canonical component transition、CAS/idempotency、bounded group
validation、rev emission 和 pull projection。Android 将 relation sidecar 视为独立 durable delta，
不能借 record body 是否需要重写来决定是否应用。

## Acceptance

- [x] 每个 live record 在一个确定 canonical component 中至多一个角色；Owner resolve 原子合并/替代
  相关 half-edge，旧 relation 不再被无序读取
- [x] 非白名单、同作者-only、错 baby/type、超过 30 分钟、disconnected extra、重复 record 与超限
  request 全部以稳定 code 拒绝；恰好 30 分钟合法，缺合法邻居返回 `incomplete_group`
- [x] 明确 member 与候选扫描上限；bulk load + sorted window/有界图替代 per-member SQL 和全 pair 扩张
- [x] relation durable transition 推进所有受影响 record 的 pull rev；普通 entity body/version 可不变
- [x] Android 在 same-version/dirty early-return 前独立校验并事务应用 sidecar；关系表变更有 observable
  invalidation，Timeline/Summary/Log 立即重算
- [x] peer 从“关系写入前 current cursor”增量 pull 能看到所有 member 的同一 summary；restart 后一致
- [x] record 仍 live、媒体永久可追溯；不得用 tombstone 或隐式 winner 代替来源关系

## Validation

- [x] Store graph-transition、2/3/limit 组、非法 extra、cursor/replay/query-budget tests 通过
- [x] Android same-version sidecar JVM test 通过；relation-only Room invalidation/rollback instrumentation
  test 已编译（当前无设备，未执行）
- [x] 隔离双客户端 Router/Store + JVM product projection smoke 通过：resolve 后 source 从普通投影消失，
  relation detail 仍保留，restart 后增量 pull summary 一致
- [x] Rust gates、相关 Android JVM/lint/assemble 通过

## Implementation evidence

- Fixed implementation base: `4401004392d91084a67dfd95a20ec561f2379bd6`.
- Rust: `cargo fmt --all -- --check`; `cargo test --locked`（222 lib + 171 API + 2 TLS）；
  `cargo clippy --all-targets --all-features -- -D warnings`.
- Android: `:sync:testDebugUnitTest`, `:domain:testDebugUnitTest`, `:feature:log:testDebugUnitTest`,
  `:feature:summary:testDebugUnitTest`, `lintDebug`, `:app:assembleDebug`, and
  `:core:database:compileDebugAndroidTestKotlin`.
- Device boundary: `adb devices -l` returned no attached device/emulator, so instrumentation execution and
  physical-device UI smoke remain unclaimed. No NAS, image, package, deployment, or production endpoint was used.
- Review closure: Standards hard findings were repaired with the indexed eligibility projection, one durable
  mutation-receipt ledger, protected Android canonical transition, legacy pull-marker upgrade, and member-role
  drift rejection. The Spec axis found no remaining acceptance gap on the fixed base.
