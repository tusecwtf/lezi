# Spec · NAS v3 离线一次升级（拷出 → 本机转换 → 拷回）

**Status:** ready-for-agent  
**定位:** **仅本家庭 NAS 私有运维**，不进入通用产品合同；日常 lezi-sync 启动仍 fail-closed（只接受当前 schema），**从不**在启动路径自动升级。

## 权威来源

| 层 | 角色 |
|----|------|
| **机器可读合同** | `tools/lezi-sync/src/offline_migrate/`（crate-internal）— **后续票只引用此模块** |
| 本文 | 人类叙事与运维摘要；**不得**与 inventory 冲突；新增权威失败须改 inventory + 本文 |

锁定常量：`SOURCE_USER_VERSION = 3`，`TARGET_USER_VERSION = store::DATABASE_SCHEMA_VERSION`（当前 11，与 store 耦合）。

## 背景（实测）

- 现网 `lezi-sync` 数据卷：`/tmp/zfsv3/sata1/13096920600/data/Docker/lezi/data` → `/data`
- 现网库：`PRAGMA user_version = 3`；表含 `invites`、`membership_credentials`；**无** v11 的 `devices` / `device_sessions` 等
- 冻结源 DDL：`SOURCE_V3_SCHEMA_SQL` / `source_v3_tables()`（历史 `2c6bbf6` 时 `user_version=3` 的 CURRENT_SCHEMA_SQL）
- 现网协议：明文 HTTP `:8765`；当前客户端与 HEAD 服务端为 **HTTPS + device session（schema v11）**
- 现网运行镜像 id 与本机已打 TLS 包镜像 id **不同**（同 tag `0.3.0` 不足为凭）

## 成功标准

1. 现网家庭**权威业务数据**在 cutover 后仍由**当前** trusted-sync 服务提供（HTTPS）。
2. 旧会话/邀请/凭证**全部作废**；owner 用**迁移时设定的新根密码**重登；成员走**现行**申请/批准或登录授权流程（全员重登，无静默恢复）。
3. 流程固定为（顺序不可颠倒）：
   1. **从 NAS 拷出**整份 data 目录作为不可变备份；
   2. **在开发机**对备份副本做**一次性** v3→当前 schema 转换；
   3. **校验**新库可被当前 lezi-sync 打开且 fail-closed 规则通过；
   4. **维护窗**停服，将升级后的 data **拷回** NAS（或替换绑定目录内容），启动当前 TLS 镜像；
   5. 人工清单验收 + 本地 APK 联调。

## 产品边界（写死）

| 条款 | 合同 |
|------|------|
| 通用产品合同 | **不**进入；ADR-0008 fresh-only 仍成立；本路径仅为私有运维破例 |
| 日常服务启动 | 仍 **fail-closed**：非空非 v11 / 形状不匹配 → 拒绝打开，**不**自动升级（`StartupUpgrade::Forbidden`） |
| 升级执行位置 | 仅开发机对**已拷出**备份的离线工具；不在 NAS 容器启动路径内嵌 |
| 半残结果 | `FailureMode::AbortNoCopyBackWithReport`：不产出可拷回 out/ + 人类可读报告 |

## 会话策略（写死）

| 角色 | cutover 后 |
|------|------------|
| 全部旧凭证 | `LegacyCredentialDisposition::DiscardAll`（credentials / invites / v3 device_id 绑定） |
| Owner | `OwnerReauth::NewRootPasswordAtMigration`（票 04） |
| 成员 | `MemberReauth::CurrentRequestOrGrantFlows` |
| devices / device_sessions | `DeviceSessionAfterMigrate::NoneUsable` |

## 数据目录旁路（`path_dispositions`）

| 路径 | 处置 |
|------|------|
| `lezi.db` | **TransformWriteOut** — 变换写入独立 out/（不就地改 backup） |
| `media/{family_uuid}/{media_uuid}` | **KeepWithDbValidation**；被保留 publication / committed bundle_media 引用则缺失/不一致 → 失败（票 03） |
| `server.secret` | **`RegenerateAlways`** — 永不拷贝旧 HMAC/签名材料 |
| `tls/` | **AbsentOrCreateAtCutover**（票 06） |
| `app-update.json` / `app-release.apk` | **OpsOptionalIgnore** |

