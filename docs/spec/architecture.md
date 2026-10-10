# 分层架构（architecture）

> 当前开发代：**0.5.5** / versionCode **35** / Room **29** / 本地数据契约 **7** /
> server schema **13** / wire **0.5.0** / paired-software floor **35**；未部署，真实渠道与NAS仍为0.5.4。
> 新代规范和发布阻塞见 [ADR0026](../adr/0026-durable-restore-authority-generation.md)。以下0.5.4身份记录为历史：
> **0.5.4** / versionCode **34** / Room **29** / 本地数据契约 **6** /
> server schema **13** / 同步 floor **21**；协议代 0.4.0（conflict-v2）。2026-09-30
> 协同维护窗后 NAS 与渠道目标均为 0.5.4。构建真值以
> `app/build.gradle.kts` + `config/android-release-compatibility.json` 为准。
>
> **权界**：本文权威 = Gradle 模块图、依赖方向、package locality、模块↔规格↔代码连线。
> 各层内部 seam / 算法 / 交互见 [`layers/`](.)；跨层行为与 wire 合同见
> [`contracts/`](./contracts/)（wire 字段唯一权威 =
> [`contracts/causal-sync-wire.md`](./contracts/causal-sync-wire.md)）。签名唯一权威
> 是代码。

---

## 1. 分层模型与依赖方向

Current Gradle modules（与根 `settings.gradle.kts` 的 `include` **一一对应**，共 19 个）。
不存在未 include 的幽灵交付（例如 **`:core:image` 不是** 产品 Gradle module）。

```text
:app                         # 组合根：一切的最后装配点
:core:model                  # 叶子：纯 Kotlin 领域值类型与约束
:core:common                 # 叶子：纯 Kotlin 原语（因果身份/失败分类/摘要/升级规划）
:core:database               # Room 唯一本地真相源（含 causal 表）
:core:datastore              # DataStore 设置
:core:ui                     # 跨 feature 共享 UI 逻辑（可看 :sync 与 :designsystem）
:designsystem                # token/两模板/叶子组件（只依赖 core:model）
:domain                      # CareLog façade：DAO+DataStore+SyncPort 之上的领域层
:sync                        # SyncPort/RealSyncPort + 副本同步栈（api 暴露 core:database）
:feature:onboarding|log|timer|family|settings|summary|growth|export|search|widget
```

目标依赖方向：`app → feature → domain → core`，且 `domain|feature|app → :sync` 为有意
sideways seam（非分层违规；完整边表见 §4）。feature 之间无 `project(":feature:…")`
边；共享只经 domain / core / designsystem / `:sync`（按 §4 允许的调用方）。

### 1.1 实际 project 依赖边表（与各 `build.gradle.kts` 一致）

| 模块 | project 依赖 |
|------|--------------|
| `:core:model` / `:core:common` | —（叶子） |
| `:designsystem` | `core:model` |
| `:core:database` | `core:model`、`core:common` |
| `:core:datastore` | `core:model` |
| `:sync` | `core:model`、`core:common`、**api**`core:database` |
| `:domain` | `core:model`、`core:common`、**api**`core:database`、`core:datastore`、`sync` |
| `:core:ui` | `core:common`、`core:model`、`designsystem`、`sync` |
| `:feature:onboarding` | `core:common`、`core:model`、`core:ui`、`designsystem`、`domain`、`sync` |
| `:feature:log` | `core:common`、`core:datastore`、`core:model`、`core:ui`、`designsystem`、`domain`、`sync` |
| `:feature:timer` | `core:common`、`core:datastore`、`core:model`、`designsystem`、`domain` |
| `:feature:family` | `core:common`、`core:model`、`core:ui`、`designsystem`、`domain`、`sync` |
| `:feature:settings` | `core:common`、`core:datastore`、`core:model`、`core:ui`、`designsystem`、`domain`、`sync` |
| `:feature:summary` | `core:datastore`、`core:model`、`designsystem`、`domain`、`sync` |
| `:feature:growth` | `core:common`、`core:datastore`、`core:model`、`designsystem`、`domain`、`sync` |
| `:feature:export` | `core:common`、`core:model`、`core:ui`、`designsystem`、`domain` |
| `:feature:search` | `core:model`、`core:ui`、`designsystem`、`domain`、`sync` |
| `:feature:widget` | `core:common`、`core:model`、`core:ui`、`designsystem`、`domain` |
| `:app` | 以上全部 18 个模块 |

