# features 层规格（`:app` 壳 + `:core:ui` + 10 个 `:feature:*`）

> 身份钉：当前 tree 0.5.4。**权界**：本文权威 = 应用壳导航/chrome 策略、各 feature 的屏幕
> 清单、关键交互与跨 feature 协作。逐页 UI 合同（token/单手/文案/校验）唯一权威 =
> [`contracts/ui.md`](../contracts/ui.md)；层规格中的「指针」不复制该合同。签名唯一权威是代码。

---

## 1. `:app` 应用壳

| seam | 锚点 | 职责 / 不变量 |
|------|------|----------------|
| 导航图 | `MainActivity.kt` | 底部导航 `TopDest{Log,Summary,Growth,Family,Settings}`；未加入家庭时走 onboarding；已加入但还没有宝宝时初始目的地 Family；向导在待建第一个宝宝的完成态上保持 onboarding，直到宝宝存在；全屏 push 路由 `timer*`、`search`、`export`、`calendar`（`isFullScreenPushRoute`）；`AndroidManifest` 锁定 `portrait`，旋转不进横屏 |
| chrome 策略 | `MainActivity.kt` `rootChromeVisibility` | Log 布局编辑态独占全屏（隐藏底导航）；底导航常驻 Log/Summary/Growth + Family + Settings 前缀；品牌头仅在 Family/Settings |
| 冲突 overlay | `MainActivity.kt` `ConflictOverlayState`（`rememberSaveable` + Saver） | `openInbox` / `openResolver(conflictId)`；inbox/resolver route 由 feature/family `conflict/` 共享提供 |
| 手势 | `BottomNavGestures.kt`、`AppHeader.kt` | 底导航手势与页头 |
| Application | `LeziApp.kt` | 网络回调注册、持久服务装配 |
| 本地数据升级流水线 | `LocalDataUpgradeModule.kt`、`CausalRoomUpgradeStep.kt`、`MediaSha256ColumnUpgradeStep.kt`、`OutboxRetirementUpgradeStep.kt`、`CustomItemClientUuidIndexUpgradeStep.kt`、`FinalCausalRoomUpgradeStep.kt`、`LocalDataSnapshotStore.kt`、`AndroidLocalDataUpgradeEnvironment.kt`、`LocalDataRecoveryScreen.kt` | APK 原地替换门禁（ADR-0012）：按受影响域先建校验快照，失败不得自动清库；基线之前/未来版/空间不足/不一致数据稳定进入恢复界面；升级完成前业务/提醒/Widget 不得提前打开持久化（platform §7 质量门） |
| 外部导航信任 | `ExternalNavigationTrust.kt` | 外部 deep-link/投喂入口的可信边界 |
| 副作用重放 | `PersistentSideEffectRehydrator.kt` | 持久副作用（`PersistentSideEffectActions`）重启重放 |

**feature 互不依赖**（architecture §2）：log↔timer 协作仅经 `core:model` 的
`TimerHandoffSeed` + composition root（app 壳）；完成写记录经 domain。

## 2. `:core:ui` 跨 feature 共享 UI 逻辑

| seam | 锚点 | 职责 |
|------|------|------|
| `CameraCapture` / `OwnedCameraCaptureSessions` | `CameraCapture.kt` | 相机采集租约（所有权边界，供 Composer 与 QR 复用） |
| `MemberLoginQrScanner` | `memberloginqr/` | 成员登录 QR 扫描（可见 sync 注册表） |
| `BabyProfileFormFields` / `BabyAvatar` / `BabyMetaLine` | 同名文件 | 宝宝表单/头像 |
| `RecordPresentation` / `RecordCatalogOrder` / `LocalRecordLayoutPolicy` | 同名文件 | 记录呈现、目录排序、本机布局策略 |
| 失败解释 | `failure/`、`FailureExplanationDialog` | 四段式失败目录的对话框面（ui.md §9） |
| 更新结果对话框 | `AppUpdateOutcomeDialogs.kt` | 自更新结果呈现 |
| 自定义条目管理 | `CustomItemManageDialog.kt` | 10 个上限的改名/排序/删除 |

