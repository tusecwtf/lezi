//! Expired exact-byte PUT recovery must renew admission, never revive GC or care data.

use super::*;
use crate::store::causal_media_staging::test_hook;
use rusqlite::params;

fn receipt_state(store: &Store, owner: &Principal, media: &str) -> (String, i64, i64) {
    store
        .connect()
        .unwrap()
        .query_row(
            "SELECT status, created_at, expires_at FROM causal_media_staging
         WHERE family_id = ?1 AND media_uuid = ?2",
            params![owner.family_id, media],
            |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
        )
        .unwrap()
}

fn staged_path(directory: &TempDir, owner: &Principal, media: &str) -> std::path::PathBuf {
    directory
        .path()
        .join("media/.causal-stage")
        .join(&owner.family_id)
        .join(media)
}

#[test]
fn expired_preimage_renewal_repairs_exact_bytes_at_expiry_without_publishing() {
    for old_status in ["writing", "staged"] {
        let (directory, store, owner) = fixture();
        let media = Uuid::new_v4().to_string();
        let bytes = b"abc";
        store
            .stage_test_preimage(&owner, &media, bytes, &digest(bytes), 100, limits())
            .unwrap();
        store.connect().unwrap().execute(
            "UPDATE causal_media_staging SET status = ?1 WHERE family_id = ?2 AND media_uuid = ?3",
            params![old_status, owner.family_id, media],
        ).unwrap();
        // A writing crash or missing local copy must be repaired by this actual PUT.
        fs::remove_file(staged_path(&directory, &owner, &media)).unwrap();
        let renewed = store
            .stage_test_preimage(&owner, &media, bytes, &digest(bytes), 110, limits())
            .expect("expired exact-byte preimage must admit a fresh bounded PUT");
        assert_eq!(renewed.status, "staged");
        assert_eq!(renewed.expires_at, 120);
        assert_eq!(renewed.sha256, digest(bytes));
        assert_eq!(renewed.byte_size, bytes.len());
        assert_eq!(
            receipt_state(&store, &owner, &media),
            ("staged".to_owned(), 100, 120)
        );
        assert_eq!(
            fs::read(staged_path(&directory, &owner, &media)).unwrap(),
            bytes
        );
        assert!(!directory
            .path()
            .join("media")
            .join(&owner.family_id)
            .join(&media)
            .exists());
        // An ordinary replay must not extend TTL again or consume another slot.
        let replay = store
            .stage_test_preimage(&owner, &media, bytes, &digest(bytes), 111, limits())
            .unwrap();
        assert_eq!(replay, renewed);
        assert_eq!(store.gc_causal_media(110).unwrap(), 0);
        assert_eq!(store.gc_causal_media(120).unwrap(), 1);
    }
}

#[test]
fn expired_preimage_renewal_recounts_membership_family_and_byte_quotas() {
    for (kind, expected) in [
        ("membership", "membership_count"),
        ("family", "family_count"),
        ("bytes", "family_bytes"),
    ] {
        let (directory, store, owner) = fixture();
        let media = Uuid::new_v4().to_string();
        let competitor = Uuid::new_v4().to_string();
        let mut bound = limits();
        if kind != "membership" {
            bound.max_membership_count = 2;
        }
        if kind == "family" {
            bound.max_family_count = 1;
        }
        store
            .stage_test_preimage(&owner, &media, b"abc", &digest(b"abc"), 100, bound)
            .unwrap();
        store
            .stage_test_preimage(&owner, &competitor, b"def", &digest(b"def"), 110, bound)
            .unwrap();
        let before = receipt_state(&store, &owner, &media);
        let result = store.stage_test_preimage(&owner, &media, b"abc", &digest(b"abc"), 110, bound);
        assert!(
            matches!(result, Err(StoreError::CausalMediaStagingQuota(code)) if code == expected),
            "{kind}: {result:?}"
        );
        assert_eq!(receipt_state(&store, &owner, &media), before);
        assert_eq!(
            fs::read(staged_path(&directory, &owner, &media)).unwrap(),
            b"abc"
        );
    }
}

