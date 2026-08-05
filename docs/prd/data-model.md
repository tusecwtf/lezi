# 乐记 — 数据模型与同步契约

> 当前 Android 以 **Room** 为本地真相源，并经 **`SyncPort`** 接家庭局域网 `lezi-sync`。
> Android 自 0.3.0 的本地数据契约 v1 起永久支持原地升级保留；当前 NAS wire 仍为 fresh-current。
> 见 [ADR-0008](../adr/0008-support-only-fresh-current-product-contracts.md) 与
> [ADR-0012](../adr/0012-preserve-android-local-data-across-in-place-upgrades.md)。
> 主 PRD：[`README.md`](./README.md)
>
> **当前身份与网络合同（0.3.9）：** 历史的一设备一 membership、`left_at`、长期
> credential、网络名称/明文传输的 0.3 实现说明已由
> [`sync-trusted-endpoint.md`](./sync-trusted-endpoint.md) 和
> [ADR-0011](../adr/0011-root-admin-and-multi-device-membership.md) 取代。当前模型如下节明确为
> membership 1:N device、每设备轮换 session、成员硬删除与无 SSID trusted endpoint。

---

## 1. 原则

1. **先写本地，再同步**（有实现时）：UI 只依赖 Room。  
2. **家庭域 vs 本机域** 分离，避免设置冲突。  
3. 每条业务实体带 **`client_uuid`**，便于幂等与后期同步。  
4. 未登录、离线、等待审批或可信 endpoint 不可达时，`SyncPort` 安全 no-op / 保留 Room 待对账修改，
   **不得**阻塞记账。

---

## 2. ER 概要

```text
LocalUser 1──0..1 Device *──1 Membership *──1 Family
Device 1──* DeviceSession
Family 1──* Baby
Family 1──* PendingDeviceRequest
Family 1──* CustomItemDef
Baby 1──* Record
Record 1──* MediaAsset
LocalUser 1──1 SettingsLocal     # 永不进家庭同步域
Room 待对账标记 / 发布回执             # 长期本机修改与远端证明
```

离线最小路径只需本机 `LocalUser` + `Baby` + `Record`；`Family` 在可信服务器完成建家后才建立，
不为离线模式伪造服务器家庭身份。

---

## 3. 实体字段

### 3.1 LocalUser

| 字段 | 说明 |
|------|------|
| `id` | 本地主键 |
| `display_name` | 本机缓存的**家庭称呼**（与当前 membership 对齐；未加入家庭时可空） |
| `device_id` | 本机标识 |
| `created_at` | |

无全局账号体系；`LocalUser` 只表示本机投影。家庭内的人类身份以 membership 为准，设备与
membership 分层。见 [ADR-0011](../adr/0011-root-admin-and-multi-device-membership.md)。

### 3.2 Family

| 字段 | 说明 |
|------|------|
| `id` | |
| `name` | 必填的**共享家庭名**；全员一致；仅 owner 可改 |
| `owner_membership_id` | 唯一 Owner membership；也可由 `role=owner` 唯一约束派生 |
| `created_at` | |

NAS 家庭记录须持久化共享 `name`（或等价字段），并在成员可见摘要中下发。

### 3.3 Membership

| 字段 | 说明 |
|------|------|
| `membership_id` | NAS 生成的不可变 membership UUID，产品身份主键 |
| `family_id` | 所属家庭 |
| `role` | `owner` \| `member` |
| `display_name` | 当前家庭内唯一的称呼；由管理员批准或修改 |
| `created_at` | 创建时间；删除 membership 时身份行硬删除 |

NAS 将 membership、device 与 credential 分表。`membership_id` 是不可变公开标识，不能用于
换发 token；一个 membership 可以绑定多台 device。角色、称呼和作者身份只由认证后的
canonical principal 决定，客户端 payload 不得冒充管理员、其它成员或其它设备。

`display_name` 先做 Unicode normalization、trim 与连续空白折叠，再执行当前 family 唯一约束；
拒绝空白、控制字符、双向文本格式控制符和产品占位名。普通成员不能直接更新称呼，只能提交
rename request；Owner 可批准、拒绝、主动改名或添加 membership。

成员删除/自行退出家庭时，membership、devices、sessions 与 pending/rename requests 在事务中
硬删除；记录/计划等事实保留，但 `created_by_membership_id` 置空并显示「家人」。称呼立即可
复用，不保留身份墓碑。只有不含身份数据的短期 token-hash revocation marker 可存活到 token
原到期时间。

### 3.3.1 Device

| 字段 | 说明 |
|------|------|
| `device_id` | 服务器分配的不可变设备标识；不是凭据 |
| `membership_id` | 所属人类 membership |
| `device_name` | 同一 membership 内唯一；默认来自 Android 设备名，可改 |
| `last_used_at` | 管理员可见的最近使用时间 |
| `created_at` | 绑定时间 |

每台 Device 拥有独立 credential lineage。撤销一台设备不改变 membership 或其它设备；服务端
对该设备返回 `device_removed`，客户端下次可信连接后清本地家庭数据。

### 3.3.2 DeviceSession

| 字段 | 说明 |
|------|------|
| `session_id` | 会话 lineage 标识 |
| `device_id` | 只绑定一台 Device |
| access hash/expiry | 目标 15 分钟短 access |
| refresh hash/rotation | 每次使用轮换并检测 replay；无时间/inactivity 自动过期 |
| revoked reason | logout、device removal、replay、Owner root rotation 等 |

`LEZI_BOOTSTRAP_SECRET` 不属于 DeviceSession。它只验证 Owner create/login/takeover 或 family delete，
然后签发/撤销设备会话。

目标权限：

| | 管理员 | 成员 |
|--|--------|------|
| 编辑/删记录 | ✓（全部） | ✓（`created_by_membership_id == self`，含本人其它设备创建） |
| 审批/添加/改名/删除成员 | ✓ | ×（本人改名只能申请） |
| 查看设备 | 全部 | 仅本人 membership |
| 撤销设备 | 全部 | 仅退出当前设备 |
| 停止共享 | 删除家庭 | 退出当前设备或硬删除自己的 membership |

Record、CarePlan 与其附件使用 membership-self-or-owner 管理规则；同 membership 多设备均视为
self。新登录设备读取完整家庭历史。不做保育只读角色、不做字段级 ACL。

### 3.4 Baby

| 字段 | 说明 |
|------|------|
| `id` | |
| `family_id` | |
| `nickname` | |
| `sex` | 可选枚举 |
| `birthday` | 日龄基准 |
| `theme_color` | 本机展示；是否同步主题 **默认不同步**（见设置） |
| `sort_order` | 本机展示顺序；不同步 |
| `family_authority` | 仅本机派生标记：该行来自家庭服务器权威集合；不进 wire，不由普通成员编辑 |
| `client_uuid` | |
| `updated_at` / `deleted_at` | 软删；Baby 无独立 `family_published_updated_at` 列。avatar-only 等合成根包在 NAS 抬高 root 修订后，本机以 CAS 前进 `updated_at` 到同一 `rootUpdatedAt` 并清除 dirty 作为等价水印，使下一次档案编辑严格大于已发布根 |
| `avatar_media_uuid` / `avatar_path` | 可移植头像指针与本机路径；**软删 Baby 时同一事务清零**，不得把 live 指针带进墓碑根包 |

