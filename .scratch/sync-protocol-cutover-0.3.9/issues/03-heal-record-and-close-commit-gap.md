# 03 — 重对账修复历史 Record 并封闭未来提交窗口

**What to build:** 用全量重对账自动重新提交管理员 Room 中仍然存在的历史 Record，并让服务器只在 CarePlan 与 Record 都完整、合法时公开履行关系。未来即使进程在两个 bundle 之间终止，也只留下可重试的延后状态。

**Blocked by:** 02

**Status:** complete — accepted on `ff80edbf`; live missing fact remains honestly deferred

- [x] 新代际重对账会冻结并裁决旧管理员 Room 中的目标 Record，即使旧发布回执曾将它标为干净；不得依赖用户再次编辑或手工点同步。
- [x] Record 到达时，服务器在同一家庭串行边界和 SQLite 事务内重新验证 ACL、LWW、不可变 pair、时间、已删除 CustomItem 的历史许可及媒体完整性。
- [x] 只有完成验证的 CarePlan 与 Record 关系才进入公开图；FulfillmentCandidate 继续在两者完整后发布，第二客户端可 pull 到相同权威结果。
- [x] 模拟 CarePlan 提交成功后、Record 提交前进程终止：服务器保留不可见延后状态，重启和精确重试幂等，且无关 payload 始终可同步。
- [x] 若 Record 已从所有授权客户端消失，服务器持续保留延后证据、正常同步其它数据，并保持诚实 pending；不得合成护理事实或显示伪完成关系。
- [x] 一个聚焦的 0.3.8→0.3.9 fixture 同时证明干净历史保留、旧半套履行自动修复、媒体字节保留、旧 500 消失和第二客户端可见。
