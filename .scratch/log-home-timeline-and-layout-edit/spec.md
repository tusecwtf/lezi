---
triage: implemented
title: 记录页：一日时间条连续绝对时间轴 + 布局编辑退出闪退修复
tracker: .scratch
decisions: 2026-09-12 owner——Q1=粘性选中日规则（当前选中日占视窗 ≥ 1/4 保持，否则占比最大日）;Q2=今天脱离吸附后停在松手处，只有右边界到现在才吸附;Q3=午夜日界加极小月/日标签;Q4=「回到现在」复用时间条下方整宽按钮（选中日=今天时换文案）;Q5=保留选中日以外标记变浅;Q6=惯性自由跨日但摩擦调大（约 2–3 天/次），跟手无上限;Q7=布局编辑闪退一并修复，先接机拉 crash 缓冲对照
---

# 记录页：一日时间条连续绝对时间轴 + 布局编辑退出闪退

## Problem Statement

两组用户反馈，都在记录页：

**A. 一日时间条**：横拖后「很容易换天」，每次换天后轨道大幅回弹，体验断裂；不想要「前后三天」
这类硬编码限制。逐行核对后三个独立原因叠加：

| # | 现象 | 根因 |
|---|---|---|
| R1 | 换日后整日横扫再弹回 | 渲染坐标是相对选中日 D 的三日工作轴分钟数。换日时 `ThreeDayTimelineAxis(selectedDay)` 原点移动一天，`viewportStartMinutes` 跳 ±1440；`rememberSettledViewportStartMinutes`（`designsystem/.../TimelineRailScroll.kt:112-137`）在分钟空间 spring 到新值 → 视觉横扫一天。`state.sleepLanes` 等经 Repository 异步重算，几帧内仍是旧轴坐标，进一步加重。 |
| R2 | 今天页松手就弹回「现在」 | `TimelineInteraction.endDrag`：LiveAttached 起点、右边界未越过今天 00:00 → `reattachLive`（`feature/log/.../TimelineInteraction.kt:283-292`）。今天上午无法停留查看。 |
| R3 | 硬限制截断惯性 | `changeDrag` 的 `coerceIn(-1.0, 1.0)`、scroll session 的 `accumulated.coerceIn(-width, width)`、`endDrag` 的 `origin±1` 夹紧、`TimelineWindowRequest.railStart/EndMillis` 只加载三天。 |

**B. 布局编辑**：长按坞进入编辑后，「完成」或系统返回都直接回到手机桌面；试用中还有其它闪退。
根因是**退出时崩溃**而非导航：提交 `a9b8f4f2`（2026-08-04）把编辑分支包进
`AnimatedContent(targetState = inLayoutEdit)`，退出过渡期间旧内容仍以 `editing = true` 重组，而外层
`editingPrefs` 已为 null：

```
feature/log/src/main/kotlin/com/lezi/babylog/feature/log/LogScreen.kt:307-308
    if (editing) {
        val prefs = checkNotNull(editingPrefs)
```

`IllegalStateException` → 进程崩溃 → 桌面。「完成」与系统返回共用 `requestLayoutExit()`，表现一致；
系统动画关闭（`layoutEditMs == 0`）时不复现。L382/L388/L390 对 `editingSession` 的三处读取依赖同一
smart-cast，必须一起改。其它静态审出的崩溃风险（按可能性）：

| # | 位置 | 触发 | 修法 |
|---|---|---|---|
| B1 | `layout/LayoutEditMode.kt:451` `LaunchedEffect(Unit) { doneFocusRequester.requestFocus() }` | 进入过渡首帧 focus 节点未 attach | `runCatching`（同 `QuickRecordSheet`） |
| B2 | `layout/DeviceLayoutSnapshotWriter.kt:147` `requireCurrentDeviceLayoutVersion` 在 `submit()`、writer try/catch 之外 | 版本不符从拖拽/撤销/重试路径抛到 UI | 返回 `Result.failure` + `Failed` 状态走既有「布局尚未保存 / 重试」对话框；`:152/:180` `check(trySend)` 同样 |
| B3 | `layout/LayoutEditCatalogSurface.kt:203/315` `checkNotNull(…FocusRequesters[key])`、`:553` `cell.key!!` | 当前 `keys` 与 `known` 同源不触发 | `?: FocusRequester()` / `?: return@forEach` |
| B4 | `layout/LayoutEditSession.kt:24-25`、`layout/LayoutEdgeAutoScrollPolicy.kt:21-22` `require` | UI 边界值 | `coerceAtLeast` |
| B5 | `draggableLayoutSource` 的 `pointerInput(dragKey)` 只 key 在 `dragKey` | 回调过期（非崩溃） | `rememberUpdatedState` |

未审出：`.first()/.single()/getValue/removeAt`、`LazyList` 重复 key、`rememberSaveable` 非法类型、
`runBlocking`、协程回调触达已销毁组合。当前无设备连接，执行第一步接机 `adb logcat -b crash -d`
（只读）对照。

## Solution