家庭会话中只有 owner 可创建、修改、删除或上传 Baby；member 只 pull/apply 家庭权威宝宝，仍可切换当前宝宝并修改本机 `theme_color` / `sort_order`。member 加入前的本机孤宝宝不上传：若 pull 完整轮次后恰有一个家庭权威宝宝，自动把孤宝宝的 Record、CarePlan 与相关媒体再绑定过去；若有多个权威宝宝，只允许用户显式选择「孤宝宝 → 权威宝宝」，不得按昵称猜测；若没有权威宝宝则保留本机数据并展示等待管理员的空态。再绑定完成前，孤宝宝下的事实、计划与媒体是明确本机保留内容，不计家庭 pending、也不携带无效宝宝引用上行；合并事务生成新修订并重新进入待对账。

Owner **软删家庭权威宝宝**时，同一 Room 事务写 Baby tombstone、清 `avatar_media_uuid`/`avatar_path`，并以不倒退的时间 tombstone 该宝宝下**全部** active `kind=avatar` MediaAsset（含 legacy 多 active 行，不只是指针指向的一行）。提交后才走引用感知文件回收；事务失败时 Baby、头像指针、MediaAsset 与文件全部保持原状。临时发布计划/atomic baby 包发布 deleted Baby root（`avatar_media_uuid = null`）与对应 media tombstone，不得再把 live avatar 带进墓碑包。对端 pull/apply 得到 deleted Baby + 非活跃 avatar 后，pointer repair 不得复活头像。物理文件只在提交后且无其它 active 引用时删除；失败保留 durable cleanup marker 可重试。删除、同步失败重试、commit-response 丢失和进程恢复均不得复活头像或丢 tombstone。产品仍保持「至少保留一个 active 宝宝」。

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
| `created_by_membership_id` | NAS 认证 principal 在首次接受 Record 时盖章的不可变作者；尚未加入家庭的本机记录可空 |
| `family_published_updated_at?` | 仅本机保存的根发布回执；等于 `updated_at` 表示当前根已发布，小于它表示家庭仍看到上一版本；不进入 wire。独立 log 媒体包会抬高 NAS 根 `updated_at`，成功后须把本机回执与（内容 epoch 未变时的）本地 `updated_at` 对齐到同一 `rootUpdatedAt`，不能只 ack 媒体 |
| `payload_json` | 类型扩展 |
| `schema_version` | 当前固定为 v2 |
| `updated_at` / `deleted_at` | 软删；**0.3.10 起** 家庭权威图上的 record tombstone **永胜**（更高 `updated_at` 的 live 不得清零 `deleted_at`）；误删/落选后需事实时 **新记一条**（新 UUID），无 LWW restore |

**索引**：`(baby_id, timestamp)`、`(client_uuid)`、`(baby_id, type, timestamp)`。

**近邻落选（0.3.10）：** 白名单类型跨 membership、主时间 ≤30 分钟的 live 近邻由家庭服务器在 atomic commit 同事务内裁决，落选写 tombstone；同 membership 豁免。详见 ADR-0018 / CONTEXT。

每个宝宝最多只有一条 `end_timestamp = null` 的开放睡眠。若本地维护或家庭 pull 发现多条，
两条路径必须调用同一个纯决策：按 `timestamp` 升序排列，时间相同时按跨设备稳定的
`client_uuid` 排列并保留最后一条；其余条目依次关闭在下一条的开始时刻。相同/异常开始时刻
使用注入的修复时钟与饱和的一分钟兜底，两者取较晚值，绝不产生负区间或 `Long` 溢出。
规则本身不读数据库和系统时间。本地适配仍在原事务中走本地更新与 dirty 语义；
pull 适配仍保留远端作者，沿用 replica repair 的 revision/dirty 语义，不伪造待发布事实。

家庭 wake 是宝宝级事实，而不是只作用于某个 sleep UUID：pull 到任一已闭合睡眠后，所有开始
时间不晚于该 wake 的开放睡眠都在本机闭合到同一 wake，标记 anomaly 并以更高修订发布；时钟
偏斜导致开始晚于 wake 的开放行保留为唯一 residual，禁止生成负区间。

已加入家庭时，本机新建 Record 立即带当前 session membership；NAS 对 atomic commit
与 atomic bundle 仍从已认证 principal 重新盖章。后续编辑或删除不得改写首次作者；
tombstone 后不得再以 live 恢复同一 `client_uuid`。Android 与 NAS 都要求 current
`record_membership_author` capability，缺失时停止同步，不发送降级 payload。

当前 Room schema 的 `pending_reminder_cleanup` 持久化 `carePlanIds`、
`systemCalendarProjectionsJson`、可空的 `currentBabyId`、哺乳计时 epoch，以及
`familyServerRetained`。`nextFeedAt` 仍是冻结的旧 schema 列（生产始终写空）；
`nextFeedEpoch` 复用为可恢复的 nursing timer epoch JSON（空串表示未捕获计时），
不为 reminder cleanup 单独增加 schema。下次喂养已统一为普通 CarePlan，不再有独立提醒状态。
`systemCalendarProjectionsJson` 是稳定护理计划 UUID 到 provider event ID（可空，表示只可按
UID 查找）的精确映射。清除记录（RecordsOnly）或全部本地数据（AllLocalData）时，领域事务
按 scope 分别写入 pending 行，持久保存护理计划提醒 ID、系统日历投影身份、当前宝宝设置
快照、捕获的 timer JSON 与稳定 session token，以及家庭服务器保留标记。提交后必须依次确认
系统日历副本已删除、**捕获 session 的 FGS 已停止且 ongoing 通知已消失**、scope 对应设置
（含 timer JSON 的 compare-and-remove）已清理、应用内提醒已取消，才可删除 pending 行并向
界面返回成功；权限撤销、provider 失败或 timer stop/DataStore 失败时保留该行，进程重启或
用户重试后继续，不得遗失已删除护理计划的任一提醒身份，也不得遗留可运行的旧计时会话。

当前清除只删除仍与捕获 UUID + event ID 精确相等的系统日历映射、已捕获 CarePlan
对应的应用内提醒，以及仍等于捕获 epoch 的 timer JSON；只对捕获的 session token 停止 FGS。
计时服务在请求启动、尚未 ack RUNNING 时即登记 STARTING token；清空留下 token-scoped stop，
服务稍后物化仍会先自停。不同 token 的新 STARTING/RUNNING 会话不受旧清空影响。
清除提交后新写入的设置、映射、护理计划提醒与**新 session 计时器**必须保留（session epoch
防 ABA）。设备撤销、成员删除、家庭删除与设置页本机清空复用同一 `LocalDataClearCoordinator`，
不得旁路清 Room 后留下计时器。

