---
triage: implemented
title: 0.5.4 本机数据安全加固 + UX 信任面与动效收尾
tracker: .scratch
decisions: 2026-09-13 owner——两条 0.5.4 规划流二合一（local-data-safety × ux-trust-motion-polish，pre-merge 原稿见 sources/）；范围=数据安全 S1–S7 + UX 信任面 T1–T5 + 动效设计 M1–M6；APK 单侧发版 0.5.4/34，服务端停 0.5.3；首页「现在怎么样」留 0.5.5
---

# 0.5.4 本机数据安全加固 + UX 信任面与动效收尾

- `Status: implemented`（2026-09-13 票 01–18 落地。2026-09-22 票 19 把交互与同步审查修复收进同一 0.5.4/34，不另开版本号。家庭 NAS 与 `app-update.json` 仍是 0.5.3，本轮不执行 CD。两台真机装 APK + 冒烟仍见 [`SMOKE-CHECKLIST.md`](./SMOKE-CHECKLIST.md)）
- 来源：两份 2026-09-13 审查合并——
  [`../2026-09-13-sync-conflict-best-practices-review.md`](../2026-09-13-sync-conflict-best-practices-review.md)
  第一批建议（S1–S7）与
  [`../2026-09-uiux-best-practice-review/REVIEW.md`](../2026-09-uiux-best-practice-review/REVIEW.md)
  （T1–T5、M1–M6，均经对抗复核）；pre-merge 原稿在 `sources/`。
- 身份钉目标：**0.5.4 / versionCode 34**。
- 硬约束：**零 schema**（本地数据契约 6 / Room 29 / server schema 13 / floor 21 不动）、
  **零 wire**（协议代 0.4.0 不变）。票 01–18 为零服务端代码。票 19 在树内修了
  lezi-sync store（不新加 JSON 字段、不抬 floor、不改 `Cargo.toml`），家庭 NAS 容器仍停
  0.5.3，**不执行 NAS CD**，`deploy/app-update.json` 不刷新。不触 TLS/secret 不变量。

## Problem Statement

**数据安全六毛边（来源：同步冲突审查）——**

1. 在「待处理」里点了「只从这台手机去掉」的一条记录，几天后家人修改了那条记录，它**又无声出现**在这台手机上——"去掉"这个决定没有被执行到底。
2. 家庭管理员把两个家庭宝宝合并后，部分记录可能留在被并掉的宝宝名下或引发同步分叉（合并前已冻结的发表信封仍按旧宝宝身份发出）。
3. 「退出这台设备」只给一句文字警告：不说还有几条没同步，点确认即永久丢弃；被家庭管理员移除的设备更是事后才无声清空。
4. 个别本机记录数值越界（绕过 UI 写入）后本地看得见、家里永远收不下，成为永久 pending 的僵尸行。
5. 低内存手机上强制更新把整个 APK 读进内存，可能中断升级流程。
6. fresh-schema 冒烟测试断言停在 Room 26（当前 29），fresh-only 策略的第一道回归防线已失效；另有三处已知 JVM 测试失败在账。

**信任面五项（来源：UX 审查，逐行验证）——**

