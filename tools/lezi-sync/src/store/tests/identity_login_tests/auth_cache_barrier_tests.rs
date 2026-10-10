//! US-032: every terminal invalidation wins over delayed cache work.
use super::*;
use rusqlite::OptionalExtension;
use std::sync::{mpsc, Arc, Mutex};
use std::time::Duration;

#[derive(Clone, Copy, Debug)]
enum TerminalAction {
    Revoke,
    Takeover,
    MemberRemoval,
    RefreshReplay,
}

#[derive(Clone, Copy, Debug)]
enum CachePath {
    Miss,
    FreshHit,
    LastUsedWrite,
}

fn member_session(store: &Store, owner: &Principal, name: &str) -> CreatedDeviceSession {
    let membership = store.add_device_less_member(owner, name, name).unwrap();
    store
        .create_member_login_grant(owner, &membership, name, 100, 600)
        .unwrap();
    store
        .claim_member_login_grant(name, "Synthetic phone", 100, |_, _, _| {
            (format!("{name}-access"), format!("{name}-refresh"))
        })
        .unwrap()
}

fn check_terminal_barrier(action: TerminalAction, path: CachePath) {
    let directory = TempDir::new().unwrap();
    let database = directory.path().join("lezi.db");
    let store = Store::open(&database).unwrap();
    let owner_session = store
        .create_family(
            CreateFamilyInput {
                now: 100,
                create_request_id: "auth-generation-matrix-request-00001",
                display_name: "Synthetic owner",
                display_name_key: "synthetic-owner",
                family_name: "Synthetic family",
                device_name: "Synthetic owner phone",
                owner_root_fingerprint: None,
            },
            |_, _, _| ("owner-access".into(), "owner-refresh".into()),
        )
        .unwrap();
    let owner = Principal {
        family_id: owner_session.family_id.clone(),
        membership_id: owner_session.membership_id.clone(),
        device_id: owner_session.device_id.clone(),
        role: "owner".into(),
    };
    // An unrelated live device proves invalidation does not revoke the whole family.
    let peer = member_session(&store, &owner, "synthetic-peer");
    let target = match action {
        TerminalAction::MemberRemoval => member_session(&store, &owner, "synthetic-target"),
        TerminalAction::RefreshReplay => store
            .refresh_session(100, "owner-refresh", Some("first-rotation"), |_, _| {
                ("rotated-access".into(), "rotated-refresh".into())
            })
            .unwrap(),
        _ => owner_session,
    };
    match path {
        CachePath::Miss => {}
        CachePath::FreshHit => {
            assert!(store
                .authenticate(&target.access_token, 160)
                .unwrap()
                .is_some());
        }
        CachePath::LastUsedWrite => {
            assert!(store
                .authenticate(&target.access_token, 100)
                .unwrap()
                .is_some());
        }
    }
    let generation = store
        .auth_cache_generation
        .load(std::sync::atomic::Ordering::Relaxed);
    let (read_tx, read_rx) = mpsc::sync_channel(1);
    let (resume_tx, resume_rx) = mpsc::sync_channel(1);
    let resume_rx = Mutex::new(resume_rx);
    *store.auth_cache_read_hook.lock().unwrap() = Some(Arc::new(move || {
        read_tx.send(()).unwrap();
        resume_rx
            .lock()
            .unwrap()
            .recv_timeout(Duration::from_secs(10))
            .unwrap();
    }));
    let delayed = std::thread::spawn({
        let store = store.clone();
        let access = target.access_token.clone();
        move || store.authenticate(&access, 161)
    });
    read_rx
        .recv_timeout(Duration::from_secs(10))
        .expect("old authentication reached the exact cache boundary");
    let reason = match action {
        TerminalAction::Revoke => {
            store
                .revoke_family_device(&owner, &target.device_id, 162)
                .unwrap();
            "device_removed"
        }
        TerminalAction::Takeover => {
            store
                .owner_login(
                    162,
                    "takeover-matrix-request-0000001",
                    "Replacement owner",
                    true,
                    |_, _, _| ("replacement-access".into(), "replacement-refresh".into()),
                )
                .unwrap();
            "owner_takeover"
        }
        TerminalAction::MemberRemoval => {
            store
                .hard_delete_membership(&owner, &target.membership_id, 162)
                .unwrap();
            "membership_deleted"
        }
        TerminalAction::RefreshReplay => {
            assert!(matches!(
                store.refresh_session(162, "owner-refresh", Some("different-rotation"), |_, _| {
                    ("replay-access".into(), "replay-refresh".into())
                }),
                Err(StoreError::RefreshTokenReplay)
            ));
            "refresh_replay"
        }
    };
    assert!(
        store
            .auth_cache_generation
            .load(std::sync::atomic::Ordering::Relaxed)
            > generation
    );
    *store.auth_cache_read_hook.lock().unwrap() = None;
    // New requests start after the terminal operation returned, while the stale
    // read is still paused. No scheduling race is used as a substitute for this order.
    for _ in 0..3 {
        assert!(
            store
                .authenticate(&target.access_token, 163)
                .unwrap()
                .is_none(),
            "{action:?}/{path:?}: post-return request before old read release"
        );
    }
    resume_tx.send(()).unwrap();
    let old_result = delayed.join().unwrap().unwrap();
    match path {
        CachePath::FreshHit => {
            // The already-started read-only request can finish its snapshot. It
            // cannot install anything, and mutations separately recheck live ACL.
            assert!(old_result.is_some());
        }
        CachePath::Miss | CachePath::LastUsedWrite => assert!(old_result.is_none()),
    }
    for _ in 0..3 {
        assert!(
            store
                .authenticate(&target.access_token, 164)
                .unwrap()
                .is_none(),
            "{action:?}/{path:?}: late work cannot resurrect authorization"
        );
    }
    assert!(!store
        .auth_cache
        .read()
        .unwrap()
        .contains_key(&crate::hash_secret(&target.access_token)));
    assert_eq!(
        store
            .revoked_access_reason(&target.access_token)
            .unwrap()
            .as_deref(),
        Some(reason)
    );
    let connection = store.connect().unwrap();
    let last_used: Option<i64> = connection
        .query_row(
            "SELECT last_used_at FROM devices WHERE device_id = ?1",
            [&target.device_id],
            |row| row.get(0),
        )
        .optional()
        .unwrap();
    assert_eq!(
        last_used,
        if matches!(action, TerminalAction::MemberRemoval) {
            None
        } else {
            Some(162)
        },
        "{action:?}/{path:?}: delayed last-used cannot modify terminal identity"
    );
    assert!(store
        .authenticate(&peer.access_token, 164)
        .unwrap()
        .is_some());
    if matches!(action, TerminalAction::Takeover) {
        assert!(store
            .authenticate("replacement-access", 164)
            .unwrap()
            .is_some());
    }
    let reopened = Store::open(&database).unwrap();
    assert!(reopened
        .authenticate(&target.access_token, 165)
        .unwrap()
        .is_none());
    assert_eq!(
        reopened
            .revoked_access_reason(&target.access_token)
            .unwrap()
            .as_deref(),
        Some(reason)
    );
}

