# 02 — 生成确定性 N 方 ConflictSnapshot

**What to build:** 让服务端对完整 open branch set 一次生成与枚举顺序无关的无损 ConflictSnapshot，不再由 pairwise 顺序产生隐式赢家。

**Blocked by:** 01；[`external 17`](../../repository-dedup-algorithm-audit-20260809/issues/17-bounded-conflict-head-loader.md)、[`external 18`](../../repository-dedup-algorithm-audit-20260809/issues/18-conflict-snapshot-receipt-pagination.md)

**Status:** implemented — Standards 0/0; Spec 0/0/0; final gates pass

## Contract slice

Stable、每个 branch、root/media/deleted、version/base、provenance 都必须完整；conflict 与 auto-merged 路径不相交；snapshot candidate 暴露 opaque choice ID、typed outcome 与 source/provenance，只有 resolution input 只接受 choice ID。

## Implementation sequence

1. 通过有界批量读取 seam 装载完整头集合与因果 base。
2. 将各头 diff 归一为不重叠 typed outcomes。
3. 对所有 changed heads 计算 distinct outcome 集并分类 auto/conflict。
4. 以 canonical 顺序生成 semantic snapshot/candidate descriptors，并由 receipt seam 持久化 opaque choice IDs。

## Acceptance

- [x] 2/3 方、到达/枚举 permutation 在排除随机 receipt/token 后产生字节等价 canonical payload
- [x] `set(null)`、媒体、deletion 与 ancestry 都使用同一分类器
- [x] 不完整/不可比较历史保守冲突或 fail closed
- [x] 无 UUID、时间、作者或 Owner 隐式赢家

## Validation

- [x] Rust table/property tests 覆盖所有 permutation
- [x] Store/API snapshot corpus 通过；资源 query/page budget 只做一条 seam 接线回归

## Out of scope

不提交 resolution，不实现 Android persistence。

## Implementation evidence

- 固定 clean source HEAD `0e4f429ffbc77851339d0c8b02ce235fea6136ee`，只实现 server-authoritative
  snapshot/candidate 描述；未新增 schema/table/index、version/capability advertisement，也未实现 H03
  choice-only resolution write、H04 restore 或 Android persistence。
- `Store::conflict_detail_page` 继续复用 R17 的完整有界 head/base projection 与 R18 的持久 receipt/page
  plan。`conflict_snapshots` 对 stable + 完整 open branch set 一次按每个 head 的直接 causal base 分类，
  canonical path/outcome/source 顺序不依赖 arrival、UUID、作者、设备或读取顺序；没有逐 branch 三方 fold
  或 first-seen winner。
- `conflict_snapshot_v2` 完整返回 stable/branches 的 root、media、deleted、version/base 与
  mutation/actor/device/received provenance。每个 conflict path 返回 typed `set`/`remove` candidate、
  opaque stable choice ID 与完整 sources；auto/conflict path 不相交。choice binding 纳入 path、outcome 与
  full source set，并由 R18 authenticated receipt 持久化，所以重复页、continuation 与 restart 稳定。
- 同一分类器覆盖显式 `set(null)`、media membership remove、delete/edit live vote、path-prefix 与
  type-dependent subtree。record conditional keys 使用 sleep/non-sleep typed source view：单侧合法 shape
  transition 不产生假 conflict/auto；同一 sleep target 的不同 effective WakeObservation UUID 保留为两个
  choice，且 removed conditional key 由完整 `/type` source view 承载。
- accepted/branched version provenance 通过现有 `mutation_receipts` 的 reserved、完整复合主键记录并在
  bounded loader 中一并读取；cross-membership 同 mutation UUID 不能串线。missing root/base/parent/media/
  provenance、multi-parent、legacy content drift 与 receipt/serializer/tamper 均 fail closed。
- 已删除旧 pairwise detail `compute_conflict_paths`、旧 `ConflictBranchDetail`/raw detail DTO、旧 v1
  serializer/fallback 与只锁定旧 wire 的测试；HTTP 只序列化同一 public Store DTO，没有 parallel
  DTO/serializer。既有 causal admission/resolve 内部 merge seam 未扩展为 H03 authoritative write。
- 票面 targeted：causal Store 整组 **47/47**；六个三 branch arrival permutations **1/1**；typed
  conditional/public Store **1/1**；page count/128 KiB budget **1/1**；conflicted pull constant-query
  **1/1**；public API create/pull/branch/detail smoke **1/1**；64 branches、4×16 pages、same-page replay、
  restart/tamper/stale HTTP smoke **1/1**。
- 最终 Rust：专属 `TMPDIR`/`CARGO_TARGET_DIR` 下 `cargo fmt --all -- --check`、
  `cargo test --locked`（**250 lib + 183 API + 1 shared-contract + 2 TLS**）与
  `cargo clippy --all-targets --all-features -- -D warnings` 全部通过。受限 sandbox 首次 full suite 仅
  2 TLS loopback 用例因 `Operation not permitted` 失败；获准使用同一 source/target 重跑完整 suite
  后上述 counts 全绿。
- Standards fixed-point：Hard **0** / Judgement **0**；Spec fixed-point：Hard **0** / Scope **0** /
  Judgement **0**。审查清零后未再修改 runtime 实现。
- Android 未改，未运行 Android/device gate。未 build image/package/push，未访问或部署家庭 NAS，
  未运行证书变更/CD。Rust production diff 为 +670/-155（净 +515），测试为 +689/-36；新增规模集中在
  单一 snapshot deep module、bounded provenance 接线与 table/oracle tests，旧重复 owner/path 已删除；
  按用户要求 LOC/复杂度只报告趋势，不作为单独阻塞项。
