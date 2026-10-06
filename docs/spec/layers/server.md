# server 层规格（`tools/lezi-sync` crate）

> 身份钉：crate 版本与 `Cargo.toml` 一致（当前 0.5.3）；server schema **13**
> （`PRAGMA user_version`）；协议代 0.4.0。**权界**：本文权威 = 服务端路由清单、鉴权层、
> `Store` 因果图与合并算法、媒体/冲突/来源关系机制。**wire 字段、枚举与闭合键集唯一权威
> = [`contracts/causal-sync-wire.md`](../contracts/causal-sync-wire.md)**；部署/CD/回滚唯一
> runbook = [`tools/lezi-sync/deploy/DEPLOY.md`](../../../tools/lezi-sync/deploy/DEPLOY.md)
> （本文不复制运维步骤）。签名唯一权威是代码。

---

## 1. 职责与边界

自托管家庭同步服务端：Axum + rustls + SQLite，单二进制、单卷 `DATA_DIR`（db + media）。
布局：crate 根 + 单一 `Store` 事务面（deep façade）；crate-private `handlers::*`、
`store::{schema,identity,bundles,media,causal,…}`；`offline_migrate/` 为独立维护窗 CLI。
Rust 门禁：`cargo fmt --all -- --check`、`cargo test --locked`、
`cargo clippy --all-targets --all-features -- -D warnings`。

## 2. 监听与拓扑（`src/main.rs`）

| 监听 | 用途 |
|------|------|
| `LEZI_HOST`:`LEZI_PORT`（默认 **8765**，rustls HTTPS；证书 `LEZI_TLS_CERTFILE`/`LEZI_TLS_KEYFILE`） | 公开 LAN 同步面（TOFU/SPKI 目标） |
| localhost **8766** | 容器内健康/就绪（不映射主机；容器 readiness 用 `lezi-sync healthcheck`） |
| 可选 **8767**（`LAN_APK_DOWNLOAD_PORT`） | 隔离 LAN HTTP 邀请安装页（`/join` + `/download/lezi.apk`；与 HTTPS invite origin 互斥，platform §4.3） |

优雅关停 30s；维护只读中间件（`maintenance_read_only`）；body limit 与 timeout layer 全局生效。

## 3. 路由清单（`src/lib.rs` router）

匿名：`/health`、`/ready`、`/v1/setup-status`、`/v1/app-update{,/apk}`（鉴权元数据/APK）、
邀请页 `/join`、`/download/lezi.apk`（8767/HTTPS-origin）。

建家/身份：`/v1/family/create`（X-Lezi-Bootstrap-Secret 头门禁）、`/v1/owner/login`、
`/v1/owner/takeover`、`/v1/session/refresh`、`/v1/leave`、`/v1/device/logout`、
`/v1/family/delete`（根密码）。

成员/设备：`/v1/member/requests{,/cancel,/claim,/status,/{id}/approve-new,/{id}/bind-existing,/{id}/reject}`、
`/v1/member/login-grants{,/claim}`、`/v1/family/members`、`/v1/family/members/{id}/display-name`、
`/v1/family/members/remove`、`/v1/family/rename-requests{,/cancel,/{id}/approve,/{id}/reject}`、
`/v1/family/devices/{id}/display-name`、`/v1/family/devices/{id}/revoke`、
`/v1/family/display-name`、`/v1/family/name`。

同步：`/v1/sync/handshake`、`/v1/sync/heartbeat`（0.4.8；wire §1.5：闭合三键、专属限流
scope、锁外构造响应）、`/v1/push`（**已退役**仍挂载；恒 `422`，新客户端不得使用）、
`/v1/pull`（活跃普通增量页端点，wire §1.4）、`/v1/causal/commit`、
`/v1/causal/media/{client_uuid}`、`/v1/media/{client_uuid}`（GET 活跃；PUT **已退役**仍
挂载，恒 `422`）、`/v1/bundles{,/{id},/{id}/commit}`。

冲突/关系：`/v1/conflicts/{id}{,/resolve,/withdraw}`、
`/v1/source-relations/{declare,resolve-group}`。

灾备：`/v1/disaster-restore/batches{,/{id}/manifest,/{id}/media/{uuid},/{id}/status,/{id}/commit,/{id}/cancel}`。

## 4. 配置与鉴权

`ServerConfig`（`src/lib.rs` `from_env()`）：`data_dir`、`version`、`max_media_bytes`、
`server_secret`、`generation`、**`bootstrap_secret`（env `LEZI_BOOTSTRAP_SECRET`，生产必填，
fail closed）**、限流 `create/member_request/heartbeat`（心跳默认 30 次/60s 窗口）、
`member_request_ttl_hours`、`max_pending_member_requests`、`app_update_metadata_path` /
`app_update_apk_path`（更新通道，platform §4.2）、`require_protocol_cutover_release`、
`lan_apk_download_origin` / `invite_install_origin`（互斥校验，违例进程不启动）、
`maintenance_read_only`、causal media hooks。