| # | 现象 | 根因（已验证） |
|---|---|---|
| T1 | 打开冲突收件箱的一瞬先闪「目前没有待处理项」再变成有项；部分行显示「删除状态待加载」「提交者详情待加载」这类工程词；整页没有加载中/失败形态 | inbox StateFlow 初值是空 `ConflictInbox()`，「未加载」与「确实为空」不可区分（`ConflictInboxRoute.kt:51`）；sheet 无 loading/失败相位（`:62-93`）；未就绪字段直出「待加载」文案（`:106-127`） |
| T2 | 汇总页把范围切到一段没有记录的周期时，KPI 显示一排 0、图表整卡消失，没有「这个范围没有记录」的空态 | `SummaryUi.empty` 已建模（`SummaryScreen.kt:199`、`SummaryAggregation.kt:426,443`）但渲染层无分支（`SummaryScreen.kt:373-529`） |
| T3 | 导出失败时磁盘满、权限等系统故障一律被解释成「输入无效」；范围内没有记录也能生成近空文件；生成期间只有按钮文案变化，成功/取消后没有任何「文件在哪」的终态反馈 | catch Throwable → `InvalidInput`（`ExportScreen.kt:171-178`、share 分支 `:202-211`）；仅超时有独立分类 `ExportTookTooLong`（`:165`）；全文件无空态与成功终态 |
| T4 | 时间线记录行的异常「!」纯视觉，TalkBack 用户听不出该行有异常 | `RecordRow.kt` 三处变体（`:98-105,167-174,254-261`）整文件无 semantics/stateDescription |
| T5 | 锁屏/通知上的喂奶计时无法暂停或结束，必须回到 App；通知每秒全量重建一次；小图标是系统 `android.R.drawable` | `NursingTimerService.kt:110-118` ticker 每秒 `notify`；`:153-171` 仅 contentIntent 无 action；`:166` 系统图标（已有缓解：IMPORTANCE_LOW 通道 + `setOnlyAlertOnce`） |

**动效/设计六项（来源：UX 审查）——**

| # | 现象 | 根因（已验证） |
|---|---|---|
| M1 | 暗色主题下底部弹层、时钟表盘等系统组件底色是 M3 baseline 紫黑（#211F26 系），与品牌蓝黑（#15202C）脱节；浅色差 1.12:1 较隐蔽 | 四套 scheme 均未覆写 `surfaceContainer*` / `surfaceBright/Dim/Tint`（`Theme.kt:190-296`）；7 处 `ModalBottomSheet` 未设 `containerColor` 吃默认（`RecordComposer.kt:113`、`LogDialogHost.kt:223`、`TimerScreen.kt:249`、`FamilyMembersListUi.kt:191`、`ConflictInboxRoute.kt:62`、`CausalProductSurfaces.kt:63,212`） |
| M2 | 缓动无 token（全库仅 6 处显式 FastOutSlowIn）；约 15 处动画时长游离于 `LeziMotion` 三档外；页面级 push 转场偏快（150/200ms） | `.scratch/motion-polish/issues/02,03,04` 均 ready-for-agent。**定性修正（复核裁定）**：游离时长均为协程动画，受系统 MotionDurationScale 缩放，reduce-motion 并未失效——属 token 一致性治理，不是可用性缺陷 |
| M3 | 触觉反馈全库仅 swipe 阈值与布局拖拽两处；记录保存成功、删除确认、计时器启停、底部长按切宝宝都没有 | `SwipeEditDeleteRow.kt:259`、`LayoutEditMode.kt:274` 为生产代码唯二触觉点 |
| M4 | `MaterialTheme.typography` 映射缺 displayMedium/displaySmall/headlineLarge 三槽（latent 漂移）；计时器大数字 28sp 内联绕过字阶 | `Tokens.kt:656-683`；`TimerScreen.kt:477` |
| M5 | 桌面小组件用默认 `GlanceTheme`（Android 12+ 走系统动态色），与品牌色板零关联 | `CareWidget.kt:61` |
| M6 | 文档权威漂移两处（UX 复核裁定的唯一 P0） | `docs/spec/architecture.md:143` 仍写「3 日时间轴」（ADR-0024/`contracts/ui.md:126` 已是连续绝对时间轴）；`docs/spec/product.md:336` 坞槽「左右手偏好持久化」措辞与 `CONTEXT.md:257`/`contracts/ui.md:105`「不镜像惯用手」有歧义 |

两份来源审查中被对抗复核/对抗校验**推翻**的论断不进本版（UX 见 REVIEW.md 附录 A；同步见
来源审查 §5），后续排票勿当作已知问题捡回。

## Solution

