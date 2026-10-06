# 0.5.4 · 本机数据安全加固（local-data-safety）

- `Status: ready-for-agent`（2026-09-13）
- 来源：[`../2026-09-13-sync-conflict-best-practices-review.md`](../2026-09-13-sync-conflict-best-practices-review.md) 第一批建议（9 agent 审查 + 对抗校验）。
- 身份钉目标：**0.5.4 / versionCode 34**。
- 硬约束：**零 schema**（本地数据契约 6 / Room 29 / server schema 13 / floor 21 不动）、**零 wire**（协议代 0.4.0 不变，仅文档注记）、**零服务端代码**（D7 仅 `docs/spec/` 文档）。NAS 同号发布在发布票另行 propose-then-confirm；不触 TLS/secret 不变量。

## Problem Statement

从家人（用户）视角，当前有六个"数据安全"毛边：

1. 在「待处理」里点了「只从这台手机去掉」的一条记录，几天后家人修改了那条记录，它**又无声出现**在这台手机上——"去掉"这个决定没有被执行到底。
2. 家庭管理员把两个家庭宝宝合并后，部分记录可能留在被并掉的宝宝名下或引发同步分叉（合并前已冻结的发表信封仍按旧宝宝身份发出）。
3. 「退出这台设备」只给一句文字警告：不说还有几条没同步，点确认即永久丢弃；被家庭管理员移除的设备更是事后才无声清空。
4. 个别本机记录数值越界（绕过 UI 写入）后本地看得见、家里永远收不下，成为永久 pending 的僵尸行。
5. 低内存手机上强制更新把整个 APK 读进内存，可能中断升级流程。
6. fresh-schema 冒烟测试断言停在 Room 26（当前 29），fresh-only 策略的第一道回归防线已失效；另有三处已知 JVM 测试失败在账。

## Solution

把「本机去掉」变成持久决定（本机拉取应用尊重 dismissed 台账）；把宝宝合并变成因果安全操作（合并前废弃冻结信封）；给两条破坏性路径加"还剩多少条"的量化披露与先同步机会（清空语义本身不变）；把数值校验下沉到落库点消灭僵尸行；把更新 APK 改为流式落盘；修好测试门禁并还清已知测试债；顺手修正三处文档漂移。全程零 schema、零 wire、零服务端代码。

## User Stories

1. 作为在「待处理」选择「本机去掉」的家人，我希望这个决定在这台手机上持久生效（即使家人之后修改了那条记录），所以我不会再看到已去掉的内容悄悄回来。
2. 作为家庭管理员，我想合并两个家庭宝宝后所有记录稳定归入目标宝宝，所以不会出现记录留在旧宝宝名下或同步分叉。
3. 作为准备退出这台设备的家人，我想在确认前看到「还有 N 条未同步」，并能先试一次同步，所以不会误丢当天的记录。
4. 作为被家庭管理员移除了设备的家人，我想在下次打开应用时被告知「何时被移除、清掉了多少条未同步内容」，所以损失是可见的而不是无声的。
5. 作为通过非 UI 入口写记录的使用者，我希望越界数值在本机落库时就被拒绝，所以不会产生"本地看得见、家里永远收不下"的僵尸行。
6. 作为在低内存手机上执行强制更新的家人，我希望 APK 下载边下边落盘，所以更新不因内存不足中断。

## Implementation Decisions

### D1 dismiss 耐久化（审查 G1）

