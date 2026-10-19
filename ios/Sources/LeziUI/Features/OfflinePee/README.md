# Bounded offline record editor

The historical folder/type names are retained to keep the existing development
host and tests compatible. A single draft, confirmation attempt, and transactional
writer serves pee, poop, both_diaper, diary, bath, and walk. No media, sleep,
custom entry, nursing timer, future care plan, or family authority is implemented.

Android anchors: QuickRecordDraft.kt (fromRecord, validationResult,
payloadDocument, toSaveCommand), QuickRecordPurposeFields.kt (TextFields), and
QuickRecordSheet.kt (note input), under feature/log/.../composer.

Text input uses Kotlin-compatible trimming and UTF-16 counts. Android truncates
changed body/note input to 800/200 code units. This SwiftUI slice deliberately
retains overlong input intact and explains why confirmation is blocked, avoiding
surrogate/combining-sequence damage. This is an explicit interaction difference,
not exact UI parity. Existing diary bodies are loaded without truncation;
unchanged legacy bodies over 800 remain saveable, matching Android's absence of
a body-length validation gate after fromRecord. Other validation still applies.
Notes over 200 are blocked even when unchanged, matching Android's explicit gate.
Whitespace-only notes become nil; body and note remain independent fields.

New bath/walk facts are point events with empty typed payloads. Existing rows with
an end timestamp remain read-only instead of silently losing their interval.
Future timestamps are refused with a missing-care-plan explanation; they never
become completed facts. Unknown payloads are likewise preserved read-only.

Apple UI/model execution is a separate acceptance gate from portable state tests.

The same owner now also serves cough/rash/vomit/injury, medicine, hospital, and
vaccine. `OfflineHealthDraft` and `OfflineHealthFields` contain only this bounded
capability; these are records of entered facts, not medical advice.
Android source: QuickRecordPurposeFields.kt SymptomFields/MedicineFields/
HospitalFields; QuickRecordPurposeTextFields.kt VaccineFields;
QuickRecordDraft.kt defaults, payloadDocument and fromRecord.

- Symptoms: severity 1 轻微 / 2 一般 / 3 明显, default 2; description optional, 200 UTF-16 units.
- Medicine: name required 50; dose optional 30 (text, no invented unit conversion).
- Hospital: reason required 100; advice optional 300.
- Vaccine: name required 80; batch/shot text optional 50.

All health text starts empty. Required text is Kotlin-trimmed; optional trimmed
blank text becomes nil. Android's limits are input `take` operations, not
confirmation validation gates. As with diary, this UI preserves exact overlong
input and blocks changed/new overlong fields instead of truncating. Unchanged
legacy health fields remain editable without truncation; required/severity,
common note, future-time, and transactional checks still apply. Invalid stored
severity or unknown documents stay read-only rather than being reset to defaults.

Growth forms: height 身高, weight 体重, head 头围, chest 胸围, foot_size 足长.
`OfflineGrowthDraft` owns raw decimal input and an immutable original payload;
`OfflineGrowthFields` is the bounded field/summary component. Android anchors:
`core/model/.../GrowthMeasurementFacts.kt`, `QuickRecordPurposeTextFields.kt`
MeasurementFields, `QuickRecordSheetControls.kt` DecimalField, and
`QuickRecordDraft.kt` fromRecord/trimNumber/payloadDocument.

All five start empty. Weight displays kg and newly confirmed values store g via
Core's kg × 1000 nearest-integer rounding (positive half grams round upward).
Rounded zero grams are refused (0.0004 kg invalid; 0.0005 kg stores 1 g).
Other measurements display/store cm without extra decimal rounding. Values must
be finite and positive, at most 100 kg or 250 cm respectively. No default number,
invented units, or growth-reference/medical interpretation is supplied.