本机清空还覆盖尚未建立 `MediaAsset` 行的草稿/导入文件：`RecordsOnly` 在最终归属重读后清扫
`files/record-media`，`AllLocalData` 额外清扫 `files/baby_avatars`；其它 app-private 目录不在此
删除域内，仍有 active 本机引用的路径必须保留。领域事实、计划、宝宝、自定义项目与两类媒体
文件写入共享进程级清空 epoch；清空与写入同时竞争时，未取得 epoch 的一侧在任何 Room/文件
修改前失败，不排队形成 `syncMutex ↔ path gate` 反向等待。清空后置 sweep、widget 状态和其它
外部清理任一失败都保留 durable pending marker，重启或重试继续完成后才向界面宣告成功。
长寿命 feature 会话还须携带进入时的 generation；清空完成后旧 Timer/ViewModel 不得重新写回
已捕获 timer JSON 或旧宝宝会话，用户必须从当前 generation 重新进入。

atomic commit 在 `record_authors` 回执中返回本次请求涉及的
canonical Record membership 作者。Android 对同 `updatedAt` 的本地行只合并这一
server-owned metadata；不修改护理内容、照片、删除状态或业务时间，不提高
`updatedAt`，不改变 `syncDirty`，也不生成额外发布候选。响应缺少当前必需字段时整次 apply
失败并保留本地行与检查点，不猜测作者。

### 3.6 typed payload 当前约定

业务代码只通过 `RecordPayloadDocument` / `RecordPayloadCodec` 读取或写入
`payload_json`。每个 `RecordType` 只接受匹配的 `RecordPayload`：

- 只接受 schema v2；未知字段、其它 schema 版本和与 `RecordType` 不匹配的 payload
  不得进入新写入或 wire。
- 当前数据库中若因损坏出现无法解码的 payload，读取为不可编辑错误并保留原始字节，
  不允许静默清空、覆盖或纳入汇总；这属于故障保护，不是旧格式兼容。
- 搜索、时间轴、汇总、导出和 Widget 复用 typed payload 与同一中文摘要模块，
  不各自用正则或手写 JSON 解释业务字段。

| type | JSON 字段 |
|------|-----------|
| `nursing` | `left_min`, `right_min`, `order`, `amount_ml?`, `record_mode=start\|end` |
| `formula` | `amount_ml`, `prepared_ml?`, `duration_min?` |
| `pumped_feed` / `pump_express` | `amount_ml` |
| `pee` | `pee_amount` 1=小 · 2=中 · 3=大（默认 2） |
| `poop` | `stool_amount?` 1–4, `stool_consistency?` 1–4, `stool_color?` 0–7 |
| `both_diaper` | `pee_amount` + 便便三字段（同上） |
| `sleep` | `anomaly_flag`, `is_nap?`（起止用 timestamp/end） |
| `temperature` | `celsius` |
| `height` / `weight` / … | `value`, `unit` |
| `medicine` | `name`, `dose?` |
| `diary` | `body`；照片统一使用 Record 关联的 MediaAsset，最多 3 张 |
| `cough` / `rash` / `vomit` / `injury` | `severity` 1–3, `description?` |
| `hospital` | `reason`, `advice?` |
| `baby_food` / `snack` / `drink` | `content`, `amount?` |
| `vaccine` | `name`, `batch?` |
| `custom` | `title`, `detail?`, `custom_item_id`, `icon_slot?`；标题/图标为创建时快照 |

Record 的本机 Room payload 使用正数 `custom_item_id`。家庭 wire 不发送这个设备自增 id，
而是在实体根携带 `custom_item_client_uuid`。同家庭且未删除的 CustomItemDef 才能用于
新建 `custom` Record；已 tombstone 的定义只保留引用存在性，允许既有历史 Record
编辑/删除，以及由既有计划明确关联的履行事实继续发布，但不能用于任意新事实。
接收端以该 UUID 解析自己的本机 id 后再写入 Room。其它类型必须省略或置空。
服务端只接受上表当前类型，`memo`、`other` 与未知字符串均返回 `422`。

**不做**：挤奶库存余额表。

### 3.7 MediaAsset

| 字段 | 说明 |
|------|------|
| `id` | |
| `client_uuid` | 跨设备同步键，UNIQUE |
| `kind` | `log`（Record/CarePlan 由归属列区分）\| `avatar` |
| `record_id` / `plan_id` / `baby_id` | 记录图关联 Record；计划图关联 CarePlan；头像关联 Baby，三选一 |
| `local_uri` | 本机私有文件路径，不进入 wire payload |
| `remote_uri` | 当前家庭服务器已上传标记；更换服务器时清除 |
| `mime` / `width` / `height` | |
| `byte_size` | |
| `created_at` / `updated_at` / `deleted_at` | LWW 与 tombstone |
| `sync_dirty` | 当前修订尚未取得家庭同步终态；只表示待对账，不等同于必然发布 |

当前仅支持图片；视频不做。
NAS 持久化和 pull 的 current MediaAsset payload 固定包含三个归属 UUID、`mime`、
`width`、`height` 与 `byte_size`；nullable 字段省略时规范为显式 `null`。live media 的
`byte_size` 必须为正数，tombstone 规范为 `0` 且不要求字节。

履行 Composer 中的计划照片是按原顺序带入的 **borrowed refs**，不是草稿拥有的临时文件：
移除预填项、取消、返回、写入失败或进程重建后的放弃均不得删除计划原图。当前 Composer
新导入的私有文件单独记为 draft-owned；写入失败时保留供重试，移除或明确放弃时才清理。
履行事务成功后，原 CarePlan 的媒体集合保持不变，事实 Record 只建立确认时仍在草稿内的
计划照片和新导入照片媒体行；已被事实引用的新导入文件不进入物理清理候选。

**计时履行（`completeNursing(carePlanId=…)`）** 不依赖 Composer 草稿或 UI 照片快照：
在与 Record 写入、计划 complete、履行候选同一事务内，读取该护理计划**当前** active
计划照片（按媒体行 id 序，最多三张），为新 Record 创建独立 `client_uuid` 的 MediaAsset
行并共享同一 `local_uri`。原 CarePlan 的媒体行、顺序与物理文件保持 active；删除任一方
媒体行不得在另一路径仍 active 时回收文件。`completionClientUuid` 幂等 replay 返回同一
Record 且不重复 clone；无照片计划与未绑定计划的计时完成不产生媒体行。生成的 Record +
0–3 媒体行继续走完整 atomic bundle 出站。

