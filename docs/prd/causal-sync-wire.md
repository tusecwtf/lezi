# 乐记 — 0.3.13 因果同步 Wire 合同（规划冻结）

> **状态：** **tree 运行时已落地**（tickets 02–08）；**家庭 NAS 强制切割与 minSupported=20 生产生效**
> 仍须 ticket 09 维护窗 CD 与双机冒烟证据。历史 0.3.9–0.3.12 叙述见
> [`sync-trusted-endpoint.md`](./sync-trusted-endpoint.md) 与 [`data-model.md`](./data-model.md)。
> 架构决策（不含 wire 字段权威）：[ADR-0019](../adr/0019-server-validates-constraints-not-care-truth.md)、
> [ADR-0020](../adr/0020-stable-projection-immutable-versions-and-branches.md)、
> [ADR-0021](../adr/0021-wake-observation-and-nondestructive-duplicate-groups.md)。
> 术语（不含字段/枚举权威）：根 [`CONTEXT.md`](../../CONTEXT.md)。
> **字段名、枚举、closed key set、合并路径与例子：本文是唯一权威。**
> 版本目标：Android/server **0.3.13**、versionCode **20**、Room **27**、server schema
> **12**（切割前须从实时清单与 live health 重核）。

实现票不得发明竞争 shape、第二套 verdict 名或第二套删除/媒体编码。

---

## 1. 能力与切割

| 项 | 合同 |
|----|------|
| Capability keys（setup-status `capabilities` 数组，字面量冻结） | `causal_versions`、`wake_observation`、`source_relations`；三者皆缺一则停止护理同步，无 dual-read |
| minSupported | 抬到 versionCode **20** 之前须已发布可安装签名 APK + 验证更新通道 |
| 旧客户端 | 低于 floor 不得写入/拉取新 shape；无 skip-unknown |
| Schema | server `user_version` **12** fresh-current；v11 仅 offline-migrate |

HTTP 路径前缀字符串（如 `/v1/families/...`）可由实现票贴合现有路由树，但 **职责、方法语义与 body shape 以本节为准**，不得另起竞争 API。

---

## 2. 删除编码（单一同构）

| 层 | 表示 | 规则 |
|----|------|------|
| Mutation 信封 | `deleted: bool`（必填） | `true` = 基于 `base_version` 的 tombstone 期望；`false` = live 期望 |
| Root JSON | **禁止** `deleted` / `deleted_at` 键 | 出现 → `rejected` `unknown_field` |
| Pull / 稳定投影信封 | `deleted_at: integer \| null` | `null` = live；非 null = 服务器盖章的 tombstone 时刻（epoch ms） |
| 三方合并 | 信封路径 `/_mutation.deleted`（逻辑路径，非 root 内键） | 与 root 叶路径并列比较；见 §8 |

同构：`deleted=true` 且 commit 成功 → 稳定投影 `deleted_at != null`；live 投影必须 `deleted_at == null` 且 `deleted=false`。

---

## 3. Mutation 与响应信封

### 3.1 Mutation 单元（reconcile 与 commit **同一完整 shape**）

| 字段 | 类型 | 约束 |
|------|------|------|
| `mutation_id` | string (UUID) | 客户端生成；重试不变；同 id 不同 canonical 内容 → `rejected` `content_drift` |
| `base_version` | string \| null | 读到的稳定 `version_id`；仅当服务器尚无该 `client_uuid` 时允许 `null`（首建） |
| `entity_type` | enum | §4 |
| `client_uuid` | string (UUID) | 根身份 |
| `root` | object | §4 closed key set；完整期望根 |
| `media` | array of media item | §4.6；无媒体时 `[]` |
| `deleted` | bool | §2 |

**禁止** 在 mutation 上携带媒体 bytes。

### 3.2 写路径成功响应（reconcile unit / commit unit）— **必填**