`sync` 以 `api(core:database)` 暴露数据库，因此 `domain` 与全部（直接依赖 `:sync` 的）
feature 传递可见 DAO——这是**有意**的 deep-façade 设计，不是泄漏；分层规则见 §2。

---

## 2. 稳定原则（Gradle 图 + 模块内就近归拢）

本节目的是固定 **current** 架构边界与防回潮规则；只描述已交付 tree。

| 原则 | 含义 |
|------|------|
| **Gradle 模块图保持** | 新增能力优先落入现有 module；**不因分包新增 Gradle module**，也不合并现有 feature module |
| **feature 互不依赖** | 跨 feature 协作经 domain / composition root，禁止 feature↔feature 工程依赖 |
| **模块内就近归拢** | 分区只发生在现有 module 的 package/file 内，按调用流或能力归拢；目录名以已落地职责为准 |
| **Deep façade 保留** | 保留 `CareLog`、`SyncPort` / `RealSyncPort`（及 lezi-sync `Store`）的 deep 公开面；**不拆** `SyncPort` / `CareLog` 为浅 capability port 表面 |
| **文档只描述 current** | 本树只描述已交付 tree 与上表规则；不以本地 tracker 草案路径作为长期产品真相 |
| **`:sync` 为允许的横向依赖** | `app` / `domain` / 若干 feature 可直接 `implementation(project(":sync"))`（§4）；这是有意 seam，不是对 `app → feature → domain → core` 的违规 |
| **禁止产品无关 StructureTest** | 不新增以源码字符串/行数/路径布局为合同的 StructureTest；行为测试才是重构合同 |

运行时 `TimerState` 落在 `:feature:timer`；持久化计时 blob / clear-epoch 策略在
`core`（datastore + model）与 domain 清空端口；log ↔ timer 协作经 `core:model` 的
`TimerHandoffSeed` 与 app composition root，**禁止** feature:log ↔ feature:timer 工程依赖；
完成写记录经 domain。

---

## 3. 已落地 package locality（current tree）

新代码进入下表子包，**不在已分区模块根继续平铺**（根仅留导航壳 / deep façade / DI 入口）。

| 模块 | 根 façade / 壳 | 已落地子包 |
|------|----------------|------------|
| `:feature:log` | `LogScreen` / `LogViewModel` / `LogDialogHost` | `timeline/`、`dock/`、`composer/`、`layout/`、`photo/` |
| `:feature:family` | `FamilyScreen` 导航壳 | `overview/`、`members/`、`wizard/`、`baby/`、`components/`、`conflict/`（app-shell 共享 inbox/resolver route）、`networksettings/` |
| `:feature:onboarding` | 导航壳 | wizard 步态与 QR UI 与 Family 向导逻辑对齐（薄壳 + 步态包） |
| `:feature:settings` | Settings 入口 | `calendar/`、`record/`、`command/` |
| `:core:database` | `LeziDatabase` / DAO / DI 入口 | `causal/`、`fulfillment/`（事务自持的履行权威派生结算） |
| `:domain` | `CareLog` | `carelog/`、`careplan/`、`family/`、`timeline/`、`catalog/`、`growth/`、`export/`、`localdata/`、`calendar/` |
| `:sync` | `SyncPort` / `RealSyncPort` / `SyncModule` | `engine/`、`backend/`、`session/`、`media/`、`appupdate/`、`qr/`、`clear/`、`heartbeat/`（前台心跳探针引擎）、`availability/`、`conflict/`（ConflictSnapshot 族）、`disasterrecovery/`（灾备四步客户端流） |
| `tools/lezi-sync` | crate 根 + 单一 `Store` 事务面 | crate-private `handlers::*`、`store::{schema,identity,bundles,media,…}`；`offline_migrate/` 独立维护窗 CLI |

Later（未在本表承诺）：不新增 Gradle module 仅为了再细分；不恢复已删 StructureTest。

---

## 4. 有意的 `project(":sync")` 直接依赖边

