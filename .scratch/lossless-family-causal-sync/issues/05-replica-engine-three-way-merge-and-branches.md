# 05 — ReplicaSyncEngine 通用三方合并、分支 CAS 与稳定投影

**What to build:** 让 `ReplicaSyncEngine` 在所有可变原子根上消费因果 API：冻结 base/mutation，
校验 reconcile proof，发布 accepted/merged/branched，CAS 应用完整稳定 root/media/version，并把
本机 mutation 终结为 confirmed、stable 或 visible conflict。pull 只应用稳定投影和冲突摘要，
不再按 `updatedAt` 整行覆盖 dirty。

**Blocked by:** 03 — 因果 API；04 — Room 27 本机状态。

**Status:** ready-for-agent

- [ ] 冻结原子单元包含完整 desired root/media、`baseVersion`、`mutationId` 与内容哈希；同周期内不可被 DAO 重读拼成不同包
- [ ] reconcile 每个 key 都有且只有一个匹配 proof；缺失、重复、额外 key、未知 disposition、generation/head/hash 漂移整批 fail closed
- [ ] `confirmed` 仅在服务器证明 exact mutation 已稳定/已记录时 exact CAS 清 pending；健康或请求成功本身不能清状态
- [ ] `accepted`/`merged` 应用回包的完整 stable root/media/version，而不是猜测本机 payload 已成为 head
- [ ] `branched` 原子保存冲突摘要/refs、服务器 stable projection 和 exact local branch receipt；pending 变为 unresolved conflict，不继续盲重试
- [ ] pull 对 clean/root 采用稳定版本，对 dirty/root 以 base/version 判断后进入 reconcile；任何 remote `updatedAt` 大小都不能直接覆盖未终结 mutation
- [ ] stable tombstone、concurrent delete/edit、显式 restore 与 ordinary stale replay 按服务端因果结果投影，不再有 Android 自创“墓碑永胜”赢家
- [ ] root、媒体 manifest、publication receipt、baseVersion、mutation 状态与 conflict/source refs 在一个 Room 事务中 exact CAS
- [ ] 同步期间用户产生新编辑导致 CAS mismatch 时，旧回包不覆盖新内容；已保存的 stable/conflict evidence 以安全方式留给下一周期关联
- [ ] canonical media 在可见前通过现有原子下载/物化 seam；设备本地 path 不上 wire，分支/来源仍引用的字节不回收
- [ ] process death 在 reconcile 后、commit 后、媒体物化中、Room CAS 前后的下一周期都能幂等恢复，不重复 branch 或丢 mutation
- [ ] generation drift/full-resync 重建稳定投影与 conflict summaries，但保留所有本机 mutation、local-only 内容、branch receipts 和媒体
- [ ] pending 浅状态按原子根分类为待对账、待 commit、未解决冲突、本机保留和技术清理；不能把 conflict 计成无限待发布
- [ ] 继续复用 `SyncPort`/`RealSyncPort`/ReplicaSyncEngine/atomic media 深 seam；不新增按实体类型的平行 uploader 或持久 payload outbox
- [ ] recording backend 表驱动测试覆盖所有 root 类型、merge/branch/delete/media、lost response、process death、CAS race、generation recovery 与 peer pull
- [ ] 相关 sync/core JVM tests、模块编译和隔离真实服务最小 accepted/merged/branched smoke 通过

