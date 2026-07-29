# Ticket 10 shared next-feed flow

Date: 2026-07-30 (Asia/Shanghai)

## RED to GREEN

- RED: `:core:model:test --tests '*NextFeedPlanFlowTest'` first failed compilation because the
  shared state, events, effects, suggestion policy and reducer did not exist.
- GREEN: the same target now covers no-plan completion, explicit future validation, configured
  default suggestion, overflow, duplicate submit, plan failure/retry, process restoration and
  RecordComposer/NursingTimer origin equivalence.
- Adapter targets passed:

  ```text
  ./gradlew :feature:log:testDebugUnitTest \
    --tests '*RecordComposerSavedStateTest' --tests '*QuickRecordDraftTest' \
    :feature:timer:testDebugUnitTest --tests '*TimerUiPolicyTest'
  ```

  The pending fact identity and suggested time survive consumption of the Composer draft. Both
  adapters retain pending identity through plan commit and clear it only after user acknowledgement,
  so process death can retry the stable CarePlan write rather than duplicate or lose it.

## Shared UI device evidence

`NextFeedPlanFlowDeviceTest` passed 3/3 on API 35 `emulator-5554`. It proves the common surface can:

1. finish a durable fact with no plan and no schedule call;
2. report a plan-only failure, retain the selected time, retry it and acknowledge success;
3. expose the shared date/clock editor and disable duplicate submission while a write is in flight.

Command:

```text
./gradlew :designsystem:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
com.lezi.babylog.designsystem.NextFeedPlanFlowDeviceTest
```

## Combined regression gate

The ticket-closing tree passed the CarePlan/domain and both adapter suites, plus compilation and
static checks:

```text
./gradlew :core:model:test :domain:testDebugUnitTest \
  :feature:log:testDebugUnitTest :feature:timer:testDebugUnitTest \
  :designsystem:lintDebug :feature:log:lintDebug :feature:timer:lintDebug \
  :app:assembleDebug :app:lintDebug
```

Result: `BUILD SUCCESSFUL`. The installed `0.2.6-debug` APK had SHA-256
`3b94f43b4046f4f6503c52eb4242dbfab2b877ec0a5eead42c4f8c19883986ec`.

## Real-entry API 35 smoke

On `emulator-5554` (API 35), using the installed production app routes rather than a test-only
host:

1. Opened the `配方奶` quick slot, confirmed a 120 ml fact at 06:56, observed the shared prompt
   with configured 09:56 suggestion, entered the shared date/clock editor, returned, and chose
   `不安排`. The UI reported `已记录配方奶；未安排下次喂养`; the 120 ml fact remained on the
   timeline.
2. Opened the `母乳` Composer, followed `打开左右计时器`, completed a reviewed 1-minute left-side
   fact, and observed the same shared prompt with a 09:59 suggestion. `确认安排` produced the
   common `已安排下次喂养` success surface and truthful notification-permission downgrade copy.
3. Force-stopped and cold-launched the app. The timeline still showed the 120 ml formula fact and
   1-minute nursing fact, while the pending-care section showed the 09:59 nursing CarePlan.

The shared Compose device test covers plan-only failure and same-time retry; the real-entry smoke
covered no-plan and successful-plan production adapters. No spoken TalkBack claim is made.
