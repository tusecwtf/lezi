# 01: 开屏闪屏修复——首帧对齐 + SplashScreen 桥接

**What to build:** 消除冷启动的 3~4 次画面突变。根因四条：
① `Theme.Lezi`（`app/src/main/res/values/themes.xml:3`）无 `windowBackground`——系统白
启动窗 → 首帧奶油 `#FBF7EE`，深色用户白→`#0E1721` 闪白；
② gate 检查屏深色口径用 `isSystemInDarkTheme()`（`MainActivity.kt:266`），Ready 后主屏
改用 DataStore `ui.darkMode`（:216-220）——app 深色用户明暗跳变；
③ `RootViewModel.ui` 初始值 `RootUi()` 默认 `hasBaby=false`（:299,:459）→ 老用户首拍
闪现 Onboarding 一帧再 200ms Crossfade 到主界面（:954-958）；
④ `enableEdgeToEdge` 三处调用（:207、:237-250、:1156-1169）scrim 中途跳变。

修复（用户已确认口径：首帧对齐 + 桥接，仍受 CONTEXT.md「Avoid: 启动页」约束）：
引入 `androidx.core:core-splashscreen:1.0.1`；新增 `Theme.Lezi.Splash`（parent
`Theme.SplashScreen`，`windowSplashScreenBackground` = 首帧背景色 + `values-night` 深色
变体，`windowSplashScreenAnimatedIcon` = 现有 launcher 图标，`postSplashScreenTheme` →
`Theme.Lezi`）；`Theme.Lezi` 补 `windowBackground` 同色；MainActivity manifest 主题切
`Theme.Lezi.Splash`；`onCreate` 里 `installSplashScreen()`（super 前）+
`setKeepOnScreenCondition { state is LocalDataUpgradeState.Checking }`——仅按住系统窗到
安全检查完成，Snapshotting/Migrating/Blocked 立即释放给应用内进度 UI；抽取 darkMode
口径 helper（DataStore `darkMode` 映射）供 Ready 分支与 gate 分支共用；`RootViewModel.ui`
改 `StateFlow<RootUi?>` 初始 null，`LeziRoot` 未决时渲染主题背景空帧；MainActivity
:237-250 的 edge-to-edge SideEffect 收敛为仅覆盖非 Scaffold 场景。

**Blocked by:** 无

**Status:** done

- [x] libs.versions.toml + app/build.gradle.kts 加 core-splashscreen 1.0.1
- [x] colors.xml 加 `lezi_window_bg`（浅 #FBF7EE）；values-night 同名深色 #0E1721
- [x] `Theme.Lezi.Splash`（splash 背景/图标/postSplashScreenTheme）；`Theme.Lezi` 补 windowBackground；manifest MainActivity 主题切换
- [x] MainActivity `installSplashScreen()` + keep-on-screen(仅 Checking)；`localDataGate.state.value` 供条件读取
- [x] darkMode 口径 helper 抽取（`leziDarkTheme`），gate 分支与 Ready 分支同源（DataStore）；Ready 分支首帧前用 DataStore 设置兜底 `RootUi` 主题参数
- [x] `RootViewModel.ui` → `StateFlow<RootUi?>`；`LeziRoot` 未决渲染主题背景空帧（不闪 Onboarding）；收集点适配（MainActivity 主题分支、LeziRoot、cycleBaby/jumpSiblingSameDayAge/toggleDark 三处 `.value` 读取）
- [x] edge-to-edge 三处收敛：gate 分支补 dark 口径 SideEffect；MainActivity 通用 SideEffect 注明「Scaffold 路由感知版为主口径」；Scaffold 内保留
- [x] 单测：`onboardingGateTarget` 未决/决出用例 + `leziDarkTheme` 口径用例（RootRoutingPolicyTest）
- [x] 门禁：`./gradlew test`（989 用例含 8 个 real-server seam，需先 `cargo build -p lezi-sync`）、`:app:assembleDebug`、`lintDebug` 全绿
- [x] spec 同步：docs/spec/contracts/ui.md §2.1.1 增「冷启动首帧」政策；CONTEXT.md 本地数据升级门禁注明「按住系统启动窗≠启动页」

**残余风险（接受）**：① app 强制深色但系统浅色的用户，冷启动窗仍为浅色（windowBackground
只能跟随系统 night 限定符，读 DataStore 需阻塞主线程不做）→ 1 次硬切且无中间错屏；
② Android 12 以下 compat 行为需真机确认。设备冒烟清单：浅色/深色/强制深色三场景 ×
Android 12 上下各一台，冷启动录屏比对。

## 落地记录（2026-09-10，motion-polish）

**Commit:** `cdb3e2e6` fix(ui): stop cold-start flash with first-frame-aligned splash（本票回填于后续 docs(scratch) 提交）。
**交付物：** `gradle/libs.versions.toml`、`app/build.gradle.kts`（core-splashscreen 1.0.1）；
`app/src/main/res/values{,-night}/colors.xml`、`values/themes.xml`、`AndroidManifest.xml`；
`MainActivity.kt`（installSplashScreen + keep-on-screen(仅 Checking)、`leziDarkTheme`
统一深色口径、`RootViewModel.ui` 可空未决态 + 主题空白帧、gate 分支 edge-to-edge）；
`RootRoutingPolicyTest.kt` 新用例；`docs/spec/contracts/ui.md`、`CONTEXT.md` 同步。
**偏差：** 无方案级偏差。实施细节增补两处——Ready 分支首帧前用 DataStore 设置兜底主题参数
（避免空帧用 RootUi() 默认 system 深色口径）、`cycleBaby`/`jumpSiblingSameDayAge`/`toggleDark`
三处 `ui.value` 读取补空值短路。
**门禁：** `./gradlew test` ✅（先 `cargo build -p lezi-sync` 供 8 个 seam 测试）；
`:app:assembleDebug` ✅；`lintDebug` ✅。纯客户端改动，无 wire/server 变更，无需 NAS 联调。
**残留风险：** 真机冷启动冒烟（浅色/深色/强制深色 × Android 12 上下）待用户执行。

## Comments
