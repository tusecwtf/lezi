import Foundation
import LeziCore

extension OfflineStore {
    /// Dedicated SleepStart writer. Ordinary confirmRecord continues to reject sleep.
    public func createSleepStart(_ draft: RecordConfirmationDraft, context: OfflineContext) throws -> OfflineSleep {
        let now = clock()
        let json = try Self.sleepJSON(draft, now: now, completed: false)
        return try writeTransaction {
            try requireSleepContext(context, baby: draft.babyClientUUID)
            if let existing = try recordDetails(draft.clientUUID) {
                try requireSleepIdentity(existing, baby: context.babyClientUUID)
                return try projectSleep(existing)
            }
            guard try latestOpenSleep(baby: context.babyClientUUID) == nil else { throw OfflineStoreError.sleepStateChanged }
            try insertSleep(draft, json: json, now: now)
            try inject(.afterSleep); try inject(.beforeCommit)
            return try projectSleep(requireSleep(draft.clientUUID, baby: context.babyClientUUID))
        }
    }

    /// Backfill is one explicit command with two atomic facts. The deterministic
    /// wake uses Android's new-backfill identity, NOT wire legacy-migration identity.
    public func recordCompletedSleep(_ draft: RecordConfirmationDraft, context: OfflineContext) throws -> OfflineSleep {
        let now = clock()
        let json = try Self.sleepJSON(draft, now: now, completed: true)
        let wakeID = SleepWakeIdentity.closedSleepObservationUUID(sleepClientUUID: draft.clientUUID)
        return try writeTransaction {
            try requireSleepContext(context, baby: draft.babyClientUUID)
            if let existing = try recordDetails(draft.clientUUID) {
                try requireSleepIdentity(existing, baby: context.babyClientUUID)
                guard let wake = try wakeDetails(wakeID), wake.sleepClientUUID == existing.clientUUID,
                      wake.deletedAt == nil else { throw OfflineStoreError.sleepStateChanged }
                return try projectSleep(existing) // Replay cannot change/revive a withdrawn wake.
            }
            guard try latestOpenSleep(baby: context.babyClientUUID) == nil else { throw OfflineStoreError.sleepStateChanged }
            // A conflicting wake identity must not be adopted by a new sleep.
            guard try wakeDetails(wakeID) == nil else { throw OfflineStoreError.identityConflict }
            try insertSleep(draft, json: json, now: now)
            try inject(.afterSleep)
            let wake = OfflineWakeDraft(clientUUID: wakeID, sleepClientUUID: draft.clientUUID,
                babyClientUUID: draft.babyClientUUID, timestampMillis: draft.endTimestampMillis!, note: draft.note)
            try insertWake(wake, now: now)
            try inject(.afterWake); try inject(.beforeCommit)
            return try projectSleep(requireSleep(draft.clientUUID, baby: context.babyClientUUID))
        }
    }

    /// Adds independent observation evidence. Already having a provisional/effective
    /// wake does not forbid another observation against an explicitly captured sleep.
    public func recordWake(_ draft: OfflineWakeDraft, context: OfflineContext) throws -> OfflineWakeObservation {
        let now = clock()
        guard RecordTime.pointError(timestamp: draft.timestampMillis, now: now) == nil else { throw OfflineStoreError.invalidDraft }
        return try writeTransaction {
            try requireSleepContext(context, baby: draft.babyClientUUID)
            let sleep = try requireSleep(draft.sleepClientUUID, baby: context.babyClientUUID)
            if let existing = try wakeDetails(draft.clientUUID) {
                guard existing.sleepClientUUID == sleep.clientUUID else { throw OfflineStoreError.identityConflict }
                guard existing.deletedAt == nil else { throw OfflineStoreError.deletedRecord }
                return existing
            }
            guard SleepProjection.validateWakeTimestamp(sleepStart: sleep.timestampMillis, wake: draft.timestampMillis) == nil else { throw OfflineStoreError.invalidDraft }
            try insertWake(draft, now: now)
            try inject(.afterWake); try inject(.beforeCommit)
            return try requireWake(draft.clientUUID)
        }
    }

