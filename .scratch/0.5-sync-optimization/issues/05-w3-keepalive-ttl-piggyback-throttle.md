# 05: W3 keep-alive 90s TTL + 更新检查 piggyback 节流

**What to build:** 轮末不再主动拆光全部 keep-alive 连接(改 90s 空闲 TTL,身份驱逐语义原样
保留),下一轮不再重付 TCP+TLS;app 更新发现从「每个心跳触发的成功轮都多发一次请求」节流为
「用户真实触发,或距上次成功检查 >1h 的既有轮」,不新增任何唤醒源。

**Blocked by:** None(can start immediately)

**Status:** ready-for-agent

- [x] 轮末全拆改 90s 空闲 TTL 释放(小于平台连接池空闲超时,不双花);TTL 过期触发 disconnect
- [x] origin+SPKI 身份变化立即驱逐的语义原样保留(TOFU 安全缝);冲突路径 5 处轮外拆除维持现状
- [x] keep-alive 测试族扩展:TTL 过期拆除、身份变化立即驱逐、心跳把手跨轮存活
- [x] piggyback 门控 = 用户真实触发(回前台/下拉)∨(距上次成功检查 >1h ∧ 本轮因其它原因已发生);心跳/续跑轮不再逐轮检查
- [x] AUDIT-20260801-P1-01 回归组全绿(判据只应用于仍 piggyback 的轮;仅成功后分类、失败不拆壳)
- [x] 更新测试族断言:心跳轮不发检查请求、真实触发发、>1h 定时门发
- [x] 文档落档:平台规格自更新触发行 + 可信端点合同的发现行,注明门控语义与「不新增唤醒源」

## 落地记录(2026-09-06,B 轨)

**keep-alive 90s TTL**
- `SyncBackend` 新增 `releaseForegroundKeepAliveToIdleTtl()`(默认 no-op);`ReplicaSyncEngine.synchronize`
  finally 改调它。原 `releaseForegroundKeepAlive()` 保持**立即拆除**语义,只服务于 PORT 五处冲突路径
  (fetchConflictSnapshot/resolveConflict/withdrawConflictBranches/declareSourceRelation/resolveSourceRelationGroup)。
- `HttpSyncBackend`:惰性过期实现(零新线程/零唤醒源)——`releaseForegroundKeepAliveToIdleTtl` 只把
  `undetachedKeepAliveIdleSinceElapsedMillis` 置为单调钟(`familyHttpClock` 的 elapsedRealtime)基线;
  下一次认证请求的 `prepareKeepAlive` 发现空闲 ≥90s(`IDLE_KEEP_ALIVE_TTL_MILLIS=90_000`)才整体驱逐。
  同身份且未过期 → 复用不驱逐;任意新 2xx 入池时基线归位 null(轮内永不过期);`evictUndetachedHandlesLocked`
  清基线。**origin+SPKI 身份变化立即驱逐原样保留**(finishKeepAlive/prepareKeepAlive 既有分支未动)。
- 测试(`HttpSyncBackendKeepAliveTest`,13→17):TTL 内跨轮存活、TTL 过期下轮请求前拆除、TTL 基线随每轮
  重启(轮 2 的把手不为轮 1 的流逝时间背锅)、身份变化在 TTL 窗口内仍立即驱逐;既有 13 个测试(含立即
  拆除、SPKI/origin 换身份驱逐 4 个)全绿未改。

**piggyback 节流**
- 门控位置不变(`RealSyncPort.sync` 1884-1902 一带,局部小改,便于与 A 轨 tip-skip 合并):
  在原 `非 LocalWrite ∧ 非 CUR 已处理 ∧ mapped.isSuccess` 之上追加
  `shouldPiggybackAppUpdateDiscovery(trigger)` = `PullToRefresh` ∨ `requestSync` 实触发旗标 ∨
  (耐久 `lastAppUpdateCheckedAt` 为空 ∨ 距今 ≥ `APP_UPDATE_PIGGYBACK_MIN_INTERVAL_MILLIS`=1h)。
- 实触发判定:只有 `requestSync(非 LocalWrite)`(回前台、冲突解决后续同步、向导刷新)置
  `realUserSyncTriggerRequested`;心跳 kick / `retriggerForegroundCycle` 与消费者合流后共用
  `Foreground` 触发值,靠该旗标区分,不经过 requestSync 的 kick/续跑轮受 1h 门约束。
  直接 `sync(Foreground)` 视为非用户轮(生产中只有 conflated 消费者这样调用)。
- 耐久存储:`SyncPreferences.lastAppUpdateCheckedAt`/`saveLastAppUpdateCheckedAt`(DataStore key
  `sync_app_update_checked_at`,零 Room schema);仅在 piggyback 检查**完整成功**后落时刻
  (`discoverAppUpdateBestEffort` 内 classify 之后),失败/门拒绝不武装窗口。
- 测试(`RealSyncPortAppUpdateTest`,44→49):非用户轮窗口内不重发;`PullToRefresh` 窗口内仍发;
  `requestSync(Foreground)` 消费者轮在窗口内仍发;>1h 后非用户轮补发;检查失败不武装窗口(下轮重试);
  既有 LocalWrite 不发、AUDIT-20260801-P1-01 回归组(530/566)全绿未改。唯一编排适配:
  `upToDateHandshakeClearsOptionalAppUpdateBanner` 的第二轮改走 PullToRefresh(原第二行为非用户轮,
  新合同下被节流;清壳语义改由真实触发验证)。

**验证**:`./gradlew :sync:test` 全绿(debug+release 两 variant)。
**偏离**:无字面偏离;「实现自选」选了惰性过期(比显式定时器更少机制,天然满足「不新增唤醒源」)。
