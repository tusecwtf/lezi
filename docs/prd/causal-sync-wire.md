# 乐记 — 0.4.0 因果同步 Wire 合同（conflict-v2 冻结）

> **状态：** 本文冻结 0.4.0 **目标合同**；运行时实现与 capability 激活仍由
> `causal-sync-conflict-transport-hardening` 02–27 逐票交付，本文本身不构成交付或生产证据。
> 当前 tree 的 0.3.13/code 20/Room 27/server schema 12 是升级源；家庭 NAS 的实际 source
> schema 仅允许在获批维护窗 preflight 中只读确认，未确认时不得猜成 11 或 12。历史
> 0.3.9–0.3.12 叙述见
> [`sync-trusted-endpoint.md`](./sync-trusted-endpoint.md) 与 [`data-model.md`](./data-model.md)。
> 架构决策（不含 wire 字段权威）：[ADR-0019](../adr/0019-server-validates-constraints-not-care-truth.md)、
> [ADR-0022](../adr/0022-commit-first-choice-only-conflict-snapshots.md)、
> [ADR-0021](../adr/0021-wake-observation-and-nondestructive-duplicate-groups.md)。
> 术语（不含字段/枚举权威）：根 [`CONTEXT.md`](../../CONTEXT.md)。
> **字段名、枚举、closed key set、合并路径与例子：本文是唯一权威。**
> 单一发布身份：Android/server **0.4.0**、versionCode **21**、Room **28**、本地数据契约
> **5**、server schema **13**、同步 floor **21**。共享语言无关语料：
> [`config/conflict-v2-golden.json`](../../config/conflict-v2-golden.json) 及其
> [JSON Schema](../../config/conflict-v2-golden.schema.json)。

实现票不得发明竞争 shape、第二套 verdict 名或第二套删除/媒体编码。

---

## 1. 能力与切割

| 项 | 合同 |
|----|------|
| 新 capability（认证握手 `capabilities` 数组，字面量冻结） | `causal_sync_v2`；票 27 完成前服务端与客户端都**不得** advertise/接受；旧 `causal_versions`/`wake_observation`/`source_relations` 集合只标识 0.3.13 source wire，不等价于 v2 |
| minSupported | 抬到 versionCode **21** 之前须先原子发布可安装、签名与 hash 匹配的 code 21 APK + metadata；能力、schema、floor 在票 27 一起激活 |
| 旧客户端 / mixed generation | mutation 前 `capability_mismatch`；无 dual-read、dual-write、downgrade 或 skip-unknown |
| Android upgrade | Room **27→28** 相邻非破坏迁移；永久 code 6/Room 24 链连续到 28；facts/tombstone/pending/frozen envelope/conflict/media/spool/session/credentials/endpoint/TLS trust 全保留 |
| Server startup | 只接受精确 `user_version=13` 或空 fresh root；不在 startup/ordinary CD 自动迁移 |
| Server source | 维护窗 copy-out `offline-migrate` 只接受完整且 shape 匹配的 **11 或 12**，写独立 staging root；其它版本/WAL/SHM/media 不完整 fail closed；实际生产 source 须在 stop/rm 前只读测量 |
| CD / rollback | H28 实现迁移，H29 独占 guarded schema-cutover CD，H30 独占隔离 rollback rehearsal；生产执行仍由 `lossless-family-causal-sync/09` 且必须再次获批维护窗 |

HTTP 路径前缀字符串（如 `/v1/families/...`）可由实现票贴合现有路由树，但 **职责、方法语义与 body shape 以本节为准**，不得另起竞争 API。

### 1.1 跨 tracker 所有权（不复制实现）

- [R12](../../.scratch/repository-dedup-algorithm-audit-20260809/issues/12-bounded-conflict-resources.md)：
  commit limiter 与每根 open-branch cap。
- [R17](../../.scratch/repository-dedup-algorithm-audit-20260809/issues/17-bounded-conflict-head-loader.md)：
  bounded branch/base loader。
- [R18](../../.scratch/repository-dedup-algorithm-audit-20260809/issues/18-conflict-snapshot-receipt-pagination.md)：
  snapshot receipt、continuation、full-set token 与 response budgets。
- [R19](../../.scratch/repository-dedup-algorithm-audit-20260809/issues/19-resolution-metadata-retention.md)：
  resolution query budget 与 metadata retention。
- [release 09](../../.scratch/lossless-family-causal-sync/issues/09-two-client-cutover-release-and-acceptance.md)：
  唯一生产切割/双端 smoke owner；H01–H43 与本合同都不能自行 deploy 或宣称生产完成。

### 1.2 认证同步握手（source 与 v2 共用入口）

endpoint trust 与家庭 session 已成立后，每个普通同步周期只发一次
`POST /v1/sync/handshake`。`/health`、`/ready`、`/v1/setup-status` 保留为显式运维、设置与
恢复入口，但不是普通同步的串行前置条件。

