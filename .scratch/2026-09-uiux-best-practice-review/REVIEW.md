# 乐记 APK 全面体验审查 · 业界最佳实践对比与更新方向

日期：2026-09-13 · 基线：master `dee1b927`（tree 身份 0.5.3 / versionCode 33，0.5.2+0.5.3 版本 bump 在工作区未提交）
> 2026-09-13 后续：第 1 波（信任面）+ 第 2 波（动效/设计收尾）已作为 T1–T5/M1–M6 并入
> [`../0.5.4-data-safety-and-ux-polish/spec.md`](../0.5.4-data-safety-and-ux-polish/spec.md)
> （与同步冲突审查的数据安全线 S1–S7 二合一）；第 3 波首页「现在怎么样」留作 0.5.5 候选；
> 第 4 波平台升级与本文件 §7 的不建议项维持原判。

审查方式：**只读**。第一轮 9 路并行专项审查（架构 / 设计系统 / 动效 / 交互与页面状态 / 无障碍与文案 / 日志首页时间线 / 其余功能屏幕 / 产品规格与迭代对齐 / 平台版本与性能），第二轮 3 路对抗式复核（code-reviewer 对第一轮结论逐条证伪 + 找第一轮盲区）。**本文所有 gap 与优先级均以对抗复核后的裁定为准**；被证伪或改判的第一轮结论在附录 A 留痕，防止后续被当作已知问题重复排查。

---

## 1. TL;DR

这是一套**工程与设计纪律达到旗舰水准**的 Compose 应用：分层即文档且逐条与代码吻合、token 化设计系统带契约测试、三态/触控目标/返回保护/危险操作确认全有组件级统一、a11y（48dp、sp、fontScale 工程、语义状态、liveRegion）远超同类、错误文案诚实克制。对抗复核后的结论是：**第一轮报告的多数 P1 用户体验缺陷属于误报、已声明决策或严重度夸大；当前不存在存活的 P1 用户可感知缺陷**。

真实的 gap 集中在四处：

1. **一处 P0 文档权威漂移**：`docs/spec/architecture.md:143` 仍写「3 日时间轴」，与 ADR-0024 / `contracts/ui.md:126` 的「连续绝对时间轴」矛盾（docs/spec 是本仓权威，一行级修复）。
2. **发布流程尾**：92 个提交未推送、0.5.2/0.5.3 两次版本身份 bump 全部未提交、三个版本共同卡在「两台真机装 APK + 冒烟」这最后一步。
3. **一个 P2「信任面」簇**：冲突收件箱首帧假空态闪现 + 「待加载」工程词泄漏、Summary 空周期无空态、导出错误归因失真——都发生在同步/数据这类最需要用户信任的界面上。
4. **设计系统「下半场」**：surfaceContainer 系语义角色未覆写（暗色下可见 baseline 紫灰）、motion-polish 票 02/03/04 仍 ready-for-agent、timer 通知不可操作等一批小额收尾。

平台债务（Compose BOM 2024.12 / Kotlin 2.1 / Room 2.6 / AGP 8.7）约落后两年，但对侧载两台真机的自用 App **无用户可见影响**，列为机会性波次而非欠账。

---

## 2. 整体画像：各维度对照业界位置

