# 平台规格（platform）

> 当前开发代：**0.5.5** / versionCode **35** / Room **29** / 本地数据契约 **7** /
> server schema **13** / wire **0.5.0** / paired-software floor **35**；本开发代未部署。仓库历史记录的渠道与 NAS 为 0.5.4，非本轮实时探测结论。
> 新代规范和发布阻塞见 [ADR0026](../adr/0026-durable-restore-authority-generation.md)。以下0.5.4身份记录为历史：
> **0.5.4** / versionCode **34** / Room **29** / 本地数据契约 **6** /
> server schema **13** / floor **21**；协议代 0.4.0（conflict-v2）。家庭 NAS 与
> `deploy/app-update.json` 均已切 **0.5.4**（2026-09-30 协同维护窗：Cargo、渠道与
> 0.5.4/34 APK 同抬，树内 lezi-sync 行为修复随之上线）。构建真值以
> `app/build.gradle.kts` + `config/android-release-compatibility.json` 为准。
>
> **权界**：本文权威 = 技术栈选型、构建身份、权限、自托管更新、质量门与合规。模块图与
> 分层见 [`architecture.md`](./architecture.md)（原 tech.md §2）；同步写路径与超时见
> [`layers/sync.md`](./layers/sync.md)（原 tech.md §3；计时器状态机在
> [`layers/features.md`](./layers/features.md) §5）。章节沿用原 tech.md 编号：§2/§3 已迁出，
> 本文从 §1 直接接 §4。

---

## 1. 技术栈

| 层 | 选型 | 说明 |
|----|------|------|
| 语言 / UI | Kotlin + Jetpack Compose | 夜喂热区、Canvas 时间条、前台服务计时 |
| 架构 | 多模块 + ViewModel + StateFlow | |
| DB | Room | 唯一真相源；本地数据契约 v1 起以相邻迁移链保护 APK 原地替换 |
| 偏好 | DataStore | SettingsLocal |
| 异步 | Coroutines + Flow | **同步不做** WorkManager 后台轮询（规格：仅前台） |
| DI | Hilt | |
| 导航 | Navigation Compose | |
| 图表 | Canvas 时间条 + Compose 自绘 | 未引入第三方图表库（Vico 等为可选未来项，仓库无依赖） |
| 通知 | NotificationCompat + **非精确**本地闹钟 | 护理计划（含下次喂养计划）；**不要求** `SCHEDULE_EXACT_ALARM`；**不为同步/伴侣新记录推送** |
| 计时 | 前台服务 + 状态持久化 | 关 App 仍跑 |
| Widget | Glance | |
| 同步 | `RealSyncPort` + 单一家庭服务器 | **当前候选 0.5.5**（wire 0.5.0）：保留 ADR-0022 commit-first + ConflictSnapshot v2；护理计划意图及恢复能力、floor 35 的配套升级边界以 [`causal-sync-wire.md`](./contracts/causal-sync-wire.md) 为准，mixed generation 在 mutation 前 fail closed |
| NAS 后端 | **Rust + Axum + Tokio + SQLite** | 交付物 `tools/lezi-sync`；单二进制、单卷 `DATA_DIR`（db+media）；层规格见 [`layers/server.md`](./layers/server.md) |
| IAP / 广告 | **不引入** | |
| 测试 | JUnit + 聚合纯函数单测 + 关键 Compose 测试 | |

**构建**