    /// Edits only the start fact. A closed projection cannot accidentally reopen.
    public func editSleepStart(_ draft: RecordConfirmationDraft, context: OfflineContext, expectedUpdatedAt: Int64) throws -> OfflineSleep {
        let now = clock(); let json = try Self.sleepJSON(draft, now: now, completed: false)
        return try writeTransaction {
            try requireSleepContext(context, baby: draft.babyClientUUID)
            let old = try requireSleep(draft.clientUUID, baby: context.babyClientUUID)
            guard old.updatedAt == expectedUpdatedAt else { throw OfflineStoreError.staleEdit }
            let wasClosed = try !projectSleep(old).interval.isOpen
            try updateSleep(draft, json: json, revision: Self.nextRevision(after: old.updatedAt, now: now))
            let result = try projectSleep(requireSleep(old.clientUUID, baby: context.babyClientUUID))
            guard !wasClosed || !result.interval.isOpen else { throw OfflineStoreError.sleepStateChanged }
            try inject(.afterSleep); try inject(.beforeCommit)
            return result
        }
    }

    /// Explicit time correction of the projected wake and start, with CAS on both roots.
    /// The independent wake note is preserved; updateWake explicitly edits that note.
    public func editCompletedSleep(_ draft: RecordConfirmationDraft, wakeClientUUID: UUID,
                                   context: OfflineContext, expectedUpdatedAt: Int64, expectedWakeUpdatedAt: Int64) throws -> OfflineSleep {
        let now = clock(); let json = try Self.sleepJSON(draft, now: now, completed: true)
        return try writeTransaction {
            try requireSleepContext(context, baby: draft.babyClientUUID)
            let old = try requireSleep(draft.clientUUID, baby: context.babyClientUUID)
            guard old.updatedAt == expectedUpdatedAt else { throw OfflineStoreError.staleEdit }
            guard try projectSleep(old).interval.endObservationClientUUID == wakeClientUUID.uuidString else { throw OfflineStoreError.sleepStateChanged }
            let wake = try requireEditableWake(wakeClientUUID, sleep: old.clientUUID, expectedUpdatedAt: expectedWakeUpdatedAt)
            let revision = try Self.nextRevision(after: old.updatedAt, now: now)
            let wakeRevision = try Self.nextRevision(after: wake.updatedAt, now: now)
            try updateSleep(draft, json: json, revision: revision); try inject(.afterSleep)
            try updateWakeRow(wakeClientUUID, timestamp: draft.endTimestampMillis!, note: wake.note, revision: wakeRevision)
            try inject(.afterWake); try inject(.beforeCommit)
            return try projectSleep(requireSleep(old.clientUUID, baby: context.babyClientUUID))
        }
    }

    /// Both revisions come from the captured editor snapshot; neither root may
    /// have changed before this independent observation is edited.
    public func updateWake(_ draft: OfflineWakeDraft, context: OfflineContext, expectedUpdatedAt: Int64,
                           expectedSleepUpdatedAt: Int64) throws -> OfflineWakeObservation {
        let now = clock()
        guard RecordTime.pointError(timestamp: draft.timestampMillis, now: now) == nil else { throw OfflineStoreError.invalidDraft }
        return try writeTransaction {
            try requireSleepContext(context, baby: draft.babyClientUUID)
            let sleep = try requireSleep(draft.sleepClientUUID, baby: context.babyClientUUID)
            guard sleep.updatedAt == expectedSleepUpdatedAt else { throw OfflineStoreError.staleEdit }
            let old = try requireEditableWake(draft.clientUUID, sleep: sleep.clientUUID, expectedUpdatedAt: expectedUpdatedAt)
            guard SleepProjection.validateWakeTimestamp(sleepStart: sleep.timestampMillis, wake: draft.timestampMillis) == nil else { throw OfflineStoreError.invalidDraft }
            try updateWakeRow(old.clientUUID, timestamp: draft.timestampMillis, note: draft.note,
                              revision: Self.nextRevision(after: old.updatedAt, now: now))
            try inject(.afterWake); try inject(.beforeCommit)
            return try requireWake(old.clientUUID)
        }
    }