## 处置代数（FieldDisposition）

| 枚举 | 含义 |
|------|------|
| `Keep` | 类型兼容原样拷贝 |
| `Validate` | Keep + CHECK/model 门闩；失败 → 权威失败 |
| `Derive` | 纯函数派生目标列（如 `display_name_key`） |
| `DropColumn` | 源列不进入目标 |
| `TargetAdd` | 仅目标列（非 v3 源） |

**行级**过滤用 `RowFilter`，**不是**字段 Transform：

- `sync_bundles`：`DiscardRowsWhenEquals { column: "status", value: "staging" }` — 丢弃 staging 整行并报告，**单独不失败**；字段表只描述**保留行**（committed）。

## 表级对照

| 表 | kind | row_filter | 说明 |
|----|------|------------|------|
| families | KeepOrTransform | All | + `owner_root_fingerprint` TargetAdd |
| memberships | KeepOrTransform | All | DropColumn `device_id`；Derive `display_name_key` |
| membership_credentials | Discard | — | |
| invites | Discard | — | |
| family_meta | KeepOrTransform | All | |
| entities | KeepOrTransform | All | payload Validate + 规范化写出 |
| sync_bundles | KeepOrTransform | discard staging | committed 保留 |
| sync_bundle_media | KeepOrTransform | All | 再经 staging cascade 过滤 |
| media_publications | KeepOrTransform | All | 再经 staging cascade 过滤 |
| devices…member_rename_requests | TargetOnlyEmpty | — | 空壳会话表 |

源表 **allowlist** = `source_v3_tables()`；`UnknownSourceTablePolicy::FailClosed` — 非 allowlist 用户表整次失败。

### 字段摘要（完整列见 `field_mappings()`）

#### families
| 源 | 目标 | 处置 |
|----|------|------|
| id, created_at, create_request_hash, name | 同名 | Keep |
| — | owner_root_fingerprint | TargetAdd（票 04） |

#### memberships
| 源 | 目标 | 处置 |
|----|------|------|
| membership_id, family_id, display_name, left_at | 同名 | Keep |
| role | role | Validate |
| display_name | display_name_key | Derive |
| device_id | — | DropColumn |

#### entities
| 源 | 目标 | 处置 |
|----|------|------|
| family_id, client_uuid, updated_at, deleted_at, rev | 同名 | Keep |
| entity_type | entity_type | Validate |
| payload_json | payload_json | Validate（见载荷合同） |

#### sync_bundles（仅 retained / committed）
| 源 | 目标 | 处置 |
|----|------|------|
| 多数标量列 | 同名 | Keep |
| status | status | Keep（已是 committed） |
| staged_membership_id, root_type | 同名 | Validate |
| root_payload_json | 同名 | Validate + 规范化写出 |
| media_entities_json | 同名 | Validate（parse + AtomicBundleMedia + 与 root 一致性） |
| content_hash | 同名 | Validate（规范化后重算须一致） |

#### media / publications
| 源 | 目标 | 处置 |
|----|------|------|
| sync_bundle_media.* | 同名 | Keep（仅保留 bundle） |
| media_publications.source | source | Validate（已在 CHECK 内，**无值域变换**） |
| 其余 publication 列 | 同名 | Keep |

#### invites / credentials
全部列 **DropColumn**（表 Discard）。

## Staging cascade（写死）

`StagingCascade::DropDependentsReportOrphanBytesIgnore`：

1. 丢弃 `status=staging` 的 `sync_bundles`（报告计数）→ **不单独失败**。
2. 丢弃这些 `bundle_id` 的全部 `sync_bundle_media`（报告）。
3. 丢弃 `media_publications` 中 `bundle_id` 属于上述丢弃集合的行（任意 `source`，报告）。
4. **保留**仍可达且可服务的 publication：`source=ordinary`，或 `source=bundle` 且 `bundle_id` 指向**已保留 committed** bundle。
5. **丢弃** `source=bundle_pending` 且 `bundle_id` 指向已保留 committed 的 publication（以及匹配的 `sync_bundle_media`）——与现网 `finalize_committed_pending_bundle_media` 一致，属不可服务的 cleanup evidence；**仅报告，不失败**，且**不作为**票 03 文件权威。
6. **仅被丢弃 staging / committed-pending cleanup 引用**的 `media/` 字节：**忽略 + 报告**，不因此失败。
7. 被保留 publication（`ordinary`|`bundle`）/ 保留的 committed bundle_media 引用的文件缺失或不一致 → **权威失败**（票 03）。

