# 乐记 — 数据模型与同步契约

> V1：**仅本地**（Room）。  
> V2：同一模型经 **`SyncPort`** 接家庭局域网 `lezi-sync`。
> 主 PRD：[`README.md`](./README.md)

---

## 1. 原则

1. **先写本地，再同步**（有实现时）：UI 只依赖 Room。  
2. **家庭域 vs 本机域** 分离，避免设置冲突。  
3. 每条业务实体带 **`client_uuid`**，便于幂等与后期同步。  
4. 未配置或不满足家网门闩时，`SyncPort` 安全 no-op / 保留 Outbox，
   **不得**阻塞记账。

---

## 2. ER 概要

```text
LocalUser 1──* Membership *──1 Family
Family 1──* Baby
Family 1──* ShareInvite          # V2 可用
Family 1──* CustomItemDef        # V2
Baby 1──* Record
Record 1──* MediaAsset
LocalUser 1──1 SettingsLocal     # 永不进家庭同步域
Family 可选 Outbox               # V2 上行队列
```

V1 最小路径：创建默认 `Family` + 当前 `LocalUser`（匿名）+ `Baby` + `Record`。

---

## 3. 实体字段

### 3.1 LocalUser

| 字段 | 说明 |
|------|------|
| `id` | 本地主键 |
| `display_name` | 可选 |
| `device_id` | 本机标识 |
| `created_at` | |

无强制账号体系；V2 可增加 `auth_subject` 绑定。

### 3.2 Family

| 字段 | 说明 |
|------|------|
| `id` | |
| `owner_user_id` | 管理员 |
| `created_at` | |

### 3.3 Membership

| 字段 | 说明 |
|------|------|
| `family_id` | |
| `user_id` | |
| `role` | `owner` \| `member` |
| `status` | `active` \| `revoked` |
| `joined_at` | |

NAS 实现以 `token_hash` 为 membership 主键，同时保存 `device_id` 与可选
`display_name`；三者都不是可公开的成员标识。家庭成员视图只返回规范化后的
`display_name`、`role`、`is_self`：服务端按当前 Bearer principal 计算
`is_self`，不返回 token、`token_hash`、原始 `device_id` 或 `family_id`。
客户端不得把本机 UI 占位名“我（本机）”当成真实成员名上传。create 与 join 共用
同一 normalize：拒绝控制字符和双向文本格式控制符，再 trim；空白归一为 null，
最长 128 个 Unicode code point。历史 null/空/不安全名称由客户端显示本地兜底。
客户端软解析滚动升级响应：未知 role 按 member、缺 `is_self` 按 false；只有从未
成功加载成员列表时才合成本机占位，服务端已返回空列表或无 self 列表时保持原样。

旧库没有 `(family_id, device_id, role)` 唯一约束。服务端读取时合并同 role +
device 的重复 active token 行，不跨 role 合并，也不凭客户端声明的 `device_id`
批量吊销；退出严格吊销当前 token。这样无需 schema migration，且不会把 member
误呈现为 owner。管理员删除家庭时由外键级联清除全部 membership。

权限（V2）：

| | 管理员 | 成员 |
|--|--------|------|
| 编辑/删任意记录 | ✓ | 建议仅自己创建的（可配置） |
| 邀请/移除成员 | ✓ | × |
| 停止共享 | ✓ | 可退出自己 |

不做保育只读角色、不做字段级 ACL。

### 3.4 Baby

| 字段 | 说明 |
|------|------|
| `id` | |
| `family_id` | |
| `nickname` | |
| `sex` | 可选枚举 |
| `birthday` | 日龄基准 |
| `due_date` | 旧版本兼容字段；现行产品不采集、不展示、不参与计算 |
| `theme_color` | 本机展示；是否同步主题 **默认不同步**（见设置） |
| `sort_order` | 本机展示顺序；不同步 |
| `client_uuid` | |
| `updated_at` / `deleted_at` | 软删 |

### 3.5 Record

| 字段 | 说明 |
|------|------|
| `id` | |
| `client_uuid` | **UNIQUE** |
| `baby_id` | |
| `type` | 见主 PRD §3 |
| `timestamp` | 主时间 |
| `end_timestamp` | 睡眠等区间 |
| `note` | |
| `created_by_user_id` | |
| `payload_json` | 类型扩展 |
| `schema_version` | v1 兼容读取；新增/编辑写 v2 |
| `updated_at` / `deleted_at` | 软删 / LWW |

