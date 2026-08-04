# 03 — 本机保留、技术清理与诚实待同步投影

**What to build:** 把无法进入家庭权威图但有用户意义的事实转为明确 local-only；只删除
可证明无独立业务意义的技术残留。浅状态统计未终态的 atomic units，不再求和所有 dirty 行。

**Blocked by:** 02 — Android 冻结快照与裁决收敛周期。

**Status:** complete

- [x] 单一家庭权威宝宝时自动 rebind；多宝宝/无宝宝时 meaningful facts local-only 且不计家庭 pending
- [x] 用户显式 rebind 后 local-only facts 重新进入待对账并可发布
- [x] remote exists 的永久 ACL loser 采用远端；remote absent 的 meaningful fact 不静默删除
- [x] remote_absent_rejected 按本机内容性质唯一映射为 keep_local_only 或 discard_technical
- [x] orphan media、无 owner row、deleted Baby live avatar、冗余 tombstone 有确定 cleanup/repair
- [x] missing media bytes 产生可审计 repair/local-only/discard，而非永久重试
- [x] pending 以 Baby+avatar、Record+photos、CarePlan+photos、CustomItem、Candidate 为单位
- [x] 系统日历、local-only、cleanup marker、已终态 verdict 不进入家庭 pending
- [x] 完整安静周期后 pending=0；健康成功但 reconcile 未完成不能显示已同步
- [x] UI/JVM/Room 行为测试覆盖文案、计数和不丢用户事实