macro_rules! terminal_barrier_test {
    ($name:ident, $action:ident, $path:ident) => {
        #[test]
        fn $name() {
            check_terminal_barrier(TerminalAction::$action, CachePath::$path);
        }
    };
}
terminal_barrier_test!(revoke_wins_over_cache_miss, Revoke, Miss);
terminal_barrier_test!(revoke_wins_over_fresh_cache_hit, Revoke, FreshHit);
terminal_barrier_test!(revoke_wins_over_last_used_write, Revoke, LastUsedWrite);
terminal_barrier_test!(takeover_wins_over_cache_miss, Takeover, Miss);
terminal_barrier_test!(takeover_wins_over_fresh_cache_hit, Takeover, FreshHit);
terminal_barrier_test!(takeover_wins_over_last_used_write, Takeover, LastUsedWrite);
terminal_barrier_test!(member_removal_wins_over_cache_miss, MemberRemoval, Miss);
terminal_barrier_test!(
    member_removal_wins_over_fresh_cache_hit,
    MemberRemoval,
    FreshHit
);
terminal_barrier_test!(
    member_removal_wins_over_last_used_write,
    MemberRemoval,
    LastUsedWrite
);
terminal_barrier_test!(refresh_replay_wins_over_cache_miss, RefreshReplay, Miss);
terminal_barrier_test!(
    refresh_replay_wins_over_fresh_cache_hit,
    RefreshReplay,
    FreshHit
);
terminal_barrier_test!(
    refresh_replay_wins_over_last_used_write,
    RefreshReplay,
    LastUsedWrite
);