请求是 closed object：

```json
{
  "protocol_version": 1,
  "required_capabilities": ["causal_versions", "source_relations", "wake_observation"]
}
```

响应是 closed result，字段如下：

| 字段 | 合同 |
|------|------|
| `protocol_version` / `server_version` / `ready` | 协议字面量、服务端发布版本与当前同步 readiness |
| `capabilities` | H14 source wire 精确三项 `causal_versions`,`wake_observation`,`source_relations`；H27 前任何额外项（尤其 `causal_sync_v2`）均 mismatch |
| `principal` | `{membership_id, device_id, role}`；全部从 session/ACL 派生，客户端不得提供 |
| `directory_generation` | 成员/设备目录结构 generation；认证活动时间不得使它变化 |
| `limits` | `{pull_page_max_entities:200,pull_page_max_encoded_bytes:9437184,pull_page_max_decoded_bytes:8388608,pull_max_pages:500,commit_batch_max_units,media_max_bytes}` |
| `compression` | `{pull_response:["gzip","identity"]}`；客户端普通同步选择 `gzip`，显式 identity 仍是等价合同 |
| `retry_hints` | `{retry_after:true}`；仅声明服务器可提供 Retry-After，H15 才拥有退避算法 |

客户端必须先验证 protocol、required capabilities、ready 与 principal/session 精确一致，再读目录、
pull、reconcile 或 commit。`directory_generation` 与本地缓存相同则不得下载成员目录；不同则读取一次
`GET /v1/family/members`，且其顶层 `directory_generation` 必须与握手一致后才能原子替换本地
generation+directory。用户显式刷新成员目录不受缓存命中抑制。

protocol/capability mismatch 返回 HTTP 409、`not_ready` 返回 HTTP 503；二者使用
`{"status":"rejected","error":{"code":"...","retryable":false}}`。认证失败沿用 session
认证错误并允许既有 refresh-once seam；最终 auth/mismatch/not-ready 都必须在 mutation 前终止。

### 1.3 前台传输重试（H15）

自动重试只有一个 typed policy owner，并且只包裹本身可幂等重放的 authenticated
handshake、ConflictSnapshot detail page、pull、frozen mutation commit 与 choice-only resolution。
source reconcile 不进入该 owner；media prepare 只在 H15 冻结预算，由 H17 接入。重试不创建后台任务，
不改写 mutation/resolution identity，不清 Room pending 或旧 ConflictSnapshot 事实。

| operation | connect timeout | response timeout | total attempts | elapsed hard cap |
|-----------|-----------------|------------------|----------------|------------------|
| handshake / detail | 3 s | 10 s | 3 | 30 s |
| pull / commit / resolution | 3 s | 20 s | 3 | 60 s |
| media prepare（H17 接入） | 5 s | 90 s | 3 | 240 s |

HTTP 408、429、非 terminal 5xx 与 I/O/timeout 可重试。合法 `Retry-After`（非负 delta-seconds
或 RFC 1123 HTTP-date）优先；非法/溢出值回退到 `base=1 s`、`cap=10 s` 的 exponential
full jitter。服务器提示或 jitter delay 若超出剩余 elapsed budget，则本轮立即结束，不裁剪提示、
不扩张预算；晚启动请求的 connect/response timeout 必须收缩到剩余预算，并在 deadline 主动断开
阻塞 I/O，恰好到达上限也不得返回晚成功。401/403、refresh 后仍失败的 auth、capability、ACL、
canonical/domain、token/choice/restore/CAS 以及 `not_ready` 是终态，直到 session/capability/
snapshot 等外部状态改变。

`snapshot_stale|snapshot_expired` 终止本次 resolution mutation，并交给 H09 的 refresh 状态机；
旧事实/快照保持只读证据，不得标为永久失败或删除。retry telemetry 只允许 operation、错误类别、
已完成 attempts 与 delay，不得记录 family/root/成员/护理内容。

### 1.4 gzip 有界普通增量 pull（H16）

本节只冻结 `GET /v1/pull` 的普通增量页；ConflictSnapshot 的 receipt、opaque continuation 与
分页预算仍由 §8/H18 独占，二者不得复用 token 或状态。H27 前本节也不激活 `causal_sync_v2`。

客户端在一次已认证同步周期中复用 §1.2 握手所得合同，并为每页发送
`cursor`、`generation` 与从 0 单调递增的 `page_index`。客户端进程重启或本轮失败后，以最后耐久
checkpoint 的 cursor 开始新一轮，`page_index` 重新从 0 计数。服务端响应是 closed object：
`{entities,cursor,generation,page_index,has_more,family_name}`，其中 `page_index` 必须精确回显。
source capability 仍活动期间，服务端只为旧 source 客户端兼容缺失的 `page_index=0`；H16 客户端
始终显式发送。

