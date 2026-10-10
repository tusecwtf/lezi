//! Public Store seam regressions for causal media resource bounds and recovery.

use std::fs;
use std::time::{SystemTime, UNIX_EPOCH};

use sha2::{Digest, Sha256};
use tempfile::{NamedTempFile, TempDir};
use uuid::Uuid;

use super::super::causal_media_staging::{
    CAUSAL_MEDIA_GC_BATCH, CAUSAL_MEDIA_GC_SCAN_LIMIT, CAUSAL_MEDIA_STAGING_TTL_SECONDS,
};
use super::super::*;
use super::test_support::{
    entity, family, owner_principal, publish_root, publish_root_with_media,
    register_test_principal, stage_log_media, TestCausalMediaStage,
};
use serde_json::json;

fn fixture() -> (TempDir, Store, Principal) {
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    let principal = owner_principal(&family(&store));
    (directory, store, principal)
}

fn digest(bytes: &[u8]) -> String {
    hex::encode(Sha256::digest(bytes))
}

fn limits() -> CausalMediaStagingLimits {
    CausalMediaStagingLimits {
        max_file_bytes: 8,
        max_membership_count: 1,
        max_family_count: 2,
        max_family_bytes: 5,
        ttl_seconds: 10,
    }
}

fn future_gc_now() -> i64 {
    i64::try_from(
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_secs(),
    )
    .unwrap()
    .saturating_add(CAUSAL_MEDIA_STAGING_TTL_SECONDS)
}

#[test]
fn causal_media_staging_defaults_and_injected_equality_boundaries_are_frozen() {
    assert_eq!(
        DEFAULT_CAUSAL_MEDIA_STAGING_LIMITS.max_file_bytes,
        10 * 1024 * 1024
    );
    assert_eq!(DEFAULT_CAUSAL_MEDIA_STAGING_LIMITS.max_membership_count, 64);
    assert_eq!(DEFAULT_CAUSAL_MEDIA_STAGING_LIMITS.max_family_count, 256);
    assert_eq!(
        DEFAULT_CAUSAL_MEDIA_STAGING_LIMITS.max_family_bytes,
        512 * 1024 * 1024
    );
    assert_eq!(
        DEFAULT_CAUSAL_MEDIA_STAGING_LIMITS.ttl_seconds,
        24 * 60 * 60
    );

    let (_directory, store, owner) = fixture();
    let count_limits = CausalMediaStagingLimits {
        max_file_bytes: 4,
        max_membership_count: 3,
        max_family_count: 2,
        max_family_bytes: 16,
        ttl_seconds: 10,
    };
    for bytes in [b"a".as_slice(), b"b".as_slice()] {
        store
            .stage_test_preimage(
                &owner,
                &Uuid::new_v4().to_string(),
                bytes,
                &digest(bytes),
                100,
                count_limits,
            )
            .unwrap();
    }
    assert!(matches!(
        store.stage_test_preimage(
            &owner,
            &Uuid::new_v4().to_string(),
            b"c",
            &digest(b"c"),
            100,
            count_limits,
        ),
        Err(StoreError::CausalMediaStagingQuota("family_count"))
    ));

    let (_directory, store, owner) = fixture();
    let byte_limits = CausalMediaStagingLimits {
        max_file_bytes: 4,
        max_membership_count: 3,
        max_family_count: 3,
        max_family_bytes: 5,
        ttl_seconds: 10,
    };
    for bytes in [b"abc".as_slice(), b"de".as_slice()] {
        store
            .stage_test_preimage(
                &owner,
                &Uuid::new_v4().to_string(),
                bytes,
                &digest(bytes),
                100,
                byte_limits,
            )
            .unwrap();
    }
    assert!(matches!(
        store.stage_test_preimage(
            &owner,
            &Uuid::new_v4().to_string(),
            b"f",
            &digest(b"f"),
            100,
            byte_limits,
        ),
        Err(StoreError::CausalMediaStagingQuota("family_bytes"))
    ));

    let (_directory, store, owner) = fixture();
    let file_limits = CausalMediaStagingLimits {
        max_file_bytes: 8,
        max_membership_count: 2,
        max_family_count: 2,
        max_family_bytes: 32,
        ttl_seconds: 10,
    };
    let exact = b"12345678";
    store
        .stage_test_preimage(
            &owner,
            &Uuid::new_v4().to_string(),
            exact,
            &digest(exact),
            100,
            file_limits,
        )
        .unwrap();
    let over = b"123456789";
    assert!(matches!(
        store.stage_test_preimage(
            &owner,
            &Uuid::new_v4().to_string(),
            over,
            &digest(over),
            100,
            file_limits,
        ),
        Err(StoreError::InvalidCausalMediaStaging)
    ));
}

