# 乐记 — Android 技术说明

> 主 PRD：[`README.md`](./README.md) · 数据：[`data-model.md`](./data-model.md)

---

## 1. 技术栈

| 层 | 选型 | 说明 |
|----|------|------|
| 语言 / UI | Kotlin + Jetpack Compose | 夜喂热区、Canvas 时间条、前台服务计时 |
| 架构 | 多模块 + ViewModel + StateFlow | |
| DB | Room | 唯一真相源（V1） |
| 偏好 | DataStore | SettingsLocal |
| 异步 | Coroutines + Flow | 当前没有 WorkManager 后台同步任务 |
| DI | Hilt | |
| 导航 | Navigation Compose | |
| 图表 | Canvas 时间条 + 轻量图表库（如 Vico） | |
| 通知 | NotificationCompat + **非精确**本地闹钟 | 下次喂奶 / 日程；**不要求** `SCHEDULE_EXACT_ALARM`；**不为同步/伴侣新记录推送** |
| 计时 | 前台服务 + 状态持久化 | 关 App 仍跑 |
| Widget | Glance（V1.5） | |
| 同步 | `SyncPort` + `RealSyncPort` 原型 | 当前 DI 使用 RealSync；仍是未通过 V2 安全验收的开发态实现 |
| IAP / 广告 | **不引入** | |
| 测试 | JUnit + 聚合纯函数单测 + 关键 Compose 测试 | |

**构建**

| 项 | 值 |
|----|-----|
| applicationId | `com.lezi.babylog` |
| minSdk | 26 |
| compileSdk | 35 |
| targetSdk | 35 |
| versionName | `0.2.0-offline-v2-beta`（同步不在该版本验收范围） |
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
:sync                 # SyncPort + NoOpSync + 当前 RealSync 开发原型
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
是 RealSync 原型遗留的 V2 架构问题，已在源码审查中登记并延期到 V2 hardening。
计时状态落 `core`/`domain`，避免 log ↔ timer 循环依赖。

---

## 3. 写路径

```text
UI 事件
  → domain UseCase
  → Room（立刻成功 → UI 刷新）
  → 当前部分写路径 enqueue Outbox
  → 用户在家庭页触发 RealSyncPort push / pull
```

当前没有 WorkManager 自动同步。RealSync 默认注入、进程内默认开启，连接开发机
`http://10.0.2.2:8765`；这条链路仅供开发验证，不是可发布的安全同步能力。

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
| INTERNET | RealSync 开发原型 | 当前 Manifest 已声明；V2 hardening 后再决定发布策略 |
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
| RealSync | | 开发原型已存在 | V2 完成安全、鉴权、持久化与后台策略验收 |

> 2026-07-24 源码审查将 V2-1～V2-9（认证与 ACL、明文 HTTP、默认开启、
> 清库/outbox 完整性、family id 与 cursor 持久化、异常写路径、模块方向、
> 双 outbox 路径及后台同步测试）明确延期。本期只同步文档事实，不将现有
> RealSync 原型标记为“已修复”或“可发布”。

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