#[test]
fn expired_preimage_renewal_rejects_byte_and_principal_rebinding() {
    let (directory, store, owner) = fixture();
    let media = Uuid::new_v4().to_string();
    store
        .stage_test_preimage(&owner, &media, b"abc", &digest(b"abc"), 100, limits())
        .unwrap();
    for bytes in [b"xyz".as_slice(), b"abcd".as_slice()] {
        assert!(matches!(
            store.stage_test_preimage(&owner, &media, bytes, &digest(bytes), 110, limits()),
            Err(StoreError::CausalMediaPreimageConflict)
        ));
    }
    let peer = Principal {
        membership_id: "peer-member".to_owned(),
        device_id: "peer-device".to_owned(),
        role: "member".to_owned(),
        ..owner.clone()
    };
    register_test_principal(&store, &peer);
    assert!(matches!(
        store.stage_test_preimage(&peer, &media, b"abc", &digest(b"abc"), 110, limits()),
        Err(StoreError::CausalMediaMembershipMismatch)
    ));
    let wrong_device = Principal {
        device_id: peer.device_id.clone(),
        ..owner.clone()
    };
    assert!(matches!(
        store.stage_test_preimage(
            &wrong_device,
            &media,
            b"abc",
            &digest(b"abc"),
            110,
            limits()
        ),
        Err(StoreError::DeviceRemoved)
    ));
    let wrong_family = Principal {
        family_id: Uuid::new_v4().to_string(),
        ..owner.clone()
    };
    assert!(matches!(
        store.stage_test_preimage(
            &wrong_family,
            &media,
            b"abc",
            &digest(b"abc"),
            110,
            limits()
        ),
        Err(StoreError::MembershipDeleted)
    ));
    assert_eq!(
        receipt_state(&store, &owner, &media),
        ("staged".to_owned(), 100, 110)
    );
    assert_eq!(
        fs::read(staged_path(&directory, &owner, &media)).unwrap(),
        b"abc"
    );
}

#[test]
fn expired_preimage_renewal_cannot_revive_gc_or_referenced_rows() {
    for reference in [
        "gc_pending",
        "live",
        "publication",
        "version",
        "consumed_at",
    ] {
        let (directory, store, owner) = fixture();
        let media = Uuid::new_v4().to_string();
        store
            .stage_test_preimage(&owner, &media, b"abc", &digest(b"abc"), 100, limits())
            .unwrap();
        let connection = store.connect().unwrap();
        match reference {
            "gc_pending" => {
                connection.execute("UPDATE causal_media_staging SET status='gc_pending' WHERE family_id=?1 AND media_uuid=?2", params![owner.family_id,media]).unwrap();
            }
            "live" => {
                connection.execute("INSERT INTO entities(family_id,entity_type,client_uuid,updated_at,payload_json,rev) VALUES (?1,'media',?2,100,'{}',1)",params![owner.family_id,media]).unwrap();
            }
            "publication" => {
                connection.execute("INSERT INTO media_publications(family_id,media_uuid,source) VALUES (?1,?2,'ordinary')",params![owner.family_id,media]).unwrap();
            }
            "version" => {
                let version = Uuid::new_v4().to_string();
                connection.execute("INSERT INTO entity_versions(family_id,version_id,entity_type,client_uuid,updated_at,payload_json,content_hash,mutation_id,origin,created_at) VALUES (?1,?2,'record',?3,100,'{}',?4,'renew-audit','branched',100)",params![owner.family_id,version,Uuid::new_v4().to_string(),"0".repeat(64)]).unwrap();
                connection.execute("INSERT INTO entity_version_media(family_id,version_id,media_uuid,media_payload_json,content_hash) VALUES (?1,?2,?3,'{}',?4)",params![owner.family_id,version,media,"1".repeat(64)]).unwrap();
            }
            "consumed_at" => {
                connection.execute("UPDATE causal_media_staging SET consumed_at=101 WHERE family_id=?1 AND media_uuid=?2",params![owner.family_id,media]).unwrap();
            }
            _ => unreachable!(),
        }
        drop(connection);
        let published = directory
            .path()
            .join("media")
            .join(&owner.family_id)
            .join(&media);
        fs::create_dir_all(published.parent().unwrap()).unwrap();
        fs::write(&published, b"care-source-copy").unwrap();
        let before = receipt_state(&store, &owner, &media);
        assert!(
            matches!(
                store.stage_test_preimage(&owner, &media, b"abc", &digest(b"abc"), 110, limits()),
                Err(StoreError::InvalidCausalMediaStaging)
            ),
            "{reference}"
        );
        assert_eq!(receipt_state(&store, &owner, &media), before);
        assert_eq!(
            fs::read(staged_path(&directory, &owner, &media)).unwrap(),
            b"abc"
        );
        assert_eq!(fs::read(published).unwrap(), b"care-source-copy");
    }
}