#[test]
fn causal_media_staging_enforces_file_membership_family_and_byte_quotas() {
    let (_directory, store, owner) = fixture();
    let first = Uuid::new_v4().to_string();
    store
        .stage_test_preimage(&owner, &first, b"abc", &digest(b"abc"), 100, limits())
        .unwrap();
    // Exact replay is idempotent and does not consume another quota slot.
    store
        .stage_test_preimage(&owner, &first, b"abc", &digest(b"abc"), 101, limits())
        .unwrap();
    let peer = Principal {
        membership_id: "m-peer".to_owned(),
        device_id: "d-peer".to_owned(),
        role: "member".to_owned(),
        ..owner.clone()
    };
    register_test_principal(&store, &peer);
    assert!(matches!(
        store.stage_test_preimage(&peer, &first, b"abc", &digest(b"abc"), 101, limits(),),
        Err(StoreError::CausalMediaMembershipMismatch)
    ));
    assert!(matches!(
        store.stage_test_preimage(
            &owner,
            &Uuid::new_v4().to_string(),
            b"d",
            &digest(b"d"),
            101,
            limits(),
        ),
        Err(StoreError::CausalMediaStagingQuota("membership_count"))
    ));

    let (_directory, store, owner) = fixture();
    let mut family_limits = limits();
    family_limits.max_membership_count = 2;
    family_limits.max_family_count = 1;
    store
        .stage_test_preimage(
            &owner,
            &Uuid::new_v4().to_string(),
            b"a",
            &digest(b"a"),
            100,
            family_limits,
        )
        .unwrap();
    let peer = Principal {
        membership_id: "m-peer".to_owned(),
        device_id: "d-peer".to_owned(),
        role: "member".to_owned(),
        ..owner.clone()
    };
    register_test_principal(&store, &peer);
    assert!(matches!(
        store.stage_test_preimage(
            &peer,
            &Uuid::new_v4().to_string(),
            b"b",
            &digest(b"b"),
            100,
            family_limits,
        ),
        Err(StoreError::CausalMediaStagingQuota("family_count"))
    ));

    let (_directory, store, owner) = fixture();
    let mut byte_limits = limits();
    byte_limits.max_membership_count = 3;
    store
        .stage_test_preimage(
            &owner,
            &Uuid::new_v4().to_string(),
            b"abcd",
            &digest(b"abcd"),
            100,
            byte_limits,
        )
        .unwrap();
    assert!(matches!(
        store.stage_test_preimage(
            &owner,
            &Uuid::new_v4().to_string(),
            b"ef",
            &digest(b"ef"),
            100,
            byte_limits,
        ),
        Err(StoreError::CausalMediaStagingQuota("family_bytes"))
    ));

    let too_large = b"123456789";
    assert!(matches!(
        store.stage_test_preimage(
            &owner,
            &Uuid::new_v4().to_string(),
            too_large,
            &digest(too_large),
            100,
            byte_limits,
        ),
        Err(StoreError::InvalidCausalMediaStaging)
    ));
}

#[test]
fn causal_media_store_rejects_a_forged_digest_claim() {
    let (_directory, _store, _owner) = fixture();
    let incoming = NamedTempFile::new().unwrap();
    fs::write(incoming.path(), b"evil").unwrap();

    let result = VerifiedCausalMediaPreimage::verify(
        incoming.path().to_owned(),
        &digest(b"good"),
        limits().max_file_bytes,
    );

    assert!(matches!(
        result,
        Err(StoreError::CausalMediaPreimageConflict)
    ));
}

