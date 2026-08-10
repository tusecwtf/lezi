//! Durable conflict-detail receipts and bounded page replay.
//!
//! This module owns receipt credentials, binding, page plans, and final-wire
//! byte accounting. [`Store`](super::Store) remains the public seam.

use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use rand::rngs::OsRng;
use rand::RngCore;
use rusqlite::{params, Transaction};
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};
use sha2::{Digest, Sha256};

use super::causal::ConflictBranchDetail;
use super::{migration_content_hash, CausalMediaItem, StoreError};

const SYSTEM_RECEIPT_PRINCIPAL: &str = "__conflict_snapshot_v2__";
const SNAPSHOT_RECEIPT_TTL_SECONDS: i64 = 10 * 60;
const MAX_BRANCHES_PER_PAGE: usize = 16;
const MAX_ENCODED_PAGE_BYTES: usize = 128 * 1024;
const MAX_RECEIPTS_PER_CONFLICT: usize = 64;
const CREDENTIAL_BYTES: usize = 32;
const SERIALIZER_CONTRACT: &str = "conflict-detail-page-serde-v1";

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ConflictDetailPageRequest {
    First,
    SnapshotToken(String),
    Continuation {
        snapshot_token: String,
        continuation: String,
    },
}

#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct ConflictDetailPage {
    pub conflict_id: String,
    pub stable_version_id: String,
    pub stable_root: Map<String, Value>,
    pub stable_media: Vec<CausalMediaItem>,
    pub branches: Vec<ConflictBranchDetail>,
    pub conflicting_paths: Vec<String>,
    pub auto_merged: Map<String, Value>,
    pub snapshot_token: String,
    pub expires_at: i64,
    pub page_index: usize,
    pub continuation: Option<String>,
    pub complete: bool,
}

pub(super) struct ConflictSnapshotMaterial {
    pub conflict_id: String,
    pub stable_version_id: String,
    pub stable_root: Map<String, Value>,
    pub stable_media: Vec<CausalMediaItem>,
    pub branches: Vec<ConflictBranchDetail>,
    pub conflicting_paths: Vec<String>,
    pub auto_merged: Map<String, Value>,
}

pub(super) struct ConflictSnapshotBinding<'a> {
    pub family_id: &'a str,
    pub conflict_id: &'a str,
    pub kind: &'a str,
    pub entity_type: &'a str,
    pub client_uuid: &'a str,
    pub stable_version_id: &'a str,
    pub branch_version_ids: &'a [String],
    pub receipt_key: &'a [u8],
}

