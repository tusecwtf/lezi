//! Public Store seam regressions for causal media resource bounds and recovery.

use std::fs;

use sha2::{Digest, Sha256};
use tempfile::{NamedTempFile, TempDir};
use uuid::Uuid;

use super::super::*;
use super::test_support::{family, owner_principal};

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

trait TestCausalMediaStage {
    fn stage_test_preimage(
        &self,
        principal: &Principal,
        media_uuid: &str,
        bytes: &[u8],
        expected_sha256: &str,
        now: i64,
        limits: CausalMediaStagingLimits,
    ) -> Result<CausalMediaStageStatus, StoreError>;
}

impl TestCausalMediaStage for Store {
    fn stage_test_preimage(
        &self,
        principal: &Principal,
        media_uuid: &str,
        bytes: &[u8],
        expected_sha256: &str,
        now: i64,
        limits: CausalMediaStagingLimits,
    ) -> Result<CausalMediaStageStatus, StoreError> {
        let incoming = NamedTempFile::new().unwrap();
        fs::write(incoming.path(), bytes).unwrap();
        let verified = VerifiedCausalMediaPreimage::verify(
            incoming.path().to_owned(),
            expected_sha256,
            limits.max_file_bytes,
        )?;
        self.stage_verified_causal_media_preimage(principal, media_uuid, &verified, now, limits)
    }
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
        ..owner.clone()
    };
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
        ..owner.clone()
    };
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
fn causal_media_gc_expires_metadata_bytes_and_untracked_crash_files() {
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
    let orphan = family_stage.join("interrupted.tmp");
    fs::write(&orphan, b"orphan").unwrap();

    assert_eq!(store.gc_expired_causal_media_preimages(109).unwrap(), 0);
    assert!(staged.exists());
    assert!(!orphan.exists(), "untracked crash file survived staging GC");
    assert_eq!(store.gc_expired_causal_media_preimages(110).unwrap(), 1);
    assert!(!staged.exists());

    // Expired UUID ownership is released only after metadata and bytes are gone.
    let replay = store
        .stage_test_preimage(&owner, &media_id, b"xyz", &digest(b"xyz"), 111, limits())
        .unwrap();
    assert_eq!(replay.sha256, digest(b"xyz"));
}

#[test]
fn causal_media_family_scoped_gc_does_not_touch_peer_family_files() {
    let (directory, store, owner) = fixture();
    let peer_family_id = Uuid::new_v4().to_string();
    let peer_stage = directory
        .path()
        .join("media/.causal-stage")
        .join(&peer_family_id);
    fs::create_dir_all(&peer_stage).unwrap();
    let peer_orphan = peer_stage.join("interrupted.tmp");
    fs::write(&peer_orphan, b"peer-crash-bytes").unwrap();

    assert_eq!(
        store
            .gc_expired_causal_media_preimages_for_family(&owner.family_id, 100)
            .unwrap(),
        0
    );
    assert!(
        peer_orphan.exists(),
        "a family-scoped sweep crossed the caller's family lock"
    );

    assert_eq!(store.gc_expired_causal_media_preimages(100).unwrap(), 0);
    assert!(
        !peer_orphan.exists(),
        "startup's global sweep did not collect the crash orphan"
    );
}
