# 04: layers/core.md + layers/domain.md

## What to build

1. `docs/spec/layers/core.md`：`core:model`（`RecordPayload`/`RecordPayloadCodec`、
   `SleepProjection`、`NextFeedPlanFlow`、`TimerHandoffSeed`、testFixtures）、
   `core:common`（`CausalIdentity` UUIDv5 命名空间及"与服务端字节一致"不变量、
   `FailureKind`/`FailureCatalog` 分类法、`MediaContentDigest`、`LocalDataUpgrade`
   规划器）、`core:database`（Room v29 全表清单含 causal 系、`CausalMutationState`
   状态机算法（ordinary-dirty/branched/open-conflict 与 freeze/ack）、
   `FulfillmentAuthoritySettlement`、`DatabaseTransactionRunner`、CAS/合成根发布）、
   `core:datastore`（`SettingsStore` 键面）、`designsystem`（token 体系、两模板、
   长辈模式缩放与运动密度、时间轴组件族、`LocalPhotoLoader` 契约指针）。
2. `docs/spec/layers/domain.md`：`CareLog` façade seam（操作按名分组：baby 生命周期、
   记录 CRUD/转换、睡眠状态机、唤醒观察、自定义条目、权限谓词、聚合读取）、协调器五件
   （`RecordMutationCoordinator`、`SourceRelationCoordinator`、
   `ConflictResolutionCoordinator`、`WakeObservationCoordinator`、
   `PhotoAttachmentReconciler`）、`CareAggregation` 算法（day/range/week/widget、睡眠
   区间重叠语义、聚合时钟）、`TimelineWindowRepository`、`FamilyWizardController`、
   端口（`SystemCalendarPort`/`ExportPort`/清空协调）。
   两文均按层规格模板（身份头/权界、seam 表、算法、交互、测试契约、代码连线）。

## Blocked by

01（模板）

## Status

done

- [x] core.md 的 Room 表清单与 `LeziDatabase.kt` 实体一致
- [x] domain.md 的 CareLog 操作分组覆盖公开面且无签名抄录
- [x] 聚合算法含睡眠区间重叠语义与聚合时钟两个易错点
- [x] CausalIdentity 命名空间不变量（跨端一致）成文

## Parent

[`../spec.md`](../spec.md)
