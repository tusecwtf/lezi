# 01 — 未加入家庭的清除文案

**What to build:** Settings 的两级「清除本机记录」确认文案必须由权威家庭会话状态决定。已加入家庭时继续说明服务器历史保留、下次同步可能重新下载；未加入家庭时只说明本机记录与保留项，不出现家庭服务器或同步恐吓。

**Blocked by:** None — completed

**Status:** completed

**Size:** S
**Review finding:** Spec 1
**Seam:** 现有 `SyncPort.session()` interface + Settings 内纯文案策略

## Initial file surface

- `feature/settings/build.gradle.kts`
- `feature/settings/.../SettingsScreen.kt`
- 新增 `feature/settings/.../ClearRecordsCopyPolicy.kt`（或同职责文件）
- `feature/settings/src/test/...`

不得修改 Family、Onboarding、Rust server 或当前家庭身份 tracker 文件。

## Acceptance criteria

- [x] `SettingsViewModel` 通过现有 `SyncPort.session()` 获得 joined 状态；不得新建只转发布尔值的浅 port。
- [x] 纯策略输入 `isFamilyJoined`，输出两级确认正文；Compose 不内联分支长文案。
- [x] 已加入：明确「只清本机」「家庭服务器仍保留」「下次家庭同步可能重新下载」。
- [x] 未加入：明确只影响本机且宝宝档案保留；正文不含「家庭服务器」「家庭同步」「重新下载」。
- [x] 会话状态变化后再次打开弹窗使用最新文案，不缓存陈旧 joined 状态。
- [x] 清除成功/失败语义与现有 `LocalRecordsClearCommittedException` 映射不变。
- [x] 纯策略测试覆盖 joined/unjoined 两级正文及禁词断言。

## Validation

- `./gradlew :feature:settings:testDebugUnitTest --no-daemon`
- `git diff --check`

## Documentation Gate

- 更新 `docs/prd/ui.md` 或对应清除文案真源，写清 joined/unjoined 两套文案；若现有 PRD 已逐字覆盖，票内记录 N/A 与引用位置。

## Out of scope

- 改动清除事务、同步屏障或服务器数据。
- 把家庭网络运维入口迁入 Settings。

## Comments

- 来源：固定范围审查 Spec finding 1；原 Ticket 01 acceptance 被错误标记为完成。
- 2026-07-27：`SettingsUi.isFamilyJoined` 直接随 `SyncPort.session()` 收集结果更新；弹窗正文每次组合时按当前状态求值。既有失败文案函数和清除调用未改动。
- 2026-07-27：先以缺少 `clearRecordsConfirmationCopy` 的编译失败确认红灯，再运行 `./gradlew :feature:settings:testDebugUnitTest --rerun-tasks --no-daemon`，105 个 task 全执行，BUILD SUCCESSFUL；`git diff --check` 通过。
- Documentation Gate：`docs/prd/ui.md` 设置页权威条目已写明 joined/unjoined 两套语义。