把「本机去掉」变成持久决定（本机拉取应用尊重 dismissed 台账）；把宝宝合并变成因果安全操作
（合并前废弃冻结信封）；给两条破坏性路径加"还剩多少条"的量化披露与先同步机会（清空语义
本身不变）；把数值校验下沉到落库点消灭僵尸行；把更新 APK 改为流式落盘；修好测试门禁并
还清已知测试债。同时让「家里那份日志」的界面状态诚实、让高频路径有终态反馈、把设计系统
与动效自家研究指出的最后三公里走完：收件箱「未加载」不再渲染成「空」、汇总/导出补空态与
真实错误归因、记录行异常可被读屏听见、计时器通知可操作、surfaceContainer/缓动/时长/字号
全部回归 token 单源。全程零 schema、零 wire、零服务端代码，NAS 容器停在 0.5.3，
两台真机直接装 0.5.4 APK。

## User Stories

**数据安全**

1. 作为在「待处理」选择「本机去掉」的家人，我希望这个决定在这台手机上持久生效（即使家人之后修改了那条记录），所以我不会再看到已去掉的内容悄悄回来。
2. 作为家庭管理员，我想合并两个家庭宝宝后所有记录稳定归入目标宝宝，所以不会出现记录留在旧宝宝名下或同步分叉。
3. 作为准备退出这台设备的家人，我想在确认前看到「还有 N 条未同步」，并能先试一次同步，所以不会误丢当天的记录。
4. 作为被家庭管理员移除了设备的家人，我想在下次打开应用时被告知「何时被移除、清掉了多少条未同步内容」，所以损失是可见的而不是无声的。
5. 作为通过非 UI 入口写记录的使用者，我希望越界数值在本机落库时就被拒绝，所以不会产生"本地看得见、家里永远收不下"的僵尸行。
6. 作为在低内存手机上执行强制更新的家人，我希望 APK 下载边下边落盘，所以更新不因内存不足中断。

**UX 信任面**

7. 作为家里同时改过同一条记录的家长，我希望打开冲突收件箱时不会先看到「目前没有待处理项」再闪出条目，以便我相信这个列表说的是真话。
8. 作为不熟悉同步机制的家长，我希望收件箱顶部有一句解释（什么时候东西会出现在这里），且看不到「删除状态待加载」这类我听不懂的词。
9. 作为收件箱加载失败的家长，我希望看到明确的错误提示并能原地重试，而不是一张静止的列表。
10. 作为翻看上周汇总的家长，我希望某段范围没有记录时直接被告知「这个范围还没有记录」，而不是对着四个 0 和一张消失的图表猜。
11. 作为导出被磁盘问题打断的家长，我希望错误文案说的是「写入失败」而不是「输入无效」，以便我知道该清空间储而不是检查日期。
12. 作为导出大范围带照片的家长，我希望生成期间看到进度、完成后知道文件在哪里、即使分享了取消也告诉我文件还在。
13. 作为使用 TalkBack 的家长，我希望滑到带「!」的记录行时能听到「有异常」，而不是毫无察觉地略过。
14. as a 夜里正在喂奶、手机在充电的家长，我希望在锁屏通知上直接暂停或结束计时，不必解锁再进 App。
15. 作为在通知上点「结束」的家长，我希望被带到完成表单确认，而不是计时被悄悄丢掉。

**动效与设计**

16. 作为深夜使用暗色主题的家长，我希望底部弹层和表盘的底色与 App 其余部分是同一套蓝黑，而不是突兀的紫灰。
17. 作为记录完一条喂奶的家长，我希望保存成功时有轻微触觉确认，以便我敢直接锁屏。
18. 作为维护者，我希望 surfaceContainer、缓动、时长、字号全部从 token 单源取值，并有契约测试守住，不再靠纪律。

**维护者**

