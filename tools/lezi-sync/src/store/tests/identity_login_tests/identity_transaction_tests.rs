//! Identity mutations must recheck their authenticated actor in the write transaction.

use super::*;

fn member_principal(store: &Store, owner: &Principal) -> Principal {
    let membership_id = store
        .add_device_less_member(owner, "Synthetic member", "synthetic-member")
        .unwrap();
    store
        .create_member_login_grant(owner, &membership_id, "member-grant", 10, 600)
        .unwrap();
    store
        .claim_member_login_grant("member-grant", "Member phone", 11, |_, _, _| {
            ("member-access".to_owned(), "member-refresh".to_owned())
        })
        .unwrap();
    store.authenticate("member-access", 12).unwrap().unwrap()
}

fn login_request(store: &Store) -> PendingMemberLoginRequest {
    store
        .create_member_login_request(CreateMemberLoginRequestInput {
            now: 10,
            ttl_seconds: 60,
            max_pending: 8,
            display_name: "New member",
            display_name_key: "new-member",
            device_name: "New phone",
            pending_secret: "new-member-pending-secret",
        })
        .unwrap()
}

fn request_status(store: &Store, table: &str, request_id: &str) -> String {
    store
        .connect()
        .unwrap()
        .query_row(
            &format!("SELECT status FROM {table} WHERE request_id = ?1"),
            [request_id],
            |row| row.get(0),
        )
        .unwrap()
}

#[test]
fn revoked_owner_cannot_administer_identity_or_expire_pending_requests() {
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    family(&store);
    let owner = store.authenticate("owner-access", 2).unwrap().unwrap();
    let member = member_principal(&store, &owner);
    let login = login_request(&store);
    let rename = store
        .create_member_rename_request(&member, "Requested name", "requested-name", 15, 60)
        .unwrap();
    store
        .revoke_family_device(&owner, &owner.device_id, 20)
        .unwrap();

    macro_rules! denied {
        ($call:expr) => {
            assert!(
                matches!($call, Err(StoreError::DeviceRemoved)),
                "revoked actor passed {}",
                stringify!($call)
            );
        };
    }
    denied!(store.pending_member_login_requests(&owner, 100));
    denied!(store.approve_new_member_login_request(&owner, &login.request_id, 100));
    denied!(store.bind_existing_member_login_request(
        &owner,
        &login.request_id,
        &member.membership_id,
        100
    ));
    denied!(store.reject_member_login_request(&owner, &login.request_id, 100));
    denied!(store.create_member_login_grant(
        &owner,
        &member.membership_id,
        "unauthorized-grant",
        100,
        600
    ));
    denied!(store.pending_member_rename_requests(&owner, 100));
    denied!(store.approve_member_rename_request(&owner, &rename.request_id, 100));
    denied!(store.reject_member_rename_request(&owner, &rename.request_id, 100));
    denied!(store.add_device_less_member(&owner, "Unauthorized member", "unauthorized-member"));
    denied!(store.rename_active_membership(
        &owner,
        &member.membership_id,
        "Unauthorized name",
        "unauthorized-name",
        100
    ));
    denied!(store.rename_active_device(
        &owner,
        &member.device_id,
        "Unauthorized phone",
        "unauthorized-phone"
    ));
    denied!(store.revoke_family_device(&owner, &member.device_id, 100));
    denied!(store.hard_delete_membership(&owner, &member.membership_id, 100));
    denied!(store.rename_family(&owner, "Unauthorized family"));
    denied!(store.delete_family(&owner, "家庭"));

    assert_eq!(
        request_status(&store, "member_login_requests", &login.request_id),
        "pending",
        "authorization must run before expiration housekeeping"
    );
    assert_eq!(
        request_status(&store, "member_rename_requests", &rename.request_id),
        "pending"
    );
    assert_eq!(store.active_memberships(&owner.family_id).unwrap().len(), 2);
    assert_eq!(
        store
            .visible_active_devices(&owner.family_id, &member.membership_id, false)
            .unwrap()[0]
            .device_name,
        "Member phone"
    );
    assert!(store.authenticate("member-access", 100).unwrap().is_some());
}

#[test]
fn revoked_member_cannot_change_cancel_or_delete_its_identity() {
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    family(&store);
    let owner = store.authenticate("owner-access", 2).unwrap().unwrap();
    let member = member_principal(&store, &owner);
    let rename = store
        .create_member_rename_request(&member, "Requested name", "requested-name", 15, 60)
        .unwrap();
    store
        .revoke_family_device(&owner, &member.device_id, 20)
        .unwrap();

    assert!(matches!(
        store.create_member_rename_request(&member, "Other name", "other-name", 100, 60),
        Err(StoreError::DeviceRemoved)
    ));
    assert!(matches!(
        store.cancel_own_member_rename_request(&member, 100),
        Err(StoreError::DeviceRemoved)
    ));
    assert!(matches!(
        store.hard_delete_membership(&member, &member.membership_id, 100),
        Err(StoreError::DeviceRemoved)
    ));
    assert!(matches!(
        store.rename_active_device(&member, &member.device_id, "Other phone", "other-phone"),
        Err(StoreError::DeviceRemoved)
    ));
    assert!(matches!(
        store.revoke_family_device(&member, &member.device_id, 100),
        Err(StoreError::DeviceRemoved)
    ));
    assert_eq!(
        request_status(&store, "member_rename_requests", &rename.request_id),
        "pending"
    );
    assert_eq!(store.active_memberships(&owner.family_id).unwrap().len(), 2);
}

