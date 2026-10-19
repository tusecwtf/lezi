/// Acceptance boundary for this additive native storage checkpoint.
///
/// Implemented: independent native SQLite schema 1; file-backed offline identity
/// and store generation; baby nickname/birthday facts plus separate local color
/// and ordering preferences; explicit-confirm no-media built-in pee create/edit;
/// live-baby/context/CAS gates; atomic fact + syncDirty writes; replay protection.
///
/// NOT implemented: family enrollment/session/membership ACL, family reads or
/// writes while disconnected, media, other record kinds, full Baby metadata,
/// reset/migrations, publication, canonical Record root encoding, causal envelope
/// freezing, receipts/conflicts, generic outbox, sync transport or an installable
/// iOS application. syncDirty is not a mutation envelope and is never sent.
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
    public static let schemaVersion = 1
    public static let supportedRecordType = "pee"
}
