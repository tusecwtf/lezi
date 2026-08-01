# 06 — 本机清空同步停止并清除计时器

**What to build:** 把 nursing timer snapshot/session 纳入可恢复的本机清空 epoch；Room/身份清空提交后，幂等停止对应 FGS、移除通知并只清除被捕获的旧 timer JSON。

**Source:** `AUDIT-20260801-P1-06`  
**Blocked by:** None — can start immediately  
**Status:** ready-for-agent  
**Size:** M

## Acceptance criteria

- [ ] `LocalClearSettingsSnapshot` 捕获 timer JSON 与稳定 session token；CareRecords 与 AllLocalData 两种会留下悬空 baby/plan 的 scope 都执行 timer cleanup。
- [ ] Room clear 未提交或失败时不停止/删除可继续使用的计时会话；提交后 stop+clear 即使进程中断也可恢复。
- [ ] stop 只针对被捕获的旧 session；clear 提交后新开始的 timer 不因旧 cleanup ABA 被停止或删 JSON。
- [ ] FGS 停止后 ongoing notification 消失，`NursingTimerServiceRuntime` 不再声称旧 session active。
- [ ] DataStore 清除使用 compare-and-remove；持久化失败时 UI/恢复路径报告 committed cleanup 待重试，不假装全部完成。
- [ ] 设备撤销、成员删除、家庭删除与设置页本机清空全部复用同一 coordinator，没有旁路清 Room 后留 timer。
- [ ] 单测覆盖 active/paused/failed timer、stop 失败、进程恢复与新旧 session 竞争；设备测试覆盖运行中撤销/清空。

## Validation

运行 domain/datastore/timer/sync 测试、`:app:assembleDebug`、`lintDebug`，并在 API 35 设备验证 FGS 与通知真实消失。

## Documentation Gate

更新本机清空与设备撤销表，明确计时状态属于清理范围且使用 session epoch 保护。

## Out of scope

不远程停止其它设备计时器，不引入后台服务协调。