    /// Withdrawal retains evidence and may deliberately reopen sleep. If selected,
    /// clearing the effective pointer and withdrawing the wake commit together.
    /// Both the wake and parent sleep must still match their captured revisions.
    public func withdrawWake(_ id: UUID, sleepClientUUID: UUID, context: OfflineContext, expectedUpdatedAt: Int64,
                             expectedSleepUpdatedAt: Int64) throws -> OfflineWakeObservation {
        let now = clock()
        return try writeTransaction {
            try requireSleepContext(context, baby: context.babyClientUUID)
            let sleep = try requireSleep(sleepClientUUID, baby: context.babyClientUUID)
            guard sleep.updatedAt == expectedSleepUpdatedAt else { throw OfflineStoreError.staleEdit }
            let old = try requireEditableWake(id, sleep: sleep.clientUUID, expectedUpdatedAt: expectedUpdatedAt)
            let revision = try Self.nextRevision(after: old.updatedAt, now: now)
            try db.execute("UPDATE wake_observations SET withdrawn=1,updated_at=?,sync_dirty=1 WHERE uuid=?", [.int(revision), .text(id.uuidString)])
            try inject(.afterWake)
            if sleep.effectiveWakeObservationClientUUID == id {
                try setEffectivePointer(nil, sleep: sleep, now: now)
            }
            try inject(.afterSleep); try inject(.beforeCommit)
            return try requireWake(id)
        }
    }

    public func selectEffectiveWake(_ wakeClientUUID: UUID?, sleepClientUUID: UUID, context: OfflineContext, expectedUpdatedAt: Int64) throws -> OfflineSleep {
        let now = clock()
        return try writeTransaction {
            try requireSleepContext(context, baby: context.babyClientUUID)
            let sleep = try requireSleep(sleepClientUUID, baby: context.babyClientUUID)
            guard sleep.updatedAt == expectedUpdatedAt else { throw OfflineStoreError.staleEdit }
            if let id = wakeClientUUID {
                let wake = try requireWake(id)
                guard wake.sleepClientUUID == sleep.clientUUID else { throw OfflineStoreError.identityConflict }
                guard !wake.withdrawn, wake.timestampMillis >= sleep.timestampMillis else { throw OfflineStoreError.invalidDraft }
            }
            try setEffectivePointer(wakeClientUUID, sleep: sleep, now: now)
            try inject(.afterSleep); try inject(.beforeCommit)
            return try projectSleep(requireSleep(sleepClientUUID, baby: context.babyClientUUID))
        }
    }

    private func requireEditableWake(_ id: UUID, sleep: UUID, expectedUpdatedAt: Int64) throws -> OfflineWakeObservation {
        let wake = try requireWake(id)
        guard wake.sleepClientUUID == sleep else { throw OfflineStoreError.identityConflict }
        guard wake.localObserverUUID == (try identity()).localActorUUID, wake.syncDirty else { throw OfflineStoreError.wakePermission }
        guard wake.updatedAt == expectedUpdatedAt else { throw OfflineStoreError.staleEdit }
        return wake
    }
    private func setEffectivePointer(_ id: UUID?, sleep: OfflineRecord, now: Int64) throws {
        let revision = try Self.nextRevision(after: sleep.updatedAt, now: now)
        try db.execute("UPDATE records SET effective_wake_observation_client_uuid=?,updated_at=?,sync_dirty=1 WHERE uuid=?", [id.map { .text($0.uuidString) } ?? .null, .int(revision), .text(sleep.clientUUID.uuidString)])
    }
    private func updateSleep(_ draft: RecordConfirmationDraft, json: String, revision: Int64) throws {
        try db.execute("UPDATE records SET timestamp=?,note=?,payload_json=?,updated_at=?,sync_dirty=1 WHERE uuid=?", [.int(draft.timestampMillis), draft.note.map(SQLiteConnection.Value.text) ?? .null, .text(json), .int(revision), .text(draft.clientUUID.uuidString)])
    }
    private func updateWakeRow(_ id: UUID, timestamp: Int64, note: String?, revision: Int64) throws {
        try db.execute("UPDATE wake_observations SET wake_timestamp=?,note=?,withdrawn=0,updated_at=?,sync_dirty=1 WHERE uuid=?", [.int(timestamp), note.map(SQLiteConnection.Value.text) ?? .null, .int(revision), .text(id.uuidString)])
    }

