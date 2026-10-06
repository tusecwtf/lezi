---
triage: ready-for-agent
title: 0.5.2 同步卡死加固：终态回执补全、超时不杀消费者、状态与锁收口
tracker: .scratch
decisions: 2026-09-12 owner——Q1=APK+NAS 同号 0.5.2（两台尚未装 0.5.1，同一维护窗直接上 0.5.2）;Q2=履行候选永久发不出去时静默放弃、不出未采纳卡片;Q3=四个永久媒体拒绝码立即终态回执，两个过期码维持每轮重新 stage;Q4=sessionMutex 只包 token 读取/刷新，LocalWrite 加 10 分钟兜底上限
---

# 0.5.2 同步卡死加固

## Problem Statement

2026-09-12 对数据库设计与前后端同步流程做了专家式审查（客户端并发 + 服务端锁/存储两条线，
关键论断逐行抽查并做了临时探针）。结论：**没有互斥锁死锁**，数据库水位设计健全；用户在
APK 上看到的「一直向最后一项同步 / 待同步 1 项永不归零 / 之后再也不自动同步」是**活锁与
状态泄漏**叠加，全部能落到具体代码行：

| # | 现象 | 根因（已验证） |
|---|---|---|
| P0-A | 「已保存在本机 · 待同步 1 项」永不归零，每次回前台跑 3 轮后变「暂时无法同步」 | `fulfillment_candidate` 没有终态回执路径：`PendingPublishDao.kt:64-67` 与 `PendingPublishInspector.kt:67` 直接数 `syncDirty`；`EphemeralPublishPipeline.kt:43-52` 的 409/422 直接抛出、无回执、不跳过；成员分支 `:34-37` consume 不 `markSynced`；服务端 `bundles.rs:1341-1372` 对「引用的记录/计划从未被家庭服务器接受」永久 409；`CausalSettlement.abandonMutation` 与 `recordTerminalRejection` 都不级联候选。**`layers/sync.md` §3 已写「FulfillmentCandidate 不再直接求和 dirty 行」，代码与 spec 相反。** |
| P0-B | 同上，但卡住的是带照片的记录 | 服务端只发六个媒体拒绝码（`causal.rs:3247-3252`）且 wire 标 `retryable=false`；客户端 `HttpSyncBackend.kt:2465-2470` 接受为冻结码，`CausalSettlement.kt:1103-1132` 却不回执。`media_preimage_expired` / `missing_media_bytes` 每轮重新 stage 可自愈；`media_uuid_conflict` / `media_sha256_mismatch` / `media_byte_size_mismatch` / `media_membership_mismatch` 四个永久码同一冻结信封每轮再被拒。wire §9.5 closed list 根本没列这六个码。 |
| P0-D | 进程级静默停摆：所有自动同步不再发生、无报错、下拉才有反应 | 实测：`SyncRetryPolicy.execute` 在单请求 elapsed 预算耗尽时抛出**原始** `TimeoutCancellationException`（`:176`、`retryOrThrow` 末尾）；`syncInternal:1933` 按 `is CancellationException` 直接 rethrow；`RealSyncPort.kt:505-518` 的 `syncSignal` 消费者无 catch → 协程结束，之后 `requestSync` 的信号无人消费。Foreground 轮 `:2625` 用的是 `withContext(ElapsedBudgetContext)` 而非 `withElapsedBudget`，同样不兜底；`FamilySyncError.kt:67` 的 `TimeoutCancellationException → ResponseTimedOut` 在该路径是死代码。 |
| P1-C | `Syncing` 残留、心跳停摆 | 续跑后消费者在后台 `continue`（`:507`）丢信号，`Syncing` 留到下次回前台；门闩阻断（`ForegroundSyncBlockedException`，`:3038`）也保留原状态；心跳循环 `:1094-1098` 在 `first { it != Syncing }` 上无上限等待。（原判「tip-skip 让它永久残留」不成立：续跑 veto 在 `continue` 前不会被消费，下次回前台必跑真轮。） |
| P1-L | N 张照片期间名册/批准/心跳全部排队 | LocalWrite 无整轮预算（`RealSyncPort.kt:2621`）；`RefreshingSyncBackend.kt:211-242` 的 `sessionMutex` 跨每个认证业务请求持有，而不只包 token 刷新。 |
| P1-R | 服务端 `/ready`、握手、commit 的 `is_ready` 串行在一次磁盘探测上 | `readiness.rs:28-64` 跨 `spawn_blocking` 持 `readiness_cache` tokio 锁。 |
| P2 | 潜在错单元回执 / 文档缺口 / 性能 | `sync.rs:611-614` `InvalidCausalBatch` 用 `first_mutation_id` 顶替（仅空批或 >64 单元可触发，客户端保证 ≤64）；`sync.rs:618-622` `Forbidden*` 422 分支不可达（`authorize_mutation` 已在 `causal.rs:2271-2285` 转成 `forbidden_*` 信封）；bundle commit 在 family 锁内哈希（当前客户端 bundle `media = emptyList()`）；`PRAGMA synchronous` 未设。 |

