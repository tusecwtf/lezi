# Ticket 02 validation · freeze completed CarePlan fulfillment binding

Validation date: 2026-07-30

## RED

Store public seam:

```text
$ cargo test --locked tombstoned_custom_item_supports -- --nocapture
thread 'store::tests::tombstoned_custom_item_supports_only_persisted_history_and_its_fulfillment' panicked at src/store.rs:3857:9:
completed CarePlan accepted fulfillment rebind: Ok(BundleCommitResult { ... status: "committed", applied: 1, cursor: 9, ... })
test result: FAILED. 0 passed; 1 failed
```

HTTP public seam:

```text
$ cargo test --locked --test api tombstoned_custom_item_supports_history_and_fulfillment_but_not_new_roots -- --nocapture
assertion `left == right` failed: {"applied":1,...,"status":"committed"}
  left: 200
 right: 409
test result: FAILED. 0 passed; 1 failed
```

Both failures are the intended regression: after the first completed-plan binding to R1, the
current server accepted a newer plan version rebound to R2 and published it instead of rejecting
the immutable-state conflict.

## GREEN

The Store now freezes the first persisted non-null
`(fulfilled_record_client_uuid, fulfilled_at)` pair for creator and owner updates. Both bundle
stage and commit map a mutation to the same content-free HTTP `409` detail.

Targeted regressions:

```text
$ cargo test --locked completed_care_plan_fulfillment -- --nocapture
store::tests::completed_care_plan_fulfillment_pair_is_immutable_and_exact_replay_is_idempotent ... ok
completed_care_plan_fulfillment_binding_is_frozen_for_creator_and_owner ... ok

$ cargo test --locked staged_care_plan_rebind_cannot_commit_after_first_binding_wins -- --nocapture
store::tests::staged_care_plan_rebind_cannot_commit_after_first_binding_wins ... ok

$ cargo test --locked tombstoned_custom_item_supports -- --nocapture
Store and HTTP tombstone history/first-fulfillment/rebind regressions ... ok
```

Full Rust gates:

```text
$ cargo fmt --all -- --check
PASS

$ cargo clippy --all-targets --all-features -- -D warnings
Finished dev profile; no warnings

$ cargo test --locked
Store/unit: 38 passed; 0 failed
HTTP API: 86 passed; 0 failed
Doc tests: 0 failed
```

Fresh temporary data-root current-wire smoke used the real Rust process and HTTP routes:

```json
{"health":"ok","first_fulfillment":"committed","rebind_status":409,"persisted_binding":"33333333-3333-4333-8333-333333333333","rebound_record_visible":false}
```

The first sandboxed process attempt could not bind localhost (`Operation not permitted`); the same
smoke was rerun with localhost-bind permission and passed. Both temporary data roots were removed.
Physical NAS deployment remains out of scope for this ticket.
