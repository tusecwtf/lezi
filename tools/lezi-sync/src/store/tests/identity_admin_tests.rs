//! Authentication snapshots cannot authorize identity writes after revocation wins.

use super::super::*;
use super::test_support::family;
use rusqlite::types::Value;
use tempfile::TempDir;

struct Fixture {
    _directory: TempDir,
    store: Store,
    owner: Principal,
    member: Principal,
    peer: Principal,
    request_id: String,
    approved_request_id: String,
    rename_request_id: String,
}

impl Fixture {
    fn new() -> Self {
        let directory = TempDir::new().unwrap();
        let store = Store::open(directory.path().join("lezi.db")).unwrap();
        family(&store);
        let owner = store.authenticate("owner-access", 2).unwrap().unwrap();
        let member = Self::member(&store, &owner, "Member", "member-access");
        let peer = Self::member(&store, &owner, "Peer", "peer-access");
        let rename_request_id = store
            .create_member_rename_request(&member, "Renamed", "renamed", 10, 600)
            .unwrap()
            .request_id;
        let request_id = Self::request(&store, "Pending", 10, 600);
        let approved_request_id = Self::request(&store, "Approved", 10, 600);
        store
            .approve_new_member_login_request(&owner, &approved_request_id, 11)
            .unwrap();
        // Expiry housekeeping is itself a write and must not run for a stale actor.
        Self::request(&store, "Expired", 10, 1);
        store
            .create_member_rename_request(&peer, "Expired rename", "expired rename", 10, 1)
            .unwrap();
        Self {
            _directory: directory,
            store,
            owner,
            member,
            peer,
            request_id,
            approved_request_id,
            rename_request_id,
        }
    }

    fn member(store: &Store, owner: &Principal, name: &str, access: &str) -> Principal {
        let id = store.add_device_less_member(owner, name, name).unwrap();
        store
            .create_member_login_grant(owner, &id, name, 3, 600)
            .unwrap();
        store
            .claim_member_login_grant(name, "Phone", 4, |_, _, _| {
                (access.to_owned(), format!("refresh-{access}"))
            })
            .unwrap();
        store.authenticate(access, 5).unwrap().unwrap()
    }

    fn request(store: &Store, name: &str, now: i64, ttl_seconds: i64) -> String {
        store
            .create_member_login_request(CreateMemberLoginRequestInput {
                now,
                ttl_seconds,
                max_pending: 20,
                display_name: name,
                display_name_key: name,
                device_name: "New phone",
                pending_secret: name,
            })
            .unwrap()
            .request_id
    }

    fn takeover(&self) -> Principal {
        self.store
            .owner_login(
                20,
                "takeover-request-0000001",
                "New owner device",
                true,
                |_, _, _| {
                    (
                        "replacement-access".to_owned(),
                        "replacement-refresh".to_owned(),
                    )
                },
            )
            .unwrap();
        self.store
            .authenticate("replacement-access", 21)
            .unwrap()
            .unwrap()
    }

