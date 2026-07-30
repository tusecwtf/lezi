# Ticket 13 validation — full layout editor regression

## Fixed point and artifact

- Validated production HEAD: `84fa96cc4fd5065344fecdfb3f1de0908a558713`.
- Variant: `debug`; package `com.lezi.babylog.debug`, version `0.2.6-debug` (`versionCode=5`).
- APK: `app/build/outputs/apk/debug/app-debug.apk`, 28,252,986 bytes.
- SHA-256: `0d6458361065f25b62adb337eb14dd7dd465ef3c29a8062e356d69f794f1dd86`.
- Main device: `lezi_api35(AVD) - 15`, Android/API 35, 1080 x 2400.

## Automated fixed-point gates

- `./gradlew :feature:log:connectedDebugAndroidTest` — PASS, 42/42 tests, failures/errors/skipped all 0, 201 tasks, 1m07s.
  - Includes the drop matrix, category/item ordering, exclusive targets, long-list edge auto-scroll, one-level undo, bounded motion/haptics, configuration recreation, first-use guidance, local-deleted behavior, snapshot persistence, and all 8 `LayoutAccessibilityDeviceTest` cases.
- `./gradlew :core:model:test :core:ui:testDebugUnitTest :core:datastore:testDebugUnitTest :domain:testDebugUnitTest :feature:log:testDebugUnitTest :feature:settings:testDebugUnitTest :feature:log:lintDebug :app:assembleDebug` — PASS at the fixed point, 609 tasks.
- `./gradlew :app:assembleDebugAndroidTest` — PASS, 421 tasks; a disposable one-test seeder created only an emulator-local encrypted token so the official TalkBack image could reach the production record page. Its source was removed before evidence staging.
- `rg` over `sync/` and `tools/lezi-sync/` found no `DeviceLayoutSnapshot`, slot/order/hidden-item, or drag-guidance layout field in the family wire/server implementation.
- `git diff --check` — PASS.

## Production-route device regression

- `adb install -r` succeeded. Cold launch opened the ordinary record page with `3 条记录`, four independently configured slots, locked More, and no editor chrome.
- Long-pressing both a bound Dock item and an actual More-catalog item entered the same full-screen editor. The date chrome and main tabs were absent; Done and system Back returned to the same date context.
- The connected suites execute the complete directory/slot/deleted/category drop matrix, every valid 0–4-slot shape, uniqueness/empty-slot preservation, edge-scroll start/stop, undo expiry/replacement, feedback throttling, configuration reconstruction, guidance completion, atomic snapshot ordering, and failure/retry behavior on the same API 35 target.
- Manual configuration reconstruction retained the editor; system Back followed by force-stop/relaunch returned to the ordinary page and did not reopen the editor.
- Before and after editing, Composer open/cancel, configuration change, Back, and force-stop/relaunch, the production page still reported `3 条记录`. Short-tapping a normal slot opened the prefilled `RecordComposer`; cancellation wrote no fact.
- Warm/light and journal/dark production editor screenshots were captured. At 720 x 1280 with `font_scale=1.5`, Help, Done, local-deleted copy, four slots, and locked More remained reachable without critical clipping. The emulator was restored to 1080 x 2400, font scale 1.0, system light, App warm, and App theme `跟随系统`.

## TalkBack and keyboard evidence

- The main AOSP AVD has no TalkBack package, so an isolated official API 35 Google Play AVD was created with Android Accessibility Suite TalkBack `15.0.0.639625893` and `hw.keyboard=yes`.
- `dumpsys accessibility` showed TalkBack bound with spoken/haptic/audible feedback and touch exploration enabled. With TalkBack active, hardware Tab moved the green accessibility focus through Help, the sortable Feeding category, and the Breastfeeding catalog item; the screenshot records the item focus.
- Platform labels exposed truthful editor, category, item, local-deleted, four-slot, and locked-More state. The 8 connected accessibility tests execute assign, move, hide, restore, clear, and sort through the shared semantic intent seam and verify keyboard chords through that same seam.
- This evidence verifies the real TalkBack service, focus routing, platform labels, and the automated action contract. The headless emulator had no monitored audio output, so it does not claim a human-listened Chinese speech session.
- The disposable AVD and both temporary state archives were deleted after the smoke; the existing two project AVDs and their data were not changed.

## Visual and crash receipts

- `warm-light.png` — warm/light full editor.
- `journal-dark.png` — journal/dark full editor.
- `small-font150-journal-dark.png` — 720 x 1280, font scale 1.5.
- `talkback-keyboard-focus.png` — official TalkBack green focus on a catalog item via hardware Tab.
- Main AVD crash buffer: empty after the production smoke.
- Official Google Play AVD had one unrelated GMS boot-time `IdentityCredentialApiService` failure; after boot stabilization and `logcat -c`, the final cold Lezi launch, normal-page check, and crash-buffer read were clean: application `FATAL=0` and global crash buffer empty.