#[test]
fn live_member_cannot_use_owner_identity_apis_or_manage_another_identity() {
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    family(&store);
    let owner = store.authenticate("owner-access", 2).unwrap().unwrap();
    let member = member_principal(&store, &owner);
    let login = login_request(&store);

    assert!(matches!(
        store.pending_member_login_requests(&member, 100),
        Err(StoreError::ForbiddenIdentityAdministration)
    ));
    assert!(matches!(
        store.approve_new_member_login_request(&member, &login.request_id, 100),
        Err(StoreError::ForbiddenIdentityAdministration)
    ));
    assert!(matches!(
        store.add_device_less_member(&member, "Unauthorized member", "unauthorized-member"),
        Err(StoreError::ForbiddenIdentityAdministration)
    ));
    assert!(matches!(
        store.rename_active_device(&member, &owner.device_id, "Other phone", "other-phone"),
        Err(StoreError::ForbiddenIdentityAdministration)
    ));
    assert!(matches!(
        store.revoke_family_device(&member, &owner.device_id, 100),
        Err(StoreError::ForbiddenIdentityAdministration)
    ));
    assert!(matches!(
        store.hard_delete_membership(&member, &owner.membership_id, 100),
        Err(StoreError::ForbiddenIdentityAdministration)
    ));
    assert!(matches!(
        store.delete_family(&member, "家庭"),
        Err(StoreError::ForbiddenIdentityAdministration)
    ));
    assert_eq!(
        request_status(&store, "member_login_requests", &login.request_id),
        "pending"
    );
    // A live member keeps the expressly permitted self-service paths.
    store
        .rename_active_device(
            &member,
            &member.device_id,
            "My renamed phone",
            "my-renamed-phone",
        )
        .unwrap();
    store
        .hard_delete_membership(&member, &member.membership_id, 100)
        .unwrap();
    assert_eq!(store.active_memberships(&owner.family_id).unwrap().len(), 1);
    assert!(matches!(
        store.create_member_rename_request(&member, "Deleted member", "deleted-member", 101, 60),
        Err(StoreError::MembershipDeleted)
    ));
}

fn assert_terminal_identity_reason(action: &str) {
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    family(&store);
    let owner = store.authenticate("owner-access", 2).unwrap().unwrap();
    let request = login_request(&store);
    match action {
        "takeover" => {
            store
                .owner_login(
                    20,
                    "identity-takeover-request-00000001",
                    "Replacement phone",
                    true,
                    |_, _, _| {
                        (
                            "replacement-access".to_owned(),
                            "replacement-refresh".to_owned(),
                        )
                    },
                )
                .unwrap();
        }
        "refresh_replay" => {
            store
                .refresh_session(20, "owner-refresh", Some("first-rotation"), |_, _| {
                    ("rotated-access".to_owned(), "rotated-refresh".to_owned())
                })
                .unwrap();
            assert!(matches!(
                store.refresh_session(21, "owner-refresh", Some("replayed-rotation"), |_, _| {
                    ("replayed-access".to_owned(), "replayed-refresh".to_owned())
                }),
                Err(StoreError::RefreshTokenReplay)
            ));
        }
        "family_deleted" => store.delete_family(&owner, "家庭").unwrap(),
        _ => unreachable!(),
    }
    let errors = [
        store
            .rename_family(&owner, "Unauthorized family")
            .unwrap_err(),
        store
            .add_device_less_member(&owner, "Unauthorized member", "unauthorized-member")
            .unwrap_err(),
        store
            .pending_member_login_requests(&owner, 100)
            .unwrap_err(),
        store
            .approve_new_member_login_request(&owner, &request.request_id, 100)
            .unwrap_err(),
        store.delete_family(&owner, "家庭").unwrap_err(),
    ];
    for error in errors {
        assert!(
            match action {
                "family_deleted" => matches!(error, StoreError::FamilyDeleted),
                _ => matches!(error, StoreError::AuthenticationInvalid),
            },
            "{action} returned the wrong terminal reason: {error:?}"
        );
    }
    if action != "family_deleted" {
        assert_eq!(
            request_status(&store, "member_login_requests", &request.request_id),
            "pending"
        );
    }
}

#[test]
fn owner_takeover_stale_identity_does_not_report_device_removal() {
    assert_terminal_identity_reason("takeover");
}

