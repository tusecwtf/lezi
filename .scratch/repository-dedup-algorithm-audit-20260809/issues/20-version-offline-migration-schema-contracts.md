# 20 — 版本化 offline migration schema 合同

Status: implemented

Priority: P1

Blocked by: —

## What to build

把 legacy `offline-migrate` 的 schema-12 target SQL、`user_version` 与 shape validator 从 live
`CURRENT_SCHEMA_SQL` / `DATABASE_SCHEMA_VERSION` 解耦成不可变的版本化历史合同。v3/v11 CLI 仍只
生成和验证 schema 12；未来 live current 变为 13 时不得静默改变旧迁移目标。

## Contract slice

- 一个深 `SchemaContract` owner 同时拥有 target version、frozen SQL、初始化与 exact shape preflight。
- v3/v11→12 保持 source read-only、identity/media disposition 与 CLI failure semantics。
- public help/docs 明示这是 legacy schema-12 target，不宣称 11/12→13 已实现。
- 历史 SQL 是审计所需版本快照，不与 live schema authority 合并。

## Acceptance

- [x] v3 与 v11 输出精确 `user_version=12` 和 frozen v12 shape
- [x] live current authority漂移不改变 legacy output contract
- [x] wrong target version/shape fail closed，source tree byte-identical
- [x] 不实现 schema-13 migration、capability、version bump 或 deploy

## Validation

- [x] public CLI/migrator contract tests 通过
- [x] Rust gates 与 relevant full repo gates 通过

## Evidence (2026-08-12, fixed base `a446c2428fa219fce173d61c244d1bc524254bd6`)

- `SchemaContract` 单一 owner 冻结 version 12、SQL snapshot、initialize 与 exact-shape
  read-only validate；`schema_v12.sql` 与 fixed-base `store/schema.rs` SQL body 逐字节一致，
  SHA-256 `da30bbf9d56d0a1636567264ca3f97166d1ed545bc3f3226d7a1ee956b357905`。
- RED→GREEN：`cargo test --locked legacy_target_is_frozen_independently_of_live_store_authority --lib`
  先因 `schema_contract` 缺失编译 RED；最终 `cargo test --locked offline_migrate --lib`
  为 105 passed / 0 failed。Wrong version 13、wrong shape、v3/v11 source DB/secret byte equality
  与独立 future contract 均有回归。
- Artifact gate RED→GREEN：`frozen_v12_copy_back_requires_schema12_compatible_image` 为
  0/1 失败后 1/1 通过；`bash deploy/test-copy-back-nas-data.sh` 为 17 checks passed。
  Live copy-back 在 rsync/SSH 前绑定 exact package `image_id`；push 必须
  `LEZI_SKIP_PACKAGE=1` 复用并重验同一制品。
- Fresh Rust gates 在同一 worktree 中连同冻结、未提交的 H24 WIP 一起编译，因此下列数据是
  combined-tree integration evidence，不是 R20-only 归因：`cargo fmt --all -- --check` GREEN；
  `cargo clippy --all-targets --all-features -- -D warnings` GREEN；
  `cargo test --locked` 的 lib 290/290、API 218/218、cross-contract 1/1 通过。
  仅3 个 TLS tests 在 sandbox bind 处因 `Operation not permitted` 失败；随后仅对开发机
  isolated loopback 执行 `cargo test --locked --test tls`，3/3 通过。
- `bash -n deploy/copy-back-nas-data.sh`、`bash -n deploy/test-copy-back-nas-data.sh`、
  `bash -n deploy/push-and-deploy.sh` 均 GREEN；`git diff --check` clean。
- Final sequential review：Standards Hard/Judgement `0/0`；Spec Hard/Judgement/Unclear
  `0/0/0`。Gate 暴露的 `store/mod.rs` cfg-only re-export hunk 又经窄 Standards
  `0/0` 和 Spec `0/0/0`，与冻结 H24 Store field hunk 可独立暂存。
- R20 只改 Rust/server maintenance contract 与脚本文档，未改 Android source，因此未运行
  Android/device gates。未构建 image/package，未 push，未访问 family NAS，未执行 CD；
  未实现 11/12→13 migration、capability 或 version bump。