| 字段 | 说明 |
|------|------|
| `status` | 见 §5 / §6 |
| `mutation_id` | 回显 |
| `stable_version_id` | 当前稳定投影版本（branched 时仍为冲突前稳定） |
| `stable_root` | 完整稳定 root（tombstone 时仍为规范 tombstone root 或空对象规则见 §4） |
| `stable_media` | 完整稳定媒体清单（canonical 排序，§8.3） |
| `generation` | 权威 generation |
| `request_hash` | 请求内容 hash 回显 |
| `branch_version_id` | 仅 `branched`：**必填**；其它 status 必须省略或 `null` |
| `conflict_id` | 存在未解决并发冲突时：**必填** string 句柄；稳定变为纯 tombstone（无未解决分支）时亦**必填** tombstone-scoped 句柄（§8.2）；既无并发冲突也非 restorable tombstone 时必须省略或 `null`。**唯一**冲突句柄名（禁止 `conflict_ref`） |

失败：稳定 `code` + 可映射 reason；不泄露跨家庭存在性。

---

## 4. 版本化原子根与 closed key sets

`entity_type` ∈
`baby` \| `record` \| `care_plan` \| `custom_item` \| `wake_observation`。

**不** 进入三方编辑：`fulfillment_candidate`（不可变证据；精确幂等 replay only；仍走既有 candidate wire，不进入本 mutation 表）。

### 4.0 字段分类（合并）

| 类 | 路径 | 三方规则 |
|----|------|----------|
| 客户端可合并 | root 内业务叶路径、`/_mutation.deleted`、`/media/{uuid}` | §8 |
| 服务器盖章 / 非冲突 | `created_by_membership_id`、`observer_membership_id`、`updated_at` | **永不因两侧值不同而 branch**；见下 |
| 禁止客户端伪造盖章 | 同上盖章字段 | 入站自报：忽略或 `rejected` `forged_stamp`（实现二选一必须在 Store 全路径一致；默认 **忽略客户端值并重盖章**） |

**`updated_at`：** 在 root 内，类型 integer epoch ms。是用户可见编辑/审计时间，**不是** 并发 token。
合并/接受后服务器规范化：

- `accepted`（单方）：`updated_at = max(mutation.root.updated_at, previous_stable.updated_at if any)`，若 mutation 省略则用服务器接收时刻；
- `merged`：`updated_at = max(stable.updated_at, incoming.updated_at)`（两侧都缺则服务器接收时刻）；
- 单独两侧 `/updated_at` 不同而其它路径可合并 → **仍 merged**，不 branch。

**`created_by_membership_id` / `observer_membership_id`：** 仅服务器首次盖章后冻结；后续 mutation 不得改写；比较时排除出冲突集。

### 4.1 `baby` root

| 键 | 必填 | 作者 |
|----|------|------|
| `nickname` | 是 | client |
| `sex` | 否 (null 可) | client |
| `birthday` | 否 | client |
| `avatar_media_uuid` | 否 (null 可) | client；须 ∈ media 或 null |
| `updated_at` | 是 | 见 §4.0 |
| `created_by_membership_id` | pull 有；mutation 可省略 | server |

未知键 → reject。媒体 role：`avatar` 0–1。

### 4.2 `record` root（非 sleep 与 sleep 共用外层键）

| 键 | 必填 | 说明 |
|----|------|------|
| `baby_client_uuid` | 是 | |
| `type` | 是 | 既有 RecordType 字面量 |
| `custom_item_client_uuid` | 是 (非 custom 时为 null) | |
| `timestamp` | 是 | 主时间 / SleepStart |
| `end_timestamp` | 见下 | 闭集内键；sleep 禁止；非 sleep 允许 |
| `note` | 是 (可为 null) | |
| `payload_json` | 是 | object；typed closed keys 沿用现网 schema v2 白名单（data-model §3.6） |
| `schema_version` | 是 | 字面量 `2` |
| `updated_at` | 是 | §4.0 |
| `created_by_membership_id` | pull 有；mutation 可省略 | server |
| `effective_wake_observation_client_uuid` | **仅** `type=sleep`：是 (可为 null) | 有效 WakeObservation；非 sleep 禁止出现 |