    fn administer(&self, operation: &str, actor: &Principal) -> Result<(), StoreError> {
        match operation {
            "rename_family" => self.store.rename_family(actor, "Changed family"),
            "pending_login" => self
                .store
                .pending_member_login_requests(actor, 30)
                .map(|_| ()),
            "approve_login" => {
                self.store
                    .approve_new_member_login_request(actor, &self.request_id, 30)
            }
            "replay_login_approval" => {
                self.store
                    .approve_new_member_login_request(actor, &self.approved_request_id, 30)
            }
            "bind_login" => self.store.bind_existing_member_login_request(
                actor,
                &self.request_id,
                &self.member.membership_id,
                30,
            ),
            "reject_login" => self
                .store
                .reject_member_login_request(actor, &self.request_id, 30),
            "create_grant" => self
                .store
                .create_member_login_grant(actor, &self.member.membership_id, "new-grant", 30, 600)
                .map(|_| ()),
            "pending_rename" => self
                .store
                .pending_member_rename_requests(actor, 30)
                .map(|_| ()),
            "approve_rename" => self
                .store
                .approve_member_rename_request(actor, &self.rename_request_id, 30)
                .map(|_| ()),
            "reject_rename" => {
                self.store
                    .reject_member_rename_request(actor, &self.rename_request_id, 30)
            }
            "add_member" => self
                .store
                .add_device_less_member(actor, "New member", "new member")
                .map(|_| ()),
            "rename_member" => self.store.rename_active_membership(
                actor,
                &self.member.membership_id,
                "Changed",
                "changed",
                30,
            ),
            "rename_device" => self.store.rename_active_device(
                actor,
                &self.member.device_id,
                "Changed device",
                "changed device",
            ),
            "delete_member" => {
                self.store
                    .hard_delete_membership(actor, &self.member.membership_id, 30)
            }
            "revoke_device" => self
                .store
                .revoke_family_device(actor, &self.member.device_id, 30),
            "delete_family" => self.store.delete_family(actor, "家庭"),
            _ => panic!("unknown operation"),
        }
    }

    fn member_action(&self, operation: &str, actor: &Principal) -> Result<(), StoreError> {
        match operation {
            "request_rename" => self
                .store
                .create_member_rename_request(actor, "Changed", "changed", 30, 600)
                .map(|_| ()),
            "cancel_rename" => self.store.cancel_own_member_rename_request(actor, 30),
            "rename_device" => self.store.rename_active_device(
                actor,
                &actor.device_id,
                "Changed device",
                "changed device",
            ),
            "leave" => self
                .store
                .hard_delete_membership(actor, &actor.membership_id, 30),
            "logout" => self.store.revoke_family_device(actor, &actor.device_id, 30),
            _ => panic!("unknown operation"),
        }
    }
}

const ADMIN_OPERATIONS: &[&str] = &[
    "rename_family",
    "pending_login",
    "approve_login",
    "replay_login_approval",
    "bind_login",
    "reject_login",
    "create_grant",
    "pending_rename",
    "approve_rename",
    "reject_rename",
    "add_member",
    "rename_member",
    "rename_device",
    "delete_member",
    "revoke_device",
    "delete_family",
];
const MEMBER_OPERATIONS: &[&str] = &[
    "request_rename",
    "cancel_rename",
    "rename_device",
    "leave",
    "logout",
];

/// Compare every persistent table, including revision, receipt, denial and grant rows.
fn snapshot(store: &Store) -> Vec<(String, Vec<Vec<Value>>)> {
    let connection = store.connect().unwrap();
    let tables = connection
        .prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
        .unwrap()
        .query_map([], |row| row.get::<_, String>(0))
        .unwrap()
        .collect::<Result<Vec<_>, _>>()
        .unwrap();
    tables
        .into_iter()
        .map(|name| {
            let mut statement = connection
                .prepare(&format!("SELECT * FROM \"{}\"", name.replace('"', "\"\"")))
                .unwrap();
            let columns = statement.column_count();
            let mut rows = statement
                .query_map([], |row| {
                    (0..columns)
                        .map(|column| row.get::<_, Value>(column))
                        .collect::<Result<Vec<_>, _>>()
                })
                .unwrap()
                .collect::<Result<Vec<_>, _>>()
                .unwrap();
            rows.sort_by_cached_key(|row| format!("{row:?}"));
            (name, rows)
        })
        .collect()
}

