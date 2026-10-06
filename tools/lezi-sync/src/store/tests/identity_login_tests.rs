//! Member login request store contract tests.

use super::super::*;
use super::test_support::family;
use tempfile::TempDir;

#[test]
fn expired_auth_cache_entries_are_dropped_and_not_accepted() {
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    let issued = store
        .create_family(
            CreateFamilyInput {
                now: 1_700_000_000,
                create_request_id: "auth-cache-expiry-request-000000001",
                display_name: "妈妈",
                display_name_key: "妈妈",
                family_name: "家庭",
                device_name: "手机",
                owner_root_fingerprint: None,
            },
            |_, _, _| {
                (
                    "cache-access-token".to_owned(),
                    "cache-refresh-token".to_owned(),
                )
            },
        )
        .unwrap();
    assert!(store
        .authenticate("cache-access-token", 1_700_000_000)
        .unwrap()
        .is_some());
    assert_eq!(store.auth_cache.read().unwrap().len(), 1);
    assert!(store
        .authenticate("cache-access-token", issued.access_expires_at - 1)
        .unwrap()
        .is_some());
    assert_eq!(store.auth_cache.read().unwrap().len(), 1);
    assert!(store
        .authenticate("cache-access-token", issued.access_expires_at)
        .unwrap()
        .is_none());
    assert!(store.auth_cache.read().unwrap().is_empty());
}

#[test]
fn owner_request_list_keeps_unexpired_approved_requests_with_their_status() {
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    let family_id = family(&store);
    let request = store
        .create_member_login_request(CreateMemberLoginRequestInput {
            now: 10,
            ttl_seconds: 600,
            max_pending: 8,
            display_name: "外婆",
            display_name_key: "外婆",
            device_name: "外婆手机",
            pending_secret: "pending-secret-for-approved-request",
        })
        .unwrap();

    store
        .approve_new_member_login_request(&family_id, &request.request_id, 11)
        .unwrap();

    let visible = store.pending_member_login_requests(&family_id, 12).unwrap();
    assert_eq!(visible.len(), 1);
    assert_eq!(visible[0].request_id, request.request_id);
    assert_eq!(
        serde_json::to_value(&visible[0]).unwrap()["status"],
        "approved"
    );

    store
        .reject_member_login_request(&family_id, &request.request_id, 13)
        .unwrap();
    assert!(store
        .pending_member_login_requests(&family_id, 14)
        .unwrap()
        .is_empty());

    let replacement = store
        .create_member_login_request(CreateMemberLoginRequestInput {
            now: 15,
            ttl_seconds: 600,
            max_pending: 8,
            display_name: "外婆",
            display_name_key: "外婆",
            device_name: "外婆新手机",
            pending_secret: "pending-secret-for-replacement-request",
        })
        .unwrap();
    store
        .approve_new_member_login_request(&family_id, &replacement.request_id, 16)
        .unwrap();
}

#[test]
fn approved_unclaimed_requests_still_count_toward_the_open_request_limit() {
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    let family_id = family(&store);
    let approved = store
        .create_member_login_request(CreateMemberLoginRequestInput {
            now: 10,
            ttl_seconds: 600,
            max_pending: 1,
            display_name: "外婆",
            display_name_key: "外婆",
            device_name: "外婆手机",
            pending_secret: "pending-secret-that-remains-open",
        })
        .unwrap();
    store
        .approve_new_member_login_request(&family_id, &approved.request_id, 11)
        .unwrap();

    let error = store
        .create_member_login_request(CreateMemberLoginRequestInput {
            now: 12,
            ttl_seconds: 600,
            max_pending: 1,
            display_name: "爷爷",
            display_name_key: "爷爷",
            device_name: "爷爷手机",
            pending_secret: "pending-secret-over-open-limit",
        })
        .unwrap_err();
    assert!(matches!(error, StoreError::MemberRequestLimit));
}