#[test]
fn expired_preimage_renewal_preserves_consumed_replay_and_canonical_bytes() {
    let (directory, store, owner) = fixture();
    let media = Uuid::new_v4().to_string();
    let canonical = seed_consumed_preimage(&directory, &store, &owner, &media, b"abc", 101);
    let no_capacity = CausalMediaStagingLimits {
        max_membership_count: 0,
        max_family_count: 0,
        max_family_bytes: 0,
        ..limits()
    };
    let replay = store
        .stage_test_preimage(&owner, &media, b"abc", &digest(b"abc"), 110, no_capacity)
        .unwrap();
    assert_eq!(replay.status, "consumed");
    assert_eq!(replay.expires_at, 110);
    assert_eq!(
        receipt_state(&store, &owner, &media),
        ("consumed".to_owned(), 100, 110)
    );
    assert_eq!(fs::read(canonical).unwrap(), b"abc");
    assert!(!staged_path(&directory, &owner, &media).exists());
}

#[test]
fn expired_preimage_renewal_concurrent_exact_puts_share_one_durable_slot() {
    let (directory, store, owner) = fixture();
    let media = Uuid::new_v4().to_string();
    store
        .stage_test_preimage(&owner, &media, b"abc", &digest(b"abc"), 100, limits())
        .unwrap();
    let concurrent_store = store.clone();
    let concurrent_owner = owner.clone();
    let concurrent_media = media.clone();
    test_hook::after_stage_file_once(&owner.family_id, move || {
        // Deterministically complete the second PUT while the first is between install and promotion.
        assert_eq!(
            receipt_state(&concurrent_store, &concurrent_owner, &concurrent_media),
            ("writing".to_owned(), 100, 120)
        );
        assert_eq!(concurrent_store.gc_causal_media(110).unwrap(), 0);
        let second = concurrent_store
            .stage_test_preimage(
                &concurrent_owner,
                &concurrent_media,
                b"abc",
                &digest(b"abc"),
                110,
                limits(),
            )
            .unwrap();
        assert_eq!(second.expires_at, 120);
    });
    let first = store
        .stage_test_preimage(&owner, &media, b"abc", &digest(b"abc"), 110, limits())
        .unwrap();
    assert_eq!(first.expires_at, 120);
    assert_eq!(
        receipt_state(&store, &owner, &media),
        ("staged".to_owned(), 100, 120)
    );
    assert_eq!(
        fs::read(staged_path(&directory, &owner, &media)).unwrap(),
        b"abc"
    );
    assert!(matches!(
        store.stage_test_preimage(
            &owner,
            &Uuid::new_v4().to_string(),
            b"x",
            &digest(b"x"),
            111,
            limits()
        ),
        Err(StoreError::CausalMediaStagingQuota("membership_count"))
    ));
}

