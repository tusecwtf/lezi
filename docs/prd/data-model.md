# 乐记 — 数据模型与同步契约

> V1：**仅本地**（Room）。  
> V2：同一模型经 **`SyncPort`** 接后端。  
> 主 PRD：[`README.md`](./README.md)

---

## 1. 原则

1. **先写本地，再同步**（有实现时）：UI 只依赖 Room。  
2. **家庭域 vs 本机域** 分离，避免设置冲突。  
3. 每条业务实体带 **`client_uuid`**，便于幂等与后期同步。  
4. V1 的 `SyncPort` 实现为空操作或返回「未启用」，**不得**阻塞记账。

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
| `due_date` | 修正月龄用，可选 |
| `theme_color` | 本机展示；是否同步主题 **默认不同步**（见设置） |
| `sort_order` | |
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
| `schema_version` | |
| `updated_at` / `deleted_at` | 软删 / LWW |

**索引**：`(baby_id, timestamp)`、`(client_uuid)`、`(baby_id, type, timestamp)`。

### 3.6 payload_json 约定

| type | JSON 字段 |
|------|-----------|
| `nursing` | `left_min`, `right_min`, `order`, `amount_ml?`, `record_at_mode` |
| `formula` | `amount_ml`, `prepared_ml?`, `duration_min?` |
| `pumped_feed` / `pump_express` | `amount_ml` |
| `pee` | `pee_amount` 1=小 · 2=中 · 3=大（默认 2） |
| `poop` | `stool_amount?` 1–4, `stool_consistency?` 1–4, `stool_color?` 0–7 |
| `both_diaper` | `pee_amount` + 便便三字段（同上） |
| `sleep` | `anomaly_flag`, `is_nap?`（起止用 timestamp/end） |
| `temperature` | `celsius` |
| `height` / `weight` / … | `value`, `unit` |
| `medicine` | `name`, `dose?` |
| `diary` / `memo` | `body`；媒体走 MediaAsset |
| `cough` / `rash` / `vomit` / `injury` | `severity` 1–3, `description?` |
| `hospital` | `reason`, `advice?` |
| `baby_food` / `snack` / `drink` | `content`, `amount?` |
| `vaccine` | `name`, `batch?` |
| `other` / `custom` | `title`, `detail?`；后续自定义目录可追加 `custom_item_id` |

**不做**：挤奶库存余额表。

### 3.7 MediaAsset

| 字段 | 说明 |
|------|------|
| `id` | |
| `record_id` | |
| `local_uri` | V1 主用 |
| `remote_uri` | V2 |
| `mime` / `width` / `height` | |
| `created_at` | |

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

最多 10：`id`, `family_id`, `name`, `sort_order`, `client_uuid`。  
图标固定模板，不支持自定义图标资源。

### 3.11 CalendarEvent（V2）

`id`, `baby_id`, `family_id`, `title`, `start_at`, `end_at?`, `remind_at?`, `created_by`, `updated_at`, `deleted_at`

### 3.12 Outbox（V2）

| 字段 | 说明 |
|------|------|
| `op` | upsert / delete |
| `entity_type` | baby / record / media / … |
| `entity_id` / `client_uuid` | |
| `payload` | |
| `attempts` / `next_retry_at` | |

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
  Disabled,   // V1 默认
  Idle,
  Syncing,
  Error,
}
```

### 6.2 接口（契约级，语言示意）

```text
interface SyncPort {
  fun status(): Flow<SyncStatus>

  /** V1: false。V2: 用户开启且已加入家庭后 true */
  fun isEnabled(): Boolean

  /** 拉取家庭增量；V1: no-op 成功 */
  suspend fun pull(familyId: String): Result<Unit>

  /** 将 Outbox 推送；V1: no-op 成功 */
  suspend fun push(familyId: String): Result<Unit>

  /** 创建邀请；V1: Result.failure(SyncNotEnabled) */
  suspend fun createInvite(familyId: String): Result<Invite>

  /** 用码加入；V1: failure */
  suspend fun joinWithCode(code: String): Result<Family>

  /** 停止共享 / 退出 */
  suspend fun leave(familyId: String): Result<Unit>
}
```

### 6.3 V1 实现

- `isEnabled() = false`  
- `pull` / `push` 立即 `Result.success`  
- 邀请相关返回明确错误，UI 展示「同步将在后续版本提供」  
- **不写** Outbox 或写了也不上传  

### 6.4 V2 规则

| 规则 | 说明 |
|------|------|
| 同步域 | Family 下 Baby、Record、Media 元数据、CustomItem、CalendarEvent |
| 不同步 | SettingsLocal、下次喂奶时刻、Widget 配置 |
| 共享粒度 | **全量**；不做字段白名单 |
| 冲突 | 同 `client_uuid` 幂等；否则按 `updated_at` LWW；删除用 tombstone |
| 通知 | **不**对成员新记录默认推送 |
| 验收 | 双机同家庭，A 新记录约 **60s 内** B 可见（可下拉加速） |
| 安全 | TLS；加入前文案：将共享全部育儿记录 |

### 6.5 本地备份（可选，不依赖 SyncPort）

V1 可提供「导出数据库/JSON 到文件」便于换机；与家庭实时同步分开。

---

## 7. 删除语义

| 操作 | 行为 |
|------|------|
| 删一条记录 | `deleted_at` 软删；V2 进 Outbox |
| 成员退出 | membership revoked；数据留在家庭侧 |
| 清除本机全部 | 多重确认后清空本地库；V2 需定义是否删云端（默认仅本地除非显式「删除家庭」） |

---

## 8. 与主 PRD 分期对应

| 版本 | 数据层 |
|------|--------|
| V1 | LocalUser, Family, Membership, Baby, Record, Media, SettingsLocal；SyncPort 空实现 |
| V1.5 | 曲线包资源只读；导出读 Record |
| V2 | ShareInvite, Outbox, CustomItem, CalendarEvent；SyncPort 真实现 |