19. 作为维护者，我希望 0.5.4 不需要动 NAS：换 APK 即完成升级，app-update.json 的渠道刷新留到下次有服务端改动的维护窗一并做。
20. 作为维护者，我希望 `./gradlew test` 恢复全绿、fresh-schema 断言跟随 `LeziDatabase.VERSION`，且 docs/spec 与 ADR-0024 恢复一致（含数据安全线三处与 UX 线两处文档修正）。

## Implementation Decisions

- **范围与版本**：17 张功能/收尾票 + 1 张发版票（见 `ISSUES.md`）。版本身份
  **0.5.4 / versionCode 34**；`config/android-release-compatibility.json` 只追加一行账本；
  Room 29、本地数据契约 6、server schema 13、同步 floor 21 全部不动；服务端 Cargo 停
  0.5.3，本版**无 NAS CD**。发版票顺带修 M6 两处 UX 文档漂移（S7 的三处文档修正是独立票）。

### S1 dismiss 耐久化（同步审查 G1）

- 新台账键：复用 `causal_transport_journal`，在 `core/database/.../causal/`（`dismissedSkipCacheKey` 旁）新增 `dismissedEntityCacheKey(entityType, clientUuid)`；`tombstoneLocallyWithoutPush`（`sync/.../engine/ReplicaSyncEngine.kt:2646`）在写本机墓碑的**同一事务**里写该键。
- 应用门：引擎各 root apply 路径在应用 **live 版本**（服务端 `deletedAt == null`）前查台账——命中且本机行处于 dismissed 墓碑态（`deletedAt != null && syncDirty == false && mutationId == null`）→ 跳过应用；来的是墓碑 → 照常应用（家庭删除照常收敛，「记录墓碑」语义不变）。`PullHole` 方向的 `dismissed-skip` 既有语义不变。
- 普查投影：census reconcile（活集 keys 对比）把命中 dismissed-entity 台账的 uuid 从服务器 keys 侧剔除，避免「家庭活集与服务器不一致」永久驻留。拉取洞方向（G7）不在本票范围。
- 收件箱投影：`ConflictResolutionCoordinator` 对命中台账的 clientUuid 不再出卡（防止 branched 摘要复活僵尸卡）。
- 文案：`LOCAL_DISMISS_CONSEQUENCE`（`UnresolvedInboxIds.kt:8`）追加一句「若家人之后修改了这条，它也不会再回到这台手机。」

### S2 宝宝合并 × 冻结信封（同步审查 G2）

- `mergeBabyProfiles`（`domain/.../family/BabyFamilyProfileCoordinator.kt:430`，已在 `sleepMutationMutex` + Room 事务内）在重绑每条 source 记录/计划**之前**，对该行调用既有 `abandonMutation` 终态回执路径（`sync/.../engine/CausalSettlement.kt:1339`；domain 侧经 `SyncPort` 新增窄方法，如 `abandonPendingLocalMutations(entities)`）——合并前已冻结的信封永不按旧 `baby_client_uuid` 发表；`syncDirty = true` 的行在下一周期以目标宝宝重新冻结（ADR-0022「恰一份不可变信封」不破坏）。
- 成员方向（本机孤宝宝并入家庭权威宝宝，`forceMemberRules`）行为不变（本就不得上传）。

### S3 破坏性路径守卫与清理收据（同步审查 G3）

- 主动退出：`FamilyScreen.kt:1274` 的 `LogoutCurrentDeviceDialog`（`MembersDevicesDialogs.kt:235`）接入既有 `SyncPort.pendingPublishCount(): Flow<Int>`（`SyncPort.kt:387`）。计数 >0 时显示「还有 N 条未同步，退出后将永久丢弃」，并提供「先同步再检查」动作（触发一次 Foreground 轮后留在弹窗刷新计数）；「仍然退出」始终可用——**确认而非阻塞**（NAS 离线时不得困住用户）。
- 被动撤销：`handleRemoteDeviceRemoved`（`RealSyncPort.kt:2570`）在执行清理**之前**持久化清理收据（时间、原因 `device_removed`、当时待发布计数）到 DataStore（`SyncPreferences`）；下次进入家庭/网络设置面一次性呈现「该设备于 X 被家庭管理员移除，已清理 N 条未同步内容」。清空语义本身不变（`sync-trusted-endpoint.md:306` 冻结合同）。