**`end_timestamp`（closed 键，按 `type` 分叉）：**

| `type` | 必填 | 规则 |
|--------|------|------|
| `sleep` | 禁止 | 键**不得**出现（含 null）→ `rejected` `forbidden_field`（醒来走 WakeObservation，§4.5） |
| 非 sleep | 否（`null` 可） | 键允许；类型 `integer \| null`；语义与 0.3.12 相同（区间类若有）；当前非 sleep 类型保持 `null` |

未知键 → reject。

`payload_json` for sleep：仅 `anomaly_flag`、`is_nap`（与现网 allowlist 一致）。

媒体 role：`log` 0–3。

### 4.3 `care_plan` root

沿用现网 closed 键（`baby_client_uuid`, `type`, `scheduled_at`, `scheduled_zone_id`, `note`, `payload_json`, `schema_version`, `status`, `fulfilled_record_client_uuid`, `fulfilled_at`, `source_record_client_uuid`, `custom_item_client_uuid`, `updated_at`, `created_by_membership_id`）；未知键 reject。媒体 role：`plan` 0–3。

### 4.4 `custom_item` root

| 键 | 必填 |
|----|------|
| `name` | 是 |
| `icon_slot` | 是 |
| `updated_at` | 是 |
| `created_by_membership_id` | server |

无媒体。

### 4.5 `wake_observation` root

| 键 | 必填 | 说明 |
|----|------|------|
| `sleep_record_client_uuid` | 是 | 同家庭 Sleep Record |
| `wake_timestamp` | 是 | 必须 `>=` 目标 Sleep 的 `timestamp`，否则 `rejected` `invalid_wake_timestamp` |
| `note` | 是 (可为 null) | |
| `withdrawn` | 是 | bool；`true` = 观察者撤回，不参与暂定/有效投影 |
| `updated_at` | 是 | §4.0 |
| `observer_membership_id` | pull 有；mutation 可省略 | **仅服务器盖章** |

禁止键：`end_timestamp`、`deleted`、`deleted_at`、自报观察者覆盖。
媒体 role：`wake` 0–3。

**观察者纠正：** 普通 mutation，`withdrawn=false`，改 `wake_timestamp`/`note`/media。
**观察者撤回：** mutation 设 `withdrawn=true`（根仍 live，非 tombstone）。
**有效观察选择：** Sleep 作者或 Owner 对 **sleep record** 做 mutation，设置
`effective_wake_observation_client_uuid` 为某一未 `withdrawn` 且合法的观察 UUID，或
`null` 清除。走 §3 mutation + §5/§6，不另开 resolution API。

**暂定投影（非 wire 字段，实现必须一致）：**
未设有效观察时，end = 该 Sleep 下所有 `withdrawn=false` 且
`wake_timestamp >= sleep.timestamp` 的观察中 **`wake_timestamp` 最小** 者；
全部合法观察仍可见。

### 4.6 Media manifest item（closed）

| 键 | 必填 | 说明 |
|----|------|------|
| `media_uuid` | 是 | UUID |
| `role` | 是 | `avatar` \| `log` \| `plan` \| `wake` |
| `sha256` | 是 | 64 lowercase hex；tombstone 媒体行另见既有 media 实体规则 |
| `byte_size` | 是 | 正整数；tombstone 规范 0 |
| `mime` | 是 | |
| `width` | 是 (可为 null) | |
| `height` | 是 (可为 null) | |

未知键 → reject。根上 `avatar_media_uuid` / 引用与清单不一致 → `rejected` `media_referential_integrity`（**不** branch；入站 fail closed）。

**顺序：** 数组顺序 **不是** 产品语义；三方合并按 `media_uuid` 键集合。
稳定投影与成功响应中的 `media` / `stable_media` **必须** 按 `media_uuid` 字典序升序输出。
仅顺序不同的 mutation 与稳定内容在 media 集合意义上相同 → `confirmed` / 幂等，不创建新版本。