| 维度 | 现状 | 业界对照位置 |
|---|---|---|
| 模块架构 | feature 零 DAO 引用、UDF + Hilt 规范、CareLog/SyncPort 门面、374 单测文件 | 教科书级；欠账仅 Navigation 字符串路由、MainActivity 1903 行 |
| 设计系统 | 四套 scheme + 全量 token + 契约测试 + 硬编码 Color 字面量 **0** 处 | 自建设计系统上游水准；欠 surfaceContainer 梯度与 Typography 全槽位映射 |
| 动效基建 | LeziMotion 三档 + 系统动画缩放动态监听 + reduce-motion + 全量盘点文档 | 旗舰水准；票 02/03/04（缓动 token/时长归一/页面过渡档位）待落地 |
| 交互三态 | StateContainer 统一组件 + 搜索页五相位范本 + 失败安全文案 | 上游；收件箱/导出/Summary 空态三处局部失守 |
| 无障碍 | contentDescription 纪律、fontScale 非线性修正、a11y 设备测试 | 接近绿灯；RecordRow 异常「!」无语义是主要残留 |
| 文案 | 纯函数中文常量 + JVM 测试锁定 + 四段式错误解释 | 同类中文产品上游；仅冲突收件箱残留工程词 |
| 平台跟进度 | BOM 2024.12 / Kotlin 2.1 / Room 2.6 / targetSdk 35 | 落后约两年，但侧载场景无硬约束；版本策略有 Gradle 守护（`app/build.gradle.kts:293-313`） |
| 性能工程 | LazyColumn key 100%、窗口化 Room、自研 bounded 图片管线、R8+逐 APK 验签 | 几乎无可挑剔；缺 baseline profile（不值得排期） |

---

## 3. 已声明决策（对抗复核后移出 gap 栏）

以下条目第一轮曾被列为 gap，复核确认是 **spec/ADR 明文决策或产品意图**，不应再当作欠账：

- **界面仅中文**：`product.md:16,97` 明文「语言：简体中文」「界面仅中文」。约 2300 行中文字面量不是 i18n 欠账；代价仅是「改文案需重编 APK」，且 Tier B 常量单源 + JVM 测试已兜住漂移。ui-copy-hardening 票 05 Tier A 余量按需推进即可。
- **仅前台同步、不做 WorkManager**：`layers/sync.md:20,89` 明文「不做 WorkManager 后台轮询」「仅前台执行」。NAS 仅局域网可达，前台驱动是正确取舍。
- **Onboarding 连接优先**：主 CTA「连接家庭服务器」（`OnboardingWizardSteps.kt:104-127`）与产品核心前提（家庭 NAS 同步）一致，endpoint 已验证时 CTA 变「继续登录」，离线一键可达——是设计意图不是门槛。
- **不做 dynamic color**：宝宝主题色驱动 primary 是产品核心（PRD §2.1）；建议仅在 `contracts/ui.md` 补一句决策记录防后续误判，无需实现。
- **图表自绘不引第三方库**（`platform.md:26`）、**timer 仅喂奶单活动**：均为声明的产品范围。

---

## 4. Gap 清单（对抗复核后）

### 4.1 P0 — 文档权威漂移（立即，一行级）

| # | 问题 | 证据 | 修复 |
|---|---|---|---|
| 1 | `architecture.md` 仍写「3 日时间轴」，与 ADR-0024、`contracts/ui.md:126`、`features.md:47-48` 的「连续绝对时间轴、无天数上限」矛盾 | `docs/spec/architecture.md:143` | 同步措辞 |
| 2 | product.md 坞槽「左右手偏好持久化」措辞与 CONTEXT.md:257 / `contracts/ui.md:105`「不镜像惯用手」存在歧义（语义可兼容但易误读） | `docs/spec/product.md:336` | 收窄措辞 |

### 4.2 P2 — 用户可感知（信任面 + 空态簇）

| # | 问题 | 证据 | 建议 |
|---|---|---|---|
| 1 | **冲突收件箱首帧假空态**：inbox StateFlow 初值 `ConflictInbox()` 使首帧渲染「目前没有待处理项」再闪成有项；未加载字段直出「删除状态待加载」「提交者详情待加载」工程词；无 loading/失败态 | `ConflictInboxRoute.kt:51,62-93,106-127` | 初值改 null 区分「未加载/空」；未加载行不渲染或「正在获取详情」；顶部加一句心智解释（「两边设备同时改了同一条记录时会出现在这里」） |
| 2 | **Summary 空周期无空态**：`SummaryUi.empty` 已建模但渲染无分支，空周期显示 0 值 KPI、图表整卡消失 | `SummaryScreen.kt:199,259,373-529` | 空 range 时图表区给 `StateContainer(Empty)` + 去记录 CTA |
| 3 | **导出错误归因失真**：catch Throwable → `InvalidInput`，磁盘满/权限等系统故障伪装成「输入无效」；范围内无记录无空态、生成期无进度反馈、成功/取消后无终态 | `ExportScreen.kt:171-178,202-211` | 按异常映射 FailureKind；0 记录给空态；生成期加不确定进度 + 阶段文案 |
| 4 | **RecordRow 异常「!」纯视觉**：三处变体整文件无 semantics，TalkBack 用户听不出该行异常 | `RecordRow.kt:98-105,167-174,254-261` | 行 semantics 加 stateDescription「有异常」 |
| 5 | **计时器通知不可操作**：无 action 按钮（锁屏无法暂停/结束）、每秒 notify() 全量重建、小图标用系统 `android.R.drawable` | `NursingTimerService.kt:110-118,153-171,166` | 加「暂停/结束」action；`setUsesChronometer(true)` 交系统计时；换自有图标；文本带「进行中·左/已暂停」状态词 |

