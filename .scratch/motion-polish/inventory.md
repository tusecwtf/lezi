# 乐记动画全量清单（2026-09-10 盘点基线）

盘点方法：对 `app/`、`core/`、`designsystem/`、`feature/`、`sync/` 全量 grep Compose 动画 API
（`AnimatedContent` / `AnimatedVisibility` / `Crossfade` / `animate*AsState` /
`rememberInfiniteTransition` / `Animatable` / `animateItem` / `tween` / `spring` /
`infiniteRepeatable`），并核查 XML（`res/anim`、`res/animator`、`animation-list`、
`animated-vector`）、Lottie、MotionLayout、View 体系动画（`ObjectAnimator` /
`ValueAnimator` / itemAnimator / pageTransformer）——后三类在本仓库为零。
`ChronoUnit.DAYS.between` / `Period.between` / `Duration.between` 为日期 API 误报，已排除。
file:line 以 2026-09-10 master（11d2bce2）为基线。

## 〇、结论

- **全部动画为 Jetpack Compose 实现**，无 XML/Lottie/MotionLayout/View 动画。
- **已有集中时长 token**：`LeziMotion`（`designsystem/src/main/kotlin/com/lezi/babylog/designsystem/Tokens.kt:196`）
  —— `Fast=150ms` / `Base=200ms` / `Emphasized=300ms` 三档，配套
  `leziMotionMillis()`（Tokens.kt:275）实现 reduce-motion（读
  `Settings.Global.ANIMATOR_DURATION_SCALE`，scale≤0 时非必要过渡归零），设计快照同步在
  `design/tokens.json` 的 `motion` 节点，契约测试 `MotionDensityTokensTest` + 设备冒烟
  `LeziMotionScaleDeviceTest`。
- **缺口一：无缓动 token**。全库 easing 仅显式 `FastOutSlowInEasing` 6 处 + Compose `tween`
  默认隐式同曲线 + 1 处自定义 spring（AppHeader 月亮帽 dampingRatio=0.58/StiffnessLow）；
  无入场 decelerate / 出场 accelerate 区分（M3 规范做法）。
- **缺口二：约 15 处时长游离于 token 外**（见 §六 分布表加粗行）。
- **缺口三：开屏首帧链路无动画策略**——系统启动窗（白）→ gate 检查屏 → 主界面共 3~4 次硬切
  （根因分析见 `issues/01`）。

## 一、app 模块（壳层）

### `app/src/main/kotlin/com/lezi/babylog/AppHeader.kt`（顶栏）

| 行 | API | 时长 | 缓动 | 用途 |
|---|---|---|---|---|
| :237 | `AnimatedContent`(sleeping) | 入 Emphasized 300+delay60 / 出 Fast 150（`headerEnterMs`/`headerExitMs` :180-181） | 显式 FastOutSlowInEasing（:249,257） | 顶栏「宝宝名↔睡眠状态」切换（淡入+1/4 高度上滑） |
| :379 | `AnimatedVisibility`(sleeping) | 入 spring（无时长）+fade Fast 150 delay40；出 Emphasized 300，fade delay80（:377-378） | spring damping 0.58/StiffnessLow（:386-388）；出显式 FastOutSlowIn（:404） | 睡眠月亮帽左上滑入/滑出（reduce-motion 时 tween(0) 兜底 :383,399） |
| :434-443 | `rememberInfiniteTransition`+`animateFloat` | **`SleepCapBobMs=1400`**（:421） | FastOutSlowInEasing+Reverse | 月亮帽上下浮动 ±1.2f 装饰循环（reduce-motion 跳过） |
| :608 | `AnimatedContent`(displayedMonth) | 入 Base 200 / 出 Fast 150（:606-607） | 默认 | 顶栏日历月翻页（水平 1/4 宽滑动+淡切，方向感知） |
| :682 | `animateColorAsState` | Fast 150（`daySelectMs` :681） | 默认 | 日历单日选中背景色过渡 |

### `app/src/main/kotlin/com/lezi/babylog/MainActivity.kt`（根壳）