**索引**：`(baby_id, timestamp)`、`(client_uuid)`、`(baby_id, type, timestamp)`。

### 3.6 typed payload 与兼容约定

业务代码只通过 `RecordPayloadDocument` / `RecordPayloadCodec` 读取或写入
`payload_json`。每个 `RecordType` 只接受匹配的 `RecordPayload`：

- v1 JSON 兼容读取，不做批量破坏性迁移；旧记录仅在用户编辑确认后写成 v2。
- 未识别字段进入 `extensions`，v2 再写时原样合并。
- 畸形 JSON 或未来 `schema_version` 解码为 `UnknownPayload`，保留原始字节，
  不允许静默清空或覆盖。
- 搜索、时间轴、汇总、导出和 Widget 复用 typed payload 与同一中文摘要模块，
  不各自用正则或手写 JSON 解释业务字段。

| type | JSON 字段 |
|------|-----------|
| `nursing` | `left_min`, `right_min`, `order`, `amount_ml?`, `record_mode=start|end` |
| `formula` | `amount_ml`, `prepared_ml?`, `duration_min?` |
| `pumped_feed` / `pump_express` | `amount_ml` |
| `pee` | `pee_amount` 1=小 · 2=中 · 3=大（默认 2） |
| `poop` | `stool_amount?` 1–4, `stool_consistency?` 1–4, `stool_color?` 0–7 |
| `both_diaper` | `pee_amount` + 便便三字段（同上） |
| `sleep` | `anomaly_flag`, `is_nap?`（起止用 timestamp/end） |
| `temperature` | `celsius` |
| `height` / `weight` / … | `value`, `unit` |
| `medicine` | `name`, `dose?` |
| `diary` / `memo` | `body`, `photos[]`（应用私有文件绝对路径；最多 9 张） |
| `cough` / `rash` / `vomit` / `injury` | `severity` 1–3, `description?` |
| `hospital` | `reason`, `advice?` |
| `baby_food` / `snack` / `drink` | `content`, `amount?` |
| `vaccine` | `name`, `batch?` |
| `other` | `title`, `detail?` |
| `custom` | `title`, `detail?`, `custom_item_id?`, `icon_slot?`；标题/图标为历史快照 |

**不做**：挤奶库存余额表。

### 3.7 MediaAsset

| 字段 | 说明 |
|------|------|
| `id` | |
| `client_uuid` | 跨设备同步键，UNIQUE |
| `kind` | `log` \| `avatar` |
| `record_id` / `baby_id` | 日志图关联 Record；头像关联 Baby，二选一 |
| `local_uri` | 本机私有文件路径，不进入 wire payload |
| `remote_uri` | 当前家庭服务器已上传标记；更换服务器时清除 |
| `mime` / `width` / `height` | |
| `byte_size` | |
| `created_at` / `updated_at` / `deleted_at` | LWW 与 tombstone |
| `sync_dirty` | 需快照入当前家庭 Outbox |

仅图片（V1/V2 初版）；视频不做。

### 3.8 SettingsLocal（**不同步**）

| 字段 | 说明 |
|------|------|
| `item_order_json` | 图标顺序 |
| `hidden_items` | 隐藏类型 |
| `action_buttons` | 计时/搜索/日历等显隐 |
| `timer_enabled` | |
| `record_at_start_or_end` | 母乳记录时刻 |
| `nursing_interval_min` | 提醒间隔 |
| `next_feed_at` | 本机下次提醒 |
| `dark_mode` | |
| `day_count_mode` | 满日龄 / 计数日龄 |
| `week_start` | |
| `units` | ml/oz、℃/℉、g/kg、12/24h |
| `amount_step_ml` | 配方奶/挤出乳步进 ml；**默认 5**；可选 5/10/15；改后 UI 立即按新步进渲染 |
| `time_step_min` | |
| `curve_dataset` | 曲线包 id |
| `infant_fever_advice_enabled` | 低月龄发热提示开关 |
| `visual_style` / `preferred_hand` | 模板与惯用手 |
| `timeline_order` | `newest_first` / `oldest_first` |