## 3. `:feature:onboarding` — 首跑向导

- 屏幕：`OnboardingScreen.kt` + `OnboardingViewModel.kt`（薄壳 + 步态包）；步态与 Family
  向导逻辑对齐。
- 交互：新建宝宝 / 加入家庭（扫成员登录 QR，或手填家庭服务器地址）；连接家庭服务器、
  TOFU 证书确认。没有「粘贴邀请」入口。
- UI 合同：ui.md §5；向导状态机 = `FamilyWizardController`（layers/domain.md §2）。

## 4. `:feature:log` — 记录首页

- 屏幕：`LogScreen.kt` / `LogViewModel.kt` / `LogDialogHost.kt`；子包 `timeline/`（连续
  绝对时间轴）、`composer/`（快记 Composer）、`dock/`（快捷坞）、`layout/`（拖拽编辑）、
  `photo/`（有界照片导入）。
- 关键算法：`timeline/TimelineInteraction.kt` — 纯 reducer，拥有绝对视窗、现在夹紧与
  粘性选中日提交（ADR-0024）；`timeline/LocalDayGrid.kt` — **DST 安全几何**：任意绝对
  范围上的本地午夜/小时刻度由 `ZoneId` 规则生成，DST 空洞返回无位置、重复本地时间
  返回双偏移；一日时间条完整合同（吸附/换日/拖动物理）= ui.md §3 / §5.2。
- 交互：图标网格 → 预填 Composer（确认后写入）；下拉刷新触发 `PullToRefresh` 同步；
  布局编辑态接管全屏 chrome（§1）；log↔timer 经 `TimerHandoffSeed`。
- 测试：布局/Composer/时间轴 JVM + 设备测试（含 DST 设备用例）。

## 5. `:feature:timer` — 哺乳计时（前台服务状态机）

屏幕：`TimerScreen.kt`、`NursingCompletionSheet.kt`；服务：`NursingTimerService.kt` +
`NursingTimerServiceController.kt`；`TimerBackPolicy.kt`；清理适配
`NursingTimerCleanupAdapter.kt`。