已推翻、**不做**：`Forbidden*` 走 422（不可达）；tip-skip 造成 `Syncing` 永久残留（veto 保护）。

## Solution

**0.5.2 同一维护窗同步升级 NAS 与两台 APK（两台尚未装 0.5.1，直接跳到 0.5.2）。**
P0 全在客户端；服务端只带 readiness 锁外移与两处 hygiene；零 wire 字段、零 schema、零
新 FailureKind。

- **终态词汇对齐（P0-B）**：客户端把四个永久媒体码视为终态并写回执；两个过期码维持
  「每轮重新 stage」的既有自愈路径。wire §9.5 补列六个媒体码及其分类。
- **履行候选有终态（P0-A）**：待同步数量与收敛判定对候选也走 `terminal-receipt:` 逃逸；
  候选永久发不出去（服务端 409/422、父记录/计划已终态拒绝或放弃、成员非权威宝宝）时
  静默写 `abandoned` 回执、不计入待同步、不出未采纳卡片；一条坏候选不阻塞同批其它候选。
- **超时是失败不是取消（P0-D）**：单请求预算耗尽必须以类型化失败离开 retry policy；
  `syncInternal` 只把真取消当控制流；信号消费者对任何非取消异常存活。
- **状态与锁收口（P1-C / P1-L）**：后台丢弃续跑信号或门闩阻断时 `Syncing` 落回 `Idle`；
  心跳等待有上限；`sessionMutex` 只包凭证读取/刷新；LocalWrite 加 10 分钟兜底上限。
- **服务端（P1-R + P2 hygiene）**：readiness 探测移出 cache 锁；`InvalidCausalBatch`
  不再冒名 `first_mutation_id`。

## User Stories

1. 作为完成了一条计划的家长，我希望「待同步 1 项」在下一轮后归零，即使这条履行证据引用的
   记录曾被家庭服务器终态拒绝，以便我不再对着一个永远消不掉的数字。
2. 作为成员账号、在自己本机宝宝上完成计划的家长，我不希望这条本机保留的履行证据计入家庭
   待同步，以便浅状态说的是家里的事、不是本机永远发不出去的事。
3. 作为发照片被 `media_uuid_conflict` 等永久拒绝的家长，我希望这条记录一次性进入「本机事实
   未被采纳」，我重新保存后再发，以便它不再每轮占住同步而其它记录发不出去。
4. 作为照片 preimage 过期的家长，我希望下一轮自动重新上传并发出，行为与 0.5.1 一样。
5. 作为家里网络很慢、某次同步超时的家长，我希望之后回前台、本地写、心跳踢仍会触发同步，
   而不是应用悄悄再也不自动同步，直到我重启它。
6. 作为超时后回到前台的家长，我希望看到「响应超时」一类失败文案而不是什么都没有。
7. 作为切到后台又回来的家长，我不希望浅状态卡在「正在同步」而其实没有任何轮在跑。
8. 作为心跳循环，我不希望在一个已经没有轮在跑的 `Syncing` 上无限等待。
9. 作为正在发几张照片的家长，我希望家人此时点名册、批准新设备不必等我的照片全传完。
10. 作为一次 LocalWrite 轮，我希望有一个只防僵死、不切合法大图的兜底上限。
11. 作为 NAS 操作者，我希望 `/ready` 与握手不再互相排队等一次磁盘探测。
12. 作为家庭维护者，我不希望这次新增 wire 字段、schema、reason、FailureKind 或抬同步最低
    versionCode，以便偶尔漏升的旧包仍能同步。
13. 作为维护者，我希望 0.5.1/31 进入已发布升级源、渠道目标变为 0.5.2/32，且更新目录与
    真实签名 APK 对得上。
14. 作为隔离实例上的维护者，我想在开发机 mktemp 数据根上证明「候选引用未被接受的记录 →
    一轮后待同步归零」与「`media_uuid_conflict` → 终态回执」，证书测试永不打到家庭 NAS。

## Implementation Decisions

- **范围**：客户端 P0-A / P0-B / P0-D 必带；P1-C / P1-L 必带；服务端 P1-R 与 `InvalidCausalBatch`
  hygiene；wire §9.5 与 `layers/sync.md`、CONTEXT 补句；0.5.2 定名与同步升级。不动续跑规则
  （`isIncompleteForegroundCycle`）与熔断预算；不新增 FailureKind；不改 Room schema
  （`causal_transport_journal` 的 `journalKey` 字符串已容纳 `terminal-receipt:fulfillment_candidate:`）。
