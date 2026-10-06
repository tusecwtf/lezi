# 07: W5 心跳放宽 60s/300s + 共享常量一致性 + 验收改写

**What to build:** 心跳基线 30s→60s、无变化退避顶 120s→300s(±20% 抖动保持),安静期探针数
减半以上、无线电更早回低功耗;降级梯 30s/120s/600s 一字不动(那是故障恢复时延)。
「对端写入可见性上限」验收显式改写为「心跳间隔 + 一轮」。

**Blocked by:** 04(tip-skip 新鲜度窗=退避顶的一致性断言需要窗常量已存在)

**Status:** landed (A 轨 2026-09-06)

- [x] 心跳策略常量更新:基线 60s、退避顶 300s;±20% 抖动与回前台防抖语义保持
- [x] 显式断言测试:降级梯 30s/120s/600s 不变(防顺手全改)
- [x] tip-skip 新鲜度窗 = 心跳退避顶的一致性断言(共享常量来源,防两处漂移)
- [x] 策略测试数字全部更新(节奏、倍增、抖动界)
- [x] 文档同步:wire 合同 §1.5 限流子弹的节奏数字;服务端限流注释的「30s 基线」假设文案;可信端点合同中心跳叙述不残留旧数
- [x] 0.4.8 验收第 1 条改写落档:「对端写入可见性上限 = 心跳间隔 + 一轮」
- [x] 服务端限流默认无需改的余量结论复核(~15×)记入工单

## 落地记录(A 轨,2026-09-06)

- 常量:`SyncHeartbeatPolicy.BASELINE_INTERVAL_MILLIS` 30s→**60s**、
  `MAX_NO_CHANGE_INTERVAL_MILLIS` 120s→**300s**;`JITTER_FRACTION=0.20` 与
  `FOREGROUND_FIRST_BEAT_DEBOUNCE_MILLIS=8s` 一字不动。倍增表变为
  60s→120s→240s→300s(cap 在第 4 档绑定)。
- **服务端限流余量复核(DEFAULT_HEARTBEAT_RATE_LIMIT=30/60s 不动)**:0.5 安静期合法
  最坏速率由降级梯首档 30s 决定 = 每 60 秒窗口 2 次;余量 = 30/2 = **15×**(健康期
  60s 基线/300s 退避下更低)。结论:服务端默认无需改。
- 一致性断言:`SyncHeartbeatEngineTest` 新增
  `degradedRetryLadderThirtyOneTwentySixHundredStaysUntouched`
  (retryDelayMillis 30s/120s/600s 逐档断言)与
  `ticketSevenConstantsAndTipSkipWindowShareTheBackoffCap`(60s/300s/±20%/8s +
  倍增表逐档)。tip-skip 侧:`RealSyncPortTipSkipTest.expiredFreshnessWindow...`
  的窗从字面量改为直接读 `SyncHeartbeatPolicy.MAX_NO_CHANGE_INTERVAL_MILLIS`
  ——窗常量 07 后 300s,测试自动跟随,漂移不可能。
- 策略测试数字:`SyncHeartbeatEngineTest`(倍增表/抖动界/各 hook 重调度全量换算)、
  `RealSyncPortHeartbeatLoopTest`(基线 30s→60s 的全部 advance/断言;cap 达成从
  3 拍改 4 拍;degraded 30/120/600 与 lease 30s 保持)。
- 文档:wire §1.5 限流子弹(60s 基线/300s 退避/降级梯不变/最坏 2 次每窗/余量 15×);
  `layers/sync.md` §5.1(60s/300s + 单一来源注记 + 「对端写入可见性上限 = 心跳间隔
  + 一轮」验收改写句);`tools/lezi-sync/src/lib.rs` 限流注释(仅注释,常量与门禁不动,
  cargo fmt/test/clippy 全绿);`sync-trusted-endpoint.md` 心跳叙述核对无旧数
  (其 30s 租约/30s·2min·10min 退避/单轮 120s 预算均为可用性与周期预算数字,非心跳节奏)。

