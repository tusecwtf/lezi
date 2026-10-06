# domain 层规格（`:domain` 模块）

> 身份钉：当前 tree 0.5.4。**权界**：本文权威 = `:domain` 的 seam（`CareLog` façade、
> 协调器、聚合算法、端口）与测试契约。实体字段与聚合规则产品合同 =
> [`contracts/data-model.md`](../contracts/data-model.md)；同步触发语义 =
> [`layers/sync.md`](./sync.md)；来源关系 wire = wire §12 / ADR-0023。签名唯一权威是代码。

---

## 1. 职责与边界

- `CareLog` 是跨 DAO + DataStore + `SyncPort` 的 **deep façade**（构造注入 baby/record/
  carePlan/customItem/localUser/family/membership/mediaAsset DAO、`SettingsStore`、
  `SyncPort`、提醒清理、`DatabaseTransactionRunner`、`SystemCalendarPort`、fulfillment、
  `PolicyClock`）——**不拆**为浅 capability port（architecture §2 稳定原则）。
- 子包：`carelog/`（协调器 + 聚合 + 查询/呈现）、`careplan/`、`family/`、`timeline/`、
  `catalog/`、`growth/`、`export/`、`localdata/`、`calendar/`；根留 `CareLog` 与
  `DomainModule`。
- 本地写成功后只**通知**有待发布内容（`notifyLocalChanges`），不在写入调用栈执行网络。

## 2. 公开 seam 表

| seam | 锚点 | 职责 / 不变量 | 关键操作（按名枚举） |
|------|------|----------------|----------------------|
| `CareLog` | `domain/CareLog.kt` | 唯一域面 façade | **宝宝**：observeBabies / observeCurrentBaby / observeHasBaby / createBaby / updateBabyProfile / deleteBaby / moveBabyLocal；**记录**：observeRecords / observeDayRecords / observeOpenSleep / addRecord / updateRecord / deleteRecord / convertRecordToCarePlan；**睡眠**：sleepDown / sleepUp / confirmSleep；**哺乳**：completeNursing；**唤醒观察**：recordWakeObservation；**自定义条目**：CRUD + canManageCustomItem；**权限谓词**：canEditRecord / canDeleteRecord；**聚合读取**：dayRecords / daySummary / weekSummary（这两条汇总 seam 排除 source-role 行，避免和展示行双计）；**搜索**：search。输入 `CreateBabyInput`/`UpdateBabyInput`；类型化异常 `DuplicateBabyNicknameException`、`SleepStateChangedException`、权限异常 |
| `RecordMutationCoordinator` | `carelog/RecordMutationCoordinator.kt` | 记录增改删的权限检查 + 事务 + 触发同步 + 媒体对账衔接 | 与 `PhotoAttachmentReconciler` 协作 |
| `SourceRelationCoordinator` | `carelog/SourceRelationCoordinator.kt` | 近邻同型来源关系（ADR-0023） | 作者 half-edge 声明 / Owner 组裁决；source-role UUID 从时间轴与统计投影剔除；**绝不写 tombstone**；消费 `source-relation-auto:` journal 修复 |
| `ConflictResolutionCoordinator` | `carelog/ConflictResolutionCoordinator.kt` + `ConflictInbox.kt`、`ConflictResolverDraft.kt`、`ConflictAuditQueries.kt` | 冲突收件箱/resolver 的域侧协调 | choice-only 裁决（wire §8）；resolver 会话/ACL 在 feature/family 共享 |
| `WakeObservationCoordinator` | `carelog/WakeObservationCoordinator.kt` | WakeObservation 写路径（ADR-0021） | 暂定最早合法观察；作者/Owner 确认有效观察；重叠开放睡眠不自动改写 |
| `PhotoAttachmentReconciler` | `carelog/PhotoAttachmentReconciler.kt` | 照片附件与记录原子对账 | 记录+照片为一个原子同步包 |
| `CareAggregation` | `carelog/CareAggregation.kt` | 纯函数聚合（§3 算法） | day / range / window / week / widget |
| 疑似重复族 | `carelog/SuspectedDuplicate{Bounds,Grouping,Presentation,Projection}.kt`、`NearbySubtypeHint.kt` | 未确认组的上下界投影与提示 | 上/下界规则 = data-model §5.2；`createdByMembershipId` 为空的行不进软组 |
| `TimelineWindowRepository` | `timeline/TimelineWindowRepository.kt` | 时间轴窗口计算 | `TimelineWindowRequest(babyId, selectedDay, zoneId, nowMillis, railStart/End)` → 窗口（dayStart/End、显式 rail 纳入 loadKey、includeOverduePlans）+ 行能力/媒体/受众快照 |
| `FamilyWizardController` | `family/FamilyWizardController.kt` | 建家/成员加入/QR/探活向导状态机 | 与 onboarding/family 向导 UI 对齐 |
| `SystemCalendarPort` | `calendar/SystemCalendarPort.kt` | 系统日历端口（`NoOpSystemCalendarPort` 默认；Android 实现在 feature/settings/calendar） | hasCalendarPermission / listWritableCalendars / upsertEvent / findOwnedEvent / deleteEvent / eventState |
| `ExportPort` | `export/ExportPort.kt`（`TxtExportPort` 实现） | 导出端口 | exportTxt / exportDocument / exportPdfText |
| 本地清空协调 | `localdata/LocalDataClearCoordinator.kt` + `LocalDataMutationEpoch` | 与 sync 侧 `LocalClearWorkflow` 对端的域侧清空 | 清空 epoch 门禁 |
| 呈现层 | `carelog/CareLogPresentation.kt`、`SleepPresentation.kt`、`DayChartCategories.kt`、`FulfillmentSurface.kt` | 纯呈现映射 | 供 feature 消费 |

