use super::*;
use crate::model::Entity;

// ADR-0025: deletion changes identity metadata even inside durable history,
// while version identities, nursing facts and exact request replay survive.
fn register_principal(store: &Store, principal: &Principal) {
    let connection = store.connect().unwrap();
    connection.execute(
        "INSERT OR IGNORE INTO memberships(membership_id, family_id, role, display_name, display_name_key)
         VALUES (?1, ?2, ?3, ?1, ?1)",
        params![principal.membership_id, principal.family_id, principal.role],
    ).unwrap();
    connection.execute(
        "INSERT OR IGNORE INTO devices(device_id, membership_id, device_name, device_name_key, status, created_at, last_used_at)
         VALUES (?1, ?2, 'historical device', 'historical device', 'active', 1, 1)",
        params![principal.device_id, principal.membership_id],
    ).unwrap();
}

fn register_deletable_member(fx: &CausalFx) {
    register_principal(&fx.store, &fx.owner);
    register_principal(&fx.store, &fx.member);
}

#[test]
fn identity_deletion_redacts_immutable_versions_and_exact_owner_receipt_replay() {
    let fx = CausalFx::new();
    register_deletable_member(&fx);
    let record = Uuid::new_v4();
    let created = fx
        .commit(
            &fx.member,
            fx.record_mutation(record, None, "care retained"),
            1_700_000_000,
        )
        .unwrap();
    let base = created.results[0].stable_version_id.clone().unwrap();
    let mut update = fx.record_mutation(record, Some(&base), "owner care retained");
    let mut media = CausalMediaItem {
        media_uuid: Uuid::new_v4().to_string(),
        role: "log".to_owned(),
        sha256: String::new(),
        byte_size: 13,
        mime: "image/jpeg".to_owned(),
        width: Some(2),
        height: Some(3),
    };
    fx.stage_media_bytes(&mut media);
    update.media = vec![media.clone()];
    let accepted = fx.commit(&fx.owner, update.clone(), 1_700_000_001).unwrap();
    let version = accepted.results[0].stable_version_id.clone().unwrap();
    assert_eq!(
        accepted.results[0].stable_root["created_by_membership_id"],
        fx.member.membership_id
    );
    fx.store
        .hard_delete_membership(&fx.owner, &fx.member.membership_id, 1_700_000_002)
        .unwrap();

    let replay = fx.commit(&fx.owner, update.clone(), 1_700_000_003).unwrap();
    assert_eq!(
        replay.results[0].stable_root["created_by_membership_id"],
        Value::Null
    );
    assert_eq!(replay.results[0].stable_root["note"], "owner care retained");
    assert_eq!(replay.results[0].stable_media, vec![media.clone()]);
    assert_eq!(
        fs::read(
            fx._dir
                .path()
                .join("media")
                .join(&fx.family_id)
                .join(&media.media_uuid)
        )
        .unwrap(),
        vec![0u8; 13]
    );
    assert_eq!(
        replay.results[0].stable_version_id.as_deref(),
        Some(version.as_str())
    );
    assert_eq!(
        replay.results[0].request_hash,
        accepted.results[0].request_hash
    );
    assert!(replay.results[0].replay);
    let (_, _, historical) = fx
        .store
        .load_validated_version_for_test(&fx.family_id, &base)
        .unwrap()
        .unwrap();
    assert_eq!(historical.root["created_by_membership_id"], Value::Null);
    assert_eq!(historical.root["note"], "care retained");
    assert_eq!(historical.actor_id.as_deref(), Some("__anonymous__"));
    assert_eq!(historical.device_id.as_deref(), Some("__anonymous__"));
    let mut drift = update;
    drift.root.insert("note".to_owned(), json!("different"));
    assert_commit_rejected(fx.commit(&fx.owner, drift, 1_700_000_004), "content_drift");
    let reopened = Store::open(fx._dir.path().join("lezi.db")).unwrap();
    let (_, _, history) = reopened
        .load_validated_version_for_test(&fx.family_id, &version)
        .unwrap()
        .unwrap();
    assert_eq!(history.root["created_by_membership_id"], Value::Null);
}