- **终态码分类（客户端 `CausalSettlement.isTerminalRejectionCode` 为唯一来源）**：
  终态 += `media_uuid_conflict`、`media_sha256_mismatch`、`media_byte_size_mismatch`、
  `media_membership_mismatch`；**不**终态：`media_preimage_expired`、`missing_media_bytes`
  （维持 0.5.1：下一轮 `CommitUnknown` 重放同一信封，不写终态回执；已有 prepared
  receipt 时可能不再 PUT。本票不新开 restage）。服务端「未列举码 →
  `invalid_domain`」维持不变。`HttpSyncBackend.CAUSAL_COMMIT_TERMINAL_CODES` 不动。
- **履行候选终态（静默放弃 = Q2）**：
  - `PendingPublishDao.observeCount` 与 `PendingPublishInspector.hasPendingPublishUnits` 对
    候选加同款 `NOT EXISTS terminal-receipt:fulfillment_candidate:<uuid> AND contentEpoch = updatedAt`。
  - `EphemeralPublishPipeline`：stage/commit 收到 409/422（`UnresolvedReference`、
    `ImmutableFulfillmentCandidateEvidence`、`InvalidCarePlanFulfillmentPair` 等）→ 写
    `TerminalRejectionReceipt(entityType="fulfillment_candidate", abandoned=true, contentEpoch=updatedAt)`，
    `consume` 该行并**继续**下一条；5xx/传输失败照旧抛出。成员非权威宝宝分支同样写
    `abandoned` 回执（CONTEXT「本机保留内容：不计入家庭待同步」）。
  - 级联：`CausalSettlement.abandonMutation("record"|"care_plan")` 与
    `recordTerminalRejection` 命中 record/care_plan 时，对引用它的 dirty 候选写同款
    `abandoned` 回执。
  - `unacceptedFact()` 投影**过滤** `fulfillment_candidate` 回执（不出卡片）。候选重新履行
    产生新 `updatedAt` → 回执 epoch 失配 → 自然重新进入待发布。
- **超时归类（P0-D）**：`SyncRetryPolicy.execute` 在「预算耗尽 / 末次尝试」把
  `TimeoutCancellationException` 换成 `SyncRetryBudgetExceededException`（已映射
  `ResponseTimedOut`），外部真取消仍原样透传（保留 `originalCancellationOrSelf` 语义与既有
  测试）。`syncInternal` 的取消判定改为 `failure is CancellationException && failure !is TimeoutCancellationException`。
  `syncSignal` 消费者对非取消异常 `runCatching` 兜底并继续循环。Foreground 轮改用
  `withElapsedBudget` 语义（同 `:2802` 灾难恢复路径），任何残余 `TimeoutCancellationException`
  变 `FamilyHttpException(SyncTookTooLong)`。
- **状态收口（P1-C）**：消费者后台 `continue` 前 `tryLock` 成功且 `Syncing` 才置 `Idle`；
  `updateFailureStatus` 对 `ForegroundSyncBlockedException` 在 `Syncing` 时落 `Idle`；心跳
  `first { it != Syncing }` 加上限（`ForegroundSyncCycle.MAX_ELAPSED_MILLIS` + LocalWrite
  兜底上限），到期按当前状态重新推导而不是永久停摆。
- **锁与预算（P1-L = Q4）**：`RefreshingSyncBackend.authenticated` 只在读取/刷新凭证时持
  `sessionMutex`，业务请求在锁外；401 后的刷新重试再进锁一次（同
  `authenticatedOnceOutsideMutex` 模式，`exactly once`）。LocalWrite 轮包
  `ElapsedBudgetContext(SyncTookTooLong, LOCAL_WRITE_MAX_ELAPSED_MILLIS = 10 min)`；单请求预算
  （MediaPrepare 240 s）不变。
- **服务端 0.5.2**：`readiness::is_ready` 先在锁内读缓存，未命中时释放锁再
  `spawn_blocking` 探测，探测完再短暂进锁写缓存（并发未命中允许重复探测一次，不串行）。
  `handlers/sync.rs` 的 `InvalidCausalBatch` 返回不带 `mutation_id` 的 §9.5 信封（`mutation_id`
  为 optional），客户端 `recordTerminalRejection` 的 `mutationId == null` 分支已按整批处理。
  `Forbidden*` 422 分支保留为防御，不改。
- **合同文档**：wire §9.5 closed code 列表补六个媒体码，并注明「过期两码客户端可重新 stage
  后再 commit，其余四码为终态」；`layers/sync.md` §3 把「FulfillmentCandidate 不再直接求和
  dirty 行」落成「候选也按终态回执逃逸；永久发不出去 → abandoned 回执、不计数、不出卡片」，
  §6 补「单请求预算耗尽以类型化失败离开 retry，不以取消形态离开」，§7 加 LocalWrite 10 分钟兜底；
  CONTEXT「待发布」`Avoid` 追加「以取消形态逃逸的超时」。不新 ADR。
