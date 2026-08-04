# 02 — Android 冻结快照与裁决收敛周期

**What to build:** Owner/Member 完成 pull 后冻结待对账原子单元，批量取得服务端 verdict，
以 `(clientUuid, updatedAt, canonicalHash)` CAS 应用终态，只为 publish verdict 生成临时
bundle；generation 变化走全量快照重建。

**Blocked by:** 01 — 服务端批量 head-by-UUID 权威裁决。

**Status:** complete

- [x] 同一周期严格执行 authenticated pull → freeze → reconcile heads → settle → publish → barrier
- [x] confirmed 精确清除；adopt_remote 原子采用 canonical remote；publish 仅 commit 后清除
- [x] incomplete response、超时、5xx、generation drift 不清任何未经证明的用户事实
- [x] concurrent local edit 使旧 verdict CAS 失败并进入下一周期
- [x] commit-response 丢失/进程死亡后，下周期以 remote head confirmed 收敛
- [x] full snapshot 仅在 authority proof 失效时使用，普通写不扫描整个家庭
- [x] 无持久 payload/outbox；现有 dirty 语义改为待对账而非无条件待发布
- [x] RealSyncPort 高层测试覆盖 Owner/Member、full resync、crash/retry 与 peer visibility
