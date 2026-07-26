# 乐记 — Android 技术说明

> 主 PRD：[`README.md`](./README.md) · 数据：[`data-model.md`](./data-model.md) · 家庭同步：[`sync-home-lan.md`](./sync-home-lan.md)

---

## 1. 技术栈

| 层 | 选型 | 说明 |
|----|------|------|
| 语言 / UI | Kotlin + Jetpack Compose | 夜喂热区、Canvas 时间条、前台服务计时 |
| 架构 | 多模块 + ViewModel + StateFlow | |
| DB | Room | 唯一真相源（V1） |
| 偏好 | DataStore | SettingsLocal |
| 异步 | Coroutines + Flow | **同步不做** WorkManager 后台轮询（规格：仅前台） |
| DI | Hilt | |
| 导航 | Navigation Compose | |
| 图表 | Canvas 时间条 + Compose 自绘 | 未引入第三方图表库（Vico 等为可选未来项，仓库无依赖） |
| 通知 | NotificationCompat + **非精确**本地闹钟 | 下次喂奶 / 日程；**不要求** `SCHEDULE_EXACT_ALARM`；**不为同步/伴侣新记录推送** |
| 计时 | 前台服务 + 状态持久化 | 关 App 仍跑 |
| Widget | Glance（V1.5） | |
| 同步 | `RealSyncPort` + 家局域网 NAS | 已实现持久会话、家网/前台门闩、Outbox、Bearer push/pull 与媒体 |
| NAS 后端 | **Rust + Axum + Tokio + SQLite** | 交付物 `tools/lezi-sync`；单二进制、单卷 `DATA_DIR`（db+media） |
| IAP / 广告 | **不引入** | |
| 测试 | JUnit + 聚合纯函数单测 + 关键 Compose 测试 | |

**构建**

| 项 | 值 |
|----|-----|
| applicationId | `com.lezi.babylog` |
| minSdk | 26 |
| compileSdk | 35 |
| targetSdk | 35 |
| versionName | `0.2.5` |
| versionCode | `4`（Play/安装升级单调整数；与 versionName 独立） |
| 应用名 | 乐记 |

---

## 2. 模块划分

```text
:app
:core:model
:core:database
:core:datastore
:core:common
:core:ui
:designsystem
:domain
:sync                 # SyncPort + NoOpSync + RealSyncPort 家网实现
:feature:onboarding
:feature:log          # 记录首页、编辑、图标网格
:feature:timer
:feature:summary      # V1.5
:feature:growth       # V1.5
:feature:family       # 账户 / 共享 UI
:feature:settings
:feature:export       # V1.5+
:feature:search       # V1.5
:feature:widget       # V1.5
```

目标依赖方向：`app → feature → domain → core`；feature **互不**依赖。

当前已存在的额外边（有意 seam，后续可下沉端口接口）：

| 边 | 用途 |
|----|------|
| `domain → sync` | 本地写后的同步触发 |
| `feature:log → sync` | Composer / 日志路径触发 sync |
| `feature:family → sync` | 账户页 join/create/invite/leave |

计时状态落 `core`/`domain`，避免 log ↔ timer 循环依赖。

**运行环境 / 验收状态**：本机 Docker `lezi-sync` 与双模拟器前台交叉可见路径已验证；
严格 live 服务端路径还完成了建家、邀请码加入、双向协议和头像 ACL。物理 NAS 生产、
双真机和相机扫码仍 open，详见
[Ticket 09](../../.scratch/home-lan-sync/issues/09-dual-device-foreground-acceptance.md)。

---

## 3. 写路径

```text
UI 事件
  → domain UseCase
  → Room（立刻成功 → UI 刷新）
  → 标记 syncDirty；同步触发将 baby / record / media 快照入 Outbox
  → 仅当：前台 && 家 Wi‑Fi && NAS health && 已配置 token
        → push；回前台/下拉 → pull + 媒体字节
```

实现无后台同步、无推送拉同步；单一 host:port + 本机 SSID 白名单≤2 为门闩；空态 UI 预填 `192.168.50.4:8765` 与当前 SSID，邀请 QR 可带 host、port、code 与最多两个 SSID。
未配置时为 `Disabled`，离线/非家网写入仍先落 Room 并保留待同步状态。

计时器：

```text
开始 → 持久化 TimerState → 前台服务走秒
完成 → 写入 nursing Record → 停服务
进程被杀 → 启动时读 TimerState 恢复或提示结束
```

---

## 4. 权限