#### 4.6.1 Preimage lifecycle

`PUT /v1/causal/media/{media_uuid}` 携带 `X-Lezi-Media-Sha256` 与原始 bytes，只创建未发布
preimage。服务端必须持久绑定 family、staging membership、UUID、SHA-256、byte size、
created/expiry 与状态；同 UUID/同 bytes replay 幂等，同 UUID/不同 bytes 冲突且不得覆盖。
单文件上限服从服务器媒体上限；未消费资源固定为每 membership 64 个、每 family 256 个、
family aggregate 512 MiB、TTL 24 小时。

`commit` 必须在写 version/rev/publication 前精确匹配 UUID/SHA/size，并在同一数据库事务把
accepted/merged/branched 引用标记为 consumed。最终 `media/{family}/{uuid}` 只接收数据库已
消费的 bytes；commit/restart retry 必须从任意 DB/filesystem crash point 收敛。启动在开放业务
路由前恢复 consumed promotion，并以 durable GC journal 删除过期/无 metadata preimage、同步
目录；稳定版本或 branch 引用的 consumed bytes 不受 TTL GC。

---

## 5. Reconcile（dry-run）

`POST` … `/reconcile`（路径叶子名冻结为 `reconcile`）

**请求：** `{ "units": [ Mutation, ... ] }`，每 unit **完整** §3.1 shape（不是摘要）。
Bounds：`units.length` ∈ 1…64；超批 `rejected` 整请求。

**每 unit `status`（恰一，闭集）：**

| status | 含义 | 写稳定版本？ |
|--------|------|----------------|
| `confirmed` | 期望态已是当前稳定，或精确幂等已接受的同一 `mutation_id` | 否 |
| `publish` | 可 commit；证明绑定 generation / 稳定版本 / request_hash | 否 |
| `conflict_preview` | 若 commit 将 `branched` 或字段冲突；含预览摘要 | 否 |
| `rejected` | ACL、形状、引用、content_drift、非法 wake、陈旧 live-over-tombstone 等 | 否 |

权威证明：响应 `generation` + 涉及 UUID 的稳定版本戳 + `request_hash`；commit 须回放或安全重评。

---

## 6. Commit

`POST` … `/commit`

**每 unit `status`（恰一，闭集）：**

| status | 含义 |
|--------|------|
| `accepted` | 单方创建/更新/删除成为新稳定版本 |
| `merged` | 自动三方合并为新稳定版本 |
| `branched` | 无法自动合并；本 mutation 分支耐久；稳定投影不变；`conflict_id`+`branch_version_id` 必填 |

同一 `mutation_id` + 相同 canonical 内容：返回 **原始** 结果（幂等）。
同一 `mutation_id` + 不同内容：`rejected` `content_drift`。

---

## 7. Pull

每实体：

| 字段 | 必填 | 说明 |
|------|------|------|
| `entity_type` / `client_uuid` | 是 | |
| `version_id` | 是 | 稳定版本 |
| `root` | 是 | closed keys |
| `media` | 是 | canonical 排序 |
| `deleted_at` | 是 | `integer \| null`（§2） |
| `conflict_summary` | 否 | 有未解决并发分支时**必填**；纯 restorable tombstone（`branch_version_ids=[]`，§8.2）时亦**必填** |

**conflict_summary closed keys：**

| 字段 | 必填 |
|------|------|
| `conflict_id` | 是 |
| `entity_type` | 是 |
| `client_uuid` | 是 |
| `stable_version_id` | 是 |
| `branch_version_ids` | 是（数组，字典序；纯 tombstone restore 句柄时为 `[]`） |

**游标 / 可发现性（冻结）：** 实体表拥有增量 pull cursor/rev。下列任一耐久写入**必须**推进该实体的 pull cursor/rev，即使稳定 `version_id` 未变：