```text
开始 → 先持久化暂停的 STARTING 快照 → 请求前台服务与通知
     → 系统真实标记前台服务，且通知权限开启时通知已发布，才回执 RUNNING 并在 UI 走秒
     → 受限启动 / 权限 / 通知 / 超时 / 运行时 / DataStore 持久化失败 → 同一总覆盖：
       停 FGS 与通知，收口 FAILED（先尝试 durable FAILED，再 memory；保留侧别、累计值、session）
     → 持久化类失败原因 `STORAGE`（文案「状态保存失败」）；服务启动类仍用 RUNTIME/权限/通知/超时
     → FAILED 再持久化失败时仍先更新内存态，UI 不得长期停在 STARTING/RUNNING 假象
完成 → 冻结 draft 打开确认 sheet（`TimerCompletionUiState`，SavedState 可恢复）
     → 确认 → Saving（单飞；再确认 no-op）→ domain completeNursing：写 nursing Record；
       若绑定 carePlanId，提交前读取计划当前 active 照片，再在 domain 事务内 clone 为 Record 独立 MediaAsset 行，
       再 complete 计划 + 候选
     → Composer→Timer handoff：显式 TimerHandoffSeed（baby/carePlan/note/amount/
       有序照片+borrowed|composer_owned）写入 TimerState 并随 DataStore 恢复；Timer accept 后
       Composer 才 close 且不删除已转移 owned 文件；完成时 merge seed 路径与事务内当前
       plan media（去重 0–3）经 photoLocalPaths 写入 Record；丢弃只回收 composer_owned
     → 成功：先 durable 发布 next-feed offer 或 pendingExit + `timerClearPending`，再按 session
       token 清空 TimerState / 停服；DataStore 失败时保持 pending、禁止退出并自动重试，成功后
       才开放 next-feed 或消费 pendingExit，绝不恢复“事实已保存但计时仍在”的 ghost session；
       失败：Saving→可重试 sheet + error（结果不经旧 composition 回调唯一交付）
     → 完成 / 暂停 / 清空：先持久化非运行快照再停服；持久化失败同样停服；
       仅当存在真实可重试侧别（lastSide / 曾运行侧）时内存 `FAILED`；
       护理计划 bind 或无侧别会话保持内存 `PAUSED`，不得伪造 `"L"`
进程被杀 / 坏存储读 → 冻结或 **init fail-closed 清空** 并停服（强于 transition 的 keep-session FAILED）；
       仅进程内同 session 见证可保留 RUNNING；不自动重复启动
     → 完成态 SavedState：submit 身份（completionClientUuid + baby）与 draft/Saving 同写；
       Saving+draft 且有 durable uuid → 幂等 resume completeNursing（timer DataStore 空亦可）；
       Saving 但身份全失 → 可重试 sheet（「会话已失效」），不得伪造成功 pendingExit；
       已发布 post-save 但 TimerState 未清/clear ack 丢失 → 只补幂等 clear；已有
       next-feed/exit → 恢复 UI，但 clear 成功前仍门禁退出与新计时；
       next-feed 结束后再发 pendingExit，与无 offer 成功路径同可消费退出
CancellationException / Error → 先停服再原样重抛，不得吞成产品错误
```

| 服务态 | 含义 | 持久化失败时 |
|--------|------|----------------|
| `PAUSED` | 无前台服务；可开始一侧 | 停服；有真实侧别则内存 `FAILED`/`STORAGE`；bind/无侧别保持 `PAUSED` |
| `STARTING` | 已写暂停快照，等待系统确认 | 不得启动或继续 FGS；收口 `FAILED`（尽量 durable） |
| `RUNNING` | 仅服务 ack 后；UI 走秒 | ack 后写盘失败 → 停服 + `FAILED`（非假 RUNNING） |
| `FAILED` | 已安全暂停，保留 side/累计/session | 写盘再失败仍先更新内存 `FAILED` |
| `RECOVERABLE` | 进程恢复未见服务见证 | 与 transition 相同：停服，可重试启动 |

| 完成 UI 态 | 含义 | 配置/进程重建 |
|------------|------|----------------|
| sheet + draft | 确认面板打开 | SavedState 恢复 draft；可改可取消 |
| Saving | 单飞提交中 | 恢复 busy sheet；同 durable `completionClientUuid` 幂等 resume |
| saveError | 可重试失败 | 恢复 error + draft（含会话失效 fail-closed） |
| next-feed offer | 事实已落，待安排 | 恢复单 blob（baby+suggestedAt）；不重复写事实 |
| pendingExit | 无 offer 或 offer 已结束，待 Host 退出 | 可确认消费一次；再订阅不重复导航 |
| timerClearPending | 事实已落，计时快照待清 | token-scoped 停服 + DataStore clear 自动重试；清完才展示 offer/退出 |

绑定护理计划的计时完成以事务内 plan media 为准（不是打开计时/Composer 时的 UI 快照）；
计划照片所有权与顺序不变，Record 行独立 `client_uuid`、可共享 `local_uri`；幂等
`completionClientUuid` replay 不重复 clone。只解析当前 TimerState；`startForeground()`
正常返回本身不是成功凭据；Android 13+ 通知权限关闭时不强求通知可见；`FAILED` /
`RECOVERABLE` 提供稳定 session token 的「重试启动」。

## 6. `:feature:family` — 账户页

