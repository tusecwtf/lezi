---
status: accepted (0.3.13 planning freeze; not runtime-delivered)
---

# 稳定投影 + 不可变版本/分支，三方合并与显式 resolution

可变家庭原子根升级为服务器签发版本的因果合同。服务器同时维护：

1. **稳定投影**（现有 entity 表事务性维护，供普通 pull/cursor）；
2. **不可变版本** 与 **冲突分支**（恢复与审计证据）；
3. **resolution / 幂等** 与媒体字节保留。

客户端每次冻结修改携带完整期望原子根、`base_version` 与稳定 `mutation_id`。
`updated_at` 与事件时间继续是可展示/审计内容，**不是** 通用并发 token；三方合并中
`/updated_at` **不** 单独制造 branch（规范化规则见
[`causal-sync-wire.md`](../prd/causal-sync-wire.md) §4.0）。

关联：[ADR-0019](./0019-server-validates-constraints-not-care-truth.md)（权威边界）、
[ADR-0021](./0021-wake-observation-and-nondestructive-duplicate-groups.md)（醒来与疑似重复）。
**字段/枚举/例子唯一权威：** [`docs/prd/causal-sync-wire.md`](../prd/causal-sync-wire.md)。

## 决策

### 1. 版本化可变原子根

| 原子根 | 媒体边界 |
|--------|----------|
| Baby + avatar | avatar 与根同事务；软删 Baby 清 live 指针 |
| Record + record media | 0–3 log 照片；根与媒体同 commit |
| CarePlan + plan media | 0–3 计划照片 |
| CustomItem | 无媒体清单 |
| WakeObservation + wake media | 0–3 观察照片；引用一个 Sleep Record UUID |

**FulfillmentCandidate** 保持不可变候选证据模型：首次接受后整元组冻结，只允许精确
幂等 replay；**不** 进入可变三方编辑路径。

### 2. Reconcile / Commit / Pull / Resolution

协议 verdict、必填字段与 `conflict_id` 句柄以 wire §5–§8 为准。决策层要求：

- Reconcile 为有界 dry-run，不写稳定版本/分支。
- Commit 原子返回 accepted / merged / branched，并始终带回完整稳定投影。
- Pull 只投递稳定投影 + 有界冲突摘要；分支字节按需 detail。
- Resolution CAS 只允许选择真实冲突路径；自动合并路径 fail-closed 不可改写。

### 3. 三方合并粒度

- 比较 base→稳定 与 base→入站 在 wire 定义的 canonical 路径上的变化。
- 不相交业务路径自动合并；媒体按 `media_uuid` 键集合合并；数组默认原子。
- 盖章字段与 `updated_at` 规范化不制造 branch（wire §4.0 / §9.4）。
- 自动合并版本带双亲 provenance；resolution 只暴露真实冲突路径。

### 4. 删除 / 编辑因果语义

- 基于 **当前** 稳定版本的删除 → 稳定 tombstone（mutation `deleted=true` ↔ pull
  `deleted_at` 同构，wire §2）。
- 同一 live base 上的删除与离线编辑 → 顺序 commit 下先到 accepted、后到 branched
  （stable = 先接受版本；wire 例 E；删除先到见例 J）。
- 稳定 tombstone 之后：
  - 无 parent 证明的陈旧 live → **`rejected` `stale_live_over_tombstone`**（wire 例 F）；
  - `incoming.base_version ∈ parents(stable_tombstone)` 的并发 live 编辑 → **`branched`**，
    稳定仍为 tombstone（wire 例 J）；
  - 同一 tombstone `mutation_id` 精确重放 → 幂等（wire 例 I）；
  - 稳定可见性变为 live 只经显式 resolution（wire 例 G）。
- 历史无法证明原因的 Record tombstone：迁移保持隐藏，不合成分支，无批量恢复入口。

### 5. 媒体原子性

分支创建、媒体字节保存与冲突摘要必须与 commit **同一事务** 确认。引用字节在确认
branch 前全部保留。客户端不得看见「有元数据无照片」的半包稳定态。

### 6. LocalWrite no-pull

因果协议落地后，前台 + 可信 endpoint + 健康租约下可对当前冻结单元直接
reconcile/commit，**不先 pull**、**不推进 pull cursor**。无因果 base 前禁止靠去掉
pull 声称正确性。回前台 / 网络恢复 / 下拉 / 常规周期仍完整 pull。

## Considered Options

- **只加快 push（LocalWrite 无条件 no-pull）：** 缩短窗口仍可能更快丢数据；拒绝。
- **普通 pull 携带全部分支字节：** 带宽与复杂度失控；拒绝，摘要 + 按需详情。
- **字段级 CRDT 无分支：** 无法表达删改真并发与家庭显式选择；拒绝。
- **dual-read 旧 LWW 与新因果：** 破坏 ADR-0008 fresh-current；拒绝，强制能力门与
  minSupported 切割。

## Consequences

- Server schema 规划 v12：版本/冲突/分支/resolution/幂等/来源关系表；entity 表仍为
  稳定投影与 pull cursor 所有者。v11→v12 仅审计 offline-migrate；启动仍 fail-closed。
- Android Room 规划 27：`baseVersion`、冻结 `mutation_id`、WakeObservation、冲突
  摘要/详情、疑似重复与来源关系、媒体引用态。
- ADR-0017 保留对账优先与临时 plan 思想；LWW head 词汇与「采用远端整行」默认路径在
  新能力代被三方合并/分支取代。
- 深度 façade 不变：`CareLog`、`SyncPort`/`RealSyncPort`/`ReplicaSyncEngine`、`Store`。

## 实现状态

规划目标：Android/server **0.3.13**、versionCode **20**、Room **27**、server schema
**12**。发版前必须从实时清单重核。本 ADR 不含运行时交付证明。
