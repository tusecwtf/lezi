# Template v2 validation

Validated on 2026-07-23 against the live PRD, the first Open Design prototype,
and `docs/prd/visual-refs/`.

## Equivalence contract

- `warm` remains the default; `journal` is selected under 菜单 → 显示 → 界面模板.
- The selection is persisted in `SettingsLocal.visualStyle` / DataStore.
- Both templates share the fixed one-hand quick dock; the persisted left/right
  preference mirrors the action order so the pee shortcut stays thumb-near.
- Both templates use the same navigation graph, ViewModels, domain services, Room
  records, timer state, summaries, growth measurements and family/account stubs.
- Journal-only branches are limited to theme tokens, component geometry,
  information density and chart marks in the record, summary and growth screens.
- The reference screenshots informed hierarchy, density and chart grammar only;
  no reference-product names, marks, illustrations or icon assets ship in Kotlin.

## Automated evidence

```text
cd design/template-v2
node --check app.js
node scripts/static-check.mjs
  PASS: 5 views, 30 record types, 10 states, dedicated fields, offline-only assets

node tools/verify-template-v2-runtime.mjs
  PASS: 5 nav, 5 summaries, 30 record types, 10 states
  Exercised: navigation, week grid, growth metrics, dark persistence,
             one-tap pee, formula fields and nursing timer session.

bash tools/verify-template-v2.sh
  PASS: persisted switch and handedness, fixed quick dock/save action,
        distinct visual grammar, brand guard and OD snapshot.

./gradlew :feature:log:testDebugUnitTest \
  --tests com.lezi.babylog.feature.log.OneHandQuickActionOrderTest
  PASS: left/right mirroring and timer-disabled action set (3 tests).

./gradlew test
  BUILD SUCCESSFUL

./gradlew :app:compileDebugKotlin
./gradlew :app:installDebug
  BUILD SUCCESSFUL; installed on the API 35 emulator.
```

## Visual evidence

- The Open Design artifact was rendered at exactly 390×844 in headless Chrome.
- The Android build was inspected on the emulator in both `warm` and `journal`.
- Warm/right-hand placed pee at the right thumb edge; journal/left-hand mirrored
  it to the left edge. Both retained the fixed dock above system navigation.
- Tapping pee once increased today's pee count without opening an edit form.
- The overflow sheet remained scrollable in a four-column grid, and the
  type-specific edit form kept its full-width save action fixed at the bottom.
- Journal record view showed the coral header, five-cell summary, vertical 0–24h
  rail and compact log; summary and growth views showed the week matrix and
  weight/height/head percentile grammar.
- The emulator selector wrote `visual_style=journal` and `preferred_hand=left`;
  after force-stopping and restarting the process, DataStore still reported both
  values and the record screen restored the compact, left-handed template.

The Open Design project remains `lezi-piyolog-template-v2`; this directory is its
checked-in source snapshot plus repository-side validation evidence.
