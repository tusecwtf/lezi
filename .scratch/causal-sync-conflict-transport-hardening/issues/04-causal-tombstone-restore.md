# 04 — 实现因果 tombstone restore

**What to build:** 为纯 tombstone 冲突提供可审计恢复选择，只恢复 tombstone 明确声明的直接完整 live base 及其媒体。

**Blocked by:** 03

**Status:** implemented — Standards 0/0; Spec 0/0/0; final gates pass

## Contract slice

不得搜索祖先、猜测多 parent、补造缺失 bytes，或在一次 resolution 中 restore-and-edit。

## Implementation sequence

1. 从 tombstone mutation 解析唯一 direct base identity。
2. 验证 base 是完整 live root 且全部媒体可用。
3. 生成同 root/media 的 restore choice 并走 choice-only validation。
4. 将恢复写为独立稳定版本与 provenance。

## Acceptance

- [x] complete direct base 可原样恢复 root/media
- [x] missing parent/bytes、多 parent、新 branch 和 restore+edit 被拒绝
- [x] delete/edit 双顺序、delete/delete 与 stale replay 不静默复活

## Validation

- [x] Store/API restore decision table 通过
- [x] 媒体字节一致性与 provenance assertions 通过

## Out of scope

不支持历史墓碑批量复活或智能推断原事实。

## Implementation evidence

- 固定 clean source HEAD `161dee567e706d990ca2be7d67154e532d6fde87`。删除 H03 的 pure-restore
  拒绝路径；public `Store`/Router 只消费 H02/H03 snapshot receipt 签发的 choice ID，不接受 raw
  restore root/media/value，不保留 ancestor search、parent guess、legacy restore helper 或 fallback。
- `direct_restore_base_policy` 是 mint/load/detail/resolve 共用的单一 direct-base policy：仅唯一 parent、
  同 root identity、完整且 live 的直接 base 可产生 restore handle/candidate。0-parent 不 mint；delete/delete
  形成 multi-parent stable 时关闭空 handle；事后 missing/corrupt/deleted/ambiguous base 以 typed
  `missing_restore_base` / `incomplete_restore_base` fail closed，未授权 caller 不得到 base oracle。
- 权威重建原样复用 base root/media/content hash evidence，经现有 ACL、receipt token/page-ready、完整 choice
  set、canonical/domain/reference/media-bytes validator 与 stable+branch full-set CAS；成功写独立 live version，
  parent 是当前 tombstone，并保存 resolver provenance。restore 不能夹带 edit，new branch 令旧 snapshot
  stale；并发 restore 唯一 accepted，delete/delete 与 replay 不静默复活。
- post-commit media repair 深化在既有 causal-media-staging owner 内：首次 resolve 与 exact replay 都只按
  receipt `stable_media` 定向查询/校验/修复，manifest 严格有序且最多 3 项，不做 family-wide retained-media
  scan。真实 Router fault 先制造 commit 后 promotion 失败，再插入 32 条无关 corrupt consumed rows/files；
  同一 request replay 只修目标 publication，无关 32 条保持不变。未实现 R19 retention/GC 或 H24 GC。
- public Store decision table 覆盖 exact root/media、ACL/no-write、missing/multi/deleted/corrupt base、missing/
  corrupt bytes、restore+edit、new-branch stale、delete/delete、并发唯一终态、restart/exact replay、pull
  projection 与有界 branch/choice 路径；real SQLite Router two-client smoke 覆盖跨 restart detail/resolve、
  provenance、media GET、双方 pull 收敛及 post-commit fault repair。
- 最终 Rust：`cargo fmt --all -- --check`、`cargo test --locked`（**265 lib + 185 API + 1
  shared-contract + 2 TLS**）和 `cargo clippy --all-targets --all-features --locked -- -D warnings` 全绿。
  Android wire/source 未改；`:sync:compileDebugKotlin :sync:compileDebugUnitTestKotlin` 尝试两次，均在
  `:core:database:kspDebugKotlin` 被环境的 sqlite-jdbc native loader 阻塞（不是 Kotlin/H04 compile error）。
  `adb devices -l` 可执行但无连接设备，未运行 device smoke。
- Standards fixed-point：Hard **0** / Judgement **0**；Spec fixed-point：Hard **0** / Scope **0** /
  Judgement **0**。`git diff --check` 通过。未 build image/package/
  push，未访问或部署家庭 NAS，未运行证书变更或 CD；未改 schema/version/capability/H27/H28。