    /// Batch record + wake projection in one read snapshot, with no per-sleep queries.
    /// The page remains bounded; all evidence for each included sleep participates.
    public func sleepPage(baby: UUID, before: OfflineRecordCursor? = nil, limit: Int = 100) throws -> OfflineSleepPage {
        try db.transaction(begin: "BEGIN") {
            try sleepPageSnapshot(baby: baby, before: before, limit: limit)
        }
    }
    // Caller owns one read snapshot, including the latest open peer outside this page.
    func sleepPageSnapshot(baby: UUID, before: OfflineRecordCursor?, limit: Int,
                           includeLatestOpenWhenEmpty: Bool = true) throws -> OfflineSleepPage {
        let page = try readPageSnapshot(baby: baby, before: before, limit: limit, peeOnly: false)
        let roots = page.records.filter { $0.type == .sleep }
        let wakes = try wakeRows(sleepIDs: roots.map(\.clientUUID))
        let grouped = Dictionary(grouping: wakes, by: \.sleepClientUUID)
        // Timeline callers also display the global wake target. Search only
        // needs that lookup in chunks containing sleep roots to project.
        let latest: OfflineRecord?
        if !roots.isEmpty || includeLatestOpenWhenEmpty {
            latest = try latestOpenSleep(baby: baby)
        } else {
            latest = nil
        }
        let pairs = roots.map { root in
            (root.clientUUID, Self.project(root, wakes: grouped[root.clientUUID] ?? [], latestOpen: latest))
        }
        return OfflineSleepPage(page: page, sleeps: Dictionary(uniqueKeysWithValues: pairs), latestOpenSleepClientUUID: latest?.clientUUID)
    }
    public func sleepDetails(_ id: UUID) throws -> OfflineSleep? {
        try db.transaction(begin: "BEGIN") {
            guard let record = try recordDetails(id), record.type == .sleep, record.deletedAt == nil else { return nil }
            return try projectSleep(record)
        }
    }
    public func openSleep(baby: UUID) throws -> OfflineSleep? {
        try db.transaction(begin: "BEGIN") {
            try requireLiveBaby(baby)
            guard let record = try latestOpenSleep(baby: baby) else { return nil }
            return Self.project(record, wakes: try wakeRows(sleepIDs: [record.clientUUID]), latestOpen: record)
        }
    }
    public func wakeDetails(_ id: UUID) throws -> OfflineWakeObservation? {
        guard let row = try db.query("SELECT " + Self.wakeColumns + " FROM wake_observations WHERE uuid=?", [.text(id.uuidString)]).first else { return nil }
        return try Self.decodeWake(row)
    }

