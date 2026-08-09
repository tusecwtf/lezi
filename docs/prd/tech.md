# 乐记 — Android 技术说明

> 主 PRD：[`README.md`](./README.md) · 数据：[`data-model.md`](./data-model.md) · 当前同步合同：[`sync-trusted-endpoint.md`](./sync-trusted-endpoint.md)

---

## 1. 技术栈

| 层 | 选型 | 说明 |
|----|------|------|
| 语言 / UI | Kotlin + Jetpack Compose | 夜喂热区、Canvas 时间条、前台服务计时 |
| 架构 | 多模块 + ViewModel + StateFlow | |
| DB | Room | 唯一真相源；本地数据契约 v1 起以相邻迁移链保护 APK 原地替换 |
| 偏好 | DataStore | SettingsLocal |
| 异步 | Coroutines + Flow | **同步不做** WorkManager 后台轮询（规格：仅前台） |
| DI | Hilt | |
| 导航 | Navigation Compose | |
| 图表 | Canvas 时间条 + Compose 自绘 | 未引入第三方图表库（Vico 等为可选未来项，仓库无依赖） |
| 通知 | NotificationCompat + **非精确**本地闹钟 | 护理计划（含下次喂养计划）；**不要求** `SCHEDULE_EXACT_ALARM`；**不为同步/伴侣新记录推送** |
| 计时 | 前台服务 + 状态持久化 | 关 App 仍跑 |
| Widget | Glance | |
| 同步 | `RealSyncPort` + 单一家庭服务器 | **当前 runtime source**：0.3.13 reconcile/commit、版本/分支、WakeObservation、LocalWrite 无 pull。**0.4.0 目标**：ADR-0022 的 commit-first + ConflictSnapshot v2；`causal_sync_v2` 在 H27 前不得 advertise，本文冻结不表示 runtime/NAS 已切换 |
| NAS 后端 | **Rust + Axum + Tokio + SQLite** | 交付物 `tools/lezi-sync`；单二进制、单卷 `DATA_DIR`（db+media） |
| IAP / 广告 | **不引入** | |
| 测试 | JUnit + 聚合纯函数单测 + 关键 Compose 测试 | |

**构建**

| 项 | 值 |
|----|-----|
| applicationId | `com.lezi.babylog` |
| minSdk | 26 |
| compileSdk | 35 |
| targetSdk | 35 |
| versionName | **当前 tree** 以 `app/build.gradle.kts` + `config/android-release-compatibility.json` 为准（0.3.13 / versionCode **20**）；家庭 NAS 是否已切到该代以 live health 为准 |
| versionCode | 同上；安装分发单调版本；本地兼容范围由 APK Manifest 的数据契约声明 |
| 本地数据契约 | 当前 tree `v4` / Room **v27**（最低可迁移与永久基线仍为 `v1`：0.3.0 / versionCode 6 / Room v24） |
| **0.3.13 发版目标（tree 就绪；生产切割待维护窗）** | versionName `0.3.13`、versionCode **20**、Room **27**、server schema **12**；source wire 以该版本 runtime 与测试为准，0.4.0 target 另见 [`causal-sync-wire.md`](./causal-sync-wire.md)；`app-update.json` minSupported=**20** 且已与签名 release APK 对齐；**不得**在未确认维护窗时对家庭 NAS stop/rm/replace |
| **0.4.0 合同目标（未激活）** | versionName `0.4.0`、versionCode **21**、Room **28**、local-data contract **5**、server schema **13**、floor **21**；H27 才能切换版本/capability，H28–H30 证明迁移与 guarded CD，本票不修改 runtime 版本 |
| 应用名 | 乐记 |

---

## 2. 模块划分

Current Gradle modules（与根 `settings.gradle.kts` 的 `include` **一一对应**）。下列为已
include 的交付模块；**不存在**未 include 的幽灵交付（例如 **`:core:image` 不是** 产品
Gradle module，亦无已交付表述）。

```text
:app
:core:model
:core:common
:core:database
:core:datastore
:core:ui
:designsystem
:domain
:sync                 # SyncPort + NoOpSyncPort（测试桩）+ RealSyncPort 家网实现（保留 deep 公开面于模块根）
:feature:onboarding
:feature:log          # 记录首页、编辑、图标网格
:feature:timer
:feature:family       # 账户 / 共享 UI
:feature:settings
:feature:summary
:feature:growth
:feature:export
:feature:search
:feature:widget
```

目标依赖方向：`app → feature → domain → core`，且 `domain|feature|app → :sync` 为有意
sideways seam（非分层违规；完整边表见 §2.2）。feature 之间无 `project(":feature:…")`
边；共享只经 domain / core / designsystem / `:sync`（按 §2.2 允许的调用方）。

### 2.1 稳定原则（Gradle 图 + 模块内就近归拢）

本节目的是固定 **current** 架构边界与防回潮规则；只描述已交付 tree。