每一页是一个完整 JSON envelope。客户端发送 `Accept-Encoding: gzip` 时服务端返回 gzip 与
`Content-Encoding: gzip`；显式 `identity` 返回相同解码字节和事实。服务端总是返回
`Vary: Accept-Encoding`，不接受协商集合以外的编码。预算按页同时成立：最多 200 entities、
HTTP content 最多 9 MiB encoded bytes、解码后的完整 JSON 最多 8 MiB、每轮最多 500 页。
服务端在 JSON 完整序列化后检查 decoded budget，再编码并检查 encoded budget；gzip 工作不占用
family write mutex。客户端先限制 encoded body，再用有上限的流式解压限制 decoded body，最后才
解析完整 envelope，禁止把 partial entity 暴露给 engine。

下列任一情况整页 fail closed：未协商/不一致的 Content-Encoding，encoded 或 decoded 超预算，
truncated/corrupt gzip 或 JSON，entity count 超预算，同页或连续页重复 `(type,client_uuid)`，
重复/跳跃 `page_index`，cursor 倒退，`has_more=true` 但 cursor 未推进，或 generation 在分页中变化。
客户端只有在完整页通过校验、该页事实的 Room transaction 提交且相关 media materialization 成功后，
才耐久保存 cursor/generation/family_name checkpoint。此前崩溃或拒绝会从上一 checkpoint 重放；
重放依靠既有 UUID/upsert 幂等语义，不得写入部分页或提前推进 cursor。

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

### 3.1 Mutation 单元（commit-first 完整 shape）

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

### 3.2 Commit batch 成功响应（closed）

顶层只有一次 `generation` 与 `results`；unit **禁止**重复 generation/cursor：

| Unit 字段 | 说明 |
|-----------|------|
| `status` | `accepted\|merged\|branched`；精确重放仍返回原 status，不新增 `replayed` status |
| `mutation_id` / `request_hash` | 回显稳定身份与 canonical 请求 hash |
| `replay` | bool；首次终态 `false`，同 mutation/hash 重放原终态为 `true` |
| `stable` | `{ version_id, root, media, deleted, deleted_at }` 完整规范投影；branched 时仍为冲突前 stable |
| `branch_version_id` | 仅 `branched` 必填；其它 status 禁止 |
| `conflict_id` | 存在 open branch 或可恢复纯 tombstone 时必填；其它状态禁止 |

失败统一使用 §9.5 terminal envelope；不得泄露跨家庭存在性。响应收缩的运行时删除由 H25
实现；在此之前 0.3.13 runtime 的冗余字段不是 v2 合同的一部分。

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
| 禁止客户端伪造盖章 | 同上盖章字段 | mutation closed root 中出现即 `unknown_field`；服务器只从已认证 principal/既有版本重建，禁止“忽略或拒绝”双解释 |

**`updated_at`：** 在 root 内，类型 integer epoch ms。是用户可见编辑/审计时间，**不是** 并发 token。
合并/接受后服务器规范化：

- `accepted`（单方）：`updated_at = max(mutation.root.updated_at, previous_stable.updated_at if any)`；
- `merged`：`updated_at = max(stable.updated_at, incoming.updated_at)`；
- 单独两侧 `/updated_at` 不同而其它路径可合并 → **仍 merged**，不 branch。

**`created_by_membership_id` / `observer_membership_id`：** 仅服务器首次盖章后冻结；后续 mutation 不得改写；比较时排除出冲突集。

### 4.1 `baby` root

| 键 | 必填 | 作者 |
|----|------|------|
| `nickname` | 是 | client |
| `sex` | 是 (null 可) | client |
| `birthday` | 是 (null 可) | client |
| `avatar_media_uuid` | 是 (null 可) | client；须 ∈ media 或 null |
| `updated_at` | 是 | 见 §4.0 |
| `created_by_membership_id` | stable/snapshot 有；mutation 禁止 | server |

未知键 → reject。媒体 role：`avatar` 0–1。

### 4.2 `record` root（非 sleep 与 sleep 共用外层键）

| 键 | 必填 | 说明 |
|----|------|------|
| `baby_client_uuid` | 是 | |
| `type` | 是 | 既有 RecordType 字面量 |
| `custom_item_client_uuid` | 是 (非 custom 时为 null) | |
| `timestamp` | 是 | 主时间 / SleepStart |
| `end_timestamp` | 见下 | 闭集内键；sleep 禁止；非 sleep 必须显式具体值或 null |
| `note` | 是 (可为 null) | |
| `payload_json` | 是 | object；typed closed keys 沿用现网 schema v2 白名单（data-model §3.6） |
| `schema_version` | 是 | 字面量 `2` |
| `updated_at` | 是 | §4.0 |
| `created_by_membership_id` | stable/snapshot 有；mutation 禁止 | server |
| `effective_wake_observation_client_uuid` | **仅** `type=sleep`：是 (可为 null) | 有效 WakeObservation；非 sleep 禁止出现 |