### 4.3 P2 — 设计系统与动效收尾

| # | 问题 | 证据 | 建议 |
|---|---|---|---|
| 1 | surfaceContainer/surfaceBright/Dim/Tint 四套 scheme 均未覆写，7 处 `ModalBottomSheet` 未设 containerColor 吃 M3 baseline 色板；**暗色下可见**（baseline 紫黑 #211F26 vs 品牌蓝黑 #15202C），浅色差 1.12:1 较隐蔽 | `Theme.kt:190-296`；`RecordComposer.kt:113`、`LogDialogHost.kt:223`、`TimerScreen.kt:249`、`FamilyMembersListUi.kt:191`、`ConflictInboxRoute.kt:62`、`CausalProductSurfaces.kt:63,212` | `resolveLeziColorScheme` 内 copy 补齐 7 个槽位（按品牌梯度，dark 优先验证）+ 契约测试 |
| 2 | 长辈模式残余：直接引静态 `LeziTypography.*` 的组件漏掉 elderTextStyle 的行高≥1.5em/负字距归零/无衬线切换（字号缩放有 `stackedElderDensity` 兜底，不致破版），典型是 RecordRow 长辈行自身用静态样式 | `Tokens.kt:747-759`；`RecordRow.kt:250,259` | RecordRow 等高频组件改经 `LeziThemeExt.typography`；CI 禁新增静态引用（可选） |
| 3 | motion-polish 票 02（LeziEasing 缓动 token）/ 03（约 15 处游离时长）/ 04（页面级 push 过渡升 medium 档 + emphasized 缓动）均 ready-for-agent 未落 | `.scratch/motion-polish/issues/02,03,04`；`MainActivity.kt:1424-1447` | 按票落地；注意票 03 的定性修正：这些是协程动画，受系统 MotionDurationScale 缩放，**reduce-motion 并未失效**，属 token 一致性治理 |
| 4 | 触觉反馈仅 2 处（swipe 阈值、布局拖拽）；保存成功/删除确认/计时器启停/底部长按切宝宝均无 | `SwipeEditDeleteRow.kt:259`、`LayoutEditMode.kt:274` 为全库唯二 | 抽 `leziHapticFeedback()` 工具先铺四个情绪点 |
| 5 | Widget 的 `GlanceTheme` 用默认（Android 12+ 走系统动态色），与 App 品牌/宝宝主题色零关联 | `CareWidget.kt:61` | 传 `GlanceThemeColors` 映射 LeziColors light/dark |
| 6 | `MaterialTheme.typography` 映射缺 displayMedium/displaySmall/headlineLarge 三槽（latent 漂移）；Timer 计时数字 `fontSize=28.sp` 内联绕过字阶 | `Tokens.kt:656-683`；`TimerScreen.kt:477` | 各一行级补齐 |

### 4.4 P2/P3 — 首页与关键路径增强（按 owner 取舍）