| 原则 | 含义 |
|------|------|
| **Gradle 模块图保持** | 新增能力优先落入现有 module；**不因分包新增 Gradle module**，也不合并现有 feature module |
| **feature 互不依赖** | 跨 feature 协作经 domain / composition root，禁止 feature↔feature 工程依赖 |
| **模块内就近归拢** | 分区只发生在现有 module 的 package/file 内，按调用流或能力归拢；目录名以已落地职责为准 |
| **Deep façade 保留** | 保留 `CareLog`、`SyncPort` / `RealSyncPort`（及 lezi-sync `Store`）的 deep 公开面；**不拆** `SyncPort` / `CareLog` 为浅 capability port 表面 |
| **文档只描述 current** | 本文件只描述已交付 tree 与上表规则；不以本地 tracker 草案路径作为长期产品真相 |
| **`:sync` 为允许的横向依赖** | `app` / `domain` / 若干 feature 可直接 `implementation(project(":sync"))`（§2.2）；这是有意 seam，不是对 `app → feature → domain → core` 的违规 |
| **禁止产品无关 StructureTest** | 不新增以源码字符串/行数/路径布局为合同的 StructureTest；行为测试才是重构合同 |

运行时 `TimerState` 落在 `:feature:timer`；持久化计时 blob / clear-epoch 策略在
`core`（datastore + model）与 domain 清空端口；log ↔ timer 协作经 `core:model` 的
`TimerHandoffSeed` 与 app composition root，**禁止** feature:log ↔ feature:timer 工程依赖；
完成写记录经 domain。

### 2.1.1 已落地 package locality（current tree）

新代码进入下表子包，**不在已分区模块根继续平铺**（根仅留导航壳 / deep façade / DI 入口）。

| 模块 | 根 façade / 壳 | 已落地子包 |
|------|----------------|------------|
| `:feature:log` | `LogScreen` / `LogViewModel` / `LogDialogHost` | `timeline/`、`dock/`、`composer/`、`layout/`、`photo/` |
| `:feature:family` | `FamilyScreen` 导航壳 | `overview/`、`members/`、`wizard/`、`baby/`、`components/` |
| `:feature:onboarding` | 导航壳 | wizard 步态与 QR UI 与 Family 向导逻辑对齐（薄壳 + 步态包） |
| `:feature:settings` | Settings 入口 | `calendar/`、`record/` |
| `:core:database` | `LeziDatabase` / DAO / DI 入口 | `causal/`、`fulfillment/`（事务自持的履行权威派生结算） |
| `:domain` | `CareLog` | `carelog/`、`careplan/`、`family/`、`timeline/`、`catalog/`、`growth/`、`export/`、`localdata/`、`calendar/` |
| `:sync` | `SyncPort` / `RealSyncPort` / `SyncModule` | `engine/`、`backend/`、`session/`、`media/`、`appupdate/`、`qr/`、`clear/` |
| `tools/lezi-sync` | crate 根 + 单一 `Store` 事务面 | crate-private `handlers::*`、`store::{schema,identity,bundles,media,…}`；`offline_migrate/` 独立维护窗 CLI |

Later（未在本表承诺）：不新增 Gradle module 仅为了再细分；不恢复已删 StructureTest。

### 2.2 有意的 `project(":sync")` 直接依赖边

下列边与各 module `build.gradle.kts` 中 `implementation(project(":sync"))` **一致**（有意
seam）。**不得**为迎合文档而静默删改 Gradle 边；亦不得在文档中遗漏合法调用方。

| 边 | 用途 |
|----|------|
| `app → sync` | composition root：前台生命周期 / `ForegroundState`、强制更新壳、`SyncPort` 注入、本地数据升级相关凭证存储 |
| `domain → sync` | CareLog 与协调器：本地写后的同步触发、家庭向导网关、会话/角色、本机清空与家庭权威回调 |
| `feature:log → sync` | 时间轴下拉刷新触发 sync；记录/计划本地发布文案 |
| `feature:family → sync` | 账户页 setup、管理员登录、成员申请/设备管理、可选更新横幅与退出 |
| `feature:onboarding → sync` | 引导内连接家庭服务器、TOFU / 成员登录 QR、setup probe |
| `feature:growth → sync` | 成长页下拉刷新触发 sync |
| `feature:settings → sync` | 关于区检查更新 / 安装更新与相关文案 |
| `feature:summary → sync` | 汇总页下拉刷新触发 sync |

无直接 `project(":sync")` 的 feature（`timer` / `export` / `search` / `widget`）经 domain
间接参与同步，不直连 sync 模块。

**运行环境 / 验收状态**：自动化覆盖可信 endpoint、建家/登录、成员申请审批、独立设备
会话、撤销与同步协议。物理 NAS 生产、双真机和相机扫码仍需按发布门执行；完整同步验收
边界见 [sync-trusted-endpoint.md](./sync-trusted-endpoint.md)。

---

## 3. 写路径

