# PR #6：警告处置与独立后续验证

以下是rebase前89eb的历史清单。新rebase数量、位置和编译诊断必须从新CI核验，不继承为新运行成绩。行号仅定位旧源码，维护时按符号和规则重新定位。参见[总handoff](2026-10-10-spec-remediation.md)。

## 结论与证据范围

历史 CI 修订：`89eb32614a6b801fdcd83ae52ccaaba69a030d83`。18 份原始 lint XML 共 **96 条 Warning 记录、27 个规则＋位置站点、0 个 Error**；按规则＋消息＋位置区分为 38 条精确诊断。两套 CI 成功、普通 JVM 6,577 项通过，不等于警告为零。

27 个站点由 17 个版本/工具更新提示和 10 个其他站点组成。相对 93ba064 的 99 条记录、30 个站点，本轮已实际消除 MainActivity 的语言拆分警告与 Haptics 的两条 InlinedApi 警告；OldTargetApi 仅因配置插入从第 75 行移动到第 84 行。未通过全局屏蔽、降规则或扩大 baseline 制造清零。

以下位置均来自最终 XML，路径相对仓库。保留理由区分有意行为、分析边界及真实待迁移事项；“有理由保留”不等于今后没有改进工作。

## 10 个非版本站点逐项处置

| 规则 | 精确位置 | 当前处置及约束 |
|---|---|---|
| OldTargetApi | `app/build.gradle.kts:84` | 保留为明确的 target SDK 迁移事项。当前 target 35 与 AGP 8.7 支持范围一致；target 36 在大屏上忽略固定方向，与现有竖屏产品合同存在实质冲突。不能只改数字；这不是纯环境阻塞，也不代表可永久不升级。 |
| LockedOrientationActivity | `app/src/main/AndroidManifest.xml:67` | 符合当前 UI 合同的固定竖屏要求；移除会改变旋转/重建行为。代价是大屏体验受限或出现信箱式布局，须随适配与 target 迁移解决。 |
| DiscouragedApi | `app/src/main/AndroidManifest.xml:67` | 固定竖屏是当前明确产品行为。主 Activity 与小组件配置页分别需要布局、重建及配置保存/取消验证；只删除 manifest 属性会改变行为。保留提示并承认大屏限制。 |
| UsableSpace | `app/src/main/kotlin/com/lezi/babylog/LocalDataSnapshotStore.kt:29` | 当前使用实际可用空间，保守保护写入前快照。getAllocatableBytes 会包括可回收缓存，直接替换而不真正预留空间会高估安全写入容量。保留的代价是可能过早报告空间不足；完整的缓存感知预留机制属于独立 I/O 改进，需保留原数据与 ENOSPC 失败证明。没有把此建议当成已修复。 |
| AppBundleLocaleChanges | `designsystem/src/main/kotlin/com/lezi/babylog/designsystem/ClockDial.kt:594` | 独立 designsystem 库的中文时间选择器确实会创建中文配置；最终 app 已关闭 AAB 语言拆分，主应用对应警告已消失。库单独 lint 无法从本模块证明消费者打包策略，因此仍报告。保留中文选择器行为；若该库被其他应用使用，消费者也必须保证中文资源可用。未以 suppress 隐藏。 |
| SdCardPath | `domain/src/main/kotlin/com/lezi/babylog/domain/calendar/SystemCalendarPort.kt:214` | /data/data/ 是向系统日历输出内容时的隐私路径拒绝列表，不是写入路径。改成当前 app 的 filesDir 会缩小禁止范围、遗漏其他私有路径。保留原隐私检查与泄露测试。 |
| DiscouragedApi | `feature/widget/src/main/AndroidManifest.xml:6` | 固定竖屏是当前明确产品行为。主 Activity 与小组件配置页分别需要布局、重建及配置保存/取消验证；只删除 manifest 属性会改变行为。保留提示并承认大屏限制。 |
| CustomX509TrustManager | `sync/src/main/kotlin/com/lezi/babylog/sync/session/TrustedEndpoint.kt:531` | 已批准连接采用 SPKI pin：要求证书存在、有效期有效，SHA-256 SPKI 做常数时间比对；不是业务请求 trust-all。默认公有 CA 校验无法替代自签名 NAS 的 TOFU 合同。保留警告及安全回归范围；不是全面安全认证。 |
| CustomX509TrustManager | `sync/src/main/kotlin/com/lezi/babylog/sync/session/TrustedEndpoint.kt:549` | 仅候选证书探测握手使用；调用方还做 hostname 与自签名检查，不发送 HTTP 业务载荷，后续连接仍需明确批准的身份。不能把该检查器扩展为一般传输信任策略。保留审查提示；不是全面安全认证。 |
| UsableSpace | `sync/src/main/kotlin/com/lezi/babylog/sync/disasterrecovery/RestoreFileSnapshotStore.kt:177` | 当前使用实际可用空间，保守保护写入前快照。getAllocatableBytes 会包括可回收缓存，直接替换而不真正预留空间会高估安全写入容量。保留的代价是可能过早报告空间不足；完整的缓存感知预留机制属于独立 I/O 改进，需保留原数据与 ENOSPC 失败证明。没有把此建议当成已修复。 |