    private static func sleepJSON(_ draft: RecordConfirmationDraft, now: Int64, completed: Bool) throws -> String {
        guard draft.item == .builtIn(.sleep), draft.payload.type == .sleep, draft.photoClientUUIDs.isEmpty,
              (draft.endTimestampMillis != nil) == completed, draft.validationErrors(nowMillis: now).isEmpty else { throw OfflineStoreError.invalidDraft }
        return try RecordPayloadCodec.encode(RecordPayloadDocument(payload: draft.payload))
    }
    private func requireSleepContext(_ context: OfflineContext, baby: UUID) throws {
        try requireOfflineContext(context)
        guard context.babyClientUUID == baby else { throw OfflineStoreError.staleContext }
        try requireLiveBaby(baby)
    }
    private func requireSleepIdentity(_ sleep: OfflineRecord, baby: UUID) throws {
        guard sleep.type == .sleep, sleep.babyClientUUID == baby else { throw OfflineStoreError.identityConflict }
        guard sleep.deletedAt == nil else { throw OfflineStoreError.deletedRecord }
        guard !sleep.isOpaque else { throw OfflineStoreError.opaqueRecord }
        guard sleep.endTimestampMillis == nil else { throw OfflineStoreError.unsupportedSleepHistory }
    }
    private func requireSleep(_ id: UUID, baby: UUID) throws -> OfflineRecord {
        guard let sleep = try recordDetails(id) else { throw OfflineStoreError.sleepStateChanged }
        try requireSleepIdentity(sleep, baby: baby)
        return sleep
    }
    private func requireWake(_ id: UUID) throws -> OfflineWakeObservation {
        guard let wake = try wakeDetails(id) else { throw OfflineStoreError.missingWake }
        guard wake.deletedAt == nil else { throw OfflineStoreError.deletedRecord }
        return wake
    }
    private func insertSleep(_ draft: RecordConfirmationDraft, json: String, now: Int64) throws {
        try db.execute("INSERT INTO records (uuid,baby_uuid,type,timestamp,end_timestamp,note,payload_json,payload_schema_version,updated_at,sync_dirty) VALUES (?,?,'sleep',?,NULL,?,?,2,?,1)", [
            .text(draft.clientUUID.uuidString), .text(draft.babyClientUUID.uuidString), .int(draft.timestampMillis),
            draft.note.map(SQLiteConnection.Value.text) ?? .null, .text(json), .int(now)])
    }
    private func insertWake(_ draft: OfflineWakeDraft, now: Int64) throws {
        let observer = try identity().localActorUUID
        try db.execute("INSERT INTO wake_observations VALUES (?,?,?,?,0,?,?,1,NULL)", [.text(draft.clientUUID.uuidString),
            .text(draft.sleepClientUUID.uuidString), .int(draft.timestampMillis), draft.note.map(SQLiteConnection.Value.text) ?? .null,
            .text(observer.uuidString), .int(now)])
    }
    func projectSleep(_ record: OfflineRecord) throws -> OfflineSleep {
        Self.project(record, wakes: try wakeRows(sleepIDs: [record.clientUUID]), latestOpen: try latestOpenSleep(baby: record.babyClientUUID))
    }
    private static func project(_ record: OfflineRecord, wakes: [OfflineWakeObservation], latestOpen: OfflineRecord?) -> OfflineSleep {
        let peers = latestOpen.map { [(clientUUID: $0.clientUUID.uuidString, timestamp: $0.timestampMillis)] } ?? []
        let interval = SleepProjection.project(sleepClientUUID: record.clientUUID.uuidString, startTimestamp: record.timestampMillis,
            effectiveWakeObservationClientUUID: record.effectiveWakeObservationClientUUID?.uuidString,
            observations: wakes.map(\.projectionFact), legacyEndTimestamp: record.endTimestampMillis, peerOpenSleepStarts: peers)
        return OfflineSleep(record: record, interval: interval, observations: wakes)
    }
    // Correlated existence lookup uses the wake_by_sleep composite index. A wake
    // closes projection even when the effective pointer is absent/invalid.
    static var latestOpenSleepSQL: String {
        "SELECT " + recordColumns.split(separator: ",").map { "r." + $0 }.joined(separator: ",") + """
         FROM records r WHERE r.baby_uuid=? AND r.type='sleep' AND r.deleted_at IS NULL AND r.end_timestamp IS NULL
         AND NOT EXISTS (SELECT 1 FROM wake_observations w WHERE w.sleep_record_client_uuid=r.uuid
         AND w.withdrawn=0 AND w.deleted_at IS NULL AND w.wake_timestamp>=r.timestamp)
         ORDER BY r.timestamp DESC,r.uuid DESC LIMIT 1
        """
    }
    private func latestOpenSleep(baby: UUID) throws -> OfflineRecord? {
        try db.query(Self.latestOpenSleepSQL, [.text(baby.uuidString)]).first.map(Self.decodeRecord)
    }
    private static let wakeColumns = "uuid,sleep_record_client_uuid,wake_timestamp,note,withdrawn,local_observer_uuid,updated_at,sync_dirty,deleted_at"
    private func wakeRows(sleepIDs: [UUID]) throws -> [OfflineWakeObservation] {
        guard !sleepIDs.isEmpty else { return [] }
        let placeholders = Array(repeating: "?", count: sleepIDs.count).joined(separator: ",")
        return try db.query("SELECT " + Self.wakeColumns + " FROM wake_observations WHERE sleep_record_client_uuid IN (" + placeholders + ") ORDER BY wake_timestamp,uuid", sleepIDs.map { .text($0.uuidString) }).map(Self.decodeWake)
    }
    private static func decodeWake(_ row: [String?]) throws -> OfflineWakeObservation {
        guard let id = UUID(uuidString: row[0] ?? ""), let sleep = UUID(uuidString: row[1] ?? ""),
              let timestamp = Int64(row[2] ?? ""), let observer = UUID(uuidString: row[5] ?? ""),
              let updated = Int64(row[6] ?? "") else { throw OfflineStoreError.incompatibleDatabase }
        return OfflineWakeObservation(clientUUID: id, sleepClientUUID: sleep, timestampMillis: timestamp,
            note: row[3], withdrawn: row[4] == "1", localObserverUUID: observer, updatedAt: updated,
            syncDirty: row[7] == "1", deletedAt: row[8].flatMap(Int64.init))
    }
}
