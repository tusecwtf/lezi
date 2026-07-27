# 14 — 乐记日历 CarePlan 视图（legacy CalendarEvent 子范围 superseded）

**What to build:** 让乐记日历成为护理计划的 current-only 日期视图；新建时先选择具体记录项目。

**Blocked by:** 13 — 计划状态、日期分组与管理操作.

**Status:** done（CarePlan current path）；历史 `CalendarEvent` 子范围 cancelled

- [x] 所有 CarePlan 无论是否启用系统日历都出现在乐记日历
- [x] 日历“＋”先展示已开启具体项目，再进入统一“安排护理” Composer，不再新建自由标题事项
- [x] 点击 CarePlan 进入计划详情或履行路径，展示状态和本地/原计划时间
- [x] ~~历史 CalendarEvent 继续可见、可编辑并使用既有提醒，不自动迁移或家庭同步~~ — **superseded 历史 receipt**
- [x] ~~历史 CalendarEvent 只有在用户明确确认后才转换为 CarePlan；转换失败保持旧事项完整~~ — **superseded 历史 receipt**
- [x] ~~转换成功后不得留下两个同时提醒的活动事项~~ — **superseded 历史 receipt**
- [x] ~~旧事项兼容和显式转换具备领域及 UI policy 测试~~ — **superseded 历史 receipt**

Fresh-current Release 证据（不能由上述历史勾选替代）：

- [ ] 源码、Room current schema 与 APK 中均不存在 `calendar_events`、`CalendarEvent` entity/DAO/DI、旧提醒 receiver/调度和转换 UI/路由
- [ ] 最终候选在 emulator-5554 证明 CarePlan 日历查询、新建、打开/履行与本机提醒仍正常
