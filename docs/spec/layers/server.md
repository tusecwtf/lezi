# server 层规格（`tools/lezi-sync` crate）

> 身份钉：crate 版本与 `Cargo.toml` 一致（当前 0.5.5）；server schema **13**
> （`PRAGMA user_version`）；协议代以 `contracts/causal-sync-wire.md` 为准。**权界**：本文权威 = 服务端路由清单、鉴权层、
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
| Bootstrap secret / 根密码 | `X-Lezi-Bootstrap-Secret` 用于 `/v1/family/create`、`/v1/owner/login`、`/v1/owner/takeover`、`/v1/family/delete`、灾备 batches 创建与 `{id}/commit`；删除家庭先要求 Owner Bearer 会话，灾备 commit 先要求该批次 recovery token。根密码即配置的 bootstrap secret；`owner_root_fingerprint` 由服务端签名 secret 与根密码派生；refresh token 历史轮换 |
| TOFU | 证书信任在**客户端**（SPKI pin）；服务端只出证书 |
| 版本闸 | 受保护路由读 `X-Lezi-Client-Version-Code`，协议硬 floor **35** 独立于更新通道；有效 floor 为 `max(35, 已验证通道 min_supported)`，并独立校验 `nursing_plan_intent_v1`。普通受保护路径版本不足 → `client_update_required`；causal commit 折叠为 409 `capability_mismatch`（platform §4.2 诚实客户端闸） |

根密码校验入口共享 `create_rate_limit` 配置的进程内准入预算：按来源 IP 滚动窗口限流，
另有进程总预算（单来源上限的 10 倍）。准入在口令比较前执行，正确口令也不能绕过已耗尽
窗口；被限流请求不追加计数，旧计数随窗口到期释放，不设置永久账户锁。共享出口可能
暂时互相限流，应等待窗口恢复。路由的前置状态/会话/能力/版本检查仍先行：已配置服务器
拒绝建家/灾备 start，缺少有效批次凭据的 commit 不进入根密码校验。灾备 manifest、media、
status、cancel 使用批次 recovery token，不额外要求根密码。

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

### 灾备的来源关系收口

灾备 manifest 的 `source_relations` 是必填数组；缺失不是「没有分组」。每项为闭合四键：
`relation_id`、`display_client_uuid`、`source_client_uuids`、`auto_aligned`。
数组和来源 UUID 集合按稳定顺序进入不可变 manifest 哈希；改选展示版会改变内容身份，
不能借原 request ID 追加或改写。旧快照缺少关系覆盖证明时必须先停止，不能按空数组恢复。

只收录有实际成员且完整闭合的 canonical 关系。被后续组吞并后遗留的零成员表头不是组；
source-first pull 的未知展示版/缺成员状态不能猜测。每组至少两个不重复成员，各组互斥，
每个 UUID 必须有实际 record 根；不能只凭 peer UUID 捏造护理事实。历史成员即使不再满足
当前近邻规则仍保留。保留历史删除的展示版/来源时，将其真实 tombstone record 以及
baby/custom item/effective wake 等实际依赖根纳入快照，保留 `deleted_at`，不得复活、剪掉
成员或另选展示版。tombstone 仅允许作为显式关系的可达历史上下文；媒体仍按实际字节清单。

新 Owner 激活在同一 SQLite 事务中写根的 accepted baseline、原关系 ID、展示/来源成员与
自动标记。不运行新的近邻推断，原来未分组的记录仍未分组。既有 schema13 的
`source_relations` / `source_relation_members` 足够表达；不增加表、列、索引或版本。
`reason=owner_group_resolve`、当前 Owner 和激活时间表示本次恢复采纳，不冒称恢复出旧
reason/作者/时间。`auto_aligned` 保留历史自动属性；旧 mutation receipts / declarations
不跨权威导入，canonical 成员本身没有待重映射的 version ID。

