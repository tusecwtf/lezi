# Spec · 自托管应用内更新（侧载 APK）

**Status:** complete · 5/5  
**Source:** 2026-07-31 grilling + research  
**Research:** [`docs/design/2026-07-31-android-apk-in-app-update-research.md`](../../docs/design/2026-07-31-android-apk-in-app-update-research.md)  
**Tracker:** [ISSUES.md](./ISSUES.md)  
**PRD:** [`docs/prd/tech.md`](../../docs/prd/tech.md) §4.2 · [`sync-trusted-endpoint.md`](../../docs/prd/sync-trusted-endpoint.md) §7.4  
**Evidence:** [evidence/05/validation.md](./evidence/05/validation.md)

## Problem Statement

家庭侧载用户升级乐记时，只能靠人工传 APK 安装。服务器（lezi-sync）发版后，已加入家庭的设备不知道有新版本，也无法在应用内完成检查、下载与替换安装。破坏性协议变更时，旧客户端仍可能继续同步，存在半兼容脏写风险。用户期望：NAS 部署新服务时同步带上最新 APK；已连接家庭的 App 能提示升级并完成应用内安装；客户机不留下可二次分享的升级包残留。

## Solution

在**已加入家庭且 endpoint 已信任**的前提下，提供自托管应用内更新通道（**不是** Google Play In-App Updates / Play Core）：

1. 发版打包时把 **release APK + 更新元数据** 打进 NAS 部署包（缺 APK 则打包失败）。
2. lezi-sync 鉴权提供版本元数据与 APK 下载；低于 `minSupportedVersionCode` 时拒绝权威同步写/拉，但仍允许更新检查与下载。
3. 客户端握手顺带检查；菜单「关于」展示当前版本并可点检查更新；可选更新用确认层 + 非阻塞横幅；强制更新全屏。
4. 使用平台 `PackageInstaller` 会话安装；暂存仅限应用私有 cache，成功/失败/取消后清理，不进公共下载目录。

## User Stories

1. As a 已加入家庭的照护者, I want 打开菜单关于区看到当前版本号, so that 我知道本机装的是哪一版乐记  
2. As a 已加入家庭的照护者, I want 点击关于区检查更新, so that 我能主动确认家庭服务器是否有新包  
3. As a 已加入家庭的照护者, I want 检查后若已是最新得到明确反馈, so that 我不反复猜测  
4. As a 已加入家庭的照护者, I want 有可选更新时看到新版本说明并选择立即或稍后, so that 我不被小版本打断记账  
5. As a 已加入家庭的照护者, I want 确认后应用内下载并调起系统安装, so that 我不必再找 U 盘或聊天传 APK  
6. As a 已加入家庭的照护者, I want 下载/安装失败时看到可理解原因并能重试, so that 我能恢复  
7. As a 已加入家庭的照护者, I want 升级流程结束后本机不留下乐记暂存的 APK 文件, so that 包不会被误分享或占空间  
8. As a 已加入家庭的照护者, I want 前台同步/握手时若发现可选更新在账户或同步相关区域看到非阻塞提示, so that 我不只会在设置里偶然发现  
9. As a 已加入家庭的照护者, I want 可选提示可以稍后且同一版本在本会话不再刷屏, so that 提示不烦人  
10. As a 使用过旧客户端的照护者, I want 当服务器抬高最低版本时被全屏要求升级且无法「稍后」绕过主功能, so that 家庭数据合同不被旧客户端破坏  
11. As a 强制升级中的照护者, I want 仍能完成检查/下载/安装更新, so that 我不会死锁在无法升级的状态  
12. As a 未加入家庭的离线用户, I want 点击检查更新时得到诚实说明（需先连接家庭）, so that 我不以为 App 坏了  
13. As a 未加入家庭的用户, I want 不能从匿名通道拉家庭服务器上的 APK, so that 更新载荷仍挂在既有信任与会话边界上  
14. As a 家庭管理员/运维, I want 部署 lezi-sync 时打包脚本同步纳入当前 release APK 与元数据, so that 发一版服务就带一版客户端  
15. As a 家庭管理员/运维, I want 打包时若缺少约定 APK 则失败, so that 不会上线「有更新 API 却下不到包」的空通道  
16. As a 家庭管理员/运维, I want 在元数据里设置 minSupportedVersionCode, so that 只有破坏性变更才强制全员升级  
17. As a 家庭管理员/运维, I want 未抬高 minSupported 时旧客户端仍可同步并仅见可选提示, so that 小版本不打断家庭  
18. As the 同步服务, I want 对低于 minSupported 的客户端拒绝 pull/push/bundle/media 等权威数据路径, so that 半兼容写入被挡住  
19. As the 同步服务, I want 对有效会话仍提供更新元数据与 APK 下载, so that 被拒绝同步的客户端仍能升级  
20. As the 同步服务, I want 从客户端请求头读取 versionCode 做门槛判断, so that 不依赖用户自报在 body 里改合同  
21. As a 已加入设备, I want 所有受保护同步请求带上本机 versionCode, so that 服务端门槛一致生效  
22. As a 下载更新的设备, I want 下载后按元数据 sha256 校验再安装, so that 损坏或篡改包不会进入安装器  
23. As a 安装更新的设备, I want 使用系统 PackageInstaller 会话并处理用户确认与未知应用来源授权, so that 符合平台安全模型  
24. As a 安装更新的设备, I want 新包与已装应用同签名且 versionCode 更高才能原地替换, so that 系统升级规则被遵守  
25. As a debug 构建使用者, I want 本轮不承诺 debug 应用 ID 的自更新, so that 发布通道只服务 release `com.lezi.babylog`  
26. As a 产品维护者, I want PRD/技术说明写明自托管更新合同与 Play 路径区分, so that 实现与评审有权威依据  
27. As a 产品维护者, I want 关于区不再展示「无广告 · 无内购 · 本地优先」营销句, so that 该入口专用于版本与更新  
28. As a 网络不稳的用户, I want 取消或失败后暂存被清理且可再次检查, so that 半截文件不残留  
29. As a 使用账户页的用户, I want 更新相关文案与现有菜单/账户视觉（面板、字阶、中文）一致, so that 不出现第二套设计语言  
30. As a 同步刚被服务端以「须升级」拒绝的用户, I want 直接进入强制升级体验而非含糊网络错误, so that 我知道下一步是升级  