| # | 问题 | 证据 | 建议 |
|---|---|---|---|
| 1 | 首页状态呈现已覆盖「睡觉中」（`AppHeader.kt:774-780` + `MainActivity.kt:503`），但**喂奶计时 handoff 到 Timer 后首页不可见**（rail 只画 open sleep，`LogTimeline.kt:108`） | `ComposerTimerHandoff.kt` | 状态行兼容进行中计时 |
| 2 | 「距上次喂奶/尿布 X 小时」聚合缺失，只能逐行看 relative 标签 | `LogTimelineList.kt:674` | 汇总行上方加「现在」chip（业界同类标配，低成本高感知） |
| 3 | 跨天补录不可发现：composer 无日期字段，记「昨晚 23:50」需先切日再改表盘 | `LogScreen.kt:187-191` | 时间行旁加「昨天」chip |
| 4 | 布局编辑入口纯长按隐藏，发现率趋近零 | `LogScreen.kt:463-465` | More sheet 顶部加文字入口 |
| 5 | rail 视窗 [now-24h, now] 跨两个日界，列表只含选中日，夜奶 00:30 归属可能违背直觉 | `TimelineInteraction.kt:12,469-485`；`LogTimelineList.kt:169-171` | rail 副标题标注视窗或列表头加限定词（先真机验证是否真困惑） |

### 4.5 P3 — 打磨与工程卫生（机会性）

- **MainActivity.kt 1903 行**：NavHost + chrome + 强更 + RootViewModel 同文件；且存在**两套近似重复的路由分类器**（`:952-956` `isFullScreenPushRoute` 与 `:961-972` 内嵌 `routeOwnsFullScreen`，字符串前缀双份维护易漂移）——拆分时先合并这两处，类型安全路由迁移不必单独立项。
- **`api(core:database)` 双通道**：`domain/build.gradle.kts:32` 与 `sync/build.gradle.kts:69` 均 `api` 暴露 DAO；feature 现零引用、app 显式自带依赖，双降 implementation 零破坏（只降 sync 治标）。
- 裸 dp 145 处 / ad-hoc alpha 23 处（表单类为主，图表 Canvas 可豁免）；`FamilyUiPolicy.kt:532,545` 文本「★」字形残留；Theme scheme 内联 `Color(0xFF…)` 绕过 LeziColors 单源。
- 微优化：`LogTimelineList.kt:505,560-563` LazyColumn scope 内 filter 未 remember；`LogTimelineList.kt:333,505,566` 与 `SearchScreen.kt:247` 缺 contentType；`gradle.properties:4` configuration-cache=false（先查是否插件不兼容所致）。
- 成员/设备页三态裸文本（有菜单「刷新」兜底，缺行内重试）；成员页无下拉刷新；`FamilySharingContent.kt:288,449` traversalIndex 无 isTraversalGroup 配合；死 token 清理（JournalMuted、Warning 全库零消费）。
- Milk 图标 tint #D99534 对白底 2.3-2.5:1 低于 3:1；**修时注意对照面是 toneBg 粉彩 chip 而非 background**（`RecordVisuals.kt:59-62` 以 background 亮度选色，选错了对照面）。
- onboarding 默认昵称「年年」预填 value（`OnboardingScreen.kt:117`）易被无意识提交，应改 placeholder。

---

## 5. 与当前迭代（0.5.x）的对比

- **迭代质量高**：0.5.0→0.5.3 全部由两台真机真实事故驱动（同步活锁、双开睡卡死、时间轴手感、退出闪退），每个修复伴随 spec/ADR/CONTEXT 回写；ticket 纪律与落地记录一一对应，`.scratch/README.md` 与代码现状无虚报。
- **团队已自我跟踪的短板**（本审查确认仍然成立）：motion-polish 票 02/03/04、ui-copy 票 05 Tier A 余量、时间轴 rail 真机调参开放（`TIMELINE_RAIL_FLING_FRICTION_MULTIPLIER = 2.5f` 未定稿）、布局编辑闪退的 adb logcat 静态审计覆盖有限。
- **流程风险（最紧迫）**：master 领先 origin/master **92 个提交未推送**；0.5.2/0.5.3 版本身份 bump（`app/build.gradle.kts` 31→33、catalog、Cargo 0.5.3、`app-update.json`、全部 spec 身份钉）**全部在工作区未提交**；0.5.1/0.5.2/0.5.3 三张发布票唯一未勾选项都是「两台真机装 APK + 冒烟」。
- **竞品对照**（`.scratch/baby-buddy-research/`）：Baby Buddy 是服务器即世界的薄客户端，无离线、无副本、无因果冲突；乐记多出的离线优先 + 因果同步 + 原子照片包是数量级差异的能力，当前短板不在能力面而在上述信任面细节。

