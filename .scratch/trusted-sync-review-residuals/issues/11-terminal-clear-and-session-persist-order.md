# 11 — Terminal clear 屏障与 saveSession 持久化顺序

**What to build:** （1）设备移除 / 成员删除 / 家庭删除触发的本机收敛在同步屏障内
完成关键步骤，避免与并发 `requestSync` 交叉半清 Room。（2）`saveSession` 避免
「新 refresh 已写入 Keystore、DataStore 身份仍是旧家庭」的中间态（对齐 reauth clear
的 pending 标记模式）。

**Blocked by:** None.

**Status:** complete

**Severity:** Medium
**Blocks release:** preferred
**Review ID:** F-11

## Must

- [x] Terminal identity clear：凭证先不可用 + 全程 `syncMutex`（或等价屏障）覆盖域清除
      与 prefs 清除顺序，文档化可恢复 marker 语义。
- [x] `saveSession`：身份与 refresh 持久化顺序不会在崩溃后出现「新 token + 旧 familyId」
      的可推送会话；或崩溃后强制 reauth 且不误推。
- [x] 现有 terminal clear 中断/恢复测试仍绿；补充并发 sync 与 clear 的回归若可稳定构造。

## Evidence paths

- `sync/.../RealSyncPort.kt` `finishPendingTerminalIdentityClear`, handleRemote*
- `sync/.../SyncPreferences.kt` `saveSession`, clearDeviceCredentialsForReauth

## Comments

- `5f9aa3c` 只稳住了测试等待；生产竞态仍开。
