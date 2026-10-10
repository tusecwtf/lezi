# sync 层规格（`:sync` 模块）

> 发布身份以 [`platform.md`](../platform.md) 与兼容目录为准；协议代 0.4.0 conflict-v2 / 只协商 `causal_sync_v2`。
> **权界**：本文权威 = `:sync` 模块的 seam、算法、交互与测试契约。**wire 字段、枚举与
> 闭合键集的唯一权威是 [`contracts/causal-sync-wire.md`](../contracts/causal-sync-wire.md)**；
> 可信端点行为合同见 [`contracts/sync-trusted-endpoint.md`](../contracts/sync-trusted-endpoint.md)；
> SyncPort 操作分组见 [`contracts/data-model.md`](../contracts/data-model.md) §6.2。**签名
> 唯一权威是代码**——本文只做 seam 枚举 + 文件锚点。

---

## 1. 职责与边界

- 副本同步客户端栈：唯一把 Room causal 数据搬上家庭服务器并应用远端事实的模块；
  `api(core:database)` 暴露使其调用方（domain、feature、app）传递可见 DAO——**有意**
  deep-façade 设计（见 [`architecture.md`](../architecture.md) §2）。
- 子包（root 仅留 `SyncPort` / `RealSyncPort` / `SyncModule`）：`engine/`、`backend/`、
  `session/`、`media/`、`appupdate/`、`qr/`、`clear/`、`heartbeat/`、`availability/`、
  `conflict/`、`disasterrecovery/`。
- 不做：WorkManager 后台轮询、推送拉同步、常驻前台服务；UI 不直连本模块内部（只经
  `SyncPort` 与状态流）。

## 2. 公开 seam 表

| seam | 锚点 | 职责 / 不变量 | 关键机制（按名枚举） |
|------|------|----------------|----------------------|
| `SyncPort` | `sync/SyncPort.kt` | 域面契约；操作分组见 data-model §6.2 | `SyncTrigger{Foreground,PullToRefresh,LocalWrite}` → `SyncPlan(push,pull)`；LocalWrite = **只 push**（不 pull、不推进 cursor、不 reconcile）；类型化异常（`BootstrapSecretRejectedException`、`MemberLoginQrTrustChangedException`…）；`NoOpSyncPort` 公开测试桩 |
| `RealSyncPort` | `sync/RealSyncPort.kt` | 生产实现（DI 默认） | 心跳循环宿主、强制更新壳状态机（`client_update_required` → Forced/PackageUnknown）、远端身份移除处理、会话观察 |
| 传输层 | `sync/backend/SyncBackend.kt` + `HttpSyncBackend` / `RetryingSyncBackend` / `RefreshingSyncBackend`、`backend/deadline/` | 装饰器栈：重试预算 → token 刷新 → OkHttp | 协议常量 `AUTHENTICATED_SYNC_PROTOCOL_VERSION`、`REQUIRED_CAUSAL_WIRE_CAPABILITIES`；`FamilyHttpBudget` / `FamilyHttpDisconnectWatchdog` 截止预算 |
| `ReplicaSyncEngine` | `sync/engine/ReplicaSyncEngine.kt` | 同步周期执行（§3） | 冻结信封 `FrozenCommitEnvelope`、`AtomicBundleId`、按实体 `ApplyVerdict`、pull 检查点重放、`LiveCensus` 对账、自愈停滞台账、reset 回执 |
| `PendingPublishInspector` | `sync/engine/PendingPublishInspector.kt` | 发布预检 + 引用媒体捕获（引擎委托，不改协议） | 批量 holder/媒体读、回执/dirty/`deleted but syncDirty`/头像语义与原 helpers 一致 |
| `SyncWireMapper` | `sync/engine/SyncWireMapper.kt` | Room ↔ wire 映射 | **不变量：设备本地 row id 与文件路径绝不出此边界**；snake_case 载荷与枚举归一 |
| `SyncHeartbeatEngine` | `sync/heartbeat/SyncHeartbeatEngine.kt` | 前台认证心跳探针（§5） | 节拍梯子、信号判定（`!=` 非 `>`）、能力门控、可用性喂食 |
| `ForegroundRoundFuse` | `sync/heartbeat/ForegroundRoundFuse.kt` | 零进度跨拍熔断（§5.2） | 预算计数、同信号抑制、身份整体作废 |
| 冲突族 | `sync/conflict/ConflictSnapshot.kt` + codec/paging/validation/projection | ConflictSnapshot v2 客户端管线 | `ConflictRootType{Baby,Record,CarePlan,CustomItem,WakeObservation}`、保留 canonical JSON、choice-only resolution |
| 灾备 | `sync/disasterrecovery/` | 灾备四步客户端流 | prepare → start → resume → commit（+cancel），凭证只进安全存储 |
| 本地清空 | `sync/clear/LocalReplicaClearCoordinator.kt` | sync barrier 内清空本地域 | `LocalClearWorkflow` 次序：syncMutex → local guard；可恢复 pending |
| 自更新 | `sync/appupdate/` | fail-closed 更新管线 | 元数据 + sha256 + 归档身份（包名/versionCode/签名）→ PackageInstaller；合同全文 platform §4.2 |
| 会话/信任 | `sync/session/` | 设备会话与 TOFU | `TrustedEndpoint`、SPKI pin、`SecureRefreshTokenStore` |
| 媒体 | `sync/media/` | 媒体暂存与身份 | `ImmutableMediaSpool`、sha256 `MediaContentIdentity`、可复用日志媒体解析 |
| QR | `sync/qr/` | 成员登录 QR codec | 严格 v1 payload + landing_url 外层（platform §4.3） |
| 可用性 | `sync/availability/` | `FamilyServerAvailability` 结果态 | 探活租约/退避；**不是**同步门闩 |