**`end_timestamp`（closed 键，按 `type` 分叉）：**

| `type` | 必填 | 规则 |
|--------|------|------|
| `sleep` | 禁止 | 键**不得**出现（含 null）→ `rejected` `invalid_domain`（醒来走 WakeObservation，§4.5） |
| 非 sleep | 是（`null` 可） | 类型 `integer \| null`；语义与 0.3.12 相同（区间类若有）；当前非 sleep 类型保持 `null` |

未知键 → reject。

`payload_json` for sleep：仅 `anomaly_flag`、`is_nap`（与现网 allowlist 一致）。

媒体 role：`log` 0–3。

### 4.3 `care_plan` root

业务 closed 键为 `baby_client_uuid`, `type`, `scheduled_at`, `scheduled_zone_id`, `note`,
`payload_json`, `schema_version`, `status`, `fulfilled_record_client_uuid`, `fulfilled_at`,
`source_record_client_uuid`, `custom_item_client_uuid`, `updated_at`；全部已知业务键必须出现，
领域允许清空者显式为 null。`created_by_membership_id` 仅 stable/snapshot 有、mutation 禁止。
未知或缺少已知键均 reject。媒体 role：`plan` 0–3。

### 4.4 `custom_item` root

| 键 | 必填 |
|----|------|
| `name` | 是 |
| `icon_slot` | 是 |
| `updated_at` | 是 |
| `created_by_membership_id` | stable/snapshot 必填；mutation 禁止 |

无媒体。

### 4.5 `wake_observation` root

| 键 | 必填 | 说明 |
|----|------|------|
| `sleep_record_client_uuid` | 是 | 同家庭 Sleep Record |
| `wake_timestamp` | 是 | 必须 `>=` 目标 Sleep 的 `timestamp`，否则 `rejected` `invalid_domain` |
| `note` | 是 (可为 null) | |
| `withdrawn` | 是 | bool；`true` = 观察者撤回，不参与暂定/有效投影 |
| `updated_at` | 是 | §4.0 |
| `observer_membership_id` | stable/snapshot 有；mutation 禁止 | **仅服务器盖章** |

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

未知键 → reject。根上 `avatar_media_uuid` / 引用与清单不一致 → `rejected`
`invalid_domain`（**不** branch；入站 fail closed）。

**顺序：** 数组顺序 **不是** 产品语义；三方合并按 `media_uuid` 键集合。
稳定投影与成功响应中的 `media` **必须** 按 `media_uuid` 字典序升序输出。
仅顺序不同的 mutation 与稳定内容在 media 集合意义上相同，不创建新版本；
服务端按其因果 base 返回 `accepted|merged`，精确 mutation replay 返回原 status 且 `replay=true`。

#### 4.6.1 Preimage lifecycle

`PUT /v1/causal/media/{media_uuid}` 携带 `X-Lezi-Media-Sha256` 与原始 bytes，只创建未发布
preimage。服务端必须持久绑定 family、staging membership、UUID、SHA-256、byte size、
created/expiry 与状态；同 UUID/同 bytes replay 幂等，同 UUID/不同 bytes 冲突且不得覆盖。
单文件上限服从服务器媒体上限；未消费资源固定为每 membership 64 个、每 family 256 个、
family aggregate 512 MiB、TTL 24 小时。

成功 PUT 返回 closed durable receipt
`{media_uuid,status,byte_size,sha256,expires_at}`；`status` 只允许 `staged|consumed`。Android
必须在 commit 前把全部 receipt 与同一 mutation、pending fact epoch、canonical request hash 和
immutable spool manifest 持久绑定。commit 请求发出前先 durable 标记 `commit_unknown`；进程死亡或
响应丢失后先 replay 同一 commit，不重复 PUT 已有 exact receipt。只有 accepted/merged 与本机 fact
settlement 同事务落库后，spool 才可进入幂等 cleanup；branched/pending/unknown 永久保留到后续明确
resolution/settlement。当前 Android 没有 media mutation abandon command，因此 pending 不存在隐式删除
入口；未来若增加显式 abandon，只有 commit 从未发出、用户确认且 pending fact 在同一事务解除绑定后才
eligible。

`commit` 必须在写 version/rev/publication 前精确匹配 UUID/SHA/size，并在同一数据库事务把
accepted/merged/branched 引用标记为 consumed。最终 `media/{family}/{uuid}` 只接收数据库已
消费的 bytes；commit/restart retry 必须从任意 DB/filesystem crash point 收敛。启动在开放业务
路由前恢复 consumed promotion，并以 durable GC journal 删除过期/无 metadata preimage、同步
目录；稳定版本或 branch 引用的 consumed bytes 不受 TTL GC。

