# 16 — 集中 structured-cancellation cause-chain policy

Status: ready-for-agent

Priority: P3

Audit baseline: `38cfbe7dbcf1f410237dce77b50ec1bd4854d8a6`

Blocked by: None.

## Findings

相同 cause walker 分别存在于：

- `domain/localdata/LocalDataClearCoordinator.kt:370-377`
- `feature/settings/BroadcastWorkBoundary.kt:27-34`
- `sync/LocalReplicaClearCoordinator.kt:204-210`

三处都判断 throwable cause chain 是否含 `CancellationException`，以决定原样 rethrow 或走普通失败。
规则复制会让以后处理循环 cause、结构化取消子类或错误包装时只修一处。

## Interface boundary

在三模块都可依赖的 `core:common` 放一个小型纯 cancellation policy。调用方直接调用该函数；不要为
每个 coordinator 增加一层 Manager/Adapter，也不要吞掉原始 cancellation instance。

## Acceptance

- [ ] direct、nested、多层 wrapper、无 cancellation 与循环/异常 cause chain 都有确定结果
- [ ] 三个私有 walker 删除，只剩一个规则 owner
- [ ] 命中 cancellation 时原 instance/上下文按现合同 rethrow；普通异常仍走各自 UI/rollback 失败路径
- [ ] 不把业务 timeout 一律误分类为 coroutine cancellation；现有 busy/feedback 状态不回归

## Validation

- [ ] shared policy table tests 与三个调用点的行为 regression 通过
- [ ] domain、settings、sync JVM tests及 app lint/assemble 通过