**Composer→Timer 草稿 handoff（所有权转移，非隐式放弃）**：从 Composer 开始计时时，
可转移字段与照片经显式 `TimerHandoffSeed`（baby、carePlan、note、amount、有序照片及
每张 borrowed/Composer-owned 所有权）交给 Timer，并随 TimerState 持久化/恢复；Timer
确认接管后 Composer 才关闭，且不得 cleanup 已转移的 Composer-owned 导入。导航失败或
接管冲突时原草稿与照片保持可编辑。完成时把 seed 照片路径与上述 Ticket 08 直播 plan
media 去重合并（seed 序优先，最多 3 张）经 `photoLocalPaths` 写入 Record；Timer 显式
丢弃只回收 Composer-owned 文件，borrowed 计划路径永不由草稿/Timer 丢弃路径物理删除。
已输入但无法映射到计时器的 manual 时长/顺序/时间须在离开 Composer 前明确确认；
seed+plan 合并超 3 张时在离开前可操作提示删减。

MediaAsset 行所有权与物理文件所有权是两层契约：每条 `log` 行仍只归属一个 Record 或
CarePlan，但多个 active 行可以用相同 `local_uri` 共享同一份本机字节。编辑、整实体删除、
Record→CarePlan 转换、**Owner 删除宝宝时的全部 active avatar** 和同步 tombstone 只把精确
媒体行标记删除；物理文件须在同一路径已无任何 `deleted_at IS NULL` 的媒体行后才可回收，
因此待上传的 active dirty 行也会保护文件。回收成功（或文件已经缺失）后只清空 tombstone
行的本机 `local_uri`，不删除 MediaAsset tombstone。删除失败或中断时保留
该路径作为重启重试 marker；live 行若有 `remote_uri` 但本机路径为空，继续按既有下载恢复
规则补齐。

本机媒体 GC 为**两阶段**，且慢文件删除不得占用 Room 写租约：

1. **DB claim（短写事务）**：在路径级互斥下重新读取精确 tombstone；若同 path 仍有
   active 引用，只清该 tombstone 的 `local_uri` marker；若无 active 引用，冻结
   `client_uuid` + path + `updated_at`（revision）+ `deleted_at` 的 cleanup claim。
2. **文件阶段（路径互斥、事务外）**：与 attach/import/复活共享 `MediaLocalPathGate`。
   全局锁序固定为 **path gate → sleepMutationMutex（涉及 open-sleep 写时）→ Room**，
   不得反序。再校验 claim 与 active 引用后，由 reclaim 路径在事务外执行
   `mediaFiles.delete`（共享 store 本身不做 depth==0 硬护栏；本地 replica clear 仍可在
   写租约内删文件）。成功后仅当 claim 仍与 tombstone 匹配时清 marker。IO/权限失败或
   进程死亡保留非空 `local_uri` 作为可重试证据；文件已缺失视为幂等成功。同 path 被新
   active 引用或 revision ABA 时不得删其文件，也不得清不匹配的 marker。
   远程 materialization 仍主要通过 sync 引擎 `syncMutex` 串行 reclaim，而非
   `MediaLocalPathGate`；domain attach 与 reference-aware reclaim 才共享 path gate。

#### 原子包 prepare / commit 与 domain revision 的 CAS 边界

推送 Record/CarePlan/Baby 原子媒体包时，客户端对每条 live 媒体先做本机探测
（prepare：`mime` / `width` / `height` / `byte_size`），再上传字节并 `commit` 根包。
**探测结果与 commit 回执不得用 prepare 时的整行快照回写 Room。**

| 写回 | 允许字段 | CAS 匹配键（全部相等才写入） | 不匹配时 |
|------|----------|------------------------------|----------|
| prepare 探测元数据 | `mime`、`width`、`height`、`byte_size` | `client_uuid` + 被发布修订的 `updated_at` + 源 `local_uri` + `deleted_at`（含双方均为 null） | 保持当前行；wire 仍可携带本次包的探测值 |
| root commit receipt | 仅 `remote_uri` | 同上 | 不写 receipt；当前行的 tombstone / 复活 / 新路径 / 更高 `updated_at` 全部保留 |
| `markSynced` | `sync_dirty = 0` | `client_uuid` + 被发布修订的 `updated_at` | 不清新修订的 dirty |
| 临时 plan 消费 | 只移除本周期内存候选 | 不写持久队列；下一周期从 Room 重新生成 | 新修订仍由 `sync_dirty`/回执进入新 plan |

长上传期间同一 MediaAsset 被 tombstone、复活、替换 `local_uri` 或产生更高 `updated_at`
时，旧 prepare 快照不得覆盖任何新字段。commit 失败、取消或上传失败不写 receipt，并关闭
已打开的媒体 source。未发生并发写时，探测元数据、receipt 与 `markSynced`
仍一次收敛。

### 3.8 SettingsLocal（**不同步**）

| 字段 | 说明 |
|------|------|
| `item_order_json` | 图标顺序 |
| `category_order_json` | 记录类别区块顺序 |
| `hidden_items` | 隐藏类型 |
| `quick_record_slots` | 四个常用记录槽位；空槽允许 |
| `device_layout_snapshot_version` | 当前完整本机布局快照版本；未知未来版本只读，不由旧客户端覆盖 |
| `action_buttons` | 计时/搜索/日历等显隐 |
| `timer_enabled` | |
| `record_at_start_or_end` | 母乳记录时刻 |
| `nursing_interval_min` | 提醒间隔 |
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

四槽、隐藏集合、类内项目序和类别序以一个版本化 `DeviceLayoutSnapshot` 作为权威
持久化单元；旧字段只在同一次 DataStore 事务内保留完整镜像以兼容降级。快照固定四槽、
非空 key 唯一且允许任意数量空槽，不自动补位。每次布局意图串行写入一个完整快照；
失败或取消保留上一份耐久快照并允许重试，未知未来版本不得被当前版本静默清空或回写。
该快照只存在当前设备，不进入家庭同步 wire 或 NAS 数据。

布局撤销是进程内、单层且带 token 的短时写入协议，不是持久化历史。只有清空常用槽和
移入本机已删除的完整 `after` 快照写入成功后，才临时保留对应完整 `before` 快照；任何
后续布局 intent、超时或退出都会使旧 token 失效。撤销仍通过同一 FIFO writer 原子写入
完整 `before`，写失败继续显示当前 `after` 并允许同 token 重试，只有写成功后 UI 才恢复。
进程重建只从最后耐久的 `DeviceLayoutSnapshot` 恢复，不序列化撤销机会，也不形成多级历史。

主题色存在 Baby 上，但 **同步策略默认：主题与排序属本机**（与参考产品一致）。若未来共享主题，再单开开关。

### 3.9 成员申请与单次登录授权

旧 `ShareInvite` 短码模型已退役，不存在于当前 Android、NAS schema 或 wire。当前成员申请只在
本机暂存无权限 request ID、pending secret 与过期时间；管理员签发的成员登录授权十分钟、
单次使用，服务端只存哈希。两者都不得进入家庭业务同步、系统备份或日志。

成员申请在 `pending` 与 `approved` 且尚未领取时都属于开放申请。批准不会提前创建新
membership；`approved` 申请保留家庭称呼并继续对 Owner 可见，直至申请设备领取、Owner
撤销批准或 24 小时到期。撤销批准与到期都会释放该称呼保留。

