# core 层规格（`:core:model` / `:core:common` / `:core:database` / `:core:datastore` + `:designsystem`）

> 身份钉：Room **v29** / 本地数据契约 **6**（相邻空列 `media_assets.sha256`；永久无损基线
> v1 = 0.3.0 / code 6 / Room 24）。**权界**：本文权威 = core 各模块与 designsystem 的
> seam、算法与测试契约。实体字段与聚合规则的产品合同见
> [`contracts/data-model.md`](../contracts/data-model.md)；照片加载合同见
> [`contracts/local-photo-loading.md`](../contracts/local-photo-loading.md)；UI 逐页合同见
> [`contracts/ui.md`](../contracts/ui.md)。签名唯一权威是代码。

---

## 2. `:core:model` — 纯 Kotlin 领域值类型与约束

叶子模块（无 project 依赖）；含 `src/testFixtures` 供跨模块测试复用。

| seam | 锚点 | 职责 / 不变量 |
|------|------|----------------|
| `Record` / `RecordType` / `RecordTime` | `Models.kt`、`RecordType.kt`、`RecordTime.kt` | 领域记录模型与 21+ 记录类型 key（产品术语表 product §3） |
| `RecordPayload` + `RecordPayloadCodec` | `RecordPayload.kt` | 类型化载荷编解码；**闭合 key 集**，wire 侧闭合键权威 = wire §4 |
| `SleepProjection` | `SleepProjection.kt` | 睡眠投影（开放睡眠配对、异常标记） |
| `NextFeedPlanFlow` | `NextFeedPlanFlow.kt` | 下次喂养计划 UI 流；`NEXT_FEED_PLAN_MARKER` 契约见 data-model §3.11 |
| `TimerHandoffSeed` | `TimerHandoffSeed.kt` | log↔timer 协作种子（baby/carePlan/note/amount/有序照片+borrowed\|composer_owned）；**feature 间唯一合法握手载体**（architecture §2） |
| 其它约束类型 | `BabyNickname.kt`、`BabyProfileConstraints.kt`、`FoodAmount.kt`、`NursingConfirmInput.kt`、`NursingTimerClearEpoch.kt`、`RecordItemIdentity.kt`、`RecordMediaFiles.kt`、`RecordPhotoResourcePolicy.kt`、`RecordSummary.kt`、`DeviceLayoutSnapshot.kt`、`ElderMode.kt`、`GrowthMeasurementFacts.kt`、`FulfillmentAuthority.kt`、`CustomCatalogIdentityMigration.kt`、`ProductDateTime.kt` | 输入约束与快照类型 |

## 3. `:core:common` — 纯 Kotlin 原语

叶子模块。

| seam | 锚点 | 职责 / 不变量 |
|------|------|----------------|
| `CausalIdentity` | `CausalIdentity.kt` | UUIDv5 确定性身份：`WAKE_MIGRATION_NAMESPACE` / `VERSION_MIGRATION_NAMESPACE` **必须与 lezi-sync 字节一致**（wire §11 / offline_migrate） |
| 失败分类 | `failure/FailureKind.kt`、`failure/FailureCatalog.kt`、`ProductUiError.kt`、`LocalOpFailureCopy.kt` | `FailureKind` 闭合 25 类 → 四段式产品文案（ui.md §9）；分类是产品文案的单一来源 |
| `MediaContentDigest` | `MediaContentDigest.kt` | 媒体 sha256 身份 |
| `LocalDataUpgrade` | `LocalDataUpgrade.kt` | 本地数据升级规划器（APK 原地替换门禁的纯逻辑半部；执行步骤在 `:app`） |
| 其它原语 | `SingleFlightAction.kt`、`Uuid.kt`、`PersistentSideEffectActions.kt`、`WizardTrustCopy.kt`、`cancellation/`、`deadline/` | 单飞动作、UUID、持久副作用重放、向导信任文案、取消与截止策略 |

## 4. `:core:database` — Room 唯一本地真相源

依赖 `core:model`、`core:common`。`LeziDatabase`（`LeziDatabase.kt`）：**version 29**、
`exportSchema = true`、20 实体：

```text
家庭/身份：local_users、families、memberships
护理域：  babies、records、care_plans、fulfillment_candidates、custom_items、media_assets
causal 系（causal/CausalEntities.kt）：wake_observations、conflict_summaries、
          conflict_detail_cache（ConflictSnapshotCacheEntity）、suspected_duplicate_groups、
          source_relations、source_relation_members、source_relation_declarations、
          media_references、causal_transport_journal
清理台账：pending_reminder_cleanup、pending_replica_cleanup
```

| seam | 锚点 | 职责 / 不变量 |
|------|------|----------------|
| `LeziDatabase` + DAO | `LeziDatabase.kt`、根 DAO、`causal/CausalDaos.kt` | 唯一真相源；schema fresh-only / 当前 `user_version`，mismatched DB fail closed |
| 因果状态机 | `causal/CausalMutationState.kt` | **纯函数状态机**（见下） |
| 履行权威结算 | `fulfillment/FulfillmentAuthoritySettlement.kt` | **自持 Room 事务**：domain 本机完成与 sync pull apply 只传 CarePlan portable identity；同一事务读取完整候选证据、计算 adoption patches、重链计划；派生写**不改**候选/计划的 `updatedAt`、`syncDirty` 或候选独立记录转换指针 |
| 事务/CAS | `DatabaseTransactionRunner`、`MediaAssetCas`、`SyntheticRootPublication`、`TimelineWindowDao` | 事务边界与乐观并发 |
| 迁移链 | `LeziDatabase` migrations | 相邻迁移保护 APK 原地替换（ADR-0012）；升级门禁步骤在 `:app`（layers/features.md §1） |