### S4 APK 流式落盘（同步审查 G12）

- `downloadAppUpdateApk`（`sync/.../backend/HttpSyncBackend.kt:1406`）改为写入调用方提供的 staging 输出流，边写边算 SHA-256，`MAX_SYNC_APP_UPDATE_APK_BYTES`（`:86`）按累计字节数强校验；`RealSyncPort` 消费侧（`:2239-2259`）改为文件路径 + 摘要，删除整包 `ByteArray`。更新校验链（sha256 → packageName/versionCode/签名三重校验 → commit）语义不变。

### S5 落库范围校验下沉（同步审查 G15 部分）

- `RecordMutationCoordinator.insertRecord` / 替换路径（`RecordMutationCoordinator.kt:938,968`）在 `requireCurrentPayloadDocument`（decode 闭键集）之后追加 `RecordPayloadCodec.validate`（数值域）。UI 侧先拦保留；生长测量域层校验不变（`GrowthMeasurements.kt:139` 已有）。

### S6 测试门禁修复（同步审查 G5）

- `core/database/src/androidTest/.../FreshDatabaseTest.kt:80` 的 `assertEquals(26, …)` 改为读取 `LeziDatabase.VERSION`，消除硬编码漂移。
- 偿还 0.5.1 票 03 记录的三处预存 JVM 失败：`BabyMoveSurfaceTest`（缺 `babyLocalLayout`）、`RealSyncPortCustomItemTest`（两条）、`ReplicaSyncEngineLocalWriteNoPullTest.wakeLiveAndTombstoneCommitDirectlyButDanglingOrMediaRootsWait`，恢复 `./gradlew test` 全绿。

### S7 文档修正（同步审查 G14，零运行时）

- `docs/spec/layers/server.md:45-46`：「已退役仍挂载」标注从 `/v1/pull` 移到 `/v1/push`（及 `/v1/media` PUT）；`/v1/pull` 是活跃增量页端点（wire §1.4、`lib.rs:979-980`）。
- `docs/spec/contracts/data-model.md`（如 UI 需要则 `ui.md` 同步）：补时间轴同 timestamp 决胜规则 `ORDER BY timestamp DESC, clientUuid DESC`（`TimelineWindowDao.kt:428`）。
- `docs/spec/contracts/causal-sync-wire.md` §16 实现注记：非 sleep record 缺 `end_timestamp` 键由服务端归一化为 null（golden 形状 `end_timestamp_null_injected` 已钉死；不改行为、不加字段、不抬 floor）。

### T1–T5 信任面五项