impl ConflictSnapshotBinding<'_> {
    fn fingerprint(&self) -> String {
        let mut parts = vec![
            "conflict_snapshot_v2",
            self.family_id,
            self.conflict_id,
            self.kind,
            self.entity_type,
            self.client_uuid,
            self.stable_version_id,
        ];
        parts.extend(self.branch_version_ids.iter().map(String::as_str));
        migration_content_hash(&parts)
    }

    fn receipt_id(&self) -> String {
        format!("snapshot-receipts:{}", self.conflict_id)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct StoredSnapshotReceipt {
    token_nonce: String,
    fingerprint: String,
    expires_at_seconds: i64,
    page_ends: Vec<usize>,
    continuation_nonces: Vec<String>,
    page_digests: Vec<String>,
    integrity_tag: String,
}

fn random_nonce() -> String {
    let mut bytes = [0_u8; CREDENTIAL_BYTES];
    OsRng.fill_bytes(&mut bytes);
    URL_SAFE_NO_PAD.encode(bytes)
}

impl StoredSnapshotReceipt {
    fn layout_binding(&self) -> String {
        serde_json::to_string(&(
            SERIALIZER_CONTRACT,
            &self.fingerprint,
            self.expires_at_seconds,
            &self.page_ends,
            &self.continuation_nonces,
        ))
        .expect("receipt layout is serializable")
    }

    fn token(&self, key: &[u8]) -> String {
        crate::derive_framed_token(
            key,
            "conflict-snapshot-token",
            &[&self.token_nonce, &self.layout_binding()],
        )
    }

    fn continuation(&self, key: &[u8], page_index: usize) -> Option<String> {
        self.continuation_nonces.get(page_index).map(|nonce| {
            crate::derive_framed_token(
                key,
                "conflict-snapshot-continuation",
                &[
                    &self.token_nonce,
                    &self.layout_binding(),
                    &page_index.to_string(),
                    nonce,
                ],
            )
        })
    }

    fn expected_integrity_tag(&self, key: &[u8]) -> String {
        crate::derive_framed_token(
            key,
            "conflict-snapshot-receipt",
            &[
                &self.token_nonce,
                &self.layout_binding(),
                &serde_json::to_string(&self.page_digests).expect("page digests are serializable"),
            ],
        )
    }
}

fn render_page(
    receipt: &StoredSnapshotReceipt,
    material: &ConflictSnapshotMaterial,
    receipt_key: &[u8],
    snapshot_token: &str,
    page_index: usize,
) -> Result<ConflictDetailPage, StoreError> {
    let end = *receipt
        .page_ends
        .get(page_index)
        .ok_or(StoreError::InvalidSnapshotToken)?;
    let start = page_index
        .checked_sub(1)
        .and_then(|previous| receipt.page_ends.get(previous).copied())
        .unwrap_or(0);
    if start > end || end > material.branches.len() {
        return Err(StoreError::InvalidStoredPayload);
    }
    let continuation = receipt.continuation(receipt_key, page_index);
    Ok(ConflictDetailPage {
        conflict_id: material.conflict_id.clone(),
        stable_version_id: material.stable_version_id.clone(),
        stable_root: material.stable_root.clone(),
        stable_media: material.stable_media.clone(),
        branches: material.branches[start..end].to_vec(),
        conflicting_paths: material.conflicting_paths.clone(),
        auto_merged: material.auto_merged.clone(),
        snapshot_token: snapshot_token.to_owned(),
        expires_at: receipt.expires_at_seconds.saturating_mul(1_000),
        page_index,
        complete: continuation.is_none(),
        continuation,
    })
}

fn checked_page(
    receipt: &StoredSnapshotReceipt,
    material: &ConflictSnapshotMaterial,
    receipt_key: &[u8],
    snapshot_token: &str,
    fingerprint: &str,
    now: i64,
    page_index: usize,
) -> Result<ConflictDetailPage, StoreError> {
    if !crate::constant_time_eq(
        receipt.expected_integrity_tag(receipt_key).as_bytes(),
        receipt.integrity_tag.as_bytes(),
    ) {
        return Err(StoreError::InvalidStoredPayload);
    }
    if !crate::constant_time_eq(
        receipt.token(receipt_key).as_bytes(),
        snapshot_token.as_bytes(),
    ) {
        return Err(StoreError::InvalidSnapshotToken);
    }
    if now >= receipt.expires_at_seconds {
        return Err(StoreError::SnapshotExpired);
    }
    if receipt.fingerprint != fingerprint {
        return Err(StoreError::SnapshotStale);
    }
    let result = render_page(receipt, material, receipt_key, snapshot_token, page_index)?;
    let encoded = serde_json::to_vec(&result)?;
    if encoded.len() > MAX_ENCODED_PAGE_BYTES {
        return Err(StoreError::ConflictSnapshotPageTooLarge);
    }
    if receipt.page_digests.get(page_index) != Some(&hex::encode(Sha256::digest(&encoded))) {
        return Err(StoreError::InvalidStoredPayload);
    }
    Ok(result)
}

fn plan_receipt(
    material: &ConflictSnapshotMaterial,
    binding: &ConflictSnapshotBinding<'_>,
    now: i64,
) -> Result<StoredSnapshotReceipt, StoreError> {
    let mut receipt = StoredSnapshotReceipt {
        token_nonce: random_nonce(),
        fingerprint: binding.fingerprint(),
        expires_at_seconds: now.saturating_add(SNAPSHOT_RECEIPT_TTL_SECONDS),
        page_ends: vec![],
        continuation_nonces: vec![],
        page_digests: vec![],
        integrity_tag: String::new(),
    };
    if material.branches.is_empty() {
        receipt.page_ends.push(0);
    } else {
        let mut offset = 0;
        while offset < material.branches.len() {
            let page_index = receipt.page_ends.len();
            receipt.page_ends.push(offset);
            receipt.continuation_nonces.push(random_nonce());
            let mut end = offset;
            while end < material.branches.len() && end - offset < MAX_BRANCHES_PER_PAGE {
                receipt.page_ends[page_index] = end + 1;
                if end + 1 == material.branches.len() {
                    receipt.continuation_nonces.truncate(page_index);
                }
                let token = receipt.token(binding.receipt_key);
                let candidate =
                    render_page(&receipt, material, binding.receipt_key, &token, page_index)?;
                if serde_json::to_vec(&candidate)?.len() > MAX_ENCODED_PAGE_BYTES {
                    break;
                }
                end += 1;
            }
            if end == offset {
                return Err(StoreError::ConflictSnapshotPageTooLarge);
            }
            receipt.page_ends[page_index] = end;
            if end < material.branches.len() && receipt.continuation_nonces.len() == page_index {
                receipt.continuation_nonces.push(random_nonce());
            }
            offset = end;
        }
    }
    let token = receipt.token(binding.receipt_key);
    receipt.page_digests = (0..receipt.page_ends.len())
        .map(|page_index| {
            let page = render_page(&receipt, material, binding.receipt_key, &token, page_index)?;
            Ok(hex::encode(Sha256::digest(serde_json::to_vec(&page)?)))
        })
        .collect::<Result<_, StoreError>>()?;
    receipt.integrity_tag = receipt.expected_integrity_tag(binding.receipt_key);
    Ok(receipt)
}

fn save_receipts(
    tx: &Transaction<'_>,
    binding: &ConflictSnapshotBinding<'_>,
    receipt_id: &str,
    receipts: &[StoredSnapshotReceipt],
    now: i64,
) -> Result<(), StoreError> {
    let receipt_json = serde_json::to_string(receipts)?;
    let updated = tx.execute(
        "UPDATE mutation_receipts
         SET content_hash = ?1, stable_version_id = ?2, receipt_json = ?3, created_at = ?4
         WHERE family_id = ?5 AND membership_id = ?6 AND entity_type = ?7
           AND client_uuid = ?8 AND mutation_id = ?9",
        params![
            binding.fingerprint(),
            binding.stable_version_id,
            receipt_json,
            now,
            binding.family_id,
            SYSTEM_RECEIPT_PRINCIPAL,
            binding.entity_type,
            binding.client_uuid,
            receipt_id,
        ],
    )?;
    if updated == 0 {
        tx.execute(
            "INSERT INTO mutation_receipts(
                family_id, membership_id, entity_type, client_uuid, mutation_id,
                content_hash, status, stable_version_id, conflict_id, receipt_json, created_at
             ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, 'accepted', ?7, ?8, ?9, ?10)",
            params![
                binding.family_id,
                SYSTEM_RECEIPT_PRINCIPAL,
                binding.entity_type,
                binding.client_uuid,
                receipt_id,
                binding.fingerprint(),
                binding.stable_version_id,
                binding.conflict_id,
                receipt_json,
                now,
            ],
        )?;
    }
    Ok(())
}