- 新台账键：复用 `causal_transport_journal`，在 `core/database/.../causal/`（`dismissedSkipCacheKey` 旁）新增 `dismissedEntityCacheKey(entityType, clientUuid)`；`tombstoneLocallyWithoutPush`（`sync/.../engine/ReplicaSyncEngine.kt:2646`）在写本机墓碑的**同一事务**里写该键。
- 应用门：引擎各 root apply 路径在应用 **live 版本**（服务端 `deletedAt == null`）前查台账——命中且本机行处于 dismissed 墓碑态（`deletedAt != null && syncDirty == false && mutationId == null`）→ 跳过应用；来的是墓碑 → 照常应用（家庭删除照常收敛，「记录墓碑」语义不变）。`PullHole` 方向的 `dismissed-skip` 既有语义不变。
- 普查投影：census reconcile（活集 keys 对比）把命中 dismissed-entity 台账的 uuid 从服务器 keys 侧剔除，避免「家庭活集与服务器不一致」永久驻留。拉取洞方向（G7）不在本票范围。
- 收件箱投影：`ConflictResolutionCoordinator` 对命中台账的 clientUuid 不再出卡（防止 branched 摘要复活僵尸卡）。
- 文案：`LOCAL_DISMISS_CONSEQUENCE`（`UnresolvedInboxIds.kt:8`）追加一句「若家人之后修改了这条，它也不会再回到这台手机。」

### D2 宝宝合并 × 冻结信封（审查 G2）

- `mergeBabyProfiles`（`domain/.../family/BabyFamilyProfileCoordinator.kt:430`，已在 `sleepMutationMutex` + Room 事务内）在重绑每条 source 记录/计划**之前**，对该行调用既有 `abandonMutation` 终态回执路径（`sync/.../engine/CausalSettlement.kt:1339`；domain 侧经 `SyncPort` 新增窄方法，如 `abandonPendingLocalMutations(entities)`）——合并前已冻结的信封永不按旧 `baby_client_uuid` 发表；`syncDirty = true` 的行在下一周期以目标宝宝重新冻结（ADR-0022「恰一份不可变信封」不破坏）。
- 成员方向（本机孤宝宝并入家庭权威宝宝，`forceMemberRules`）行为不变（本就不得上传）。

### D3 破坏性路径守卫与清理收据（审查 G3）

- 主动退出：`FamilyScreen.kt:1274` 的 `LogoutCurrentDeviceDialog`（`MembersDevicesDialogs.kt:235`）接入既有 `SyncPort.pendingPublishCount(): Flow<Int>`（`SyncPort.kt:387`）。计数 >0 时显示「还有 N 条未同步，退出后将永久丢弃」，并提供「先同步再检查」动作（触发一次 Foreground 轮后留在弹窗刷新计数）；「仍然退出」始终可用——**确认而非阻塞**（NAS 离线时不得困住用户）。
- 被动撤销：`handleRemoteDeviceRemoved`（`RealSyncPort.kt:2570`）在执行清理**之前**持久化清理收据（时间、原因 `device_removed`、当时待发布计数）到 DataStore（`SyncPreferences`）；下次进入家庭/网络设置面一次性呈现「该设备于 X 被家庭管理员移除，已清理 N 条未同步内容」。清空语义本身不变（`sync-trusted-endpoint.md:306` 冻结合同）。

### D4 APK 流式落盘（审查 G12）

- `downloadAppUpdateApk`（`sync/.../backend/HttpSyncBackend.kt:1406`）改为写入调用方提供的 staging 输出流，边写边算 SHA-256，`MAX_SYNC_APP_UPDATE_APK_BYTES`（`:86`）按累计字节数强校验；`RealSyncPort` 消费侧（`:2239-2259`）改为文件路径 + 摘要，删除整包 `ByteArray`。更新校验链（sha256 → packageName/versionCode/签名三重校验 → commit）语义不变。

### D5 落库范围校验下沉（审查 G15 部分）

- `RecordMutationCoordinator.insertRecord` / 替换路径（`RecordMutationCoordinator.kt:938,968`）在 `requireCurrentPayloadDocument`（decode 闭键集）之后追加 `RecordPayloadCodec.validate`（数值域）。UI 侧先拦保留；生长测量域层校验不变（`GrowthMeasurements.kt:139` 已有）。

### D6 测试门禁修复（审查 G5）

- `core/database/src/androidTest/.../FreshDatabaseTest.kt:80` 的 `assertEquals(26, …)` 改为读取 `LeziDatabase.VERSION`，消除硬编码漂移。
- 偿还 0.5.1 票 03 记录的三处预存 JVM 失败：`BabyMoveSurfaceTest`（缺 `babyLocalLayout`）、`RealSyncPortCustomItemTest`（两条）、`ReplicaSyncEngineLocalWriteNoPullTest.wakeLiveAndTombstoneCommitDirectlyButDanglingOrMediaRootsWait`，恢复 `./gradlew test` 全绿。

