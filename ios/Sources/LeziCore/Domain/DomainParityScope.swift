// Domain parity scope (2026-10-19)
//
// Sources: core/model/{RecordType,RecordPayload,RecordItemIdentity,
// NursingConfirmInput,GrowthMeasurementFacts,RecordTime,SleepProjection,
// FoodAmount,Models}.kt and docs/spec/{product,contracts/data-model,
// contracts/ui,layers/domain}.md. CONTEXT.md defines care facts vs plans.
//
// Implemented: all 28 type keys, 27 concrete built-in creation targets, typed
// payload field validation, nursing confirmation text rules, growth input units,
// concrete catalog-key parsing, immutable confirmation snapshot, epoch fact/plan
// gates, strict wall-time/DST resolution and natural-day intervals, sleep/wake
// projection, plan status/next-feed marker and local root publication state.
// Storage payload schema-2 codec covers all 28 types, closed keys, explicit-null
// rejection, local primitive coercion and opaque raw-document round trips. Its
// document wrapper retains local custom row IDs outside the portable enum.
// This is not frozen-wire canonicalization; see codec limits and source notes.
//
// Pending (do not claim complete domain parity): resolved local custom-definition
// lookup; complete
// Baby/Record/CarePlan persistence models; author/owner ACL; atomic media packages;
// fulfillment candidate adjudication; nursing timer/clear epochs; source relations
// and duplicate bounds; daily/weekly aggregation and reference growth curves;
// date-selection clamping/new-draft gap advance/reminder-shift policy; calendar,
// reminder, photo and repository adapters. Wake values here omit observer/note
// metadata because the implemented projection only consumes identity/time/state.
//
// No model in this folder is a Codable wire DTO, persistent pending mutation,
// network request or storage write. RecordConfirmationDraft is only an explicit
// review snapshot; it does not authorize or implement saving/publishing a fact.
// Linux tests cover portable behavior only; Xcode/iOS validation remains required.