```text
UI 事件
  → domain UseCase
  → Room（立刻成功 → UI 刷新）
  → 标记 syncDirty；LocalWrite 只通知协调器“有待发布内容”
  → 仅当：前台 && availability 健康租约 && trusted HTTPS endpoint && 有效 device session
        → LocalWrite（因果 capability 已具备）:
             冻结 dirty 原子单元 → causal reconcile/commit（可含 media preimage）
             **不** incremental pull、**不** 推进 pull cursor
        → Foreground / 网络恢复 / PullToRefresh（完整周期）:
             pull → 冻结 → causal reconcile/commit（或 legacy fulfillment 路径）
        → 无因果 wire 时 LocalWrite 不得 no-pull（仍先 pull，避免更快 LWW 覆盖）
```

实现继续无后台同步、无推送拉同步。系统 PKI 或 TOFU/SPKI 验证 HTTPS endpoint，每台设备
持有独立 opaque session；未登录、断网或等待审批时仍先落 Room
并保留待同步状态。记录/汇总/成长下拉刷新是唯一显式立即同步动作。

Owner 与 Member 使用同一对账优先次序。跨进程只持久化 Room 实体、媒体与修订回执，不持久化
待发送 payload 队列；进程终止后丢弃临时 plan，下次成功对账后由当前 `syncDirty`/回执重新生成。
0.3.8 浅层待同步数量按 Baby+avatar、Record+photos、CarePlan+photos、CustomItem 与
FulfillmentCandidate 原子单元投影，不再直接求和六类 Room dirty 行。
升级完成后能在本机看到记录只证明数据保留；家庭侧可见性仍须等待首次成功的
发布结算（LocalWrite 无 pull 或完整周期 pull+settle），运营验收不得把“本机可见”误报为
“已发布到家庭”。

### 3.1 已实现的权威收敛周期

ADR-0017 的对账优先骨架保留；因果代（ADR-0020）下完整周期为：pull → 冻结 atomic units →
causal reconcile/commit（`confirmed|publish|conflict_preview|rejected` /
`accepted|merged|branched`），fulfillment 等仍可走 legacy authority 路径。LocalWrite 在
因果 capability 下跳过 pull 与 cursor 推进，与完整周期共用同一 settlement seam。
generation/cursor 证明失效时才走全量实体快照。浅层待同步数量按未终态 atomic units 投影，
静止且完整落库的周期必须把冻结集收敛到零；健康探测成功本身不能清状态。

履行权威的本机派生结算统一由 `core:database/fulfillment` 的
`FulfillmentAuthoritySettlement` 自持 Room 事务：domain 本机完成与 sync pull apply 只传
CarePlan portable identity；Module 在同一事务读取完整候选证据、计算 adoption patches，并重链
计划。该派生写不改变候选/计划的 `updatedAt`、`syncDirty` 或候选的独立记录转换指针。

`FamilyServerAvailability` 是协调器私有网络门闩和家庭网络设置的结果态，不替代浅层
`SyncStatus`。匿名客户端在可信 TLS 下并行检查 `/health`、`/ready` 与 setup capability，
8 秒总超时，不带家庭 token/数据；成功租约 30 秒。失败退避为 30 秒/2 分钟/10 分钟，
LocalWrite 事件只合并 pending，不突破退避。回前台、网络恢复和下拉刷新可立即探测；后台
停止 probe/sync。Android 不要求公网 `NET_CAPABILITY_VALIDATED`，避免家庭 LAN 被误判离线。

家庭成员最小目录（membership ID、称呼、role、本人标记）存入设备级 DataStore；时间轴直接
组合 Room、session 与该缓存，不在首个发射前调用远端 roster。完整设备/申请不进缓存；成功
成员刷新替换目录，退出设备/成员/家庭或远端删除身份时清除。

候选 endpoint 与现有会话分属两个隔离上下文。候选只匿名 probe；地址或证书改变后必须新登录/
审批，且 configured 返回的 family ID 与旧家庭相同才在 sync mutex 内一次切换。灾难恢复凭证
存安全凭证域，根密码不持久化；server `/data` staging/journal 在完整校验后调用 `Store` façade
单事务激活，SQLite schema/user_version 不改变。

计时器：