主题色存在 Baby 上，但 **同步策略默认：主题与排序属本机**（与参考产品一致）。若 V2 要共享主题，再单开开关。

### 3.9 ShareInvite（V2）

| 字段 | 说明 |
|------|------|
| `code` | 短码 |
| `qr_payload` | |
| `expires_at` | 建议默认 24h，可配 |
| `created_by` | |
| `used_count` / `max_uses` | |

### 3.10 CustomItemDef（V2）

最多 10：`id`, `family_id`, `name`, `icon_slot` (0–7), `sort_order`,
`client_uuid`, `updated_at`, `deleted_at`。图标固定模板，不支持自定义图标资源。
删除目录项不级联删除或改写历史 `custom` 记录。

### 3.11 CalendarEvent（V2）

`id`, `baby_id`, `family_id`, `title`, `start_at`, `end_at?`, `remind_at?`, `created_by`, `updated_at`, `deleted_at`

### 3.12 Outbox（V2）

| 字段 | 说明 |
|------|------|
| `family_id` | 队列所属家庭，防止跨家庭 ACK |
| `entity_type` | `baby` \| `record` \| `media` |
| `client_uuid` | portable 实体键 |
| `payload_json` | 不含本机自增 id / 文件绝对路径 |
| `updated_at` / `deleted_at` | LWW 与 tombstone |
| 唯一约束 | `(family_id, entity_type, client_uuid)`，后写覆盖同键待发送快照 |

---

## 4. 单位存储

| 量 | 存储 | 展示 |
|----|------|------|
| 奶量 | ml | 可 oz |
| 体温 | ℃ | 可 ℉ |
| 身长 | cm | |
| 体重 | g | 可 kg |
| 时间 | epoch ms | 12/24h |

---

## 5. 聚合（可读模型）

不必强行落表；可用查询或按日缓存：

- **日汇总**：睡眠分钟、尿次、便次、配方+母乳+挤出乳 ml、母乳分钟等。  
- **周汇总**：按类型分桶给图表。  
- **睡眠配对**：由 sleep 记录推导区间；`anomaly_flag` 写入或查询时计算。

---

## 6. SyncPort（同步接口）

### 6.1 状态

```kotlin
enum class SyncStatus {
  Disabled,            // 未配置服务器/SSID 白名单或未加入家庭
  BlockedOfflineHome,  // 非 Wi-Fi、SSID 未命中/读不到、health 失败、退避或不在前台
  Idle,
  Syncing,
  Error,
}
```

本机家网配置（`SyncPreferences`，**不同步到 NAS**）：

| 字段 | 说明 |
|------|------|
| `serverHost` / `serverPort` / `serverScheme` | 单一 NAS；port 默认 8765、scheme 默认 `http`；三者为真源并派生 `{scheme}://{host}:{port}` |
| `allowedSsids` | 最多 2 个；trim 后精确匹配当前 Wi‑Fi 名 |
| 会话字段 | `familyId` / token / role / cursor / generation（同前） |

### 6.2 接口（契约级，语言示意）

以 `sync/.../SyncPort.kt` 为准：

```text
interface SyncPort {
  fun status(): Flow<SyncStatus>
  fun session(): Flow<SyncSession>

  /** 已保存服务器且持有家庭会话时为 true */
  fun isEnabled(): Boolean

  /** 前台非阻塞触发；未加入家庭时 no-op */
  fun requestSync(trigger: SyncTrigger)

  suspend fun saveServer(baseUrl: String): Result<Unit>
  suspend fun saveHomeLanConfig(config: HomeLanServerConfig): Result<Unit>
  suspend fun createFamily(displayName: String? = null, bootstrapSecret: String): Result<SyncSession>
  suspend fun sync(trigger: SyncTrigger): Result<Unit>

  /** 显式触发；内部走同一前台/门闩路径 */
  suspend fun pull(familyId: String): Result<Unit>
  suspend fun push(familyId: String): Result<Unit>

  suspend fun createInvite(familyId: String): Result<Invite>
  /** 产品入口必须显式携带邀请码与本次编辑后的 endpoint/SSID */
  suspend fun joinFamily(command: JoinFamilyCommand): Result<SyncSession>
  /** 仅兼容旧调用；产品 UI 不依赖保存态隐式补端点 */
  suspend fun joinWithCode(code: String): Result<SyncSession>
  suspend fun joinWithPayload(
    payload: String,
    preferredConfig: HomeLanServerConfig? = null,
    displayName: String? = null,
  ): Result<SyncSession>
  suspend fun listFamilyMembers(): Result<List<FamilyMember>>
  suspend fun leave(familyId: String): Result<Unit>
  suspend fun deleteFamily(): Result<Unit>

  /** 回调须在领域事务提交后立刻调用 marker；随后清日志媒体/outbox，generation 保留 */
  suspend fun clearLocalRecords(
    clearLocal: suspend (onCommitted: () -> Unit) -> Unit,
  ): Result<Unit>
  /** 全量本机 wipe（含 outbox/头像媒体/文件）；同样要求领域提交 marker */
  suspend fun clearAllLocalData(
    clearLocal: suspend (onCommitted: () -> Unit) -> Unit,
  ): Result<Unit>
}
```

