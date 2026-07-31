# 02 — 本机离线库迁移器 v3→当前

**What to build:** 在开发机上提供一次性离线工具：输入**已拷出**的 v3 `lezi.db`（及合同约定的旁路元数据），输出当前 schema 的新库，使当前 lezi-sync 预检能够打开。权威业务行按 01 对照迁移；invites/旧 credentials 丢弃；membership 不带可用设备会话。合成夹具可单测；不可映射则失败且不写半残库。

**Blocked by:** 01 — 锁定迁移合同与 v3↔v11 清单

**Status:** complete

## Acceptance criteria

- [x] 对合成 v3 夹具运行迁移后，当前服务预检/打开成功（user_version 与形状为当前合同）
- [x] families、memberships（角色与称呼）、entities、可映射 bundles 进入新库
- [x] 新库无可用旧会话/邀请；无 devices 会话或仅空壳且不能冒充已登录
- [x] 夹具含「不可映射行」时迁移失败（`Err` / 权威失败 kind），源库与目标均不被部分污染（或目标目录保持未提交）
- [x] 自动化测试覆盖成功路径与至少一类 fail-closed 路径

## Out of scope

- 媒体文件字节拷贝（见 03）
- 根密码注入细节可与 04 衔接，但本票至少产出可打开的库骨架
- 上 NAS 或 scp
- **进程 exit code / CLI**：票 05；本票交付 crate-internal `migrate_v3_database` → `Result`（`Authoritative` = 未来非零退出的库级对应）

## Notes

- 实现：`tools/lezi-sync/src/offline_migrate/migrator.rs` — `migrate_v3_database(source, dest)`
- 合同：`tools/lezi-sync/src/offline_migrate/inventory.rs`（票 01；含 `SourceConstrainedValueInvalid`、`target_only_empty_tables`、`is_discarded_bundle_status`）
- 内容哈希：`store::bundle_content_hash`（与 live commit 共用）
- 测试：`cargo test --locked offline_migrate`
- `owner_root_fingerprint` 本票为 NULL（TargetAdd → 票 04）；`server.secret` / media 字节见 03–04
- 权威失败带 `MigrateReport` 片段（`MigrateError::report()`）；半残 out/ 不产出