| 行 | API | 时长 | 缓动 | 用途 |
|---|---|---|---|---|
| :954 | `Crossfade`(onboarding gate) | Base 200（:952） | 默认 | 首启 onboarding ↔ 主框架整体淡切（`rootOnboardingGate`） |
| :997 | `AnimatedVisibility`(强更全屏壳) | 入 Base 200 / 出 Fast 150（:995-996） | 默认 | 强制更新遮罩层淡入淡出 |
| :1211 | `AnimatedContent`(RootHeaderKind) | 入 Base 200 / 出 Fast 150 | 默认 | 顶栏「日期头↔品牌栏」变体切换（入场自顶部 -h/8 下滑） |
| :1278 | `AnimatedVisibility`(底栏) | 入 Base 200（slide）/Fast 150（fade）；出 Fast 150 | 默认 | 底部导航栏整高滑入/滑出 |
| :1367-1390 | `NavHost` enter/exit/popEnter/popExit | enter fade Fast 150+slide Base 200；exit/popEnter fade Fast；popExit fade Fast+slide Base（:1085-1086 捕获） | 默认 | 全局导航过渡；全屏 push 路由（timer/search/export/calendar）额外 h/24 垂直滑动（`isFullScreenPushRoute` :903-907），tab 切换 enter=EnterTransition.None 纯 fade |
| :1189 | `delay(至午夜)` | — | — | 跨天时钟翻转触发（状态性，非视觉动画） |

### `app/src/main/kotlin/com/lezi/babylog/LocalDataRecoveryScreen.kt`

| 行 | API | 时长 | 缓动 | 用途 |
|---|---|---|---|---|
| :111 | `Crossfade`(升级状态) | **硬编码 tween(200)**（:113） | 默认 | 本地数据迁移 进度态↔阻断态 淡切 |

## 二、designsystem 模块

### Tokens.kt（motion token 源）

- :196 `LeziMotion` 三档；:213 `nonEssentialMillis`（reduce-motion 归零策略）；
  :240 `leziMotionDurationScale`（ContentObserver 监听系统动画缩放）；:275 `leziMotionMillis()`。
  全库约 21 处 `leziMotionMillis(...)` 解析点。

### Components.kt / ActionStateComponents.kt

| 位置 | API | 时长 | 用途 |
|---|---|---|---|
| Components.kt:76 | `animateFloatAsState` | 默认 spring（无显式时长） | 卡片按压 scale 1→0.98 |
| Components.kt:84,150 / ActionStateComponents.kt:68,250 | `ripple()` | 默认 | Material ripple |
| ActionStateComponents.kt:61 | `animateFloatAsState` | 默认 spring | 快捷记录按钮按压 scale 1→0.97 |

### LeziRangeTabs.kt

| 行 | API | 时长 | 用途 |
|---|---|---|---|
| :63 | `animateDpAsState` | Fast 150（:53） | 分段 tab 指示条位移 |
| :87 | `animateColorAsState` | Fast 150 | tab 文字颜色 |

### TimelineComponents.kt

| 行 | API | 时长 | 用途 |
|---|---|---|---|
| :173-181 | `Animatable`×N（`animateTo`） | **tween(150)** | 日视图类别选中高亮 0→1（点半径/alpha/聚焦环） |
| :183 | `animateFloatAsState` | **tween(150)** | lane 未选中压暗 0→1 |

### SwipeEditDeleteRow.kt

| 行 | API | 时长 | 用途 |
|---|---|---|---|
| :129/:163-211 | `Animatable`+6 处 `animateTo`+3 处 `snapTo` | **`SWIPE_SETTLE_MS=200`**（:71） | 左滑删除/右滑编辑的回弹、揭示与提交收合 |

### NextFeedPlanFlow.kt

| 行 | API | 时长 | 用途 |
|---|---|---|---|
| :87-89 | `AnimatedContent`(计划阶段) | **fadeIn tween(150) togetherWith fadeOut tween(120)**——120 为全库唯一非 token 数值 | 下一段喂养计划阶段切换 |

### TransientShallowSyncChrome.kt