#[test]
fn expired_preimage_renewal_rechecks_revoked_identity_before_completion() {
    for revoke_membership in [false, true] {
        let (directory, store, owner) = fixture();
        let media = Uuid::new_v4().to_string();
        store
            .stage_test_preimage(&owner, &media, b"abc", &digest(b"abc"), 100, limits())
            .unwrap();
        let revoke_store = store.clone();
        let revoke_owner = owner.clone();
        test_hook::after_stage_file_once(&owner.family_id, move || {
            let connection = revoke_store.connect().unwrap();
            if revoke_membership {
                connection
                    .execute(
                        "UPDATE memberships SET left_at=110 WHERE membership_id=?1",
                        params![revoke_owner.membership_id],
                    )
                    .unwrap();
            } else {
                connection
                    .execute(
                        "UPDATE devices SET status='revoked' WHERE device_id=?1",
                        params![revoke_owner.device_id],
                    )
                    .unwrap();
            }
        });
        let result =
            store.stage_test_preimage(&owner, &media, b"abc", &digest(b"abc"), 110, limits());
        if revoke_membership {
            assert!(matches!(result, Err(StoreError::MembershipDeleted)));
        } else {
            assert!(matches!(result, Err(StoreError::DeviceRemoved)));
        }
        assert_eq!(
            receipt_state(&store, &owner, &media),
            ("writing".to_owned(), 100, 120)
        );
        assert_eq!(
            fs::read(staged_path(&directory, &owner, &media)).unwrap(),
            b"abc"
        );
    }
}

#[test]
fn expired_preimage_renewal_requires_full_bytes_and_stays_family_scoped() {
    let (directory, store, owner) = fixture();
    let media = Uuid::new_v4().to_string();
    store
        .stage_test_preimage(&owner, &media, b"abc", &digest(b"abc"), 100, limits())
        .unwrap();
    assert!(matches!(
        store.bind_empty_causal_media_preimage(&owner, &media, &digest(b"abc"), 110, limits()),
        Err(StoreError::InvalidCausalMediaStaging)
    ));
    assert_eq!(
        receipt_state(&store, &owner, &media),
        ("staged".to_owned(), 100, 110)
    );
    let peer_family = Uuid::new_v4().to_string();
    store
        .connect()
        .unwrap()
        .execute(
            "INSERT INTO families(id,created_at) VALUES (?1,100)",
            params![peer_family],
        )
        .unwrap();
    let peer = Principal {
        family_id: peer_family,
        membership_id: "other-family-member".to_owned(),
        device_id: "other-family-device".to_owned(),
        role: "owner".to_owned(),
    };
    register_test_principal(&store, &peer);
    store
        .stage_test_preimage(&peer, &media, b"xyz", &digest(b"xyz"), 110, limits())
        .unwrap();
    store
        .stage_test_preimage(&owner, &media, b"abc", &digest(b"abc"), 110, limits())
        .unwrap();
    assert_eq!(
        fs::read(staged_path(&directory, &owner, &media)).unwrap(),
        b"abc"
    );
    assert_eq!(
        fs::read(staged_path(&directory, &peer, &media)).unwrap(),
        b"xyz"
    );
}

#[test]
fn expired_preimage_renewal_does_not_complete_a_superseded_expiry_generation() {
    let (directory, store, owner) = fixture();
    let media = Uuid::new_v4().to_string();
    store
        .stage_test_preimage(&owner, &media, b"abc", &digest(b"abc"), 100, limits())
        .unwrap();
    let newer_store = store.clone();
    let newer_owner = owner.clone();
    let newer_media = media.clone();
    test_hook::after_stage_file_once(&owner.family_id, move || {
        let newer = newer_store
            .stage_test_preimage(
                &newer_owner,
                &newer_media,
                b"abc",
                &digest(b"abc"),
                120,
                limits(),
            )
            .unwrap();
        assert_eq!(newer.expires_at, 130);
    });
    assert!(matches!(
        store.stage_test_preimage(&owner, &media, b"abc", &digest(b"abc"), 110, limits()),
        Err(StoreError::InvalidCausalMediaStaging)
    ));
    assert_eq!(
        receipt_state(&store, &owner, &media),
        ("staged".to_owned(), 100, 130)
    );
    assert_eq!(
        fs::read(staged_path(&directory, &owner, &media)).unwrap(),
        b"abc"
    );
}