- 新建或更新未解决的 `conflict_id`；
- 新建 `branch_version_id`（`branched` 提交）；
- 更新 tombstone-scoped restore 句柄（§8.2）的可见摘要。
- 新建或更新 `source_relation_summary`（§12.3）；关系中的每个 Record 都必须推进自身
  pull cursor/rev，即使其稳定 `version_id` 未变。

`conflict_summary` 在存在未解决并发分支**或** restorable 纯 tombstone 句柄时必填；无上述情况时省略。页界仍适用。对端不得因「stable version_id 未变」而永久看不到分支或 restore 句柄。

**不** 在普通 pull 返回：分支 root 全文、媒体 bytes、版本图。每页 conflict_summary ≤ 32。
第 33 个 mandatory summary 必须留到下一页，并把本页 cursor 截在该实体尚未披露的 rev 之前；
不得先推进 cursor 再静默省略。实体数和字节上限同样以附完 branch IDs、relation peers 的最终
HTTP JSON envelope 为准；一个完整 dependency group 本身无法装入时 fail closed。

---

## 8. Conflict detail 与 Resolution CAS

### 8.1 Detail

`GET` … `/conflicts/{conflict_id}`

返回 closed 对象：

| 字段 | 说明 |
|------|------|
| `conflict_id` | |
| `stable_version_id` / `stable_root` / `stable_media` | |
| `branches[]` | 每项：`branch_version_id`, `root`, `media`, `mutation_id?` |
| `conflicting_paths` | 真实冲突 canonical 路径列表（字典序） |
| `auto_merged` | object：路径 → 冻结值（已自动合并、resolution **不可改**） |

### 8.2 Resolve

`POST` … `/conflicts/{conflict_id}/resolve`

| 字段 | 必填 |
|------|------|
| `expected_stable_version` | 是 |
| `expected_branch_versions` | 是（完整集合，字典序；**无分支时必须 `[]`**） |
| `resolved_root` | 是 |
| `resolved_media` | 是（canonical 排序） |
| `resolution_mutation_id` | 是 |
| `conflict_choices` | 是 | map：`path` → 所选值（仅 `conflicting_paths` 内键） |

**校验 fail-closed：**
对每个 **非** `conflicting_paths` 的可合并路径，`resolved_root`/`resolved_media` 必须等于
`auto_merged`（或 stable⊕已合并结果）。改写自动合并路径或非冲突媒体 → `rejected`
`rewrote_auto_merged_path`。服务器以 `auto_merged ⊕ conflict_choices` 重建权威稳定根；
`resolved_*` 必须与重建结果 deep-equal（规范化后），否则 reject。
**不** 提供「Owner 任意改写已合并字段」旁路；若需改非冲突字段，先 resolution 后再发新 mutation。

授权：Record/WakeObservation 作者或 Owner；其它根既有 ACL。
CAS 失败：返回最新 summary，不改状态。

**显式 restore（tombstone→live）— 唯一路径（冻结为本 CAS，禁止普通 mutation revive）：**

1. **有未解决并发分支时**（例 E/J 后）：使用既有并发 `conflict_id`；`expected_branch_versions` = 完整分支集合；`conflicting_paths` 含 `/_mutation.deleted`（及真实业务冲突路径）；choices/resolved 选 live → 新稳定 live。
2. **纯 tombstone、无未解决并发分支时**（当前 base 删除已 accepted，例 D/G）：服务器为该稳定 tombstone 维护 **tombstone-scoped** `conflict_id`：
   - 绑定 `(entity_type, client_uuid, stable_tombstone_version_id)`；同一三元组幂等同一 `conflict_id`（可在 delete `accepted`/`merged` 时铸造，或首次授权 detail 懒铸造，但不得漂移）。
   - `branch_version_ids` / `expected_branch_versions` **必须为 `[]`**。
   - detail 的 `conflicting_paths` **至少** 含 `/_mutation.deleted`；`auto_merged` 为 tombstone 根/媒体规范投影。
   - `conflict_choices["/_mutation.deleted"] = false`，且 `resolved_root`/`resolved_media` 为授权方期望的 live 完整根（须通过 `auto_merged ⊕ choices` 重建校验）。
   - 成功 → 新稳定 live；该 `conflict_id` 关闭；pull 不再带该 restore 句柄。
   - 若随后有并发 live 分支附着（例 J）：**同一** `conflict_id` 升级为非空 `branch_version_ids`（不再是纯空分支句柄）；resolve 改走本条 1。
