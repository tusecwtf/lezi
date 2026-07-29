# 06 — 无障碍布局动作与真实空槽文案

**What to build:** 为布局指派、移动、清空、隐藏、恢复及排序提供 TalkBack/键盘可执行动作，并让日常和编辑态空槽的名称、状态与实际行为一致；所有输入方式共用同一 reducer 和原子快照 writer。

**Blocked by:**
- 04 — 分类标题拖动与可靠类别排序
- 05 — 有界、可读的本机已删除分区

**Status:** complete

## Acceptance criteria

- [x] TalkBack 用户无需精确拖动即可完成目录项目指派到槽、槽位前后移动/换位、清空槽、隐藏项目、恢复项目、类内排序和类别排序。
- [x] 键盘/方向键具有稳定焦点顺序和等价可执行动作；焦点不会落入跟手浮层、装饰图标或不可操作的陈旧节点。
- [x] 触摸、TalkBack 和键盘动作只发出同一套布局 intent，调用同一 reducer，并通过 Ticket 02 的 `DeviceLayoutSnapshot` writer 持久化；不得复制业务规则。
- [x] 日常空槽短按保持 no-op，视觉标签和 `contentDescription` 不再声称会“选择”或直达设置；同时提供可发现、可访问的进入布局编辑方式。
- [x] 编辑态空槽如实说明它是可指派目标；固定“更多”如实说明锁定且不可投放，不暴露误导动作。
- [x] 每个项目、分类标题、槽、“完成”和本机已删除分区都有名称、当前位置/状态、可用动作和成功/失败播报。
- [x] 所有触控目标至少 48dp；禁用、锁定、命中和写入失败不只依赖颜色表达。
- [x] 快速连续语义动作仍满足槽唯一、空槽保留、当前目标互斥和原子快照 latest-wins；失败后可重试且不播报虚假成功。
- [x] 语义树/纯动作测试覆盖全部操作、真实空槽文案、焦点顺序、锁定“更多”、成功/失败播报及与触摸 reducer 的等价性。

## Validation

- 运行布局无障碍动作、四槽展示、reducer、原子快照和 Compose 语义测试。
- 运行 `:feature:log:testDebugUnitTest`、相关 connected/Compose 测试、`:feature:log:lintDebug` 与 `:app:assembleDebug`。
- 完成 TalkBack 与硬件/模拟键盘 smoke：进入编辑、指派、移动、隐藏、恢复、清空、类别排序、完成退出。
- 运行 `git diff --check`，确认没有仅为测试暴露的第二套生产入口。

## Documentation Gate

更新布局编辑 spec、UI PRD 与无障碍说明：记录替代输入路径、日常空槽 no-op、编辑态空槽可指派，以及“更多”锁定语义；删除仍声称空槽短按打开选择器的文案。

## Evidence

- `LayoutEditCanvas` 的 TalkBack custom actions 与键盘 chord 共用一个 `layoutAlternativeInput` dispatch seam，只调用生产 `onIntent`；`LogRoute` 仍是唯一 reducer → `DeviceLayoutSnapshotWriter` 路径。
- 日常空槽显示“空槽”，触摸短按仍为 no-op；读屏“编辑常用布局”和 Enter 进入现有编辑入口。编辑空槽无虚假动作，固定“更多”保留可读锁定节点且无键盘焦点。
- 目录、分类、已绑槽和已删除项目均暴露位置/状态和等价动作；Tab/DPAD 焦点顺序在 API 35 锁定，普通方向键不发 intent，拖拽头像从无障碍树隐藏。
- writer 状态与当前完整快照双重校验后才用 polite live region 播报；旧快照成功静默、失败不报成功、retry 成功才报已保存。
- RED/GREEN、JVM、connected、lint、assemble 与真实设备能力边界见 [`evidence/06`](../evidence/06/validation.md)。