| 项 | 值 |
|----|-----|
| applicationId | `com.lezi.babylog` |
| minSdk | 26 |
| compileSdk | 35 |
| targetSdk | 35 |
| versionName | **当前 tree** 以 `app/build.gradle.kts` + `config/android-release-compatibility.json` 为准（0.5.5 / versionCode **35**）；本轮未部署或探测家庭 NAS，2026-09-30 的 0.5.4 协同 CD 记录仅作历史参考 |
| versionCode | 同上；安装分发单调版本；本地兼容范围由 APK Manifest 的数据契约声明 |
| 本地数据契约 | 当前 tree `v7` / Room **v29**（最低可迁移与永久基线仍为 `v1`：0.3.0 / versionCode 6 / Room v24） |
| **0.3.13 升级源** | versionName `0.3.13`、versionCode **20**、Room **27**、历史 server schema **12** |
| **0.5.5 当前候选** | versionName `0.5.5`、versionCode **35**、Room **29**、local-data contract **7**、server schema **13**、wire **0.5.0**、floor **35**；本轮未部署，配套升级要求见 ADR0026 与 Wire 合同 |
| **0.5.4 历史发布** | versionName `0.5.4`、versionCode **34**、Room **29**、local-data contract **6**、server schema **13**、floor **21**；协议代仍是 0.4.0 conflict-v2。0.5.3 / versionCode **33** 及更早（0.5.2–0.4.0）已发布为升级源。0.5.4 不抬 schema、wire 或 floor。客户端修复随 0.5.4/34 APK。服务端 store 修复（损坏载荷返回 `invalid_stored_payload`、bundles 入口冻结已完成履行、多图一次发布、唤醒恢复校验）随 crate **0.5.4** 于 2026-09-30 与 APK/渠道协同上 NAS |

| 应用名 | 乐记 |

---

## 4. 权限

| 权限 | 用途 | 状态 |
|------|------|------|
| POST_NOTIFICATIONS | 喂奶提醒、计时 | 当前 |
| READ_CALENDAR / WRITE_CALENDAR | 用户主动开启护理计划的系统日历副本时按需申请；拒绝不影响乐记内计划与提醒 | 当前 |
| FOREGROUND_SERVICE（及合规类型） | 喂奶计时 | 当前 |
| RECEIVE_BOOT_COMPLETED | 重启恢复本地提醒/计时 | 当前 |
| 相册 / Photo Picker | 日记照片 | 当前 |
| INTERNET / ACCESS_NETWORK_STATE | HTTPS setup/login、push/pull 与媒体 | 已声明；网络调用受前台 + transport trust + session 约束，不限网络类型 |
| REQUEST_INSTALL_PACKAGES | 自托管应用内更新：调起系统 `PackageInstaller` 会话安装 release APK | 已声明；仅 release `com.lezi.babylog` 通道使用；须用户确认安装与「未知应用来源」授权 |
| ACCESS_FINE_LOCATION / ACCESS_WIFI_STATE | 家庭同步不需要 | 不声明，不存在运行时申请 |
| CAMERA | 扫描管理员 App 提供的普通成员单次登录 QR | 可选硬件；拒绝后仍可手动 endpoint + 申请 |
| SCHEDULE_EXACT_ALARM / USE_EXACT_ALARM | **不申请**；护理计划提醒用非精确闹钟即可 | |
| 麦克风 / 后台定位 / 附近设备 | **不申请** | |

拒绝通知：仍可记账，无提醒。
护理计划本地提醒允许系统在省电策略下批量/延后触发，**不保证**准时到秒；产品不承诺「精确闹钟」体验。下次喂养只复用家庭护理计划及其单一提醒来源，不写入独立时间或安排第二个闹钟；依照 ADR-0012，只承诺从 0.3.0 本地数据契约 v1 起的连续升级。

系统日历副本提供三级本机披露：仅“乐记 · 护理计划”、标题显示“宝宝昵称 · 记录类型”，或再把文字备注写入描述。标准 `CalendarContract.Events` 无通用照片附件字段；最高级别仅写“照片 N 张，打开乐记查看”并配置应用 URI，照片字节不交给系统日历账户。

同一设备上的每个计划只安排一个提醒来源：系统日历副本和开始时提醒严格回读成功后取消乐记重复通知；未启用、拒绝权限，或 provider 已确认已有提醒释放/不存在时，才由乐记使用非精确本地提醒回退。provider 结果不确定或仍持有已有提醒时保持该来源并持久标记待恢复，禁止形成双提醒。

---

## 4.1 发布与数据保护