## 3. 聚合算法（`CareAggregation`）

纯函数族（`day` / `range` / `window` / `week` / `widget`），Log、Summary、一日时间条与
Widget **必须复用同一实现**（product §4.5）。

- **聚合时钟**：点事实仅 `timestamp ≤ now` 计入累计；睡眠按 `[start, min(end, now)]`
  裁剪。履行允许的「已确认但时刻未到」记录可在时间轴展示，但不进汇总，到点后自然计入
  （data-model §5.1）。
- **睡眠区间重叠语义**：跨午夜睡眠以区间与各日窗口的**重叠部分**分别计入；起点早于窗口
  的记录在区间重叠时照常参与（不能只看起点落位）——进行中睡眠以 `now` 收口。
- **近邻同型上下界**：未 pull 到来源关系前，未确认组在汇总中呈现**上界**（全体计入）与
  **下界**（仅展示版）之间；对齐后按展示版单值聚合，其它来源/照片永久保留（data-model
  §5.2、ADR-0023）。
- 输出类型：`CareDay` / `CareRange` / `DailySummary` / `WeekSummary` / `DayBucket`。

## 4. 交互

- 写路径：UI → `CareLog`（事务内 Room 写 + 权限谓词）→ `SyncPort.notifyLocalChanges`
  （仅通知）→ 由 sync 层决定 LocalWrite push（layers/sync.md §3-§4）。
- 冲突流：sync `branched` → 冲突收件箱（`ConflictInbox`）→ resolver（feature/family
  `conflict/` 共享 route）→ choice-only 裁决回 `SyncPort.resolveConflict`。未对齐项
  （普查多出 / 家里拒绝 / 拉取洞）并入同一收件箱，走 `dismissUnresolvedLocally`，
  不伪造 `conflict_id`。
- 向导流：onboarding / family 向导 UI → `FamilyWizardController` → `SyncPort`
  家庭生命周期组操作。
- 日历流：careplan 写 → `SystemCalendarPort`（可选副本；单一提醒来源规则 platform §4）。

## 5. 测试契约

- `domain/src/test/`：协调器行为、聚合纯函数（DST/跨午夜/进行中睡眠）、权限谓词、向导
  状态机。
- 真服务端 seam：`CareLogRealServerSeam*`（`IsolatedLeziSyncServer` 拉起真实 lezi-sync）
  覆盖 n-way 冲突矩阵与写后收敛。
- 好测试只断言外部行为（假时钟/假 DAO），不测协调器内部结构。

## 6. 代码连线

| 本文章节 | 代码 |
|----------|------|
| §2 seam 表 | `domain/src/main/kotlin/com/lezi/babylog/domain/`（`CareLog.kt`、`carelog/`、`family/`、`timeline/`、`calendar/`、`export/`、`localdata/`） |
| §3 聚合 | `carelog/CareAggregation.kt` |
| §4 交互 | 与 `sync/RealSyncPort.kt`、`feature/family/conflict/`、`feature/settings/calendar/` 的边界 |
| §5 测试 | `domain/src/test/kotlin/com/lezi/babylog/domain/` |
