# 04: W3 tip-skip——心跳证明无新数据时安静轮零数据请求

**What to build:** 前台返回轮若最近一次心跳已证明「服务端无新数据且目录未变」,且没有任何
恢复/清理/发表/缺图挂起,则整段跳过握手+必为空的 pull——安静前台返回轮从 3 个网络请求降到
0 个数据请求(心跳探针已付)。任何灾难恢复态一票否决,省电优化永不推迟修复动作。

**Blocked by:** None(can start immediately)

**Status:** landed (A 轨 2026-09-06)

- [x] 谓词(一票否决制):无待 generation 重对账 ∧ 无 pending local-clear(设备/成员/家庭删除恢复)∧ 重置回执账本空 ∧ 无待发布单元 ∧ 无缺图(全部缺图为已确认 404 时放行)∧ 心跳新鲜证明 ∧ 距上次成功整轮 ≤ 新鲜度窗
- [x] 新鲜度窗 = 心跳退避顶,常量与心跳策略共享来源;锚点 = 上次成功整轮完成时刻(心跳只做 head 未动的负向证明),QuietProof 以 volatile 形式存于端口层
- [x] 404 确认态耐久标记走既有 transport journal,无 schema 变更;一条永久 404 缺图既不钝化 tip-skip、也不停止补图重试
- [x] 故障代理测试:谓词全真时该轮 0 个数据请求;心跳 kick 的 NeedsSync 轮绝不被吞
- [x] 一票否决矩阵:五个恢复缝(待重对账/pending 清理/回执/待发表/缺图)各一条测试
- [x] 跳过轮不弄脏零进度熔断器、Syncing 状态行、最近健康时间
- [x] 实现注记含安全论证:跳过握手期间服务端版本 floor 仍由心跳端点强制
- [x] wire 合同 §1.5 增补 tip-skip 行为注记(消费三键的新合同);0.4.7 老服务端(无心跳能力)下行为与现状完全一致

## 落地记录(A 轨,2026-09-06)

- PORT(`RealSyncPort`):`@Volatile quietHeartbeatProof`（三键 + atMillis，只由心跳拍
  写入）+ `@Volatile quietFreshnessAnchorMillis`（每次成功轮盖章）+ `@Volatile
  quietProofIdentity`（身份切换才失效证明——普通 session 变更如 markSuccess 不得清证明）。
  `foregroundRoundSkipVeto` AtomicBoolean 由 kick 与零进度续跑置位、信号消费处随
  `pullRequested` 一并消费；`sync()` 拆为公有 seam（永不跳）+ `syncInternal(trigger,
  allowQuietSkip)`（仅 conflated consumer 的 Foreground 轮带跳过许可）。跳过点在
  `synchronizeJoinedSessionLocked` 顶部（syncMutex 内、recover 之后），短路返回，不触碰
  markSuccess / 熔断 / Syncing / lastServerHealthyAt / census 比对。
- **与 C9 字面偏离（有意）**：`fullCycleCompletedAtMillis` 不放在 QuietProof 对象里，而是
  独立 `quietFreshnessAnchorMillis`——成功整轮绝不能自己「铸造证明」，否则 0.4.7 老服务端
  （无心跳拍）连续两次前台返回后第二轮会被跳过，违反本票「老服务端行为与现状完全一致」
  红线。C9 的负向证明/能力证明分工由此更严格：拍=证明，轮=窗锚。
- ENGINE（`ReplicaSyncEngine`）：404 耐久标记走 `causal_transport_journal`，key
  `media-404:<clientUuid>`、payload `{"schema":1}`、epoch 0，无 schema 变更。写入点
  = `downloadMissingMedia` 404 分支（ENGINE:3197，单行小函数调用，便于与 B 轨 06 并行化
  改写合并）；清除点 = `adoptHistoricalLocalBytes` 成功 adopt 事务内（ENGINE:3242）；
  查询 = `isMissingMediaConfirmedNotFound`（ENGINE:2490）；tip-skip 缺图缝 =
  `hasQuietForegroundRoundBlockers`（ENGINE:2503，receipt 过滤后任一未确认 404 即否决）。
  补图循环语义逐字节不变（标记不影响 GET 重试）。
- **B 轨合并注意**：404 写入点在 B 轨所有的 `downloadMissingMedia` 区域内
  （ENGINE:3037 起），但本票例外条款允许的插入为 3197 行单行调用；06 工单并行化改写时
  保留该行即可。
- 跳过轮仍会走 sync 尾部的 app-update piggyback（B 轨 1884-1902 区域未动，节流由 05 票
  门控）——跳过轮本就是用户真实触发的轮，与「真实触发仍检查」一致。
- 测试（`RealSyncPortTipSkipTest`，11 条）：安静轮 0 数据请求（fake backend 观察口径，
  C11 允许）、kick/零进度续跑不被吞、五缝否决矩阵、404 全确认放行 + 单条未确认否决 +
  标记写入/adopt 清除、窗过期强转全轮、老服务端永不跳、跳过轮状态不脏
  （status 采集器 + lastServerHealthyAt + failureKind）。
- 证据:`./gradlew :sync:test` 全绿（含全部既有 heartbeat/family/clear 测试族）。

