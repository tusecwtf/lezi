---
triage: ready-for-agent
title: 0.5.4 UX 信任面与动效设计收尾：收件箱状态诚实、空态补全、计时器通知可操作、surfaceContainer 与缓动 token 落地
tracker: .scratch
decisions: 2026-09-13 owner——0.5.4 范围=体验审查（.scratch/2026-09-uiux-best-practice-review/REVIEW.md）第 1 波信任面 5 项 + 第 2 波动效/设计收尾 6 项（收编 motion-polish 票 02/03/04）；第 3 波首页「现在怎么样」留 0.5.5；0.5.4 为 APK 单侧发版，服务端停 0.5.3 不动
---

# 0.5.4 UX 信任面与动效设计收尾

## Problem Statement

2026-09-13 对整个 APK 做了只读体验审查（9 路并行专项 + 3 路对抗复核，结论以复核后裁定为准，
全文与改判记录见
[`../2026-09-uiux-best-practice-review/REVIEW.md`](../2026-09-uiux-best-practice-review/REVIEW.md)）。
对抗复核后**没有存活的 P1 用户可感知缺陷**；本版收编的是复核裁定的 P2「信任面」簇与
P2 设计系统/动效收尾簇——它们都落在同步、汇总、导出这类最需要家长信任的界面上，
或属于自家研究早已指出、票据已开却未落地的最后一公里。

**信任面（5 项，均已逐行验证）：**

| # | 现象 | 根因（已验证） |
|---|---|---|
| T1 | 打开冲突收件箱的一瞬先闪「目前没有待处理项」再变成有项；部分行显示「删除状态待加载」「提交者详情待加载」这类工程词；整页没有加载中/失败形态 | inbox StateFlow 初值是空 `ConflictInbox()`，「未加载」与「确实为空」不可区分（`ConflictInboxRoute.kt:51`）；sheet 无 loading/失败相位（`:62-93`）；未就绪字段直出「待加载」文案（`:106-127`） |
| T2 | 汇总页把范围切到一段没有记录的周期时，KPI 显示一排 0、图表整卡消失，没有「这个范围没有记录」的空态 | `SummaryUi.empty` 已建模（`SummaryScreen.kt:199`、`SummaryAggregation.kt:426,443`）但渲染层无分支（`SummaryScreen.kt:373-529`） |
| T3 | 导出失败时磁盘满、权限等系统故障一律被解释成「输入无效」；范围内没有记录也能生成近空文件；生成期间只有按钮文案变化，成功/取消后没有任何「文件在哪」的终态反馈 | catch Throwable → `InvalidInput`（`ExportScreen.kt:171-178`、share 分支 `:202-211`）；仅超时有独立分类 `ExportTookTooLong`（`:165`）；全文件无空态与成功终态 |
| T4 | 时间线记录行的异常「!」纯视觉，TalkBack 用户听不出该行有异常 | `RecordRow.kt` 三处变体（`:98-105,167-174,254-261`）整文件无 semantics/stateDescription |
| T5 | 锁屏/通知上的喂奶计时无法暂停或结束，必须回到 App；通知每秒全量重建一次；小图标是系统 `android.R.drawable` | `NursingTimerService.kt:110-118` ticker 每秒 `notify`；`:153-171` 仅 contentIntent 无 action；`:166` 系统图标（已有缓解：IMPORTANCE_LOW 通道 + `setOnlyAlertOnce`） |

**动效/设计收尾（6 项）：**