- **T1 冲突收件箱相位建模**（blocked by S1：收件箱投影行为先落地）：`ConflictInboxRoute.kt` 的流状态改为区分「未加载/加载中/空/错误/内容」相位（search 页五相位是仓内范本，`SearchScreen.kt:196-241`）。初值不再可被渲染为空态；加载失败态复用 `StateContainer(Error)` + 重试，接既有刷新链路。未就绪的元数据行**不渲染该行**，整卡未就绪时给「正在获取详情…」；「处理/审阅」动词保持 424fc4a2 的对齐。顶部固定一句心智解释（文案走 Tier B 纯函数常量 + JVM 测试锁定，ui-copy-hardening 模式）。
- **T2 汇总空态**：`SummaryContent` 增加 `ui.empty` 分支——空周期时图表区渲染 `StateContainer(Empty)`（复用 `ActionStateComponents.kt:127` 组件），文案说明该范围无记录并指向底栏记录页；KPI 区维持现状。不做跨页跳转按钮。
- **T3 导出归因与终态**：归因抽纯函数（先例 `ClearRecordsFailureCopyPolicy`）——`ExportTookTooLong` 维持；IOException/磁盘类映射「写入失败，记录未受影响」语族；**未识别异常走 `productUiError` 兜底**，不再一律 `InvalidInput`（优先复用 `core/ui` 既有 FailureKind，确缺「写入失败」档才允许票内最小新增）。生成前 0 记录 → `StateContainer(Empty)`，不产出近空文件。生成期按钮区上方加不确定进度条 + 一句「正在生成，最长约半分钟」（不新增 ExportPort 阶段回调 seam）。终态：成功 Snackbar「已生成 · 可分享」（含按龄清理保留说明）；sharesheet 取消同样给「文件已生成，可在本页再次分享」。
- **T4 RecordRow 异常语义**：三处变体统一在行级 semantics 追加 `stateDescription` 异常词（无异常不追加）；文案 Tier B 常量 + JVM 测试。先例：`LogDialogHost.kt:179-180` stateDescription、`RecordSummary.spokenValue` 下沉模式。
- **T5 计时器通知**：运行中 `setUsesChronometer(true)` + `setWhen()`（系统走秒），ticker 只在左右切换/暂停/恢复时各 notify 一次，删除每秒重建；暂停态静态文本 + 状态词（「进行中 · 左/右」「已暂停」）；页内 200ms tick（`TimerScreen.kt:134-140`）不变。新增两个 `NotificationCompat.Action`：「暂停/继续」直接以 service intent 作用于服务；「结束」拉起 App 的完成表单（复用既有 completion 路径，**不**静默丢弃）。小图标换自有单色（monochrome）drawable。通道 IMPORTANCE_LOW 与 `setOnlyAlertOnce` 保持。

### M1–M6 动效与设计六项

- **M1 surfaceContainer 梯度**：`resolveLeziColorScheme`（`Theme.kt:298-327`）内对四套 scheme copy 补 `surfaceContainerLowest/Low/Container/High/Highest/Bright/Dim` 与 `surfaceTint`，值入 `LeziColors` 单源（避免第三处内联字面量）；7 处 ModalBottomSheet **不改 call site** 即吃新梯度；暗色优先真机/预览验证。顺带清理零消费 token `JournalMuted`、`Warning`（`Tokens.kt:84,47`，删除或注明保留理由）。
- **M2 motion-polish 票收编**：按既有票内容落地——票 02 `LeziEasing`（值取 `research.md` AAR 口径 decelerate `(0.1,0.7,0.1,1)` / accelerate `(0.3,0,0.8,0.2)`）入 `Tokens.kt` + `design/tokens.json` 同步 + 契约测试；票 03 五文件游离时长归一（`NextFeedPlanFlow.kt:88-89`、`TimelineComponents.kt:189,195`、`LocalDataRecoveryScreen.kt:113`、`SwipeEditDeleteRow.kt:71`、`LogTimelineList.kt:539`，以票内清单为准）；票 04 全屏 push enter 升 Emphasized 300 + EmphasizedDecelerate、exit 保持 Fast + Accelerate、tab 切换维持纯 fade Fast（`MainActivity.kt:1424-1447`）。**不**引入 material3 1.4 `MotionScheme`（随平台升级波）。票 01（开屏闪屏）已 done 不收编；落地后回写原票状态。
- **M3 触觉四点**：designsystem 新增触觉小工具（封装 `LocalHapticFeedback`，确认/拒绝类经 `view.performHapticFeedback(CONFIRM/REJECT)` 补足——BOM 当前只有 LongPress/TextHandleMove）；接入四处：记录保存成功（`RecordComposer` onSaved）、删除确认执行（`LogDialogHost`）、计时器开始/暂停与完成（`TimerScreen`）、底部长按切宝宝（`MainActivity`）；尊重系统触觉设置，不加新依赖。
- **M4 Typography**：`LeziTypography.material()`（`Tokens.kt:656-683`）补齐 displayMedium/displaySmall/headlineLarge 三槽；计时器大数字新增 `Metric`/`MetricSm` 档（含 tokens.json 同步）并经 `LeziThemeExt.typography` 消费，替换 `TimerScreen.kt:477` 内联 28sp。
- **M5 Glance 主题**：`CareWidget.kt:61` 传入 `GlanceThemeColors`，映射 `LeziColors` light/dark（跟随系统暗色）；宝宝主题色**不**进 widget。
- **M6 UX 文档漂移**（放发版票顺带）：`docs/spec/architecture.md:143` 同步「连续绝对时间轴」措辞；`docs/spec/product.md:336` 收窄坞槽「左右手偏好」措辞（与 `CONTEXT.md:257`/`contracts/ui.md:105` 对齐）。

