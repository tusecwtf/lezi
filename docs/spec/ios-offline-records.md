# iOS offline records: bounded editor expansion plan

> Status as of 2026-10-19: **pee UI implemented; broader storage implemented
> and locally verified, pending new-head Apple CI; generic editor planned**. This document refines the
> [iOS parity inventory](ios-parity.md), not the Android product contract. Native
> offline mode means a device that has not joined a family. It is not permission
> to bypass membership/ACL while an enrolled device cannot reach its server.

## 1. Implemented checkpoint versus proposed work

The published checkpoint `1e5802a6eeaec8521b89e812e33c9e328e66c736` has green
Android and Apple CI. Apple evidence comprises **93 package tests plus one
Simulator UI smoke test**, and the unsigned development host build. This proves
the bounded paths exercised there, not full iOS/Android parity, signing, physical
installation, family synchronization, or every visual/accessibility scenario.

Evidence verified 2026-10-19 16:25 UTC:

- [Android run 39282387623](https://github.com/tusecwtf/lezi/actions/runs/39282387623): completed/success.
- [Apple run 39282387612](https://github.com/tusecwtf/lezi/actions/runs/39282387612): completed/success;
  93 macOS unit tests and one real XCUITest (61.124 seconds). Artifact `12202077796`
  contains the result bundle and screenshots; screenshots were inspected.
- Head tree `5062478e241b159ee8bf091224c7c0c63d7c70ed` matched synthetic PR merge
  `73263baf`. The PR remains draft and unmerged. These run results apply to that
  tree only.

Implemented at that checkpoint:

- Real native SQLite schema 1: local identity, baby nickname/birthday, separate
  local baby preferences, and no-media pee records.
- SwiftUI baby creation/selection, immutable pee draft, explicit confirmation,
  real transaction completion before success, and bounded read-only pagination.
- Original-baby ownership survives selection changes. Matching attempt ownership
  prevents duplicate callback dispatch; failures retain the draft. Portable and
  Apple model tests cover these gates, including real store commits/rollback.
- An unsigned Simulator-only development host opens a persistent sandbox store.
  It does not seed sample history or substitute an in-memory store on failure.

Subsequent local implementation, **not part of that published checkpoint's
Apple acceptance**: native schema 1→2 migration preserving existing data and a
unified storage table supporting create, revision-checked edit and soft-delete
for all 26 ordinary no-media types below. Independent source review found no
blocking issue. The locked Linux full-package run passed **106/106 tests**,
including **28 storage tests** covering migration and each supported type's
lifecycle with actual payload edits. New-head Apple CI remains pending; the
older green runs above cannot accept these changes.

The unified APIs are `confirmRecord`, `recordDetails`, `recordPage` and
`deleteRecord`; the existing pee APIs remain compatibility wrappers. Opaque
records remain preserved/readable through the generic read path and cannot be
silently rewritten by a pee wrapper. Sleep/custom/media writes remain rejected.
The current UI still exposes only the existing baby/pee flow; these library
capabilities do not expose a 26-type editor. A native database schema number is
distinct from the schema-2 payload codec document format in LeziCore.

A subsequent local Core change adds shared typed-structure validation before
validation/encoding: non-formula milk cannot carry `preparedMl`/`durationMinutes`,
and invalid nursing order/mode cannot be encoded. Local validation reported
60 passing Core/Domain tests. No new-head CI acceptance is claimed here. This prevents a typed
payload from serializing into a document its own decoder cannot safely read;
it is an explicit native safety restriction, not a claim that every Android
in-memory combination behaves identically. Legacy numeric-domain values retain
their existing readable-preservation behavior. See
[the payload contract](contracts/data-model.md) and Android
`core/model/src/main/kotlin/com/lezi/babylog/core/model/RecordPayload.kt`.

Planned, **not currently implemented UI**: generic record selection, these 26
editors, general create/edit/delete flows, and Android-equivalent dirty-exit
handling. A storage API supporting a type does not expose its UI automatically.
Sleep and concrete custom items require separately coordinated workflows and
remain excluded. Media, nursing timer handoff, care plans, family enrollment,
sync, and full-product navigation are not implied by this work.

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
| Temperature | `temperature` (1) | Dedicated numeric value and ℃/℉ conversion; persist Celsius, range 34–43℃. Unit switching follows Android rounding. Its configured low-age fever hint uses birthday and the event's local date, not age today; do not invent medical guidance. | A-fields (`TemperatureFields`, `shouldShowInfantFeverAdvice`), A-draft |
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
   care fact. The existing pee component's simpler cancel is a limited checkpoint,
   not proof that Android's dirty-exit behavior is already implemented.
9. **Scope remains visible.** No fabricated family/session, sync status, media
   controls, timer navigation or recent history. A local dirty marker is not a
   family pending-publication status. The development host remains clearly
   labeled until the corresponding full-product workflows are implemented.

## 5. Smallest implementation slices and acceptance

All UI steps below are **planned**. Storage is locally verified as above; its
new-head Apple gate remains pending and does not substitute for UI acceptance.
Do not replace the working pee flow with a generic form before its regression
proof remains green.

1. **Pee integration first:** adapt existing pee to the unified repository while
   preserving existing database rows/UUIDs, then add genuine edit, explicit
   soft-delete and dirty-exit confirmation. Prove opening/selecting/canceling
   writes no record and that create/edit/delete survive ordinary process restart.
2. **Excretion family:** add poop and both-diaper with the same shell and composed
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
