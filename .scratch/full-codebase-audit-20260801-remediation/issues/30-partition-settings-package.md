# 30 — Settings 按 calendar/record 分包

**What to build:** 系统日历同步与记录设置分别拥有可定位子包，Settings 根保留页面入口；
记录设置继续不承担记录页布局编排。

**Source:** merged directory C6
**Blocked by:** 06、25 — 先闭合清理路径与低价值 settings 测试
**Status:** ready-for-agent
**Size:** M

## Acceptance criteria

- [ ] `calendar/` 接纳 Calendar、AndroidSystemCalendarPort、CarePlanReminder、Alarm 等日历/提醒职责。
- [ ] `record/` 接纳 RecordAndShortcutSettings、CustomItem、ClearRecords、Reorder 等记录配置职责。
- [ ] `SettingsScreen` 保持根入口；about/update 依实际调用形成 `about/` 或留根，不强造空层。
- [ ] test package/import 同步；diff 仅移动与必要可见性调整。
- [ ] 系统日历单向投影、记录设置范围和清除本地数据产品行为不变。

## Validation

运行 `:feature:settings:compileDebugKotlin`、`:feature:settings:testDebugUnitTest`、
相关 domain/sync tests、`:app:assembleDebug` 与 `lintDebug`。

## Documentation Gate

实际落地形状由 Ticket 33 汇总。

## Out of scope

不重开系统日历权限/披露规则，不把布局编辑搬回设置页。