---

## 6. 建议更新方向（按波次）

**第 0 波 · 收尾当前迭代（本周内，非新功能）**
1. 提交 0.5.2/0.5.3 版本 bump、推送 92 个提交；两台真机装 0.5.3 APK 冒烟（重点：带照片记录、完成计划后浅同步状态归零、ReturnToNow/LayoutUndo）。
2. 修 `architecture.md:143` 与 `product.md:336` 两处文档漂移。

**第 1 波 · 信任面收尾（小改动、高感知，建议下一个功能版本的主体）**
冲突收件箱假空态 + 工程词 + loading/失败态（4.2-1）→ Summary 空周期空态（4.2-2）→ 导出错误归因/进度/终态（4.2-3）→ RecordRow 异常语义（4.2-4）→ 计时器通知 actions + chronometer + 自有图标（4.2-5）。

**第 2 波 · 设计系统与动效下半场**
surfaceContainer 梯度补齐（dark 优先）+ 契约测试（4.3-1）→ 落 motion-polish 票 02/03/04（03 按「token 一致性」新定性）→ 触觉四点（4.3-4）→ Typography 三槽补齐 + Timer 字号入阶（4.3-6）→ Glance 主题接线（4.3-5）。

**第 3 波 · 首页「现在怎么样」增强（owner 产品取舍）**
距上次喂奶/尿布「现在」chip → 首页承载进行中计时 → 跨天补录日期 chip → 布局编辑 More 入口（4.4-1~4）。

**第 4 波 · 机会性平台升级（单次有序波次，联动版本守护）**
AGP 8.9+ → Kotlin 2.2+ → Compose BOM 2025H1+ → Room 2.8，顺带 targetSdk 36 + `enableOnBackInvokedCallback`（预测性返回）与 material3 1.4 `MotionScheme` 评估；**必须与 `app/build.gradle.kts:293-313` 的 versionCode/升级源连续性守护及 `config/android-release-compatibility.json` 账本同步走**，一次发版只做这一件事。

**顺带卫生项**（随相邻改动捎带，不单独立项）：MainActivity 拆分 + 双路由分类器合并、`api(core:database)` 双降、裸 dp/alpha 与「★」字形、contentType/remember 微优化。

---

## 7. 明确不建议投入的方向

- **i18n 资源化全量迁移**：单语是声明决策，全量迁移收益为零；仅维持 Tier B 常量单源现状。
- **WorkManager 后台同步**：与 `layers/sync.md` 决策冲突，且 NAS 仅局域网可达。
- **dynamic color 全量接入**：与宝宝主题色核心机制冲突。
- **Baseline profile / reportFullyDrawn**：本地 Room + 无网络首屏，冷启动收益与基建成本不成比例。
- **类型安全 Navigation 单独立项迁移**：无深链无 Web，用户零收益；随 MainActivity 拆分顺带评估即可。

---

## 附录 A：对抗复核改判记录（防复查回潮）

