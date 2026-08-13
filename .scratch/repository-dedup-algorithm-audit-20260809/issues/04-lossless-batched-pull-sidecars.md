# 04 — 完整 sidecar 参与分页，禁止 cursor 跳项并批量读取

Status: implemented — validated working tree on `1328f95b`; commit pending

Priority: P1

Audit baseline: `38cfbe7dbcf1f410237dce77b50ec1bd4854d8a6`

Blocked by: None.

## Findings

- `store/pull.rs:493-559` 先选实体、计算 8 MiB、推进 `page_cursor`，`:560-621` 才附 version、
  conflict 与 source-relation sidecar。
- 第 33 个 conflict 在 `:577-579` 被 `continue`，但 cursor 已越过该实体；下页不会再返回，summary
  永久不可发现。该 `continue` 也跳过后面的 source relation attachment。
- branch IDs、relation peers 等最终 JSON 字节未计入预算。每实体查 stable head/open conflict，冲突再查
  branches，record 再查 relation+peers，形成 3–5N SQL。

## Interface boundary

pull planner 必须对“最终完整 envelope”分页；sidecar 不是事后装饰。一个批量 projection query 应一次
装载本页 stable heads、conflicts/branches、relations/peers，并在决定 cursor 前计算 count/byte budget。

## Acceptance

- [x] 31/32/33 个独立 conflict 跨页，每个 mandatory summary 恰好可发现且不被 cursor 跳过
- [x] 达到 32 summary 时在下一个未披露实体 rev 之前截页，或采用等价且 frozen-wire-compatible 的
  continuation；不得静默省略 mandatory summary
- [x] source relation 不因 conflict cap 被跳过；stable-version-unchanged sidecar durable write 可重新发射
- [x] `cursor`、`has_more`、revision gap、重拉与 page restart 都不丢、不重复终态
- [x] byte budget 以最终 API JSON envelope 为准，包括 branch IDs 和 relation peers；超大单实体 fail closed
- [x] SQL statement count 对本页实体数保持常数级批量，不随 N 线性增长

## Validation

- [x] Store pull boundary/query-count tests 与 API response-size tests 通过
- [x] Rust fmt/test/Clippy 通过
- [x] 两客户端从 sidecar 写入前 cursor 增量拉取的隔离 smoke 通过

## Evidence

- `pull_paginates_every_mandatory_conflict_summary_without_cursor_loss` 覆盖 31/32/33、
  conflict cap 与 relation summary 同页/跨页发现。
- `source_relation_write_reemits_unchanged_stable_roots_after_the_prior_cursor` 证明关系写入推进
  两个未改 stable root 的 rev 并从旧 cursor 重发。
- `pull_sql_statement_count_is_constant_for_conflicted_record_pages` 实测 1 与 100 个带 conflict 的 Record roots
  都是 3 条 family-scoped SQL statement。
- `two_clients_incrementally_pull_lossless_sidecar_pages_across_restart` 使用隔离 Router/SQLite、
  两个真实 membership，从 sidecar 写入前 cursor 拉取；同页重拉 byte-identical，restart 后续页，
  33 个终态恰好发现，且每个最终 HTTP envelope ≤ 8 MiB。
- `cargo fmt --all -- --check`、`cargo test --locked`（213 lib、171 API、2 TLS）、
  `cargo clippy --all-targets --all-features -- -D warnings` 与 `git diff --check` 通过。
- 未运行 `build-image.sh`、package、push、NAS CD；本票只要求开发机隔离服务 smoke。
