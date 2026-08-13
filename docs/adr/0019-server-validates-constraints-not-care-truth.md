---
status: accepted (0.3.13; runtime landed via lossless-family-causal-sync tickets 02–08; production cutover residual ticket 09)
---

# 服务器验证约束，不裁决护理真相

家庭服务器认证成员、执行 ACL、盖章不可变作者/观察者、校验引用与媒体完整性、强制
协议有界与同步顺序，并执行家庭明确授权的管理操作。它 **不得** 用墙钟、Owner 身份、
到达序、字段完整度或近邻启发式，替家庭判断哪一条护理观察或不同 UUID 事件「更真」。

术语见根目录 [`CONTEXT.md`](../../CONTEXT.md)。**字段/枚举/API shape** 见
[`docs/prd/causal-sync-wire.md`](../prd/causal-sync-wire.md)（唯一 wire 权威）。
产品数据叙述见 [`docs/prd/data-model.md`](../prd/data-model.md)。本决策与
[ADR-0020](./0020-stable-projection-immutable-versions-and-branches.md)、
[ADR-0021](./0021-wake-observation-and-nondestructive-duplicate-groups.md) 一并冻结
0.3.13 因果代的服务器权威边界。

## 决策

### 1. 服务器可以且必须做的

| 职责 | 说明 |
|------|------|
| 认证与会话 | 验证设备凭证，解析 canonical membership/family/role |
| 家庭 ACL | 执行既有 Baby/Record/CarePlan/自定义/媒体写权限；冲突 resolution 不扩大 ACL |
| 不可变盖章 | 首次接受时盖章作者；WakeObservation 盖章观察者；履行候选证据冻结 |
| 引用与媒体 | 同家庭引用、媒体字节/hash 完整、原子根与媒体清单一致 |
| 协议有界 | 批次大小、冲突详情页、JSON 形状、幂等 `mutation_id`、CAS 期望集 |
| 同步顺序 | 稳定投影事务、版本/分支与媒体字节原子落库、pull cursor 语义 |
| 显式管理 | Owner 删除家庭、成员硬删除匿名化、授权的 conflict/duplicate resolution |

### 2. 服务器明确不做的

- 用 `updated_at`、事件时间、设备优先级、Owner 优先、到达序或「字段更完整」作为
  **通用** 同字段冲突赢家。
- 对跨 UUID 近邻记录自动选胜并 tombstone 落选（废止 ADR-0018 对 **新因果数据** 的
  服务器近邻裁决；历史 tombstone 处置见 ADR-0021）。
- 在开放睡眠重叠时自动改写较旧 SleepStart 的结束时刻或合成 anomaly 闭合。
- 在未获授权 resolution 时丢弃冲突分支、媒体字节或来源关系。
- 把 stable 投影相等当作 mutation 已保留的唯一证明。

### 3. 拒绝的方案与原因

| 方案 | 拒绝原因 |
|------|----------|
| 墙钟 / `updated_at` 通用 LWW | 离线与时钟偏斜会静默抹掉另一端已确认编辑；无法表达真并发 |
| 睡眠 open→closed 窄例外当通用修复 | 只保护一种字段组合，不能覆盖备注/剂量/照片/删除/跨 UUID |
| 事件时间或「更完整字段」赢家 | 不可测、可被堆字段抢赢；事件时间是业务事实不是并发 token |
| 全文 CRDT（Automerge 等） | 过重，不消除「两条实体」语义，也不适合 NAS 有界运维 |
| 服务器近邻自动落选 | 越过约束边界替家庭判断护理真相；两位照护者的独立事件会被误删 |

## Considered Options

- **保持 head-by-UUID + LWW，仅加强 tombstone 永胜：** 终结 dirty，不能无损；拒绝作为
  0.3.13 目标。
- **仅睡眠字段特殊规则：** 已由家庭 wake 票验证不够；拒绝作为通用架构。
- **客户端各自合并、服务器只存 blob：** 权威图漂移；拒绝，仍由服务器做约束与 CAS，
  但不做真相启发式。

## Consequences

- 实现票（schema v12、因果 API、Android Room 27、Replica 引擎）必须服从本边界；任何
  「先让服务器猜一个赢家」的捷径都与合同冲突。
- ADR-0017 的 **先对账、冻结、有界裁决、终态收敛** 骨架保留；其 LWW verdict 词汇与
  「复用 atomic commit 的 LWW」叙述在 0.3.13 能力代由因果 reconcile/commit 取代
  （见该 ADR 的 superseded 范围）。
- ADR-0018 的服务器近邻采纳与 `neighbor_losers` 信号在新能力代移除；历史 Record
  tombstone 不批量复活（ADR-0021）。
- 产品文档必须把「0.3.10–0.3.12 已交付 LWW/近邻」与「0.3.13 tree 因果合同」分开书写；
  在维护窗 CD 完成前不得把 tree 因果写成家庭 NAS 已上线无损同步。

## 实现状态

本 ADR 冻结架构边界。**tree 运行时已落地**（schema v12、因果 API、Room 27、Replica 引擎，
tickets 02–08）。**家庭 NAS 强制切割**（minSupported=20 生产生效、v11→v12 offline migrate、
joined-client 实机 smoke）由 ticket 09 维护窗证据拥有，不得仅凭本 ADR 宣称生产已切换。