#[test]
fn causal_media_store_repairs_a_writing_crash_replay_from_verified_bytes() {
    let (directory, store, owner) = fixture();
    let media_id = Uuid::new_v4().to_string();
    let bytes = b"good";
    store
        .stage_test_preimage(&owner, &media_id, bytes, &digest(bytes), 100, limits())
        .unwrap();
    let staged_path = directory
        .path()
        .join("media/.causal-stage")
        .join(&owner.family_id)
        .join(&media_id);
    fs::write(&staged_path, vec![b'x'; bytes.len()]).unwrap();
    let connection = rusqlite::Connection::open(directory.path().join("lezi.db")).unwrap();
    connection
        .execute(
            "UPDATE causal_media_staging SET status = 'writing'
             WHERE family_id = ?1 AND media_uuid = ?2",
            rusqlite::params![owner.family_id, media_id],
        )
        .unwrap();
    drop(connection);
    let incoming = NamedTempFile::new().unwrap();
    fs::write(incoming.path(), bytes).unwrap();
    let verified = VerifiedCausalMediaPreimage::verify(
        incoming.path().to_owned(),
        &digest(bytes),
        limits().max_file_bytes,
    )
    .unwrap();

    let replay = store
        .stage_verified_causal_media_preimage(&owner, &media_id, &verified, 101, limits())
        .unwrap();

    assert_eq!(replay.status, "staged");
    assert_eq!(fs::read(staged_path).unwrap(), bytes);
    let connection = rusqlite::Connection::open(directory.path().join("lezi.db")).unwrap();
    let status: String = connection
        .query_row(
            "SELECT status FROM causal_media_staging
             WHERE family_id = ?1 AND media_uuid = ?2",
            rusqlite::params![owner.family_id, media_id],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(status, "staged");
}

#[test]
fn causal_media_gc_expires_metadata_bytes_and_reserved_crash_uploads() {
    let (directory, store, owner) = fixture();
    let media_id = Uuid::new_v4().to_string();
    store
        .stage_test_preimage(&owner, &media_id, b"abc", &digest(b"abc"), 100, limits())
        .unwrap();
    let family_stage = directory
        .path()
        .join("media/.causal-stage")
        .join(&owner.family_id);
    let staged = family_stage.join(&media_id);
    let reservation = store
        .reserve_causal_media_upload(&owner, &Uuid::new_v4().to_string(), 100)
        .unwrap();
    let orphan = reservation.path().to_owned();
    drop(reservation);
    fs::write(&orphan, b"orphan").unwrap();

    assert_eq!(store.gc_causal_media(109).unwrap(), 0);
    assert!(staged.exists());
    assert!(
        orphan.exists(),
        "active upload temp was collected before TTL"
    );
    assert_eq!(store.gc_causal_media(future_gc_now()).unwrap(), 2);
    assert!(
        !orphan.exists(),
        "reserved crash upload survived staging GC"
    );
    assert!(!staged.exists());

    // Expired UUID ownership is released only after metadata and bytes are gone.
    let replay = store
        .stage_test_preimage(&owner, &media_id, b"xyz", &digest(b"xyz"), 111, limits())
        .unwrap();
    assert_eq!(replay.sha256, digest(b"xyz"));
}

#[test]
fn causal_media_family_scoped_gc_does_not_touch_peer_family_files() {
    let (_directory, store, owner) = fixture();
    let peer_family_id = Uuid::new_v4().to_string();
    store
        .connect()
        .unwrap()
        .execute(
            "INSERT INTO families(id, created_at) VALUES (?1, 1)",
            rusqlite::params![peer_family_id],
        )
        .unwrap();
    let peer = owner_principal(&peer_family_id);
    register_test_principal(&store, &peer);
    let peer_reservation = store
        .reserve_causal_media_upload(&peer, &Uuid::new_v4().to_string(), 1)
        .unwrap();
    let peer_orphan = peer_reservation.path().to_owned();
    drop(peer_reservation);
    fs::create_dir_all(peer_orphan.parent().unwrap()).unwrap();
    fs::write(&peer_orphan, b"peer-crash-bytes").unwrap();

    assert_eq!(
        store
            .gc_causal_media_for_family(&owner.family_id, 100)
            .unwrap(),
        0
    );
    assert!(
        peer_orphan.exists(),
        "a family-scoped sweep crossed the caller's family lock"
    );

    assert_eq!(store.gc_causal_media(future_gc_now()).unwrap(), 1);
    assert!(
        !peer_orphan.exists(),
        "startup's global sweep did not collect the crash orphan"
    );
}

fn seed_consumed_preimage(
    directory: &TempDir,
    store: &Store,
    owner: &Principal,
    media_uuid: &str,
    bytes: &[u8],
    consumed_at: i64,
) -> std::path::PathBuf {
    let mut roomy = limits();
    roomy.max_membership_count = 64;
    roomy.max_family_count = 64;
    roomy.max_family_bytes = 1024;
    store
        .stage_test_preimage(owner, media_uuid, bytes, &digest(bytes), 100, roomy)
        .unwrap();
    let staged = directory
        .path()
        .join("media/.causal-stage")
        .join(&owner.family_id)
        .join(media_uuid);
    let published = directory
        .path()
        .join("media")
        .join(&owner.family_id)
        .join(media_uuid);
    fs::create_dir_all(published.parent().unwrap()).unwrap();
    fs::rename(staged, &published).unwrap();
    let connection = store.connect().unwrap();
    connection
        .execute(
            "UPDATE causal_media_staging
             SET status = 'consumed', consumed_at = ?1
             WHERE family_id = ?2 AND media_uuid = ?3",
            rusqlite::params![consumed_at, owner.family_id, media_uuid],
        )
        .unwrap();
    published
}

#[test]
fn causal_media_gc_retains_live_or_version_referenced_consumed_bytes_and_audit() {
    let (directory, store, owner) = fixture();
    let live_media = Uuid::new_v4().to_string();
    let version_media = Uuid::new_v4().to_string();
    let live_path = seed_consumed_preimage(&directory, &store, &owner, &live_media, b"live", 100);
    let version_path =
        seed_consumed_preimage(&directory, &store, &owner, &version_media, b"version", 100);
    let version_id = Uuid::new_v4().to_string();
    let connection = store.connect().unwrap();
    connection
        .execute(
            "INSERT INTO entities(
                family_id, entity_type, client_uuid, updated_at, deleted_at, payload_json, rev
             ) VALUES (?1, 'media', ?2, 100, NULL, '{}', 1)",
            rusqlite::params![owner.family_id, live_media],
        )
        .unwrap();
    connection
        .execute(
            "INSERT INTO entity_versions(
                family_id, version_id, entity_type, client_uuid, updated_at, deleted_at,
                payload_json, content_hash, mutation_id, origin, created_at
             ) VALUES (?1, ?2, 'record', ?3, 100, NULL, '{}', ?4, 'm-audit', 'branched', 100)",
            rusqlite::params![
                owner.family_id,
                version_id,
                Uuid::new_v4().to_string(),
                "0".repeat(64)
            ],
        )
        .unwrap();
    connection
        .execute(
            "INSERT INTO entity_version_media(
                family_id, version_id, media_uuid, media_payload_json, content_hash
             ) VALUES (?1, ?2, ?3, '{}', ?4)",
            rusqlite::params![owner.family_id, version_id, version_media, "1".repeat(64)],
        )
        .unwrap();
    connection
        .execute(
            "INSERT INTO mutation_receipts(
                family_id, membership_id, entity_type, client_uuid, mutation_id,
                content_hash, status, stable_version_id, branch_version_id,
                conflict_id, receipt_json, created_at
             ) VALUES (?1, ?2, 'record', ?3, 'm-audit', ?4, 'branched', NULL, ?5, NULL, '{}', 100)",
            rusqlite::params![
                owner.family_id,
                owner.membership_id,
                Uuid::new_v4().to_string(),
                "2".repeat(64),
                version_id
            ],
        )
        .unwrap();
    drop(connection);

    assert_eq!(
        store
            .gc_causal_media(100 + CAUSAL_MEDIA_STAGING_TTL_SECONDS)
            .unwrap(),
        0
    );
    assert!(live_path.is_file());
    assert!(version_path.is_file());
    let connection = store.connect().unwrap();
    let retained_audit: i64 = connection
        .query_row(
            "SELECT COUNT(*) FROM mutation_receipts
             WHERE family_id = ?1 AND mutation_id = 'm-audit'",
            rusqlite::params![owner.family_id],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(retained_audit, 1);
    drop(connection);

    let connection = store.connect().unwrap();
    connection
        .execute(
            "DELETE FROM entity_versions WHERE family_id = ?1 AND version_id = ?2",
            rusqlite::params![owner.family_id, version_id],
        )
        .unwrap();
    drop(connection);
    assert_eq!(
        store
            .gc_causal_media(100 + CAUSAL_MEDIA_STAGING_TTL_SECONDS)
            .unwrap(),
        1
    );
    assert!(live_path.is_file());
    assert!(!version_path.exists());
    let retained_audit: i64 = store
        .connect()
        .unwrap()
        .query_row(
            "SELECT COUNT(*) FROM mutation_receipts
             WHERE family_id = ?1 AND mutation_id = 'm-audit'",
            rusqlite::params![owner.family_id],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(
        retained_audit, 1,
        "content GC deleted replay/audit evidence"
    );
}

#[test]
fn causal_media_gc_bounds_work_and_retries_consumed_delete_after_restart() {
    let (directory, store, owner) = fixture();
    let consumed = Uuid::new_v4().to_string();
    let consumed_path =
        seed_consumed_preimage(&directory, &store, &owner, &consumed, b"orphan", 100);
    for index in 0..CAUSAL_MEDIA_GC_BATCH + 2 {
        let reservation = store
            .reserve_causal_media_upload(&owner, &Uuid::new_v4().to_string(), 100)
            .unwrap();
        fs::create_dir_all(reservation.path().parent().unwrap()).unwrap();
        fs::write(reservation.path(), format!("orphan-{index:02}")).unwrap();
    }

    super::super::causal_media_staging::test_hook::fail_after_mark_once(&owner.family_id);
    assert!(matches!(
        store.gc_causal_media(future_gc_now()),
        Err(StoreError::InvalidCausalMediaStaging)
    ));
    assert!(consumed_path.is_file());
    let status: String = store
        .connect()
        .unwrap()
        .query_row(
            "SELECT status FROM causal_media_staging
             WHERE family_id = ?1 AND media_uuid = ?2",
            rusqlite::params![owner.family_id, consumed],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(status, "gc_pending");

    let restarted = Store::open(directory.path().join("lezi.db")).unwrap();
    assert_eq!(
        restarted.gc_causal_media(future_gc_now()).unwrap(),
        CAUSAL_MEDIA_GC_BATCH
    );
    assert!(!consumed_path.exists());
    let remaining_orphans = fs::read_dir(
        directory
            .path()
            .join("media/.causal-stage")
            .join(&owner.family_id),
    )
    .unwrap()
    .count();
    assert_eq!(remaining_orphans, 3);
}

#[test]
fn causal_media_gc_resyncs_parent_before_confirming_an_unlinked_object() {
    let (directory, store, owner) = fixture();
    let consumed = Uuid::new_v4().to_string();
    let consumed_path =
        seed_consumed_preimage(&directory, &store, &owner, &consumed, b"unlink", 100);
    super::super::causal_media_staging::test_hook::fail_before_parent_sync_once(&owner.family_id);

    assert!(matches!(
        store.gc_causal_media_for_family(&owner.family_id, future_gc_now()),
        Err(StoreError::InvalidCausalMediaStaging)
    ));
    assert!(!consumed_path.exists());
    let status: String = store
        .connect()
        .unwrap()
        .query_row(
            "SELECT status FROM causal_media_staging
             WHERE family_id = ?1 AND media_uuid = ?2",
            rusqlite::params![owner.family_id, consumed],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(status, "gc_pending");

    let restarted = Store::open(directory.path().join("lezi.db")).unwrap();
    assert_eq!(
        restarted
            .gc_causal_media_for_family(&owner.family_id, future_gc_now())
            .unwrap(),
        1
    );
    let remaining: i64 = restarted
        .connect()
        .unwrap()
        .query_row(
            "SELECT COUNT(*) FROM causal_media_staging
             WHERE family_id = ?1 AND media_uuid = ?2",
            rusqlite::params![owner.family_id, consumed],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(remaining, 0);
}

#[test]
fn causal_media_gc_bounds_candidate_and_orphan_inspection_work() {
    let (_directory, store, owner) = fixture();
    let mut connection = store.connect().unwrap();
    let tx = connection.transaction().unwrap();
    for _ in 0..CAUSAL_MEDIA_GC_SCAN_LIMIT + 1 {
        tx.execute(
            "INSERT INTO causal_media_staging(
                family_id, membership_id, media_uuid, sha256, byte_size,
                created_at, expires_at, status, consumed_at
             ) VALUES (?1, ?2, ?3, ?4, 1, 0, 1, 'consumed', 1)",
            rusqlite::params![
                owner.family_id,
                owner.membership_id,
                Uuid::new_v4().to_string(),
                "0".repeat(64),
            ],
        )
        .unwrap();
    }
    tx.commit().unwrap();
    drop(connection);

    assert_eq!(
        store
            .gc_causal_media_for_family(&owner.family_id, future_gc_now())
            .unwrap(),
        CAUSAL_MEDIA_GC_BATCH
    );
    assert_eq!(
        super::super::causal_media_staging::test_hook::candidate_inspections(&owner.family_id),
        CAUSAL_MEDIA_GC_SCAN_LIMIT
    );

    let orphan_family = Uuid::new_v4().to_string();
    let now = i64::try_from(
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_secs(),
    )
    .unwrap();
    let mut connection = store.connect().unwrap();
    let tx = connection.transaction().unwrap();
    tx.execute(
        "INSERT INTO families(id, created_at) VALUES (?1, 1)",
        rusqlite::params![orphan_family],
    )
    .unwrap();
    for sequence in 1..=CAUSAL_MEDIA_GC_SCAN_LIMIT as i64 + 8 {
        tx.execute(
            "INSERT INTO causal_media_uploads(
                family_id, sequence, membership_id, media_uuid, created_at, expires_at
             ) VALUES (?1, ?2, 'member', ?3, 0, ?4)",
            rusqlite::params![
                orphan_family,
                sequence,
                Uuid::new_v4().to_string(),
                now.saturating_add(1),
            ],
        )
        .unwrap();
    }
    tx.commit().unwrap();
    drop(connection);
    assert_eq!(
        store
            .gc_causal_media_for_family(&orphan_family, now)
            .unwrap(),
        0
    );
    assert_eq!(
        super::super::causal_media_staging::test_hook::orphan_inspections(&orphan_family),
        CAUSAL_MEDIA_GC_SCAN_LIMIT
    );
}

#[test]
fn causal_media_gc_staging_cursor_survives_restart_and_wraps_safely() {
    let (directory, store, owner) = fixture();
    let mut connection = store.connect().unwrap();
    let tx = connection.transaction().unwrap();
    for index in 0..CAUSAL_MEDIA_GC_SCAN_LIMIT {
        tx.execute(
            "INSERT INTO causal_media_staging(
                family_id, membership_id, media_uuid, sha256, byte_size,
                created_at, expires_at, status, consumed_at
             ) VALUES (?1, ?2, ?3, ?4, 1, 0, 1, 'consumed', ?5)",
            rusqlite::params![
                owner.family_id,
                owner.membership_id,
                format!("retained-{index:04}"),
                "0".repeat(64),
                future_gc_now().saturating_add(1),
            ],
        )
        .unwrap();
    }
    tx.execute(
        "INSERT INTO causal_media_staging(
            family_id, membership_id, media_uuid, sha256, byte_size,
            created_at, expires_at, status, consumed_at
         ) VALUES (?1, ?2, 'zz-eligible', ?3, 1, 0, 1, 'gc_pending', 1)",
        rusqlite::params![owner.family_id, owner.membership_id, "1".repeat(64)],
    )
    .unwrap();
    tx.commit().unwrap();
    drop(connection);

    assert_eq!(
        store
            .gc_causal_media_for_family(&owner.family_id, future_gc_now())
            .unwrap(),
        0,
        "first hard-bounded window contains only retained rows"
    );
    drop(store);

    let restarted = Store::open(directory.path().join("lezi.db")).unwrap();
    assert_eq!(
        restarted
            .gc_causal_media_for_family(&owner.family_id, future_gc_now())
            .unwrap(),
        1,
        "restart lost durable progress past the retained prefix"
    );
    let remaining: i64 = restarted
        .connect()
        .unwrap()
        .query_row(
            "SELECT COUNT(*) FROM causal_media_staging
             WHERE family_id = ?1 AND media_uuid = 'zz-eligible'",
            rusqlite::params![owner.family_id],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(remaining, 0);
}

#[test]
fn causal_media_gc_orphan_cursor_survives_restart_and_collects_reserved_upload() {
    let (directory, store, owner) = fixture();
    let reservation = store
        .reserve_causal_media_upload(&owner, &Uuid::new_v4().to_string(), 100)
        .unwrap();
    fs::create_dir_all(reservation.path().parent().unwrap()).unwrap();
    fs::write(reservation.path(), b"interrupted-upload").unwrap();
    let sequence = reservation.sequence();
    drop(reservation);
    drop(store);

    let restarted = Store::open(directory.path().join("lezi.db")).unwrap();
    assert_eq!(
        restarted
            .gc_causal_media_for_family(&owner.family_id, future_gc_now())
            .unwrap(),
        1
    );
    assert!(!directory
        .path()
        .join("media/.causal-stage")
        .join(&owner.family_id)
        .join(format!(".upload-{sequence}.tmp"))
        .exists());
    let remaining: i64 = restarted
        .connect()
        .unwrap()
        .query_row(
            "SELECT COUNT(*) FROM causal_media_uploads
             WHERE family_id = ?1 AND sequence = ?2",
            rusqlite::params![owner.family_id, sequence],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(remaining, 0);
}

#[test]
fn causal_media_gc_orphan_cursor_advances_past_retained_prefix_after_restart() {
    let (directory, store, owner) = fixture();
    let future = future_gc_now().saturating_add(10_000);
    let mut connection = store.connect().unwrap();
    let tx = connection.transaction().unwrap();
    tx.execute(
        "INSERT OR IGNORE INTO causal_media_gc_state(family_id) VALUES (?1)",
        rusqlite::params![owner.family_id],
    )
    .unwrap();
    for sequence in 1..=CAUSAL_MEDIA_GC_SCAN_LIMIT as i64 {
        tx.execute(
            "INSERT INTO causal_media_uploads(
                family_id, sequence, membership_id, media_uuid, created_at, expires_at
             ) VALUES (?1, ?2, ?3, ?4, 0, ?5)",
            rusqlite::params![
                owner.family_id,
                sequence,
                owner.membership_id,
                Uuid::new_v4().to_string(),
                future,
            ],
        )
        .unwrap();
    }
    let eligible_sequence = CAUSAL_MEDIA_GC_SCAN_LIMIT as i64 + 1;
    tx.execute(
        "INSERT INTO causal_media_uploads(
            family_id, sequence, membership_id, media_uuid, created_at, expires_at
         ) VALUES (?1, ?2, ?3, ?4, 0, 1)",
        rusqlite::params![
            owner.family_id,
            eligible_sequence,
            owner.membership_id,
            Uuid::new_v4().to_string(),
        ],
    )
    .unwrap();
    tx.commit().unwrap();
    drop(connection);
    let eligible_path = directory
        .path()
        .join("media/.causal-stage")
        .join(&owner.family_id)
        .join(format!(".upload-{eligible_sequence}.tmp"));
    fs::create_dir_all(eligible_path.parent().unwrap()).unwrap();
    fs::write(&eligible_path, b"expired").unwrap();

    assert_eq!(
        store
            .gc_causal_media_for_family(&owner.family_id, future_gc_now())
            .unwrap(),
        0
    );
    drop(store);
    let restarted = Store::open(directory.path().join("lezi.db")).unwrap();
    assert_eq!(
        restarted
            .gc_causal_media_for_family(&owner.family_id, future_gc_now())
            .unwrap(),
        1
    );
    assert!(!eligible_path.exists());
}

