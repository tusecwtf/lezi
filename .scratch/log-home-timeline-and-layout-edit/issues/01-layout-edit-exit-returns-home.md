# 01: 布局编辑「完成 / 系统返回」回到记录页，不再闪退到桌面

**What to build:** 长按坞进入布局编辑后，点「完成」或按系统返回，等最后一份快照耐久提交后回到日常
记录页（坞、时间条、列表可见），应用进程不结束。修法：`LogScreen.kt` 的 `AnimatedContent` 改为
`targetState = layoutPresentation`、`contentKey = { it != null }`，编辑分支只用 lambda 参数里的
`prefs/session`，删除 `checkNotNull(editingPrefs)`（L308）与 L382/L388/L390 对外层 `editingSession`
的读取；prefs 更新时 contentKey 不变 → 内容原地更新、无过渡。

**Blocked by:** None (can start immediately)

**Status:** implemented

- [ ] 执行前接机 `adb logcat -b crash -d`（只读）拉最近崩溃栈；`LogScreen` `IllegalStateException` 与根因对上，若有其它栈追加到 spec.md 审计表
- [x] feature/log androidTest：长按坞进入编辑 → 点 `layout_edit_done` → `UiTags.LOG_HOME` 日常坞可见、Activity 未 finish
- [x] 同一测试的系统返回变体
- [ ] 系统动画开启（`ANIMATOR_DURATION_SCALE` = 1）的真机上退出无崩溃
- [x] 独立提交 `fix(log): keep layout-edit content alive through exit transition`

## Comments

2026-09-13 — ticket 01 implemented in worktree `lezi-log-home-timeline` (not committed).

**Crash buffer:** `adb logcat -b crash -d` waited for a device (`- waiting for device -`) and produced no crash buffer. No device attached; no stack invented. Spec.md 审计表 unchanged.

**Code:** Extracted `LogHomeLayoutMode` (`feature/log/.../layout/LogHomeLayoutMode.kt`) so `LogRoute` and the androidTest share the same contract:
- `AnimatedContent(targetState = layoutPresentation, contentKey = { it != null })`
- edit branch reads `prefs`/`session` only from the lambda `presentation` (also `presentation.writeState`)
- deleted outer `editingPrefs` / `editingSession` and `checkNotNull(editingPrefs)`
- `BackHandler` lives on the host and calls `onRequestExit` (same path as 「完成」)
- prefs update keeps `contentKey == true` → in-place recomposition, no transition

**Tests:** `LayoutEditExitReturnsHomeDeviceTest` hosts `LogHomeLayoutMode` with a home stub tagged `UiTags.LOG_HOME` (LogRoute is Hilt-heavy). Variants:
1. Click `layout_edit_done`
2. `Espresso.pressBack()`
3. Prefs update stays on the edit branch (contentKey)

Exit variants freeze the Compose clock (`autoAdvance = false`) and advance through `LeziMotion.Emphasized` (300ms) so the outgoing edit content still recomposes after outer presentation is null — that is the old crash window. Mid-transition the host still shows retained slot text `pee` and `layout_edit_done`; after the transition: `LOG_HOME` displayed, `activity.isFinishing` / `isDestroyed` false.

**Ran:** `./gradlew :feature:log:compileDebugKotlin :feature:log:compileDebugUnitTestKotlin :feature:log:compileDebugAndroidTestKotlin` — BUILD SUCCESSFUL. Did **not** run `connectedAndroidTest` (no device) or full `./gradlew test`. Did **not** commit.