| 项 | 实现 |
|----|------|
| release R8 | `isMinifyEnabled = true` + `isShrinkResources = true` |
| 系统备份 | `android:allowBackup="false"`；`backup_rules` / `data_extraction_rules` 对齐排除 |
| 明文 HTTP | 家庭 API、登录、同步与应用内更新在 release 中禁用明文；唯一产品例外是 ADR-0015 的家庭 LAN 邀请安装页，且不得映射公网 |
| FileProvider | `cache/export` 与 `cache/camera`（拍照落盘，见 local-photo-loading.md）；不暴露 `files/` / database；升级 APK **不**经 FileProvider 长期暴露 |
| 升级暂存 | 已安装 App 的更新仅用私有 `cache/app-update`；邀请安装页是浏览器首装分发，可进入系统 Download，但不属于应用内更新暂存合同 |
| 日志 | 不打印根密码、access/refresh、grant、Authorization 或敏感 body；用户可见错误过滤技术细节 |

---

## 4.2 自托管应用内更新（侧载 APK）

本产品当前以**家庭服务器侧载**为主通道，提供应用内检查 / 下载 / 系统安装。**不是**
Google Play In-App Updates / Play Core；若未来上架 Play，须另 flavor，不得在 Play 构建走
自研 APK 替换。

| 项 | 合同 |
|----|------|
| 通道 | 平台 `PackageInstaller.Session` + 家庭服务器鉴权元数据/APK；无 FCM、无后台推包 |
| 资格 | 鉴权 `/v1/app-update*` 仅供已加入家庭且 endpoint 已信任、会话有效的客户端；所有 `config/android-release-compatibility.json` 已枚举正式版本都可在家庭 LAN 通过独立 HTTP `8767` 取得同一个已验证 APK，恢复下载资格不受 `min_supported_version_code` 控制 |
| 版本语义 | 比较与门槛只用整数 **versionCode**；**versionName** 仅展示 |
| 双档 | `local < latest` → 可选；`local < minSupportedVersionCode` → 强制全屏（无「稍后」绕过主功能） |
| 请求头 | 受保护同步请求携带 `X-Lezi-Client-Version-Code`（整数） |
| 服务端 API | 鉴权 `GET /v1/app-update`（JSON）与 `GET /v1/app-update/apk`（APK 字节）；与同步共用会话与 TLS/信任。**两者**仅在元数据 + APK sha256 已验证时返回成功体：半通道（仅元数据 / 哈希不符）对元数据路由诚实 404/5xx，避免客户端 dual-tier 把无包可装的 `min_supported` 当成强制升级 floor |
| 门槛 | 协议硬下限 **35** 独立于更新通道是否存在/有效；有效门槛为 `max(35, 已验证通道 minSupported)`，并独立要求当前协议 capability。头缺失或低于有效门槛时权威 sync 写/拉（pull / bundle / media 等）**以及灾难恢复写路径**（restore start / manifest / media / commit）返回 `code=client_update_required`。`POST /v1/causal/commit` 是例外：同一门槛折叠成 409 终态 `capability_mismatch`，客户端 `FamilySyncError` 仍把它映射成强制升级，不重试。**仍放行**已验证通道上的更新元数据与 APK 下载。从未验证过、元数据缺失或通道文件消失时，协议硬下限及 capability 检查仍然生效。原子 promote 中途（新 APK + 旧元数据哈希暂不符）保留 **last-known-good** floor 直至新 pair 验证成功，避免通道要求的更高门槛在替换期间下降；负向 stamp 缓存避免半部署下每请求重哈希 APK |
| 诚实客户端闸 | `X-Lezi-Client-Version-Code` / `minSupported` 是对**诚实官方 App** 的兼容闸：阻止半兼容旧客户端脏写，**不是**防篡改安全根。头可被非官方客户端伪造；**真协议硬闸**仍靠 setup-status **capabilities**、wire schema/payload 校验与已验证会话。服务端不对「伪造高 version 头」做强绑定证明（权威叙述；同步合同见 [sync-trusted-endpoint.md §7.5](./contracts/sync-trusted-endpoint.md)） |
| 强制壳兜底 | `client_update_required` 后：元数据成功且 **versionCode &gt; local** → `ForcedAppUpdateState.WithPackage`（可安装 CTA，即使双档会判 optional）；元数据/通道失败或无更高 versionCode → **`PackageUnknown` 强制壳**（说明 +「重试检查更新」），`SyncStatus` 保持 Idle，**不得**呈现为泛同步/NAS 故障或「假正常」无强制层。已有强制态时，手动 `checkAppUpdate` 与同步 CUR 恢复共用：`versionCode &gt; local` 一律升为可安装 Forced；否则不得拆壳/刷 optional 横幅；`checkAppUpdate` **Result** 在壳保留时返回 `ForcedUpdate`/`ForcedPackageUnknown`（不得 bare UpToDate/Optional）；失败非 CUR 同步 **不** piggyback 拆壳；权威同步成功后的 piggyback 才可清壳；未加入家庭才清 surface。壳内可恢复会话（reauth）与 401 下载单次 refresh 重试；鉴权更新不可用且 origin 已知时给出 **8767** 局域网邀请安装引导；**无**「稍后」绕过主功能 |
| wire 破坏纪律 | 封闭 wire / `schema_version` / allowlist 的破坏性变更 **必须先** 抬 `min_supported_version_code` 并发布已验证可安装包，再让新 shape 入站；**不**做 dual-read / skip-unknown 协议。当前 floor 与「支持范围内 wire 冻结」关系见下方 §4.2.1 与 [DEPLOY.md](../../tools/lezi-sync/deploy/DEPLOY.md) wire-break checklist |
| 部署 | `package-nas` **fail-closed**：须 release APK + 合法 `app-update.json` 且 sha256 一致；鉴权 `/v1/app-update*` 不设匿名旁路，同一已验证 APK 可由 §4.3 的隔离邀请安装页提供首装 |
| 客户端缝 | `SyncPort`：`checkAppUpdate`、`availableOptionalAppUpdate` / `availableForcedAppUpdate`（`ForcedAppUpdateState?`）、`installAvailableAppUpdate`、会话内 dismiss；UI 不直连 PackageInstaller |
| 安装约束 | 装前解析 APK 归档：`packageName` == 本机 applicationId == 元数据；`versionCode` == 元数据且 &gt; 本机；签名证书与已装乐记一致；再 PackageInstaller 同签名原地替换；仅 release `applicationId = com.lezi.babylog`；本轮不承诺 debug 后缀包自更新 |
| 历史枚举 | `config/android-release-compatibility.json` 是 versionCode、versionName、Room schema、本地数据契约与当前同步 floor 的单一清单；Gradle、Android 更新矩阵、Room 迁移夹具和 lezi-sync 路由矩阵共同校验。永久无损基线仍是 0.3.0 / versionCode 6 / contract 1，后续正式版必须连续追加 |
| 无残留 | 流程结束后应用私有目录无 APK；**不**承诺清除系统 PackageInstaller 内部缓存 |