---

## 5. 普通发表只走 commit-first

`causal_sync_v2` 下，普通发表从 Room product facts 冻结一个 §3.1 完整 mutation；如有媒体先取得
durable preimage receipt，然后**只调用一次** `POST …/commit`。安全性来自不可变
`mutation_id`、canonical `request_hash`、`base_version` 与持久终态；不得先调用普通
`/reconcile`，不得推进 pull cursor。

0.3.13 runtime 的 `/reconcile` 与 `confirmed|publish|conflict_preview|rejected` 是升级源合同，
不是 v2 fallback。H26 在全部 root/media 已迁移后删除其 runtime route/state/recording/test；在此之前
不得提前删除仍由 0.3.13 source runtime 消费的路径，也不得让 v2 advertise 成功后调用它。

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
响应保留原 `accepted|merged|branched` 并设置 `replay=true`；`replayed` 禁止成为第四种 status。
同一 `mutation_id` + 不同内容：`rejected` `content_drift`。

### 6.1 Commit admission（R12）

一次含任何非精确 receipt replay 的 authenticated commit batch 计一次 admission attempt；全 batch
精确 replay 免计，混合 replay/new batch 计一次。principal key 是
`(family_id, membership_id)`（不含 device），family key 是 `family_id`；60 秒半开滑动窗口内分别
最多 120/1,200 次。窗口是进程内资源保护，restart 后重新开始；durable correctness 由 receipt 与
branch 表承担。

每个 `(family_id, entity_type, client_uuid)` 在所有 open conflict 下最多保留 64 条 durable
`conflict_branches`；tombstone restore 的空 branch set 不占名额，resolved branch 不占名额。第 65 条
在 branch/version/rev/receipt 任一写入前原子拒绝，既有 branch 必须继续能被 pull/detail/resolution
发现，不得覆盖、删除或从摘要隐藏。

Admission saturation 是 HTTP 429，不进入 §9.5 的 terminal `retryable=false` envelope：

| code | detail.scope | 解除条件 |
|------|--------------|----------|
| `causal_commit_principal_rate_limited` | `principal` | 60 秒窗口释放 |
| `causal_commit_family_rate_limited` | `family` | 60 秒窗口释放 |
| `causal_open_branch_limit_reached` | `root` | 既有 conflict resolution 关闭 branch |

body 沿用 `{ "code": code, "detail": { "scope": scope, "retryable": true } }`；不得含家庭事实、
root UUID 或成员显示信息。响应携带 `Retry-After: 60`；客户端按 §1.3 消费，不得在 header/body
中追加 root UUID、成员显示信息或家庭事实。

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

## 8. ConflictSnapshot v2 与 choice-only Resolution CAS

### 8.1 Detail

`GET` … `/conflicts/{conflict_id}`

返回 `contract="conflict_snapshot_v2"` 的 closed page。共享 token 下所有页合起来是一个不可损
快照；分页 limit/receipt/response budget 的实现所有权属于 external R18。

| 字段 | 说明 |
|------|------|
| `conflict_id` / `entity_type` / `client_uuid` | 句柄与根身份 |
| `snapshot_token` | 至少 256-bit CSPRNG opaque 值；仅 hash/等价秘密表示持久化；禁止把绑定字段编码成自包含客户端声明 |
| `expires_at` | server epoch ms；detail 可重复读取，token 非单次使用 |
| `stable` | 完整 `{ version_id, base_version, root, media, deleted, mutation_id, actor_id, device_id, received_at }` |
| `branches[]` | 本页完整 version view；按 `version_id` 字典序，不丢 root/media/deleted/base/provenance |
| `conflicting[]` | 本页 path + 完整 candidates；每 candidate 为 `{ choice_id, outcome, sources[] }` |
| `auto_merged[]` | 本页 `{ path, outcome, sources[] }`；path 与 conflicting 全集严格不相交 |
| `page_index` / `continuation` / `complete` | index 从 0 单调；非末页 continuation 为 opaque，末页 null + complete=true |

version view 是 closed object，键只有
`version_id|base_version|root|media|deleted|mutation_id|actor_id|device_id|received_at`；
`base_version` 为该 mutation 声明的直接因果 base（首版为 null）。candidate 的 `outcome` 必须是
§9.1 typed outcome；`sources[]` 每项是 closed
`{ version_id, mutation_id, actor_id, device_id, received_at }`，不得只返回 label/value
或不可追溯的摘要。`choice_id` 是 receipt 内候选 outcome+完整 sources 的 opaque 稳定标识，
不是客户端可解析的编码。

