---
status: accepted (0.4.0 runtime activated by hardening ticket 27; production cutover pending)
---

# Commit-first 与 choice-only ConflictSnapshot

0.4.0 把普通因果发表收缩为一次幂等 commit，并把完整 ConflictSnapshot 作为冲突读取、
离线缓存与 resolution 的唯一事实单元。字段、枚举与 closed shape 只以
[`causal-sync-wire.md`](../prd/causal-sync-wire.md) 和共享
[`conflict-v2-golden.json`](../../config/conflict-v2-golden.json) 为准。

本 ADR 保留 [ADR-0019](./0019-server-validates-constraints-not-care-truth.md) 的服务器约束边界与
[ADR-0021](./0021-wake-observation-and-nondestructive-duplicate-groups.md) 的事实模型。

## 取代范围

- 取代 [ADR-0020](./0020-stable-projection-immutable-versions-and-branches.md) 中普通发表必须先
  reconcile，以及 resolution 由客户端同时提交 choices 与完整 rebuilt root/media 的部分。
  ADR-0020 的不可变版本、稳定投影、因果 base、耐久 branch、显式 resolution 与媒体原子性保留。
- 窄化取代 [ADR-0016](./0016-reconcile-before-ephemeral-publish-planning.md) 对所有 durable pending
  payload/envelope 的禁令：每个 pending mutation 可以有**恰一份**不可变冻结传输 envelope，
  绑定 mutation ID、base、canonical request hash 与媒体 spool/receipt。Room product facts 仍是
  领域真相；envelope 不是第二份可编辑事实、事件日志或通用 outbox。
- 0.3.13 source runtime 在各迁移票完成前仍可使用旧 reconcile 路径；它只服务升级源，绝不是
  0.4.0 dual-read/downgrade。H26 独占最终删除。
- H26 删除 reconcile 时必须保留 reset/full-resync 的用户意图边界。H27 前允许在现有
  `conflict_detail_cache` 以一个 closed private key 暂存**恰一个** replica-reset receipt；它与五类
  root 的 pending capture/requeue 同一 Room 事务写入，恢复成功即删除，不进入冲突收件箱，也不是
  产品事实、事件日志或通用 outbox。H27 独占把该 receipt 迁入 Room 28 专用 transport 存储。

## 决策

### 1. 普通发表 commit-first

认证、能力与 endpoint trust 成立后，客户端从 Room facts 冻结一次 mutation；媒体先进入应用私有
不可变 spool 并取得服务端 durable receipt，然后只提交一次 commit。稳定 mutation ID、canonical
request hash、因果 base 与持久终态承担幂等性。相同请求 replay 返回原
`accepted|merged|branched` 加独立 marker；本地 publish 不推进 pull cursor。

### 2. 服务端独占重建结果

ConflictSnapshot 完整保留 stable、全部 branch 的 root/media/deleted/base/provenance，及不相交的
auto/conflict path outcomes。客户端 resolution 只提交 snapshot token、resolution mutation ID 和
每条冲突路径的 opaque choice ID。服务器从 receipt 重建 root/media/deleted，并通过普通 commit
共用的 canonical/domain/media validator 与 stable projection builder 后才 CAS；客户端不得提交
`resolved_root`/`resolved_media` 或改写自动合并字段。

### 3. Token、choice 与 restore

snapshot token 是持久 receipt 支撑的随机 opaque 标识，绑定 family/root、stable、完整 branch set、
分页视图、合同版本与 expiry。它可重复 detail/paging/resolve 且跨重启稳定；state 变化或 expiry 后
fail closed 并要求 refresh。choice ID 在一个 snapshot/token 的全部页和重启间稳定，仅是选择输入，
label 不是身份。

纯 tombstone restore 只能选择删除 mutation 声明的直接 `base_version`。该 base 必须是完整 live
root + media 且 bytes 可用；不搜索祖先、不猜 parent、不在 restore 同时编辑字段。

### 4. 单一能力与升级边界

新能力字面量为 `causal_sync_v2`。Android/server 0.4.0、code 21、Room 28、本地数据契约 5、
server schema 13 与同步 floor 21 是一个发布身份；H27 前两端均不得 advertise/接受。Room 27→28
原地保留全部事实与信任状态；server 11/12→13 只经 ADR-0013 的独立 copy-out offline-migrate。
普通 CD 不迁移；生产切割仍须 release 09 的新维护窗确认与 rollback 证据。

## 拒绝的方案

- **保留 reconcile 作安全前置：** 重复上传同一完整意图并扩大丢响应状态机；幂等 commit 已拥有
  所需证明。
- **客户端提交 choices + rebuilt result：** 两端成为竞争权威，额外输入可绕过 canonical validator。
- **自包含签名 token：** 难以撤销 state-changing snapshot，且分页容易把局部分支误作 full-set。
- **按 pairwise/UUID/到达序合并：** N 方同路径结果可能被先到分支静默选胜。
- **通用 durable outbox：** 重新引入可漂移的第二份产品事实；冻结 envelope 只能由当前 mutation
  建立、终态清理并由 Room facts 恢复。

## Consequences

- Store 仍是深 façade；merge、snapshot、resolution rebuild 与 validator 位于一个深 conflict module。
- Android 持久化完整 snapshot 和一个 mutation 的冻结 envelope，不让 Compose 解析 transport JSON。
- external R12/R17/R18/R19 分别独占资源准入、bounded loader、receipt/paging 与 retention；本 ADR
  只冻结它们共同消费的 shape，不复制实现所有权。
- 本 ADR 与 H01 只冻结合同/fixtures，不证明 runtime、APK、schema migration、设备或 NAS 已完成。