## 载荷 / 校验合同（写死）

`payload_validation_policy` + `entity_validation_context`：

| 规则 | 值 |
|------|-----|
| `media` 实体 | `EntityValidationContext::AtomicBundleMedia` |
| 其它实体类型 / bundle root | `EntityValidationContext::AtomicBundleRoot` |
| 墓碑（`deleted_at` 有值） | **仍校验**（tombstones 不跳过） |
| 写出 payload | **持久化校验后的规范化 JSON**（含默认填充） |
| committed bundle | 规范化 root + media 后 **重算并校验** `content_hash`；`media_entities_json` 必须通过 media 校验与 root 一致性 |

## 权威失败集合（闭合）

`AuthoritativeFailure`（**闭合集合**；新增须改 inventory，禁止在 02+ 私自扩展语义）：

- `SourceUserVersionNotThree`
- `SourceShapeMismatch`（allowlist 表缺列/类型/空值/PK 不符）
- `UnknownSourceUserTable`
- `SourceConstrainedValueInvalid`（受 CHECK/域约束的文本不在闭合集合内，如 bundle status / publication source）
- `InvalidMembershipRole`
- `UnknownEntityType`
- `PayloadValidationFailed`
- `ActiveDisplayNameKeyConflict`
- `NotExactlyOneActiveOwner`（每家恰好一名 `left_at IS NULL` 的 owner）
- `OrphanAuthoritativeForeignKey`
- `FamilyMetaMissingForFamily`
- `MediaEntitiesJsonInvalid`
- `ContentHashMismatchAfterCanonicalize`
- `MediaFileMissingOrMismatch`（票 03 细化测量；仅对保留的 `ordinary`|`bundle` publication 与保留的 committed bundle_media）

失败时：`FailureMode::AbortNoCopyBackWithReport`。

## 执行形态

```text
[现网 NAS data] --scp/rsync--> [本机 backup/ 只读]
                                    |
                                    v
                           migrate 工具（一次）
                                    |
                                    v
                           [本机 out/ 当前 schema data]
                                    |
                         校验：当前二进制 preflight + ready
                                    |
维护窗：停容器 --> 备份 NAS 再确认 --> 拷回 out/ --> 启 TLS 新镜像
                                    |
                                    v
                         人工清单 + APK TOFU + 新根密码登录
失败：用拷出备份恢复 NAS data + 旧镜像，恢复 HTTP v3 服务
```

## 非目标

- 通用跨版本迁移框架（3→4→…→11 步进产品化）
- 静默迁移旧客户端会话
- 零停机双写
- 在 lezi-sync **启动路径**内嵌自动升级（保持 fail-closed）
- 修改 ADR 使「任意旧库自动升级」成为产品默认（本路径仅为私有运维破例）

## 相关

- 现网控制面与 agent 流程：`AGENTS.md` § lezi-sync NAS CD  
- 当前 schema 合同：`tools/lezi-sync/README.md`（user_version 与 `DATABASE_SCHEMA_VERSION`）  
- 机器可读清单：`tools/lezi-sync/src/offline_migrate/`  
- fresh-only 产品史：`docs/adr/0008-…`（正文仍写 v3，与代码 v11 有漂移；**本 tracker 不靠 ADR 做通用迁移授权**）

## Grill 锁定摘要

- 目标：保住家庭数据 + 当前 trusted-sync  
- 会话：强制全员重登  
- 根密码：迁移时重置（运维新设）  
- 不可映射：整库拒迁 + 报告  
- staging：行级丢弃 + cascade；不单独失败  
- server.secret：始终重生  
- 窗口：可较长 + 人工校验  
- 边界：私有运维；启动 fail-closed 不变  