## 17 个版本／工具站点

这些诊断报告“存在较新版本”，没有给出 CVE 或漏洞结论。本次有限的官方资料核对没有确立当前固定版本的具体安全漏洞，亦不构成完整 SCA 或“所有旧版本均安全”的保证。固定版本保留的是已验证兼容组合；升级仍是维护工作，不能只为减少提示一次性追最新。下列目标版本是本次 lint 报告值。

| 规则与精确位置 | 当前版本 → 报告版本 | 保留固定版本的具体验证约束 |
|---|---|---|
| AndroidGradlePluginVersion · `gradle/libs.versions.toml:2` | 8.7.3 → 9.4.1（2 个坐标） | AGP 与 Gradle/Kotlin/KSP/Hilt、变体及 R8 联动；官方 AGP 9.4 要求 Gradle 9.6，当前固定组合并非可单点替换。 |
| GradleDependency · `gradle/libs.versions.toml:5` | 1.15.0 → 1.19.1（1 个坐标） | 需核 AAR/编译工具最低要求及权限、insets、文件提供器行为；本次 haptics 修复使用现有版本即可。 |
| GradleDependency · `gradle/libs.versions.toml:6` | 1.0.1 → 1.2.0（1 个坐标） | 启动主题、首帧与低版本启动行为需要验证。 |
| GradleDependency · `gradle/libs.versions.toml:7` | 2.8.7 → 2.11.0（6 个坐标） | 六个 Lifecycle 坐标需成组兼容，并验证生命周期取消、驻留和状态恢复。 |
| GradleDependency · `gradle/libs.versions.toml:8` | 1.9.3 → 1.13.0（1 个坐标） | 涉及 Activity result、返回导航、edge-to-edge 和 Compose 集成。 |
| GradleDependency · `gradle/libs.versions.toml:9` | 2024.12.01 → 2026.09.00（1 个坐标） | BOM 控制整组 UI 依赖；需兼容编译器及实际布局、字体、无障碍与 Compose 语义。 |
| GradleDependency · `gradle/libs.versions.toml:10` | 2.8.5 → 2.10.2（1 个坐标） | 需验证导航栈、深链、恢复及 Activity/Lifecycle/Compose 组合。 |
| GradleDependency · `gradle/libs.versions.toml:12` | 1.2.0 → 1.4.0（1 个坐标） | 需验证 Hilt/Compose/ViewModel 与生成代码组合。 |
| GradleDependency · `gradle/libs.versions.toml:13` | 2.6.1 → 2.8.5（4 个坐标） | 四个 Room 坐标需同步；官方新版本包含已关闭数据库查询行为变化，需数据库迁移、重启、回滚及 invalidation 验证。 |
| GradleDependency · `gradle/libs.versions.toml:14` | 1.1.1 → 1.2.1（1 个坐标） | 需验证并发写入、持久格式与身份/凭据迁移顺序，不能损坏已存数据。 |
| GradleDependency · `gradle/libs.versions.toml:17` | 1.7.0 → 1.8.0（1 个坐标） | 需保留 Locale/context/theme 与旧设备支持行为。 |
| GradleDependency · `gradle/libs.versions.toml:18` | 1.12.0 → 1.14.0（1 个坐标） | 涉及主题、对话框、资源及旧 API View/Compose 混合布局。 |
| GradleDependency · `gradle/libs.versions.toml:20` | 1.2.1 → 1.3.0（1 个坐标） | 测试依赖，需与 runner/Espresso 配套并验证设备测试发现与加载。 |
| GradleDependency · `gradle/libs.versions.toml:21` | 1.6.2 → 1.7.0（1 个坐标） | 测试 runner，需保留自定义启动入口、进程终止和 host-driver 流程。 |
| GradleDependency · `gradle/libs.versions.toml:22` | 3.6.1 → 3.7.0（2 个坐标） | 测试依赖，需验证同步、intent 拦截与 Compose 集成，不能靠放宽断言迁移。 |
| GradleDependency · `gradle/libs.versions.toml:24` | 1.1.1 → 1.2.0（2 个坐标） | 需验证小组件发布、配置 Activity、恢复、尺寸和宿主行为。 |
| GradleDependency · `gradle/libs.versions.toml:25` | 2.7.1 → 2.12.0（1 个坐标） | 用于持久副作用恢复；需验证唯一任务、重启重试和启动配置。官方版本改变调度/启动行为，不能只以 minSdk 可满足判断可直接替换。 |

