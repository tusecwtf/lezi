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