```text
开始 → 先持久化暂停的 STARTING 快照 → 请求前台服务与通知
     → 系统真实标记前台服务，且通知权限开启时通知已发布，才回执 RUNNING 并在 UI 走秒
     → 受限启动 / 权限 / 通知 / 超时 / 运行时 / DataStore 持久化失败 → 同一总覆盖：
       停 FGS 与通知，收口 FAILED（先尝试 durable FAILED，再 memory；保留侧别、累计值、session）
     → 持久化类失败原因 `STORAGE`（文案「状态保存失败」）；服务启动类仍用 RUNTIME/权限/通知/超时
     → FAILED 再持久化失败时仍先更新内存态，UI 不得长期停在 STARTING/RUNNING 假象
完成 → 冻结 draft 打开确认 sheet（`TimerCompletionUiState`，SavedState 可恢复）
     → 确认 → Saving（单飞；再确认 no-op）→ domain completeNursing：写 nursing Record；
       若绑定 carePlanId，同事务读取计划当前 active 照片并 clone 为 Record 独立 MediaAsset 行，
       再 complete 计划 + 候选
     → Composer→Timer handoff（Ticket 09）：显式 TimerHandoffSeed（baby/carePlan/note/amount/
       有序照片+borrowed|composer_owned）写入 TimerState 并随 DataStore 恢复；Timer accept 后
       Composer 才 close 且不删除已转移 owned 文件；完成时 merge seed 路径与 Ticket 08 直播
       plan media（去重 0–3）经 photoLocalPaths 写入 Record；丢弃只回收 composer_owned
     → 成功：先 durable 发布 next-feed offer 或 pendingExit + `timerClearPending`，再按 session
       token 清空 TimerState / 停服；DataStore 失败时保持 pending、禁止退出并自动重试，成功后
       才开放 next-feed 或消费 pendingExit，绝不恢复“事实已保存但计时仍在”的 ghost session；
       失败：Saving→可重试 sheet + error（结果不经旧 composition 回调唯一交付）
     → 完成 / 暂停 / 清空：先持久化非运行快照再停服；持久化失败同样停服；
       仅当存在真实可重试侧别（lastSide / 曾运行侧）时内存 `FAILED`；
       护理计划 bind 或无侧别会话保持内存 `PAUSED`，不得伪造 `"L"`
进程被杀 / 坏存储读 → 冻结或 **init fail-closed 清空** 并停服（强于 transition 的 keep-session FAILED）；
       仅进程内同 session 见证可保留 RUNNING；不自动重复启动
     → 完成态 SavedState：submit 身份（completionClientUuid + baby）与 draft/Saving 同写；
       Saving+draft 且有 durable uuid → 幂等 resume completeNursing（timer DataStore 空亦可）；
       Saving 但身份全失 → 可重试 sheet（「会话已失效」），不得伪造成功 pendingExit；
       已发布 post-save 但 TimerState 未清/clear ack 丢失 → 只补幂等 clear；已有
       next-feed/exit → 恢复 UI，但 clear 成功前仍门禁退出与新计时；
       next-feed 结束后再发 pendingExit，与无 offer 成功路径同可消费退出
CancellationException / Error → 先停服再原样重抛，不得吞成产品错误
```

| 服务态 | 含义 | 持久化失败时 |
|--------|------|----------------|
| `PAUSED` | 无前台服务；可开始一侧 | 停服；有真实侧别则内存 `FAILED`/`STORAGE`；bind/无侧别保持 `PAUSED` |
| `STARTING` | 已写暂停快照，等待系统确认 | 不得启动或继续 FGS；收口 `FAILED`（尽量 durable） |
| `RUNNING` | 仅服务 ack 后；UI 走秒 | ack 后写盘失败 → 停服 + `FAILED`（非假 RUNNING） |
| `FAILED` | 已安全暂停，保留 side/累计/session | 写盘再失败仍先更新内存 `FAILED` |
| `RECOVERABLE` | 进程恢复未见服务见证 | 与 transition 相同：停服，可重试启动 |

| 完成 UI 态 | 含义 | 配置/进程重建 |
|------------|------|----------------|
| sheet + draft | 确认面板打开 | SavedState 恢复 draft；可改可取消 |
| Saving | 单飞提交中 | 恢复 busy sheet；同 durable `completionClientUuid` 幂等 resume |
| saveError | 可重试失败 | 恢复 error + draft（含会话失效 fail-closed） |
| next-feed offer | 事实已落，待安排 | 恢复单 blob（baby+suggestedAt）；不重复写事实 |
| pendingExit | 无 offer 或 offer 已结束，待 Host 退出 | 可确认消费一次；再订阅不重复导航 |
| timerClearPending | 事实已落，计时快照待清 | token-scoped 停服 + DataStore clear 自动重试；清完才展示 offer/退出 |

绑定护理计划的计时完成以事务内 plan media 为准（不是打开计时/Composer 时的 UI 快照）；
计划照片所有权与顺序不变，Record 行独立 `client_uuid`、可共享 `local_uri`；幂等
`completionClientUuid` replay 不重复 clone。

只解析当前 TimerState；非 current 计时状态不属于支持输入。当前状态的进程重启恢复、幂等完成与异常安全结束仍是
必须门禁，fresh-current 不能被解释为丢弃当前会话恢复。`startForeground()` 正常返回本身不是
成功凭据（系统 AppOp 可静默忽略）；UI 只有在系统真实确认前台态后才显示“运行中”。Android
13+ 的通知权限被用户关闭时，前台服务仍可由系统接纳，此时不强求通知出现在应用可见列表；
`FAILED` / `RECOVERABLE` 明示已安全暂停并提供“重试启动”。重试使用稳定 session token，成功 ack 后才显示 RUNNING。

---

## 4. 权限

