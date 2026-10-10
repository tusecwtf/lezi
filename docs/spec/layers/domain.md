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
| `TimelineWindowRepository` | `timeline/TimelineWindowRepository.kt` | 时间轴窗口计算 | `TimelineWindowRequest(babyId, selectedDay, zoneId, nowMillis, railStart/End)` → 窗口（dayStart/End、显式 rail 纳入 loadKey、includeOverduePlans）+ 已完成 wake、来源角色、未确认重复组/统计上下界、来源详情与行能力/媒体/受众的公开事实快照 |
| `FamilyWizardController` | `family/FamilyWizardController.kt` | 建家/成员加入/QR/探活向导状态机 | 与 onboarding/family 向导 UI 对齐 |
| `SystemCalendarPort` | `calendar/SystemCalendarPort.kt` | 系统日历端口（`NoOpSystemCalendarPort` 默认；Android 实现在 feature/settings/calendar） | hasCalendarPermission / listWritableCalendars / upsertEvent / findOwnedEvent / deleteEvent / eventState |
| `ExportPort` | `export/ExportPort.kt`（`TxtExportPort` 实现） | 导出端口 | exportTxt / exportDocument / exportPdfText |
| 本地清空协调 | `localdata/LocalDataClearCoordinator.kt` + `LocalDataMutationEpoch` | 与 sync 侧 `LocalClearWorkflow` 对端的域侧清空 | 清空 epoch 门禁 |
| 呈现层 | `carelog/CareLogPresentation.kt`、`SleepPresentation.kt`、`DayChartCategories.kt`、`FulfillmentSurface.kt` | 纯呈现映射 | 供 feature 消费 |

时间轴 feature 只把完成的 `TimelineWindowSnapshot` 映射为显示，不组合 source-role /
auto-alignment 观察流，也不再重跑领域投影配方。来源关系与窗口记录在同一 Room 事务读取；
前台分钟钟仅重新计算缓存窗口的时间语义，不查询 Room、成员目录、wake 或媒体历史。
成员目录与 token-free session 由同步身份 owner 原子产生；异步目录请求捕获持久身份 epoch，
只允许同 epoch 提交。家庭／成员／设备／端点替换与身份退休推进 epoch，普通凭据轮换、
checkpoint 和改名不推进。contract-7 新增的可选 epoch 偏好默认为零；旧的未标记目录在
下次成功刷新前不进入该公开快照，原有护理事实不变。该变化不改变 Room schema 或 wire。

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

## 6. 护理事实完整性（本轮可靠性修复）

- 补记闭合睡眠、修正开睡后记录醒来、无开放睡眠的异常醒来，均在一次数据库事务中
  写入 SleepStart 与 WakeObservation；照片在同一事务内建立所有权。同步提示在提交后发出
- 普通睡眠编辑先按原始区间确定有效／暂定观察，再对新起点验证目标醒来时间；
  睡下备注或照片不改写观察备注、照片、作者或修订。改变他人观察结束时刻仍拒绝
- 撤回观察只修订观察自身。有效指针指向已撤回观察时，读投影忽略它；不得为此替
  没有授权的睡眠作者制造 dirty 根修订。时间轴编辑能力复用命令的同一观察权限规则
- 普通导出在一次提交快照中读取投影区间、可见观察及根／观察附件；范围按
  [from 的午夜, to 后一天的午夜) 裁剪候选，不按现代睡眠根的空 end 判断进行中
- 搜索先补全睡眠投影再匹配可见文本，派生时长也参与候选。窗口 SQL 先按合法有效观察、
  最早合法暂定观察、历史 end 的顺序排除已结束历史图，再物化记录／观察／媒体
- 未确认近邻组保留窗口两侧成员作为解释选择（窗外成员可贡献零）。真实 now 用于
  过久未醒判断；事实半开截止点单独传递，不伪装成历史日末的时钟
- 自定义定义删除在事务内重读、授权并单调推进修订；修改已删除或不存在定义返回失败
- 创建计划在写入事务重验宝宝和自定义定义；无显式照片的哺乳完成复验当前计划附件
  身份、修订和路径快照，变化则明确拒绝并允许重试。文件摘要在 IO dispatcher 计算
- 宝宝创建的稳定 command UUID 可重放到同一已提交 ID。提交后选择或通知失败携带
  已提交 ID；提交后取消也携带 ID 并保留 CancellationException 语义。UI 不得因此重建宝宝

这些条款不改变 ADR-0023 的自动近邻来源关系、永久来源保留或字段不自动合并的决策。

### 6.1 类型明确的常规写入入口（US-067）

常规 feature 写入使用 CareLog 的 createRecord / editRecord、startSleep / backfillSleep /
editOpenSleep / closeSleep / correctWake，以及 createPlan / editPlan / fulfillPlan /
convertRecordToPlan。命令位于 carelog/CareWriteIntents，沿用 core:model.RecordPayload；
Baby、Record、SleepStart、WakeObservation、CarePlan 与写入重试标识各自有不同目标类型。
CareAttachments.Keep 不改现有附件；RemoveAll 明确清空；Replace 指定完整替换集合。
创建新所有权时不能使用 Keep。

这些入口只进行当前模型编码与参数映射，不新建事务层、锁层或额外数据库查询。
旧 raw-payload 入口保留兼容，最终都进入既有 RecordMutationCoordinator /
WakeObservationCoordinator / CarePlanCoordinator。

Composer 冻结新命令保存 typed payload，不把 JSON/schema 传给正常写入口。旧持久化
字段只作为读回历史 pending-write 的适配器；固定旧序列化身份，并以真实旧类生成的
合成 fixture 验证恢复。模型 payload 的全部字段为可序列化基础值／枚举，各变体有
往返测试。同步、导入、存储与兼容测试仍可使用旧适配入口，不把它们当普通 UI 协议。


闭合睡眠计划履行的语义修复独立于上述接口迁移：在同一事务内写入 SleepStart、
当前履行者的 WakeObservation、媒体、候选与计划状态。醒来写入失败时整体回滚，
相同写入标识重试只形成一个闭合图；不再创建只带 legacy end 的新事实。这是
纠正旧行为，不作为与旧实现输出等价的性能样本。

### 6.2 目标类型与计划数值验证

已有目标的类型化编辑、履行和事实转计划，在原有写入事务读取当前目标后，核对
typed payload 的声明类型；不能通过相同 JSON 形状把 FORMULA 解释成 PUMPED_FEED
或把 COUGH 解释成 RASH。履行／转换重放也不能绕过该核对。转换重放归入同一事务，
不在事务外提前返回成功；常规路径不新增公开读取或检查后写入竞态。

计划创建、编辑、转换在最终类型／自定义快照确定后、任何计划／附件写入或来源
tombstone 前调用 RecordPayloadCodec.validate。普通奶量使用 1–999 的共享规则，
next-feed 标记继续允许零量；可选数值和非奶量类型也复用同一验证器。

普通亲喂计划允许先记录零时长意图；Kotlin 的 carePlanAllowsIntentOnlyFeed 与 Rust
care_plan 验证统一此规则，出站、拉取、冲突与 settlement 都消费同一计划策略。
该例外只针对计划：实际亲喂护理事实仍要求合法正时长。普通零奶量仍拒绝，已有
next-feed 标记的零奶量例外不变。跨语言接受矩阵为 care-plan-intent-v1-golden.json；
是否通过双端／设备运行以验证证据为准。
