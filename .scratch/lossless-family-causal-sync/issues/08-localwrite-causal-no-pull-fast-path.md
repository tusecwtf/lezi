# 08 — 因果协议上的 LocalWrite 无 pull 快速路径

**What to build:** 在因果 accepted/merged/branched 已能无损终结后，兑现现有 LocalWrite
`pull=false` 计划：前台、可信 endpoint、健康租约允许时，冻结当前 dirty 原子根直接
reconcile/commit，不先增量 pull、不推进 pull cursor。完整周期继续拉取其它家庭变化。

**Blocked by:** 03 — 因果 API；05 — ReplicaSyncEngine 因果 settlement。

**Status:** complete

- [x] 所有现有家庭写路径仍先提交 Room、立即返回 UI，再发合并的 `LocalWrite` 通知；网络工作不进入领域事务
- [x] LocalWrite 计划确实不调用 pull，只冻结当下 dirty atomic roots 并执行 authenticated causal reconcile/commit
- [x] 快速路径与完整周期共用同一 base/mutation、三方 merge、branch、ACL、atomic media、proof 和 exact CAS seam，不旁路裸 push
- [x] remote stable 已变化时结果只能 confirmed/accepted/merged/branched/rejected；不得按 `updatedAt` 覆盖任一端
- [x] 多次短时写信号合并，冻结时取最新完整内容 epoch；已冻结 mutation 后的新编辑进入下一周期
- [x] LocalWrite reconcile/commit 的 server cursor 不写入 incremental pull cursor，也不伪造已拉取远端 revisions
- [x] 之后的前台、网络恢复、下拉刷新和普通计划仍完整 pull，并能取得快速路径期间其它成员的无关变化
- [x] App 后台不探测/同步；后台写保留本机 mutation，回前台完整周期收敛
- [x] 健康租约内不重复匿名 probe；租约外继续既有 availability policy、前台 gate 与退避，不扩大 NAS 请求风暴
- [x] transport/capability/generation/proof/commit/parse/media/CAS/cancellation 失败都保留可恢复状态；`branched` 则准确转成 visible conflict
- [x] 快速路径不触发 APK metadata 检查、不增加同步按钮、spinner、toast 或保存阻断面
- [x] 公共同步 façade 操作序测试证明 `reconcile → stage/upload/commit` 且无 pull；不绑定 Channel、Job 或 mutex 私有实现
- [x] 对照测试覆盖 accepted、merged、branched、unreachable、lease expiry、background、并发本机编辑与后续完整 pull
- [x] 相关 RealSyncPort/ReplicaSyncEngine/atomic-media/trigger tests 和模块编译通过
- [ ] 隔离真实服务证明 LocalWrite 无 pull 发布后，第二客户端可见；原客户端下一完整周期仍拉到另一成员无关写入

**Notes:** Engine applies LocalWrite `SyncPlan.pull=false` **only when**
`supportsCausalWire()` is true; without causal capability LocalWrite still pulls (no faster
LWW). Causal path: freeze → reconcile → optional media preimage → commit; no pull cursor
advance. Full cycles retain pull. members()+self-membership convergence is an intentional
LocalWrite precondition (not entity pull). Unit coverage:
`ReplicaSyncEngineLocalWriteNoPullTest`, `RealSyncPortLocalWriteNoPullTest`. Tracker status
"complete" means engine/unit acceptance for this ticket; isolated two-client real-server
LocalWrite smoke remains ticket 09 (checkbox still open).