#[test]
fn identity_deletion_invalidates_existing_snapshot_preserving_care_choices() {
    let fx = CausalFx::new();
    register_deletable_member(&fx);
    let record = Uuid::new_v4();
    let created = fx
        .commit(
            &fx.member,
            fx.record_mutation(record, None, "base"),
            1_700_000_000,
        )
        .unwrap();
    let base = created.results[0].stable_version_id.clone().unwrap();
    fx.commit(
        &fx.owner,
        fx.record_mutation(record, Some(&base), "stable"),
        1_700_000_001,
    )
    .unwrap();
    let branched = fx
        .commit(
            &fx.member,
            fx.record_mutation(record, Some(&base), "branch"),
            1_700_000_002,
        )
        .unwrap();
    let conflict = branched.results[0].conflict_id.clone().unwrap();
    let before = first_conflict_detail(&fx.store, &fx.owner, &conflict).unwrap();
    let old_input = ResolveConflictInput {
        snapshot_token: before.snapshot_token.clone(),
        resolution_mutation_id: Uuid::new_v4().to_string(),
        choices: vec![resolution_choice(
            &before,
            "/note",
            ConflictOutcome::Set {
                value: json!("branch"),
            },
        )],
    };
    fx.store
        .hard_delete_membership(&fx.owner, &fx.member.membership_id, 1_700_000_101)
        .unwrap();
    let old = fx
        .store
        .resolve_conflict(&fx.owner, &conflict, old_input, 1_700_000_102);
    assert!(
        matches!(old, Err(StoreError::InvalidSnapshotToken))
            || old.as_ref().is_ok_and(|r| r.status == "rejected")
    );
    let after = first_conflict_detail(&fx.store, &fx.owner, &conflict).unwrap();
    let json = serde_json::to_string(&after).unwrap();
    assert!(!json.contains(&fx.member.membership_id));
    assert!(!json.contains(&fx.member.device_id));
    assert_eq!(after.stable.root["note"], "stable");
    assert_eq!(after.branches[0].root["note"], "branch");
    assert_ne!(before.snapshot_token, after.snapshot_token);
    let resolved = fx
        .store
        .resolve_conflict(
            &fx.owner,
            &conflict,
            ResolveConflictInput {
                snapshot_token: after.snapshot_token.clone(),
                resolution_mutation_id: Uuid::new_v4().to_string(),
                choices: vec![resolution_choice(
                    &after,
                    "/note",
                    ConflictOutcome::Set {
                        value: json!("branch"),
                    },
                )],
            },
            1_700_000_103,
        )
        .unwrap();
    assert_eq!(resolved.status, "accepted");
    assert_eq!(resolved.stable_root["note"], "branch");
    assert_eq!(
        resolved.stable_root["created_by_membership_id"],
        Value::Null
    );
}

#[test]
fn identity_deletion_preserves_wake_observation_and_tombstone_history() {
    let fx = CausalFx::new();
    register_deletable_member(&fx);
    let sleep = Uuid::new_v4();
    fx.commit(
        &fx.owner,
        mut_unit(
            "record",
            sleep,
            None,
            sleep_root(fx.baby_id, 100, 20),
            false,
        ),
        1_700_000_000,
    )
    .unwrap();
    let wake = Uuid::new_v4();
    let created = fx
        .commit(
            &fx.member,
            mut_unit(
                "wake_observation",
                wake,
                None,
                wake_observation_root(sleep, 200, 30),
                false,
            ),
            1_700_000_001,
        )
        .unwrap();
    let base = created.results[0].stable_version_id.clone().unwrap();
    let deleted = fx
        .commit(
            &fx.owner,
            mut_unit(
                "wake_observation",
                wake,
                Some(&base),
                created.results[0].stable_root.clone(),
                true,
            ),
            1_700_000_002,
        )
        .unwrap();
    let tombstone = deleted.results[0].stable_version_id.clone().unwrap();
    let deleted_at = deleted.results[0].stable_deleted_at;
    fx.store
        .hard_delete_membership(&fx.owner, &fx.member.membership_id, 1_700_000_003)
        .unwrap();
    for version in [&base, &tombstone] {
        let (_, _, historic) = fx
            .store
            .load_validated_version_for_test(&fx.family_id, version)
            .unwrap()
            .unwrap();
        assert_eq!(historic.root["observer_membership_id"], Value::Null);
        assert_eq!(historic.root["wake_timestamp"], 200);
        assert_eq!(historic.root["sleep_record_client_uuid"], sleep.to_string());
        if version == &tombstone {
            assert_eq!(historic.deleted_at, deleted_at);
            assert!(historic.parents.contains(&base));
        }
    }
    let page = fx.store.pull(&fx.family_id, 0).unwrap();
    let pulled = page
        .entities
        .iter()
        .find(|item| item.client_uuid == wake.to_string())
        .unwrap();
    assert_eq!(pulled.payload["observer_membership_id"], Value::Null);
    assert_eq!(pulled.deleted_at, deleted_at);
}

