# 03: W2 客户端 census 谓词复用 + 重走强制重建

**What to build:** 客户端收敛校验从「每轮 head 处 7 表全量重算」改为「安静轮(本轮零应用、
零本地写、零待发布、零待重对账)直接复用上次 census;否则恰一次重算」。任何 cursor=0
全量重走结束时无条件丢弃缓存重算一次,保证比对值永远可追溯到真值——缓存漂移的最坏结局
是「恰一次额外重走」,不是永久假诊断。不引入 Room InvalidationTracker。

**Blocked by:** None(can start immediately)

**Status:** landed (A 轨 2026-09-06)

- [x] 零应用谓词:全部页实体空 ∧ deferred 未解决集空 ∧ cursor 未动 ∧ 无待发布单元 ∧ 无待 generation 重对账 → 复用上次 (generation, census),零本地重算
- [x] 任一子句不成立 → 恰一次全量重算(现状语义)
- [x] 谓词矩阵测试:任一子句为真即触发重算
- [x] cursor=0 重走(失配修复/409/诊断路径)结束时强制丢弃缓存全量重算
- [x] 竞态行为测试:pull 进行中本地写 → 假失配 → 恰一次重走 → 无风暴、无重复诊断
- [x] 复用条目在 generation 变化后失效
- [x] 现有 census 对账测试族全绿;deferred 行闭环行为逐字节保持

## 落地记录(A 轨,2026-09-06)

- 实现:`ReplicaSyncEngine` 内 `localCensusReuseCache`(进程内 `(pullGeneration, entries)`,
  单轮互斥下无需同步);`pullAllPages` 在 `reconcileLiveCensus` 分支计算零应用谓词
  (observedEntityKeys 空 ∧ deferredUnresolved 空 ∧ cursor/generation 未动),传入
  `reconcileLiveCensusAtHead`;复用判定 = 零应用 ∧ `!hasPendingGenerationResync()`
  (`hasPendingPublishUnits` 早退保持原位)。
- A2 强制重建:census 失配 rewalk 成功结束 → `localCensusReuseCache = null` + 恰一次
  fresh 重算(诊断快照仍记录 rewalk 前的 `mismatched` 明细,字节保持);409/authority 的
  `recoverFullResync` 结束 → 同款强制重建。诊断路径(cycle abort)不改缓存——下一轮谓词
  判定自然决定复用/重算,不额外引入失效点。
- 非安静轮(谓词任一子句真)的重算会刷新缓存;LocalWrite(causal)无 pull 无比对,不改
  缓存——本地发表后服务端 head 推进使下一轮 pull 回声非空,谓词自然失败并刷新,无假失配
  风暴(设计 §1.2 残余竞态 = 本地写落在 pending 检查与重算之间的窗口,恰一次重走自愈)。
- 测试:`ReplicaSyncEngineCensusReuseTest`(10 条):安静轮复用零 rewalk、谓词矩阵
  (实体非空/cursor 推进/deferred 未决/待发表单元/generation 重对账标记各一条)、
  generation 失效、rewalk 后强制重建(下一安静轮零 rewalk 佐证)、pull 中本地写竞态
  (恰一次重走/无重复诊断)。观察口径 = 轮形状(pull cursors、rewalk、耐久诊断),不测内部。
- 证据:`./gradlew :sync:test` 全绿(首跑两条 RealSyncPort 全套顺序flake——
  DisasterRestore/FamilyWire——隔离复跑与全套复跑均绿,与本改动无关)。