receipt 绑定 family/root、contract version、stable version、**完整** open branch set、全部
choice/outcome/source、分页视图与 expiry。相同 receipt 的重复 detail、各页与服务重启必须给同一
choice ID；stable/branch 改变或 expiry 后旧 token 只返回 `snapshot_stale|snapshot_expired`，客户端
丢弃提交资格并从 summary/detail 刷新。刷新生成新 token，choice ID 可变，客户端只能从新快照重建
选择，禁止按 label/旧 value 猜映射。部分页永远不能授权 resolution。

### 8.2 Resolve

`POST` … `/conflicts/{conflict_id}/resolve`

| 字段 | 必填 | 规则 |
|------|------|------|
| `snapshot_token` | 是 | opaque receipt token |
| `resolution_mutation_id` | 是 | UUID；相同 id + 相同 canonical choices 精确 replay 原终态 |
| `choices` | 是 | 数组；每项仅 `{ path, choice_id }`，按 path 字典序；每个 conflicting path 恰一项 |

请求**禁止** `expected_stable_version`、`expected_branch_versions`、`resolved_root`、
`resolved_media`、裸 value 或 candidate label。服务器从 receipt 独立验证完整 branch set、token
expiry/state、ACL、choice membership 与 path 恰好覆盖，再用 `auto_merged + choices` 重建
root/media/deleted，经过与普通 commit **同一** canonical decoder、领域约束、媒体验证与稳定投影
builder，最后做 stable+branch full-set CAS。修改期间出现新 branch/stable → `snapshot_stale`；
CAS 竞争 → `cas_mismatch`，都不写状态。若需改非冲突字段，resolution 成功后另发普通 mutation。

授权：Record/WakeObservation 作者或 Owner；其它根既有 ACL。
CAS 失败：返回最新 summary，不改状态。

**显式 restore（tombstone→live）— 唯一路径（冻结为本 CAS，禁止普通 mutation revive）：**

1. **有未解决并发分支时**（例 E/J 后）：使用既有 `conflict_id` 的完整 snapshot；
   `conflicting` 含 `/_mutation.deleted`（及真实业务冲突路径）；choice 选 live source 后由服务端重建。
2. **纯 tombstone、无未解决并发分支时**（当前 base 删除已 accepted，例 D/G）：服务器为该稳定 tombstone 维护 **tombstone-scoped** `conflict_id`：
   - 绑定 `(entity_type, client_uuid, stable_tombstone_version_id)`；同一三元组幂等同一 `conflict_id`（可在 delete `accepted`/`merged` 时铸造，或首次授权 detail 懒铸造，但不得漂移）。
   - snapshot 的 open branch set 必须为 `[]`，`conflicting` 至少含 `/_mutation.deleted`。
   - restore choice 只绑定 tombstone mutation **声明的直接 `base_version`**；该版本必须是完整 live
     root + 完整 media，且所有 bytes 可读。禁止搜索祖先、猜多个 parent、补造字段或缺失媒体。
   - 缺 base / base 不完整 / bytes 缺失分别为 `missing_restore_base` /
     `incomplete_restore_base` / `missing_restore_media`；保持 tombstone 不变。
   - 成功 → 新稳定 live；该 `conflict_id` 关闭；pull 不再带该 restore 句柄。
   - 若随后有并发 live 分支附着（例 J）：**同一** `conflict_id` 升级为非空 `branch_version_ids`（不再是纯空分支句柄）；resolve 改走本条 1。
3. **禁止** 用 §6 `commit` / 普通 mutation 将稳定 tombstone 推回 live（例 F：`invalid_domain`）。
4. 历史无法证明原因的迁移 tombstone：无批量恢复；单条若产品开放，仍仅本 CAS + 同一 ACL，不得另开旁路。

---

## 9. Canonical 路径与三方合并

### 9.1 路径与 typed outcome

- Root 叶：`/note`、`/timestamp`、`/payload_json/amount_ml`、`/withdrawn`、
  `/effective_wake_observation_client_uuid`、…
- 删除意图：`/_mutation.deleted`
- 媒体：`/media/{media_uuid}` keyed；成员变更 = 增删改该 uuid 的 manifest 项内容

每个业务 leaf/subtree outcome 只有 `{ "op":"set", "value":… }`；业务可空字段清空必须是
`set(null)`。`{ "op":"remove" }` 仅允许媒体成员删除与 live→tombstone deletion transition；
普通业务字段禁止 remove。live/restore 候选在 `/_mutation.deleted` 上使用 `set(false)` 并绑定
可重建的 live source；tombstone 候选使用 `remove`。缺少已知字段永远不是“未改动”或“清空”。

### 9.2 数组

`payload_json` 内未 keyed 的数组整组原子。未知键→`unknown_field`，缺少已知键→
`missing_field`，错误 JSON 类型→`wrong_type`，非规范数字/时间/UUID/hash→
`non_canonical_value`，领域组合非法→`invalid_domain`。所有这些在 commit 与 resolution 共用。

