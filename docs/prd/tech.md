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
| 异步 | Coroutines + Flow；WorkManager（V2 同步） | |
| DI | Hilt | |
| 导航 | Navigation Compose | |
| 图表 | Canvas 时间条 + 轻量图表库（如 Vico） | |
| 通知 | NotificationCompat + 精确闹钟策略 | 下次喂奶 |
| 计时 | 前台服务 + 状态持久化 | 关 App 仍跑 |
| Widget | Glance（V1.5） | |
| 同步 | `SyncPort` 接口；V1 空实现 | 见 data-model |
| IAP / 广告 | **不引入** | |
| 测试 | JUnit + 聚合纯函数单测 + 关键 Compose 测试 | |

**构建**

| 项 | 值 |
|----|-----|
| applicationId | `com.lezi.babylog` |
| minSdk | 26 |
| targetSdk | 34+（随政策） |
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
:sync                 # SyncPort + NoOpSync / 日后 RealSync
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

依赖方向：`app → feature → domain → core`；feature 互不依赖。  
计时状态落 `core`/`domain`，避免 log ↔ timer 循环依赖。

---

## 3. 写路径

```text
UI 事件
  → domain UseCase
  → Room（立刻成功 → UI 刷新）
  →（V2）Outbox + SyncPort.push
```

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
| SCHEDULE_EXACT_ALARM / 等效 | 精确提醒 | V1（按系统策略降级） |
| RECEIVE_BOOT_COMPLETED | 重启恢复闹钟/计时 | V1 |
| 相册 / Photo Picker | 日记照片 | V1 |
| INTERNET | 仅 V2 同步 | V2 再声明也可用 runtime 判断 |
| 麦克风 / 定位 | **不申请** | |

拒绝通知：仍可记账，无提醒。

---

## 5. 功能 × 分期（工程）

| 模块 | V1 | V1.5 | V2 |
|------|----|------|-----|
| onboarding / log / timer / settings | ✓ | | |
| family UI + NoOpSync | ✓ | | |
| summary / growth / search / export TXT / widget | | ✓ | |
| RealSync / PDF / custom / food types / calendar | | | ✓ |

---

## 6. 非功能指标

| 项 | 目标 |
|----|------|
| 快记 | 1–2 tap，无强制二层弹窗 |
| 冷启 | 尽快可点图标（目标约 1s 量级可交互） |
| 当日时间轴 | 按日查询，避免一次加载全历史 |
| 计时电池 | FGS + 有限闹钟，禁止空转轮询 |
| 隐私 | 默认本机；日志不打印健康明细 |
| 无障碍 | contentDescription；≥48dp；字体缩放不裁主按钮 |

---

## 7. 质量门

**单测优先**

- 日汇总计算  
- 睡眠配对与 `anomaly_flag`  
- 便便枚举边界  
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
app/build/outputs/apk/release/app-release.apk   # 本地 keystore，密钥不入库
README.md  # ./gradlew assembleDebug
docs/prd/  # 本产品规格
```

---

## 9. 合规备忘

- 包名、图标、文案自有；不使用参考产品商标。  
- 上架前自备隐私说明（本机数据、是否同步、儿童信息）。  
- 曲线数据注明来源与免责，不作医疗诊断。