### 3.10 CustomItemDef

每个家庭最多 10 条未删除定义：`id`, `family_id`, `name`, `icon_slot` (0–7), `client_uuid`,
`created_by_membership_id`, `updated_at`, `deleted_at`，本机 `syncDirty`。
图标固定模板，不支持自定义图标资源；排序、显隐和常用槽位属于 SettingsLocal，
不进入共享定义。家庭同步实体类型为 `custom_item`（空媒体 atomic bundle），服务端在首次
写入时从认证 membership 盖章创建者，普通成员仅可改自己的定义，管理员可改全部，
tombstone 不可复活。删除目录项不级联删除或改写已存在的 `custom` 记录，也不会重新进入
可选择目录；其 UUID 继续作为同家庭历史记录/计划的引用完整性证据。
Room 对 `client_uuid` 建唯一索引；NAS commit 与 Android full-page apply 都先计算包含同包
tombstone/LWW 后的家庭 live 集，若超过 10 条则整包失败且不暴露第 11 条。设备布局只保存
`custom:<client_uuid>`，Room v24→v25 升级把可解析的 legacy `custom:<local_id>` 映射成该
稳定键；无法解析的旧本机 id 留空/移除，绝不在 row id 重用后误绑到另一家庭定义。

### 3.11 CarePlan（本机、NAS wire/ACL 与客户端家庭 apply 已落地）

护理计划与已发生 Record 分离。Room 表 `care_plans` 字段包括：`client_uuid`,
`baby_id`, `type`, `custom_item_id?`, `scheduled_at`, `scheduled_zone_id`,
`note`, `payload_json`, `schema_version`, `status`,
`created_by_membership_id`, `fulfilled_record_client_uuid?`, `fulfilled_at?`,
`source_record_client_uuid?`, `updated_at`, `deleted_at`, `sync_dirty`,
`family_published_updated_at?`。根回执只在 atomic commit 成功或 pull 到已提交根后写入，
不进入家庭 wire，也不由媒体 `remote_uri` 推断。独立计划照片包抬高 NAS 根修订时，
回执与（内容 epoch 未变时的）本地 `updated_at` 必须对齐到同一 `rootUpdatedAt`。此外保留
`system_calendar_projection_enabled`, `system_calendar_event_id?`,
`system_calendar_reminder_ready` 与 `system_calendar_projection_pending` 等当前设备
副作用状态；这些字段不进入家庭 wire。
远端 apply 保留本机投影选择与 event ID，但共享的时间、时区、标题内容或生命周期
变化时会使前一 reminder generation 失效，并在已有 provider 身份时留下待收敛标记。

NAS 原子包根类型 `care_plan` 的 wire payload 为：
`baby_client_uuid`, `type`, `custom_item_client_uuid?`, `scheduled_at`,
`scheduled_zone_id`, `note?`, `payload_json`（object）, `schema_version`（必填且精确为 `2`）,
`status`（pending|missed|completed|skipped）,
`created_by_membership_id`（服务端盖章）, `fulfilled_record_client_uuid?`,
`fulfilled_at?`。计划媒体为 bundle 内 `media` 且 `care_plan_client_uuid` 指向根。

**Completed ↔ fulfillment pair 双向不变量（model/API fail-closed）：**

| status | `fulfilled_record_client_uuid` + `fulfilled_at` |
|--------|--------------------------------------------------|
| `completed` | 两字段必须**同时非空**（完整 pair）；任一为空/缺失 → `422` |
| `pending` / `missed` / `skipped` | 两字段必须**同时为空**；携带 pair 或残缺 pair → `422` |

两字段本身也是「同时为空或同时非空」；禁止残缺绑定。`status=completed` 的首次 atomic
root 写入必须在同一事务携带完整 pair，不能先 completed 再补绑。若 bound Record 已在同
家庭存在，须与计划同宝宝（否则引用冲突）；传输仍允许 completed CarePlan → Record 的
前向提交，但 NAS 只把它保留为不可 pull 的延后履行。启动校验须验证已提交 bundle 的完整
manifest/hash；成员注销后由服务端匿名化的提交证据继续有效。关联 Record 完整到达后须复验 ACL/LWW、
不可变 pair、历史 CustomItem 许可，以及计划媒体 publication/manifest/大小/摘要/文件，才在
同一家庭事务公开完整关系。NAS **一旦首次持久化** completed 完整 pair，后续版本必须精确保留这两个值；
清空、改绑其它 Record 或改变确认时间：残缺/清空在 model 边界 `422`，合法完整但改绑/改时
的 rewrite 返回 `409`，creator 与 owner 遵循相同规则。精确 replay（同 pair）幂等；stage
与 commit 间的并发 rebind 仍由 commit 时冻结检查拦截。其它计划字段仍可按原 ACL/LWW 更新。
这个不可变 pair 是服务端证明 tombstone 自定义定义只产生一次显式履行事实的 current-wire
门闩；设备根据 FulfillmentCandidate 做的赢家重链仍是本机派生，不把 rebind 重新发布为
CarePlan LWW。
`type` 使用与 Record 相同的当前类型集合；`type=custom` 时
`custom_item_client_uuid` 新建时必须引用同家庭、未删除的 CustomItemDef；既有计划可继续
引用同家庭 tombstone 定义并被编辑、删除或显式履行，其它类型必须省略或置空。
显式履行在本机事务中仍同时生成事实、完成计划和候选；家庭发布顺序固定为 completed
CarePlan → 关联 Record（含 0–3 张照片）→ FulfillmentCandidate，使 NAS 能以已持久化计划
证明 tombstone 引用来自既有计划。接收端在事实到达前不得把 completed plan 暴露为完整结果。

当前状态为 `pending`, `missed`, `completed`, `skipped`，且只支持单次计划。
`missed` 可由当前绝对时刻超过计划时刻且仍未完成/跳过派生。本机履行在同一事务
中写入关联 Record 并将计划标为 `completed`。多候选时各设备用盖章证据稳定裁决
唯一权威记录，并在不置脏、不回写 NAS immutable pair 的前提下本地重链
`fulfilled_record_client_uuid`（不依赖计划 LWW 到达序）。

下次喂养 marker 的计划写入回调不是持久化回执。若 callback 丢失、返回失败或 UI 从
`Scheduling`/持久化查询中恢复，Composer 与 Nursing Timer 必须经同一 domain seam 读取当前
宝宝所有未删除且状态为 `pending`/`missed` 的 marker（包括已经到点的开放计划）。查询与
本机 marker 写事务串行：Found 返回持久化 `client_uuid` 与 `scheduled_at` 并收敛为已安排；
只有 Absent 才开放同稳定身份重试或“不安排”；查询错误保持歧义并只能重试查询。不可管理的
家庭 winner 仍属于 Found，读取真相不得借 creator ACL 隐藏它；`completed`、`skipped` 与
tombstone marker 不属于开放计划。