#[test]
fn identity_deletion_keeps_old_base_owner_edits_anonymous_and_member_acl_closed() {
    let fx = CausalFx::new();
    register_deletable_member(&fx);
    let record = Uuid::new_v4();
    let created = fx
        .commit(
            &fx.member,
            fx.record_mutation(record, None, "base nursing fact"),
            1_700_000_000,
        )
        .unwrap();
    let base = created.results[0].stable_version_id.clone().unwrap();
    let stale = CausalMutation {
        root: created.results[0].stable_root.clone(),
        ..fx.record_mutation(record, Some(&base), "unused")
    };
    fx.store
        .hard_delete_membership(&fx.owner, &fx.member.membership_id, 1_700_000_001)
        .unwrap();
    let mut edited = stale.clone();
    edited
        .root
        .insert("note".to_owned(), json!("edited by owner"));
    let result = fx.commit(&fx.owner, edited, 1_700_000_002).unwrap();
    assert_eq!(result.results[0].status, "accepted");
    assert_eq!(
        result.results[0].stable_root["created_by_membership_id"],
        Value::Null
    );
    assert_eq!(result.results[0].stable_root["note"], "edited by owner");
    let outsider_membership = fx
        .store
        .add_device_less_member(&fx.owner, "other member", "other member")
        .unwrap();
    let outsider_device = Uuid::new_v4().to_string();
    fx.store.connect().unwrap().execute(
        "INSERT INTO devices(device_id, membership_id, device_name, device_name_key, status, created_at, last_used_at)
         VALUES (?1, ?2, 'other device', 'other device', 'active', 1, 1)",
        params![outsider_device, outsider_membership],
    ).unwrap();
    let outsider = Principal {
        membership_id: outsider_membership,
        device_id: outsider_device,
        ..fx.member.clone()
    };
    assert_commit_rejected(fx.commit(&outsider, stale, 1_700_000_003), "forbidden");
}

#[test]
fn identity_deletion_redacts_cached_resolution_without_changing_request_identity() {
    let fx = CausalFx::new();
    register_deletable_member(&fx);
    let record = Uuid::new_v4();
    let created = fx
        .commit(
            &fx.member,
            fx.record_mutation(record, None, "base"),
            1_700_000_000,
        )
        .unwrap();
    let base = created.results[0].stable_version_id.clone().unwrap();
    fx.commit(
        &fx.owner,
        fx.record_mutation(record, Some(&base), "stable"),
        1_700_000_001,
    )
    .unwrap();
    let branched = fx
        .commit(
            &fx.member,
            fx.record_mutation(record, Some(&base), "branch"),
            1_700_000_002,
        )
        .unwrap();
    let conflict = branched.results[0].conflict_id.clone().unwrap();
    let detail = first_conflict_detail(&fx.store, &fx.owner, &conflict).unwrap();
    let input = ResolveConflictInput {
        snapshot_token: detail.snapshot_token.clone(),
        resolution_mutation_id: Uuid::new_v4().to_string(),
        choices: vec![resolution_choice(
            &detail,
            "/note",
            ConflictOutcome::Set {
                value: json!("branch"),
            },
        )],
    };
    let resolved = fx
        .store
        .resolve_conflict(&fx.owner, &conflict, input.clone(), 1_700_000_101)
        .unwrap();
    fx.store
        .hard_delete_membership(&fx.owner, &fx.member.membership_id, 1_700_000_102)
        .unwrap();
    assert_eq!(
        fx.store
            .gc_conflict_metadata_for_family(&fx.family_id, 1_700_000_102 + 24 * 60 * 60)
            .unwrap(),
        1
    );
    let replay = fx
        .store
        .resolve_conflict(&fx.owner, &conflict, input, 1_700_000_103 + 24 * 60 * 60)
        .unwrap();
    assert_eq!(replay.status, resolved.status);
    assert_eq!(replay.stable_version_id, resolved.stable_version_id);
    assert_eq!(
        replay.resolution_mutation_id,
        resolved.resolution_mutation_id
    );
    assert_eq!(replay.replay, Some(true));
    assert_eq!(replay.stable_root["note"], "branch");
    assert_eq!(replay.stable_root["created_by_membership_id"], Value::Null);
    assert!(!serde_json::to_string(&replay)
        .unwrap()
        .contains(&fx.member.membership_id));
}