产品合同已改写：`docs/spec/contracts/ui.md` §3 / §5.2、`product.md` §4.1-6、`CONTEXT.md`
「选中日」「时间轴历史浏览」；架构决策 [ADR-0024](../../docs/adr/0024-timeline-absolute-rail-and-sticky-selected-day.md)。

- **绝对瞬时渲染**：designsystem 轨道只做 `(t − start) / duration × width`；不持有日历、选中日。
- **换日零位移**：提交选中日只更新 TopBar/列表/汇总锚点/变浅；唯一 settle 是回到实时吸附。
- **无天数上限**：跟手无限，惯性自由跨日（摩擦调大），只在现在边界夹紧；记录按视窗覆盖本地日
  ±1 缓冲加载。
- **粘性选中日**：右边界到现在 → 今天吸附；`share(当前选中日) ≥ 1/4` → 保持；否则占比最大日
  （并列取较晚，≤ 日历今天）。实时吸附态恒为今天。
- **今天可停留**：实时吸附向过去拖后松手 → 今天的历史浏览；「返回今天 / 回到现在」按钮恢复吸附。
- **日界日期标签**：午夜线顶端极小「月/日」，选中日以外变浅；现在线仍无字。
- **布局编辑**：`AnimatedContent` 从 lambda 参数取 session；B1–B5 改失败关闭。

## User Stories

1. 作为早上 8 点想看今天凌晨喂奶的家长，我左拖 2 小时松手后画面停在那里，TopBar 仍是今天。
2. 作为想回看昨天整晚的家长，我把视窗拖到大半在昨天时 TopBar 与列表切到昨天，但轨道一动不动。
3. 作为想翻到三天前的家长，我用力甩两下就能到，中途不会被「一次只能一天」截断。
4. 作为翻完历史的家长，我点「回到现在」或把右边界拖到现在，时间条回到最近 24 小时并继续跟随。
5. 作为在多日之间浏览的家长，我能从日界线上的小日期看出正在看哪天，选中日以外的记录更浅。
6. 作为夏令时地区用户，71h/73h 的日子占比、日界、刻度都按真实时长，不会错位。
7. 作为整理常用坞的家长，我点「完成」或按返回后回到记录页，而不是被扔回桌面。
8. 作为在编辑态里拖拽、撤销、重试保存的家长，任何失败都以「布局尚未保存 / 重试」呈现，应用不闪退。

## Implementation Decisions

- **视窗时长**：进入时决定（今天 24h 经过时长；历史日为该自然日真实时长），一次浏览中不变，
  不做缩放。TopBar/月历跳日仍重置为该日默认视窗。
- **粘性阈值**：单一常量 `STICKY_DAY_SHARE = 0.25`，按视窗时长计算；并列取较晚一日。
- **加载键**：`TimelineWindowRequest` 的 rail 范围改为显式字段 = 视窗覆盖的本地日集合 ±1 日
  （`distinctUntilChanged`），纳入 `loadKey`；列表/汇总/计划仍按 `selectedDay`。惯性途中可视日
  集合变化才重查；短暂空白可接受，不引入第二套缓存。
- **消费 px**：`onHorizontalPan` 返回状态机实际应用的像素，现在边界夹紧时返回 0，让系统 fling
  自然停止、overscroll 生效。
- **惯性**：自定义 `FlingBehavior`（`exponentialDecay`）；落地常量
  `TIMELINE_RAIL_FLING_FRICTION_MULTIPLIER = 2.5f`（Compose 默认 1f），真机未接，07 仍可按
  「用力一甩约 2–3 天」再调；不设硬行程上限。
- **settle 动画**：只对「绘制视窗 − 目标视窗」的差值（ms → Float）做 spring；目标是绝对值，换日
  不改变目标。
- **邻日变浅**：以选中日自然日范围为准，其余一律变浅（含日界日期标签）。
- **designsystem 分钟 → 毫秒**：调用面约 10 个文件（`LogTimelineList`、`ComponentPreviews`、
  device/JVM tests），不构成千级 blast radius，在一张票内一次切换，不做 expand–contract。
- **布局编辑版本不符**：`submit()` 不再抛出，进入 `Failed` 复用既有重试对话框；不新增文案。
- **不做**：拖动中实时换日；缩放；把工作轴保留为可选路径；TalkBack/键盘自定义换日动作；lezi-sync
  改动（纯客户端，不涉及 NAS CD）。

## Acceptance（总）

- 布局编辑：完成 / 返回 / 退出重试均回到 `UiTags.LOG_HOME`，`adb logcat -b crash` 为空。
- 换日提交前后 `drawnViewportStart` 相同（device test）。
- 今天 08:00 实时 → 左拖 2h 松手：Browsing、selectedDay=今天、视窗不变。
- 今天 08:00 实时 → 拖到右边界早于 06:00 松手：selectedDay=昨天，视窗不变。
- 历史自然日 D → 多日 fling 落在 D−3 大半：selectedDay=D−3。
- 右边界到现在 → LiveAttached，视窗 `[now−24h, now]`。
- DST 71h/73h 日：占比、日界、刻度按真实时长。
- `./gradlew test`、`lintDebug` 绿；真机手感确认后定摩擦与阈值常量。