全组与根的原子激活由持久化批次 manifest 哈希、确定性初始 accepted baseline，以及既有
`mutation_receipts` 中恢复专用命名空间的一条常量大小覆盖收据共同见证。收据只保存格式版本、
批次/manifest request ID、服务端实际不可变 manifest SHA256、规范化关系 SHA256、组/成员数；
不复制完整成员数组、护理 payload 或旧关系操作收据。即使显式空组也写覆盖证明。旧二进制
把该保留命名空间当作无关收据；旧批次缺覆盖证明仍可读，保留为不支持无损续传的旧状态，
不得改写旧哈希或在同一个请求后补关系。
提交应答丢失后重放相同批次不追加关系；之后正常改选不应因与旧快照不同而使已提交批次失效。

恢复中间文件兼容性（US-093）：仅新建服务器批次使用 journal `protocol_version=2`；HTTP
恢复响应的 `protocol_version=1`、普通同步 wire、SQL schema13 均不变。旧服务端因此拒绝
继续新批次，而不是忽略来源关系后只导入护理事实。新版可读旧 journal1，但不转换、不在
旧批次下补写 relation-aware manifest；保留其快照和媒体供检查/取消，重新恢复须显式新建
尝试。旧版的启动清理可能处理尚未激活的最终媒体残留，所以此标记不构成安全回滚机制：
新恢复进行中不得换旧服务端维护。兼容性结论必须区分已激活共享领域数据库和进行中批次。

新恢复的 baby 与 record/care_plan/custom_item 一样，在建立新权威 accepted baseline 时
明确标记本次 Owner 为采纳作者，使随后正常编辑、分支和冲突详情具有完整作者字段。
不回写任何旧激活根或旧不可变版本，也不把缺失历史作者臆造为当前 Owner。

历史睡眠上下文按身份闭合而非重新裁决当前有效性：独立作者撤回/删除醒来观察可保留
SleepStart 上的原有效观察指针；之后改睡眠起点也可使既存观察早于当前起点。灾备保留
这些真实事实，由正常投影忽略当前无效观察；仍拒绝缺失目标或跨睡眠引用。普通新提交的
醒来合法性检查不变。

### 当前来源组只读投影

新增 `POST /v1/source-relations/current`，仅供已确认操作后的现状刷新，不改既有 mutation
请求/静态回执、普通 pull 或 SQL13/Room29。请求闭合字段：`protocol_version:1`、
`family_id`、`generation`、`record_client_uuids`（1–64 个不重复规范 UUID）。同 pull 的家庭
鉴权/版本门槛；家庭匹配后校验 generation，家庭锁后重新鉴权。请求最多 64 KiB。

一次只读 SQLite 事务返回闭合对象：`protocol_version:1`、`family_id`、`generation`、
`head_rev`（同事务真实 `family_meta.rev`）、排序后的 `requested_record_client_uuids`、
`records`、`source_relations`。每条 record 为 `{record_client_uuid, record_state,
relation_id}`，状态 `live|deleted|missing` 与关系成员身份独立；无关系时 relation_id 为 null。
每组为 `{relation_id, display_client_uuid, source_client_uuids, media_retained:true,
auto_aligned}`；返回完整当前成员，不根据旧回执重建，不返回无成员的历史遗留表头。

查找只用现有按 record 的 unique index、按 relation 的成员主键及 entity 主键。最多 64 组、
每组 2–64 个成员，整体闭包最多 4096 个 record UUID；每组读第 65 条作为溢出哨兵，绝不
截断组。每个标识符最多 128 字节（record/relation 另须规范 UUID），最终 UTF-8 JSON 最多
2 MiB。不存在的请求 UUID 明确返回 missing；成员行引用不存在 record、表头/角色/展示版
不一致等情况拒绝整个投影。无业务写入、无回执或 cursor 推进。

错误：422 `invalid_source_relation_scope`；协议不支持为 422
`source_relation_protocol_unsupported`；家庭不匹配 403 `source_relation_family_mismatch`；
generation 不匹配 409 `generation_mismatch`；存储投影不一致 409
`source_relation_projection_invalid`；闭包/字节容量超限 413
`source_relation_projection_limit_exceeded`。旧服务端 404/501 表示需升级原服务器后再刷新，
不得退回静态回执或若干普通 pull 页猜测不存在/完整分组。客户端还须覆盖旧本地组的所有
成员，最终仅用一次完整范围的同快照响应完成原子对账，不能拼接多个时点的局部响应。