| 权限 | 用途 | 状态 |
|------|------|------|
| POST_NOTIFICATIONS | 喂奶提醒、计时 | 当前 |
| READ_CALENDAR / WRITE_CALENDAR | 用户主动开启护理计划的系统日历副本时按需申请；拒绝不影响乐记内计划与提醒 | 当前 |
| FOREGROUND_SERVICE（及合规类型） | 喂奶计时 | 当前 |
| RECEIVE_BOOT_COMPLETED | 重启恢复本地提醒/计时 | 当前 |
| 相册 / Photo Picker | 日记照片 | 当前 |
| INTERNET / ACCESS_NETWORK_STATE | HTTPS setup/login、push/pull 与媒体 | 已声明；网络调用受前台 + transport trust + session 约束，不限网络类型 |
| REQUEST_INSTALL_PACKAGES | 自托管应用内更新：调起系统 `PackageInstaller` 会话安装 release APK | 已声明；仅 release `com.lezi.babylog` 通道使用；须用户确认安装与「未知应用来源」授权 |
| ACCESS_FINE_LOCATION / ACCESS_WIFI_STATE | 家庭同步不需要 | 不声明，不存在运行时申请 |
| CAMERA | 扫描管理员 App 提供的普通成员单次登录 QR | 可选硬件；拒绝后仍可手动 endpoint + 申请 |
| SCHEDULE_EXACT_ALARM / USE_EXACT_ALARM | **不申请**；护理计划提醒用非精确闹钟即可 | |
| 麦克风 / 后台定位 / 附近设备 | **不申请** | |

拒绝通知：仍可记账，无提醒。
护理计划本地提醒允许系统在省电策略下批量/延后触发，**不保证**准时到秒；产品不承诺「精确闹钟」体验。下次喂养只复用家庭护理计划及其单一提醒来源，不写入独立时间或安排第二个闹钟；依照 ADR-0012，只承诺从 0.3.0 本地数据契约 v1 起的连续升级。

系统日历副本提供三级本机披露：仅“乐记 · 护理计划”、标题显示“宝宝昵称 · 记录类型”，或再把文字备注写入描述。标准 `CalendarContract.Events` 无通用照片附件字段；最高级别仅写“照片 N 张，打开乐记查看”并配置应用 URI，照片字节不交给系统日历账户。

同一设备上的每个计划只安排一个提醒来源：系统日历副本和开始时提醒严格回读成功后取消乐记重复通知；未启用、拒绝权限，或 provider 已确认已有提醒释放/不存在时，才由乐记使用非精确本地提醒回退。provider 结果不确定或仍持有已有提醒时保持该来源并持久标记待恢复，禁止形成双提醒。

---

## 4.1 发布与数据保护

| 项 | 实现 |
|----|------|
| release R8 | `isMinifyEnabled = true` + `isShrinkResources = true` |
| 系统备份 | `android:allowBackup="false"`；`backup_rules` / `data_extraction_rules` 对齐排除 |
| 明文 HTTP | 家庭 API、登录、同步与应用内更新在 release 中禁用明文；唯一产品例外是 ADR-0015 的家庭 LAN 邀请安装页，且不得映射公网 |
| FileProvider | 仅 `cache/export`；不暴露 `files/` / database；升级 APK **不**经 FileProvider 长期暴露 |
| 升级暂存 | 已安装 App 的更新仅用私有 `cache/app-update`；邀请安装页是浏览器首装分发，可进入系统 Download，但不属于应用内更新暂存合同 |
| 日志 | 不打印根密码、access/refresh、grant、Authorization 或敏感 body；用户可见错误过滤技术细节 |

---

## 4.2 自托管应用内更新（侧载 APK）

本产品当前以**家庭服务器侧载**为主通道，提供应用内检查 / 下载 / 系统安装。**不是**
Google Play In-App Updates / Play Core；若未来上架 Play，须另 flavor，不得在 Play 构建走
自研 APK 替换。