#[test]
fn authenticated_owner_snapshot_cannot_administer_after_takeover_or_revocation() {
    let mut failures = Vec::new();
    for takeover in [false, true] {
        for operation in ADMIN_OPERATIONS {
            let fx = Fixture::new();
            // This is exactly the handler's authenticate -> competing commit -> Store call interleave.
            let stale = fx.store.authenticate("owner-access", 19).unwrap().unwrap();
            if takeover {
                fx.takeover();
            } else {
                fx.store
                    .revoke_family_device(&fx.owner, &fx.owner.device_id, 20)
                    .unwrap();
            }
            let before = snapshot(&fx.store);
            let result = fx.administer(operation, &stale);
            if !matches!(result, Err(StoreError::DeviceRemoved)) {
                failures.push(format!("{operation}, takeover={takeover}: {result:?}"));
            }
            if snapshot(&fx.store) != before {
                failures.push(format!(
                    "{operation}, takeover={takeover}: persistent state changed"
                ));
            }
        }
    }
    assert!(failures.is_empty(), "{}", failures.join("\n"));
}

#[test]
fn authenticated_member_snapshot_cannot_mutate_after_device_or_membership_removal() {
    for delete_membership in [false, true] {
        for operation in MEMBER_OPERATIONS {
            let fx = Fixture::new();
            let stale = fx.store.authenticate("member-access", 19).unwrap().unwrap();
            if delete_membership {
                fx.store
                    .hard_delete_membership(&fx.owner, &stale.membership_id, 20)
                    .unwrap();
            } else {
                fx.store
                    .revoke_family_device(&fx.owner, &stale.device_id, 20)
                    .unwrap();
            }
            let before = snapshot(&fx.store);
            let result = fx.member_action(operation, &stale);
            assert!(
                if delete_membership {
                    matches!(result, Err(StoreError::MembershipDeleted))
                } else {
                    matches!(result, Err(StoreError::DeviceRemoved))
                },
                "{operation}, delete_membership={delete_membership}: {result:?}"
            );
            assert_eq!(
                snapshot(&fx.store),
                before,
                "rejected {operation} wrote persistent state"
            );
        }
    }
}

#[test]
fn live_member_cannot_call_owner_admin_store_methods_or_manage_another_member() {
    for operation in ADMIN_OPERATIONS {
        let fx = Fixture::new();
        let before = snapshot(&fx.store);
        let result = fx.administer(operation, &fx.peer);
        assert!(
            matches!(result, Err(StoreError::ForbiddenIdentityAdministration)),
            "{operation}: {result:?}"
        );
        assert_eq!(
            snapshot(&fx.store),
            before,
            "forbidden {operation} wrote persistent state"
        );
    }
}

#[test]
fn replacement_owner_can_administer_and_active_member_can_manage_own_identity() {
    for operation in ADMIN_OPERATIONS {
        let fx = Fixture::new();
        let replacement = fx.takeover();
        assert!(
            fx.administer(operation, &replacement).is_ok(),
            "{operation}"
        );
    }
    for operation in MEMBER_OPERATIONS {
        let fx = Fixture::new();
        assert!(
            fx.member_action(operation, &fx.member).is_ok(),
            "{operation}"
        );
    }
}

#[test]
fn owner_cannot_leave_through_member_path_or_request_a_member_rename() {
    let fx = Fixture::new();
    for operation in ["request_rename", "cancel_rename", "leave"] {
        let before = snapshot(&fx.store);
        let result = fx.member_action(operation, &fx.owner);
        assert!(
            matches!(
                result,
                Err(StoreError::ForbiddenIdentityAdministration)
                    | Err(StoreError::MembershipNotFound)
            ),
            "{operation}: {result:?}"
        );
        assert_eq!(snapshot(&fx.store), before);
    }
}

#[test]
fn forged_owner_role_and_cross_family_actor_are_rejected_before_any_write() {
    let fx = Fixture::new();
    let mut forged = fx.member.clone();
    forged.role = "owner".to_owned();
    for operation in ADMIN_OPERATIONS {
        let before = snapshot(&fx.store);
        assert!(
            matches!(
                fx.administer(operation, &forged),
                Err(StoreError::MembershipDeleted)
            ),
            "{operation}"
        );
        assert_eq!(snapshot(&fx.store), before);
    }
    let mut crossed = fx.owner.clone();
    crossed.family_id = "a-different-family".to_owned();
    for operation in ADMIN_OPERATIONS {
        let before = snapshot(&fx.store);
        assert!(
            matches!(
                fx.administer(operation, &crossed),
                Err(StoreError::MembershipDeleted)
            ),
            "{operation}"
        );
        assert_eq!(snapshot(&fx.store), before);
    }
}