Explicit approved Android differences:
- Android filters decimal input to digits/dot and takes seven UTF-16 units. This
  UI retains invalid raw input and blocks confirmation, rather than turning
  pasted `-1`, commas or units into another number. Changed/new input permits
  ASCII digits and at most one dot, maximum seven characters. iOS uses a decimal
  keyboard; pasted invalid input remains visible with a validation explanation.
- Android fromRecord uses trimNumber, rounding non-integers to two decimal places;
  for example 12345 g becomes 12.35 kg in its editor. This UI displays a round-trip
  representation (12.345 kg), and unchanged values directly preserve the original
  payload, precision and unit. This avoids the source editor's potential loss;
  it is not exact interaction parity. Old long/scientific notation display text
  is preserved when unchanged; a changed input must meet the seven-character
  grammar and Core range/conversion rules. Changing the numeric input produces
  canonical g/cm. Known legacy weight kg is preserved until changed; unsupported
  units remain read-only, never silently reinterpreted.

The existing nonoptional confirmation API requires a typed draft before valid
input exists. Its unconfirmable growth snapshot uses an explicitly invalid
infinite value, not a zero/old-value fallback. Feature validation, domain
validation and encoding reject it; `OfflineGrowthDraft.payload` returns nil for
invalid input. Tests assert invalid input cannot launch a write or encode a fact.

Milk forms: formula 配方奶, pumped_feed 母乳瓶喂, pump_express 挤奶. The three
inputs live in one `OfflineMilkDraft` and one field component; switching a new
draft's type preserves each input separately. Existing record type remains fixed.
Formula alone supports optional prepared volume (0–999 ml) and duration (0–1440
minutes); blank is nil, explicit zero remains zero. Other milk types reject those
fields instead of silently dropping them. All amounts must be 1–999 ml.

Android source anchors: QuickRecordPurposeFields.kt MilkFields; QuickRecordDraft.kt
create/validationResult/payloadDocument/fromRecord; QuickRecordSheetControls.kt
IntegerField; domain/.../CareLogQueries.kt recentMilkAmounts; LogScreen.kt
openComposer; core/datastore/.../SettingsDataSource.kt AMOUNT_STEP.

- The global device-local step preference uses a dedicated AppStorage key,
  `lezi.offline.milkAmountStepMl`. Valid choices are 5/10/15 ml, invalid values fall
  back to 5. Each editor captures its initial step; changing it updates that
  preference without changing the amount, record type or care-fact dirty state.
  This explicit preference write can survive cancel; cancel never writes a fact.
- Refresh reads all three types' real recent amounts from one SQLite snapshot,
  bound to the selected baby. The query walks beyond the visible page, skips
  opaque/deleted records, and returns up to three distinct valid amounts for each
  exact type. New editors capture that data; switching the selected baby does not
  retarget their inputs or recents. Read failure is reported, not presented as
  empty history. A successful save refreshes the source values.
- Without a valid recent value the defaults are formula/pumped feed 120 ml and
  express 60 ml. Quick choices are recent values followed by current−step,
  current, current+step and current+2×step, clamped to 1–999 and de-duplicated.
  Invalid raw input contributes no fabricated neighboring values.
- Android's separate `lastAmountMl` override is not a persistent preference:
  LogScreen passes the first same-type amount in its selected baby's current-day
  record list. This development host has no day-screen launch intent and does
  not fabricate that override. It uses genuine recent values then the defaults;
  the missing upstream launch behavior is not claimed as full product parity.
- Android IntegerField filters digits/takes four UTF-16 units; its amount callback
  retains the previous amount when parsing fails. This UI deliberately retains
  the user's raw invalid text and refuses confirmation. It accepts BMP decimal
  digits as Kotlin's parser does, with four UTF-16 units maximum; no signs, units,
  decimal separators or whitespace around a number are silently removed. Optional
  Kotlin-blank input becomes nil. No timer, nursing editor, inventory or future
  care plan is implied by the duration field.