| # | 现象 | 根因（已验证） |
|---|---|---|
| D1 | 暗色主题下底部弹层、时钟表盘等系统组件底色是 M3 baseline 紫黑（#211F26 系），与品牌蓝黑（#15202C）脱节；浅色差 1.12:1 较隐蔽 | 四套 scheme 均未覆写 `surfaceContainer*` / `surfaceBright/Dim/Tint`（`Theme.kt:190-296`）；7 处 `ModalBottomSheet` 未设 `containerColor` 吃默认（`RecordComposer.kt:113`、`LogDialogHost.kt:223`、`TimerScreen.kt:249`、`FamilyMembersListUi.kt:191`、`ConflictInboxRoute.kt:62`、`CausalProductSurfaces.kt:63,212`） |
| D2 | 缓动无 token（全库仅 6 处显式 FastOutSlowIn，其余隐式同曲线）；约 15 处动画时长游离于 `LeziMotion` 三档外；页面级 push 转场偏快（150/200ms） | `.scratch/motion-polish/issues/02,03,04` 均 ready-for-agent。**定性修正（复核裁定）**：游离时长均为协程动画，受系统 MotionDurationScale 缩放，reduce-motion 并未失效——票 03 属 token 一致性治理，不是可用性缺陷 |
| D3 | 触觉反馈全库仅 swipe 阈值与布局拖拽两处；记录保存成功、删除确认、计时器启停、底部长按切宝宝都没有 | `SwipeEditDeleteRow.kt:259`、`LayoutEditMode.kt:274` 为生产代码唯二触觉点 |
| D4 | `MaterialTheme.typography` 映射缺 displayMedium/displaySmall/headlineLarge 三槽（latent 漂移）；计时器大数字 28sp 内联绕过字阶 | `Tokens.kt:656-683`；`TimerScreen.kt:477` |
| D5 | 桌面小组件用默认 `GlanceTheme`（Android 12+ 走系统动态色），与品牌色板零关联 | `CareWidget.kt:61` |
| D6 | 文档权威漂移两处（复核裁定的唯一 P0） | `docs/spec/architecture.md:143` 仍写「3 日时间轴」（ADR-0024/`contracts/ui.md:126` 已是连续绝对时间轴）；`docs/spec/product.md:336` 坞槽「左右手偏好持久化」措辞与 `CONTEXT.md:257`/`contracts/ui.md:105`「不镜像惯用手」有歧义 |

第一轮审查中对抗复核已推翻或改判的条目（首页状态摘要、对比度四色、长辈字体机制、
reduce-motion 失效、预测性返回、onboarding 排序等）**不在本版范围**，见 REVIEW.md 附录 A，
不要当作已知问题重复排查。

## Solution

**0.5.4 是 APK 单侧小步版本：让「家里那份日志」的界面状态诚实、让高频路径有终态反馈、
把设计系统与动效自家研究指出的最后三公里走完。** 零 wire 字段、零 schema、零服务端改动——
NAS 容器停在 0.5.3，两台真机直接装 0.5.4 APK。

- **收件箱状态诚实（T1）**：「未加载」不再渲染成「空」；未就绪的行不直出工程词；
  加载失败给可重试的错误态；收件箱顶部固定一句心智解释。
- **空态补全（T2/T3）**：汇总空周期与导出空范围都给 StateContainer 空态；导出按真实
  原因归因错误、生成期有进度、成功/取消有终态。
- **异常可听见（T4）**：记录行的异常「!」进语义树。
- **计时器通知可操作（T5）**：通知带「暂停/继续」与「结束」（结束拉起完成表单，不静默
  丢弃）；运行中交给系统 chronometer 走秒，不再每秒重建通知；换自有单色图标。
- **设计系统下半场（D1–D5）**：surfaceContainer 梯度入四套 scheme（暗色优先验证）；
  落 motion-polish 票 02/03/04（缓动 token、时长归一、push 过渡升档）；触觉铺四个情绪点；
  Typography 补槽 + 计时器数字入阶；Glance 主题接品牌色。
- **文档权威对齐（D6）**：两处一行级修复随发版票带走。

## User Stories

1. 作为家里同时改过同一条记录的家长，我希望打开冲突收件箱时不会先看到「目前没有待处理项」
   再闪出条目，以便我相信这个列表说的是真话。
2. 作为不熟悉同步机制的家长，我希望收件箱顶部有一句解释（什么时候东西会出现在这里），
   且看不到「删除状态待加载」这类我听不懂的词。
3. 作为收件箱加载失败的家长，我希望看到明确的错误提示并能原地重试，而不是一张静止的列表。
4. 作为翻看上周汇总的家长，我希望某段范围没有记录时直接被告知「这个范围还没有记录」，
   而不是对着四个 0 和一张消失的图表猜。
5. 作为导出被磁盘问题打断的家长，我希望错误文案说的是「写入失败」而不是「输入无效」，
   以便我知道该清空间储而不是检查日期。
