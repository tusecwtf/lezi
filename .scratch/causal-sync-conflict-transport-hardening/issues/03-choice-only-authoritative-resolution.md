# 03 — 落地 choice-only 权威 resolution

**What to build:** 让客户端只提交 snapshot token、resolution mutation ID 和 choice IDs，由服务端独立重建并权威验证稳定结果。

**Blocked by:** 02

**Status:** implemented — Standards 0/0; Spec 0/0/0; final gates pass

## Contract slice

选择必须恰好覆盖完整 conflict path set；服务端执行 ACL、full-set CAS、canonical/domain/media validation。resolution replay 在 closed-conflict 检查前按 request hash 返回原终态。

## Implementation sequence

1. 收缩输入并为 resolution mutation 持久化 request hash 与原结果。
2. 从 snapshot receipt 验证每个 choice 的成员关系和完整性。
3. 复用普通 commit 的 canonical、领域、媒体和稳定投影构建路径。
4. 原子 CAS 提交并在丢响应后支持精确 replay。

## Acceptance

- [x] missing/duplicate/foreign choice 与 payload drift 被拒绝
- [x] stale stable/new branch/expired token/ACL fail closed
- [x] resolution 不接受客户端 resolved root/media
- [x] closed conflict 的精确 replay 返回原终态加 replay marker

## Validation

- [x] Store/API tamper、ACL、lost-response/replay tests 通过
- [x] 普通 commit 与 resolution 共用 validator corpus

## Out of scope

不提供 restore 特例或 Android UI。

## Implementation evidence

- 固定 clean source HEAD `f7fc5a19ff4932adcd8ee590ee3a9106d4dc8323`。Rust resolve 输入只保留
  `snapshot_token`、canonical UUID `resolution_mutation_id` 与按 path 字典序的
  `{path, choice_id}`；请求在 transaction/hash/receipt lookup 前限制 token/choice ID 形状、path
  长度与至多 64 项。route-local bounded raw classifier 对 legacy/unknown/missing/wrong-type 请求返回
  §9.5 closed terminal envelope，不存在兼容 DTO、旧 root/media/value parser 或 fallback。
- 服务端从 H02/H18 持久 snapshot receipt 重建完整 root/media/deleted，验证 ACL、receipt integrity/
  expiry/readiness、choice membership/全 path 覆盖与 stable+完整 branch set CAS。live 与 concurrent
  tombstone 成功用例分别证明 media add/remove 与 deleted 选择；tombstone 结果权威清空 media。普通
  commit 与 resolution 共用 canonical root、domain、media/reference validator 及持久版本完整性算法。
- resolution receipt 在 closed-conflict 检查前按 canonical request hash replay。stored result 与 schema-12
  audit 列、closed conflict、真实 resolved version/entity/client/direct parent/root/media/content hash 和
  provenance 必须完全自洽；同 mutation ID 内容漂移、协调篡改 receipt+version、restart/replay 均有负向
  或正向 public Store/Router 证据。真实 SQLite contention test 证明并发 resolution 唯一 accepted，loser
  前后完整相关持久状态不变。
- H04 pure tombstone restore 仍只返回 `missing_restore_base`，未实现 restore write；Android 旧 0.3.13
  resolver 由 H06/H26 后续迁移/删除，本票未增加 deprecated adapter。未改 schema/version/capability，
  未激活 H27/H28，也未实现 H19 retention/query budget。
- Targeted public seams：choice-only Store tamper/ACL/bounds/restart/corruption/concurrency/live-media/
  tombstone/replay 与 shared-validator tests 全绿；real Router closed malformed envelope、65/oversize、ACL/
  stale/replay、two-client pull convergence、media+tombstone tests 全绿。
- 最终 Rust：`cargo fmt --all -- --check`、`cargo test --locked`（**258 lib + 184 API + 1
  shared-contract + 2 TLS**）与 `cargo clippy --all-targets --all-features -- -D warnings` 全绿。相关
  Android `:sync:compileDebugKotlin :sync:compileDebugUnitTestKotlin` **41 tasks** 成功；Android source 未改。
  `adb devices -l` 可执行但没有连接设备，因此未运行 device smoke。
- Standards fixed-point：Hard **0** / Judgement **0**；Spec fixed-point：Hard **0** / Scope **0** /
  Judgement **0**。`git diff --check` 通过。未 build image/package/push，未访问或部署家庭 NAS，未运行
  证书变更或 CD。