#[test]
fn causal_media_gc_family_lease_is_single_flight_and_releases_on_drop() {
    let (_directory, store, owner) = fixture();
    let lease = store
        .try_begin_causal_media_gc(&owner.family_id)
        .expect("first family sweep was not admitted");
    assert!(
        store.try_begin_causal_media_gc(&owner.family_id).is_none(),
        "same-family detached sweeps were admitted concurrently"
    );
    drop(lease);
    assert!(store.try_begin_causal_media_gc(&owner.family_id).is_some());
}

#[test]
fn causal_media_gc_global_and_family_leases_are_mutually_exclusive() {
    let (_directory, store, owner) = fixture();
    let family_lease = store
        .try_begin_causal_media_gc(&owner.family_id)
        .expect("family sweep was not admitted");
    assert!(
        store.try_begin_causal_media_gc("").is_none(),
        "global maintenance overlapped an admitted family sweep"
    );
    drop(family_lease);

    let global_lease = store
        .try_begin_causal_media_gc("")
        .expect("global maintenance was not admitted");
    assert!(
        store.try_begin_causal_media_gc(&owner.family_id).is_none(),
        "family sweep overlapped admitted global maintenance"
    );
    drop(global_lease);
    assert!(store.try_begin_causal_media_gc(&owner.family_id).is_some());
}

