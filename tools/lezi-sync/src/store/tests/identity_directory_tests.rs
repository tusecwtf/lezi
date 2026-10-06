//! Authenticated family-directory snapshot consistency tests.

use std::sync::mpsc;
use std::thread;

use rusqlite::{params, TransactionBehavior};
use tempfile::TempDir;

use super::super::*;
use super::test_support::family;

#[test]
fn directory_loader_returns_one_snapshot_across_concurrent_aba() {
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    let family_id = family(&store);
    let membership_id = store.active_memberships(&family_id).unwrap()[0]
        .membership_id
        .clone();
    let original = store
        .family_directory_snapshot(&family_id, &membership_id, true)
        .unwrap();
    let original_name = original.memberships[0].display_name.clone();
    let original_device_name = original.visible_devices[0].device_name.clone();
    let hook = super::super::identity::session::family_directory_test_hook::install(&family_id);
    let reader_store = store.clone();
    let reader_family = family_id.clone();
    let reader_membership = membership_id.clone();
    let (result_tx, result_rx) = mpsc::channel();
    let reader = thread::spawn(move || {
        result_tx
            .send(reader_store.family_directory_snapshot(&reader_family, &reader_membership, true))
            .unwrap();
    });

    hook.wait_entered(1);
    update_directory_names(
        &store,
        &membership_id,
        "temporary member",
        "temporary device",
    );
    hook.release(1);
    hook.wait_entered(2);
    update_directory_names(
        &store,
        &membership_id,
        &original_name,
        &original_device_name,
    );
    hook.release(2);

    let snapshot = result_rx.recv().unwrap().unwrap();
    reader.join().unwrap();
    assert_eq!(snapshot.generation, original.generation);
    assert_eq!(snapshot.memberships[0].display_name, original_name);
    assert_eq!(
        snapshot.visible_devices[0].device_name,
        original_device_name
    );
}

fn update_directory_names(
    store: &Store,
    membership_id: &str,
    membership_name: &str,
    device_name: &str,
) {
    let mut connection = store.connect().unwrap();
    let transaction = connection
        .transaction_with_behavior(TransactionBehavior::Immediate)
        .unwrap();
    transaction
        .execute(
            "UPDATE memberships SET display_name = ?1 WHERE membership_id = ?2",
            params![membership_name, membership_id],
        )
        .unwrap();
    transaction
        .execute(
            "UPDATE devices SET device_name = ?1 WHERE membership_id = ?2 AND status = 'active'",
            params![device_name, membership_id],
        )
        .unwrap();
    transaction.commit().unwrap();
}