#[test]
fn identity_deletion_never_rehashes_corrupted_history_into_valid_care_facts() {
    let fx = CausalFx::new();
    register_deletable_member(&fx);
    let record = Uuid::new_v4();
    let created = fx
        .commit(
            &fx.member,
            fx.record_mutation(record, None, "original care"),
            1_700_000_000,
        )
        .unwrap();
    let version = created.results[0].stable_version_id.clone().unwrap();
    let connection = fx.store.connect().unwrap();
    connection.execute("UPDATE entity_versions SET payload_json = json_set(payload_json, '$.note', 'tampered care') WHERE family_id = ?1 AND version_id = ?2", params![fx.family_id, version]).unwrap();
    let before = resolution_durable_state(&fx.store, &fx.family_id, "", record);
    let result =
        fx.store
            .hard_delete_membership(&fx.owner, &fx.member.membership_id, 1_700_000_001);
    assert!(matches!(result, Err(StoreError::InvalidStoredPayload)));
    assert_eq!(
        resolution_durable_state(&fx.store, &fx.family_id, "", record),
        before
    );
    assert!(fx
        .store
        .active_memberships(&fx.family_id)
        .unwrap()
        .iter()
        .any(|member| member.membership_id == fx.member.membership_id));
}

#[test]
fn identity_deletion_rejects_stale_upload_admission_without_new_identity_rows() {
    let fx = CausalFx::new();
    register_deletable_member(&fx);
    fx.store
        .hard_delete_membership(&fx.owner, &fx.member.membership_id, 1_700_000_000)
        .unwrap();
    let result = fx.store.reserve_causal_media_upload(
        &fx.member,
        &Uuid::new_v4().to_string(),
        1_700_000_001,
    );
    assert!(matches!(result, Err(StoreError::MembershipDeleted)));
    let rows: i64 = fx
        .store
        .connect()
        .unwrap()
        .query_row(
            "SELECT COUNT(*) FROM causal_media_uploads WHERE family_id = ?1",
            params![fx.family_id],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(rows, 0);
}

#[test]
fn identity_deletion_rejects_stale_verified_media_and_preserves_incoming_bytes() {
    let fx = CausalFx::new();
    register_deletable_member(&fx);
    let bytes = b"valid-only-upload-copy";
    let incoming = NamedTempFile::new().unwrap();
    fs::write(incoming.path(), bytes).unwrap();
    let verified = VerifiedCausalMediaPreimage::verify(
        incoming.path().to_owned(),
        &hex::encode(Sha256::digest(bytes)),
        100,
    )
    .unwrap();
    let media = Uuid::new_v4().to_string();
    let reserved = fx
        .store
        .reserve_causal_media_upload(&fx.member, &media, 1_700_000_000)
        .unwrap();
    fx.store
        .hard_delete_membership(&fx.owner, &fx.member.membership_id, 1_700_000_001)
        .unwrap();
    let result = fx.store.stage_verified_causal_media_preimage(
        &fx.member,
        &media,
        &verified,
        1_700_000_002,
        DEFAULT_CAUSAL_MEDIA_STAGING_LIMITS,
    );
    assert!(matches!(result, Err(StoreError::MembershipDeleted)));
    assert_eq!(fs::read(incoming.path()).unwrap(), bytes);
    assert!(matches!(
        fx.store.bind_empty_causal_media_preimage(
            &fx.member,
            &Uuid::new_v4().to_string(),
            &hex::encode(Sha256::digest(bytes)),
            1_700_000_002,
            DEFAULT_CAUSAL_MEDIA_STAGING_LIMITS
        ),
        Err(StoreError::MembershipDeleted)
    ));
    let (staged, upload_member): (i64, String) = fx.store.connect().unwrap().query_row(
        "SELECT (SELECT COUNT(*) FROM causal_media_staging WHERE family_id = ?1), membership_id FROM causal_media_uploads WHERE family_id = ?1 AND sequence = ?2",
        params![fx.family_id, reserved.sequence()], |row| Ok((row.get(0)?, row.get(1)?)),
    ).unwrap();
    assert_eq!(staged, 0);
    assert!(upload_member.is_empty());
}

#[test]
fn identity_deletion_rejects_stale_bundle_stage_and_commit_without_fact_changes() {
    let fx = FulfillmentCandidateFixture::seed();
    register_principal(&fx.store, &fx.member);
    let candidate = entity(
        "fulfillment_candidate",
        fx.candidate_id,
        50,
        fx.exact_candidate_payload(),
    );
    let pending_bundle = Uuid::new_v4().to_string();
    fx.store
        .stage_bundle(
            &fx.member,
            &pending_bundle,
            candidate.clone(),
            vec![],
            1_700_000_000,
        )
        .unwrap();
    fx.store
        .hard_delete_membership(&fx.owner, &fx.member.membership_id, 1_700_000_001)
        .unwrap();
    assert!(matches!(
        fx.store.stage_bundle(
            &fx.member,
            &Uuid::new_v4().to_string(),
            candidate,
            vec![],
            1_700_000_002
        ),
        Err(StoreError::MembershipDeleted)
    ));
    assert!(matches!(
        fx.store.commit_bundle(
            &fx.member,
            &pending_bundle,
            &Default::default(),
            i64::MAX,
            1_700_000_002
        ),
        Err(StoreError::MembershipDeleted)
    ));
    let rows: i64 = fx
        .store
        .connect()
        .unwrap()
        .query_row(
            "SELECT COUNT(*) FROM sync_bundles WHERE family_id = ?1",
            params![fx.family_id],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(rows, 0);
}

#[test]
fn legacy_pending_bundle_cannot_replace_a_causal_head_but_committed_replay_remains() {
    let fx = CausalFx::new();
    register_principal(&fx.store, &fx.owner);
    let record = Uuid::new_v4();
    let accepted = fx
        .commit(
            &fx.owner,
            fx.record_mutation(record, None, "authoritative"),
            1_700_000_000,
        )
        .unwrap();
    let root = Entity {
        entity_type: "record".to_owned(),
        client_uuid: record.to_string(),
        updated_at: 500,
        deleted_at: None,
        payload: accepted.results[0].stable_root.clone(),
    };
    let bundle = Uuid::new_v4().to_string();
    // A historical pending bundle cannot be created through today's stage API;
    // this exact persisted-row fixture exercises the supported restart boundary.
    fx.store.connect().unwrap().execute(
        "INSERT INTO sync_bundles(family_id,bundle_id,staged_membership_id,status,root_type,root_client_uuid,root_updated_at,root_payload_json,media_entities_json,content_hash,created_at) VALUES (?1,?2,?3,'staging','record',?4,?5,?6,'[]',?7,1)",
        params![fx.family_id, bundle, fx.owner.membership_id, record.to_string(), root.updated_at, serde_json::to_string(&root.payload).unwrap(), bundle_content_hash(&root,&[]).unwrap()],
    ).unwrap();
    let before = resolution_durable_state(&fx.store, &fx.family_id, "", record);
    let result = fx.store.commit_bundle(
        &fx.owner,
        &bundle,
        &Default::default(),
        i64::MAX,
        1_700_000_001,
    );
    assert!(
        matches!(result, Err(StoreError::LegacyBundleCausalRootUnsupported(ref kind)) if kind == "record")
    );
    assert_eq!(
        resolution_durable_state(&fx.store, &fx.family_id, "", record),
        before
    );
    assert_eq!(
        fx.store
            .bundle_status(&fx.family_id, &bundle)
            .unwrap()
            .unwrap()
            .status,
        "staging"
    );
    fx.store.connect().unwrap().execute("UPDATE sync_bundles SET status='committed', committed_cursor=7, committed_applied=1 WHERE family_id=?1 AND bundle_id=?2", params![fx.family_id,bundle]).unwrap();
    let replay = fx
        .store
        .commit_bundle(
            &fx.owner,
            &bundle,
            &Default::default(),
            i64::MAX,
            1_700_000_002,
        )
        .unwrap();
    assert_eq!(replay.0.status, "committed");
    assert_eq!(replay.0.cursor, 7);
    assert_eq!(
        resolution_durable_state(&fx.store, &fx.family_id, "", record),
        before
    );
}

#[test]
fn identity_deletion_during_media_replay_completion_denies_without_destroying_care_bytes() {
    let fx = CausalFx::new();
    register_deletable_member(&fx);
    let bytes = b"retained-photo";
    let incoming = NamedTempFile::new().unwrap();
    fs::write(incoming.path(), bytes).unwrap();
    let sha256 = hex::encode(Sha256::digest(bytes));
    let verified =
        VerifiedCausalMediaPreimage::verify(incoming.path().to_owned(), &sha256, 100).unwrap();
    let media = CausalMediaItem {
        media_uuid: Uuid::new_v4().to_string(),
        role: "log".to_owned(),
        sha256,
        byte_size: bytes.len() as i64,
        mime: "image/jpeg".to_owned(),
        width: None,
        height: None,
    };
    fx.store
        .stage_verified_causal_media_preimage(
            &fx.member,
            &media.media_uuid,
            &verified,
            1_700_000_000,
            DEFAULT_CAUSAL_MEDIA_STAGING_LIMITS,
        )
        .unwrap();
    let mut create = fx.record_mutation(Uuid::new_v4(), None, "keep photo and nursing fact");
    create.media = vec![media.clone()];
    fx.commit(&fx.member, create, 1_700_000_001).unwrap();
    let delete_store = fx.store.clone();
    let owner = fx.owner.clone();
    let membership_id = fx.member.membership_id.clone();
    crate::store::causal_media_staging::test_hook::after_stage_file_once(
        &fx.family_id,
        move || {
            delete_store
                .hard_delete_membership(&owner, &membership_id, 1_700_000_002)
                .unwrap();
        },
    );
    let replay_incoming = NamedTempFile::new().unwrap();
    fs::write(replay_incoming.path(), bytes).unwrap();
    let replay_verified =
        VerifiedCausalMediaPreimage::verify(replay_incoming.path().to_owned(), &media.sha256, 100)
            .unwrap();
    let replay = fx.store.stage_verified_causal_media_preimage(
        &fx.member,
        &media.media_uuid,
        &replay_verified,
        1_700_000_003,
        DEFAULT_CAUSAL_MEDIA_STAGING_LIMITS,
    );
    assert!(matches!(replay, Err(StoreError::MembershipDeleted)));
    assert_eq!(
        fs::read(
            fx._dir
                .path()
                .join("media")
                .join(&fx.family_id)
                .join(&media.media_uuid)
        )
        .unwrap(),
        bytes
    );
    let (principal, status): (String, String) = fx.store.connect().unwrap().query_row(
        "SELECT membership_id,status FROM causal_media_staging WHERE family_id=?1 AND media_uuid=?2",
        params![fx.family_id,media.media_uuid], |row| Ok((row.get(0)?,row.get(1)?)),
    ).unwrap();
    assert!(principal.is_empty());
    assert_eq!(status, "consumed");
}

#[test]
fn identity_deletion_preserves_raw_legacy_mime_and_media_bytes() {
    for mime in [
        Value::Null,
        json!(""),
        json!("x".repeat(129)),
        json!("x".repeat(255)),
    ] {
        let fx = CausalFx::new();
        register_deletable_member(&fx);
        let bytes = b"unchanged-legacy-photo";
        let incoming = NamedTempFile::new().unwrap();
        fs::write(incoming.path(), bytes).unwrap();
        let sha256 = hex::encode(Sha256::digest(bytes));
        let verified =
            VerifiedCausalMediaPreimage::verify(incoming.path().to_owned(), &sha256, 100).unwrap();
        let media = CausalMediaItem {
            media_uuid: Uuid::new_v4().to_string(),
            role: "log".to_owned(),
            sha256,
            byte_size: bytes.len() as i64,
            mime: "image/jpeg".to_owned(),
            width: None,
            height: None,
        };
        fx.store
            .stage_verified_causal_media_preimage(
                &fx.member,
                &media.media_uuid,
                &verified,
                1_700_000_000,
                DEFAULT_CAUSAL_MEDIA_STAGING_LIMITS,
            )
            .unwrap();
        let record = Uuid::new_v4();
        let mut create = fx.record_mutation(record, None, "legacy nursing fact");
        create.media = vec![media.clone()];
        let accepted = fx.commit(&fx.member, create, 1_700_000_001).unwrap();
        let version = accepted.results[0].stable_version_id.clone().unwrap();
        rewrite_version_media_to_household_lww(&fx, &version);
        let mut raw_media: Value =
            serde_json::from_str(&stored_version_media_payloads(&fx, &version)[0].1).unwrap();
        raw_media["mime"] = mime.clone();
        let raw_media_json = serde_json::to_string(&raw_media).unwrap();
        let connection = fx.store.connect().unwrap();
        connection.execute("UPDATE entity_version_media SET media_payload_json=?1 WHERE family_id=?2 AND version_id=?3", params![raw_media_json,fx.family_id,version]).unwrap();
        connection.execute("UPDATE entities SET payload_json=?1 WHERE family_id=?2 AND entity_type='media' AND client_uuid=?3", params![raw_media_json,fx.family_id,media.media_uuid]).unwrap();
        drop(connection);
        mark_migration_base(&fx, &version);
        let connection = fx.store.connect().unwrap();
        let original_root: String = connection
            .query_row(
                "SELECT payload_json FROM entity_versions WHERE family_id=?1 AND version_id=?2",
                params![fx.family_id, version],
                |row| row.get(0),
            )
            .unwrap();
        connection.execute("UPDATE entity_versions SET payload_json=json_set(payload_json,'$.note','tampered') WHERE family_id=?1 AND version_id=?2",params![fx.family_id,version]).unwrap();
        assert!(matches!(
            fx.store
                .hard_delete_membership(&fx.owner, &fx.member.membership_id, 1_700_000_002),
            Err(StoreError::InvalidStoredPayload)
        ));
        connection
            .execute(
                "UPDATE entity_versions SET payload_json=?1 WHERE family_id=?2 AND version_id=?3",
                params![original_root, fx.family_id, version],
            )
            .unwrap();
        drop(connection);
        fx.store
            .hard_delete_membership(&fx.owner, &fx.member.membership_id, 1_700_000_002)
            .unwrap();
        let connection = fx.store.connect().unwrap();
        let (root,stored_hash,updated,deleted):(String,String,i64,Option<i64>)=connection.query_row("SELECT payload_json,content_hash,updated_at,deleted_at FROM entity_versions WHERE family_id=?1 AND version_id=?2",params![fx.family_id,version],|row|Ok((row.get(0)?,row.get(1)?,row.get(2)?,row.get(3)?))).unwrap();
        let root_value: Value = serde_json::from_str(&root).unwrap();
        assert_eq!(root_value["created_by_membership_id"], Value::Null);
        assert_eq!(root_value["note"], "legacy nursing fact");
        assert_eq!(
            stored_version_media_payloads(&fx, &version),
            vec![(media.media_uuid.clone(), raw_media_json.clone())]
        );
        assert_eq!(
            stored_hash,
            migration_content_hash(&[
                &updated.to_string(),
                &deleted.map(|v| v.to_string()).unwrap_or_default(),
                &root,
                &media.media_uuid,
                &raw_media_json
            ])
        );
        assert_eq!(
            fs::read(published_media_path(&fx, &media.media_uuid)).unwrap(),
            bytes
        );
    }
}

#[test]
fn revoked_owner_cannot_mark_legacy_bundle_media_prepared() {
    let fx = CausalFx::new();
    let bundle = Uuid::new_v4().to_string();
    let media = Uuid::new_v4().to_string();
    let connection = fx.store.connect().unwrap();
    connection.execute("INSERT INTO sync_bundles(family_id,bundle_id,staged_membership_id,status,root_type,root_client_uuid,root_updated_at,root_payload_json,media_entities_json,content_hash,created_at) VALUES (?1,?2,?3,'staging','record',?4,1,'{}','[]','legacy',1)",params![fx.family_id,bundle,fx.owner.membership_id,Uuid::new_v4().to_string()]).unwrap();
    connection.execute("INSERT INTO sync_bundle_media(family_id,bundle_id,media_uuid,declared_byte_size) VALUES (?1,?2,?3,10)",params![fx.family_id,bundle,media]).unwrap();
    fx.store
        .owner_login(
            1_700_000_001,
            "takeover-before-legacy-media-prepare",
            "new owner device",
            true,
            |_, _, _| ("takeover-access".to_owned(), "takeover-refresh".to_owned()),
        )
        .unwrap();
    let result = fx
        .store
        .mark_bundle_media_prepared(&fx.owner, &bundle, &[media]);
    assert!(matches!(result, Err(StoreError::DeviceRemoved)));
    let publications: i64 = connection
        .query_row(
            "SELECT COUNT(*) FROM media_publications WHERE family_id=?1",
            params![fx.family_id],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(publications, 0);
    assert!(fx
        .store
        .bundle_status(&fx.family_id, &bundle)
        .unwrap()
        .is_some());
}
