import LeziCore

/// Acceptance boundary for this additive native storage checkpoint.
///
/// Implemented: independent native SQLite schema 5 with atomic native schema-1/2/3/4 migration; file-backed offline identity
/// and store generation; baby nickname/sex/birthday/birth-weight facts plus separate local color
/// and ordering preferences; explicit-confirm no-media ordinary built-in record create/edit/soft-delete;
/// live-baby/context/CAS gates; atomic fact + syncDirty writes; replay protection.
///
/// NOT implemented: family enrollment/session/membership ACL, family reads or
/// writes while disconnected, media, full Baby metadata,
/// reset, publication, canonical Record root encoding, causal envelope
/// freezing, receipts/conflicts, generic outbox, sync transport or a complete shipping
/// iOS application. syncDirty is not a mutation envelope and is never sent.
///
/// Dedicated sleep commands persist SleepStart and WakeObservation as separate
/// roots; raw sleep end_timestamp stays NULL. Completed creation uses a stronger
/// dual-root transaction than Android's separate start/wake commits. Correction
/// and withdrawal also keep both roots atomic. Projected-closed start edits
/// cannot reopen sleep accidentally; explicit withdrawal can. Wake replay binds
/// the captured sleep and baby rather than Android's UUID-only early return.
/// Completed time correction preserves the independent wake note, unlike Android
/// copying the sleep draft note; updateWake explicitly edits observation notes.
/// CAS and local dirty/actor ownership are offline gates, not family ACL parity.
/// Native v2 admitted no sleep: unexpected historical sleep rows reject migration.
/// New completed sleep uses the Android backfill UUIDv5 helper, never the separate
/// wire legacy migration identity. Local observer UUID is not a membership stamp.
/// Soft-deleting sleep preserves all wake evidence. Batch page projection reads
/// roots, their observations, and latest-open target within one SQLite snapshot.
/// Ordinary writer support below intentionally excludes the dedicated sleep/custom paths.
///
/// Concrete custom definitions use stable UUID identity, separate local ordering,
/// and atomic dirty facts. A local creation ordinal breaks equal sort positions
/// like Android local insertion order; it is never business or wire identity.
/// Dedicated custom confirmation binds that UUID to the
/// record; numeric Android IDs are not generated or serialized. Historical title/
/// icon snapshots survive definition rename/delete; typed edits keep the original
/// definition and baby. Definitions and records are separate future causal roots.
/// Unexpected pre-v4 custom rows reject migration rather than inventing identity.
/// Catalog equality is case-sensitive, exact Unicode encoding as Kotlin String;
/// catalog names have no storage length limit. No family authority is fabricated.
///
/// Product text forms (note length, nursing input, food-amount editing and
/// display-unit conversion) remain UI/domain responsibilities. Typed storage
/// validation does not establish acceptance of those forms. The legacy pee-only
/// adapter rejects opaque pee documents without modifying them; recordPage
/// returns opaque documents for a generic read-only presentation.
///
/// Source anchors: CONTEXT.md offline mode versus offline-available membership;
/// RecordMutationCoordinator.kt identity/replay/live-baby/update invariants;
/// CausalMutationState.kt fact epoch versus frozen mutation/base identity.
/// Open editors retain their original baby across UI selection changes; only
/// generation, offline mode and the original live baby authorize the write.
/// Native edit CAS and Int64.max overflow rejection are intentional safety
/// differences from Android timestamp saturation, not claimed exact parity.
/// Future family enrollment must blockOfflineWrites before making session state
/// visible. Family authority needs a separate integrated ACL-aware coordinator;
/// absence of a network connection must never select this offline-only writer.
public enum OfflineStorageScope {
    public static let schemaVersion = 5
    public static let supportedRecordTypes = RecordType.allCases.filter { $0 != .sleep && $0 != .custom }
}