下列边与各 module `build.gradle.kts` 中 `implementation(project(":sync"))` **一致**（有意
seam）。**不得**为迎合文档而静默删改 Gradle 边；亦不得在文档中遗漏合法调用方。

| 边 | 用途 |
|----|------|
| `app → sync` | composition root：前台生命周期 / `ForegroundState`、强制更新壳、`SyncPort` 注入、本地数据升级相关凭证存储 |
| `domain → sync` | CareLog 与协调器：本地写后的同步触发、家庭向导网关、会话/角色、本机清空与家庭权威回调 |
| `feature:log → sync` | 时间轴下拉刷新触发 sync；记录/计划本地发布文案 |
| `feature:family → sync` | 账户页 setup、管理员登录、成员申请/设备管理、可选更新横幅与退出；共享冲突 resolver 会话/ACL |
| `feature:onboarding → sync` | 引导内连接家庭服务器、TOFU / 成员登录 QR、setup probe |
| `feature:growth → sync` | 成长页下拉刷新触发 sync |
| `feature:settings → sync` | 关于区检查更新 / 安装更新与相关文案 |
| `feature:summary → sync` | 汇总页下拉刷新触发 sync |
| `feature:search → sync` | 搜索页同步可用性与状态 |

无直接 `project(":sync")` 的 feature（`timer` / `export` / `widget`）经 domain
间接参与同步，不直连 sync 模块。

---

普通 app／domain／feature 会话读取统一使用 `SyncPort.sessionPresentation()`，返回无凭据的
`SyncSessionPresentation`；家庭向导已提交／重试状态同样只保留该只读投影。身份、endpoint、
`isJoined`、reauth 与 provenance 来自同一 owner 快照；`isJoined` 在凭据丢弃前由原会话计算，
不在 UI 根据缺少 token 重建。旧 `session()` 与命令结果的 `session` 仅作为内部／测试过渡适配器保留。
源 API 迁移不改变持久化／wire，消费者和 fake 清单见
[`session presentation migration`](./contracts/session-presentation.md)。

## 5. ★模块连线表（模块 ↔ 规格 ↔ 代码 ↔ 测试）

每个模块一行：职责 → 本树层规格锚点 → 代码入口（façade / 壳）→ 测试位置
（`src/test` 为 JVM 单测；标 † 者另有 `src/androidTest` 设备/Compose 测试）。