Before valid input exists the existing panel's typed snapshot uses invalid
amount −1, never a valid default/old amount. The optional payload accessor returns
nil and feature/domain checks prevent persistence. This representation is not a
new wire payload or a saved measurement.

### Temperature (local implementation; Apple validation pending)

The same confirmation/edit/delete owner now accepts `temperature`. The draft starts at
36.5 ℃; Celsius/Fahrenheit is a draft-only choice, reset to Celsius on reopening.
New or edited input must be a finite ASCII decimal of at most seven UTF-16 units,
with canonical Celsius in 34...43. Invalid text remains visible and cannot be
saved or switched into a different unit; it never becomes a default temperature.
The canonical payload remains Celsius.

Precision is an intentional native interaction difference: Android's
`QuickRecordDraft.fromRecord` formats two decimal places and
`QuickRecordPurposeFields.TemperatureFields` formats one decimal on unit changes
(and substitutes a default for invalid input). This component retains the exact
original Celsius payload for unchanged edits, and an exact Celsius anchor during
unit-only changes. Converted display text may therefore exceed seven characters;
unchanged generated/legacy text is accepted, while editing that text applies the
seven-character rule. No epsilon or new medical threshold is introduced.

`TemperatureRules` supplies the source-order conversion and source calendar rule.
The editor captures the original baby's birthday, uses the record timestamp's
local date in `TimeZone.current`, and checks birthday <= record date < birthday
plus three calendar months, with Celsius >= 38. It never uses today's age or the
currently selected different baby. The global local preference
`lezi.offline.infantFeverAdviceEnabled` defaults to true; changing this preference
writes no record and may survive cancelling an editor. The displayed source copy
is “低月龄发热请及时就医。本提示仅供参考，不构成医疗诊断。” This is a source-defined
informational prompt, not a new diagnostic rule.

Sources: Android `QuickRecordDraft.kt` (`TemperatureUnit`, defaults, `fromRecord`),
`QuickRecordPurposeFields.kt` (`TemperatureFields`, `shouldShowInfantFeverAdvice`),
`QuickRecordSheetControls.kt` (`DecimalField`), and `SettingsDataSource.kt`
(`infant_fever_advice`). Portable tests cover input/range/precision, original-baby
birthday ownership, record-date boundaries, opaque/interval protection and real
SQLite reopen/edit/delete. Two Apple-only tests cover the real model's original
baby after selection changes, CUD, duplicate dispatch, rollback and discard.

### Food (local implementation; Apple validation pending)

The shared owner now accepts `baby_food` (辅食), `snack` (点心), and `drink` (饮料).
Each type keeps its own raw content/amount in a new draft; switching types never
copies an unrelated value into another food kind. Stored types remain frozen.
Content is required, trimmed with Kotlin-compatible `AndroidText`, with an input
limit of 80 UTF-16 units. Amount is free-form text with a 30-unit input limit;
there is no unit picker or unit conversion. Both start empty. As with the other
native text fields, changed overlong input is retained and rejected instead of
Android's on-change truncation; unchanged legacy long fields remain editable.

New or changed baby-food amounts must be nonblank and parseable after
`FoodAmount.normalize`. Zero is accepted, as is the source upper bound of
1,000,000. `半碗` writes `0.5碗`; full-width supported digits/punctuation are folded;
ambiguous phrases such as `十几口` are rejected for new baby-food amounts. Snack
and drink permit empty or nonnumeric amounts. Content/amount errors focus their
own fields; note is independent and cannot substitute for required content.

Legacy amount preservation is exact: a separate optional source value retains
nil, empty string, whitespace, Chinese numerals, and full-width text. If the
loaded amount text is unchanged (including being changed then restored), writes
reuse that source optional value without normalization. Only a new or changed
amount is normalized, with blank converted to nil. Reads and summaries display
the original amount, and do not turn old `半碗` into a numeric statistics value.
`FoodAmount.parseValue` is available for source-compatible aggregation, but this
slice does not implement an aggregate statistics screen or claim that snack/drink
participate in Android's baby-food-only summary.

