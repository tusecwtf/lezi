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