| 鉴权层 | 机制 |
|--------|------|
| Bearer 会话 | `authenticate()`（`auth_cache` 60s last_used_at 去抖）；设备独立 opaque session |
| Bootstrap secret | `X-Lezi-Bootstrap-Secret` 头仅在 `/v1/family/create`；owner 根密码 + `owner_root_fingerprint` 派生自 bootstrap secret；refresh token 历史轮换 |
| TOFU | 证书信任在**客户端**（SPKI pin）；服务端只出证书 |
| 版本闸 | 受保护路由读 `X-Lezi-Client-Version-Code`；低于 `min_supported` 且更新通道已验证 → `client_update_required`（platform §4.2 诚实客户端闸） |

## 5. `Store` 因果图与合并算法（`src/store/`）

- **版本 DAG**（`causal.rs`）：`entity_versions` / `entity_version_parents` /
  `entity_version_media` / `entity_stable_heads` / `mutation_receipts`。commit 携带期望
  `stable_version_id` + `expected_branch_version_ids`；并发分叉返回 **`branched`** 回执并开
  分支（每根最大开分支数受 `max_open_causal_branches_per_root` 限制）；record commit 后
  自动对齐（auto-align，ADR-0023）。
- **三方合并**（`causal_merge.rs`）：`three_way_merge` + `mutation_content_hash` 产出
  `MergeDecision`；merged 路径与字段权威 = wire §9。
- **准入**（`causal_admission.rs` `admit_new_branch`）：分支数/回执/能力校验。
- **冲突快照**（`conflict_snapshots.rs` + `conflict_retention.rs`）：N-way ConflictSnapshot
  v2、choice-only resolution CAS（wire §8）、保留策略与裁决审计。
- **来源关系**（`source_relations.rs` + `suspected_duplicates.rs`）：近邻同型自动对齐
  （展示版 + 来源）、作者声明 / Owner 组裁决（wire §12、ADR-0023）。
- **媒体两阶段**（`causal_media_staging.rs` + `media.rs` + `bundles.rs`）：bundle stage →
  commit 原子发布；sha256 preimage（`putCausalMediaPreimage`）；staging GC。
- **拉取**（`pull.rs` + `live_census_cache.rs`）：分页 + 检查点 + 活集 census；census
  按 (family, head) 进程内缓存，同一 head O(1) 命中。失效只经 `advance_rev` 自增漏斗与
  绝对 rev 写入的无条件失效；只读 store 不安装缓存。`close_empty_open_conflicts` 不在
  pull 读路径，而在写事务尾 + 启动/周期维护。权限投影（member 只见 Baby 的写限制在写
  路径，读全量共享）。
- **灾备**（`restore.rs` + `handlers/disaster_restore.rs` + `restore_locks.rs`）：批次
  manifest/media/commit；staging/journal 完整校验后 `Store` façade 单事务激活；schema
  user_version 不变。
- **身份**（`identity/`：`login.rs`、`session.rs`、`membership_admin.rs`、`anonymize.rs`）。
- **schema**（`schema.rs`）：`DATABASE_SCHEMA_VERSION = 13`，WAL + 外键，~30 表
  （families/memberships/devices/device_sessions/refresh_token_history/login requests+grants/
  family_meta/entities/sync_bundles/media_publications/causal 系/conflicts/source_relations 系）；
  **fresh-only**：startup 校验精确当前 `user_version`，不匹配 fail closed（ADR-0008）；
  历史 v11/12 只经 `offline_migrate/` 维护窗 CLI（ADR-0013）。

## 6. 测试契约

- 单元：`src/store/tests/` + 内联 `#[cfg(test)]`（三方合并、准入、限流、schema）。
- 集成：`tests/api.rs`、`tests/tls.rs`、`tests/conflict_v2_contract_fixture.rs`——后者与
  Android 侧 `ConflictV2GoldenCorpusTest` 共享黄金语料 `config/conflict-v2-golden.json`
  （跨语言合同锚）。
- 真服务端 seam：Android `sync`/`domain` 的 `IsolatedLeziSyncServer` 拉起本 crate 二进制。
- 发布烟测：`deploy/test-package-nas-app-update.sh`（APK+元数据 fail-closed）、
  `deploy/test-nas-release-identity.sh`（发布身份）。

## 7. 代码连线

| 本文章节 | 代码 |
|----------|------|
| §2 监听 | `tools/lezi-sync/src/main.rs` |
| §3 路由 | `tools/lezi-sync/src/lib.rs`（router 组装）+ `src/handlers/` |
| §4 配置/鉴权 | `src/lib.rs`（`ServerConfig`/`from_env`）、`src/store/identity/` |
| §5 因果图/合并 | `src/store/{causal,causal_merge,causal_admission,causal_media_staging,conflict_snapshots,conflict_retention,source_relations,suspected_duplicates,pull,live_census_cache,bundles,media,restore,schema}.rs` |
| §6 测试 | `src/store/tests/`、`tests/`、`deploy/test-*.sh` |