6. 作为导出大范围带照片的家长，我希望生成期间看到进度、完成后知道文件在哪里、
   即使分享了取消也告诉我文件还在。
7. 作为使用 TalkBack 的家长，我希望滑到带「!」的记录行时能听到「有异常」，
   而不是毫无察觉地略过。
8. as a 夜里正在喂奶、手机在充电的家长，我希望在锁屏通知上直接暂停或结束计时，
   不必解锁再进 App。
9. 作为在通知上点「结束」的家长，我希望被带到完成表单确认，而不是计时被悄悄丢掉。
10. 作为深夜使用暗色主题的家长，我希望底部弹层和表盘的底色与 App 其余部分是同一套蓝黑，
    而不是突兀的紫灰。
11. 作为记录完一条喂奶的家长，我希望保存成功时有轻微触觉确认，以便我敢直接锁屏。
12. 作为维护者，我希望 surfaceContainer、缓动、时长、字号全部从 token 单源取值，
    并有契约测试守住，不再靠纪律。
13. 作为维护者，我希望 0.5.4 不需要动 NAS：换 APK 即完成升级，app-update.json 的渠道刷新
    留到下次有服务端改动的维护窗一并做。
14. 作为维护者，我希望 architecture.md 与 product.md 里两处旧措辞随本版对齐，
    使 docs/spec 恢复与 ADR-0024 一致。

## Implementation Decisions

- **范围与版本**：11 张功能/收尾票 + 1 张发版票。版本身份 0.5.4 / versionCode 34
  ——**0.5.4 为共享发布列车**：同日并行的
  [`../0.5.4-local-data-safety/spec.md`](../0.5.4-local-data-safety/spec.md)
  （dismiss 耐久化、宝宝合并×冻结信封、破坏性路径守卫等）钉的是同一个 0.5.4/34，两条流
  在发版票合并为**一次** versionCode bump 与**一次**两台真机冒烟，分别覆盖各自验证点；
  `config/android-release-compatibility.json` 只追加一行账本。Room 29、本地数据契约 6、
  server schema 13、同步 floor 21 全部不动；服务端 Cargo 停 0.5.3，本版**无 NAS CD**
  （`deploy/app-update.json` 渠道目标是否顺手刷新由发版票决定，默认不动）。发版票顺带修
  D6 两处文档漂移。
- **T1 收件箱相位建模**：`ConflictInboxRoute.kt` 的流状态改为区分「未加载/加载中/空/
  错误/内容」相位（search 页五相位是仓内范本，`SearchScreen.kt:196-241`）。初值不再可被
  渲染为空态；加载失败态复用 `StateContainer(Error)` + 重试，接既有刷新链路。未就绪的
  元数据行**不渲染该行**，整卡未就绪时给「正在获取详情…」；「处理/审阅」动词保持
  424fc4a2 的对齐。顶部固定一句心智解释（文案走 Tier B 纯函数常量 + JVM 测试锁定，
  ui-copy-hardening 模式）。不新增 wire 字段。
- **T2 汇总空态**：`SummaryContent` 增加 `ui.empty` 分支——空周期时图表区渲染
  `StateContainer(Empty)`（复用 `ActionStateComponents.kt:127` 组件），文案说明该范围无
  记录并指向底栏记录页；KPI 区维持现状（0 值可接受，不是本票目标）。不做跨页跳转按钮。
- **T3 导出归因与终态**：
  - 归因抽纯函数（先例 `ClearRecordsFailureCopyPolicy`）：`ExportTookTooLong` 维持；
    IOException/磁盘类映射到「写入失败，记录未受影响」语族；**未识别异常走
    `productUiError` 兜底**，不再一律 `InvalidInput`。优先复用 `core/ui` 既有 FailureKind，
    确实缺「写入失败」档才允许在票内最小新增。
  - 生成前检查范围内记录数为 0 → `StateContainer(Empty)`，不产出近空文件。
  - 生成期在按钮区上方加不确定进度条 + 一句「正在生成，最长约半分钟」（不新增
    ExportPort 阶段回调 seam）。
  - 终态：成功后 Snackbar「已生成 · 可分享」（含按龄清理的保留说明）；sharesheet 取消
    同样给「文件已生成，可在本页再次分享」。
