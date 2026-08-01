# Android 应用内 APK 升级调研

- 日期：2026-07-31
- Status：**Implemented**（自托管通道已落地；合同写回 [tech.md §4.2](../prd/tech.md)、
  [sync-trusted-endpoint.md §7.4](../prd/sync-trusted-endpoint.md)、
  [`tools/lezi-sync/deploy/DEPLOY.md`](../../tools/lezi-sync/deploy/DEPLOY.md)；
  原 tracker `.scratch/self-hosted-app-update/` 已在 0.3.1 收口后清出工作区，细节见 git 历史）
- 问题：如何在 APK 内做「检测到版本低 → 服务端推送/下发新 APK → 提示用户升级」的替换升级；尽量复用业界成熟 API 与最佳实践。
- 与 Lezi 相关背景：当前 `versionName=0.3.1` / `versionCode=7`（见 `docs/prd/tech.md` / `app/build.gradle.kts`）；家庭 LAN/自托管同步为主；实现使用 `REQUEST_INSTALL_PACKAGES` + 鉴权 `GET /v1/app-update` / `apk` + `PackageInstaller`。

## 结论（可直接指导选型）

Android 上成熟的「应用内升级」**不是一条统一路径**，而是由**分发渠道**决定：

| 渠道 | 应复用的成熟做法 | 是否允许自研「下载 APK 替换自己」 |
|------|------------------|----------------------------------|
| **Google Play 分发** | [Play In-App Updates](https://developer.android.com/guide/playcore/in-app-updates)（Play Core） | **否**。Play 政策禁止用 Play 以外方式修改/替换/自我更新应用，也禁止从 Play 外下载可执行代码（dex/JAR/.so 等） |
| **旁加载 / 自托管 / 企业内部分发** | 服务端版本元数据 + 下载 APK + 平台 [`PackageInstaller`](https://developer.android.com/reference/android/content/pm/PackageInstaller) 会话安装 | **是**，但须用户确认安装来源与安装 UI；普通第三方应用**不能**静默强制装包 |

对 Lezi 这类以家庭 NAS / 自托管同步、侧载 APK 为主的产品：**应走自托管路径**（版本检查 API + `PackageInstaller`），把 Play In-App Updates 当作 UX/强度策略参考，而不是直接依赖 Play Core。

**不要**自己实现「解压 APK 覆盖私有目录 / 动态加载新 dex 替换主包」这类伪升级；平台与商店都把完整包安装与签名校验当作权威路径。

---

## 1. 版本语义（平台约定）

依据：[Version your app](https://developer.android.com/studio/publish/versioning)

| 字段 | 角色 |
|------|------|
| `versionCode`（整数） | **唯一用于比较新旧**；更高即更新。系统用它阻止降级安装。 |
| `versionName`（字符串） | **仅展示**（如 `0.3.0`），不参与系统升级判定。 |

服务端与客户端比较应只依赖 `versionCode`（或等价单调整数）。产品文案用 `versionName` + changelog。

平台**不会**替第三方应用强制「必须升到某版本」；强制升级是应用自己的产品门闩（弹窗 / 阻断功能），系统只保证不装更低 `versionCode`。

---

## 2. Google Play：In-App Updates（官方最佳实践）

依据：[In-app updates](https://developer.android.com/guide/playcore/in-app-updates)、[Kotlin/Java 支持](https://developer.android.com/guide/playcore/in-app-updates/kotlin-java)

- API 21+；面向手机 / 平板 / ChromeOS。
- 由 **Play 商店**完成下载、安装与重启；应用只负责检查与展示 UX。
- 两种官方模式，可直接对应「可选 / 强制」：

| 模式 | 行为 | 适用 |
|------|------|------|
| **Flexible** | 后台下载安装，用户可继续使用 | 非关键功能、体验优化 |
| **Immediate** | 全屏阻塞，必须更新并重启才能继续 | 核心功能 / 安全关键修复 |

强度可结合：

- 服务端 `inAppUpdatePriority`（0–5，Play Developer API）
- 客户端 `clientVersionStalenessDays`（版本过期天数）

常见策略：轻微 UI 可不弹 → 性能类 Flexible → 关键安全 Immediate；或先 soft 提示，随 staleness 再升级为强制。

**Play 政策红线**（[Device and Network Abuse](https://support.google.com/googleplay/android-developer/answer/16559646)）：

- 不得用 Play 以外方式修改、替换或自我更新。
- 不得从 Play 外下载可执行代码。
- 因此「应用内下载 APK + PackageInstaller 替换」**不能**用于 Play 渠道构建。

`REQUEST_INSTALL_PACKAGES` 在 Play 上也有严格用途限制（核心能力须是收发/安装包且用户发起安装）；政策目标之一是遏制「非 Play 自更新」滥用。见 [Play Console：REQUEST_INSTALL_PACKAGES](https://support.google.com/googleplay/android-developer/answer/12085295)。

---

## 3. 自托管路径：服务端版本检查 + 下发 APK + 就地安装

这是「后台检测到版本低 → 推送/提示升级 → 应用内替换」在非 Play 场景下的业界主流做法。

### 3.1 版本发现（服务端）

平台**没有**标准「应用更新 manifest」schema；产品需自建。成熟参考：

- **简单 API**（多数自研 App）：返回包名、建议 `versionCode` / `versionName`、下载 URL、摘要、是否强制、changelog。
- **签名仓库索引**（更稳，F-Droid 一类）：以签名过的索引为中心做发现与校验，而非裸 APK URL。见 [F-Droid APIs](https://f-droid.org/en/docs/All_our_APIs/)。

建议的最小元数据（产品选择，非平台标准）：

```json
{
  "packageName": "com.lezi.babylog",
  "latestVersionCode": 7,
  "latestVersionName": "0.3.1",
  "minSupportedVersionCode": 6,
  "forceUpdate": false,
  "apkUrl": "https://…/lezi-0.3.1.apk",
  "apkSha256": "…",
  "releaseNotes": "…",
  "publishedAt": "2026-07-31T00:00:00Z"
}
```

语义建议：

| 字段 | 含义 |
|------|------|
| `latestVersionCode` | 当前可推送的最新包 |
| `minSupportedVersionCode` | 低于此则 **强制** 升级（服务端可拒绝旧客户端同步） |
| `forceUpdate` | 或与 min 二选一；客户端阻断主流程 |
| `apkSha256` | 下载后、commit 前校验；防 CDN/中间人篡改 |
| `apkUrl` | HTTPS；家庭 NAS 场景可用已信任 endpoint + 鉴权 |

**检查触发**（业界常见组合，非互斥）：

1. App 冷启动 / 回到前台
2. 已有同步会话的 health / capability 握手时附带版本策略
3. 可选：用户点「检查更新」
4. 可选：服务端在 API 响应里带 `X-Min-Client-Version` 或 JSON 错误码，旧客户端强制升级

「推送」在消费级 Android 上通常**不是**静默装包，而是：

- 服务端标记有新版本 → 客户端下次检查时发现；或
- FCM / 自有 WebSocket / LAN 通知 → 客户端再拉元数据并弹 UI。

真正的静默安装仅限 Device Owner / Profile Owner 等企业设备管理路径，普通家庭 App 不可用。

### 3.2 下载与暂存

- 使用应用私有目录（`context.cacheDir` / `filesDir`）暂存 APK。
- **`PackageInstaller.Session.openWrite` 可直接流式写入会话**，不必先完整落盘再经 `FileProvider` 交给系统安装器；会话路径是推荐的现代做法。
- 若必须暴露给其他组件：用 [FileProvider](https://developer.android.com/training/secure-file-sharing/setup-sharing)（`grantUriPermissions`、不导出），避免 `file://`。
- 下载完成后比对 `apkSha256`（或等价摘要）；失败则删除并重试/报错。

### 3.3 就地安装：`PackageInstaller`（平台权威 API）

依据：[`PackageInstaller`](https://developer.android.com/reference/android/content/pm/PackageInstaller)

标准会话流：

```text
PackageInstaller
  → createSession(SessionParams(MODE_FULL_INSTALL))
  → openSession
  → openWrite → 写入 APK 字节（可边下边写）
  → fsync / close
  → commit(IntentSender)   // 异步状态回调
```

要点：

- 会话**可以升级已安装的同包名应用**（签名匹配且 `versionCode` 更高）。
- 普通应用 commit 后常见 `STATUS_PENDING_USER_ACTION`：必须把 `Intent.EXTRA_INTENT` 拉到前台（或通知引导），由系统安装确认 UI 完成；**不能静默完成**。
- target API 26+ 声明 `REQUEST_INSTALL_PACKAGES`；安装前查 `PackageManager.canRequestPackageInstalls()`；未授权则引导 `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES`（可带 `package:` URI）。Android 8+ 起「未知应用来源」按**安装来源应用**授权，用户可随时撤销。见 [Android O 安全获取应用](https://android-developers.googleblog.com/2017/08/making-it-safer-to-get-apps-on-android-o.html)。

### 3.4 签名与完整性

依据：[Sign your app](https://developer.android.com/studio/publish/app-signing)

- 安装/更新前 APK **必须**用证书数字签名。
- **更新时新包签名证书必须与设备上已安装版本一致**，否则只能当新应用（改包名）或卸载重装（数据丢失）。
- 平台会拒绝畸形、损坏、签名错误或不匹配的包。
- 安装方自定义 `Session.setChecksums` 已弃用；优先平台校验（如 `PackageManager.requestChecksums` / `Session.requestChecksums`），并在客户端做下载摘要校验。

常见失败（安装错误码，见社区汇总如 [Bayton 安装失败说明](https://bayton.org/android/resolve-app-install-errors/)；以平台实际码为准）：

| 情形 | 典型结果 |
|------|----------|
| 签名证书不一致 | `INSTALL_FAILED_UPDATE_INCOMPATIBLE` |
| `versionCode` 不升反降 | `INSTALL_FAILED_VERSION_DOWNGRADE` |
| 签名损坏/异常 | `INSTALL_FAILED_BAD_SIGNATURE` 等 |

**发布纪律**：debug / release 签名分离时，自更新链路只推送与线上同签名的 release 包；密钥轮换需走 Android 签名 lineage（v3+），未在本调研中完整核验，实现前应再查 AOSP / 官方签名文档。

### 3.5 升级后钩子

- 系统在**本应用被替换成功**后发送受保护广播 [`Intent.ACTION_MY_PACKAGE_REPLACED`](https://developer.android.com/reference/android/content/Intent#ACTION_MY_PACKAGE_REPLACED)（无 extras）。
- 可用于清理旧缓存、调度一次迁移、或拉起服务；**是否能在所有 OEM 上无交互直接 `startActivity` 未在本调研中穷尽验证**——现代 Android 后台启动限制可能要求用户再次打开 App。

---

## 4. 推荐产品流（自托管 / Lezi 向）

```text
[服务端] 发布新 APK + 元数据（versionCode、url、sha256、minSupported、notes）
        │
        ▼
[客户端] 启动 / 同步握手 / 用户「检查更新」
        → GET /app/update 或附在 /health|/ready 策略
        → 比较 local versionCode vs latest / minSupported
        │
        ├─ 已最新 → 无操作
        ├─ 可选更新 → 非阻塞 Banner / Dialog（可稍后）
        └─ 强制更新 → 全屏阻塞（参考 Immediate UX），禁止主功能
        │
        ▼
用户确认 → 下载（Wi‑Fi 优先策略可选）→ sha256 校验
        → PackageInstaller Session 写入 + commit
        → 处理 STATUS_PENDING_USER_ACTION（系统安装 UI）
        → 成功后进程被替换；下次启动或 ACTION_MY_PACKAGE_REPLACED 收尾
```

### 服务端协作（与现有 Lezi 同步契合的方向）

- 在已有信任 endpoint（HTTPS / TOFU SPKI）上挂版本元数据，避免另开不可信 CDN。
- 可选：**协议合同版本**与 **APK versionCode** 解耦——`capabilities` / wire 版本过旧时返回明确错误码，文案引导升级（即使未实现完整 APK 下发，也可先做「硬提示」）。
- `minSupportedVersionCode` 上升时，旧客户端同步 API 可直接 `4xx` + 升级说明，防止半兼容脏写。

### 客户端 UX 借鉴 Play 的两点

1. **Flexible 类**：可继续用 App，下载进度在通知/设置页；适合非破坏性小版本。
2. **Immediate 类**：全屏必须更新；适合破坏性协议变更、安全修复。

普通应用两者都仍要经过系统安装确认（与 Play Immediate 由商店代装不同）。

---

## 5. 权限与 Manifest 清单（自托管路径）

| 项 | 说明 |
|----|------|
| `REQUEST_INSTALL_PACKAGES` | target 26+ 安装未知来源包所需；安装前查 `canRequestPackageInstalls()` |
| 网络权限 | 已有；下载走 HTTPS |
| 前台服务 / 通知 | 大包后台下载时建议可见进度（Android 后台限制） |
| FileProvider | 仅当必须把 APK URI 交给外部组件时；Session 直写可省 |

**不要**在 Play 渠道构建里声明并依赖该权限做自更新。

---

## 6. 安全清单（实现时必查）

1. **传输**：HTTPS；家庭 NAS 场景复用已 pin 的信任模型，不要明文下发 APK。
2. **完整性**：`sha256`（或更好：签名仓库 + 平台 checksum API）在 commit 前校验。
3. **同源签名**：CI 只产出与已安装证书一致的 release APK。
4. **版本单调**：CI 强制 `versionCode` 递增；拒绝覆盖上传更低 code。
5. **权限最小化**：仅在用户确认升级流程中请求安装未知应用；说明文案诚实。
6. **降级与回滚**：系统默认禁降级；回滚需更高 `versionCode` 的修复包或卸载重装（数据策略需产品定义）。
7. **强制升级边界**：强制只用于真正不兼容 / 安全问题；滥用会损害信任。

---

## 7. 与「假升级」路径的对比（应避免）

| 做法 | 评价 |
|------|------|
| Play In-App Updates | Play 渠道唯一推荐 |
| 下载 APK + `PackageInstaller` | 自托管推荐；需用户确认 |
| `Intent.ACTION_VIEW` + `application/vnd.android.package-archive` | 旧式；仍可用但控制力弱于 Session；仍依赖未知来源权限 |
| 覆盖 app 私有文件 / 动态加载新 dex 当「更新」 | **不推荐**；Play 禁止；安全与生命周期问题多 |
| 无用户确认的静默装包 | 仅企业 Device Owner 等；消费级不可用 |

---

## 8. 对 Lezi 的落地建议（调研级，非 PRD）

1. **默认假设侧载/自托管**：在 `lezi-sync` 或独立静态发布点提供「版本元数据 + APK」；客户端设置页「检查更新」+ 启动时软检查。
2. **协议层先于装包层**：若近期只做破坏性 wire 变更，可先做 `minSupportedVersionCode` / capability 拒绝 + 引导文案，再补完整下载安装。
3. **安装实现只用 `PackageInstaller.Session`**，不要新造安装器。
4. **若未来上架 Play**：用 product flavor 拆分——Play 变体走 In-App Updates，侧载变体走 Session；共享「是否需要更新」的产品状态机。
5. **当前仓库缺口**（调研时）：无 `REQUEST_INSTALL_PACKAGES`、无更新元数据 API、无客户端提示流；`versionCode`/`versionName` 仅有构建与 PRD 记录。

---

## 9. 覆盖范围与不确定性

已覆盖：

- Play In-App Updates vs 自托管 `PackageInstaller` 分流
- `versionCode` 比较语义、签名一致性、未知来源权限
- 可选/强制 UX 与服务端元数据形状建议
- 替换后 `ACTION_MY_PACKAGE_REPLACED`

未完整覆盖（实现前宜补查）：

- Device Owner / 企业静默安装完整操作手册
- 国内应用商店各自更新 SDK（华为/小米/应用宝等）
- APK Signature Scheme v3 key rotation / lineage 与侧载替换的精确交互
- Play App Signing 下「设备签名密钥 vs 上传密钥」与侧载包一致性
- 各 OEM 对 `ACTION_MY_PACKAGE_REPLACED` 拉起 Activity 的后台限制
- 差分包（bsdiff / Play asset delivery 类）——消费级自托管通常先做全量 APK

---

## Sources

| ID | 来源 |
|----|------|
| S1–S3, S9 | [In-app updates \| Android Developers](https://developer.android.com/guide/playcore/in-app-updates) |
| S4, S10 | [Support in-app updates (Kotlin/Java)](https://developer.android.com/guide/playcore/in-app-updates/kotlin-java) |
| S5, S21 | [Device and Network Abuse - Play Console Help](https://support.google.com/googleplay/android-developer/answer/16559646) |
| S6, S13 | [`PackageInstaller` API](https://developer.android.com/reference/android/content/pm/PackageInstaller) |
| S7, S8 | [Version your app](https://developer.android.com/studio/publish/versioning) |
| S11, S12 | [F-Droid: All our APIs](https://f-droid.org/en/docs/All_our_APIs/) |
| S14 | [`STATUS_PENDING_USER_ACTION`](https://developer.android.com/reference/android/content/pm/PackageInstaller#STATUS_PENDING_USER_ACTION) |
| S15 | [`canRequestPackageInstalls`](https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls()) 等 |
| S16 | [`STATUS_FAILURE_INVALID` / checksums](https://developer.android.com/reference/android/content/pm/PackageInstaller#STATUS_FAILURE_INVALID) |
| S17 | [Secure file sharing / FileProvider](https://developer.android.com/training/secure-file-sharing/setup-sharing) |
| S18 | [`ACTION_MY_PACKAGE_REPLACED`](https://developer.android.com/reference/android/content/Intent#ACTION_MY_PACKAGE_REPLACED) |
| S19, S20 | [Sign your app](https://developer.android.com/studio/publish/app-signing) |
| S22 | [REQUEST_INSTALL_PACKAGES policy](https://support.google.com/googleplay/android-developer/answer/12085295) |
| S23 | [Making it safer to get apps on Android O](https://android-developers.googleblog.com/2017/08/making-it-safer-to-get-apps-on-android-o.html) |
| S24 | [Common Android install failures (Bayton)](https://bayton.org/android/resolve-app-install-errors/)（二次来源；错误码以实现时平台为准） |
| — | 仓库：`docs/prd/tech.md`（当前 `versionCode` / `versionName`） |

---

## 变更记录

| 日期 | 说明 |
|------|------|
| 2026-07-31 | 初稿：Play In-App Updates vs 自托管 PackageInstaller；Lezi 侧载向落地建议 |
