//! Work-count and restart checks for the exact production immutable-manifest/status helpers.
use super::*;

fn fixture(count: usize) -> (tempfile::TempDir, RestoreJournal, Vec<RestoreMediaSpec>) {
    let root = tempfile::TempDir::new().unwrap();
    let batch_id = Uuid::new_v4().to_string();
    let specs = (0..count)
        .map(|index| RestoreMediaSpec {
            client_uuid: Uuid::from_u128(index as u128 + 1).to_string(),
            byte_size: 32,
            sha256: hex::encode(Sha256::digest([7u8; 32])),
        })
        .collect::<Vec<_>>();
    let baby = Uuid::from_u128(0x50000000000040008000000000000000).to_string();
    let mut entities=vec![Entity {entity_type:"baby".to_owned(),client_uuid:baby.clone(),updated_at:1,deleted_at:None,payload:json!({"nickname":"synthetic","sex":"female","birthday":"2025-01-02","birth_weight_grams":null,"avatar_media_uuid":null}).as_object().unwrap().clone()}];
    for (index, spec) in specs.iter().enumerate() {
        let record =
            Uuid::from_u128(0x60000000000040008000000000000000 + index as u128).to_string();
        entities.push(Entity{entity_type:"record".to_owned(),client_uuid:record.clone(),updated_at:1,deleted_at:None,payload:json!({"baby_client_uuid":baby,"type":"formula","custom_item_client_uuid":null,"timestamp":1000,"end_timestamp":null,"note":"synthetic","payload_json":{"amount_ml":100},"schema_version":2,"created_by_membership_id":null}).as_object().unwrap().clone()});
        entities.push(Entity{entity_type:"media".to_owned(),client_uuid:spec.client_uuid.clone(),updated_at:1,deleted_at:None,payload:json!({"kind":"log","record_client_uuid":record,"baby_client_uuid":null,"care_plan_client_uuid":null,"byte_size":spec.byte_size,"mime":"image/jpeg","width":1,"height":1}).as_object().unwrap().clone()});
    }
    let manifest = ValidatedRestoreManifest {
        request_id: "synthetic-cache-manifest-request-0001".to_owned(),
        entities,
        source_relations: vec![],
        media: specs.clone(),
    };
    let encoded = serde_json::to_vec(&manifest).unwrap();
    let journal = RestoreJournal {
        protocol_version: RESTORE_JOURNAL_VERSION,
        batch_id,
        start_request_id: "synthetic-cache-start-request-00001".to_owned(),
        family_id: Uuid::new_v4().to_string(),
        family_name: "synthetic".to_owned(),
        owner_display_name: "synthetic".to_owned(),
        device_name: "synthetic".to_owned(),
        owner_membership_id: Uuid::new_v4().to_string(),
        device_id: Uuid::new_v4().to_string(),
        session_id: Uuid::new_v4().to_string(),
        access_expires_at: 100,
        recovery_token_hash: "a".repeat(64),
        created_at: 0,
        expires_at: 100,
        status: "manifest_received".to_owned(),
        manifest_request_id: Some(manifest.request_id.clone()),
        manifest_hash: Some(hex::encode(Sha256::digest(&encoded))),
        commit_request_id: None,
    };
    fs::create_dir_all(
        batch_dir(root.path(), &journal.batch_id)
            .unwrap()
            .join("media"),
    )
    .unwrap();
    write_private_file(
        &manifest_path(root.path(), &journal.batch_id).unwrap(),
        &encoded,
    )
    .unwrap();
    (root, journal, specs)
}
fn check_order(count: usize, order: impl FnOnce(&mut Vec<usize>)) {
    let (root, journal, specs) = fixture(count);
    let mut indices = (0..count).collect::<Vec<_>>();
    order(&mut indices);
    cache::begin_work(root.path());
    assert!(!cache::ready(root.path(), &journal, false).unwrap());
    for (position, index) in indices.into_iter().enumerate() {
        let spec = &specs[index];
        let media = cache::media(root.path(), &journal).unwrap();
        let found = media
            .binary_search_by(|m| m.client_uuid.cmp(&spec.client_uuid))
            .unwrap();
        assert_eq!(media[found], *spec);
        let path = staged_media_path(root.path(), &journal.batch_id, &spec.client_uuid).unwrap();
        write_private_file(&path, &[7u8; 32]).unwrap();
        write_private_file(
            &verified_marker(root.path(), &journal.batch_id, &spec.client_uuid).unwrap(),
            spec.sha256.as_bytes(),
        )
        .unwrap();
        cache::verified(root.path(), &journal, &spec.client_uuid).unwrap();
        assert_eq!(
            cache::ready(root.path(), &journal, false).unwrap(),
            position + 1 == count
        );
    }
    let progress = cache::finish_work(root.path());
    assert_eq!(progress.manifest_reads, 1, "{progress:?}");
    assert_eq!(
        progress.media_hashes, 0,
        "progress must not reread media bytes"
    );
    cache::clear(root.path(), &journal);
    cache::begin_work(root.path());
    assert!(cache::ready(root.path(), &journal, false).unwrap());
    let manifest = load_fresh_manifest(root.path(), &journal).unwrap();
    ensure_all_media_ready(root.path(), &journal, &manifest).unwrap();
    let final_work = cache::finish_work(root.path());
    assert_eq!(final_work.manifest_reads, 2);
    assert_eq!(final_work.media_hashes, count);
    assert_eq!(final_work.media_bytes, count * 32);
    let path = staged_media_path(root.path(), &journal.batch_id, &specs[0].client_uuid).unwrap();
    write_private_file(&path, &[8u8; 32]).unwrap();
    assert!(
        ensure_all_media_ready(root.path(), &journal, &manifest).is_err(),
        "cached progress cannot authorize corrupt activation bytes"
    );
}
#[test]
fn immutable_restore_progress_is_linear_in_original_bytes_in_all_upload_orders() {
    for count in [100, 1000, 10000] {
        check_order(count, |_| {});
        check_order(count, |v| v.reverse());
        check_order(count, |v| {
            use rand::seq::SliceRandom;
            use rand::SeedableRng;
            v.shuffle(&mut rand::rngs::StdRng::seed_from_u64(42));
        });
    }
}
#[test]
fn immutable_manifest_cache_detects_change_and_never_blinds_final_hash_check() {
    let (root, journal, _) = fixture(1);
    cache::media(root.path(), &journal).unwrap();
    let path = manifest_path(root.path(), &journal.batch_id).unwrap();
    let mut bytes = fs::read(&path).unwrap();
    bytes[0] = b'[';
    write_private_file(&path, &bytes).unwrap();
    assert!(cache::media(root.path(), &journal).is_err());
    assert!(load_fresh_manifest(root.path(), &journal).is_err());
}