#[test]
fn refresh_replay_stale_identity_does_not_report_device_removal() {
    assert_terminal_identity_reason("refresh_replay");
}

#[test]
fn deleted_family_stale_identity_preserves_family_deleted_reason() {
    assert_terminal_identity_reason("family_deleted");
}

#[test]
fn revoked_principal_cannot_write_causal_bundle_media_or_source_relations() {
    use serde_json::json;
    use sha2::{Digest, Sha256};
    use std::collections::BTreeMap;
    use uuid::Uuid;

    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    family(&store);
    let owner = store.authenticate("owner-access", 2).unwrap().unwrap();
    let baby_id = Uuid::new_v4().to_string();
    let media_id = Uuid::new_v4().to_string();
    let bundle_id = Uuid::new_v4().to_string();
    let record_id = Uuid::new_v4().to_string();
    let other_record_id = Uuid::new_v4().to_string();
    let incoming_path = directory.path().join("verified-upload");
    std::fs::write(&incoming_path, b"synthetic-media").unwrap();
    let digest = hex::encode(Sha256::digest(b"synthetic-media"));
    let incoming =
        VerifiedCausalMediaPreimage::verify(incoming_path.clone(), &digest, 1024).unwrap();
    store
        .revoke_family_device(&owner, &owner.device_id, 20)
        .unwrap();

    let row_counts = || {
        let connection = store.connect().unwrap();
        [
            "entities",
            "sync_bundles",
            "media_publications",
            "causal_media_uploads",
            "causal_media_staging",
            "causal_media_gc_state",
            "source_relations",
            "source_relation_mutation_receipts",
        ]
        .map(|table| {
            connection
                .query_row(&format!("SELECT COUNT(*) FROM {table}"), [], |row| {
                    row.get::<_, i64>(0)
                })
                .unwrap()
        })
    };
    let before_counts = row_counts();
    let before_revision = store.current_revision(&owner.family_id).unwrap();
    macro_rules! denied {
        ($call:expr) => {
            assert!(
                matches!($call, Err(StoreError::DeviceRemoved)),
                "revoked actor passed {}",
                stringify!($call)
            );
        };
    }
    denied!(store.causal_commit(
        &owner,
        vec![CausalMutation {
            mutation_id: Uuid::new_v4().to_string(),
            base_version: None,
            entity_type: "baby".to_owned(),
            client_uuid: baby_id,
            root: json!({
                "nickname": "Synthetic baby",
                "sex": "female",
                "birthday": "2025-01-02",
                "avatar_media_uuid": null,
                "updated_at": 100,
            })
            .as_object()
            .unwrap()
            .clone(),
            media: vec![],
            deleted: false,
        }],
        100
    ));
    denied!(store.stage_bundle(
        &owner,
        &bundle_id,
        crate::model::Entity {
            entity_type: "fulfillment_candidate".to_owned(),
            client_uuid: Uuid::new_v4().to_string(),
            updated_at: 100,
            deleted_at: None,
            payload: serde_json::Map::new(),
        },
        vec![],
        100
    ));
    denied!(store.commit_bundle(&owner, &bundle_id, &BTreeMap::new(), 100, 100));
    denied!(store.mark_bundle_media_prepared(&owner, &bundle_id, std::slice::from_ref(&media_id)));
    denied!(store.reserve_causal_media_upload(&owner, &media_id, 100));
    denied!(store.stage_verified_causal_media_preimage(
        &owner,
        &media_id,
        &incoming,
        100,
        DEFAULT_CAUSAL_MEDIA_STAGING_LIMITS
    ));
    denied!(store.bind_empty_causal_media_preimage(
        &owner,
        &media_id,
        &digest,
        100,
        DEFAULT_CAUSAL_MEDIA_STAGING_LIMITS
    ));
    denied!(store.declare_source_relation(
        &owner,
        DeclareSourceRelationInput {
            mutation_id: Uuid::new_v4().to_string(),
            record_client_uuid: record_id.clone(),
            equivalent_to_client_uuid: other_record_id.clone(),
            expected_record_version: "record-version".to_owned(),
            expected_other_version: "other-record-version".to_owned(),
        },
        100
    ));
    denied!(store.resolve_source_relation_group(
        &owner,
        ResolveSourceRelationGroupInput {
            mutation_id: Uuid::new_v4().to_string(),
            member_client_uuids: vec![record_id.clone(), other_record_id.clone()],
            display_client_uuid: record_id.clone(),
            expected_versions: BTreeMap::from([
                (record_id, "record-version".to_owned()),
                (other_record_id, "other-record-version".to_owned()),
            ]),
        },
        100
    ));

    assert_eq!(row_counts(), before_counts);
    assert_eq!(
        store.current_revision(&owner.family_id).unwrap(),
        before_revision
    );
    assert_eq!(std::fs::read(incoming_path).unwrap(), b"synthetic-media");
}