先把 base→head diff 规范化为不重叠 leaf/subtree outcomes。ancestor `set(null)` 与并发 descendant
edit 归到 ancestor path 并冲突；只有最终完整 canonical subtree deep-equal 才合并。禁止因 JSON
Pointer 前缀不同把它们当不相交。

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

### 9.5 Terminal error 与 replay closed shape

终态错误统一为：

```json
{ "status": "rejected", "mutation_id": "<optional>", "error": { "code": "<closed>", "retryable": false } }
```

resolution 可用 `resolution_mutation_id` 取代 `mutation_id`，两者不得同时出现。closed code：
`unknown_field|missing_field|wrong_type|non_canonical_value|invalid_domain|content_drift|`
`unauthenticated|forbidden|capability_mismatch|not_ready|invalid_snapshot_token|snapshot_expired|snapshot_stale|`
`invalid_choice|duplicate_choice|incomplete_choices|missing_restore_base|incomplete_restore_base|`
`missing_restore_media|cas_mismatch`。认证/ACL/能力/canonical/token/choice/restore/CAS 错误不得被
客户端当弱网盲重试；状态改变或取得新 snapshot/session/capability 后才可发起新操作。

成功 replay 不进入本 envelope：返回原 `accepted|merged|branched` 终态并只把 `replay` 设为
`true`。同 resolution mutation ID 但 choices 漂移也返回 `content_drift`。

本 closed list 只属于 v2 authenticated handshake、commit 与 conflict detail/resolve；§12 已冻结的
source-relation 管理 mutation 保留其 route-specific semantic codes，本票不重新设计其产品合同。

### 9.6 确定性 N-way 分类

一次 snapshot 对 stable 与**全部** open branches 同时计算，不做 pairwise fold。每个 head 相对其
真实因果 base 产生 canonical outcomes；未改变某 path 的 head 不投反对票。所有 changed heads 在
一条 path 只有一个 distinct outcome → auto-merged；有两个或更多 → conflicting。完整 history
不可比较时保守 conflicting；base/head 无法有界完整加载时 fail closed。stable、auto/conflict、
candidates、source set 与 byte ordering 必须与 branch arrival/enumeration、UUID、墙钟、作者或角色无关。

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
0.4.0 不做普通 reconcile preview；双方直接按稳定 `mutation_id` commit，先到者建立 stable，后到者
无损合并或耐久 branched。
最终可见性变更（删↔活或选字段）仅经 §8.2 resolve CAS。
```

**例 F — 稳定 tombstone 后陈旧 live replay（非并发证明）**

```text
stable = tombstone V2 (parent live was V1)
incoming: deleted=false, base_version=V1 以外的旧证明 / null / 与 parent 无关
  或 base_version=V2 却推 live 而无 resolution
→ commit unit status = rejected
→ code = invalid_domain（stale live over tombstone）
→ 不得 revive；不得 branched；客户端 pending 不得当作成功终态清掉
```

**例 G — 显式 restore（唯一路径 = §8.2 resolve CAS）**

```text
# 纯 tombstone（无未解决并发分支）— 例 D 之后
stable tombstone V2；tombstone-scoped conflict_id C（branch_version_ids=[]）
POST /conflicts/C/resolve
  snapshot_token=<C 对应的完整 receipt token>
  resolution_mutation_id=<stable UUID>
  choices=[{path:"/_mutation.deleted", choice_id:<direct-base-live choice>}]
→ 新稳定 live V3；C 关闭

# 有并发分支时 — 例 E/J 之后
既有 conflict_id 的完整 snapshot token；choices 恰覆盖全部 conflict path
→ 新稳定 live（或仍 tombstone，若 choices 选删除）
```

**例 H — mutation_id 内容漂移 → rejected content_drift**

**例 I — 同一 tombstone mutation 精确幂等**

```text
tombstone mutation_id=M 已 accepted
retry 同 M 同 body → 原 accepted 结果，`replay=true`
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
若 E.base_version 不是 V2 的 parent（例如更旧或未知）→ rejected invalid_domain
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
稳定版本的 `media` / 引用 CAS）。`observer_membership_id = created_by_membership_id`
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

**Canonical / bounds（冻结）：** 一个 live Record 同时至多属于一个 canonical source
component、且在其中恰有一个 `display|source` role。`member_client_uuids` 必须无重复、至多 64
条；服务器一次至多扫描 256 条同 baby + 精确 type 的 live 邻域候选。校验必须 bulk-load named
members；Record 稳定发布/墓碑同步维护 source-relation Module 私有的 eligibility projection，候选
查询必须用 `(family,baby,type,timestamp,uuid)` 索引范围 + 257 行 fail-closed sentinel，不得扫描/排序
全 family JSON。随后在 `timestamp, client_uuid` 稳定排序的含边界 30 分钟窗口内构造有界连通关系；
不得在 family write lock 内逐 member 查询或物化无界全 pair graph。Owner resolve 原子停用所有完全包含于
本次 member set 的旧 half-edge/component，再写一个 canonical component；旧 mutation 的精确 replay
仍返回原 receipt，但不重新激活旧 component。每次 transition 同事务推进所有 member Record 的 pull
rev，普通 root/version 保持不变。

