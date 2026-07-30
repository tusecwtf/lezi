# 13 — 完整手势、重进与设备回归

**What to build:** 用自动化和真实设备证据锁定最终布局编辑闭环：从日常记录页进入，完成所有槽、目录、类别和本机已删除操作，经完成/返回与进程重建后保持一致，并证明编辑不会误写护理事实。

**Blocked by:**
- [`P1/02` — 删除重复与旧布局表面](../../p1-redundancy-ui-debt/issues/02-layout-migration-residue-cleanup.md)
- 08 — 目录拖动边缘自动滚动
- 09 — 高风险布局操作一层撤销
- 10 — 克制的编辑动效与限频触觉反馈
- 11 — 配置变更保持布局编辑会话
- 12 — 首次拖放引导与可重访帮助

**Status:** complete

## Acceptance criteria

- [x] 可分别从已绑定 Dock 项目和“更多”目录项目进入同一个全屏编辑器；进入后日期 chrome/主 tabs 隐藏，退出后恢复原日期上下文。
- [x] 自动化与设备手势覆盖目录→空槽/已占槽、槽→槽、槽→自身、槽→更多、槽→Dock 间隙、槽→真正坞外、项目/槽→本机已删除、已删除→恢复。
- [x] 覆盖同类别精确项目排序、类别标题首/中/末排序和跨类别项目 no-op；每个高亮与最终结果一致。
- [x] 长目录中目录项目和类别标题可以通过上下边缘自动滚动跨屏排序；快捷槽/已删除来源不触发滚动，进入删除区或 Dock 后立即停止。
- [x] 覆盖 0–4 个有效槽、同一 key 唯一、空槽保留、全部项目本机已删除和恢复到所属类末尾。
- [x] 清空槽位和移入本机已删除各完成一次一层撤销；换位/排序不弹撤销，失败或后续意图不会让旧快照覆盖新布局。
- [x] 静止编辑页无持续晃动；拾取、新合法落点和成功放下的触觉限频正确，关闭系统动画/触觉后行为与语义不丢失。
- [x] 配置重建恢复编辑页、滚动位置和最后成功快照并取消半途拖动；force-stop/relaunch 只恢复布局，不自动重开编辑器。
- [x] 首次提示只自动出现一次，成功拖放或主动关闭后不再自动显示；顶栏帮助可以重访且 TalkBack 可读。
- [x] “完成”和系统返回都可靠提交最后快照；force-stop/relaunch 后四槽、隐藏集合、项目序和类别序完全一致，不出现混合状态。
- [x] 编辑态的短按、拖动、无障碍动作和返回不会新增 Record；退出后点击正常记录入口仍只打开 Composer，并在用户确认后才写事实。
- [x] TalkBack 与键盘完成指派、移动、隐藏、恢复、清空和排序闭环；日常空槽与锁定“更多”的播报真实。
- [x] warm / journal、浅色 / 深色、小屏和字体放大均完成页面 smoke；本机已删除不挤出 Dock，关键标题和按钮不裁切。
- [x] 设备本地布局不会出现在家庭同步 payload，也不会覆盖另一设备的布局；历史 Record 的查看/编辑不受入口隐藏影响。
- [x] 验收记录包含精确 commit、构建变体、APK 哈希、设备/API、测试命令、截图/录屏位置及 Logcat `FATAL=0`；未跑的设备项目不得勾选或将本票标记完成。

## Validation

- 运行布局 reducer、目录排序、边缘滚动、撤销、交互反馈、会话恢复、首次提示、快照、ViewModel、无障碍和 Compose 全部定向测试。
- 运行 `:core:ui:testDebugUnitTest`、`:core:datastore:testDebugUnitTest`、`:feature:log:testDebugUnitTest`、相关 connected/Compose 测试、`:feature:log:lintDebug` 与 `:app:assembleDebug`。
- 安装本次最终 Debug APK 到目标设备/模拟器，执行全部手势矩阵、边缘滚动、撤销、动效/触觉、配置重建、首次提示、完成/返回、force-stop/relaunch、warm/journal、明暗主题、小屏/字体放大、TalkBack/键盘 smoke。
- 运行 `git diff --check`，重新核对实际 HEAD 和工作区状态；将精确通过/失败/未运行项追加到本票 Comments。

## Documentation Gate

最终复核 glossary、UI PRD、技术持久化说明和布局设计与实际 APK 一致；记录当前视觉权威、拖放矩阵、边缘滚动、撤销、动效/触觉、会话连续性、首次帮助、原子快照、无障碍路径、设备本地边界和验收证据限制。

## Implementation evidence

- Production fixed point: `84fa96cc4fd5065344fecdfb3f1de0908a558713`.
- Exact automated/device receipts: [`../evidence/13/validation.md`](../evidence/13/validation.md).
- The family sync implementation contains no layout snapshot, slot, hidden-set, item-order, category-order, or guidance marker field.

## Comments

- Full feature connected suite passed 42/42 on API 35; the complete 609-task JVM/lint/app gate also passed.
- Production smoke covered both entry routes, return/relaunch, Composer-without-confirm, four visual/device conditions, and unchanged `3 条记录` before/after.
- Official TalkBack 15.0 and a hardware keyboard were validated on a disposable Google Play API 35 AVD. Green focus and truthful platform labels are recorded; action execution and chord behavior are locked by the 8 connected accessibility cases. No human-listened audio claim is made.
- Final clean-buffer Lezi launch reported application `FATAL=0`; the disposable TalkBack AVD and temporary state archives were removed.