不同设备离线完成旧 marker 后可能用分叉 generation seed 建出不同 UUID。家庭 pull/apply
按 `(updated_at, client_uuid)` 升序保留唯一开放 winner；本 principal 可管理的 loser 写更高
修订 tombstone 供家庭收敛，不可管理的 foreign loser 只在本机派生为 clean `skipped`，立即
取消闹钟/系统日历且不伪造 ACL 写入。loser 创建者或 Owner 上线后发布 durable tombstone。
同 UUID 的并发 create 仍沿用 NAS canonical no-op，不进入本规则。

**Next-feed note 协议 marker（内部版本化，非用户备注格式）：** CarePlan `note` 以
`[[lezi:next-feed:v1]]` 为前缀时表示家庭共享的下次喂养意图（`startsWith` 识别；可见备注
在 marker 后可选，strip 规则与客户端一致）。该前缀是内部 wire/data-model 协议，不是用户
可编辑的备注语法；履行生成的 Record 不得携带该前缀。Kotlin 经 `core.model` 的
`NEXT_FEED_PLAN_MARKER` + `isNextFeedPlanNote` / `visibleNextFeedPlanNote`（domain 与
sync 共用），Rust crate 内经 `NEXT_FEED_PLAN_MARKER` + `is_next_feed_plan_note`；
跨语言 build/test 合同见版本化 fixture
[`config/next-feed-plan-marker.v1.json`](../../config/next-feed-plan-marker.v1.json)
（marker 字面量与 marker-only / marker+可见备注 / 相似非法 prefix 样例）。客户端 compose
（`encodeNextFeedPlanNote`）是 Kotlin 侧规则，不进入共享 fixture 语义。运行时不从磁盘
加载该 fixture。

### 3.11.1 FulfillmentCandidate（NAS 契约 + 本机）

`entity_type = fulfillment_candidate`：不可拼接改写的审计证据。业务字段
`care_plan_client_uuid`、`record_client_uuid`、`actual_timestamp?` 与服务端首次接受时
盖章的 `submitter_membership_id`、`submitter_role`、`confirmed_at` 共同构成证据元组；
**首次写入后整元组冻结**，后续同一 candidate UUID 只允许精确幂等 replay（任意活动
成员或 Owner），不得改写任一业务字段或提交者戳，也不推进 revision。Owner 亦不能
改写历史证据。stage 与 commit 均重新执行冻结与 ACL，角色变化或并发 staged package
不能绕过。跨家庭/缺失 plan/record 引用以冲突拒绝且不泄露其它家庭是否存在某 UUID；
关联 plan/record 必须同家庭、同宝宝。tombstone 可软删整行但不得改证据字段，禁止
复活。权威裁决键（客户端纯函数，与到达序无关）：
1) 提交者是否管理员（`owner`/`admin`）；2) 较早的不可编辑 `confirmed_at`；
3) 候选 `client_uuid` 升序。NAS 到达时间、设备 `updated_at` 与后续角色变化不参与
比较。候选上的 `actual_timestamp` 是首次提交时的证据快照（冻结），不同于护理记录上
仍可编辑的实际发生时间。
请求省略 `actual_timestamp` 时 NAS 规范为显式 `null`，保证 current pull 与 Android
exact-key parser 使用同一 canonical shape。

Android 本机表 `fulfillment_candidates` 在履行事务中写入稳定 `clientUuid` 与
不可变本地 `confirmedAt`，并与 Record 原子包 + completed CarePlan 原子包一起出站
（空媒体 atomic bundle 根）；接收端 completed 计划须已有关联 Record，候选须 plan+record
均已落地后才应用。全量候选就绪后裁决：赢家 `adoptionStatus=adopted` 并写入计划
关联；落选 `conflict_not_adopted`，**不**软删除 Record/照片；落选记录不进入普通
时间轴、汇总、搜索或普通导出。管理员可在本机审计落选并「转为独立记录」：创建**新的**
`clientUuid` 与新 media 所有权的普通 Record，**不**翻转落选 `adoptionStatus`、**不**
重链 `CarePlan.fulfilled_record_client_uuid`。幂等靠本机
`fulfillment_candidates.convertedRecordClientUuid` 指针（不进家庭 wire）；双管理员在
两台设备上各转一次且未共享指针时，产品接受两条独立普通记录。`adoptionStatus` 与
`convertedRecordClientUuid` 均为本机派生字段，不进家庭 wire。

### 3.12 权威裁决与临时发布计划

> 当前合同：ADR-0017 已实现；0.3.8 保留 ADR-0016 的 Room-first、先 pull、临时计划、
> atomic bundle 与修订 CAS，并以批量权威裁决取代 raw-dirty 直接发布。

发布计划不是 Room 表，也不跨进程保存 payload。每个已加入家庭的同步周期先增量 pull，再把
六类待对账 Room 实体按 Baby+avatar、Record+照片、CarePlan+照片、CustomItem 和
FulfillmentCandidate 原子单元冻结，以 `(client_uuid, updated_at, canonical_hash)` 批量请求
家庭服务器 head-by-UUID 裁决。服务端响应必须在同一 generation/cursor 快照上为每个请求 key
返回 canonical head 或权威 absence，以及 `confirmed`、`publish`、`adopt_remote`、
`remote_absent_rejected` 或 `retry_authority`。客户端把 remote_absent_rejected 按本机内容性质
唯一映射为 `keep_local_only` 或 `discard_technical`，不得由 transport 错误触发丢弃。

只有 `publish` 生成内存 atomic bundle 计划；confirmed 以冻结修订 CAS 清待对账，adopt_remote
原子采用远端家庭字段，keep_local_only 保留用户事实但退出家庭 pending，discard_technical 只
清理无独立业务意义的孤儿媒体/同步残留。generation 变化、响应不完整或 authority proof 失效时
走全量实体快照，不推测 absence。commit 回执、远端采用、重绑和技术清理都必须绑定冻结修订；
并发新编辑进入下一周期。完整静止周期结束时冻结集必须全部终态，浅层 pending 按未终态原子
单元计数。contract 2→3 的旧 outbox identity 仍只用于把相应 Room 行转成待对账，然后删除旧表。

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

不必强行落表；可用查询或按日缓存。**唯一 in-process 入口**是 `CareAggregation`
（`day` / `range` / `week` / `window` / `widget`）；Log、Summary、时间条与 Widget
必须复用，不得各自过滤。

- **日汇总**：睡眠分钟、尿次、便次、配方+母乳+挤出乳 ml、母乳分钟等。  
- **周汇总**：按类型分桶给图表。  
- **睡眠配对**：由 sleep 记录推导区间；`anomaly_flag` 写入或查询时计算。

### 5.1 聚合时钟与「已确认但时间未到」

所有入口共用同一聚合时钟参数 `now`（默认设备当前时刻）：