#[test]
fn self_logout_is_current_device_only_but_member_may_rename_a_sibling_device() {
    let fx = Fixture::new();
    fx.store
        .create_member_login_grant(
            &fx.owner,
            &fx.member.membership_id,
            "sibling-grant",
            15,
            600,
        )
        .unwrap();
    let sibling = fx
        .store
        .claim_member_login_grant("sibling-grant", "Sibling", 16, |_, _, _| {
            ("sibling-access".to_owned(), "sibling-refresh".to_owned())
        })
        .unwrap();
    fx.store
        .rename_active_device(
            &fx.member,
            &sibling.device_id,
            "Renamed sibling",
            "renamed sibling",
        )
        .unwrap();
    let before = snapshot(&fx.store);
    assert!(matches!(
        fx.store
            .revoke_family_device(&fx.member, &sibling.device_id, 30),
        Err(StoreError::ForbiddenIdentityAdministration)
    ));
    assert_eq!(snapshot(&fx.store), before);
    fx.store
        .revoke_family_device(&fx.owner, &sibling.device_id, 31)
        .unwrap();
    fx.store
        .revoke_family_device(&fx.owner, &sibling.device_id, 32)
        .unwrap();
}

#[test]
fn deleted_family_actor_cannot_administer_a_replacement_family() {
    for operation in ADMIN_OPERATIONS {
        let fx = Fixture::new();
        let stale = fx.store.authenticate("owner-access", 19).unwrap().unwrap();
        fx.store.delete_family(&fx.owner, "家庭").unwrap();
        fx.store
            .create_family(
                CreateFamilyInput {
                    now: 21,
                    create_request_id: "replacement-family-request-0001",
                    display_name: "Replacement owner",
                    display_name_key: "replacement owner",
                    family_name: "Replacement family",
                    device_name: "Replacement phone",
                    owner_root_fingerprint: None,
                },
                |_, _, _| {
                    (
                        "new-family-access".to_owned(),
                        "new-family-refresh".to_owned(),
                    )
                },
            )
            .unwrap();
        assert!(fx
            .store
            .authenticate("new-family-access", 22)
            .unwrap()
            .is_some());
        let before = snapshot(&fx.store);
        assert!(
            matches!(
                fx.administer(operation, &stale),
                Err(StoreError::MembershipDeleted)
            ),
            "{operation}"
        );
        assert_eq!(snapshot(&fx.store), before);
    }
}

#[test]
fn identity_administration_denial_uses_existing_forbidden_http_shape() {
    let error = crate::ApiError::from(StoreError::ForbiddenIdentityAdministration);
    assert_eq!(error.status, axum::http::StatusCode::FORBIDDEN);
    assert_eq!(error.code, None);
    assert!(!error.authenticate);
}

#[test]
fn member_device_rename_rejects_invisible_and_missing_targets_the_same_way() {
    let fx = Fixture::new();
    let before = snapshot(&fx.store);
    for target in [fx.owner.device_id.as_str(), "missing-device"] {
        assert!(matches!(
            fx.store
                .rename_active_device(&fx.member, target, "Changed", "changed"),
            Err(StoreError::ForbiddenIdentityAdministration)
        ));
    }
    assert!(matches!(
        fx.store
            .rename_active_device(&fx.owner, "missing-device", "Changed", "changed"),
        Err(StoreError::DeviceNotFound)
    ));
    assert_eq!(snapshot(&fx.store), before);
}
