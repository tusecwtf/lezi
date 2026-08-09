# 08 — 复用一个事务自持的 fulfillment-authority settlement

Status: implemented — JVM/lint/assemble and Android-test compile gates pass; no device attached

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

- [x] 两个入口对同组 evidence 产生完全相同的 patches、winner 与 plan relink
- [x] candidate patch 或 plan update 任一步抛错时整体回滚；调用方不依赖 ambient transaction
- [x] 幂等重放不改变 `updatedAt`、`syncDirty` 或已转换独立 Record
- [x] 并发 observer 只能看到 settlement 前或后的完整状态，不见 partial candidate set
- [x] 删除两套 coordinator/engine 写入循环；规则、错误映射与测试 fixture 只有一个 owner

## Validation

- [x] domain/sync parity 与 replay JVM regressions 通过；真实 Room DAO failure rollback、concurrent
  observer regressions 已编译，无设备可执行
- [x] timeline invalidation regression 已编译；无设备可执行
- [x] Android JVM/lint/assemble 通过；Android instrumentation 已编译，无连接设备可执行

## Implementation evidence (fixed HEAD `9fdf94088980146c0052306bc435d0027a97dbab`)

- `core:database/fulfillment/FulfillmentAuthoritySettlement` 是唯一写入 owner，公开 seam 只有
  `settle(carePlanClientUuid)`。Module 自持 `DatabaseTransactionRunner`，在同一 Room transaction
  读取 live candidates、调用纯 `core:model FulfillmentAuthority`、patch adoption status 并重链 plan；
  所有 derived copies 保留 `updatedAt`、`syncDirty` 与 `convertedRecordClientUuid`。
- `CarePlanCoordinator` 的本机 completion 与 `ReplicaSyncEngine` 的完整 pull-page apply 都只传 plan
  portable identity；旧的两套 evidence mapping、patch loop 与 plan relink loop 已删除。

| evidence / replay case | domain completion | sync pull apply |
|---|---|---|
| owner 对更早 member | owner adopted，member conflict-not-adopted，plan 重链 owner | 同一 shared settlement；arrival-order / plan-LWW 三序列均重链 owner |
| 同角色同 confirmed-at | 纯 resolver 以 candidate UUID 稳定裁决 | pull replay 保持 UUID winner 与相同 patches |
| 重放与 metadata | 第二次 settlement 不写，plan/candidates exact-equal | full-page replay winner/status 不变 |

- `FulfillmentAuthoritySettlementTest` 通过公开 seam 验证 owner/member patches、plan relink、metadata、
  converted Record pointer 与 replay；domain/sync 既有多候选 regressions 验证两入口 parity。
- `FulfillmentAuthoritySettlementRoomTest` 使用真实 in-memory `LeziDatabase` 与
  `RoomDatabaseTransactionRunner`：DAO adapter 在 candidate update 或 plan update 写后抛错，断言整个
  settlement 回滚；并发 observer 只接收完整 before/after snapshot；嵌套 outer transaction 回滚证明
  Module 不创建脱离调用链的新事务；timeline invalidation emission 后只投影 winner。上述 instrumentation
  regressions 已编译，但无连接设备可执行。
- Final post-review targeted module/domain tests — pass (76 tasks; 30s); sync parity/replay tests —
  pass (50 tasks; 13s).
- Final post-review `./gradlew test` — pass (872 tasks; 30s).
- Final post-review `:core:database:compileDebugAndroidTestKotlin` — pass (39 tasks; 10s).
- Final post-review `./gradlew lintDebug :app:assembleDebug` — pass (816 tasks; 54s).
- Earlier combined gate also built the signed Release APK and all three affected Android-test source
  sets: pass (1481 tasks; 49m20s). Review 后按串行门禁要求未重复运行 Release R8。
- Instrumentation was not executed; the sandboxed `adb devices -l` probe could not start its local
  daemon, so no device result is claimed.
- Fixed-point review: Standards 0 hard / 0 judgement; Spec 0 hard / 0 judgement.
