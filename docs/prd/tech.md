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
| 图表 | Canvas 时间条 + 轻量图表库（如 Vico） | |
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
| versionName | `0.2.0-offline-v2-beta`（同步实现已入工作树，环境验收待补） |
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

目标依赖方向：`app → feature → domain → core`；feature 互不依赖。当前 `domain → sync`
用于本地写后的同步触发 seam；后续可把端口接口下沉到更内层模块，避免 domain
依赖具体同步模块。
计时状态落 `core`/`domain`，避免 log ↔ timer 循环依赖。

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

实现无后台同步、无推送拉同步；`baseUrl` 无内置默认（QR 可带地址）。
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
| FOREGROUND_SERVICE（及合规类型） | 喂奶计时 | V1 |
| RECEIVE_BOOT_COMPLETED | 重启恢复本地提醒/计时 | V1 |
| 相册 / Photo Picker | 日记照片 | V1 |
| INTERNET / ACCESS_NETWORK_STATE | 家网 health、push/pull 与媒体 | 已声明；网络调用仍受前台 + Wi-Fi + health 门闩 |
| CAMERA | 扫描家庭邀请 QR | 可选硬件；无相机仍可粘贴载荷 |
| SCHEDULE_EXACT_ALARM / USE_EXACT_ALARM | **不申请**；喂奶/日程提醒用非精确闹钟即可 | |
| 麦克风 / 定位 | **不申请** | |

拒绝通知：仍可记账，无提醒。
本地提醒（下次喂奶、日程）允许系统在省电策略下批量/延后触发，**不保证**准时到秒；产品不承诺「精确闹钟」体验。

---

## 5. 功能 × 分期（工程）

| 模块 | V1 | V1.5 | V2 |
|------|----|------|-----|
| onboarding / log / timer / settings | ✓ | | |
| family UI；NoOpSync 实现保留（非默认 DI） | ✓ | | |
| summary / growth / search / export TXT / widget | | ✓ | |
| PDF / custom / food types / calendar | | | ✓ |
| RealSync 家网实现 | | | 代码与自动化完成；双设备环境验收待补 |
| `lezi-sync` NAS | | | API/镜像配置完成；Docker/NAS 运行验收待补 |

> **V2 同步策略（2026-07-25）**：中心化 NAS、硬家网、仅前台、无即时通知；
> **不做**后台 60s 对齐。实现与自动化已完成；当前环境无 Docker/Podman、
> ADB 双设备和 NAS 家网，故仍需镜像运行与双端设备验收后才能宣称可部署交付。

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
app/build/outputs/apk/release/app-release-unsigned.apk
README.md  # ./gradlew assembleDebug
docs/prd/  # 本产品规格
```

---

## 9. 合规备忘

- 包名、图标、文案自有；不使用参考产品商标。
- 上架前自备隐私说明（本机数据、是否同步、儿童信息）。
- 曲线数据注明来源与免责，不作医疗诊断。
