//! Behavior regressions for startup closure and deleted-identity transaction boundaries.
use super::super::*;
use super::test_support::*;
use rusqlite::params;
use serde_json::json;
use tempfile::TempDir;
use uuid::Uuid;

fn fixture() -> (TempDir, Store, Principal, Uuid) {
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    let family_id = family(&store);
    let principal = owner_principal(&family_id);
    let baby_id = Uuid::new_v4();
    publish_root(&store,&principal,entity("baby",baby_id,1,json!({"nickname":"synthetic","sex":"female","birthday":"2025-01-02","birth_weight_grams":null,"avatar_media_uuid":null})),10).unwrap();
    (directory, store, principal, baby_id)
}
fn add_legacy_baby(store: &Store, principal: &Principal, id: Uuid) {
    store.connect().unwrap().execute("INSERT INTO entities(family_id,entity_type,client_uuid,updated_at,deleted_at,payload_json,rev) VALUES (?1,'baby',?2,1,NULL,?3,100)",params![principal.family_id,id.to_string(),json!({"nickname":"legacy synthetic","sex":"female","birthday":"2025-01-02","birth_weight_grams":null,"avatar_media_uuid":null}).to_string()]).unwrap();
}
#[test]
fn startup_keeps_valid_ordinary_headless_history_without_fabricating_versions() {
    let (_dir, store, principal, _) = fixture();
    let legacy = Uuid::new_v4();
    add_legacy_baby(&store, &principal, legacy);
    store
        .validate_authority_graph(100, |_, _, _| Ok(true))
        .unwrap();
    let count: i64 = store
        .connect()
        .unwrap()
        .query_row(
            "SELECT COUNT(*) FROM entity_stable_heads WHERE client_uuid=?1",
            params![legacy.to_string()],
            |r| r.get(0),
        )
        .unwrap();
    assert_eq!(count, 0);
}
#[test]
fn startup_rejects_a_foreign_root_head_even_without_own_versions_or_media() {
    let (_dir, store, principal, baby) = fixture();
    let legacy = Uuid::new_v4();
    add_legacy_baby(&store, &principal, legacy);
    store.connect().unwrap().execute("INSERT INTO entity_stable_heads(family_id,entity_type,client_uuid,version_id) SELECT family_id,'baby',?2,version_id FROM entity_stable_heads WHERE family_id=?1 AND client_uuid=?3",params![principal.family_id,legacy.to_string(),baby.to_string()]).unwrap();
    assert!(matches!(
        store.validate_authority_graph(100, |_, _, _| Ok(true)),
        Err(StoreError::AuthorityGraphInvalid { .. })
    ));
}
#[test]
fn startup_rejects_live_media_outside_its_head_manifest() {
    let (_dir, store, principal, baby) = fixture();
    let media = Uuid::new_v4();
    let connection = store.connect().unwrap();
    connection.execute("INSERT INTO entities(family_id,entity_type,client_uuid,updated_at,deleted_at,payload_json,rev) VALUES (?1,'media',?2,1,NULL,?3,100)",params![principal.family_id,media.to_string(),json!({"kind":"avatar","baby_client_uuid":baby,"record_client_uuid":null,"care_plan_client_uuid":null,"byte_size":4,"mime":"image/jpeg","width":null,"height":null}).to_string()]).unwrap();
    connection.execute("INSERT INTO media_publications(family_id,media_uuid,source,bundle_id) VALUES (?1,?2,'ordinary',NULL)",params![principal.family_id,media.to_string()]).unwrap();
    assert!(matches!(
        store.validate_authority_graph(100, |_, _, _| Ok(true)),
        Err(StoreError::AuthorityGraphInvalid { .. })
    ));
}
#[test]
fn removed_principal_cannot_create_a_new_root_or_receipt_after_deletion_commits() {
    let (_dir, store, owner, baby) = fixture();
    let member = Principal {
        family_id: owner.family_id.clone(),
        role: "member".to_owned(),
        membership_id: "synthetic-removed-member".to_owned(),
        device_id: "synthetic-removed-device".to_owned(),
    };
    register_test_principal(&store, &member);
    store
        .hard_delete_membership(&owner, &member.membership_id, 100)
        .unwrap();
    let record = Uuid::new_v4();
    let mutation=CausalMutation {mutation_id:Uuid::new_v4().to_string(),base_version:None,entity_type:"record".to_owned(),client_uuid:record.to_string(),root:json!({"baby_client_uuid":baby,"type":"formula","custom_item_client_uuid":null,"timestamp":1000,"end_timestamp":null,"note":null,"payload_json":{"amount_ml":100},"schema_version":2,"updated_at":1000}).as_object().unwrap().clone(),media:vec![],deleted:false};
    assert!(matches!(
        store.causal_commit(&member, vec![mutation], 101),
        Err(StoreError::MembershipDeleted)
    ));
    let count:i64=store.connect().unwrap().query_row("SELECT (SELECT COUNT(*) FROM entities WHERE client_uuid=?1)+(SELECT COUNT(*) FROM entity_versions WHERE client_uuid=?1)+(SELECT COUNT(*) FROM mutation_receipts WHERE client_uuid=?1)",params![record.to_string()],|r|r.get(0)).unwrap();
    assert_eq!(count, 0);
}