**因果根状态机算法**（`CausalMutationState.kt`，全部纯函数，无 I/O）：

```text
CausalRootMutationState(baseVersion, mutationId, contentEpoch, syncDirty,
                        openConflictId, localBranchVersionId)
普通本地脏（ordinary-dirty） ──freezeDirtyEpoch──▶ 冻结 epoch（待 commit-first）
commit-first 提交 ──freezeCommitFirstEpoch──▶ 结算中
  ├─ accepted|merged ──settleCommitFirstAcceptedOrMerged──▶ 回执后 acknowledgeAcceptedOrMerged 清脏
  └─ branched ──settleCommitFirstBranched──▶ 开分支态；acknowledgeBranched 后进入
       open-conflict（openConflictId 非空）→ 等 ConflictSnapshot 裁决
```

三分支语义（ordinary-dirty / branched / open-conflict）是浅层待同步投影与续跑熔断的共同
底座；journal 前缀（如 `source-relation-auto:`）承载跨进程重放证据。

## 5. `:core:datastore` — DataStore 设置

| seam | 锚点 | 职责 |
|------|------|------|
| `SettingsStore` / `SettingsDataSource` | `SettingsStore.kt`、`SettingsDataSource.kt` | 长辈模式、设备布局快照（DeviceLayoutSnapshot）、选中宝宝、TimerState 持久化 blob。同步偏好与成员目录缓存在 `sync/session/SyncPreferences.kt`，不在本模块 |

均为**本机不同步**偏好（product §4.7）；键面以代码为准。

## 6. `:designsystem` — token 与叶子组件

只依赖 `core:model`（UI 最底层；`core:ui` 之上才可见 sync）。

| seam | 锚点 | 职责 / 不变量 |
|------|------|----------------|
| token 体系 | `Tokens.kt`（`LeziColors` light/dark/journal、`LeziSpacing` Xxs..Xxl + **Touch=48dp**、`LeziCanvas`）、`Theme.kt` | 双模板（warm/journal）token；触控 ≥48dp 无障碍底线 |
| 长辈模式 | `Theme.kt` + `MotionDensity` | 字号/密度缩放与运动密度；合同 ui.md §2 |
| 时间轴组件 | `TimelineComponents.kt`、`TimelineAxis.kt`、`TimelineMarkerLayout.kt`、`RecordRow.kt` | DST 安全几何在 `feature/log/timeline/LocalDayGrid`（layers/features.md §4，ADR-0024）；组件只做绝对瞬时→像素的绘制/命中，不持有日历或选中日 |
| 表单/确认 | `Components.kt`、`LeziFormControls.kt`、`LeziAlertDialog.kt`、`ConfirmChrome.kt`、`DatePickerComponents.kt`、`LeziRangeTabs.kt` | 闭合组件面；产品级确认样式 |
| 记录视觉 | `RecordVisuals.kt`（`LeziPeeAmountMark`、`LeziStool*Mark`）、`LeziCustomItemGlyphs.kt` | 排泄图标资源合同 assets-notes.md |
| 照片加载 | `LocalPhotoLoader.kt`、`AndroidLocalPhotoLoader.kt`、`LocalPhotoMemoryCache.kt` | 有界内存解码；合同 local-photo-loading.md |
| 其它 | `ClockDial.kt`、`NextFeedPlanFlow.kt`、`NursingConfirmFields.kt`、`SwipeEditDeleteRow.kt`、`TransientShallowSyncChrome.kt`、`ActionStateComponents.kt`、`KeyboardDismiss.kt` | 圆盘时间、计划流、滑动编辑行、浅同步提示 chrome |

## 7. 测试契约

| 模块 | 位置 | 重点 |
|------|------|------|
| `core:model` | `src/test`（+ testFixtures） | 载荷编解码边界、约束 |
| `core:common` | `src/test` | UUIDv5 命名空间（与 Rust 侧对照）、失败目录四段式 |
| `core:database` | `src/test` + `src/androidTest`† | Room 行为进**设备测试**：`CausalRoomTransactionTest`（因果事务）、fulfillment 结算、media CAS、merge |
| `core:datastore` | `src/test` | 键读写与默认值 |
| `designsystem` | `src/test` + `src/androidTest`† | token/typography/长辈模式合同测试 + `TimelineDstDeviceTest` 设备冒烟 |

## 8. 代码连线

| 本文章节 | 代码 |
|----------|------|
| §2 model | `core/model/src/main/kotlin/com/lezi/babylog/core/model/` |
| §3 common | `core/common/src/main/kotlin/com/lezi/babylog/core/common/` |
| §4 database | `core/database/src/main/kotlin/com/lezi/babylog/core/database/`（`causal/`、`fulfillment/`） |
| §5 datastore | `core/datastore/src/main/kotlin/com/lezi/babylog/core/datastore/` |
| §6 designsystem | `designsystem/src/main/kotlin/com/lezi/babylog/designsystem/` |
| §7 测试 | 各模块 `src/test/`；`core/database/src/androidTest/`、`designsystem/src/androidTest/` |