| 事实形态 | 计入规则 |
|----------|----------|
| **点事实**（配方奶、瓶喂、母乳、尿/便、体温等） | 仅当 `timestamp <= now` 计入数量、奶量、体温与喂养时段桶；`timestamp == now` 计入（含边界）。 |
| **睡眠区间** | 裁剪为 `[start, min(end, now)]`（开放睡眠的 end 取 `now`）；`start > now` 不产生分钟也不计 segment。 |

**写入与展示 vs 累计：**

- 计划履行允许设备时钟 **最多 +5 分钟** skew（`RecordTime.FULFILLMENT_ACTUAL_TIME_SKEW_MILLIS`），
  因此库中可以存在 `timestamp` 略大于当前时刻的合法护理记录。
- 这类「已确认但时间尚未来到」的点事实**可以出现在时间轴/明细**（UI 展示库中行），
  但 **暂不计入** 日/周/窗/Widget 累计；时钟到达该 `timestamp` 后，下一次聚合自然计入，
  **无需改库**。
- 不取消履行侧的 5 分钟 skew；只在聚合侧按时钟延迟可见累计。

---

## 6. SyncPort（同步接口）

### 6.1 状态

```kotlin
enum class SyncStatus {
  OfflineOnly,         // 用户尚未连接/登录家庭，或选择保持离线
  PendingApproval,     // 普通成员等待管理员确认
  TrustBlocked,        // TLS/SPKI 安全信息不一致，禁止发送凭证
  ReauthRequired,      // 普通凭证丢失；保留本地数据并重新申请
  Idle,
  Syncing,
  RetryableError,
}
```

本机可信连接与会话投影（**不同步到家庭业务域**）：

| 字段 | 说明 |
|------|------|
| trusted endpoint | normalized HTTPS origin + system PKI 或 pinned SPKI |
| setup state | 未登录时可记住已通过 probe 的 endpoint；不得上传 |
| device session | device/membership/family 本机投影 + 安全存储 refresh；access 只在内存 |
| pending request | 无权限 request ID 与 expiry；不包含家庭数据 |
| pull checkpoint | cursor 与 familyName 缓存；成功后原子更新 |
| member directory | membership ID、称呼、role、本人标记；不含设备/申请；身份退出时清除 |
| availability | 当前结果、最近健康时间与租约/退避时点；不进入家庭 wire |
| restore credential | 空服恢复批次的限时 token；仅安全凭证存储，不含根密码 |

`familyName` 是 NAS 权威共享家庭名的本机会话缓存：create/login/claim/本机 rename 会立即
写入；之后每次允许的前台/下拉 pull 都可刷新，即使该页没有实体。NAS 显式
返回 `null` 时清空缓存并走产品兜底；缺少当前必需字段时 pull 失败并保留缓存。更新检查点只改
`cursor` 和 presence-aware `familyName`，不得覆盖并发变化的家庭身份或 trusted endpoint。

### 6.2 接口（契约级，语言示意）

以 `sync/.../SyncPort.kt` 为准：

```text
interface SyncPort {
  fun status(): Flow<SyncStatus>
  fun session(): Flow<SyncSession>
  fun availability(): Flow<FamilyServerAvailability>
  fun familyMemberDirectory(): Flow<List<FamilyMemberView>>

  /** 前台非阻塞触发；未认证时 no-op */
  fun requestSync(trigger: SyncTrigger)
  fun notifyLocalChanges()
  suspend fun probeAvailability(reason: AvailabilityProbeReason): Result<FamilyServerAvailability>

  suspend fun probeEndpoint(endpointDraft: String): SetupProbeResult
  suspend fun trustCertificate(candidate: CertificateTrustCandidate): SetupProbeResult
  suspend fun rememberEndpoint(endpoint: TrustedEndpointProfile): Result<Unit>
  /** 持久化 endpoint 原点；信任由 setup probe 另建 */
  suspend fun saveEndpointConfig(config: FamilyEndpointConfig): Result<Unit>
  suspend fun createFamily(displayName: String, deviceName: String, bootstrapSecret: String, familyName: String?): Result<CreateFamilyResult>
  suspend fun ownerLogin(deviceName: String, rootPassword: String, takeover: Boolean): Result<OwnerLoginResult>
  suspend fun requestMemberLogin(displayName: String, deviceName: String): Result<PendingMemberLogin>
  suspend fun checkMemberLogin(): Result<MemberLoginCheckResult>
  /** 返回严格 v1 payload 与可选的隔离 LAN 邀请安装页地址 */
  suspend fun createMemberLoginQrCode(membershipId: String): Result<MemberLoginQrCode>
  suspend fun claimMemberLoginQr(payload: MemberLoginQrPayload, deviceName: String): Result<SyncSession>
  suspend fun renameFamily(familyName: String): Result<Unit>
  suspend fun deleteFamily(familyName: String, rootPassword: String): Result<Unit>
  suspend fun updateMyDisplayName(displayName: String): Result<Unit>
  /** 前台/下拉/本机写触发的统一同步；内部 SyncBackend pull/push 不经此面暴露 familyId */
  suspend fun sync(trigger: SyncTrigger): Result<Unit>

  /** 当前设备会话所在家庭的 active 成员安全视图（含称呼与 role） */
  suspend fun listFamilyMembers(): Result<List<FamilyMemberView>>
  suspend fun reconnectCandidate(candidate: TrustedEndpointProfile, credentials: ReconnectCredentials): Result<ReconnectResult>
  suspend fun startDisasterRestore(...): Result<DisasterRestoreBatch>
  suspend fun uploadDisasterRestore(...): Result<DisasterRestoreStatus>
  suspend fun commitDisasterRestore(...): Result<ReconnectResult>
  suspend fun cancelDisasterRestore(batchId: String): Result<Unit>
  /** 退出当前设备的家庭会话（无 familyId；始终针对当前会话） */
  suspend fun leave(): Result<Unit>
  /** 仅 owner：按 membership_id 移除另一 active member */
  suspend fun removeMember(membershipId: String): Result<Unit>

  /**
   * 在同一 sync barrier 下清除选定的本地域与副本状态。
   * LocalDataClearScope.AllLocalData 还会移除头像媒体；发布 plan 不跨进程持久化。
   */
  suspend fun clearLocalData(scope: LocalDataClearScope, workflow: LocalClearWorkflow): Result<Unit>

  /** 可信家庭服务器 app-update：检查、强制/可选 surface、安装与 staging 清理 */
  suspend fun checkAppUpdate(): Result<AppUpdateCheckResult>
  fun availableOptionalAppUpdate(): Flow<AppUpdateMetadata?>
  fun availableForcedAppUpdate(): Flow<ForcedAppUpdateState?>
  suspend fun installAvailableAppUpdate(metadata: AppUpdateMetadata): Result<AppUpdateInstallResult>
  suspend fun cleanupAppUpdateStaging(): Result<Unit>
}
```