3. **禁止** 用 §6 `commit` / 普通 mutation 将稳定 tombstone 推回 live（例 F：`stale_live_over_tombstone`）。
4. 历史无法证明原因的迁移 tombstone：无批量恢复；单条若产品开放，仍仅本 CAS + 同一 ACL，不得另开旁路。

---

## 9. Canonical 路径与三方合并

### 9.1 路径

- Root 叶：`/note`、`/timestamp`、`/payload_json/amount_ml`、`/withdrawn`、
  `/effective_wake_observation_client_uuid`、…
- 删除意图：`/_mutation.deleted`（bool）
- 媒体：`/media/{media_uuid}` keyed；成员变更 = 增删改该 uuid 的 manifest 项内容

### 9.2 数组

`payload_json` 内未 keyed 的数组整组原子。未知 root/`payload_json` 键 → reject。

### 9.3 媒体合并

| base | A | B | 结果 |
|------|---|---|------|
| {} | +m1 | +m2 | merge |
| {m1} | −m1 | +m2 | merge |
| {m1} | −m1 | m1 内容变 | **branch** 整根 |
| {m1} | m1 内容变 | m1 删除 | **branch** |

输出 media 字典序。仅顺序不同 → 非冲突。

### 9.4 非合并路径

`/updated_at`、`/created_by_membership_id`、`/observer_membership_id` **不进入**
`conflicting_paths`（§4.0）。

---

## 10. 无歧义例子

**例 A — 不相交字段自动合并（两侧 `updated_at` 不同仍 merged）**

```text
base:  { note: "a", payload_json.amount_ml: 100, updated_at: 10 }
left:  { note: "b", payload_json.amount_ml: 100, updated_at: 20 }
right: { note: "a", payload_json.amount_ml: 120, updated_at: 30 }
→ merged: note=b, amount_ml=120, updated_at=max(20,30)=30
```

**例 B — 同路径不同值 → branched**

```text
base/left/right 仅 /note 分别为 a/b/c → branched；stable 仍 base
```

**例 C — 同路径同值 → 非冲突**

**例 D — 当前 base 删除**

```text
live V1; mutation deleted=true, base_version=V1
→ accepted; stable tombstone V2, deleted_at≠null
→ 响应/pull 带 tombstone-scoped conflict_id（branch_version_ids=[]；§8.2 供显式 restore）
```

**例 E — 并发删/改（均自 live V1；顺序 commit）**

```text
双方 mutation 均 base_version=V1：
  D: deleted=true
  E: deleted=false，业务字段相对 V1 有改动

顺序提交（服务器无「双 unit 同时冻住 stable」路径；真并发 = 同 base 先后到达）：

  先 D 后 E（删除先到）：
    D → accepted → stable tombstone V2，V2.parent 含 V1；
      响应带 tombstone-scoped conflict_id（§8.2）
    E → branched（例 J 特化）：编辑保留为分支；stable 仍 tombstone V2；
      conflict_id + branch_version_id 必填

  先 E 后 D（编辑先到）：
    E → accepted → 新稳定 live V2'（note 等已变），V2'.parent 含 V1
    D → branched：删除意图保留为分支；stable 仍 live V2'；
      conflict_id + branch_version_id 必填；conflicting_paths 含 /_mutation.deleted

任一顺序：先到者 accepted（或可 merged），后到者同 base 并发 mutation → branched；
**stable = 先接受的版本**，不会停留在 V1。
未 commit 前：reconcile 可将双方标为 conflict_preview（仍不写稳定版本）。
最终可见性变更（删↔活或选字段）仅经 §8.2 resolve CAS。
```