- **版本**：渠道目标 0.5.2 / versionCode 32；0.5.1 / 31 移入 `released_versions`。Room 29、
  本地数据契约 6、server schema 13、同步 floor 21 不动。服务端 Cargo 同号 0.5.2。
- **发布顺序**：同一维护窗；推荐两台先装 0.5.2 APK，再换 NAS。本 spec 不执行 NAS CD；到发布
  票再 propose-then-confirm；普通 CD 不替换 TLS 身份。
- **测试缝（现有最高缝，不新建门面）**：`RetryingSyncBackendTest`（预算耗尽异常类型）、
  `RealSyncPortForegroundCycleTest` / `RealSyncPortHeartbeatLoopTest` / `RealSyncPortLocalWriteNoPullTest`
  （信号循环级：超时后信号仍被消费、`Syncing` 回落、心跳不永久停）、
  `RealSyncPortCarePlanFulfillTest` + `IsolatedLeziSyncServer`（候选终态、待同步归零、无卡片）、
  `RealServerMediaReceiptFaultSeamTest`（`media_uuid_conflict` 终态、过期码仍非终态重放）、
  `tools/lezi-sync/tests/api.rs` readiness 族。

## Testing Decisions

- 只测对外行为：待同步数量是否归零、卡片出不出、超时后下一次 `requestSync` 有没有产生请求、
  `Syncing` 是否回落、心跳是否继续拍、名册请求是否在照片上传期间完成、`/ready` 并发是否
  互不排队。不测私有函数名。
- **客户端**：
  - 假后端让 Pull 第 N 次尝试挂到预算耗尽 → 失败分类 `ResponseTimedOut`、`SyncStatus.Error`，
    随后 `requestSync(LocalWrite)` 仍产生 commit 请求（消费者存活）。
  - 续跑信号到达时前台状态为 false → `status` 不再停留 `Syncing`；心跳循环在假时钟推进上限
    后继续拍。
  - 真服务端 seam：候选引用一条被终态拒绝（或 `abandonMutation`）的记录 → 一轮后
    `pendingPublishCount == 0`、`unacceptedFact` 无候选卡片、同批其它候选照常发出；成员非权威
    宝宝候选同样归零。
  - 真服务端媒体故障 seam：`media_uuid_conflict` → 终态回执 + 未采纳事实出现且下一轮不再
    commit 同信封；`media_preimage_expired` 不写终态、下一轮仍 CommitUnknown 重放（与
    0.5.1 相同；不新开 restage）。
  - `RefreshingSyncBackend`：一个慢业务请求持有期间，另一个认证请求不等待其完成；401 刷新
    仍 exactly once。
- **服务端**：readiness 缓存过期时两个并发 `/ready` 都在单次探测时长内返回；
  `InvalidCausalBatch` 响应无 `mutation_id`。Rust 三件套。
- **不单开**：续跑规则、熔断预算、tip-skip、同步冲突收件箱、pull 媒体组瞬态过滤、
  bundle 锁内哈希、`PRAGMA synchronous`。
- **发布冒烟**：Rust 三件套 + 相关 JVM；隔离实例候选终态/媒体终态；NAS 仅在 owner 确认 CD 后
  按回滚手册做健康与证书指纹比对；两台装 0.5.2 后各发一条带照片记录 + 完成一条计划，浅状态
  归零。

## Out of Scope

- 改续跑规则（`isIncompleteForegroundCycle`）或熔断预算；把续跑改成进度 UI。
- 新 FailureKind、新 wire 字段/reason、Room schema、抬同步最低 versionCode。
- 服务端「未列举码 → `invalid_domain`」的收窄；`Forbidden*` 422 防御分支。
- bundle commit 锁内哈希优化、`PRAGMA synchronous`、pull SQL `LIMIT`、live census 冷缓存。
- 在家庭 NAS 上做证书测试；VPS 部署。本 spec 不执行 NAS `push-and-deploy`。

## Further Notes

- 审查过程稿在会话记录；P0-D 用临时 JVM 探针（已删）实测：假时钟下单次 Pull 尝试挂满 60 s，
  `execute` 抛出的类型是 `kotlinx.coroutines.TimeoutCancellationException`。
- 0.4.5 票里的「待同步 1 项被扣」与本次 P0-A 同一族；0.4.6 给 pull 加了终态回执/放弃，
  发表侧的候选与永久媒体码是这次补齐的两块。
- 工单见 `issues/01`–`06`。01–05 无阻塞可并行；06 等 01–05。