| 行 | API | 时长 | 用途 |
|---|---|---|---|
| :70 | `AnimatedVisibility`(浅同步状态行) | 入/出 Fast 150（:68-69） | 浅同步状态行展开/收起 |
| :53 | `delay(contentDwellMillis)` | `TransientShallowSyncContentMillis=5000`（:23） | 内容停留 5s 后收起（视觉节奏，非补间） |

## 三、feature 模块

### feature/onboarding `OnboardingScreen.kt`

- :285 `AnimatedContent`(向导步骤)：入 fadeIn+slideInHorizontally(w/8) 各 Emphasized 300；出 fadeOut Fast 150（:270-271）。

### feature/log

- `LogScreen.kt:289` `AnimatedContent`(布局编辑模式)：入 fadeIn+slideInVertically(h/24) / 出反向，均 Emphasized 300（:286）。
- `composer/QuickRecordPurposeFields.kt:540-557` `rememberInfiniteTransition`+2×`animateFloat`：睡眠动作图标 **bob tween(1400)** + **wake pulse tween(1000)**，FastOutSlowInEasing+Reverse。
- `composer/QuickRecordPurposeTextFields.kt:209`、`composer/QuickRecordSheet.kt:473` `AnimatedVisibility`（默认规格 fade+expand）。
- `layout/LayoutEditMode.kt:250` `Animatable`(feedbackProgress)：拖拽吸附反馈脉冲 1→0，Fast 150（:255,293）；`LayoutUndoSession.kt:17` `LAYOUT_UNDO_OFFER_DURATION_MS=4000`（撤销 snackbar 截止，非补间）。
- `timeline/LogTimelineList.kt:538` `Crossfade`(加载/空态)：**tween(250)**（:540）；:542,594,652 `Modifier.animateItem()`（LazyColumn 列表项增删/重排默认动画）。

### feature/timer

- `TimerScreen.kt:397` `animateFloatAsState`（默认 spring，按压 scale 1→0.96）；:401-417 `animateColorAsState`×4（默认 spring：背景/标签/时间/动作词颜色随运行态）；:434 ripple；:331 `ModalBottomSheet`（M3 内建手势动画）；:137 `delay(200)` 计时 tick（状态性）。
- `NursingCompletionSheet.kt:218` `AnimatedVisibility`（默认规格）。

### feature/family

- `FamilyScreen.kt:531` `AnimatedContent`(主页↔网络设置)：`fadeIn() togetherWith fadeOut()` 库默认时长。
- `networksettings/FamilyNetworkSettingsScreen.kt:160` `AnimatedVisibility`（默认规格）。
- `members/FamilyMembersListUi.kt:284,310,385,417,426` `Modifier.animateItem()`×5。

### feature/settings

- `calendar/CalendarScreen.kt:425` `AnimatedContent`(选中日期标题)：默认 fade；:807/:879 `HorizontalPager` 月份横滑（内建）；:819 `animateScrollToPage`（默认规格）。

### feature/summary

- `SummaryScreen.kt:351` `Crossfade`(计算中↔内容) Base 200（:350）；:445 `AnimatedContent`(区间切换) 入 Base 200/出 Fast 150（:402-403）。

### feature/export

- `ExportScreen.kt:325` `AnimatedVisibility`(导出预览)：fadeIn+expandVertically/fadeOut+shrinkVertically，入 Base 200/出 Fast 150（:258-259）。

### feature/search

- `SearchScreen.kt:203` `Crossfade`(Prompt/Loading/Empty/Error/Results 五相，默认规格)；:101 `delay(200)` 搜索防抖；:148 `delay(LOADING_INDICATOR_DELAY_MS=300)`（:287）loading 延迟阈值。

### feature/widget

- `WidgetConfigurationActivity.kt:77` `Crossfade`(配置加载态，默认规格)；`WidgetAutoRefresh.kt:88` `delay(至午夜)`（任务调度，非动画）。

### feature/growth

- `GrowthScreen.kt:431` `Modifier.animateItem()`×1。

## 四、core / domain / sync 模块

零动画（grep 命中均为 `ChronoUnit` 等日期 API 误报）。

## 五、XML / 非 Compose 检查（全部为「无」）