## 3. 同步周期算法（写路径）

```text
UI 事件
  → domain UseCase
  → Room（立刻成功 → UI 刷新）
  → 标记 syncDirty；LocalWrite 只通知协调器“有待发布内容”
  → 仅当：前台 && trusted HTTPS endpoint && 有效 device session
        → LocalWrite:
             冻结 dirty 原子单元 → commit-first（可含 media preimage）
             **不** incremental pull、**不** 推进 pull cursor、**不** reconcile
        → Foreground / 网络恢复 / PullToRefresh（完整周期）:
             pull → 冻结 → commit-first
        → 两端只协商 `causal_sync_v2`；mixed generation 在 mutation 前 fail closed
```

- 完整周期：**pull → 冻结 atomic units → commit-first（`accepted|merged|branched`）**。
  LocalWrite 跳过 pull 与 cursor 推进，与完整周期共用同一 settlement seam。
- generation 绑在家庭服务器数据根上，进程重启复用；generation/cursor 证明失效（显式换代、
  cursor 领先、灾难恢复后的新数据根）时才走全量实体快照。
- 浅层待同步数量按**未终态原子单元**投影：Baby+avatar、Record+record media、CarePlan+plan
  media、CustomItem、WakeObservation+wake media、FulfillmentCandidate。候选同样按
  `terminal-receipt:fulfillment_candidate:<uuid>` + `contentEpoch = updatedAt` 逃逸；
  永久发不出去（服务端 409/422、父记录/计划已终态拒绝或放弃、成员非权威宝宝）时写
  `abandoned` 回执，不计入待同步、不出未采纳卡片。重新履行产生新 epoch 后自然重新进入待发布。
- 跨进程只持久化 Room 实体、媒体与修订回执；pending mutation 保留一份不可变冻结信封
  （ADR-0022），不恢复通用 outbox。进程终止后丢弃临时 plan，下次成功对账后由当前
  `syncDirty`/回执与该信封重新生成。