| 第一轮结论（原级） | 复核裁定 | 证据 |
|---|---|---|
| 首页无「宝宝现在状态」摘要（P0） | ❌ 大幅夸大→P3：头部已有「X睡觉中」+徽标+AnimatedContent，dock 睡眠槽变「醒来」，行级 relative 标签齐全；剩余仅「距上次喂奶/尿布」聚合 | `AppHeader.kt:774-780`、`MainActivity.kt:503`、`QuickRecordSlots.kt:48-49` |
| 对比度不达标四色（P1） | ❌ 大部分误报：JournalMuted、Warning **全库零消费**（死 token）；FoodSummaryOther 是图表图形（3.49:1 过 3:1 非文本线）；仅 Milk 图标 tint 属实降 P3 | `Tokens.kt:47,52,84`；`SummaryScreen.kt:811` |
| Typography 双轨使长辈模式优化只覆盖 10%（P1） | ⚠️ 机制误读→P2：长辈样式同时注入 `MaterialTheme.typography`（`Theme.kt:441-444`），`stackedElderDensity` 对全部 sp 兜底缩放；残差仅 elderTextStyle 行高/字距/无衬线细节，典型 `RecordRow.kt:250,259` | `Tokens.kt:656-657,747-759` |
| 15 处游离时长致 reduce-motion 失效（P1） | ⚠️ 结论错→P3：均为协程动画，受系统 MotionDurationScale 自动缩放（与 leziMotionMillis 同源）；残留仅 token 一致性问题 | `Tokens.kt:275-278` |
| surfaceContainer 未覆写为 P1（含 LeziFormControls 违规） | ⚠️ 部分属实→P2：7 处 sheet 属实；LeziFormControls.kt:127 系误读（label 用已覆写槽位）；浅色差 1.12:1 隐蔽、暗色才可见 | `Theme.kt:190-296`、`LeziFormControls.kt:127,134` |
| 预测性返回未 opt-in（P1） | ⚠️ 属实但降级：侧载无 Play 政策压力、targetSdk 35 兼容路径功能完好；随 SDK 36 波次一并做 | `AndroidManifest.xml`、`app/build.gradle.kts:66` |
| 删除无撤销与布局编辑 undo 模式失衡（P1） | ⚠️ 降 P3：布局 undo 是未提交编辑会话，删除是已同步 tombstone，语义不同类；软删除已可逆 | `RecordMutationCoordinator.kt:412-439`、`LayoutEditMode.kt:455-489` |
| Onboarding 连接优先是单机用户门槛（P1） | ⚠️ 降 P3/设计意图：NAS 同步即产品前提，endpoint 已验证时 CTA 变「继续登录」 | `OnboardingWizardSteps.kt:104-127` |
| 成员页裸三态无任何重试（P1） | ⚠️ 降 P3：菜单「刷新」+ FailureAction.Retry 链路存在，缺的只是行内重试 | `FamilyMembersListUi.kt:255-261`、`FamilyScreen.kt:335-348` |
| 无 WorkManager 后台同步（P2） | ❌ 非 gap：`layers/sync.md:20,89` 明文决策 | `docs/spec/layers/sync.md` |
| i18n 1905 行硬编码（P0） | ⚠️ 改判为已声明决策（`product.md:16,97`），移出 gap | `docs/spec/product.md` |
| （第一轮未发现）冲突收件箱首帧假空态 | ✅ 新增 P2（比缺 loading 更伤信任） | `ConflictInboxRoute.kt:51` |
| （第一轮未发现）architecture.md「3 日时间轴」权威漂移 | ✅ 新增 P0 | `docs/spec/architecture.md:143` |
| （第一轮未发现）MainActivity 双路由分类器重复维护 | ✅ 新增 P3 卫生项 | `MainActivity.kt:952-972` |
| （第一轮未发现）Milk 图标对比度对照面选错（background vs toneBg） | ✅ 新增修复注意点 | `RecordVisuals.kt:59-62` |

## 参考

[Guide to app architecture](https://developer.android.com/topic/architecture) · [Material 3 in Compose](https://developer.android.com/develop/ui/compose/designsystems/material3) · [M3 Expressive](https://m3.material.io/blog/building-with-m3-expressive) · [M3 Easing & duration](https://m3.material.io/styles/motion/easing-and-duration) · [Predictive back](https://developer.android.com/guide/navigation/predictive-back-gesture) · [Android haptics](https://developer.android.com/develop/ui/design/haptics) · [Notifications](https://developer.android.com/develop/ui/views/notifications/build-notification) · [WCAG 2.1 对比度](https://www.w3.org/WAI/WCAG21/Understanding/contrast-minimum) · [Compose a11y](https://developer.android.com/develop/ui/compose/accessibility) · [Modularization guide](https://developer.android.com/topic/modularization)