| 检查项 | 结果 |
|---|---|
| `res/anim` / `res/animator` / animation-list / animated-vector / `<transition>` | 0 处 |
| Lottie 依赖与引用 | 无 |
| MotionLayout / ViewPager pageTransformer / RecyclerView itemAnimator | 无（对应物为 `HorizontalPager` 与 `Modifier.animateItem()`×9） |
| ObjectAnimator / ValueAnimator / ViewPropertyAnimator | 无 |
| Splash 动画图标 / core-splashscreen | 无（票 01 引入桥接） |
| `Handler.postDelayed` 视觉切换 | 无（视觉节奏用协程 `delay`：浅同步 5s、搜索防抖 200ms、loading 阈值 300ms、undo 4s、timer tick 200ms） |
| `ModalBottomSheet`（M3 内建） | 3 处：RecordComposer.kt:113、LogDialogHost.kt:221、TimerScreen.kt:331 |
| keyframes / snap / repeatable(非 infinite) | 0（仅 3 个 `infiniteRepeatable` 循环 + `snapTo` 瞬时定位） |

## 六、汇总统计

自定义 Compose 动画调用点合计 **58 处**（不含 ripple 5 处、ModalBottomSheet 内建 3 处、进度指示器自转）。

| 类别 | 数量 |
|---|---|
| `AnimatedContent` | 9 |
| `AnimatedVisibility` | 9 |
| `Crossfade` | 6 |
| `animate*AsState`（float 4 / color 6 / dp 1） | 11 |
| `rememberInfiniteTransition`（3 个循环） | 2 |
| `Animatable`（8 处 `animateTo` / 6 处 `snapTo`） | 3 |
| `NavHost` 转场 lambda | 4（1 个 NavHost） |
| `Modifier.animateItem()` | 9 |
| `animateScrollToPage` | 1 |
| 显式 `spring` spec | 1（其余 6 处为 animateXAsState 默认 spring） |

### 时长数值分布（动画上下文显式毫秒；**加粗 = 游离于 LeziMotion token**）

| 数值 | 出现处 | 备注 |
|---|---|---|
| 0ms | AppHeader.kt:383,399 | reduce-motion 兜底 |
| 40/60/80ms（delay） | AppHeader.kt:394,244,410 | 入场 choreography 延迟 |
| **120ms** | NextFeedPlanFlow.kt:89 | 全库唯一非 token 补间值 |
| **150ms 显式** | NextFeedPlanFlow.kt:89；TimelineComponents.kt:179,185 | 值同 Fast 但绕过 token（reduce-motion 不生效） |
| **200ms 显式×7** | LocalDataRecoveryScreen.kt:113 + SwipeEditDeleteRow（SWIPE_SETTLE_MS） | 值同 Base 但绕过 token |
| **250ms** | LogTimelineList.kt:540 | 唯一 250 档 |
| 300ms（Emphasized） | ~8 个解析点 | token |
| **1000ms / 1400ms** | QuickRecordPurposeFields.kt:554,545；AppHeader.kt:421,439 | 装饰循环节奏（建议标注保留，不迁 token） |
| 4000ms / 5000ms / 300ms 阈值 | LayoutUndoSession:17 / TransientShallowSyncChrome:23 / SearchScreen:287 | 非补间（节奏/阈值），不属 token 迁移范围 |

### 缓动分布

- `FastOutSlowInEasing` 显式 6 处（AppHeader.kt:249,257,404,439；QuickRecordPurposeFields.kt:545,554）；其余 `tween()` 隐式默认即同曲线。
- 自定义 spring 1 处（AppHeader.kt:386，damping 0.58/StiffnessLow）；animateXAsState 默认 spring 6 处。
- 无 decelerate/accelerate 语义区分，无 easing token —— 票 02 缺口。

## 七、测试中的动画资产（非生产代码）

- `designsystem/src/test/.../MotionDensityTokensTest.kt`（token 值 + reduce-motion 单测）
- `designsystem/src/androidTest/.../LeziMotionScaleDeviceTest.kt`（设备端 motion scale 冒烟）
- `feature/log/src/test/.../LayoutDragFeedbackTest.kt:211`（引用 Fast 档）
- `feature/family/src/test/.../FamilyLazyMembersContractTest.kt`（提及 animateItem 为实现细节）