#[test]
fn causal_media_gc_does_not_claim_expired_writing_row_with_active_upload_owner() {
    let (directory, store, owner) = fixture();
    let media_uuid = Uuid::new_v4().to_string();
    let reservation = store
        .reserve_causal_media_upload(&owner, &media_uuid, 100)
        .unwrap();
    store
        .stage_test_preimage(
            &owner,
            &media_uuid,
            b"owned",
            &digest(b"owned"),
            100,
            limits(),
        )
        .unwrap();
    let staged = directory
        .path()
        .join("media/.causal-stage")
        .join(&owner.family_id)
        .join(&media_uuid);

    assert_eq!(
        store
            .gc_causal_media_for_family(&owner.family_id, 111)
            .unwrap(),
        0
    );
    assert!(staged.is_file(), "active writer bytes were collected");

    store
        .complete_causal_media_upload(&owner.family_id, reservation.sequence())
        .unwrap();
    assert_eq!(
        store
            .gc_causal_media_for_family(&owner.family_id, 111)
            .unwrap(),
        1
    );
    assert!(!staged.exists());
}

#[test]
fn causal_media_startup_promotion_skips_terminal_retained_history() {
    let (directory, store, owner) = fixture();
    let media_uuid = Uuid::new_v4().to_string();
    let published = seed_consumed_preimage(&directory, &store, &owner, &media_uuid, b"done", 100);
    fs::write(&published, b"corrupt-history-must-not-be-rehashed").unwrap();
    store
        .connect()
        .unwrap()
        .execute(
            "UPDATE causal_media_staging SET publication_confirmed = 1
              WHERE family_id = ?1 AND media_uuid = ?2",
            rusqlite::params![owner.family_id, media_uuid],
        )
        .unwrap();

    store.promote_consumed_causal_media().unwrap();
}