### D7 文档修正（审查 G14，零运行时）

- `docs/spec/layers/server.md:45-46`：「已退役仍挂载」标注从 `/v1/pull` 移到 `/v1/push`（及 `/v1/media` PUT）；`/v1/pull` 是活跃增量页端点（wire §1.4、`lib.rs:979-980`）。
- `docs/spec/contracts/data-model.md`（如 UI 需要则 `ui.md` 同步）：补时间轴同 timestamp 决胜规则 `ORDER BY timestamp DESC, clientUuid DESC`（`TimelineWindowDao.kt:428`）。
- `docs/spec/contracts/causal-sync-wire.md` §16 实现注记：非 sleep record 缺 `end_timestamp` 键由服务端归一化为 null（golden 形状 `end_timestamp_null_injected` 已钉死；不改行为、不加字段、不抬 floor）。

## Testing Decisions

- **D1**：sync 引擎测试（fake backend，先例 `ReplicaSyncEngineCensusReconcileTest`）——dismiss 后服务器 rev 前进 → 不复活；服务器墓碑 → 本机保持墓碑；census 对账不再报 mismatch。domain 投影测试（先例 `ConflictInboxProjectionTest`）——台账命中 uuid 不出卡。
- **D2**：domain 测试（先例 `CareLogBabyProfileTest`）——合并前已有冻结信封 → abandon 被调用且旧信封不再提交；引擎 abandoned 逃逸（先例 `RealSyncPortCarePlanFulfillTest`）。
- **D3**：Compose 设备测试（先例 `H42ConflictDeviceAcceptanceTest`）——pending>0 时弹窗含计数与「先同步」；引擎测试——清理收据先于清库持久化（先例 `ProcessDeathRecoveryAcceptanceTest` 的 durable-before-act 模式）。
- **D4**：backend seam 测试（先例 `RealServerMediaReceiptFaultSeamTest` / `IsolatedLeziSyncServer`）——大 body 流式落盘、sha256 与既有值一致、超 cap 中断且 staging 清理。
- **D5**：domain 单测——越界 payload 落库抛错（先例 addRecord 既有异常断言）。
- **D6**：`./gradlew test` 全绿；`connectedDebugAndroidTest` 中 `FreshDatabaseTest` 与 `LocalDataContractMigrationDeviceTest` 通过。
- 验收基线照旧：`cargo` 三闸不涉及（零服务端代码）；发布票保留两台家庭机装 APK + 冒烟人工步。

## Out of Scope

- NAS 全量备份 / 设备全量导出（G4，第二批）。
- causal `updated_at` 未来上界（G6，需 ADR + 抬 floor）。
- census **拉取洞方向**（dismissed-skip）的终局语义（G7）——本票只做 dismissed-entity 方向的普查抑制。
- 证书到期预警（G8）、本机静态加密与 security-crypto 替换（G9，需 ADR）、property/仿真/fuzz 三件套（G10）、服务端限流补齐（G11）、Log 首页待处理入口与常驻离线指示（G13，需修订 `ui.md`/CONTEXT）。
- 后台同步（冻结决策，不做）。
- 「本机去掉」的撤销/恢复入口（本票只做决定持久，不提供 undo；恢复可见需未来显式 restore 入口，另行立项）。

## Further Notes

- 被对抗校验证伪的论断见来源审查 §5（如「invalid_domain 折叠是缺陷」「/v1/bundles 应退役」「共享密钥丢归属」均不成立），后续讨论勿重复立项。
- D1 的普查抑制是明示的产品折衷：本机被去掉的条目永久不参与活集对账；文案已同步披露。
- 术语红线（CONTEXT.md）：「待处理」仍是单一徽章 + 同一底栏，未对齐项不伪造 `conflict_id`；「本机去掉」不得表述为家庭删除；UI 不出现 "Owner"。