`listFamilyMembers()` 是显式远端刷新并在成功后替换最小目录；护理读取只订阅
`familyMemberDirectory()`。灾难恢复导出 current active 护理图，不导出旧身份、凭证、设置、
墓碑或游标；服务器 commit 统一用新 Owner membership 给历史作者盖章。

### 6.3 未配置实现

- 无会话时状态保持 `Disabled`，前台与本地写触发为安全 no-op。
- 建家、管理员登录或成员申请前必须先确认可信 endpoint；失败返回中文产品文案。
- 只有有效设备会话才会生成并上传临时发布计划；endpoint 变化时必须重新建立 trust 并普通登录，
  不向新地址发送旧 credential。

### 6.4 当前规则（摘要）

> **transport trust、身份、前台策略、部署与 API 的权威说明见 [`sync-trusted-endpoint.md`](./sync-trusted-endpoint.md)。** 本节仅保留数据契约摘要。

| 规则 | 说明 |
|------|------|
| 部署 | 家庭 NAS 中心化（Docker `lezi-sync`）；**非** P2P 主路径 |
| 门闩 | **硬家庭局域网**：Wi‑Fi + NAS health；蜂窝不同步 |
| 触发 | **仅前台**：回前台、下拉、前台写成功后 push；**无**后台轮询、**无**推送拉同步 |
| 同步域（现行） | **Baby + Record + CustomItemDef + CarePlan + FulfillmentCandidate + Record/计划 MediaAsset（含原子照片包）** |
| 写权限 | Baby 全部家庭档案字段与头像仅 owner；member 只 pull Baby，日志媒体家庭内可同步 |
| 已落地扩展 | CustomItemDef、CarePlan、计划 MediaAsset、Record/CarePlan 原子照片包与履行候选 |
| 继续不同步 | 系统日历 ID/权限/披露级别、提醒偏好、快捷槽位与布局顺序 |
| 不同步 | SettingsLocal、Baby `theme_color`/`sort_order`/`family_authority`、护理计划提醒与系统日历状态、Widget 配置、本机路径 |
| 共享粒度 | **全量**（同步域内）；不做字段白名单 |
| 冲突 | 同 `client_uuid` 幂等；否则 `updated_at` LWW；删除 tombstone |
| 跨机引用 | Record 使用 `baby_client_uuid`，不用对端本地自增 id |
| 通知 | **不**对成员新记录推送 |
| 验收 | 双方在家且打开 App 时回前台/下拉一致；**不**承诺息屏 60s |
| 安全 | 用户确认的 HTTPS endpoint + 每设备轮换 access/refresh session；加入前明示全量共享 |
| 持久化 | NAS 单数据根：`DATA_DIR/lezi.db` + `DATA_DIR/media/` |

当前 Room schema 强制 `MediaAsset` 只有一个归属：`log` 在 `record_id` 与 `plan_id`
中恰选一个，`avatar` 只使用 `baby_id`。

Record/CarePlan 的 `family_published_updated_at` 是独立的设备本机根回执。仅当它与根
`updated_at` 相等时才表示当前版本已发布；较小正值表示家庭仍看到上一完整版本，缺失、
零值或未来值均按从未发布 fail closed。媒体 `remote_uri` 只说明某个媒体上传步骤已有
回执，不能证明根已 commit。跨家庭或失效重建同步凭据时须清空根回执。独立 log 媒体包与
avatar-only Baby 包仍发布完整 atomic root 并可能抬高 root 修订：成功后 Record/CarePlan
须 CAS 写入精确 `rootUpdatedAt` 回执并在内容未并发编辑时对齐本地 `updated_at`；Baby
以 CAS 前进的 `updated_at` 作为等价水印。较旧回执不得倒退较新水印。

记录页通过一个不可变的 timeline window snapshot 消费这些状态。每次 Room invalidation
固定执行 1 次根记录读取、1 次计划读取和 1 次活跃日志媒体读取；根与媒体读取位于同一
Room 事务，查询数不随行数或每行 0–3 张照片增长。snapshot 同时携带一个家庭成员/角色
快照，并为根发布状态、媒体本机齐备状态、作者称呼和行级编辑/删除/履行/跳过能力标记
同一 revision。UI 只消费该 revision，不把新根与旧媒体或旧权限拼接；成员/家庭、宝宝、
窗口或 refresh 改变时取消旧装配，旧结果不得覆盖新结果。该批量投影只复用既有 ACL，
不改变任何实体的写权限，也不改变 0–3 张照片原子发布门闩。

### 6.5 本地备份（可选，不依赖 SyncPort）

当前产品可提供「导出数据库/JSON 到文件」便于换机；与家庭实时同步分开。

---

## 7. 删除语义

| 操作 | 行为 |
|------|------|
| 删一条记录 | `deleted_at` 软删并进入待对账；权威裁决后发布 tombstone、确认远端已删或清理冗余本机 tombstone |
| 成员退出 | membership 标记离开并吊销其全部 credentials；**NAS 业务数据保留** |
| 清除本机记录（RecordsOnly） | 清 Record/CarePlan/履行候选/日志媒体与对应提醒、系统日历投影；**停止并清除捕获的本机 nursing timer session/JSON**（session epoch 保护）；保留宝宝档案、自定义项目与家庭会话 |
| 清除本机全部 / 设备撤销（AllLocalData） | 多重确认或撤设备路径清空本地库与会话投影；**同一 coordinator 停止并清除捕获的 nursing timer**；**默认仅本地** |
| 成员删除 / 家庭删除（本机侧） | 身份或家庭终止后走同一本机清空协调器收口，不得留下可运行计时器或旧家庭 timer JSON |
| 管理员删除家庭数据 | 多重确认后清空 NAS entities + `DATA_DIR/media/`（见 [`sync-trusted-endpoint.md`](./sync-trusted-endpoint.md)） |

---

## 8. 当前数据层

Android 本地数据永久基线契约 v1（0.3.0 / versionCode 6）的 Room schema 为 v24；契约
v2（0.3.5 / versionCode 12）为 Room v25，并通过 `CustomItemClientUuidIndexUpgradeStep`
相邻升级；当前 0.3.10 / versionCode 17 继续使用契约 v3（由 0.3.8 / versionCode 15 引入）
与 Room v26，通过
`OutboxRetirementUpgradeStep` 转交旧发布意图并移除 outbox。数据域包含 LocalUser、Family、
Membership、Baby、Record、MediaAsset、SettingsLocal、ShareInvite、CustomItemDef、CarePlan 与 FulfillmentCandidate，
并使用真实 `SyncPort` 和 Record/计划媒体原子包。后续本地数据契约必须通过相邻迁移链保留
Room、设置、家庭凭证与受影响媒体；基线之前的 Room schema 在业务入口前无破坏阻断。
当前数据库在进程重启及 APK 原地替换后必须完整保留业务数据、待对账标记/发布回执、计时与提醒恢复状态。后续裁决模型升级必须把 0.3.6 可能被角色/结构过滤的 dirty 行收敛为发布、采用远端、本机保留或技术清理，不能清库或静默删除用户事实。
