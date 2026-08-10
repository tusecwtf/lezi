# 18 — 持久化 snapshot receipt 与有界分页

Status: implemented — Standards 0/0; Spec 0/0/0; final gates pass

Priority: P1

Audit baseline: `38cfbe7dbcf1f410237dce77b50ec1bd4854d8a6`

Blocked by: 17；以及 [`causal hardening 01`](../../causal-sync-conflict-transport-hardening/issues/01-freeze-conflict-v2-contract.md)。

## What to build

为完整 branch-set fingerprint 签发持久随机 snapshot receipt，并按 count/encoded-byte budget 提供稳定 continuation。receipt 跨重复 detail、分页和服务重启有效，直到过期或 stable/branch set 变化。

## Implementation sequence

1. 冻结 receipt binding、TTL、continuation 与 page budget。
2. 持久化 family/root/stable/branch fingerprint/contract/expiry。
3. 用票 17 loader 生成不重不漏的有界 raw page。
4. 对重启、过期、新 branch、stable 变化和篡改返回 typed stale/error。

## Acceptance

- [x] 每页同时满足 count/encoded-byte budget
- [x] continuation 遍历完整 raw head set，不重不漏
- [x] receipt 跨重启稳定，state change/expiry 明确 stale
- [x] partial page 从不授权 partial-set resolution

## Validation

- [x] continuation/response-budget/restart/tamper tests 通过
- [x] Rust gates 与隔离分页 smoke 通过

## Out of scope

不生成 semantic candidate/choice ID，不定义 N 方 merge。

## Implementation evidence

- 固定 clean source HEAD `86c7fab0d37ce3ab7ae6e6a4e261d46d835476f8`。实现只深化
  现有 schema-12 私有 `mutation_receipts` 与 0.3.13 raw detail seam；未新增 schema/table、
  version/capability advertisement，也未提前实现 H02 semantic candidates 或 H03 choice-only
  resolution。
- `Store::conflict_detail_page` 只消费票 17 的完整 bounded head projection。snapshot deep
  module 独占 family/root/contract/stable/full sorted branch fingerprint、10 分钟 receipt lifecycle、
  最多 64 个 receipt 的有界历史、每页最多 16 个 raw heads、最终 typed JSON 128 KiB budget、
  持久 page cuts/page digests 与 independent opaque continuation binding。
- production Store 使用现有 server signing secret 派生的 off-DB HMAC key；SQLite 仅持久随机
  nonce 与认证后的 plan/digest，不持久 raw token。snapshot token、continuation、receipt layout
  和 serializer contract 均 domain-separated、length-framed、constant-time 验证。相同 token/
  continuation 的重复读取与 restart 返回 byte-identical page；tamper、expiry、stable/branch drift
  分别 fail closed。
- partial page 的 branch list 不能通过既有 full-set resolution CAS。family-scoped test hook 只在
  SQLite `BEGIN IMMEDIATE` 实际收到 BUSY 后确认 follower/writer 已争锁，并以 5 秒 timeout、
  poison-tolerant 幂等清理证明 concurrent First 只生成一个 receipt，branch writer 后旧 token
  明确 stale。
- 已删除旧一次性 `ConflictDetail` DTO/Store path、全量一次 serialize-size path、可逆
  token+offset continuation、临时 read-pause harness 与相关旧测试；ordinary mutation receipt
  保持 INSERT-only。HTTP handler 只做 typed query/error mapping，最终 wire 与 byte accounting
  使用同一个 serde render seam。
- 最终 Rust：`cargo fmt --all -- --check`、`cargo test --locked` 的 245 lib + 183 API +
  1 shared-contract + 2 TLS 用例在同一最终 source 上全部通过；
  `cargo clippy --all-targets --all-features -- -D warnings` 通过。最终双轴 review 为
  Standards 0/0、Spec 0/0/0。隔离 HTTP smoke 覆盖 64-head
  全分页、页 byte budget、same-continuation replay、server restart、tamper、`snapshot_stale` 与
  `snapshot_expired`；Store smoke 覆盖两客户端、真实 SQLite BUSY、restart、plan/serializer
  tamper 与 64-entry retention。
- Android 未改，未运行 Android gate。未 build image/package/push，未访问或部署家庭 NAS，
  未运行证书变更/CD；R19 仍独占 conflict metadata 的物理 retention/GC。