- **合同文档**：零 wire、零 schema、零同步协议改动（S1/S2 仅域内台账与回执，协议不变）。
  `docs/spec/contracts/ui.md` 若需为 surfaceContainer 梯度与触觉点补一句 token 说明，随票带。
  不新 ADR。
- **测试缝（现有最高缝，不新建门面）**：sync 引擎 fake-backend 测试、
  `IsolatedLeziSyncServer` 真服务端 seam、domain 协调器单测、Token 契约测试
  （`MotionDensityTokensTest` / `ElderModeContrastTest` 先例）、Tier B 文案 JVM 测试
  （`FamilyErrorCopyTest` / `ClearRecordsFailureCopyTest` 先例）、semantics androidTest
  （`FamilyAccountAffordanceSemanticsTest` / `ElderMode*DeviceTest` 先例）、
  `H42ConflictDeviceAcceptanceTest` 先例。

## Testing Decisions

**数据安全（S1–S7）**

- **S1**：sync 引擎测试（fake backend，先例 `ReplicaSyncEngineCensusReconcileTest`）——dismiss 后服务器 rev 前进 → 不复活；服务器墓碑 → 本机保持墓碑；census 对账不再报 mismatch。domain 投影测试（先例 `ConflictInboxProjectionTest`）——台账命中 uuid 不出卡。
- **S2**：domain 测试（先例 `CareLogBabyProfileTest`）——合并前已有冻结信封 → abandon 被调用且旧信封不再提交；引擎 abandoned 逃逸（先例 `RealSyncPortCarePlanFulfillTest`）。
- **S3**：Compose 设备测试（先例 `H42ConflictDeviceAcceptanceTest`）——pending>0 时弹窗含计数与「先同步」；引擎测试——清理收据先于清库持久化（先例 `ProcessDeathRecoveryAcceptanceTest` 的 durable-before-act 模式）。
- **S4**：backend seam 测试（先例 `RealServerMediaReceiptFaultSeamTest` / `IsolatedLeziSyncServer`）——大 body 流式落盘、sha256 与既有值一致、超 cap 中断且 staging 清理。
- **S5**：domain 单测——越界 payload 落库抛错（先例 addRecord 既有异常断言）。
- **S6**：`./gradlew test` 全绿；`connectedDebugAndroidTest` 中 `FreshDatabaseTest` 与 `LocalDataContractMigrationDeviceTest` 通过。
- **S7**：纯文档，随 review 走读。

**UX 信任面与动效（T1–T5、M1–M6）**

- 只测对外行为与可见状态：收件箱首帧不闪假空态、失败可重试；空周期/空范围有空态卡；导出错误文案与异常类型对应、0 记录不产文件、成功/取消有终态；行异常可被 TalkBack 读出；通知有暂停/结束且运行中不每秒 notify；scheme 槽位不等于 baseline；时长/缓动全部来自 token；触觉点调用发生。不测私有函数名。
- **JVM**：收件箱相位状态机与文案；导出归因表驱动（超时/IO/未识别 → 各自文案）；Typography 15 槽映射完整性 + Metric 档存在；Token 契约测试扩展到 surfaceContainer 系与 LeziEasing（含 `design/tokens.json` 同步断言）；通知 builder（chronometer flag、action 数、状态词、图标资源）纯函数单测。
- **androidTest/设备**：RecordRow 异常 stateDescription；触觉四点设备测试（先例 `LayoutMotionHapticsDeviceTest`）；暗色底部弹层/表盘底色截屏对比；通知暂停/结束 action 真机冒烟（锁屏可达、结束进完成表单）。