- 静止且完整落库的周期必须把冻结集收敛到零；健康探测成功本身不能清状态。
- 活集普查 mismatch 每轮最多一次 cursor-0 重走；同一 `localSnapshot` 的耐久回执跨轮保留，
  下一轮不再为同一差异付全量历史拉。这份回执只记下每类 `localCount/serverCount`。
  计数相同的键集或 digest 漂移不改变 `localSnapshot`，因此不触发另一次重走。
  计数变化才再重走一次。
  持续 mismatch 同时投影本机多出的 `client_uuid`（先启发式：活行且无家庭发表；重走时可
  opt-in `include_live_keys` 只要对不上的类）。`dismissUnresolvedLocally`：本机多出 /
  家里拒绝写墓碑 + abandoned 且不 push；拉取洞只撤 skip 回执。宝宝/家庭不可去掉。
- 履行权威派生结算不在本模块：`core:database/fulfillment` 的
  `FulfillmentAuthoritySettlement` 自持 Room 事务（见 `layers/core.md` §4）。

## 4. 触发矩阵（`SyncTrigger` → 行为）

| 触发 | plan | 说明 |
|------|------|------|
| `Foreground`（回前台/网络恢复/心跳踢） | pull + push 完整周期 | 未收敛时静默续跑（受 §5.2 熔断约束） |
| `PullToRefresh`（三页下拉/账户浅状态点按） | pull + push 完整周期 | 用户主动「立即同步」 |
| `LocalWrite`（本地写成功） | **push only** | 不 pull、不推进 cursor、不 reconcile；接受 ~2 个回声前台轮成本 |

仅前台执行；进入后台停止探测和同步（取消式，非跳过）。完整合同：
[`contracts/sync-trusted-endpoint.md`](../contracts/sync-trusted-endpoint.md) §7.1。

## 5. 心跳与可用性（0.4.8）

### 5.1 节拍梯子（`SyncHeartbeatEngine` 常量）

- 基线 **60s**（`BASELINE_INTERVAL_MILLIS`，0.5 由 30s 放宽）→ 无变化按 **±20% 抖动**
  （`JITTER_FRACTION`）退避至封顶 **300s**（`MAX_NO_CHANGE_INTERVAL_MILLIS`，0.5 由 120s
  放宽；该常量同时是 0.5 tip-skip 安静轮的新鲜度窗，单一来源防漂移）。
- 回前台首拍 **+8s 防抖**（`FOREGROUND_FIRST_BEAT_DEBOUNCE_MILLIS`）；本地写完成、探到
  变化、回前台重置基线。
- 循环启动条件：前台 + 已加入 + 端点已 TOFU 固定；后台**取消**（非跳过）；未加入/需重登
  即取消；`Syncing` 状态跳过当拍不累积。
- 单拍 = 一次认证 `GET /v1/sync/heartbeat`（wire §1.5），拿闭合三键
  `{generation, head_rev, directory_generation}`；任一键与本机快照 **`!=`**（含水位回退）
  即经熔断缝踢一次前台轮。
- 能力门控：`sync_heartbeat_v1` 经 setup-status 增量 capabilities 广播，或经已加入前台循环自己的一次性 discovery beat（该拍 2xx 即视为能力证据并武装；404 则永久停用）。老服务端不走同步失败路径。
- **对端写入可见性上限 = 心跳间隔 + 一轮**（0.4.8 验收第 1 条「最迟约 30s + 一轮」的
  0.5 显式改写，owner 拍板接受的交换）：安静期由 60s 基线/300s 退避推导；tip-skip
  安静轮窗内的对端提交同属此上限，轮末心跳即恢复可见。
- **统一模型**：心跳是可用性的常驻喂食者——成功即复健 Available 并刷新最近健康时间；
  失败按既有传输失败分类降级（无新迟滞），降级期节奏切既有 30s/120s/600s 重试梯；不进
  同步失败 UI。TOFU 证书变化照常浮现；401 终态走既有设备移除/家庭删除异常映射。

### 5.2 零进度跨拍熔断（`ForegroundRoundFuse`）