#[test]
fn causal_media_upload_history_cap_triggers_bounded_expired_cleanup_before_reserve() {
    let (_directory, store, owner) = fixture();
    let mut connection = store.connect().unwrap();
    let tx = connection.transaction().unwrap();
    for sequence in 1..=CAUSAL_MEDIA_GC_SCAN_LIMIT as i64 {
        let expires_at = if sequence <= 400 { 1 } else { 100 };
        tx.execute(
            "INSERT INTO causal_media_uploads(
                family_id, sequence, membership_id, media_uuid, created_at, expires_at
             ) VALUES (?1, ?2, ?3, ?4, 0, ?5)",
            rusqlite::params![
                owner.family_id,
                sequence,
                owner.membership_id,
                Uuid::new_v4().to_string(),
                expires_at,
            ],
        )
        .unwrap();
    }
    tx.execute(
        "INSERT OR IGNORE INTO causal_media_gc_state(family_id) VALUES (?1)",
        rusqlite::params![owner.family_id],
    )
    .unwrap();
    tx.execute(
        "UPDATE causal_media_gc_state
            SET next_upload_sequence = ?1, orphan_after_sequence = 400
          WHERE family_id = ?2",
        rusqlite::params![CAUSAL_MEDIA_GC_SCAN_LIMIT as i64 + 1, owner.family_id],
    )
    .unwrap();
    tx.commit().unwrap();
    drop(connection);

    let reservation = store
        .reserve_causal_media_upload(&owner, &Uuid::new_v4().to_string(), 2)
        .expect("expired history cap did not self-heal with bounded cleanup");
    assert_eq!(
        reservation.sequence(),
        CAUSAL_MEDIA_GC_SCAN_LIMIT as i64 + 1
    );
    let remaining: i64 = store
        .connect()
        .unwrap()
        .query_row(
            "SELECT COUNT(*) FROM causal_media_uploads WHERE family_id = ?1",
            rusqlite::params![owner.family_id],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(remaining, CAUSAL_MEDIA_GC_SCAN_LIMIT as i64 - 7);
}