### 6.3 未配置实现

- 无会话时状态保持 `Disabled`，前台与本地写触发为安全 no-op。
- 建家、加入、邀请前必须配置服务器；失败返回中文产品文案。
- 只有已加入家庭的会话才会生成并上传 Outbox；已加入后变更服务器地址时保留 token/family，
  原子清除 cursor/generation 并按 full-resync 重新拉取。

### 6.4 V2 规则（摘要）

> **网络拓扑、门闩、前台策略、NAS Docker 与 API 的权威说明见 [`sync-home-lan.md`](./sync-home-lan.md)。** 本节仅保留数据契约摘要。

| 规则 | 说明 |
|------|------|
| 部署 | 家庭 NAS 中心化（Docker `lezi-sync`）；**非** P2P 主路径 |
| 门闩 | **硬家庭局域网**：Wi‑Fi + NAS health；蜂窝不同步 |
| 触发 | **仅前台**：回前台、下拉、前台写成功后 push；**无**后台轮询、**无**推送拉同步 |
| 同步域（首版） | **Baby + Record + 日志 MediaAsset（含字节）** |
| 写权限 | 宝宝**头像**仅 owner；日志媒体家庭内可同步 |
| 后置 | CustomItemDef、CalendarEvent |
| 不同步 | SettingsLocal、Baby `theme_color`/`sort_order`、下次喂奶时刻、Widget 配置、本机路径 |
| 共享粒度 | **全量**（同步域内）；不做字段白名单 |
| 冲突 | 同 `client_uuid` 幂等；否则 `updated_at` LWW；删除 tombstone |
| 跨机引用 | Record 使用 `baby_client_uuid`，不用对端本地自增 id |
| 通知 | **不**对成员新记录推送 |
| 验收 | 双方在家且打开 App 时回前台/下拉一致；**不**承诺息屏 60s |
| 安全 | 默认家网 HTTP + family token；可选 HTTPS；加入前明示全量共享 |
| 持久化 | NAS 单数据根：`DATA_DIR/lezi.db` + `DATA_DIR/media/` |

Room schema v7 会把历史 `MediaAsset` 关联规范为二选一：`log` 仅保留
`record_id`，`avatar` 仅保留 `baby_id`。

### 6.5 本地备份（可选，不依赖 SyncPort）

V1 可提供「导出数据库/JSON 到文件」便于换机；与家庭实时同步分开。

---

## 7. 删除语义

| 操作 | 行为 |
|------|------|
| 删一条记录 | `deleted_at` 软删；V2 进 Outbox |
| 成员退出 | membership revoked + 吊销本机 token；**NAS 业务数据保留** |
| 清除本机全部 | 多重确认后清空本地库；**默认仅本地** |
| 管理员删除家庭数据 | 多重确认后清空 NAS entities + `DATA_DIR/media/`（见 sync-home-lan） |

---

## 8. 与主 PRD 分期对应

| 版本 | 数据层 |
|------|--------|
| V1 | LocalUser, Family, Membership, Baby, Record, Media, SettingsLocal；SyncPort 空实现 |
| V1.5 | 曲线包资源只读；导出读 Record |
| V2 | ShareInvite, Outbox, CustomItem, CalendarEvent；SyncPort 真实现 |