The baby-food guidance component uses `BabyFoodGuidance.at` with the original
baby's captured birthday, record timestamp and `TimeZone.current`. Its completed
calendar months follow Java `Period.between(...).toTotalMonths()`, not a fixed-day
age or the temperature helper's clamped anniversary boundary. Missing/invalid
birthday hides guidance; records before birth resolve to zero months, as in the
source. Stages, suggestions, expandable explanation and disclaimer copy come
from Android `BabyFoodGuidance.kt`; suggestion buttons modify only baby-food
content, retaining amount/note/ownership. Expanded state resets when stage changes.
These are source-defined product tips, not a claim of complete independent
medical/nutritional validation or new advice authored for the native app.

Android references: `QuickRecordPurposeTextFields.kt:100–221`,
`QuickRecordDraft.kt:488–506,690–716,855–857`, `BabyFoodGuidance.kt`,
`core/model/.../FoodAmount.kt`, `QuickRecordDraftFoodAmountTest.kt`, and
`SummaryAggregation.kt:325–340`. Android plan intents can omit baby-food amount;
this development host still rejects future facts and exposes no plan, fulfillment,
media, family or sync workflow.

Six portable tests cover all three forms, UTF-16/trim/numeric boundaries, exact
legacy optionals through SQLite reopen/edit/delete, type/suggestion isolation,
future rejection and opaque/interval protection. Two Apple-only model tests
cover real CUD, original baby after selection changes, duplicate dispatch,
rollback and discard. Apple SDK and UI acceptance remain separate CI gates.

### Manual nursing (local implementation; Apple validation pending)

`nursing` (母乳) uses the same ordinary confirmation/CAS/delete owner for manual
point facts only. Left/right minutes default to `0`, order to `LR`, optional milk
amount to empty, and new record mode to `end`. Both minutes must be integer
0...1440 and at least one side must be positive. The source order choices are
L/仅左, LR/先左后右, RL/先右后左, R/仅右; no additional matching between order and
nonzero sides is imposed. Optional milk amount must be 1...999 ml when nonblank;
it is user-entered, never estimated from minutes.

The draft delegates domain validation to `NursingConfirmInput` only after strict
text checks, and calls its payload adapter only after validation succeeds. That
adapter's invalid-text zero/nil fallbacks never become a successful native fact.
Minutes allow four BMP decimal-digit UTF-16 units, amount three; pasted invalid
text remains visible and blocks confirmation instead of Android's digit-filtering
and truncation. The duration issue focuses the left field, as Android does;
order and optional amount have separate focus targets.

Editing preserves the original `start`/`end` payload mode, timestamp and optional
amount. Minutes never synthesize or change a timestamp. Known-schema legacy
numeric values outside confirmation ranges remain visible for explicit correction;
even a note-only edit cannot bypass strict validation. Unknown mode/order/opaque
records remain read-only. Every record with an `endTimestamp` remains read-only
in this bounded editor, including start-mode timer interval records, so editing
cannot silently drop an endpoint.

There is no timer entry, running-state claim, plan or automatic next-feed offer.
Android timer completion uses a separate durable session/UUID, elapsed-time
accumulation and frozen confirmation. Its global start/end setting chooses
`timestamp=startedAt,endTimestamp=endedAt` for start, or
`timestamp=endedAt,endTimestamp=nil` for end. The manual editor does not expose
that timer preference or reinterpret its own point time from duration. Real timer
persistence, restoration, completion replay/cleanup, background lifecycle and
plan/media handoff require a separate future slice. Future facts remain blocked.