## 编译器警告：与 lint 分开统计

已审查的保留组为 **5 个生产兼容桥接诊断、11 个 Security Crypto 弃用诊断、4 个 opaque 测试源码站点**。这三个数字来自相应源码/编译证据，不能相加后称为最终全项目编译警告总数；测试中的完整 session/凭据断言等也可能发出其他弃用警告。增量编译未发出某条诊断不代表源码警告已消失。

### 5 个 legacy 兼容桥接诊断

`sync/src/main/kotlin/com/lezi/babylog/sync/SyncPort.kt:180:62、189:62、223:62、256:66、454:9`。前四处是源兼容结果类型从旧 full-session 属性产生无凭据 presentation 的桥；改名会改变构造参数、copy/component 使用合同。第五处是兼容旧实现的默认 sessionPresentation 适配，生产 RealSyncPort 已直接投影 owner 状态。没有新增凭据外泄路径；不能靠删除 Deprecated、改用 component1 或返回空 presentation 消警。

### 11 个 Security Crypto 弃用诊断

`sync/src/main/kotlin/com/lezi/babylog/sync/session/SecureRefreshTokenStore.kt:4:8、5:8、143:29、143:39、144:31、144:41、146:20、150:17、150:44、151:17、151:44`。固定依赖为稳定版 security-crypto 1.1.0，其整个 API 已由官方弃用；这本身不是漏洞报告。现实现保护 refresh token、待批准成员登录 secret 与恢复 token。机械替换成普通 SharedPreferences 会降低静态加密保护；兼容迁移必须保证旧密文/Keystore 读取、掉电恢复、写入失败、身份切换和回滚。不为告警清零迁移密钥、删除凭据或改成明文。

### 4 个 opaque 测试源码站点

- `feature/family/src/test/kotlin/com/lezi/babylog/feature/family/LogoutDestructivePathCopyTest.kt:75:35`
- `feature/family/src/test/kotlin/com/lezi/babylog/feature/family/LogoutDestructivePathCopyTest.kt:97:35`
- `feature/family/src/test/kotlin/com/lezi/babylog/feature/family/members/SourceCommandLogoutHostTest.kt:188:31`
- `feature/family/src/androidTest/kotlin/com/lezi/babylog/feature/family/SourceCommandLogoutDialogDeviceTest.kt:120`

这些测试已有 INVISIBLE_REFERENCE/INVISIBLE_MEMBER 用法，用来构造跨模块不可公开伪造的退出同意能力。编译器明确提示其行为不保证持续支持，属于真实测试基础设施技术债。不能通过开放生产 constructor、弱化能力边界或增加屏蔽消警；当前保留原安全边界，后续需要经过审查的 test-only 夹具方案。本次没有新增这些 suppress。

### 实际日志计数边界

本地 009（e0f57b2）日志实际发出上述 5 条生产兼容、11 条 Security Crypto 和 3 条 JVM opaque 诊断；第 4 个 opaque 位于 androidTest，不能把源码存在直接算作该日志已发出。最终 89eb paired 任务原始日志实际发出上述 5 条生产兼容与 11 条 Security Crypto 诊断，opaque 诊断为 0 条（该任务不代表 family JVM/androidTest 编译覆盖）。最终普通任务原始日志两次读取均失败，无法核定其全项目编译警告总数；其完整测试 XML 与 lint 工件已取得。不得将 5＋11＋4 称作最终全项目“20 条 warning”。lint 数字已由最终完整 XML 确认，不依赖编译日志推断。

## 依据

- 历史 CI 工件：`android-gates-89eb326.zip` 中 18 份 lint XML；`lint-unique-sites.json` 保留重复来源和消息变体。
- 产品合同：`docs/spec/contracts/ui.md`（固定竖屏）；`docs/spec/contracts/session-presentation.md`（presentation 与兼容边界）。
- [官方 Haptics Compat API](https://developer.android.com/reference/androidx/core/view/HapticFeedbackConstantsCompat)
- [官方 AAB 语言切换打包说明](https://developer.android.com/guide/app-bundle/configure-base#handling_language_changes)
- [Android 16 大屏行为变化](https://developer.android.com/about/versions/16/behavior-changes-16)
- [AGP 8.7 兼容范围](https://developer.android.com/build/releases/agp-8-7-0-release-notes)；[AGP 9.4 兼容要求](https://developer.android.com/build/releases/agp-9-4-0-release-notes)
- [Room 发布说明](https://developer.android.com/jetpack/androidx/releases/room)；[WorkManager 发布说明](https://developer.android.com/jetpack/androidx/releases/work)
- [Security Crypto 1.1.0 弃用说明](https://developer.android.com/jetpack/androidx/releases/security#security-crypto-1.1.0)；[EncryptedSharedPreferences API](https://developer.android.com/reference/androidx/security/crypto/EncryptedSharedPreferences)