| 项 | 合同 |
|----|------|
| 通道 | 平台 `PackageInstaller.Session` + 家庭服务器鉴权元数据/APK；无 FCM、无后台推包 |
| 资格 | 鉴权 `/v1/app-update*` 仅供已加入家庭且 endpoint 已信任、会话有效的客户端；所有 `config/android-release-compatibility.json` 已枚举正式版本都可在家庭 LAN 通过独立 HTTP `8767` 取得同一个已验证 APK，恢复下载资格不受 `min_supported_version_code` 控制 |
| 版本语义 | 比较与门槛只用整数 **versionCode**；**versionName** 仅展示 |
| 双档 | `local < latest` → 可选；`local < minSupportedVersionCode` → 强制全屏（无「稍后」绕过主功能） |
| 请求头 | 受保护同步请求携带 `X-Lezi-Client-Version-Code`（整数） |
| 服务端 API | 鉴权 `GET /v1/app-update`（JSON）与 `GET /v1/app-update/apk`（APK 字节）；与同步共用会话与 TLS/信任。**两者**仅在元数据 + APK sha256 已验证时返回成功体：半通道（仅元数据 / 哈希不符）对元数据路由诚实 404/5xx，避免客户端 dual-tier 把无包可装的 `min_supported` 当成强制升级 floor |
| 门槛 | 仅当已验证更新通道（元数据 + APK sha256）存在时生效：头缺失或 `< minSupported` 时权威 sync 写/拉（pull / bundle / media 等）**以及灾难恢复写路径**（restore start / manifest / media / commit）返回 `code=client_update_required`；**仍放行**已验证通道上的更新元数据与 APK 下载。从未验证过、元数据缺失、或通道文件消失时同步/restore fail-open。原子 promote 中途（新 APK + 旧元数据哈希暂不符）保留 **last-known-good** floor 直至新 pair 验证成功，避免短暂降级到零门槛；负向 stamp 缓存避免半部署下每请求重哈希 APK |
| 诚实客户端闸 | `X-Lezi-Client-Version-Code` / `minSupported` 是对**诚实官方 App** 的兼容闸：阻止半兼容旧客户端脏写，**不是**防篡改安全根。头可被非官方客户端伪造；**真协议硬闸**仍靠 setup-status **capabilities**、wire schema/payload 校验与已验证会话。服务端不对「伪造高 version 头」做强绑定证明（权威叙述；同步合同见 [sync-trusted-endpoint.md §7.5](./sync-trusted-endpoint.md)） |
| 强制壳兜底 | `client_update_required` 后：元数据成功且 **versionCode &gt; local** → `ForcedAppUpdateState.WithPackage`（可安装 CTA，即使双档会判 optional）；元数据/通道失败或无更高 versionCode → **`PackageUnknown` 强制壳**（说明 +「重试检查更新」），`SyncStatus` 保持 Idle，**不得**呈现为泛同步/NAS 故障或「假正常」无强制层。已有强制态时，手动 `checkAppUpdate` 与同步 CUR 恢复共用：`versionCode &gt; local` 一律升为可安装 Forced；否则不得拆壳/刷 optional 横幅；`checkAppUpdate` **Result** 在壳保留时返回 `ForcedUpdate`/`ForcedPackageUnknown`（不得 bare UpToDate/Optional）；失败非 CUR 同步 **不** piggyback 拆壳；权威同步成功后的 piggyback 才可清壳；未加入家庭才清 surface。壳内可恢复会话（reauth）与 401 下载单次 refresh 重试；鉴权更新不可用且 origin 已知时给出 **8767** 局域网邀请安装引导；**无**「稍后」绕过主功能 |
| wire 破坏纪律 | 封闭 wire / `schema_version` / allowlist 的破坏性变更 **必须先** 抬 `min_supported_version_code` 并发布已验证可安装包，再让新 shape 入站；**不**做 dual-read / skip-unknown 协议。当前 floor 与「支持范围内 wire 冻结」关系见下方 §4.2.1 与 [DEPLOY.md](../../tools/lezi-sync/deploy/DEPLOY.md) wire-break checklist |
| 部署 | `package-nas` **fail-closed**：须 release APK + 合法 `app-update.json` 且 sha256 一致；鉴权 `/v1/app-update*` 不设匿名旁路，同一已验证 APK 可由 §4.3 的隔离邀请安装页提供首装 |
| 客户端缝 | `SyncPort`：`checkAppUpdate`、`availableOptionalAppUpdate` / `availableForcedAppUpdate`（`ForcedAppUpdateState?`）、`installAvailableAppUpdate`、会话内 dismiss；UI 不直连 PackageInstaller |
| 安装约束 | 装前解析 APK 归档：`packageName` == 本机 applicationId == 元数据；`versionCode` == 元数据且 &gt; 本机；签名证书与已装乐记一致；再 PackageInstaller 同签名原地替换；仅 release `applicationId = com.lezi.babylog`；本轮不承诺 debug 后缀包自更新 |
| 历史枚举 | `config/android-release-compatibility.json` 是 versionCode、versionName、Room schema、本地数据契约与当前同步 floor 的单一清单；Gradle、Android 更新矩阵、Room 迁移夹具和 lezi-sync 路由矩阵共同校验。永久无损基线仍是 0.3.0 / versionCode 6 / contract 1，后续正式版必须连续追加 |
| 无残留 | 流程结束后应用私有目录无 APK；**不**承诺清除系统 PackageInstaller 内部缓存 |

元数据形状（wire **snake_case**；部署文件 `app-update.json`）：

```json
{
  "package_name": "com.lezi.babylog",
  "version_code": 20,
  "version_name": "0.3.13",
  "min_supported_version_code": 20,
  "sha256": "<64 lowercase hex of APK>",
  "release_notes": "可选"
}
```

**当前 floor** 以 `config/android-release-compatibility.json` 的
`minimum_sync_version_code` 与部署 `app-update.json` 的 `min_supported_version_code`
为唯一真值（tree 目标 **20** / 0.3.13）。发版与 CD 前必须重核 catalog、`app-update.json`、
Cargo/Gradle 与签名 APK sha256 一致；不得沿用历史 16/0.3.9 或 16/0.3.12 示例当生产 floor。

触发：① 已加入且前台对信任 endpoint 握手/同步时顺带检查；② 菜单关于区点击检查。
可选更新：确认层 → 下载 → sha256 → **归档身份校验**（包名 / versionCode / 签名）→
PackageInstaller；失败清理私有暂存且**不** commit 异包。账户区非阻塞横幅，同一 versionCode
**进程会话内**「稍后」不再刷屏。强制更新：根全屏（含 onboarding 之上）消费系统返回键并遮罩主功能；有包时
立即安装（同一装前身份门），元数据暂缺时仅「重试检查更新」；壳内可 reauth 与 8767 邀请安装引导。
详细同步门槛与错误语义见
[sync-trusted-endpoint.md](./sync-trusted-endpoint.md)；部署 runbook 见
[`tools/lezi-sync/deploy/DEPLOY.md`](../../tools/lezi-sync/deploy/DEPLOY.md)。