- 屏幕：`FamilyScreen.kt` 导航壳；子包 `overview/`（家庭总览）、`members/`（成员/设备/
  改名申请/审批）、`wizard/`（向导宿主）、`baby/`（家庭档案）、`conflict/`
  （`ConflictInboxRoute.kt` + `ConflictResolverRoute.kt` + `LocalUnresolvedResolverRoute.kt`，
  app-shell 共享；待处理 = 冲突 + 未对齐）、
  `networksettings/`（家庭网络设置：可用性/手动刷新/重连）、`components/`。
- 交互：管理员登录、成员申请审批、设备撤销、可选更新横幅；失败浅状态点按接同一条
  `PullToRefresh`（endpoint §7.1）；已加入态手动刷新 = 单次认证心跳（layers/sync.md §5）。
- UI 合同：ui.md §5、design/2026-07-30 companion。

## 7. `:feature:settings` — 设置中心

- 屏幕：`SettingsScreen.kt`；子包 `calendar/`（系统日历集成：`CalendarScreen.kt` +
  `AndroidSystemCalendarPort` 实现 + 提醒 + 广播边界）、`record/`（记录设置：步进/显隐
  排序）、`command/`（`SettingsCommandState` 设置页命令态）。
- 交互：关于区检查/安装更新（直连 `:sync` appupdate 面）；三级日历披露与单一提醒来源
  规则 = platform §4。

## 8. `:feature:summary` — 汇总

`SummaryScreen.kt` + `SummaryAggregation.kt`（`SummaryAggregationEngine`）：日/周聚合、
喂养图卡 + 辅食面板（Top3 + 其他）。聚合**必须**复用 domain `CareAggregation`
（layers/domain.md §3）；下拉刷新触发 `PullToRefresh`。

## 9. `:feature:growth` — 成长

`GrowthScreen.kt`、`GrowthMeasurementWriteCoordinator.kt`（测量写入协调）、
`GrowthReferenceCatalog.kt`（离线中国 `WS/T 423—2022` P3/P50/P97 参考带；产品边界 =
product §4.6）。下拉刷新触发同步。

## 10. `:feature:export` — 导出

`ExportScreen.kt`、`ExportFileGenerator.kt`、`PdfExport.kt`、`ExportCacheCleanup.kt`；
经 domain `ExportPort`；TXT/PDF（系统分享，App 不长期存 PDF）。

## 11. `:feature:search` — 搜索

`SearchScreen.kt` + `SearchRepository.kt`；最近备注候选严格按当前宝宝与记录类型
（product §4.1）；不能编辑的记录打开只读查看会话。

## 12. `:feature:widget` — 桌面小组件

`CareWidget.kt` + `WidgetAutoRefresh.kt` / `WidgetRefresh`、`WidgetConfigurationActivity.kt`；
每实例独立 `widgetId` 绑定宝宝与快捷类型（product §4.8）；快捷入口只打开 Composer，
确认后才写入。

## 13. 测试契约

- 各 feature `src/test`：VM 行为、交互策略（timer back policy、layout policy、composer
  draft）。
- 设备/Compose 测试（`src/androidTest`†）：log（布局/Composer/时间轴）、family（向导/
  resolver）、settings/summary/growth/onboarding/timer 冒烟、`app` 路由与 chrome 策略
  （`MainActivity` 路由策略 JVM 测试 + 设备 UX 测试）。
- UI 回归须附截图/设备证据（AGENTS.md PR 约定）。

## 14. 代码连线

| 本文章节 | 代码 |
|----------|------|
| §1 app 壳 | `app/src/main/kotlin/com/lezi/babylog/`（`MainActivity.kt`、`LeziApp.kt`、`*UpgradeStep.kt` 等） |
| §2 core:ui | `core/ui/src/main/kotlin/com/lezi/babylog/core/ui/` |
| §3-§12 | `feature/<name>/src/main/kotlin/com/lezi/babylog/feature/<name>/` |
| §13 测试 | 各模块 `src/test/`、`src/androidTest/` |