#[test]
fn causal_media_startup_promotion_is_hard_batched_and_restart_progresses() {
    let (directory, store, owner) = fixture();
    let family_media = directory.path().join("media").join(&owner.family_id);
    fs::create_dir_all(&family_media).unwrap();
    let mut connection = store.connect().unwrap();
    let tx = connection.transaction().unwrap();
    for index in 0..=CAUSAL_MEDIA_GC_BATCH {
        let media_uuid = format!("pending-{index:04}");
        tx.execute(
            "INSERT INTO causal_media_staging(
                family_id, membership_id, media_uuid, sha256, byte_size,
                created_at, expires_at, status, consumed_at
             ) VALUES (?1, ?2, ?3, ?4, 1, 0, 1, 'consumed', 1)",
            rusqlite::params![
                owner.family_id,
                owner.membership_id,
                media_uuid,
                digest(b"x"),
            ],
        )
        .unwrap();
        fs::write(family_media.join(format!("pending-{index:04}")), b"x").unwrap();
    }
    tx.commit().unwrap();
    drop(connection);

    assert!(matches!(
        store.promote_consumed_causal_media(),
        Err(StoreError::InvalidCausalMediaStaging)
    ));
    let pending: i64 = store
        .connect()
        .unwrap()
        .query_row(
            "SELECT COUNT(*) FROM causal_media_staging
              WHERE status = 'consumed' AND publication_confirmed = 0",
            [],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(pending, 1);
    store.promote_consumed_causal_media().unwrap();
}

fn consume_family_log_media(
    store: &Store,
    owner: &Principal,
    bytes: &[u8],
) -> (Uuid, CausalMediaItem) {
    let baby_id = Uuid::new_v4();
    publish_root(
        store,
        owner,
        entity(
            "baby",
            baby_id,
            1,
            json!({
                "nickname":"年年","sex":"female","birthday":"2025-01-02",
                "avatar_media_uuid":null,"birth_weight_grams":3200
            }),
        ),
        10,
    )
    .unwrap();
    let media_id = Uuid::new_v4();
    let item = stage_log_media(store, owner, media_id, bytes);
    let record_id = Uuid::new_v4();
    publish_root_with_media(
        store,
        owner,
        entity(
            "record",
            record_id,
            2,
            json!({
                "baby_client_uuid":baby_id,
                "type":"diary",
                "custom_item_client_uuid":null,
                "timestamp":100,
                "end_timestamp":null,
                "note":null,
                "payload_json":{"body":"photo"},
                "schema_version":2
            }),
        ),
        vec![item.clone()],
        10,
    )
    .unwrap();
    (media_id, item)
}

#[test]
fn empty_put_binds_new_uuid_to_same_family_consumed_sha() {
    let (directory, store, owner) = fixture();
    let bytes = b"abc";
    let (donor_id, donor) = consume_family_log_media(&store, &owner, bytes);
    let new_id = Uuid::new_v4();

    let bound = store
        .bind_empty_causal_media_preimage(
            &owner,
            &new_id.to_string(),
            &donor.sha256,
            1_700_000_000,
            limits(),
        )
        .unwrap();

    assert_eq!(bound.media_uuid, new_id.to_string());
    assert_eq!(bound.status, "staged");
    assert_eq!(bound.sha256, donor.sha256);
    assert_eq!(bound.byte_size, bytes.len());
    let donor_published = directory
        .path()
        .join("media")
        .join(&owner.family_id)
        .join(donor_id.to_string());
    let bound_staged = directory
        .path()
        .join("media/.causal-stage")
        .join(&owner.family_id)
        .join(new_id.to_string());
    assert_eq!(fs::read(&donor_published).unwrap(), bytes);
    assert_eq!(fs::read(&bound_staged).unwrap(), bytes);
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        assert_eq!(
            fs::metadata(&donor_published).unwrap().ino(),
            fs::metadata(&bound_staged).unwrap().ino(),
            "bind must hard-link, not copy, the consumed family blob"
        );
    }

    let baby_id = Uuid::new_v4();
    publish_root(
        &store,
        &owner,
        entity(
            "baby",
            baby_id,
            3,
            json!({
                "nickname":"豆豆","sex":"male","birthday":"2025-02-02",
                "avatar_media_uuid":null,"birth_weight_grams":3100
            }),
        ),
        10,
    )
    .unwrap();
    let record_id = Uuid::new_v4();
    let accepted = publish_root_with_media(
        &store,
        &owner,
        entity(
            "record",
            record_id,
            4,
            json!({
                "baby_client_uuid":baby_id,
                "type":"diary",
                "custom_item_client_uuid":null,
                "timestamp":200,
                "end_timestamp":null,
                "note":null,
                "payload_json":{"body":"clone"},
                "schema_version":2
            }),
        ),
        vec![CausalMediaItem {
            media_uuid: new_id.to_string(),
            ..donor
        }],
        10,
    )
    .unwrap();
    assert_eq!(accepted.status, "committed");
    assert_eq!(accepted.applied, 1);
}