- **身份维度**：`ForegroundFuseIdentity(familyId, deviceId, baseUrl, membershipId, joined)`；
  身份切换整体作废全部熔断状态（普通 token/cursor 更新不作废）。
- **预算**：同一身份 × 同一观测信号最多跑完 **3** 个零进度前台轮（含第一轮，
  `MAX_ZERO_PROGRESS_FOREGROUND_CONTINUES`）。第 3 轮结束后武装抑制——后续**同信号**
  拍照常探针、照常喂可用性，但绝不再开第 4 个数据轮。
- **解除并重置**：信号任一变化（含水位回退）、**真实用户触发**（回前台/本地写/下拉/网络
  恢复）、成功或耐久进度。心跳的踢与内部续走内部缝，**不得**冒充真实用户触发。
- 全部判定非挂起、共享一把 monitor（无锁升级）。

## 6. 重试与截止预算（`backend/retry` + `backend/deadline`）

- handshake/detail：3s connect、10s response、3 attempts、30s elapsed；
  pull/commit/resolution：3s / 20s / 3 / 60s（H15，wire §1.3）。
- 合法 `Retry-After` 优先，否则 capped exponential full jitter；**只重放幂等的冻结请求**。
- policy deadline 透传到 HTTP adapter：晚启动 attempt 收缩 socket timeout 并到期 disconnect。
- 单请求预算耗尽以类型化失败离开 retry，不以取消形态离开。
- 不由 `RealSyncPort` 另建 30s/2min/10min 广域同步调度。stale/expired resolution 保留旧
  事实并转 ConflictSnapshot refresh；auth/capability/ACL/canonical 为终态。

## 7. 等待上限表（原 tech.md §3.2；不改 0.4.0 wire / H15）

0.4.4 只收客户端等待上限。本表**不是** 0.4.0 wire 合同，也不改 wire §1.3 H15 前台单请求
数字。0.4.0 黄金身份（Android/server 0.4.0、versionCode 21、Room 28、本地数据契约 5、
server schema 13、同步 floor 21）不动。

| 路径 | 上限 | 说明 |
|------|------|------|
| 探测 / health / setup-status | 8 s | Auth/Setup 探测；单次尝试 |
| 会话建立 / 申请 / 认领 / 批准 / 刷新 | 12 s | Auth/Setup 会话命令 |
| 拿不到对账锁 | 2 s | 登录/检查/批准不得空转满向导超时 |
| 前台整轮对账 | 120 s | 到顶停下一页或下一文件；检查点保留 |
| LocalWrite 整轮兜底 | 10 min | 只防僵死、不切合法大图 |
| 本地数据门闩 | 45 s | 检查 + 本机密钥库 + 快照 + 迁移 + 校验；超时进已有阻断，不访问家庭服务器 |
| 相册导入读无进展 | 8 s | 关闭读流；按导入合同回收临时文件 |
| 系统日历 write | 8 s | 可取消；计划已落本机则保存成功；开机全量重排不得堵住门闩 |
| 导出生成 | 30 s | 或可返回上级；不得全应用遮罩 |

删除或更换记录照片后的本机清理拿不到整轮对账锁时保留待清理标记，不把保存焊在对账上；
不重做更旧契约升级步骤的整文件哈希。

## 8. 交互与实现注记

- **最小成员目录缓存**：家庭成员最小目录（membership ID、称呼、role、本人标记）存设备级
  DataStore；时间轴直接组合 Room、session 与该缓存，不在首个发射前调用远端 roster。完整
  设备/申请不进缓存；成功成员刷新替换目录，退出设备/成员/家庭或远端删除身份时清除。
- **候选 endpoint 隔离**：候选端点与现有会话分属两个隔离上下文。候选只匿名 probe；地址或
  证书改变后必须新登录/审批，且 configured 返回的 family ID 与旧家庭相同才在 sync mutex 内
  一次切换。灾难恢复凭证存安全凭证域，根密码不持久化。