### 4.2.1 Wire-break → raise min checklist

封闭家庭 wire（ADR-0008）**不做** dual-read / skip-unknown。多机 home LAN 的安全阀是
`min_supported_version_code` + 已验证可安装包：

1. **判定是否破坏性：** 新实体 type、封闭 key 集合变更、record/care_plan `schema_version`  bump、服务端 allowlist 放宽使旧客户端 `requireExactKeys` 整页失败等。
2. **先抬 floor：** 在 `app-update.json` 将 `min_supported_version_code` 提到**能解析新 shape 的最低官方 versionCode**；同时准备该 versionCode（或更高）的 **已签名 release APK**，`sha256` 与包一致。
3. **先发布可安装通道：** CD 原子对发布（APK 再 metadata），确认 `load_verified` 成功；旧客户端随后在权威 sync / restore 写路径收到 `client_update_required` 并能装包。
4. **再启用新写入：** 仅当通道已验证后，再让新客户端/服务端发布破坏性 shape。
5. **支持范围内 wire 冻结：** 当前 floor 以 catalog `minimum_sync_version_code`（tree **20**）为准，到最新 versionCode 之间 wire/`schema_version`/allowlist 视为冻结；该范围内多机可混用。下一轮任何破坏性变更必须先执行 2–4，**禁止**指望旧机 skip-unknown。低于 floor 的已发布版本仍可取得 APK，但不能借此继续使用旧同步 wire。
6. **0.3.13 因果切割（tree 已落地；NAS 生产切割待维护窗）：** 新实体 `wake_observation`、因果字段
   `base_version`/`mutation_id`/`version_id`、verdict 枚举与移除服务器近邻落选均属破坏性
   wire；打包须 minSupported=20 且 verified 通道可装，再在维护窗部署 server schema 12 与新客户端。
   权威 shape 见 [`causal-sync-wire.md`](./causal-sync-wire.md)。

完整运维条目见 [`tools/lezi-sync/deploy/DEPLOY.md`](../../tools/lezi-sync/deploy/DEPLOY.md)「Wire-break checklist」。

---

## 4.3 LAN 安装页（首次安装与历史版本恢复）

尚未安装乐记的受邀 Android 设备可用系统相机扫描同一个成员登录二维码，在家庭 LAN 内进入
由 lezi-sync 提供的安装页；无法使用当前鉴权 HTTPS 更新流的历史正式版本也可直接下载同一个
APK。该端口不完成家庭登录，也不放宽同步门禁。首次安装结束后用户必须打开乐记并重新扫描
管理员仍在展示的二维码；同签名原地升级保留本机数据、家庭 session、endpoint 与 SPKI 信任，
只有原会话本来就处于 reauth 时才继续既有重新登录流程。

| 项 | 合同 |
|----|------|
| QR envelope | 内层继续使用严格 member-login JSON v1；外层为 `http://<same-IPv4-or-IPv4-DNS-host>:8767/join#v1.<base64url>`。本版 NAS 分发监听为 IPv4；新版读取 URL 与旧 raw JSON，旧版不理解 URL 时改用系统相机下载新版 |
| 发现 | 管理员鉴权创建 grant 的响应可附加 `landing_url`；缺失时 App 继续生成旧 raw JSON QR，存在但不合法时拒绝展示 |
| 服务端面 | 可选独立 HTTP Router 只开放 `GET /join` 与 `GET /download/lezi.apk`；不得开放 `/v1/*`、`/health`、`/ready` 或数据目录 |
| APK | LAN 内匿名下载；复用 `app-update.json` 与运行时 SHA-256 校验，缺包、空包或哈希不符 fail closed |
| 页面 | 不显示家庭名、成员称呼或 grant，不加载第三方资源；正常页面立即清除地址栏 fragment，并设置 no-store、no-referrer、CSP 与禁止嵌入 |
| 风险 | 用户已明确接受 HTTP 中间人可替换页面/APK，或注入脚本读取 fragment 并盗用未过期 grant；8767 只允许可信家庭 LAN，禁止公网转发 |

该例外不改变生产 HTTPS、TOFU/SPKI、设备会话或既有鉴权 app-update 合同，架构边界见
[ADR-0015](../adr/0015-isolate-lan-invite-install-distribution.md)。

---

## 5. 当前工程能力

