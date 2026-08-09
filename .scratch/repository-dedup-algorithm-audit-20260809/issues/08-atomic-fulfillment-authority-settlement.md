# 08 — 复用一个事务自持的 fulfillment-authority settlement

Status: ready-for-agent

Priority: P2

Audit baseline: `38cfbe7dbcf1f410237dce77b50ec1bd4854d8a6`

Blocked by: 01 — 两票都修改 `ReplicaSyncEngine`，先稳定 causal settlement。

## Findings

- `CarePlanCoordinator.kt:649-697` 与 `ReplicaSyncEngine.kt:884-932` 近乎逐行复制：读取 candidates、
  映射 evidence、调用 resolver、逐条 patch，再 relink plan。
- 相同收敛 invariant 有两套写入实现；新增字段/异常恢复时可能让本地 completion 与 replica pull 得到
  不同 candidate 状态或 plan winner。
- public `CareLog` seam 只有 mutation epoch；复制实现依赖调用者“恰好已有 transaction”。中途 DAO
  失败时可能留下 candidate patches 与 plan pointer 不一致。

## Interface boundary

保留 `core:model` 的纯 `FulfillmentAuthority` 算法；在 database/domain 边界建立一个事务自持的
`FulfillmentAuthoritySettlement` Module。domain local completion 与 sync apply 都只传 plan identity，
由 Module 读取同一证据、计算并原子落库。

## Acceptance

- [ ] 两个入口对同组 evidence 产生完全相同的 patches、winner 与 plan relink
- [ ] candidate patch 或 plan update 任一步抛错时整体回滚；调用方不依赖 ambient transaction
- [ ] 幂等重放不改变 `updatedAt`、`syncDirty` 或已转换独立 Record
- [ ] 并发 observer 只能看到 settlement 前或后的完整状态，不见 partial candidate set
- [ ] 删除两套 coordinator/engine 写入循环；规则、错误映射与测试 fixture 只有一个 owner

## Validation

- [ ] domain/sync parity table、injected DAO failure rollback、concurrent observer 与 replay tests 通过
- [ ] timeline invalidation regression 继续通过
- [ ] Android JVM/lint/assemble 通过