- **T4 RecordRow 异常语义**：三处变体统一在行级 semantics 追加 `stateDescription`
  异常词（无异常不追加）；文案 Tier B 常量 + JVM 测试。先例：`LogDialogHost.kt:179-180`
  的 stateDescription、`RecordSummary.spokenValue` 下沉模式。
- **T5 计时器通知**：
  - 运行中 `setUsesChronometer(true)` + `setWhen()`（系统走秒），ticker 只在左右切换/
    暂停/恢复时各 notify 一次，删除每秒重建；暂停态用静态文本 + 状态词
    （「进行中 · 左/右」「已暂停」）。页内 200ms tick（`TimerScreen.kt:134-140`）不变。
  - 新增两个 `NotificationCompat.Action`：「暂停/继续」直接以 service intent 作用于服务；
    「结束」拉起 App 的完成表单（复用既有 completion 路径，**不**静默丢弃）。
  - 小图标换自有单色（monochrome）drawable。
  - 通道 IMPORTANCE_LOW 与 `setOnlyAlertOnce` 保持。
- **D1 surfaceContainer 梯度**：`resolveLeziColorScheme`（`Theme.kt:298-327`）内对四套
  scheme copy 补 `surfaceContainerLowest/Low/Container/High/Highest/Bright/Dim` 与
  `surfaceTint`，值入 `LeziColors` 单源（避免第三处内联字面量）；7 处 ModalBottomSheet
  **不改 call site** 即吃新梯度。暗色优先真机/预览验证。顺带清理零消费 token
  `JournalMuted`、`Warning`（`Tokens.kt:84,47`，删除或注明保留理由）。
- **D2 motion-polish 票收编**：按既有票内容落地——票 02 `LeziEasing`（值取
  `research.md` AAR 口径 decelerate `(0.1,0.7,0.1,1)` / accelerate `(0.3,0,0.8,0.2)`）入
  `Tokens.kt` + `design/tokens.json` 同步 + 契约测试；票 03 五文件游离时长归一
  （`NextFeedPlanFlow.kt:88-89`、`TimelineComponents.kt:189,195`、
  `LocalDataRecoveryScreen.kt:113`、`SwipeEditDeleteRow.kt:71`、`LogTimelineList.kt:539`，
  以票内清单为准）；票 04 全屏 push enter 升 Emphasized 300 + EmphasizedDecelerate、
  exit 保持 Fast + Accelerate、tab 切换维持纯 fade Fast（`MainActivity.kt:1424-1447`）。
  **不**引入 material3 1.4 `MotionScheme`（随平台升级波）。
- **D3 触觉四点**：designsystem 新增触觉小工具（封装 `LocalHapticFeedback`，确认/拒绝
  类经 `view.performHapticFeedback(CONFIRM/REJECT)` 补足——BOM 当前只有
  LongPress/TextHandleMove）；接入四处：记录保存成功（`RecordComposer` onSaved）、
  删除确认执行（`LogDialogHost`）、计时器开始/暂停与完成（`TimerScreen`）、底部长按切宝宝
  （`MainActivity`）；尊重系统触觉设置，不加新依赖。
- **D4 Typography**：`LeziTypography.material()`（`Tokens.kt:656-683`）补齐
  displayMedium/displaySmall/headlineLarge 三槽；计时器大数字新增 `Metric`/`MetricSm`
  档（含 tokens.json 同步）并经 `LeziThemeExt.typography` 消费，替换
  `TimerScreen.kt:477` 内联 28sp。
- **D5 Glance 主题**：`CareWidget.kt:61` 传入 `GlanceThemeColors`，映射 `LeziColors`
  light/dark（跟随系统暗色）；宝宝主题色**不**进 widget。
- **合同文档**：零 wire、零 schema、零同步协议改动。`docs/spec/contracts/ui.md` 若需为
  surfaceContainer 梯度与触觉点补一句 token 说明，随票带；D6 两处漂移修复放发版票
  （`architecture.md:143`、`product.md:336`）。不新 ADR。