**验收基线照旧**：`cargo` 三闸不涉及（零服务端代码）；发布票保留两台家庭机装 APK + 冒烟
人工步，冒烟清单合并两流验证点（数据安全：dismiss 后家人修改不复活、宝宝合并、退出设备
计数披露；UX：暗色收件箱/弹层、空周期汇总、导出一次含失败重试、通知上暂停并结束一次
计时、长辈模式开至关各过一遍记录页）。0.5.1/0.5.2/0.5.3 三张发布票共同欠的「两台真机
装 APK + 冒烟」由本次一并覆盖。

## Out of Scope

- NAS 全量备份 / 设备全量导出（G4，第二批）；causal `updated_at` 未来上界（G6，需 ADR + 抬 floor）；census **拉取洞方向**（dismissed-skip）的终局语义（G7）；证书到期预警（G8）、本机静态加密与 security-crypto 替换（G9，需 ADR）、property/仿真/fuzz 三件套（G10）、服务端限流补齐（G11）、Log 首页待处理入口与常驻离线指示（G13，需修订 `ui.md`/CONTEXT）。
- 「本机去掉」的撤销/恢复入口（本票只做决定持久，不提供 undo；恢复可见需未来显式 restore 入口，另行立项）。
- UX 审查第 3 波首页「现在怎么样」四项（距上次喂奶/尿布 chip、首页承载进行中计时、跨天补录日期 chip、布局编辑 More 入口）——0.5.5 候选，产品细节未定。
- 平台升级波：AGP/Kotlin/Compose BOM/Room 升级、targetSdk 36、预测性返回 opt-in、baseline profile、material3 1.4 MotionScheme。
- 已声明决策不再投入：i18n 资源化全量、dynamic color、WorkManager 后台同步（后台同步亦为同步线冻结决策）。
- 工程卫生机会项：MainActivity 拆分与双路由分类器合并、`api(core:database)` 双降、裸 dp/alpha 全量迁移、LazyColumn contentType/remember 微优化、type-safe navigation。
- 删除撤销（Snackbar undo）模式统一、成员页三态迁移与下拉刷新（P3 打磨池）。
- 任何 NAS/服务端代码改动与 NAS CD；证书相关一切操作。

## Further Notes

- 本 spec 由两条 0.5.4 规划流于 2026-09-13 二合一；pre-merge 原稿存于 `sources/`（local-data-safety 与 ux-trust-motion-polish 两份 spec），内容已全部吸收，勿再回老路径找。
- 被对抗校验证伪的论断见来源审查 §5（如「invalid_domain 折叠是缺陷」「/v1/bundles 应退役」「共享密钥丢归属」均不成立）；UX 第一轮被复核推翻的结论见 REVIEW.md 附录 A。两边都是防复查回潮记录。
- S1 的普查抑制是明示的产品折衷：本机被去掉的条目永久不参与活集对账；文案已同步披露。
- 术语红线（CONTEXT.md）：「待处理」仍是单一徽章 + 同一底栏，未对齐项不伪造 `conflict_id`；「本机去掉」不得表述为家庭删除；UI 不出现 "Owner"。T1 的收件箱文案遵守同一红线。
- T1 依赖 S1 的收件箱投影（台账命中不出卡）先落地，避免相位建模与投影变化互相踩；其余票互不阻塞。
- 工单见 `ISSUES.md`：01–17 除 08 依赖 01 外均可并行；18（发版）等 01–17。
