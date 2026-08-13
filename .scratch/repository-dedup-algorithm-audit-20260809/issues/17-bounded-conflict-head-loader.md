# 17 — 批量加载有界 conflict heads

Status: implemented — review and final Rust gates pass

Priority: P1

Audit baseline: `38cfbe7dbcf1f410237dce77b50ec1bd4854d8a6`

Blocked by: 12；以及 [`causal hardening 01`](../../causal-sync-conflict-transport-hardening/issues/01-freeze-conflict-v2-contract.md)。

## What to build

提供一个按固定 statement/query budget 批量加载完整 branch、direct base、version、media 和 provenance 的 Store 原语，消除 detail/resolution 的逐 branch N+1。

## Implementation sequence

1. 冻结输入 root/branch-set 与输出完整性、不存在/不完整错误。
2. 以固定批次装载 heads、direct bases、media 和 mutation provenance。
3. 在 Store façade 下复用同一 loader，handler 不自行查询。
4. 对高基数、缺失 parent 与并发 branch 变化 fail closed。

## Acceptance

- [x] statement count 随固定批次有界，不随 branch 数形成 N+1
- [x] root/media/deleted/base/provenance 完整或整体失败
- [x] 已 durable branch 在准入饱和后仍可读取

## Validation

- [x] high-cardinality/query-count/completeness tests 通过
- [x] Rust gates 与隔离 Store smoke 通过

## Out of scope

不定义分页、snapshot receipt、choice ID 或 merge 语义。

## Implementation evidence

- 固定 clean base `c1e05d6b3996f93549c3562961ddda356c40b605`。`conflict_detail` 与
  `resolve_conflict` 现在只消费同一个 `ConflictHeads` 深 loader；它复用 `StableSnapshot`，在同一
  SQLite transaction 内用固定 **2 statements** 装载有序 stable/branch/direct-base、完整 root、
  media、deleted 与 mutation/origin provenance。旧实现实测 1 branch 为 10 statements、64 branches
  为 388；新实现两者均为 2，且 64 个已 durable branch 在 admission 饱和与 Store 重启后仍完整可读。
- loader 对 branch 数、identity、direct-parent 数、canonical root/media bounds、causal content hash 与
  mutation provenance fail closed。`migration_base` 不套用 causal hash，也不再豁免：runtime 与
  offline migration 共用 schema-12 的 framed hash owner，合法 legacy base 可读，legacy root/media/hash
  任一漂移整体返回 `InvalidStoredPayload`。并发 branch arrival 测试以 family 第 N 条 statement 的
  有界同步证明 detail 是一个 transaction snapshot，不依赖 private SQL 文本。
- 删除 detail 的逐 branch version/media/mutation 查询、resolution 的重复 conflict/branch/version 查询、
  `compute_conflict_paths` 的逐 branch parent/base fallback，以及独立 `load_version_media` / `load_parents`
  owners；没有保留 parallel projection model、双 path 或兼容 adapter。0.3.13 source DTO 保持不变，
  未提前实现 R18 pagination/token/receipt、H02 merge 或 v2/version/schema/capability。
- Standards review **Hard 0 / Judgement 0**；Spec review **Hard 0 / Scope 0 / Judgement 0**。
- 相对固定 base：production **+63**、tests **+311**、docs/tracker **+28**；从 `d3955590`
  累计为 production **+318**、tests **+1,602**、docs/tracker **+103**。本票涉及的 production
  files 函数数 **117→121**、control-flow token **438→441**。shared migration hash 删除了
  offline-migrate 私有 `content_hash_hex`（该文件净 -9 LOC），在既有 bundles hash owner 中集中一份，
  runtime/offline 不再复制规则；本票 production 增量已从首版平行模型的 +227 收敛到 +63。
- Final gates：`cargo fmt --all -- --check`；`cargo test --locked`（237 lib、183 API、1 shared corpus）通过；
  两个 localhost TLS tests 因 restricted sandbox 拒绝 socket 后在获准的同一 tree 重跑 2/2 通过；
  `cargo clippy --all-targets --all-features -- -D warnings` 通过。另有 1/64 query-count、完整性/legacy
  corruption、transaction snapshot、restart 与 authenticated HTTP detail targeted smoke 全部通过。
  Android 未改，未运行 Android gates；未 build image/package/push，未访问或部署 NAS，未执行 CD。