pub(super) fn open_conflict_detail_page(
    tx: &Transaction<'_>,
    binding: ConflictSnapshotBinding<'_>,
    material: ConflictSnapshotMaterial,
    request: ConflictDetailPageRequest,
    now: i64,
) -> Result<ConflictDetailPage, StoreError> {
    let fingerprint = binding.fingerprint();
    let receipt_id = binding.receipt_id();
    let mut receipts: Vec<StoredSnapshotReceipt> = super::causal::load_receipt(
        tx,
        binding.family_id,
        SYSTEM_RECEIPT_PRINCIPAL,
        binding.entity_type,
        binding.client_uuid,
        &receipt_id,
    )?
    .map(|(_, json)| serde_json::from_str(&json))
    .transpose()?
    .unwrap_or_default();

    let first_request = matches!(&request, ConflictDetailPageRequest::First);
    let requested = match request {
        ConflictDetailPageRequest::First => receipts
            .iter()
            .rev()
            .find(|receipt| receipt.fingerprint == fingerprint && now < receipt.expires_at_seconds)
            .map(|receipt| (receipt, receipt.token(binding.receipt_key), 0)),
        ConflictDetailPageRequest::SnapshotToken(token) => receipts
            .iter()
            .find(|receipt| {
                crate::constant_time_eq(
                    receipt.token(binding.receipt_key).as_bytes(),
                    token.as_bytes(),
                )
            })
            .map(|receipt| (receipt, token, 0)),
        ConflictDetailPageRequest::Continuation {
            snapshot_token,
            continuation,
        } => receipts
            .iter()
            .find(|receipt| {
                crate::constant_time_eq(
                    receipt.token(binding.receipt_key).as_bytes(),
                    snapshot_token.as_bytes(),
                )
            })
            .and_then(|receipt| {
                (0..receipt.continuation_nonces.len())
                    .find(|page_index| {
                        receipt
                            .continuation(binding.receipt_key, *page_index)
                            .is_some_and(|expected| {
                                crate::constant_time_eq(
                                    expected.as_bytes(),
                                    continuation.as_bytes(),
                                )
                            })
                    })
                    .map(|page_index| (receipt, snapshot_token, page_index + 1))
            }),
    };
    if let Some((receipt, token, page_index)) = requested {
        return checked_page(
            receipt,
            &material,
            binding.receipt_key,
            &token,
            &fingerprint,
            now,
            page_index,
        );
    }
    if !first_request {
        return Err(StoreError::InvalidSnapshotToken);
    }

    let receipt = plan_receipt(&material, &binding, now)?;
    let token = receipt.token(binding.receipt_key);
    if receipts.len() == MAX_RECEIPTS_PER_CONFLICT {
        receipts.remove(0);
    }
    receipts.push(receipt);
    save_receipts(tx, &binding, &receipt_id, &receipts, now)?;
    checked_page(
        receipts.last().expect("new receipt was appended"),
        &material,
        binding.receipt_key,
        &token,
        &fingerprint,
        now,
        0,
    )
}

