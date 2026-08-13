---
status: partially superseded by ADR-0019/0020 for 0.3.13+ causal generation — LWW head vocabulary and “adopt remote whole row” defaults; retained: Room-first, settle-before-publish skeleton, bounded authoritative proofs, atomic media, no durable outbox
---

# 家庭服务器权威裁决必须终结本机待对账修改

## 0.3.9–0.3.12 已交付范围（历史合同）

常规家庭同步在增量 pull 后使用经过认证的批量 head-by-UUID 裁决，generation 证明失效时才
退回全量实体快照。服务端复用 atomic commit 的 LWW、ACL、tombstone 与证据冻结规则，为每个
冻结原子同步单元给出远端 head/absence 与确认、发布、采用远端或永久拒绝 verdict；客户端再按
本机内容性质把永久拒绝终结为本机保留或技术清理。完整周期结束时该冻结集不得残留未裁决
dirty，浅状态只统计未终态原子单元。

这部分取代 ADR-0016 对新增对账协议的禁止，但保留 Room 本地优先、pull-before-plan、临时
发布计划、原子照片包和修订 CAS。选择批量 head-by-UUID 而不是每次全家庭扫描，是为了让
远端存在/缺失有证明且工作量随本机待对账集增长；全量快照仍作为 generation/cursor 恢复边界。
有用户意义但暂不能进入家庭权威图的事实转为明确本机保留内容，不以永久 dirty 假装可发布；
只有可证明无业务所有者的同步/媒体残留允许自动清理。

## 0.3.13 规划 supersession（非已交付）

[ADR-0019](./0019-server-validates-constraints-not-care-truth.md) 与
[ADR-0020](./0020-stable-projection-immutable-versions-and-branches.md) 保留「冻结待对账
修改必须取得权威终态」与「先证明再发布」骨架，但 **废止** 本 ADR 作为目标合同的：

- 以 `updated_at` LWW 整行比较作为通用裁决；
- reconcile verdict 中的 `adopt_remote` 作为默认同 UUID 收敛手段；
- 将 head-by-UUID 证明等同于无损事实保留。

新能力代：因果 `base_version` + `mutation_id`，reconcile
`confirmed|publish|conflict_preview|rejected`，commit `accepted|merged|branched`，
稳定投影 + 不可变版本/分支。实现完成前，运行时仍以本节「已交付范围」为准；不得把规划
叙述写成已上线行为。
