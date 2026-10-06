---
triage: partial — 01-04 done, 05 Tier A 剩余模块按 search 模式逐个迁移
title: UI 文案质量加固：硬伤修复、原因透传、模板句治理与资源化
tracker: .scratch（本仓库工单目录；AGENTS.md 指定 GitHub Issues 非跟踪器）
---

# UI 文案质量加固

## Problem Statement

2026-09-05 全量文案审查（三路探查 + 人工核验）发现用户可见文案呈双轨：`FailureCatalog.kt`
四段式失败目录（标题/发生了什么/可能原因/本机数据状态/动作按钮）是仓内最佳实践，但存在四类洼地：

1. **硬伤**：时间轴带备注的记录行显示占位字面量「备注1」；`CalendarScreen` 与
   `LogViewModel`（自定义项目增改删）共 4 处裸透传异常 message 绕过 `productUiError` 守门；
   `familyFailureKind` 把一切未识别异常兜底成 `InvalidInput`——NPE、磁盘满、设备被移除
   都显示成「填写的内容不对」，误导用户改表单。
2. **吞原因**：首次同步失败的 cause 在 `RealSyncPort`/`FamilySessionCoordinator` 被
   `catch (_: Throwable)` 整体丢弃；向导层 kind 命中后清空具体 fallback 文案；引擎状态失败
   靠中文 message 子串匹配分类，措辞一改即静默掉进错误兜底。
3. **模板句**：约 25 处「X失败，请重试」无原因、无数据状态、无差异化（最差为
   `OnboardingViewModel` 的三个字「创建失败」）；同句文案多处重复硬编码。
4. **黑话**：约 15 处开发者/规范口吻直接见家长（自动对齐、合法时间、上下界、本机投影、
   接回、审计、履行、权威事实、会话失效、时长冻结、APK 迁移链、永久兼容基线）。

## Solution

纯客户端改动，零 wire、零服务端（`tools/lezi-sync` 审查结论：`/join` 安装页质量合格，不改）。

- **批次 1**：修占位符与 4 处裸透传；新增 `FailureKind.UnexpectedError`（意外兜底）、
  `LocalSaveFailed`（本机保存失败）、`DeviceRemoved`（设备被移出家庭），分类器补 6 个缺失
  类型映射；探测 catch-all 区分意外与网络。
- **批次 2**：`InitialFamilyDataRecovery.RetryRequired` 携带 FailureKind；向导层保留具体
  原因文案；marker 匹配以类型化异常优先。
- **批次 3**：模板句统一为「动作没生效 + 数据状态 + 重试」句式；重复文案收敛为共享常量。
- **批次 4**：黑话按映射表替换为家长语言。
- **批次 5**：文案资源化两层方案——Tier A（Compose 直渲染层）迁各模块 `strings.xml` +
  `<plurals>`；Tier B（FailureCatalog/ShallowSyncStatus/VM fallback 等 JVM 测试断言原文的
  非 UI 层文案）收敛为模块级 Copy 常量对象。Tier B 不迁资源的理由：不引入 Robolectric 则
  纯 JVM 测试无法解析资源，文案回归测试（如 `FamilySyncErrorProductCopyTest`）是现有
  最强护栏，不为迁移牺牲。

## User Stories

1. 作为家庭成员，我在任何失败提示里都能看到发生了什么、本机数据是否安全、下一步该做什么，
   以便不恐慌、不误操作。
2. 作为家庭成员，本机保存失败（磁盘满等）不再显示成「填写的内容不对」，以便不被误导去改表单。
3. 作为家庭成员，设备被管理员移出家庭时看到专属解释与「联系管理员重新加入」引导。
4. 作为家庭成员，首次同步失败时知道是网络不通还是服务器在忙，以便决定稍后再试还是找管理员。
5. 作为家庭成员，时间轴带备注的记录行显示真实备注预览而非占位符。
6. 作为长辈用户，界面文案不含「审计/履行/投影/冻结」等内部术语。
7. 作为乐记维护者，异常分类不依赖中文 message 子串匹配，引擎措辞重构不改变 UI 分类结果。
8. 作为乐记维护者，文案断言测试继续覆盖所有用户可见失败文案（Tier B 边界记录于本 spec）。

## Non-goals

- 服务端 `tools/lezi-sync` 零改动。
- 不做多语言（values-zh 等）——资源化只为 lint 可查与未来 i18n 预留。
- 不引入 Robolectric；不为资源化牺牲 JVM 文案测试。
- 不做全仓无差别结构测试（AGENTS.md：行为测试是重构契约）。

## Land record

- 2026-09-05：票 01-04 及票 05 Tier B/Tier A 示范提交为 `c575cc4e`
  （fix(ui): make failure copy name causes and guide next steps，66 文件；
  staged 树在独立 worktree 全量 test+lint 验证）。
  与近邻对齐/热接续流纠缠的两处文案改动（LogTimelineList 对齐徽章改写、
  家庭名漂移 wire 测试期望）留在工作区随那些流提交。

## Issues

见 [`issues/`](./issues/)：01 硬伤、02 原因透传、03 模板句、04 黑话、05 资源化。