元数据形状（wire **snake_case**；部署文件 `app-update.json`）：

```json
{
  "package_name": "com.lezi.babylog",
  "version_code": 34,
  "version_name": "0.5.4",
  "min_supported_version_code": 21,
  "sha256": "<64 lowercase hex of APK>",
  "release_notes": "可选"
}
```

**当前真实渠道 floor21/code34** 由保留的已验证 `app-update.json` 表示。
`config/android-release-compatibility.json` 的 minimum_sync_version_code35 与 upgrade_target35/0.5.5
是新代发布目标，不证明APK已生成或生产floor已激活。二者当前有意不同；签名APK35与真实metadata、
完整的既有 schema13／Room29 升级兼容验证和单独部署授权齐备前不得发布新渠道或替换NAS。
本代保留 schema13，不要求或授权13→14转换；配套软件升级仍须满足亲喂计划新取值规则，
不能仅因数据库 schema 未变就允许旧客户端混用，或承诺写入新取值后可降级旧服务端。
发版与 CD 前必须重核 catalog、`app-update.json`、
Cargo/Gradle 与签名 APK sha256 一致；不得沿用历史 16/0.3.9 或 16/0.3.12 示例当生产 floor。

触发：① 已加入且前台对信任 endpoint 握手/同步时顺带检查——0.5 起带节流门控：用户真实触发
（回前台 / 下拉刷新）必查；心跳 kick / 前台续跑等非用户轮仅在距上次**成功**检查 >1h 时补查
（时刻耐久存储；检查失败不武装节流窗）；② 菜单关于区点击检查。门控**不新增任何唤醒源**：
发现请求只搭「本就发生」的轮，绝不自调度轮次；AUDIT-20260801-P1-01 的拆壳判据仅应用于
仍执行 piggyback 的轮。
可选更新：确认层 → 下载 → sha256 → **归档身份校验**（包名 / versionCode / 签名）→
PackageInstaller；失败清理私有暂存且**不** commit 异包。账户区非阻塞横幅，同一 versionCode
**进程会话内**「稍后」不再刷屏。强制更新：根全屏（含 onboarding 之上）消费系统返回键并遮罩主功能。
在强制壳内展开会话恢复后，系统返回只收起恢复并回到强制壳，不结束持有未保存业务草稿的 Activity，也不挂回普通业务页面。
有包时立即安装（同一装前身份门），元数据暂缺时仅「重试检查更新」；壳内可 reauth 与 8767 邀请安装引导。
详细同步门槛与错误语义见
[sync-trusted-endpoint.md](./contracts/sync-trusted-endpoint.md)；部署 runbook 见
[`tools/lezi-sync/deploy/DEPLOY.md`](../../tools/lezi-sync/deploy/DEPLOY.md)。