| 模块 | 状态 |
|------|------|
| onboarding / log / timer / settings | 当前交付 |
| family UI；RealSyncPort 默认 DI（含 Disabled）；NoOpSyncPort 仅公开测试桩（无 Hilt） | 当前交付 |
| summary / growth / search / export TXT / widget | 当前交付 |
| PDF / custom / food types / CarePlan calendar | 当前交付 |
| RealSync 家网实现 | 默认 DI；本机 Docker + 双模拟器前台已验收 |
| 自托管应用内更新 | 鉴权元数据/APK、双档门槛、PackageInstaller、打包 fail-closed；见 §4.2 |
| `lezi-sync` NAS | API/镜像/自动化；物理 NAS 生产部署待目标环境 |

**NAS schema / offline-migrate 边界：** 日常启动只接受精确 current schema（fail closed；
见 [ADR-0008](../adr/0008-support-only-fresh-current-product-contracts.md)）。**当前 tree**
server `DATABASE_SCHEMA_VERSION` 以 `tools/lezi-sync` 源码为准（撰写时 **12**，含
不可变版本/冲突/来源关系表）。**v11 仅为 offline-migrate 源**：审计 copy-out 升级到
v12；旧二进制不得打开 v12。历史 v3 数据根**不得**在 server startup 自动迁移；唯一出路
是 [ADR-0013](../adr/0013-offline-migrate-is-maintenance-window-cutover.md) 的两阶段路径：
维护窗前在备份上用显式 `lezi-sync offline-migrate`（源 user_version **3 或 11**→current、
独立临时 `out/`、`validate`），再经已授权维护窗 stop/copy-back/TLS CD。0.3.13 的 wire
minSupported / 客户端能力门与 schema 12 落地分开叙述。发布二进制含该子命令 ≠ 滚动兼容；
普通 CD 不执行。权威 runbook：
[`tools/lezi-sync/deploy/copy-back-tls-cutover-runbook.md`](../../tools/lezi-sync/deploy/copy-back-tls-cutover-runbook.md)。

> **当前同步策略（2026-07-25）**：中心化 NAS、硬家网、仅前台、无即时通知；
> **不做**后台 60s 对齐。实现与自动化已完成；本机 Docker 与双模拟器前台
> formula/pee 交叉可见已验证。物理 NAS / 双真机 / 相机扫码仍 open。

---

## 6. 非功能指标

| 项 | 目标 |
|----|------|
| 快记 | 点入口立即打开预填 Composer；确认前不得写入 |
| 冷启 | 尽快可点图标（目标约 1s 量级可交互） |
| 当日时间轴 | 按日查询，避免一次加载全历史 |
| 计时电池 | FGS + 有限**非精确**闹钟，禁止空转轮询 / 精确闹钟权限 |
| 隐私 | 默认本机；日志不打印健康明细 |
| 无障碍 | contentDescription；≥48dp；字体缩放不裁主按钮 |

---

## 7. 质量门

**单测优先**

- 日汇总计算
- 睡眠配对与 `anomaly_flag`
- 便便枚举边界；尿尿 `pee_amount` 1–3；排泄图标资源存在性（designsystem）
- `client_uuid` 幂等合并
- NoOpSyncPort 不抛未捕获异常
- fresh Room 创建当前 schema；force-stop/重启后当前数据、dirty/发布回执、TimerState 与提醒清理状态保持
- APK 原地替换先经过本地数据升级门禁；相邻迁移先按受影响域创建校验快照，失败不得自动清库
- 基线之前、未来版、空间不足或不一致数据稳定进入恢复界面；业务、提醒、Widget 不得提前打开持久化
- 应用内更新：SyncPort 检查结果（NotJoined / UpToDate / Optional / Forced）、校验失败不安装、
  同步被 `client_update_required` 映射为强制态；lezi-sync HTTP 鉴权元数据/APK 与门槛
  （pull / media GET 在低或缺 `X-Lezi-Client-Version-Code` 时 `client_update_required`；
  客户端权威 pull 携带 version 头；目标 APK 本地数据契约必须覆盖当前契约；`package-nas`
  用 `apkanalyzer` 对照追加式契约账本，check-only 缺 APK / sha 错 / 契约错均 fail-closed 烟测
  `tools/lezi-sync/deploy/test-package-nas-app-update.sh`，CI 随 lezi-sync workflow 跑）

**冒烟（当前 APK）**

1. 安装 → 建宝宝
2. 记：配方奶、便（带色）、睡下/醒来、母乳计时
3. 核对日汇总与时间条
4. force-stop → 再开 → 数据一致
5. 开深色、切第二宝宝
6. 账户页可见且无崩溃、无购买入口
7. （已加入家庭）关于区显示 `版本 {versionName}`；检查更新得到已最新或可选确认；强制时全屏

---

## 8. 交付物

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk  # 必须通过 release 签名与 apksigner 校验
README.md  # ./gradlew assembleDebug assembleRelease
docs/prd/  # 本产品规格
```

---

## 9. 合规备忘

- 包名、图标、文案自有；不使用参考产品商标。
- 上架前自备隐私说明（本机数据、是否同步、儿童信息）。
- 曲线数据注明来源与免责，不作医疗诊断。
- **Play 渠道**：不得在 Play 分发构建中使用本自托管 APK 替换通道；Play 构建须另 flavor 并仅用
  Play In-App Updates（若上架）。当前主路径为侧载 + 家庭服务器。
