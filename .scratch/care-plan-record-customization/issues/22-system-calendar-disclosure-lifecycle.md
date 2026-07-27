# 22 — 系统日历三级披露与副本生命周期

**What to build:** 完成系统日历副本的隐私披露、深链和全生命周期维护，使每台设备始终只有一个提醒来源且照片永不泄露给日历账户。

**Blocked by:** 04 — 所有记录类型的通用三张照片; 14 — 乐记日历 CarePlan 视图; 18 — 计划三张照片与履行带入; 19 — 护理记录显式转为护理计划; 21 — 系统日历授权、目标选择与基本投影.

**Status:** done

- [x] 支持三级披露：仅“乐记 · 护理计划”、默认“宝宝昵称 · 记录类型”、以及标题加文字备注描述
- [x] 第三级有照片时只写“照片 N 张，打开乐记查看”和稳定深链，不写照片字节、公开 URL 或可外泄本机 URI
- [x] 修改披露级别只更新尚未发生的系统日历事项，不批量扩大历史披露
- [x] 编辑计划更新乐记拥有的副本；完成、跳过、删除按规则移除或结束副本与提醒
- [x] Record 转 CarePlan 时按单条默认开关建立新副本，不残留原记录提醒
- [x] 系统日历副本成功时取消乐记重复提醒；任何后续失败都恢复一个乐记提醒来源
- [x] 系统日历中的外部编辑或删除不反向修改 CarePlan；乐记下次更新可重建权威副本
- [x] Adapter 契约和领域测试覆盖三级内容、照片隔离、未来更新、全生命周期与提醒切换

Notes:
- Pure policy: `SystemCalendarDisclosurePolicy` / `SystemCalendarDisclosureLevel` in domain (L1/L2/L3); photo paths never enter title/description.
- CareLog `projectOrScheduleCarePlanReminder` reads prefs disclosure, builds content, maps CUSTOM_APP_URI; external-delete rebuild via insert when update fails.
- `reprojectOpenFutureSystemCalendarCopies` only touches `listAllOpenFuture` after disclosure/target change.
- Setup dialog + PlanCalendar settings expose disclosure picker; default L2 on first enable only.
- Deep link `lezi://care-plan/{uuid}` in Manifest + MainActivity fulfill parse path.
- Tests: SystemCalendarProjectionPolicyTest, CareLog system-calendar lifecycle, SystemCalendarDisclosureUiTest.