### 4.2.1 Wire-break → raise min checklist

封闭家庭 wire（ADR-0008）**不做** dual-read / skip-unknown。多机 home LAN 的安全阀是
`min_supported_version_code` + 已验证可安装包：

1. **判定是否破坏性：** 新实体 type、封闭 key 集合变更、record/care_plan `schema_version`  bump、服务端 allowlist 放宽使旧客户端 `requireExactKeys` 整页失败等。
2. **先抬 floor：** 在 `app-update.json` 将 `min_supported_version_code` 提到**能解析新 shape 的最低官方 versionCode**；同时准备该 versionCode（或更高）的 **已签名 release APK**，`sha256` 与包一致。
3. **先发布可安装通道：** CD 原子对发布（APK 再 metadata），确认 `load_verified` 成功；旧客户端随后在权威 sync / restore 写路径收到 `client_update_required` 并能装包。
4. **再启用新写入：** 仅当通道已验证后，再让新客户端/服务端发布破坏性 shape。
5. **支持范围内 wire 冻结：** 源码发布目标 floor 以 catalog `minimum_sync_version_code`（本代 **35**）为准，到最新 versionCode 之间 wire/`schema_version`/allowlist 视为冻结；该范围内多机可混用。下一轮任何破坏性变更必须先执行 2–4，**禁止**指望旧机 skip-unknown。低于 floor 的已发布版本仍可取得 APK，但不能借此继续使用旧同步 wire。
6. **0.4.0 因果切割已完成：** 新实体 `wake_observation`、因果字段 `base_version`/`mutation_id`/`version_id`、verdict 枚举与移除服务器近邻落选均属破坏性 wire。该历史发布的 floor=21、schema 13；本代保留 schema13，但因亲喂计划取值域扩展采用 wire0.5.0／floor35，并在更新渠道缺失时仍强制校验版本与 `nursing_plan_intent_v1`。真实已发布渠道仍以 §4.2 的已验证元数据为准。权威 shape 见 [`causal-sync-wire.md`](./contracts/causal-sync-wire.md)。

完整运维条目见 [`tools/lezi-sync/deploy/DEPLOY.md`](../../tools/lezi-sync/deploy/DEPLOY.md)「Wire-break checklist」。