## Implementation Decisions

### 通道与政策

- **自托管应用内更新**：平台 `PackageInstaller.Session`；**不**引入 Google Play In-App Updates / Play Core。
- 与 Play 政策区分：本产品当前以侧载 + 家庭服务器为主；若未来 Play 渠道，须另 flavor，不得在 Play 构建走自研 APK 替换。
- 资格：**仅已加入家庭且具备有效设备会话、endpoint 已信任** 的客户端可检查元数据与下载 APK。
- 离线模式 / 未加入：无更新通道；关于点击给诚实文案。

### 版本语义

- 比较与门槛只用整数 **versionCode**；**versionName** 仅展示。
- 双档：`local < latest` → 可选；`local < minSupportedVersionCode` → 强制。
- 未配置抬高 minSupported（min ≤ 合理默认/不强制）时，有新包只可选提示。

### 部署与产物

- `package-nas`（及同等 NAS 打包入口）**fail-closed**：约定路径缺少 release APK 或元数据不合法则打包失败。
- 输入约定：单一 release APK 路径 + `app-update.json`（可手写或脚本生成）；打包时计算/校验 **sha256** 并写入随包元数据。
- APK + 元数据随**当次** NAS 部署包进入服务可读固定路径（镜像或只读挂载）；由 lezi-sync 进程提供下载，**不**另开匿名 nginx 静态站。
- 仅 **release**、`applicationId = com.lezi.babylog`、与线上一致签名证书。

### 服务端 API 与门槛

- 独立鉴权端点（路径最终对齐现有 `/v1/...` 风格，示意）：
  - 元数据：`GET` 更新描述（JSON）
  - APK：`GET` 字节流（`application/vnd.android.package-archive` 或等价）
- 客户端在受保护请求上携带请求头 **`X-Lezi-Client-Version-Code`**（整数）。
- 元数据最小字段：`packageName`、`versionCode`、`versionName`、`minSupportedVersionCode`、`sha256`；可选 `releaseNotes`。
- 当 `versionCode` 头缺失或 `< minSupported`：拒绝 **权威同步**路径（pull、普通 push/bundle、媒体上下行等写/拉家庭权威数据的 API）；**不**拒绝更新元数据与 APK 下载；探活类可保持可发现性。
- 拒绝使用**单一可映射错误语义**（如 `client_update_required`），客户端映射到强制升级 UI，避免当成普通网络故障。
- 更新端点与同步共用同一会话鉴权与 TLS/信任模型。

### 客户端检查与状态

- 触发：① 已加入且前台对信任 endpoint 握手/同步路径顺带检查；② 菜单关于区点击检查。
- 无 FCM、无后台常驻推送、本轮不做独立冷启动强制轮询（有前台同步则覆盖）。
- 可选更新：确认层（新 versionName、可选 releaseNotes、立即更新 / 稍后）→ 下载 → 校验 → 安装。
- 强制更新：全屏，无稍后绕过主功能；必须仍能走完更新安装。
- 可选横幅：账户/同步相关区域非阻塞；同一 `versionCode` **进程会话内**稍后不再刷屏。
- 关于区 UI：保留标题「乐记」；副文案为 **`版本 {versionName}`**（如 `版本 0.3.0`）；整块可点 = 检查更新；**删除**「无广告 · 无内购 · 本地优先」；不新开独立关于页；沿用现有菜单 panel / 字阶 / 间距。

### 下载、安装与无残留