#[test]
fn empty_put_does_not_bind_cross_family_consumed_sha() {
    let (_directory, store, owner) = fixture();
    let bytes = b"abc";
    let (_donor_id, donor) = consume_family_log_media(&store, &owner, bytes);
    let other_family = Uuid::new_v4().to_string();
    store
        .connect()
        .unwrap()
        .execute(
            "INSERT INTO families(id, created_at) VALUES (?1, 1)",
            rusqlite::params![other_family],
        )
        .unwrap();
    let stranger = owner_principal(&other_family);
    register_test_principal(&store, &stranger);
    let new_id = Uuid::new_v4();

    assert!(matches!(
        store.bind_empty_causal_media_preimage(
            &stranger,
            &new_id.to_string(),
            &donor.sha256,
            1_700_000_000,
            limits(),
        ),
        Err(StoreError::InvalidCausalMediaStaging)
    ));
    let count: i64 = store
        .connect()
        .unwrap()
        .query_row(
            "SELECT COUNT(*) FROM causal_media_staging
              WHERE family_id = ?1 AND media_uuid = ?2",
            rusqlite::params![other_family, new_id.to_string()],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(count, 0);
}

#[test]
fn empty_put_fails_closed_when_family_has_no_consumed_sha() {
    let (_directory, store, owner) = fixture();
    let new_id = Uuid::new_v4();

    assert!(matches!(
        store.bind_empty_causal_media_preimage(
            &owner,
            &new_id.to_string(),
            &digest(b"abc"),
            1_700_000_000,
            limits(),
        ),
        Err(StoreError::InvalidCausalMediaStaging)
    ));
}

#[test]
fn empty_put_fails_closed_when_consumed_blob_is_unreadable() {
    let (directory, store, owner) = fixture();
    let bytes = b"abc";
    let (donor_id, donor) = consume_family_log_media(&store, &owner, bytes);
    fs::remove_file(
        directory
            .path()
            .join("media")
            .join(&owner.family_id)
            .join(donor_id.to_string()),
    )
    .unwrap();
    let new_id = Uuid::new_v4();

    assert!(matches!(
        store.bind_empty_causal_media_preimage(
            &owner,
            &new_id.to_string(),
            &donor.sha256,
            1_700_000_000,
            limits(),
        ),
        Err(StoreError::InvalidCausalMediaStaging)
    ));
    let count: i64 = store
        .connect()
        .unwrap()
        .query_row(
            "SELECT COUNT(*) FROM causal_media_staging
              WHERE family_id = ?1 AND media_uuid = ?2",
            rusqlite::params![owner.family_id, new_id.to_string()],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(count, 0);
}

#[test]
fn empty_put_same_uuid_sha_replays_existing_receipt_without_a_second_copy() {
    let (directory, store, owner) = fixture();
    let bytes = b"abc";
    let (media_id, item) = consume_family_log_media(&store, &owner, bytes);
    let published = directory
        .path()
        .join("media")
        .join(&owner.family_id)
        .join(media_id.to_string());
    let inode_before = {
        #[cfg(unix)]
        {
            use std::os::unix::fs::MetadataExt;
            fs::metadata(&published).unwrap().ino()
        }
        #[cfg(not(unix))]
        0u64
    };

    let replayed = store
        .bind_empty_causal_media_preimage(
            &owner,
            &media_id.to_string(),
            &item.sha256,
            1_700_000_000,
            limits(),
        )
        .unwrap();

    assert_eq!(replayed.media_uuid, media_id.to_string());
    assert_eq!(replayed.status, "consumed");
    assert_eq!(replayed.sha256, item.sha256);
    assert_eq!(replayed.byte_size, bytes.len());
    assert_eq!(fs::read(&published).unwrap(), bytes);
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        assert_eq!(fs::metadata(&published).unwrap().ino(), inode_before);
    }
}

#[test]
fn empty_put_fails_closed_when_hard_link_cannot_be_created() {
    let (directory, store, owner) = fixture();
    let bytes = b"abc";
    let (_donor_id, donor) = consume_family_log_media(&store, &owner, bytes);
    let new_id = Uuid::new_v4();
    let destination = directory
        .path()
        .join("media/.causal-stage")
        .join(&owner.family_id)
        .join(new_id.to_string());
    fs::create_dir_all(destination.parent().unwrap()).unwrap();
    fs::write(&destination, b"blocker").unwrap();

    assert!(matches!(
        store.bind_empty_causal_media_preimage(
            &owner,
            &new_id.to_string(),
            &donor.sha256,
            1_700_000_000,
            limits(),
        ),
        Err(StoreError::InvalidCausalMediaStaging)
    ));
    assert_eq!(fs::read(&destination).unwrap(), b"blocker");
    let count: i64 = store
        .connect()
        .unwrap()
        .query_row(
            "SELECT COUNT(*) FROM causal_media_staging
              WHERE family_id = ?1 AND media_uuid = ?2",
            rusqlite::params![owner.family_id, new_id.to_string()],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(count, 0);
}

#[test]
fn empty_put_fails_closed_when_same_uuid_sha_mismatches() {
    let (_directory, store, owner) = fixture();
    let bytes = b"abc";
    let (media_id, item) = consume_family_log_media(&store, &owner, bytes);
    let wrong_sha = digest(b"xyz");
    assert_ne!(wrong_sha, item.sha256);

    assert!(matches!(
        store.bind_empty_causal_media_preimage(
            &owner,
            &media_id.to_string(),
            &wrong_sha,
            1_700_000_000,
            limits(),
        ),
        Err(StoreError::CausalMediaPreimageConflict)
    ));
    let (count, sha): (i64, String) = store
        .connect()
        .unwrap()
        .query_row(
            "SELECT COUNT(*), sha256 FROM causal_media_staging
              WHERE family_id = ?1 AND media_uuid = ?2",
            rusqlite::params![owner.family_id, media_id.to_string()],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .unwrap();
    assert_eq!(count, 1);
    assert_eq!(sha, item.sha256);
}

#[test]
fn empty_put_fails_closed_when_same_uuid_stored_size_does_not_match_file() {
    let (directory, store, owner) = fixture();
    let bytes = b"abc";
    let (media_id, item) = consume_family_log_media(&store, &owner, bytes);
    fs::write(
        directory
            .path()
            .join("media")
            .join(&owner.family_id)
            .join(media_id.to_string()),
        b"abcd",
    )
    .unwrap();

    assert!(matches!(
        store.bind_empty_causal_media_preimage(
            &owner,
            &media_id.to_string(),
            &item.sha256,
            1_700_000_000,
            limits(),
        ),
        Err(StoreError::InvalidCausalMediaStaging)
    ));
    let count: i64 = store
        .connect()
        .unwrap()
        .query_row(
            "SELECT COUNT(*) FROM causal_media_staging
              WHERE family_id = ?1 AND media_uuid = ?2",
            rusqlite::params![owner.family_id, media_id.to_string()],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(count, 1);
}

mod renewal_tests;
