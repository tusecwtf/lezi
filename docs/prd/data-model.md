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
| `display_name` | 本机缓存的**家庭称呼**（与当前 membership 对齐；未加入家庭时可空） |
| `device_id` | 本机标识 |
| `created_at` | |

无强制账号体系；身份展示以家庭 membership 的称呼为准，不引入跨设备照护者实体。
见 [`docs/adr/0002-family-identity-and-account-overview.md`](../adr/0002-family-identity-and-account-overview.md)。

### 3.2 Family

| 字段 | 说明 |
|------|------|
| `id` | |
| `name` | **共享家庭名**；全员一致；仅 owner 可改；可空，客户端兜底「我的家庭」/「{宝宝昵称}的家庭」 |
| `owner_user_id` | 管理员（本地）；NAS 侧以 owner membership 为准 |
| `created_at` | |

NAS 家庭记录须持久化共享 `name`（或等价字段），并在成员可见摘要中下发。

### 3.3 Membership

| 字段 | 说明 |
|------|------|
| `membership_id` | NAS 生成的不可变 membership UUID，产品身份主键 |
| `family_id` | 所属家庭 |
| `role` | `owner` \| `member` |
| `device_id` | 旧数据迁移提示与副本标签，不承担鉴权 |
| `display_name` | 当前家庭称呼；所有指向该 membership 的凭证共享 |
| `left_at` | 空表示 active；非空表示已退出 |

NAS 将持久 membership 与 Bearer credential 分表。`memberships.membership_id` 是
不可变公开身份；`membership_credentials.token_hash` 是可轮换、可单独吊销并指向
membership 的访问凭证，不是成员主键。`membership_id` 在建家/加入时分配，升级旧库
时保留既有 ID 或只生成一次；token 更新、地址变化或服务器重启不得改变它。角色、
称呼与写者身份只由认证后的 canonical membership principal 决定，客户端 payload
不得冒充管理员或其他成员。

`display_name` 在产品层于建家/加入时**必填**；服务端 trim，拒绝控制字符与双向文本
格式控制符，最长 128 个 Unicode 字符；空白、省略字段与本机占位名「我（本机）」均
返回 `422`，不得静默收成 null。家庭成员视图返回规范化后的 `display_name`、`role`、
`is_self`、`membership_id`，以及客户端链路键 `device_id`（用于把记录
`created_by_device_id` 解析为当前称呼；**UI 永不展示 device id**）。服务端按当前
Bearer principal 计算 `is_self`，不返回 token、`token_hash` 或 `family_id`。本人可
通过 `POST /v1/family/display-name` 更新自己的称呼，不能改他人。客户端不得把本机
UI 占位名“我（本机）”当成真实成员名上传。历史 null/空/不安全名称由客户端按角色
兜底（如「家庭管理员」「家庭成员」或「家人」），且不得把「我（本机）」展示给其他
成员。管理员在 UI 上以 ★ 标出。

旧库没有 `(family_id, device_id, role)` 唯一约束。升级事务仅在迁移时把同 family +
role + device 的历史 active token 归并到一个 canonical membership，保留其它既有
membership ID 为作者/ACL alias；owner/member 冲突不跨 role 合并。迁移后运行时不再
按客户端声明的 `device_id` 合并，新 join 总是创建独立 membership。单凭证轮换/吊销
不改变 membership；成员退出会标记该 membership 离开并吊销它的全部凭证。管理员
删除家庭时由外键级联清除 membership、credential 与 alias。

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
| `diary` / `memo` | `body`；旧 `photos[]` 仅做历史兼容读取，新照片统一使用 Record 关联的 MediaAsset，最多 3 张 |
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
| `kind` | `log` \| `plan`（已批准扩展、待实现）\| `avatar` |
| `record_id` / `plan_id` / `baby_id` | 记录图关联 Record；计划图关联 CarePlan；头像关联 Baby，三选一 |
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
| `category_order_json` | 记录类别区块顺序 |
| `hidden_items` | 隐藏类型 |
| `quick_record_slots` | 四个常用记录槽位；空槽允许 |
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
| `family_plan_reminders_enabled` | 当前设备是否提醒家庭护理计划；默认开 |
| `system_calendar_enabled` / `system_calendar_id` | 当前设备的系统日历副本开关与用户选择的可写日历 |
| `system_calendar_disclosure` | `event_only` \| `baby_and_type`（默认）\| `details` |

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