**例 F — 稳定 tombstone 后陈旧 live replay（非并发证明）**

```text
stable = tombstone V2 (parent live was V1)
incoming: deleted=false, base_version=V1 以外的旧证明 / null / 与 parent 无关
  或 base_version=V2 却推 live 而无 resolution
→ commit/reconcile unit status = rejected
→ code = stale_live_over_tombstone
→ 不得 revive；不得 branched；客户端 pending 不得靠 confirmed 清掉
```

**例 G — 显式 restore（唯一路径 = §8.2 resolve CAS）**

```text
# 纯 tombstone（无未解决并发分支）— 例 D 之后
stable tombstone V2；tombstone-scoped conflict_id C（branch_version_ids=[]）
POST /conflicts/C/resolve
  expected_stable_version=V2
  expected_branch_versions=[]
  conflict_choices["/_mutation.deleted"]=false
  resolved_* = 授权 live 完整根/媒体
→ 新稳定 live V3；C 关闭

# 有并发分支时 — 例 E/J 之后
既有 conflict_id + 非空 expected_branch_versions；choices 含 live / 业务路径
→ 新稳定 live（或仍 tombstone，若 choices 选删除）
```

**例 H — mutation_id 内容漂移 → rejected content_drift**

**例 I — 同一 tombstone mutation 精确幂等**

```text
tombstone mutation_id=M 已 accepted
retry 同 M 同 body → 原 accepted 结果（confirmed/accepted 幂等回放）
```

**例 J — 删除已稳定，后到的同 base 并发编辑（证明规则）**

```text
V1 live
mutation D: deleted=true, base_version=V1, mutation_id=Md → accepted → stable tombstone V2,
  V2.parent_versions 包含 V1（实现须持久化 parent 边）
later mutation E: deleted=false, base_version=V1, mutation_id=Me≠Md, 业务字段相对 V1 有改动
→ 检测：stable 为 tombstone 且 incoming.base_version ∈ parents(stable_version)
  且 incoming 为 live 且 mutation_id 非删除幂等 → **branched**
  （编辑保留为冲突分支；stable 仍 tombstone V2；conflict_id 必填）
若 E.base_version 不是 V2 的 parent（例如更旧或未知）→ rejected stale_live_over_tombstone
```

---

## 11. 历史闭合睡眠 → WakeObservation 确定性 ID

迁移器（server offline-migrate 与 Android Room 27 **必须同式**）：

```text
NAMESPACE = 7c9e6679-7425-40de-944b-e07fc1f90ae7   # frozen, lezi wake migration

# WakeObservation client_uuid
NAME_OBS  = "wake_obs_v1:" + sleep_client_uuid + ":" + decimal(legacy_updated_at)
client_uuid(WakeObservation) = UUIDv5(NAMESPACE, UTF-8(NAME_OBS))

# log→wake 媒体 media_uuid（禁止 random UUID）
NAME_MEDIA = "wake_media_v1:" + sleep_client_uuid + ":" + legacy_media_uuid
             + ":" + lowercase_hex_sha256 + ":wake"
media_uuid(wake) = UUIDv5(NAMESPACE, UTF-8(NAME_MEDIA))
```

| 输入 | 含义 |
|------|------|
| `sleep_client_uuid` | 原 sleep Record UUID |
| `legacy_updated_at` | 迁移前所用 closed sleep 行的 `updated_at`（integer epoch ms） |
| `legacy_media_uuid` | 迁移前 sleep 上 `role=log` 媒体行的 `media_uuid`（原样字符串） |
| `lowercase_hex_sha256` | 该媒体行的 `sha256`（64 lowercase hex；与字节一致） |