| 模块 / crate | 职责 | 层规格 | 代码入口 | 测试 |
|---|---|---|---|---|
| `:app` | 组合根：导航壳、chrome 策略、本地数据升级流水线、前台服务装配、外部导航信任 | [`layers/features.md §1`](./layers/features.md) | `MainActivity.kt`、`LeziApp.kt`、`LocalDataUpgradeModule.kt` | `src/test` † |
| `:core:model` | 纯 Kotlin 领域值类型与约束（Record/CarePlan/Baby/SleepProjection/TimerHandoffSeed…） | [`layers/core.md §2`](./layers/core.md) | `Models.kt`、`RecordPayload.kt`、`SleepProjection.kt` | `src/test`（+ `src/testFixtures`） |
| `:core:common` | 纯 Kotlin 原语：UUIDv5 因果身份、失败分类目录、媒体摘要、本地数据升级规划 | [`layers/core.md §3`](./layers/core.md) | `CausalIdentity.kt`、`failure/`、`LocalDataUpgrade.kt` | `src/test` |
| `:core:database` | Room v29 唯一本地真相源：20 实体、causal 表、事务与 CAS | [`layers/core.md §4`](./layers/core.md) | `LeziDatabase.kt`、`causal/`、`fulfillment/` | `src/test` † |
| `:core:datastore` | DataStore 设置（长辈模式、布局快照、选中宝宝、同步偏好） | [`layers/core.md §5`](./layers/core.md) | `SettingsStore.kt`、`SettingsDataSource.kt` | `src/test` |
| `:designsystem` | 设计 token、两模板、长辈模式缩放、时间轴/记录行叶子组件、本地照片加载 | [`layers/core.md §6`](./layers/core.md) | `Tokens.kt`、`Theme.kt`、`Components.kt`、`RecordRow.kt`、`TimelineComponents.kt` | `src/test` † |
| `:domain` | `CareLog` deep façade：记录/计划/宝宝生命周期、协调器、聚合、时间轴窗口、端口 | [`layers/domain.md`](./layers/domain.md) | `CareLog.kt`、`carelog/`、`DomainModule.kt` | `src/test`（含真服务端 seam） |
| `:sync` | `SyncPort` 契约 + `RealSyncPort` 家网实现：副本引擎、心跳、冲突、灾备、清空、自更新、QR | [`layers/sync.md`](./layers/sync.md) | `SyncPort.kt`、`RealSyncPort.kt`、`engine/`、`heartbeat/` | `src/test`（含 `IsolatedLeziSyncServer`）† |
| `:core:ui` | 跨 feature 共享 UI 逻辑：相机采集租约、成员登录 QR 扫描、记录呈现、失败解释 | [`layers/features.md §2`](./layers/features.md) | `CameraCapture.kt`、`RecordPresentation.kt`、`failure/` | `src/test` † |
| `:feature:onboarding` | 首跑向导（与 Family 向导步态对齐） | [`layers/features.md §3`](./layers/features.md) | `OnboardingScreen.kt`、`OnboardingViewModel.kt` | `src/test` † |
| `:feature:log` | 记录首页：连续绝对时间轴、快记 Composer、快捷 dock、布局拖拽编辑、有界照片导入 | [`layers/features.md §4`](./layers/features.md) | `LogScreen.kt`、`LogViewModel.kt`、`timeline/`、`composer/`、`dock/`、`layout/` | `src/test` † |
| `:feature:timer` | 哺乳计时：前台服务状态机、完成确认 sheet、back 策略 | [`layers/features.md §5`](./layers/features.md) | `TimerScreen.kt`、`NursingTimerService.kt`、`NursingCompletionSheet.kt` | `src/test` † |
| `:feature:family` | 账户页：家庭总览、成员/设备管理、向导宿主、冲突收件箱/resolver、网络设置 | [`layers/features.md §6`](./layers/features.md) | `FamilyScreen.kt`、`overview/`、`members/`、`wizard/`、`conflict/`、`networksettings/` | `src/test` † |
| `:feature:settings` | 设置中心 + 系统日历集成 + 记录设置 + 命令态 | [`layers/features.md §7`](./layers/features.md) | `SettingsScreen.kt`、`calendar/`、`record/`、`command/` | `src/test` † |
| `:feature:summary` | 日/周聚合汇总页 | [`layers/features.md §8`](./layers/features.md) | `SummaryScreen.kt`、`SummaryAggregation.kt` | `src/test` † |
| `:feature:growth` | 成长曲线与测量写入协调 | [`layers/features.md §9`](./layers/features.md) | `GrowthScreen.kt`、`GrowthMeasurementWriteCoordinator.kt`、`GrowthReferenceCatalog.kt` | `src/test` † |
| `:feature:export` | TXT/PDF 导出与缓存清理 | [`layers/features.md §10`](./layers/features.md) | `ExportScreen.kt`、`ExportFileGenerator.kt`、`PdfExport.kt` | `src/test` |
| `:feature:search` | 记录搜索 | [`layers/features.md §11`](./layers/features.md) | `SearchScreen.kt`、`SearchRepository.kt` | `src/test` |
| `:feature:widget` | 桌面小组件（Glance） | [`layers/features.md §12`](./layers/features.md) | `CareWidget.kt`、`WidgetConfigurationActivity.kt` | `src/test` |
| `tools/lezi-sync` | NAS 家庭同步服务端（Axum/rustls/SQLite）：身份、因果图、合并、媒体、灾备、更新分发 | [`layers/server.md`](./layers/server.md) | `src/main.rs`、`src/lib.rs`、`src/store/` | `cargo test`（`src/store/tests/` + `tests/`） |

跨层合同的归属：同步 wire → [`contracts/causal-sync-wire.md`](./contracts/causal-sync-wire.md)；
实体与聚合规则 → [`contracts/data-model.md`](./contracts/data-model.md)；可信端点行为 →
[`contracts/sync-trusted-endpoint.md`](./contracts/sync-trusted-endpoint.md)；逐页 UI →
[`contracts/ui.md`](./contracts/ui.md)。
