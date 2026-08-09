//! Public Store seam regressions for causal media resource bounds and recovery.

use std::fs;

use sha2::{Digest, Sha256};
use tempfile::TempDir;
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
fn causal_media_staging_enforces_file_membership_family_and_byte_quotas() {
    let (_directory, store, owner) = fixture();
    let first = Uuid::new_v4().to_string();
    store
        .stage_causal_media_preimage(&owner, &first, b"abc", &digest(b"abc"), 100, limits())
        .unwrap();
    // Exact replay is idempotent and does not consume another quota slot.
    store
        .stage_causal_media_preimage(&owner, &first, b"abc", &digest(b"abc"), 101, limits())
        .unwrap();
    let peer = Principal {
        membership_id: "m-peer".to_owned(),
        device_id: "d-peer".to_owned(),
        ..owner.clone()
    };
    assert!(matches!(
        store.stage_causal_media_preimage(&peer, &first, b"abc", &digest(b"abc"), 101, limits(),),
        Err(StoreError::CausalMediaMembershipMismatch)
    ));
    assert!(matches!(
        store.stage_causal_media_preimage(
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
        .stage_causal_media_preimage(
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
        store.stage_causal_media_preimage(
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
        .stage_causal_media_preimage(
            &owner,
            &Uuid::new_v4().to_string(),
            b"abcd",
            &digest(b"abcd"),
            100,
            byte_limits,
        )
        .unwrap();
    assert!(matches!(
        store.stage_causal_media_preimage(
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
        store.stage_causal_media_preimage(
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
fn causal_media_gc_expires_metadata_bytes_and_untracked_crash_files() {
    let (directory, store, owner) = fixture();
    let media_id = Uuid::new_v4().to_string();
    store
        .stage_causal_media_preimage(&owner, &media_id, b"abc", &digest(b"abc"), 100, limits())
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
        .stage_causal_media_preimage(&owner, &media_id, b"xyz", &digest(b"xyz"), 111, limits())
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
