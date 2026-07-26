# 21 — 系统日历授权、目标选择与基本投影

**What to build:** 让用户在明确授权流程中选择一个 Android 可写日历，并把本机护理计划以默认第二级内容单向投影；集成失败绝不阻断计划。

**Blocked by:** 12 — 内建项目本地护理计划 tracer; 20 — 护理计划本机提醒与履行深链.

**Status:** done

- [x] 只有用户主动启用系统日历时才请求所需日历权限；被动读取本机计划不弹授权
- [x] 授权后只列出可写日历并要求用户明确选择，不静默采用默认账户
- [x] 目标日历、启用状态和本机事件映射仅存当前设备，不进入家庭同步
- [x] 单条计划“同步到系统日历”默认开启；未完成配置时进入设置流程，取消仍可保存计划
- [x] 基本副本标题使用默认第二级“宝宝昵称 · 记录类型”，事件指向稳定计划身份
- [x] 写入成功时由系统日历在开始时提醒并避免重复乐记通知；拒绝或写入失败时回退乐记提醒
- [x] 权限撤销或目标日历消失显示“未同步到系统日历”，不回滚 CarePlan
- [x] Fake adapter 与最小 provider 契约测试覆盖授权、选择、成功、拒绝、失败和单一提醒

Notes:
- Device-local prefs + UUID→event map live in SettingsStore (not family sync).
- Composer per-plan switch defaults to project-on via CareLog projection path; setup dialog is settings entry. Full per-plan Composer chrome can deepen in ticket 22.

Notes (residual fix 2026-07-26):
- Calendar UI surfaces「未同步到系统日历」via carePlanCalendarMetaLine + ViewModel unsynced set.
- Unsynced detects permission revoke, vanished writable target, missing map, and missing event.
- Composer ScheduleCare seam: projectToSystemCalendar default-on + optional setup entry; unconfigured cancel still saves.
- SystemCalendarProjectionContract unit tests lock writable access / begin reminder / stable UID (no photo bytes).
- FIX: MainActivity wires RecordComposerHost.onConfigureSystemCalendar → SystemCalendarSetupDialog; RootViewModel.setSystemCalendarId mirrors settings; composer observes live settings so「去配置系统日历」updates after pick without reopening sheet.