所有 mutation id 合法的 `accepted|cas_mismatch|rejected` 结果都在业务状态校验返回前以 canonical
request fingerprint + closed receipt blob 写入单一 receipt ledger；精确 replay 返回原 receipt，内容漂移
稳定返回 `content_drift`，不得因 Record/version/relation 后续变化重新求值。

语义拒绝返回 `status=rejected` + 稳定 `code`：`duplicate_member`、`too_many_members`、
`unsupported_record_type`、`same_author_only`、`wrong_baby_or_type`、`outside_time_window`、
`disconnected_group`、`incomplete_group`、`candidate_limit_exceeded`、`already_related`、
`content_drift`（以及 request shape 的 `invalid_mutation_id|too_few_members|display_not_member|`
`incomplete_expected_versions|not_live_record|invalid_record`）。恰好 30 分钟合法；named set 漏掉同一
连通分量内的合法邻居必须为 `incomplete_group`，不得静默接受。

### 12.3 Pull 可见性

稳定实体可附可选 `source_relation_summary?`：`{ relation_id, role: display\|source, peer_ids[] }`。
无关系则省略。关系耐久写入必须重新发射每个成员 Record；完整 summary 先参与普通 pull 的
summary/实体数/最终 JSON envelope 分页预算，再决定 cursor，禁止在 cursor 后追加或省略。

Android 把该 summary 当作独立 durable delta：先校验并事务应用 relation component，再决定相同
`version_id`、dirty root 或旧 `updated_at` 的 Record body 是否需要重写。Room relation member 查询必须
提供 observable invalidation；Timeline、Summary 与 Log 的普通投影同时观察该 Flow，使 relation-only
pull 无需改写 Record body 即可立即隐藏 source、保留可展开 provenance。sidecar transaction 仍嵌套在
整页 Room transaction 内；若同页其它 root dependency 失败，整页（含 relation）回滚且 cursor 不前进。
若 source-role 页先于 display-role 页到达，Room 以 `displayClientUuid=""` 明确保存 pending pull delta，
仅让已观察 source 参与 Flow，并在 synthetic pull mutation id 中持久绑定 closed member-set fingerprint；
不得从无 role 的 peer id 猜 display。display-role summary 到达时必须与既有 relation/closed peer set
fingerprint 精确一致，并在同一事务替换为恰有一个 display 的完整 canonical component。
底层 relation/member 写方法不对产品层公开，所有写入只能经过上述 canonical transition。

---

## 13. LocalWrite no-pull

| 门闩 | 要求 |
|------|------|
| 协议 | 具备 §1 全部 capability |
| 运行 | 前台 + 可信 endpoint + 健康租约 |
| 行为 | 冻结 dirty 单元 → commit-first；**不** reconcile、pull |
| cursor | **不** 前进增量 pull cursor |
| 失败 | 保留 pending；不回滚本机护理事务 |
| 完整周期 | 回前台/网络恢复/下拉/常规周期仍 pull + 结算 |

---

## 14. 与升级源 wire 对照（禁止双读双写）

| 主题 | 0.3.13 tree/source | 0.4.0 `causal_sync_v2` |
|------|---------------------|------------|
| 普通发表 | reconcile → commit | 一次 commit-first；reconcile 禁止 |
| 合并 | pairwise 三方 fold | 完整 head set 的确定性 N-way |
| 冲突详情 | stable/branch 字段不完整、客户端 result | 完整 ConflictSnapshot pages + choice-only request |
| nullable | 字段省略与清空可能混义 | 已知业务字段显式值或 null；清空=`set(null)` |
| replay | 原终态但 response marker 不统一 | 原 `accepted\|merged\|branched` + `replay` bool |
| 版本/schema | 0.3.13/code20/Room27/server12 | 0.4.0/code21/Room28/contract5/server13/floor21 |

---

## 15. 验收（文档冻结）

1. 字段/枚举/例子以本文与 shared golden corpus 为准；ADR/CONTEXT 不另造标识符方言。
2. H02–H27 与 external R12/R17/R18/R19 不得发明第二 snapshot/token/choice/error/replay shape。
3. `causal_sync_v2` 在 H27 前不得 advertise；source runtime 保留到其明确删除票，禁止作为 v2 fallback。
4. 无 StructureTest 锁行数/目录布局；Kotlin/Rust 读取同一 fixture，runtime conformance 留给实现票。