Source references: `core/model/.../NursingConfirmInput.kt`,
`designsystem/.../NursingConfirmFields.kt`, `QuickRecordDraft.kt:171–174,448–457,627–629`,
`RecordPayload.kt:54–76`, `RecordMutationCoordinator.kt:580–677`, and
`feature/timer/.../{NursingCompletionDraft,TimerState,TimerViewModel}.kt`.
Seven portable tests cover every order, single/double sides, strict raw text and
optional nil, both legacy modes, invalid legacy repair through a real COMMIT,
interval/opaque protection, original-baby file reopen/edit/delete and future
rejection. Two Apple-only model tests cover actual CUD, original baby across
selection changes, duplicate dispatch, rollback and discard. These are separate
from the pending ninth Simulator UI acceptance.

### Concrete custom definitions and records (local implementation; Apple pending)

The global **本机自定义项目** catalog is independent of baby selection. It offers
create, name/icon editing, explicit delete confirmation, and explicit up/down
ordering confirmation. Names trim using AndroidText, new/changed input is limited
to 20 UTF-16 units, and unchanged legacy long names remain usable. Invalid text
is retained rather than Android's on-change `take(20)`. The store enforces exact
name uniqueness, a ten-live-definition limit, generation ownership and revision
CAS. The eight slot meanings remain star/heart/sun/moon/music/circle/triangle/
diamond; the native component uses corresponding SF Symbols, not a claim of
pixel-identical Android vector paths. Definition icon selection does not mutate
historical record icons. Ordering is device-local and does not change business
revision or dirty flags. No family/shared-delete or local-hide behavior is implied.

`OfflineRecordTarget` is `.builtIn(OfflineRecordKind)` or `.custom(UUID)`.
`OfflineRecordKind` remains the 26 built-ins, so no bare custom can enter the
existing picker or reach `builtIn(.custom)!`. The Core UUID factory creates a
canonical concrete custom identity. The draft's optional kind is only a built-in
projection; target is authoritative and custom identity cannot be changed by
`editing(kind:)`. Existing built-in initialization and accessibility IDs remain.
The single sheet host switches between the catalog and a record editor; it never
presents two sheets concurrently.

New custom records require selecting a live concrete definition and a baby.
Title/icon are captured from that selection. The record form edits only title
(required, 30 UTF-16) and detail (optional, 200); the icon snapshot is read-only.
Record fields trim on confirmation, preserve unchanged legacy long input, and
retain/reject new overlong input. Catalog rename/icon edits do not restamp an
open draft or historical record. Creating from a subsequently deleted definition
fails without clearing the draft. Known historical records still edit after the
definition is deleted, retaining original baby, definition UUID, icon and CAS
revision. Missing associations, unknown documents and unsupported intervals stay
read-only. Device-local numeric custom IDs are never guessed into UUID identity.
The Core field named `familyCustomUUID` is only a stable identity carrier here;
it creates no membership, family session or synchronization capability.

Record confirmation still uses the same immutable attempt, busy/dirty/discard
owner, but dispatches concrete custom facts to `confirmCustomRecord`; ordinary
facts continue through `confirmRecord`. Definition operations use their own
small catalog owner with captured generation/revision and duplicate-dispatch
busy gates. Draft clearing and success messages happen only after COMMIT;
rollback keeps inputs. Catalog saves/deletes/reorders never write a baby record.

Sources: `core/ui/.../CustomItemManageDialog.kt:368–442`,
`QuickRecordPurposeTextFields.kt:35–76`, `QuickRecordDraft.kt:680–685,850–853`,
`designsystem/.../LeziCustomItemGlyphs.kt`, and `OfflineStore+Custom.swift`.
Four portable tests cover concrete targets, limits, readonly cases and actual
SQLite restart/rename/delete/history editing/cross-definition refusal. Five
Apple-only model tests cover catalog CRUD/reorder/restart, rollback/duplicate
save/discard, read-failure retention, session-bound close versus cancel-edit, and record original-baby/rename/delete-history/rollback behavior.
These and the tenth UI acceptance remain pending Apple SDK/runtime validation.