#[cfg(test)]
pub(super) mod test_hook {
    use std::cell::RefCell;
    use std::collections::BTreeMap;
    use std::sync::{Arc, Condvar, Mutex, MutexGuard, OnceLock};
    use std::time::Duration;

    use rusqlite::Connection;

    const WAIT_TIMEOUT: Duration = Duration::from_secs(5);

    #[derive(Clone, Copy)]
    pub(crate) enum BusyOperation {
        Snapshot,
        Writer,
    }

    thread_local! {
        static BUSY_CONTEXT: RefCell<Option<(String, BusyOperation)>> = const { RefCell::new(None) };
    }

    #[derive(Default)]
    struct State {
        snapshot_busy: usize,
        writer_busy: usize,
        projection_loaded: bool,
        released: bool,
    }

    struct Hook {
        state: Mutex<State>,
        changed: Condvar,
        timeout: Duration,
    }

    impl Hook {
        fn new(timeout: Duration) -> Self {
            Self {
                state: Mutex::new(State::default()),
                changed: Condvar::new(),
                timeout,
            }
        }
    }

    fn lock_unpoisoned<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
        mutex
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    fn hooks() -> &'static Mutex<BTreeMap<String, Arc<Hook>>> {
        static HOOKS: OnceLock<Mutex<BTreeMap<String, Arc<Hook>>>> = OnceLock::new();
        HOOKS.get_or_init(Default::default)
    }

    pub(crate) struct Control {
        family_id: String,
        hook: Arc<Hook>,
    }

    pub(crate) fn install(family_id: &str) -> Control {
        install_with_timeout(family_id, WAIT_TIMEOUT)
    }

    fn install_with_timeout(family_id: &str, timeout: Duration) -> Control {
        let hook = Arc::new(Hook::new(timeout));
        let previous = lock_unpoisoned(hooks()).insert(family_id.to_owned(), hook.clone());
        assert!(previous.is_none());
        Control {
            family_id: family_id.to_owned(),
            hook,
        }
    }

    fn notify(family_id: &str, update: impl FnOnce(&mut State)) {
        let hook = lock_unpoisoned(hooks()).get(family_id).cloned();
        if let Some(hook) = hook {
            let mut state = lock_unpoisoned(&hook.state);
            update(&mut state);
            hook.changed.notify_all();
        }
    }

    fn busy_handler(attempt: i32) -> bool {
        if attempt == 0 {
            BUSY_CONTEXT.with(|context| {
                if let Some((family_id, operation)) = context.borrow().as_ref() {
                    notify(family_id, |state| match operation {
                        BusyOperation::Snapshot => state.snapshot_busy += 1,
                        BusyOperation::Writer => state.writer_busy += 1,
                    });
                }
            });
        }
        std::thread::sleep(Duration::from_millis(1));
        true
    }

    pub(crate) fn arm_busy_handler(
        connection: &Connection,
        family_id: &str,
        operation: BusyOperation,
    ) -> rusqlite::Result<()> {
        BUSY_CONTEXT.with(|context| {
            assert!(context
                .replace(Some((family_id.to_owned(), operation)))
                .is_none());
        });
        connection.busy_handler(Some(busy_handler))
    }

    pub(crate) fn disarm_busy_handler(connection: Option<&Connection>) -> rusqlite::Result<()> {
        BUSY_CONTEXT.with(|context| {
            context.replace(None);
        });
        match connection {
            Some(connection) => connection.busy_timeout(Duration::from_secs(10)),
            None => Ok(()),
        }
    }

    pub(crate) fn projection_loaded(family_id: &str) {
        let hook = lock_unpoisoned(hooks()).get(family_id).cloned();
        if let Some(hook) = hook {
            let mut state = lock_unpoisoned(&hook.state);
            if !state.projection_loaded {
                state.projection_loaded = true;
                hook.changed.notify_all();
                while !state.released {
                    let (next, timeout) = hook
                        .changed
                        .wait_timeout(state, hook.timeout)
                        .unwrap_or_else(|poisoned| poisoned.into_inner());
                    if timeout.timed_out() {
                        drop(next);
                        panic!("snapshot hook release timed out");
                    }
                    state = next;
                }
            }
        }
    }

    impl Control {
        pub(crate) fn wait_projection(&self) {
            self.wait(|state| state.projection_loaded);
        }

        pub(crate) fn wait_snapshot_busy(&self) {
            self.wait(|state| state.snapshot_busy >= 1);
        }

        pub(crate) fn wait_writer_busy(&self) {
            self.wait(|state| state.writer_busy >= 1);
        }

        fn wait(&self, ready: impl Fn(&State) -> bool) {
            let mut state = lock_unpoisoned(&self.hook.state);
            while !ready(&state) {
                let (next, timeout) = self
                    .hook
                    .changed
                    .wait_timeout(state, self.hook.timeout)
                    .unwrap_or_else(|poisoned| poisoned.into_inner());
                if timeout.timed_out() {
                    drop(next);
                    panic!("SQLite busy acknowledgement timed out");
                }
                state = next;
            }
        }

        pub(crate) fn release(&self) {
            let mut state = lock_unpoisoned(&self.hook.state);
            state.released = true;
            self.hook.changed.notify_all();
        }
    }

    impl Drop for Control {
        fn drop(&mut self) {
            self.release();
            lock_unpoisoned(hooks()).remove(&self.family_id);
        }
    }

    #[test]
    fn timeout_cleanup_allows_the_same_family_hook_to_be_reinstalled() {
        let family_id = "snapshot-hook-timeout-family";
        let control = install_with_timeout(family_id, Duration::from_millis(1));
        let timed_out = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            control.wait_projection();
        }));
        assert!(timed_out.is_err());
        drop(control);

        let replacement = install_with_timeout(family_id, Duration::from_millis(1));
        replacement.release();
    }
}
