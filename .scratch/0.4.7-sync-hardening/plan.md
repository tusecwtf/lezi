# 0.4.7 同步加固 — 设计共识（grill 定稿）

日期：2026-08-30 · 前置：`.scratch/0.4.5-sync-stuck-remediation/`（已闭环）
决策方式：grill 两轮 + 修订，全部客户端、零 wire、零服务端改动。

## 范围

0.4.7 = **sync 加固三件套**（elder 面已按用户决定移入 0.4.6 重出，见下）。

## 定稿决策

| # | 决策 | 结论 |
|---|---|---|
| Q1 | 真门定位重放 | **做**：NAS 副本（仍留存，免 sudo）三页重放进 `ReplicaEngineRig`，member 角色、全新库 |
| Q2′ | 校验重构 (d) | **先落「密封判决」再跑重放**：`apply*` 返回 `Boolean → ApplyVerdict(Applied/Deferred(reason))`，编译期根除无名失败；`PullDiagnosticReceipt` 加 `reason`（Room 本地，零 wire）；重放成功=点名门并修掉，失败=复发即自证，不出诊断装机轮次 |
| Q3 | 台账重置边界 | **cursor 归零即清**（重装/重登/被移除后重加）；不用 generation/升级触发 |
| Q4 | 3 次上限 | **保持 `MAX_PULL_STALL_ATTEMPTS=3`**；reason 分流后结构性洞（永久跳过）与状态性 misfire（backoff 重试）可差异化，届时再依据证据调整 |
| Q5 | 跳过可见性 | **状态行聚合「N 项未收下」+ 明细清单入口**（类型/短id/时间）；不做时间轴占位 |
| Q6 | 跳过与主状态 | **照常「已同步」，跳过项独立警告行**；不降级主状态，不重演 0.4.5 恐慌循环 |
| Q7 | elder 并版 | **（用户推翻推荐）elder 并入 0.4.6 重出**，`c8e5d3b9` 作废；理由：成员手机=姥姥=elder 目标设备，全家一次到位 |
| Q7a | elder 验证深度 | **仅 JVM 门禁（test+lintDebug）即出包**；4 个 Elder DeviceTest + 字体三档目视移装机后（成员手机 无线 ADB 192.168.50.x 跑 `connectedDebugAndroidTest`，红了按跟进修处理不阻塞） |
| Q7b | 成员手机 复验深度 | **点检**（同步状态行 + elder 界面目视）；sync 代码与已验证构建逐字节相同，结论可迁移 |

## 0.4.6 重出序列（定稿，等用户确认窗口执行）

```
./gradlew test + lintDebug（elder 改动 01:11–01:28 晚于上次全绿，必须重跑）
→ assembleRelease + 签名 → app-update.json 重对齐（sha256/signer）
→ LEZI_PACKAGE_APP_UPDATE_CHECK_ONLY=1 ./deploy/test-package-nas-app-update.sh
→ adb install -r 到 成员手机 → 点检
→ NAS /download 渠道替换（fail-closed 三对齐）+ 全家升级
```

## 范围增补（2026-08-30 to-tickets 定稿）

用户追加**契约升级**轨道，0.4.7 由纯客户端变双端版本。约束三条：旧 APK（0.4.5/0.4.6）必须
兼容（wire 纯加性，双向优雅降级）；服务端 DB 可改（预计零 schema 变更，普查为纯读导出）；
**激活数据（非墓碑）零改写**为硬不变量（部署以 pre/post 活集逐字节等价校验举证）。
普查形态定稿：**每实体类型 计数 + 活集键摘要**（G5=(b)，G5=(a) 计数法有同数异集盲区，
(c) 逐设备台账为家庭规模下的多余负重）；修复路径统一为全量重对账（复用台账自愈）。
工单见 `issues/01`–`09`（spec.md 为 parent，其 Out of Scope 中「服务端 Rust」条目由此增补
取代）。

## 0.4.7 工作项与验收

1. **判决重构**（Q2′）：8 个 `apply*` 返回类型改造 + 回执 codec + 测试改写。
   验收：`return false` 从 apply 树中消失（编译器强制）；每个 `DeferredReason` 可被测试构造。
2. **真实三页重放**（Q1）：DB 副本 → 三页 fixture → rig 重放。
   验收：若 `unresolved` 非空 ⇒ reason 点名门 ⇒ 修门 + 回归测试；若干净 ⇒ 记录结论，依赖 1 的复发自证。
3. **台账自愈**（Q3）：cursor 归零清 `pull-stall-state`。
   验收：rejoin/重装后计数为零、被跳过键随全量重走重投。
4. **可见性**（Q5/Q6）：聚合行 + 明细入口；主状态不降级。
   验收：注入 receipt 的测试断言 UI 行为；真机目视。

## 依赖与顺序

1 → 2（判决先行，重放才能带预言机）；3、4 独立可并行；全部在 0.4.6 重出之后开工不阻塞发布。
