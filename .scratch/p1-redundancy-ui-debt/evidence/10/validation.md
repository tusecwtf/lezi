# Ticket 10 validation — journal 主色政策 + PRD 坞条款 + 死组件

Date: 2026-07-30 (Asia/Shanghai)
Validation base: `215f761d3040202808dc9ffb162461b5c79969a1`

## Live disposition

- 历史提交 `b91601c583ec98e7fec6f089399e8d8e49953c2e` 已完成生产行为主体：
  `resolveLeziColorScheme` 让 warm / journal 在有宝宝主题时共享主色，
  `resolveLeziExtendedColors.babyAccent` 跟随该主色，PRD / CONTEXT 已采用绝对坞序，
  `JournalSummaryStrip` 与 `AppContextRow` 也已删除。
- 当前剩余缺口是：PRD 没有明确排除「journal 珊瑚 CTA 例外」，设置页存在两段重复的
  惯用手/坞说明，且上述已落地政策缺少直接回归。
- 因此本票只补政策、去重设置文案和增加契约测试；不改 `Theme.kt` 算法，不恢复死组件。

## TDD seams

### RED

Command:

```text
./gradlew :designsystem:testDebugUnitTest --tests com.lezi.babylog.designsystem.JournalThemeAndDockPolicyContractTest --no-daemon
```

Result: failed at the source/document contract because `docs/prd/ui.md` did not contain the explicit
journal CTA policy. Compilation succeeded first, so this was a valid behavioral/document RED.

### GREEN and characterization

- Added the explicit PRD policy and removed the duplicate Settings explanation; the targeted command
  passed (`BUILD SUCCESSFUL in 10s`, 17 tasks, 1 executed).
- Added honest current-behavior characterization for light/dark theme resolution, AppHeader accent,
  shared primary CTA, dock wording, and retired API absence. The targeted command passed again
  (`BUILD SUCCESSFUL in 5s`, 17 tasks, 2 executed, 15 up-to-date).
- No RED was fabricated for behavior that was already correct on the live checkout.

## Contract evidence

- With the same baby theme color, warm and journal resolve to the same `primary` and `onPrimary` in
  light and dark modes; journal `babyAccent` equals that resolved primary.
- Journal without a baby theme keeps its template default, so coral remains a fallback rather than a
  CTA exception.
- PRD, CONTEXT and Settings all state absolute left-to-right quick-slot order with no handedness
  mirroring; Settings now contains one explanation rather than two.
- Production design-system sources contain neither `JournalSummaryStrip` nor `AppContextRow`, and the
  enabled primary button continues to consume `MaterialTheme.colorScheme.primary`.

## Final serial gate

Command:

```text
./gradlew :designsystem:testDebugUnitTest \
  :feature:log:testDebugUnitTest \
  :feature:settings:testDebugUnitTest \
  :designsystem:lintDebug \
  :feature:settings:lintDebug \
  :app:assembleDebug --no-daemon
```

Result:

```text
BUILD SUCCESSFUL in 7s
594 actionable tasks: 2 executed, 592 up-to-date
```

The gate was run serially after the concurrent Layout 08 / Program 03 work had landed, from the exact
validation base above. No device gate was required for this documentation/theme-policy closeout.

## Scope limits

- No app version change.
- No theme algorithm or visual redesign.
- No changes to Program 10 NextFeed, Program 04, Layout 09, or unrelated working-tree files.
