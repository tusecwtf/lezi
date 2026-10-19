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
