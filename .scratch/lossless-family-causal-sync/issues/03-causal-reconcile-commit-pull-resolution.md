# 03 — 因果 reconcile、atomic commit、pull 与 resolution API

**What to build:** 在 Store 深 façade 和现有认证 HTTP 同步面上实现冻结的因果协议。reconcile
验证 base/mutation 并给出 dry-run；commit 原子 accepted/merged/branched；pull 返回稳定版本与
冲突摘要；detail/resolution 按分支版本 CAS。移除新 capability 下的通用 `updated_at` winner、
服务端近邻裁决和 `neighbor_losers`。

**Blocked by:** 02 — v12 持久化和迁移合同。

**Status:** ready-for-agent

- [ ] reconcile 对每个完整原子单元返回 `confirmed|publish|conflict_preview|rejected`，并绑定 generation、稳定 `version_id`、请求哈希与有界 authority proof
- [ ] commit 重新验证 proof/base/head，原子返回 `accepted|merged|branched`、稳定版本、完整稳定 root/media manifest 和冲突 refs；不返回媒体字节
- [ ] 同一 mutation ID 精确 replay 返回原回执；内容、base、root identity 或 media manifest 漂移均拒绝
- [ ] 三方合并只合并共同 base 上不相交 canonical JSON leaf paths；相同路径相同结果无冲突，相同路径不同结果建立分支
- [ ] 数组/标量按冻结合同原子比较；独立 media UUID 修改可合并，同一媒体 delete/edit、hash/association 漂移形成整根分支
- [ ] current-base delete accepted；同 base delete/edit 无论到达顺序都保留双方；已稳定 tombstone 的普通 stale replay 不复活
- [ ] `branched` 在回包前已经持久保存完整 incoming branch 和媒体；稳定投影保持合同指定版本，pull 摘要可发现冲突
- [ ] conflict detail 只向有家庭读取权的 principal 返回有界 base/stable/branches、冲突路径与媒体 manifest；日志/错误不含媒体字节或凭据
- [ ] resolution 仅允许作者或 Owner（按实体既有管理 ACL）并要求 expected stable + 完整 expected branch set CAS；竞态返回最新摘要且不部分写
- [ ] resolution 保留已自动合并字段，只应用真正冲突路径选择；显式恢复 tombstone 产生新稳定版本并记录 resolver/provenance
- [ ] pull 页面为每个稳定原子根返回 opaque `version_id` 和有界 conflict summary；旧 `rev` cursor 仍单调且 branch-only 写也能让相关摘要被后续 pull 发现
- [ ] 服务器继续盖章 Record author/Wake observer，执行 Baby/Record/CarePlan/CustomItem ACL、引用、媒体和 payload allowlist；合并不能绕过字段/身份不变量
- [ ] 新 capability 明确破坏性实体/shape；缺 capability 或低于 minSupported 的客户端 fail closed，不走 LWW 兼容降级
- [ ] 新 capability 下不调用 neighbor adjudication，不生成近邻 tombstone，不返回/消费 `neighbor_losers`；不同 UUID live 记录全部保留
- [ ] Store 与 HTTP 测试覆盖 merge/conflict/delete/media/idempotency/lost response/process restart/CAS race/auth/ACL/bounds/error mapping
- [ ] Rust fmt/test/Clippy 全门通过，且隔离真实服务协议 smoke 能创建、拉取、分支和 resolve 一个原子根