- **可用性不是门闩**：`FamilyServerAvailability` 是家庭网络设置的结果态，不替代浅层
  `SyncStatus`；健康租约只抑制冗余重复探活，`sync()` 与心跳都不检查 availability。匿名
  探测并行查 `/health`、`/ready` 与 setup capability，8 秒总超时，不带 token/家庭数据；
  成功租约 30 秒；失败退避 30s/2min/10min；LocalWrite 只合并 pending 不突破退避；Android
  不要求公网 `NET_CAPABILITY_VALIDATED`。

## 9. 测试契约（三缝隙布局）

- **端口信号循环级**（`RealSyncPort*` seam 测试，假时钟 + 录制后端）：生命周期（前台启动/
  后台取消零探针）、触发→请求映射、心跳节拍与抑制、`Syncing` conflation、会话失效取消、
  401/404 映射、跨拍熔断必须**跨熔断后数拍**断言（同信号后 handshake/pull 不增长而心跳与
  availability 照常）。
- **纯判定函数级**：信号 × 会话快照 → 动作；`SyncTrigger` → `SyncPlan`。
- **真服务端 seam**：`IsolatedLeziSyncServer`（拉起真实 lezi-sync 二进制）覆盖引擎验收与
  n-way 冲突矩阵（与 `domain` 侧共享黄金语料 `config/conflict-v2-golden.json`）。
- 先例文件族：`RealSyncPortHeartbeatLoopTest`、`engine/ReplicaSyncEngine*Test`、
  `backend/HttpSyncBackend*WireTest`、`engine/ConflictV2GoldenCorpusTest`。

## 10. 代码连线

| 本文章节 | 代码 |
|----------|------|
| §2 seam 表 | `sync/src/main/kotlin/com/lezi/babylog/sync/{SyncPort,RealSyncPort,SyncModule}.kt`、`backend/`、`engine/`、`heartbeat/`、`conflict/`、`disasterrecovery/`、`clear/`、`appupdate/`、`session/`、`media/`、`qr/`、`availability/` |
| §3 周期 / §4 触发 | `engine/ReplicaSyncEngine.kt`、`engine/FrozenCommitEnvelope.kt`、`engine/SyncWireMapper.kt`、`SyncPort.kt`（`SyncTrigger`/`SyncPlan`） |
| §5 心跳/熔断 | `heartbeat/SyncHeartbeatEngine.kt`、`heartbeat/ForegroundRoundFuse.kt`、`RealSyncPort.kt`（循环宿主） |
| §6 重试/预算 | `backend/retry/`、`backend/deadline/FamilyHttpBudget.kt`、`backend/RetryingSyncBackend.kt`、`backend/RefreshingSyncBackend.kt` |
| §8 交互 | `session/`、`availability/`、`core:datastore`（目录缓存） |
| §9 测试 | `sync/src/test/kotlin/com/lezi/babylog/sync/`（含 `RealSyncPortTestSupport.kt`、`IsolatedLeziSyncServer.kt`） |

### 成员二维码：候选传输信任与会话激活

二维码校验及用户确认后的领取请求直接使用二维码中的明确 HTTPS/SPKI profile；
不为“尚未领取成功”预先写入可信 endpoint，也不在失败、取消或页面切换时调用用户级
“忘记服务器”。既有信任及其它普通加入申请的本地结果未知记录保持不变。
传输层在发送 grant 前安装该 profile 的 TLS 校验，不能降级为未确认的 TOFU。

领取使用进程内操作身份与原本机身份/信任快照。只有操作和快照仍有效，才在既有
凭据 owner 内激活服务器返回的会话及 QR endpoint，并保留既有副本重置门禁。
显式忘记服务器、身份替换或另一条领取会撤销旧操作的激活权限；旧操作清理只释放
自己的进程内身份，不恢复或删除任何 endpoint，不触碰其它加入申请。进程退出不会留下
临时 trust journal；未完成的 grant 仍按既有精确领取回执协议由用户重新发起恢复。