字段转移：`wake_timestamp = legacy end_timestamp`；`note` 原样；每条 log 媒体：
**字节与 sha256 原样复用**，`role=wake`，`media_uuid` **仅** 按上式 UUIDv5 生成
（hash 相同不足以代替 uuid 公式；双端必须得到同一 `media_uuid` 才能对齐
`stable_media` / 引用 CAS）。`observer_membership_id = created_by_membership_id`
（legacy 作者）；`withdrawn=false`；Sleep
`effective_wake_observation_client_uuid =` 该观察 uuid。
开放 sleep：不创建 WakeObservation。
**禁止** 迁移路径使用 random/UUIDv4 或各端自创不同 NAME 输入；禁止只对齐 sha256
而放任 `media_uuid` 分叉。

---

## 12. 疑似重复与来源关系 API（最小冻结）

服务器 **不** 写 `neighbor_losers` / 近邻 tombstone。

### 12.1 作者等价声明

`POST` … `/source-relations/declare`

| 字段 | 必填 |
|------|------|
| `mutation_id` | 是 |
| `record_client_uuid` | 是（必须为调用者作者） |
| `equivalent_to_client_uuid` | 是 |
| `expected_record_version` | 是 |
| `expected_other_version` | 是 |

成功：持久化 pending 或直接关系边（组未完整时允许半边；Owner resolve 收口）。
失败 CAS → 最新版本摘要。

### 12.2 Owner 组 resolution

`POST` … `/source-relations/resolve-group`

| 字段 | 必填 |
|------|------|
| `mutation_id` | 是 |
| `member_client_uuids` | 是（完整组，字典序） |
| `display_client_uuid` | 是（∈ members） |
| `expected_versions` | 是 | map uuid → version_id |

成功写 `source_relation`：

| 字段 | 说明 |
|------|------|
| `relation_id` | server id |
| `display_client_uuid` | |
| `source_client_uuids` | 非展示成员 |
| `media_retained` | true（永久） |

源记录 **保持 live 实体**（`deleted_at=null`）；时间轴主路径只展示 display；来源可展开。
**禁止** 用 `deleted_at` 表示来源隐藏。

### 12.3 Pull 可见性

稳定实体可附可选 `source_relation_summary?`：`{ relation_id, role: display\|source, peer_ids[] }`。
无关系则省略。关系耐久写入必须重新发射每个成员 Record；完整 summary 先参与普通 pull 的
summary/实体数/最终 JSON envelope 分页预算，再决定 cursor，禁止在 cursor 后追加或省略。

---

## 13. LocalWrite no-pull

| 门闩 | 要求 |
|------|------|
| 协议 | 具备 §1 全部 capability |
| 运行 | 前台 + 可信 endpoint + 健康租约 |
| 行为 | 冻结 dirty 单元 → reconcile → commit；**不** pull |
| cursor | **不** 前进增量 pull cursor |
| 失败 | 保留 pending；不回滚本机护理事务 |
| 完整周期 | 回前台/网络恢复/下拉/常规周期仍 pull + 结算 |

---

## 14. 与旧 wire 对照（禁止混写为已交付）

| 主题 | 0.3.9–0.3.12 已交付 | 0.3.13 规划 |
|------|---------------------|------------|
| 同 UUID | `updated_at` LWW | 三方合并 / 分支；`updated_at` 不冲突 |
| Reconcile | adopt_remote 等 | `confirmed\|publish\|conflict_preview\|rejected` |
| Commit | 修订 CAS | `accepted\|merged\|branched` + `conflict_id` |
| 跨 UUID | 服务器近邻 tombstone | 来源关系 API |
| 睡眠醒来 | `end_timestamp` | WakeObservation + `effective_wake_observation_client_uuid` |
| 删除复活 | 墓碑永胜 / 新 UUID | 因果 tombstone + 例 F/J/G |

---

## 15. 验收（文档冻结）

1. 字段/枚举/例子以本文为准；ADR/CONTEXT 只链到本文，不另造标识符方言。
2. 票 02–08 不得发明第五 reconcile status、第二冲突句柄名或第二套删除编码。
3. 无 StructureTest 锁行数/目录布局。