---

## 4.3 邀请安装页（首次安装与历史版本恢复）

尚未安装乐记的受邀 Android 设备可用系统相机扫描同一个成员登录二维码，进入
由 lezi-sync 提供的安装页；无法使用当前鉴权 HTTPS 更新流的历史正式版本也可直接下载同一个
APK。该页不完成家庭登录，也不放宽同步门禁。首次安装结束后用户必须打开乐记并重新扫描
管理员仍在展示的二维码；同签名原地升级保留本机数据、家庭 session、endpoint 与 SPKI 信任，
只有原会话本来就处于 reauth 时才继续既有重新登录流程。

邀请安装有且仅有一种 origin，互斥：

| 项 | 合同 |
|----|------|
| 旧 LAN origin | `LEZI_LAN_APK_DOWNLOAD_ORIGIN` 只接受 `http://<IPv4-或-IPv4-DNS>:8767`（无用户信息/路径/查询/片段）。设置则另绑 8767，只挂 `GET /join` 与 `GET /download/lezi.apk`。冻结 NAS 回滚继续用这条 |
| 新 HTTPS origin | `LEZI_INVITE_INSTALL_ORIGIN` 只接受 `https://invite.example.invalid`（无用户信息/路径/查询/片段，端口空或 443）。设置则把同样两条路由挂上 HTTPS public 面，**不**创建 8767 监听 |
| 互斥 | 两个都设、或新 origin 写成其它主机/http/带路径：配置失败，进程不启动。两个都不设：无邀请页、无 8767 |
| QR envelope | 内层继续使用严格 member-login JSON v1；外层为 `{landing_url}#v1.<base64url>`。`landing_url` 为所选 origin 加 `/join`。新版读取 URL 与旧 raw JSON，旧版不理解 URL 时改用系统相机下载新版 |
| 发现 | 管理员鉴权创建 grant 的响应可附加 `landing_url`；缺失时 App 继续生成旧 raw JSON QR，存在但不合法时拒绝展示 |
| APK | 匿名下载；复用 `app-update.json` 与运行时 SHA-256 校验，缺包、空包或哈希不符 fail closed |
| 页面 | 不显示家庭名、成员称呼或 grant，不加载第三方资源；正常页面立即清除地址栏 fragment，并设置 no-store、no-referrer、CSP 与禁止嵌入 |
| 风险 | 旧 8767 明文：用户已明确接受 HTTP 中间人可替换页面/APK 或盗用 grant；只允许可信家庭 LAN，禁止公网转发。新 HTTPS origin 走系统 PKI，不再听 8767 |

该合同不改变生产 TOFU/SPKI、设备会话或既有鉴权 app-update；架构边界见
[ADR-0015](../adr/0015-isolate-lan-invite-install-distribution.md)。


---

## 5. 当前工程能力

| 模块 | 状态 |
|------|------|
| onboarding / log / timer / settings | 当前交付 |
| family UI；RealSyncPort 默认 DI（含 Disabled）；NoOpSyncPort 仅公开测试桩（无 Hilt） | 当前交付 |
| summary / growth / search / export TXT / widget | 当前交付 |
| PDF / custom / food types / CarePlan calendar | 当前交付 |
| RealSync 家网实现 | 默认 DI；本机 Docker + 双模拟器前台已验收 |
| 自托管应用内更新 | 鉴权元数据/APK、双档门槛、PackageInstaller、打包 fail-closed；见 §4.2 |
| `lezi-sync` 部署 | VPS 部署工具链已删除（2026-09-06）。现网家庭仍在 NAS；保留 NAS 冻结回滚路径，权限修复与容器替换分别授权，见 `tools/lezi-sync/deploy/DEPLOY.md` |