- **测试缝（现有最高缝，不新建门面）**：Token 契约测试
  （`MotionDensityTokensTest` / `ElderModeContrastTest` 先例）、Tier B 文案 JVM 测试
  （`FamilyErrorCopyTest` / `ClearRecordsFailureCopyTest` 先例）、通知 builder 纯函数化
  单测、semantics androidTest（`FamilyAccountAffordanceSemanticsTest` /
  `ElderMode*DeviceTest` 先例）、触觉设备测试（`LayoutMotionHapticsDeviceTest` 先例）。

## Testing Decisions

- 只测对外行为与可见状态：收件箱首帧不闪假空态、失败可重试；空周期/空范围有空态卡；
  导出错误文案与异常类型对应、0 记录不产文件、成功/取消有终态；行异常可被 TalkBack 读出；
  通知有暂停/结束且运行中不每秒 notify；scheme 槽位不等于 baseline；时长/缓动全部来自
  token；触觉点调用发生。不测私有函数名。
- **JVM**：收件箱相位状态机与文案；导出归因表驱动（超时/IO/未识别 → 各自文案）；
  Typography 15 槽映射完整性 + Metric 档存在；Token 契约测试扩展到 surfaceContainer 系
  与 LeziEasing（含 `design/tokens.json` 同步断言）；通知 builder（chronometer flag、
  action 数、状态词、图标资源）纯函数单测。
- **androidTest/设备**：RecordRow 异常 stateDescription（先例
  `FamilyAccountAffordanceSemanticsTest`）；触觉四点设备测试（先例
  `LayoutMotionHapticsDeviceTest`）；暗色底部弹层/表盘底色截屏对比；通知暂停/结束
  action 真机冒烟（锁屏可达、结束进完成表单）。
- **发布冒烟**：相关 JVM 三件套 + lintDebug；两台真机装 0.5.4：暗色开收件箱/弹层、
  空周期汇总、导出一次含失败重试、通知上暂停并结束一次计时、长辈模式开至关各过一遍
  记录页。NAS 不动，无证书/CD 步骤。

## Out of Scope

- 第 3 波首页「现在怎么样」四项（距上次喂奶/尿布 chip、首页承载进行中计时、跨天补录
  日期 chip、布局编辑 More 入口）——0.5.5 候选，产品细节未定。
- 平台升级波：AGP/Kotlin/Compose BOM/Room 升级、targetSdk 36、预测性返回 opt-in、
  baseline profile、material3 1.4 MotionScheme。
- 已声明决策不再投入：i18n 资源化全量、dynamic color、WorkManager 后台同步。
- 工程卫生机会项：MainActivity 拆分与双路由分类器合并、`api(core:database)` 双降、
  裸 dp/alpha 全量迁移、LazyColumn contentType/remember 微优化、type-safe navigation。
- 删除撤销（Snackbar undo）模式统一、成员页三态迁移与下拉刷新（P3 打磨池）。
- 任何 NAS/服务端改动与 NAS CD；证书相关一切操作。

## Further Notes

- 事实基础与逐条证据：[`../2026-09-uiux-best-practice-review/REVIEW.md`](../2026-09-uiux-best-practice-review/REVIEW.md)；
  其附录 A 记录了对抗复核推翻的第一轮结论，排 implement 时不要把它们当需求捡回来。
- motion-polish 票 01（开屏闪屏）已 done，不收编；02/03/04 由本版票 07 收编执行，
  原票内容为准，落地后回写原票状态。
- 前置：0.5.1/0.5.2/0.5.3 三张发布票共同的「两台真机装 APK + 冒烟」应先关或与本版
  冒烟合并执行（一次装 0.5.4 即覆盖前三版的真机验证点，见各发布票冒烟清单）。
- 工单见 `ISSUES.md`：01–10 无阻塞可并行；11（发版）等 01–10，并与
  `0.5.4-local-data-safety` 的发布合并执行（同一维护窗、同一 0.5.4/34、一次冒烟）。
- **与 0.5.4-local-data-safety 的接缝**：该流的 dismiss 耐久化与「收件箱投影不再出卡」
  改在域层（`ConflictResolutionCoordinator` / 引擎 apply 路径），本版票 01 改的是收件箱的
  **UI 状态呈现**（相位、文案、错误态），两者互补不冲突；实现票 01 时以其落地后的投影
  行为为准，避免相位建模与投影变化互相踩。