最多 10：`id`, `family_id`, `name`, `icon_slot` (0–7), `client_uuid`,
`created_by_membership_id`, `updated_at`, `deleted_at`，本机 `syncDirty`。
图标固定模板，不支持自定义图标资源；排序、显隐和常用槽位属于 SettingsLocal，
不进入共享定义。家庭同步实体类型为 `custom_item`（legacy push），服务端在首次
写入时从认证 membership 盖章创建者，普通成员仅可改自己的定义，管理员可改全部，
tombstone 不可复活。删除目录项不级联删除或改写历史 `custom` 记录。

### 3.11 CarePlan（本机 tracer 已落地；NAS wire/ACL 已落地；客户端家庭 apply 待后续票）

护理计划与已发生 Record 分离。Room 表 `care_plans` 字段包括：`client_uuid`,
`baby_id`, `type`, `custom_item_id?`, `scheduled_at`, `scheduled_zone_id`,
`note`, `payload_json`, `schema_version`, `status`,
`created_by_membership_id`, `fulfilled_record_client_uuid?`, `fulfilled_at?`,
`updated_at`, `deleted_at`。

NAS 原子包根类型 `care_plan` 的 wire payload 为：
`baby_client_uuid`, `type`, `custom_item_client_uuid?`, `scheduled_at`,
`scheduled_zone_id`, `note?`, `payload_json`（object）, `schema_version?`,
`status`（pending|missed|completed|skipped）,
`created_by_membership_id`（服务端盖章）, `fulfilled_record_client_uuid?`,
`fulfilled_at?`。计划媒体为 bundle 内 `media` 且 `care_plan_client_uuid` 指向根。

首版状态为 `pending`, `missed`, `completed`, `skipped`，且只支持单次计划。
`missed` 可由当前绝对时刻超过计划时刻且仍未完成/跳过派生。本机履行在同一事务
中写入关联 Record 并将计划标为 `completed`。多候选时各设备用盖章证据稳定裁决
唯一权威记录，并本地重链 `fulfilled_record_client_uuid`（不依赖计划 LWW 到达序）。

### 3.11b FulfillmentCandidate（NAS 契约 + 本机）

`entity_type = fulfillment_candidate`：`care_plan_client_uuid`,
`record_client_uuid`, `actual_timestamp?`, 以及服务端首次接受时盖章且不可改写的
`submitter_membership_id`, `submitter_role`, `confirmed_at`。任意活动成员可提交；
跨家庭/缺失引用以冲突拒绝。权威裁决键（客户端纯函数，与到达序无关）：
1) 提交者是否管理员（`owner`/`admin`）；2) 较早的不可编辑 `confirmed_at`；
3) 候选 `client_uuid` 升序。NAS 到达时间、可编辑实际发生时间、设备 `updated_at`
与后续角色变化不参与比较。

Android 本机表 `fulfillment_candidates` 在履行事务中写入稳定 `clientUuid` 与
不可变本地 `confirmedAt`，并与 Record 原子包 + completed CarePlan 原子包一起出站
（legacy push 候选）；接收端 completed 计划须已有关联 Record，候选须 plan+record
均已落地后才应用。全量候选就绪后裁决：赢家 `adoptionStatus=adopted` 并写入计划
关联；落选 `conflict_not_adopted`，**不**软删除 Record/照片；落选记录不进入普通
时间轴、汇总、搜索或普通导出。管理员可在本机审计落选并「转为独立记录」：创建**新的**
`clientUuid` 与新 media 所有权的普通 Record，**不**翻转落选 `adoptionStatus`、**不**
重链 `CarePlan.fulfilled_record_client_uuid`。幂等靠本机
`fulfillment_candidates.convertedRecordClientUuid` 指针（不进家庭 wire）；双管理员在
两台设备上各转一次且未共享指针时，产品接受两条独立普通记录。`adoptionStatus` 与
`convertedRecordClientUuid` 均为本机派生字段，不进家庭 wire。

### 3.11a CalendarEvent（历史兼容）

`id`, `baby_id`, `family_id`, `title`, `start_at`, `end_at?`, `remind_at?`, `created_by`, `updated_at`, `deleted_at`

不再新建自由标题 CalendarEvent，也不进入家庭同步；旧事件继续本机显示、编辑和提醒，用户确认后可转换为 CarePlan。