**NAS schema / offline-migrate 边界：** 日常启动只接受精确 current schema（fail closed；见 [ADR-0008](../adr/0008-support-only-fresh-current-product-contracts.md)）。当前 server schema **13**。历史 v3 数据根不得在 startup 自动迁移；维护窗内 schema 11/12→13 走 [ADR-0013](../adr/0013-offline-migrate-is-maintenance-window-cutover.md) 的 copy-out：备份上 `lezi-sync offline-migrate`（只接受冻结 user_version **11 或 12**→13）、独立临时 `out/`、`validate`，再经已授权维护窗 stop/copy-back/TLS CD。发布二进制含该子命令 ≠ 滚动兼容；普通 CD 不执行。权威 runbook：[`tools/lezi-sync/deploy/copy-back-tls-cutover-runbook.md`](../../tools/lezi-sync/deploy/copy-back-tls-cutover-runbook.md)。

> **当前同步策略：** 中心化家庭服务器、仅前台、无即时通知、**不做**后台轮询。现网家庭仍在 NAS；VPS 部署线已删除，不改变客户端的可信 endpoint 合同。

---

## 6. 非功能指标

| 项 | 目标 |
|----|------|
| 快记 | 点入口立即打开预填 Composer；确认前不得写入 |
| 冷启 | 尽快可点图标（目标约 1s 量级可交互） |
| 当日时间轴 | 按日查询，避免一次加载全历史 |
| 计时电池 | FGS + 有限**非精确**闹钟，禁止空转轮询 / 精确闹钟权限 |
| 隐私 | 默认本机；日志不打印健康明细 |
| 无障碍 | contentDescription；≥48dp；字体缩放不裁主按钮 |

---

## 7. 质量门

**单测优先**

- 日汇总计算
- 睡眠配对与 `anomaly_flag`
- 便便枚举边界；尿尿 `pee_amount` 1–3；排泄图标资源存在性（designsystem）
- `client_uuid` 幂等合并
- NoOpSyncPort 不抛未捕获异常
- fresh Room 创建当前 schema；force-stop/重启后当前数据、dirty/发布回执、TimerState 与提醒清理状态保持
- APK 原地替换先经过本地数据升级门禁；相邻迁移先按受影响域创建校验快照，失败不得自动清库
- 基线之前、未来版、空间不足或不一致数据稳定进入恢复界面；业务、提醒、Widget 不得提前打开持久化
- 应用内更新：SyncPort 检查结果（NotJoined / UpToDate / Optional / Forced）、校验失败不安装、
  同步被 `client_update_required` 映射为强制态；lezi-sync HTTP 鉴权元数据/APK 与门槛
  （pull / media GET 在低或缺 `X-Lezi-Client-Version-Code` 时 `client_update_required`；
  客户端权威 pull 携带 version 头；目标 APK 本地数据契约必须覆盖当前契约；`package-nas`
  用 `apkanalyzer` 对照追加式契约账本，check-only 缺 APK / sha 错 / 契约错均 fail-closed 烟测
  `tools/lezi-sync/deploy/test-package-nas-app-update.sh`，CI 随 lezi-sync workflow 跑）

**冒烟（当前 APK）**

1. 安装 → 建宝宝
2. 记：配方奶、便（带色）、睡下/醒来、母乳计时
3. 核对日汇总与时间条
4. force-stop → 再开 → 数据一致
5. 开深色、切第二宝宝
6. 账户页可见且无崩溃、无购买入口
7. （已加入家庭）关于区显示 `版本 {versionName}`；检查更新得到已最新或可选确认；强制时全屏

---

## 8. 交付物

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk  # 必须通过 release 签名与 apksigner 校验
README.md  # ./gradlew assembleDebug assembleRelease
docs/spec/  # 本规格树
```

---

## 9. 合规备忘

- 包名、图标、文案自有；不使用参考产品商标。
- 上架前自备隐私说明（本机数据、是否同步、儿童信息）。
- 曲线数据注明来源与免责，不作医疗诊断。
- **Play 渠道**：不得在 Play 分发构建中使用本自托管 APK 替换通道；Play 构建须另 flavor 并仅用
  Play In-App Updates（若上架）。当前主路径为侧载 + 家庭服务器。
