# Ticket 11 dirty Composer discard

Date: 2026-07-30 (Asia/Shanghai)

## Unified dismiss authority

- `RecordComposerSavedState` persists both the initial prefilled draft and the active draft. Dirty
  comparison includes fields, time, photo membership/order and custom identity while excluding only
  internal draft-photo ownership bookkeeping.
- System Back, header Close, footer Cancel and sheet dismissal all enter
  `decideRecordComposerDismiss`. Saving/deleting ignores dismissal; a clean draft closes immediately;
  a dirty draft opens the same retained-sheet confirmation surface.
- Continuing removes only the confirmation overlay, so the Composer composition, current values,
  validation state and focus remain in place. Discard delegates cleanup to the existing
  reference-aware draft photo lifecycle; source, persisted and shared files remain protected.

## Automated regression

The ticket-closing working tree on base `348f25f39095020b7592240bfa010e09d95c023e` passed the focused
state and photo suites:

```text
./gradlew :feature:log:testDebugUnitTest \
  --tests '*RecordComposerDiscardPolicyTest' \
  --tests '*RecordComposerSavedStateTest' \
  --tests '*RecordComposerPhotoLifecycleTest'
```

Result: `BUILD SUCCESSFUL`, 105 tasks. The complete module/static/application gate also passed:

```text
./gradlew :feature:log:testDebugUnitTest :feature:log:lintDebug \
  :app:lintDebug :app:assembleDebug
```

Result: `BUILD SUCCESSFUL`, 770 tasks. `git diff --check` was clean. The generated Debug APK SHA-256
was `65c192e64cae8d5da37a88e9d8c2576f4ec9b6bf9920ce082b2e810fa0c90758`.

## API 35 device evidence

`RecordComposerDiscardDeviceTest` passed 5/5 on `lezi_api35`:

```text
./gradlew :feature:log:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
com.lezi.babylog.feature.log.RecordComposerDiscardDeviceTest
```

The device suite covers restoration of the pending prompt, busy-state protection, retained text and
focus after continuing, exact header/footer dismiss sources, and keeping the modal sheet visible
after continuing from its discard overlay.

Using the installed production app route on the same API 35 emulator:

1. Opened the Formula Composer at its 120 ml prefill, changed it to 125 ml and sent Android System
   Back. The shared `放弃未保存的更改？` prompt appeared.
2. Selected `继续编辑`; the visible Composer still contained `125 ml` and the `125` text field.
3. Selected the header `关闭`; the same prompt appeared. Selected `放弃` and returned to the record
   screen, which still showed `120ml` and `3 条记录`, proving the 125 ml draft was not persisted.

Raw System Back is asserted by this real-entry smoke because the library-host instrumentation target
does not reproduce the app Dialog dispatcher contract. No spoken TalkBack claim is made.