| 权限 | 用途 | 分期 |
|------|------|------|
| POST_NOTIFICATIONS | 喂奶提醒、计时 | V1 |
| READ_CALENDAR / WRITE_CALENDAR | 用户主动开启护理计划的系统日历副本时按需申请；拒绝不影响乐记内计划与提醒 | V2 |
| FOREGROUND_SERVICE（及合规类型） | 喂奶计时 | V1 |
| RECEIVE_BOOT_COMPLETED | 重启恢复本地提醒/计时 | V1 |
| 相册 / Photo Picker | 日记照片 | V1 |
| INTERNET / ACCESS_NETWORK_STATE | 家网 health、push/pull 与媒体 | 已声明；网络调用仍受前台 + Wi-Fi + health 门闩 |
| ACCESS_FINE_LOCATION | 读取当前 SSID，执行硬家庭 Wi-Fi 门闩 | 仅用户操作家庭同步时申请；Android 将 SSID 视为位置敏感字段，应用不读取坐标、不上传 SSID |
| CAMERA | 扫描家庭邀请 QR | 可选硬件；无相机仍可粘贴载荷 |
| SCHEDULE_EXACT_ALARM / USE_EXACT_ALARM | **不申请**；喂奶/日程提醒用非精确闹钟即可 | |
| 麦克风 / 后台定位 / 附近设备 | **不申请** | |

拒绝通知：仍可记账，无提醒。
本地提醒（下次喂奶、日程）允许系统在省电策略下批量/延后触发，**不保证**准时到秒；产品不承诺「精确闹钟」体验。

系统日历副本提供三级本机披露：仅“乐记 · 护理计划”、标题显示“宝宝昵称 · 记录类型”，或再把文字备注写入描述。标准 `CalendarContract.Events` 无通用照片附件字段；最高级别仅写“照片 N 张，打开乐记查看”并配置应用 URI，照片字节不交给系统日历账户。

同一设备上的每个计划只安排一个提醒来源：系统日历副本成功时写入事件开始时提醒并取消乐记重复通知；未启用、拒绝权限或副本写入失败时，由乐记使用非精确本地提醒回退。

---

## 4.1 发布与数据保护

| 项 | 实现 |
|----|------|
| release R8 | `isMinifyEnabled = true` + `isShrinkResources = true` |
| 系统备份 | `android:allowBackup="false"`；`backup_rules` / `data_extraction_rules` 对齐排除 |
| 明文 HTTP | 家网 PRD 默认：`usesCleartextTraffic=true`；可选反向代理 HTTPS（见 `sync-home-lan.md`） |
| FileProvider | 仅 `cache/export`；不暴露 `files/` / database |
| 日志 | 不打印 family token / Authorization；用户可见错误经 `productUiError` 过滤技术细节 |

---

## 5. 功能 × 分期（工程）

| 模块 | V1 | V1.5 | V2 |
|------|----|------|-----|
| onboarding / log / timer / settings | ✓ | | |
| family UI；NoOpSync 实现保留（非默认 DI） | ✓ | | |
| summary / growth / search / export TXT / widget | | ✓ | |
| PDF / custom / food types / calendar | | | ✓ 本机已交付 |
| RealSync 家网实现 | | | ✓ 默认 DI；本机 Docker + 双模拟器前台已验收 |
| `lezi-sync` NAS | | | ✓ API/镜像/自动化；物理 NAS 生产部署待目标环境 |

> **V2 同步策略（2026-07-25）**：中心化 NAS、硬家网、仅前台、无即时通知；
> **不做**后台 60s 对齐。实现与自动化已完成；本机 Docker 与双模拟器前台
> formula/pee 交叉可见已验证。物理 NAS / 双真机 / 相机扫码仍 open。

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
- `client_uuid` 幂等合并（为 V2 预埋纯函数）
- NoOpSync 不抛未捕获异常

**冒烟（V1 APK）**

1. 安装 → 建宝宝
2. 记：配方奶、便（带色）、睡下/醒来、母乳计时
3. 核对日汇总与时间条
4. force-stop → 再开 → 数据一致
5. 开深色、切第二宝宝
6. 账户页可见且无崩溃、无购买入口

---

## 8. 交付物

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk  # 必须通过 release 签名与 apksigner 校验
README.md  # ./gradlew assembleDebug assembleRelease
docs/prd/  # 本产品规格
```

---

## 9. 合规备忘

- 包名、图标、文案自有；不使用参考产品商标。
- 上架前自备隐私说明（本机数据、是否同步、儿童信息）。
- 曲线数据注明来源与免责，不作医疗诊断。