#[test]
fn complete_progress_retires_rejected_evidence_and_full_reupload_stays_linear() {
    let (root, journal, specs) = fixture(100);
    cache::media(root.path(), &journal).unwrap();
    for spec in &specs {
        write_private_file(
            &staged_media_path(root.path(), &journal.batch_id, &spec.client_uuid).unwrap(),
            &[7u8; 32],
        )
        .unwrap();
        write_private_file(
            &verified_marker(root.path(), &journal.batch_id, &spec.client_uuid).unwrap(),
            spec.sha256.as_bytes(),
        )
        .unwrap();
        cache::verified(root.path(), &journal, &spec.client_uuid).unwrap();
    }
    assert!(cache::ready(root.path(), &journal, true).unwrap());
    fs::remove_file(
        staged_media_path(root.path(), &journal.batch_id, &specs[0].client_uuid).unwrap(),
    )
    .unwrap();
    assert!(!cache::ready(root.path(), &journal, true).unwrap());
    cache::begin_work(root.path());
    for spec in &specs {
        write_private_file(
            &staged_media_path(root.path(), &journal.batch_id, &spec.client_uuid).unwrap(),
            &[7u8; 32],
        )
        .unwrap();
        write_private_file(
            &verified_marker(root.path(), &journal.batch_id, &spec.client_uuid).unwrap(),
            spec.sha256.as_bytes(),
        )
        .unwrap();
        cache::verified(root.path(), &journal, &spec.client_uuid).unwrap();
        assert!(cache::ready(root.path(), &journal, false).unwrap());
    }
    assert!(cache::ready(root.path(), &journal, true).unwrap());
    let work = cache::finish_work(root.path());
    assert_eq!(work.metadata_checks, specs.len());
    assert_eq!(work.manifest_reads, 0);
    let manifest = load_fresh_manifest(root.path(), &journal).unwrap();
    write_private_file(
        &staged_media_path(root.path(), &journal.batch_id, &specs[0].client_uuid).unwrap(),
        &[8u8; 32],
    )
    .unwrap();
    assert!(ensure_all_media_ready(root.path(), &journal, &manifest).is_err());
    assert!(!cache::ready(root.path(), &journal, true).unwrap());
    cache::clear(root.path(), &journal);
    assert!(!cache::ready(root.path(), &journal, true).unwrap());
}
#[test]
fn record_heavy_manifest_uses_only_compact_media_index_after_first_read() {
    let (root, mut journal, _) = fixture(800);
    let mut manifest = load_fresh_manifest(root.path(), &journal).unwrap();
    for entity in manifest
        .entities
        .iter_mut()
        .filter(|e| e.entity_type == "record")
    {
        entity
            .payload
            .insert("note".to_owned(), json!("n".repeat(19_000)));
    }
    let bytes = serde_json::to_vec(&manifest).unwrap();
    assert!(bytes.len() > 14 * 1024 * 1024 && bytes.len() < 16 * 1024 * 1024);
    journal.manifest_hash = Some(hex::encode(Sha256::digest(&bytes)));
    write_private_file(
        &manifest_path(root.path(), &journal.batch_id).unwrap(),
        &bytes,
    )
    .unwrap();
    cache::begin_work(root.path());
    for _ in 0..3 {
        assert_eq!(cache::media(root.path(), &journal).unwrap().len(), 800);
        assert!(!cache::ready(root.path(), &journal, false).unwrap());
    }
    assert_eq!(
        cache::finish_work(root.path()).manifest_reads,
        1,
        "large notes never trigger a silent uncached fallback"
    );
}
