# iOS offline records: bounded editor expansion plan

> Status as of 2026-10-19: **current local schema-4/custom/manual-nursing batch
> implements 26 ordinary forms plus custom; 209 Linux tests pass, Apple acceptance pending. Sleep remains storage/projectors only, without UI**.
> This document refines the [iOS parity inventory](ios-parity.md), not the Android
> product contract. Native offline mode is not permission to bypass membership/ACL
> while an enrolled device cannot reach its server.

## 1. Implemented checkpoint versus proposed work

The earlier **dual-green** checkpoint **`f7ca7571`** covered 13 forms:
Android **5,908 tests**, Apple **151 package tests plus four real Simulator UI
tests**. Its bounded UI supports `pee`, `poop`, `both_diaper`, `diary`, `bath`,
`walk`, `cough`, `rash`, `vomit`, `injury`, `medicine`, `hospital`, and `vaccine`.
These use genuine create, revision-checked edit, explicit soft-delete, dirty-exit
confirmation and restart persistence. The health UI scroll failure was resolved
by a test gesture starting in measured content padding rather than a TextField;
the application layout was not altered to hide it.

The earlier **dual-green** growth checkpoint **`3f8e449`** adds `height`, `weight`, `head`, `chest`, and `foot_size`,
for **18 verified bounded UI types**. [Android run 39318117835](https://github.com/tusecwtf/lezi/actions/runs/39318117835)
passed **5,908 tests**; [Apple run 39318117841](https://github.com/tusecwtf/lezi/actions/runs/39318117841)
passed **159 package tests plus five Simulator UI tests**. UI execution totaled
863.188 seconds, including 147.704 seconds for growth; artifact `12218688971`
contains the acceptance results. These results cover this published tree only. Growth
preserves unchanged original payload precision/unit rather than Android's lossy
two-decimal editor refill. Changed values use shared Core range/conversion rules;
weight kg converts to integer g, with rounded zero refused. This is an explicit
native interaction difference, not exact Android editor parity.

The historical complete **dual-green** checkpoint is **`10016b6a`**, with **22 bounded
UI types** (including milk and temperature), native schema **3**, and dedicated
sleep storage. [Apple run 39329817431](https://github.com/tusecwtf/lezi/actions/runs/39329817431)
passed **199 package tests + seven Simulator UI tests**;
[Android run 39329817430](https://github.com/tusecwtf/lezi/actions/runs/39329817430)
passed **5,908 tests**. UI duration was 1,450.014 seconds (milk 249.255 seconds,
temperature 184.565 seconds); artifact `12225776079` contains the acceptance
evidence. Schema **1/2→3** migration preserves data atomically. Storage supports
26 ordinary no-media types plus dedicated sleep commands. Fresh schema-3 host
launch is verified, but **sleep UI is not exposed or accepted**. Database schema
and payload document schema versions are separate concepts.

**Published Food head `aa2011c`:** 25 ordinary UI forms; Apple package testing
passed **212 tests**, but the parallel Simulator run hit its **40-minute timeout**
with only **three of eight scenarios completed**. Android passed **5,908 tests**.
This is not complete Apple acceptance; the old `10016b6a` green run does not cover it.

**Current draft batch:** native schema **4**, atomic schema-1/2/3
migration, concrete custom catalog and dedicated record create/edit/soft-delete,
and manual nursing bring the source to **26 ordinary forms plus custom**.
The complete Linux regression passed **209 tests, zero failures** (46 storage,
77 portable presentation, 61 domain, 25 core). Apple-only orchestration and UI
checks are not part of that count. This batch is **not Apple-accepted**. Full serial execution of ten Simulator scenarios is the next
acceptance gate; changing the execution budget is not a passing result.

Custom definitions use stable UUID identity and separate local ordering; historical
title/icon snapshots survive definition changes. Custom writes use their dedicated
identity/context/revision gates and do not create Android numeric IDs or family
membership. Manual nursing captures left/right minutes, order and optional ml as
point facts, preserving supported historical record mode on edits. It does not
implement active timer sessions or interval inference. Sleep remains dedicated
storage/projectors only: **no sleep UI is exposed or accepted**. Photos remain
unsupported. Native schema 4 is not server schema 13 or payload schema 2.

Food keeps untouched optional legacy amounts exactly (nil, empty, Chinese,
full-width and whitespace), normalizes only new/changed amounts, and requires a
parseable amount only for new/changed baby-food facts. Guidance uses the original
baby's birthday and the record's local date, with Java-compatible completed
calendar months; suggestion buttons change only content.

Milk recent amounts are read for all three types in one SQLite snapshot using
bounded keyset batches across history, not just the visible UI page. Following
Android's query, a decoded milk payload contributes when its amount is 1–999;
this query does not apply full optional-field payload validation. Actual editing
and saving still enforce their full validation/structure gates. Values are
baby/type-bound, distinct and limited to three; opaque/tombstoned rows are skipped.
The editor captures this real snapshot without following later baby switches.
Step 5/10/15 (fallback 5) is a global local AppStorage preference, not per-baby.
Android's separate day-list `lastAmountMl` launch override is absent from this
host; genuine recent defaults are not presented as that missing intent.

Ordinary APIs remain `confirmRecord`, `recordDetails`, `recordPage`, and
`deleteRecord`, with pee compatibility wrappers. Generic ordinary confirmation
still rejects sleep and custom: dedicated commands preserve their independent
identity and evidence contracts.
The eight explicit sleep writes are:

1. `createSleepStart`
2. `recordCompletedSleep`
3. `recordWake`
4. `editSleepStart`
5. `editCompletedSleep`
6. `updateWake`
7. `withdrawWake`
8. `selectEffectiveWake`

`sleepPage` batch-projects each bounded page's sleep roots and wake evidence in
one read snapshot without per-sleep queries; `sleepDetails`, `openSleep`, and
`wakeDetails` expose dedicated reads. Wake identity uses the source-derived UUIDv5
helper. Local observer identity comes from durable local identity, not a fabricated
family member/session. This does not establish family-aware authorization or sync.

Explicit native safety differences: completed-sleep creation commits the sleep
and wake roots atomically (stronger than the source flow); editing a projected
closed sleep cannot accidentally reopen it; replay requires matching content
rather than silently accepting a changed request; wake notes remain independent,
and editing a sleep note does not silently rewrite the wake note. Only explicit
wake operations modify that evidence. Unknown documents remain preserved/readable
and cannot be silently rewritten by a supported form.

The unsigned development host uses a persistent sandbox store without fake
history or silent in-memory fallback. None of these checkpoints establishes full
product, signing, physical-device, family/sync, media, or complete accessibility
acceptance. Library capability does not expose all its forms automatically.

Text semantics use the shared Kotlin-compatible `AndroidText` helper and UTF-16
counts. The implemented input adaptation retains overlong input and refuses a
changed/new overlong field rather than Android's `take` truncation. Unchanged
legacy diary/health text can remain over its input cap because Android loads it
without truncation and has no corresponding confirmation length gate; required,
severity, note and future-time validation still applies. Notes over 200 remain
blocked even when unchanged. Body, note and health text fields are independent.
See the feature directory README and tests for each field's source anchors.

Core typed-structure validation refuses non-formula milk with prepared/duration
fields and invalid nursing order/mode before encoding. This native safety
restriction prevents unreadable round trips; it does not assert every Android
in-memory combination behaves identically. Legacy numeric-domain values remain
readable. See [the payload contract](contracts/data-model.md).

The current local manual nursing and custom workflows still need Apple acceptance.
Remaining UI work includes nursing timer handoff, care plans, media and dedicated
sleep UI. Full
navigation, family enrollment and synchronization remain unimplemented here.
The 12 groups below are a catalog/implementation plan, not an assertion that
all their UI or complete product parity has been delivered.

Historical evidence: the earlier pee-only `1e5802a6` checkpoint passed
[Android 39282387623](https://github.com/tusecwtf/lezi/actions/runs/39282387623)
and [Apple 39282387612](https://github.com/tusecwtf/lezi/actions/runs/39282387612)
with 93 package tests and one UI smoke. It is superseded by the latest verified
checkpoint above; it is not evidence for the Food or current draft batch.

## 2. Sources and authority

Paths below are relative to the repository root. Current Android source and
[CONTEXT.md](../../CONTEXT.md) remain the behavior/terminology anchors; a Swift
payload enum alone is not the entire editor contract.

- **A-draft**: `feature/log/src/main/kotlin/com/lezi/babylog/feature/log/composer/QuickRecordDraft.kt`
  (`quickRecordMode`, `create`, `fromRecord`, `validationResult`, `payloadDocument`,
  `foodAmountForWrite`, `workMode`, `toSaveCommand`).
- **A-fields**: the same directory's `QuickRecordPurposeFields.kt` (`PurposeFields`,
  nursing/milk/excretion/temperature/diary/symptom/medicine/hospital components).
- **A-text**: the same directory's `QuickRecordPurposeTextFields.kt` (measurement,
  food and vaccine components).
- **A-shell**: the same directory's `QuickRecordSheet.kt`,
  `QuickRecordSheetControls.kt`, `RecordComposerDiscardPolicy.kt`,
  `RecordComposer.kt`, `RecordComposerViewModel.kt` (time, note, focus, confirmation,
  all dismissal sources and explicit deletion).
- **A-copy**: `core/model/src/main/kotlin/com/lezi/babylog/core/model/RecordSummary.kt`
  and `core/ui/src/main/kotlin/com/lezi/babylog/core/ui/RecordPresentation.kt`
  (labels and summaries); `RecordType.kt` beside `RecordSummary.kt` (28 types).
- **I-domain**: `ios/Sources/LeziCore/Domain/RecordPayload.swift`,
  `RecordIdentityAndDraft.swift`, `RecordPayloadCodec.swift`, `FoodAmount.swift`.
- **I-confirm**: `ios/Sources/LeziUI/Confirmation/LeziConfirmationSession.swift`
  and `LeziConfirmationPanel.swift`.
- **I-pee**: `ios/Sources/LeziUI/Features/OfflinePee/` and
  `ios/Tests/LeziPresentationTests/OfflinePeeStateTests.swift`.
- **I-store**: `ios/Sources/LeziStorage/` and `ios/Tests/LeziStorageTests/`.

## 3. Planned field routing: 12 groups, 26 types

Every row shares fixed record/baby/type identity, captured event time, optional
note, explicit confirmation and transactional result handling. Reuse controls;
do not collapse distinct payload meanings into a dictionary of arbitrary fields.

| Group | Types (count) | Shared controls and required specialization | Source |
|---|---|---|---|
| Nursing | `nursing` (1) | Dedicated left/right minute input, order `L/R/LR/RL`, optional estimated ml. Use strict `NursingConfirmInput` text validation, not only payload validation. Preserve source `recordMode` on edit; new manual entry uses Android's default. Do not offer an unimplemented timer action. | A-fields, A-draft, I-domain |
| Milk | `formula`, `pumped_feed`, `pump_express` (3) | Shared 1–999 ml quantity controls. Only formula exposes optional prepared ml (0–999) and duration minutes (0–1440). Android step options 5/10/15, fallback 5; recent values must come from real data. Defaults are 120 ml for formula/pumped feed and 60 ml for express when no valid recent value exists. Keep type fixed. | A-fields, A-draft |
| Excretion | `pee`, `poop`, `both_diaper` (3) | Compose urine and stool controls: urine 1–3; stool amount/consistency 1–4; color 0–7. Both-diaper combines the two, not a type-changing toggle in an existing editor. Match labels, defaults and accessible choice meanings. | A-fields, A-copy, I-pee |
| Temperature | `temperature` (1) | Dedicated numeric value and ℃/℉ conversion; persist Celsius, range 34–43℃. Android rounds its display on switching; the verified native editor instead preserves the exact canonical value across unit-only changes. Its configured low-age fever hint uses birthday and the event's local date, not age today; do not invent medical guidance. | A-fields (`TemperatureFields`, `shouldShowInfantFeverAdvice`), A-draft |
| Diary | `diary` (1) | Required multiline body, Android input limit 800; body is separate from note. | A-fields, A-draft |
| Simple | `bath`, `walk` (2) | Common time/note only. Do not invent duration fields; Android writes walk without `endTimestamp`. | A-draft, A-shell |
| Symptoms | `cough`, `rash`, `vomit`, `injury` (4) | Shared severity 1/2/3 with labels 轻微/一般/明显 and optional description (input limit 200). Preserve exact type. | A-fields, A-draft |
| Medicine | `medicine` (1) | Required name (input limit 50), optional free-text dose (30). Do not turn arbitrary dose text into an inferred numeric unit. | A-fields, A-draft |
| Hospital | `hospital` (1) | Required reason (input limit 100), optional multiline advice (300). | A-fields, A-draft |
| Growth measurements | `height`, `weight`, `head`, `chest`, `foot_size` (5) | Shared numeric measurement. Weight displays kg and stores g using `GrowthMeasurementFacts`; others use cm. Reuse the existing range and rounding rules instead of another conversion implementation. | A-text, A-draft, I-domain |
| Food | `baby_food`, `snack`, `drink` (3) | Shared required content (input limit 80) and textual amount (30). Baby-food has additional amount validation and birthday/event-date guidance; drink has different examples. Preserve untouched historical amount exactly; see §4. | A-text, A-draft, I-domain |
| Vaccine | `vaccine` (1) | Required name (input limit 80), optional batch/dose-number text (50). | A-text, A-draft |

Medicine, hospital and vaccine can share labeled text primitives, but their field
identity, requiredness, lengths and payload keys remain separate. Route all 26
explicitly; no fallback may immediately save or silently use an empty payload.

## 4. Semantics that must survive generalization

1. **Raw input and validation.** Keep editable numeric text until explicit
   confirmation. A failed parse cannot silently become zero, a default, or nil.
   Use feature validation with a stable field identity for reason/focus, followed
   by domain validation and final transactional storage checks. Existing
   `RecordPayload.validationErrors` is necessary but not sufficient.
2. **Text lengths and whitespace.** Android input limits/`String.length` are
   Kotlin UTF-16 code-unit semantics, not Swift grapheme `String.count`. Audit
   emoji, combining text, truncation boundaries and whitespace rules with shared
   fixtures. Do not split a surrogate or silently erase existing unsupported
   text. Common note limit is 200; Android writes trimmed blank note as null.
   Reuse the actual normalization for each field, not one blanket policy.
3. **Food history.** `sourceFoodAmount` is distinct from editable text. Existing
   unchanged amounts retain null, empty, pre-normalized, or non-numeric legacy
   strings verbatim. New/touched baby-food fact amounts must be present and
   parseable after `FoodAmount.normalize`; e.g. 半碗 becomes 0.5碗. This rule is
   stricter than `RecordPayload`'s content-only validation. Do not normalize an
   untouched amount merely because another field was edited.
4. **Time and future plans.** Capture initial event time on opening, not on save.
   Android future create switches to care-plan intent; future edit conversion is
   explicit. This bounded editor supports current/past facts only: reject future
   entries with an honest unavailable-plan explanation until the real plan flow
   exists. Never store a future plan as a completed fact or claim full composer
   parity while this product capability is absent.
5. **Original identity and source preservation.** Capture record UUID, baby,
   concrete type, store generation and edit revision. Selection changes cannot
   retarget the draft. Preserve source values not represented by a control
   (notably nursing `recordMode`). Unknown documents or records with unsupported
   media/fields remain read-only when lossless editing is not implemented; never
   strip their content into a supported-looking replacement.
6. **Commit and task ownership.** Keep the existing confirmation session and
   per-attempt persistence claim. Exactly one writer can consume a confirmed
   attempt; success follows real COMMIT, not task launch. Late completions cannot
   mutate a replacement editor. Errors retain the current draft and meaningful
   retry state. Serialize save/delete and scope changes at their actual boundary.
7. **Edit/delete.** Edit captures expected revision; stale edits report conflict
   rather than overwriting. Soft-delete has its own explicit confirmation bound
   to the original record and revision. Do not hard-delete data, impersonate
   synchronization, or report deletion before the transaction finishes.
8. **Dirty exit.** Match `decideRecordComposerDismiss` for footer cancel, header
   close, system back and sheet dismissal: busy ignores exit, changed drafts ask
   to discard, unchanged/view-only drafts close. Cancel/discard never writes a
   care fact. The current SwiftUI adaptation blocks dirty/busy swipe dismissal;
   explicit close/cancel offers discard confirmation, and clean swipe closes.
   Native sheet lifecycle is exercised by the bounded UI tests, not a claim of
   every Android/system-back gesture being identical.
9. **Scope remains visible.** No fabricated family/session, sync status, media
   controls, timer navigation or recent history. A local dirty marker is not a
   family pending-publication status. The development host remains clearly
   labeled until the corresponding full-product workflows are implemented.

## 5. Smallest implementation slices and acceptance

The 22-type checkpoint `10016b6a` verifies the existing daily/health/growth/milk/
temperature forms and schema-3 sleep storage with 199 Apple package tests and
seven UI tests, without a sleep UI entry. Published Food `aa2011c` has 212 passing
Apple package tests but incomplete UI acceptance after timeout. The draft
schema-4/custom/manual-nursing batch has 26 ordinary forms plus custom and 209
passing Linux tests; full ten-scenario Apple acceptance is pending. Dedicated
sleep UI and nursing timers remain future slices; storage is not UI acceptance.
Do not replace the working pee flow with a generic form before its regression
proof remains green.

1. **Pee integration (verified):** adapt existing pee to the unified repository while
   preserving existing database rows/UUIDs, then add genuine edit, explicit
   soft-delete and dirty-exit confirmation. Prove opening/selecting/canceling
   writes no record and that create/edit/delete survive ordinary process restart.
2. **Excretion family (verified):** add poop and both-diaper with the same shell and composed
   controls. Prove defaults, every choice boundary, full payload round-trip and
   correct original-baby scope. Only these working entries become visible.
3. **Incremental remaining groups:** simple/text, symptoms/measurements, milk,
   then dedicated nursing/temperature/food behavior. Vaccine/medicine/hospital
   reuse text primitives while retaining their individual contracts. Each group
   needs real write/read/edit/delete and source-preservation evidence before its
   entry is exposed; this ordering is not permission to call missing features
   complete.

Required regression scenarios: A draft while selection changes to B; cancel zero
writes; stale edit CAS; save/delete contention; duplicated callback when the first
write rolls back; stale success/failure after editor replacement; unknown payload
read-only; unchanged legacy food amount; UTF-16/whitespace boundaries; conversion
round-trips; migration/reopen preservation and paged history without duplicate or
skipped stable rows. Apple tests must exercise actual ObservableObject task
orchestration and visible user paths, not just a portable reducer.

Record evidence per slice: commit, implemented types and operations, migration
version, Linux test count, Apple-only test count, Simulator UI coverage, CI run
links and remaining gaps. A green package run alone does not accept the full
[parity matrix](ios-parity.md).