### 3.12 Outbox（V2）

| 字段 | 说明 |
|------|------|
| `family_id` | 队列所属家庭，防止跨家庭 ACK |
| `entity_type` | `baby` \| `record` \| `media` \| `custom_item` \| `care_plan`（atomic bundle 根）\| `fulfillment_candidate` |
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
| `serverHost` / `serverPort` | 单一 NAS；port 默认 8765；派生 `baseUrl=http://host:port` |
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
  /** displayName=家庭称呼（必填）；familyName=共享家庭名（可空） */
  suspend fun createFamily(displayName: String, familyName: String?, bootstrapSecret: String?): Result<SyncSession>
  suspend fun renameFamily(familyName: String?): Result<Unit>
  suspend fun updateMyDisplayName(displayName: String): Result<Unit>
  suspend fun sync(trigger: SyncTrigger): Result<Unit>

  /** 显式触发；内部走同一前台/门闩路径 */
  suspend fun pull(familyId: String): Result<Unit>
  suspend fun push(familyId: String): Result<Unit>

  suspend fun createInvite(familyId: String): Result<Invite>
  /** 当前 token 所在家庭的 active 成员安全视图（含称呼与 role） */
  suspend fun listFamilyMembers(): Result<List<FamilyMemberView>>
  /** 邀请码或完整载荷；displayName 为必填家庭称呼 */
  suspend fun joinWithCode(code: String, displayName: String): Result<SyncSession>
  suspend fun joinWithPayload(payload: String, displayName: String): Result<SyncSession>
  suspend fun leave(familyId: String): Result<Unit>
  suspend fun deleteFamily(): Result<Unit>

  /** 清本机记录副本 + 日志媒体/文件；保留会话 generation */
  suspend fun clearLocalRecords(clearLocal: suspend () -> Unit): Result<Unit>
  /** 全量 wipe（含 outbox/头像媒体），join 前用 */
  suspend fun clearAllLocalData(clearLocal: suspend () -> Unit): Result<Unit>
}
```

### 6.3 未配置实现

- 无会话时状态保持 `Disabled`，前台与本地写触发为安全 no-op。
- 建家、加入、邀请前必须配置服务器；失败返回中文产品文案。
- 只有已加入家庭的会话才会生成并上传 Outbox；服务器地址变化时原子清除旧 token/cursor。

### 6.4 V2 规则（摘要）

> **网络拓扑、门闩、前台策略、NAS Docker 与 API 的权威说明见 [`sync-home-lan.md`](./sync-home-lan.md)。** 本节仅保留数据契约摘要。

| 规则 | 说明 |
|------|------|
| 部署 | 家庭 NAS 中心化（Docker `lezi-sync`）；**非** P2P 主路径 |
| 门闩 | **硬家庭局域网**：Wi‑Fi + NAS health；蜂窝不同步 |
| 触发 | **仅前台**：回前台、下拉、前台写成功后 push；**无**后台轮询、**无**推送拉同步 |
| 同步域（首版） | **Baby + Record + 日志 MediaAsset（含字节）** |
| 写权限 | 宝宝**头像**仅 owner；日志媒体家庭内可同步 |
| 已批准扩展（待实现） | CustomItemDef、CarePlan、计划 MediaAsset、Record/CarePlan 原子照片包 |
| 继续不同步 | 通用 CalendarEvent、系统日历 ID/权限/披露级别、提醒偏好、快捷槽位与布局顺序 |
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
| 成员退出 | membership 标记离开并吊销其全部 credentials；**NAS 业务数据保留** |
| 清除本机全部 | 多重确认后清空本地库；**默认仅本地** |
| 管理员删除家庭数据 | 多重确认后清空 NAS entities + `DATA_DIR/media/`（见 sync-home-lan） |

---

## 8. 与主 PRD 分期对应

| 版本 | 数据层 |
|------|--------|
| V1 | LocalUser, Family, Membership, Baby, Record, Media, SettingsLocal；SyncPort 空实现 |
| V1.5 | 曲线包资源只读；导出读 Record |
| V2（现行） | ShareInvite, Outbox, CustomItem, CalendarEvent；SyncPort 真实现 |
| V2 护理计划扩展（待实现） | 家庭共享 CustomItemDef、CarePlan、计划媒体与原子照片同步包；CalendarEvent 保持历史本机兼容 |