- 优先 `PackageInstaller` 会话 **流式写入**；若需落盘，仅应用**私有 cache**。
- **禁止**公共 Download/共享目录；不长期 FileProvider 暴露 APK。
- 下载完成后 **sha256** 与元数据一致才 commit 安装。
- 处理 `STATUS_PENDING_USER_ACTION` 与未知应用来源（`REQUEST_INSTALL_PACKAGES` / `canRequestPackageInstalls`）。
- 清理时机：成功、失败、用户取消、再进前台时尽力删除本应用暂存 APK；验收查应用私有目录无残留。**不**承诺清除系统 PackageInstaller 内部缓存。
- 签名不一致 / versionCode 降级等按系统失败呈现可理解文案。

### 模块与接缝（实现导向）

- **lezi-sync**：加载部署元数据；注册鉴权 update 路由；同步路径读 version 头做门槛。
- **Android sync / 会话传输**：所有需门槛的受保护请求附加 versionCode 头；实现检查与下载（复用既有信任 transport）。
- **领域或 sync 对外端口（首选单缝）**：在现有 **SyncPort（或紧邻的单一 ClientAppUpdate 端口，由 RealSyncPort 实现）** 上暴露：检查更新、观察可用更新/强制态、开始下载安装、清理暂存。UI 与前台同步只依赖此高位缝，不直连 PackageInstaller。
- **平台安装适配**：端口内部隔离 `PackageInstaller`；单测可替换安装后端。
- **feature/settings（及账户/同步展示面）**：关于区、确认层、可选横幅、强制全屏；中文文案。
- **文档**：`docs/prd/tech.md`（及同步相关 PRD 必要一句）写回合同；可链向 design 研究笔记。

### 元数据示意（合同形状，非最终字段名强制）

```json
{
  "packageName": "com.lezi.babylog",
  "versionCode": 7,
  "versionName": "0.3.1",
  "minSupportedVersionCode": 6,
  "sha256": "<hex>",
  "releaseNotes": "可选"
}
```

## Testing Decisions

### 好测试的标准

- 只断言**外部行为**（HTTP 合同、端口返回的更新态、同步被拒绝的语义、清理后无私有 APK、UI 可见文案/主路径），不锁 PackageInstaller 内部实现细节。
- 优先**最高缝**：客户端以 SyncPort/ClientAppUpdate 端口为测点；服务端以 HTTP API + 同步拒绝为测点。
- 少造缝：理想情况下客户端测试只 mock/ fake **一个**更新+同步端口；安装器用可替换后端。

### 推荐接缝（实现时遵守）

| 接缝 | 测什么 |
|------|--------|
| **S1 · lezi-sync HTTP** | 无会话 401；有会话返回元数据；APK 字节与 sha256；version 头低于 minSupported 时 sync 类 API 失败且错误可识别；update 路由仍 200 |
| **S2 · 客户端更新端口（SyncPort 扩展或单一并列端口）** | 已加入检查得到 Optional/Forced/UpToDate/NotJoined；下载校验失败不调用安装；成功路径调用安装后端；清理后无暂存文件 |
| **S3 · 设置/关于 UI（轻量）** | 展示 `版本 {versionName}`；点击触发检查；强制态全屏；可选确认层主按钮 |

**优先复用：** lezi-sync 既有 API 集成测（`tools/lezi-sync/tests`）、`RealSyncPort` / 传输层单测风格、feature/settings 的 JVM 或 Compose 测（若项目已有同类）。

**设备/人工：** 真机或模拟器一条：已加入 → 检查 → 确认 → 系统安装 UI 可调起；私有 cache 无残留。打包脚本缺 APK 失败可用 CI/本地脚本断言。

## Out of Scope

- Google Play In-App Updates / Play Core / Play 上架专用通道  
- FCM 或其它推送「推包」  
- 差分包 / 多 APK 分包优化  
- 普通应用静默安装、Device Owner 企业路径  
- 未加入 / 离线模式设备的应用内升级  
- 公网匿名 CDN 或未鉴权 APK URL  
- debug `applicationId` 后缀包的自更新  
- 仅 Wi‑Fi 下载硬门闩  
- 独立「关于」全屏产品页、恢复关于区「无广告·无内购·本地优先」为更新入口文案  
- 承诺清除 OEM/系统安装器缓存中的副本  

## Further Notes

- Grill 锁定摘要：Q1 自托管；Q2 仅已加入会话；Q3 双档 minSupported；Q4 握手+手动检查；Q5 打包 fail-closed；Q6 拒 sync 放行 update；Q7 横幅+设置；Q8 独立 API+头；Q9 APK+json 输入；Q10 E2E 最小可用；Q11 尽力无残留；Q12 关于单行版本可点；Q13 可选先确认再下载。  
- 强制升级与「同步失败」文案必须区分，避免用户以为 NAS 挂了。  
- `versionCode` 与 lezi-sync Cargo 版本号**不要求**数值相等；以 `app-update.json` 与 Android 构建为准。  
- 实现顺序建议见 [ISSUES.md](./ISSUES.md) frontier（垂直切片：01 检查 → 02 安装∥04 横幅 → 03 强制 → 05 验收）。
