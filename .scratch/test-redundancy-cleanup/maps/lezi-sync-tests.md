# lezi-sync test contract map (ticket 09)

**Tree:** `tools/lezi-sync/`  
**Policy:** Q2=A — classify kitchen sinks before delete; inventory by **protocol/store contract**, not LOC.  
**HEAD note:** re-check paths if tree moves; inventory date 2026-08-05.  
**Gates (if code changes):** `cargo fmt --all -- --check`, `cargo test --locked`, `cargo clippy --all-targets --all-features -- -D warnings` under `tools/lezi-sync/`.  
**NAS:** no CD for pure layout; no certificate destructive tests against family NAS.

## Disposition vocabulary (this ticket)

| Tag | Meaning |
|-----|---------|
| **KEEP** | Unique contract at this layer; leave as-is |
| **DEFER-split** | Multi-contract surface; candidates named; no move this round |
| **exact-duplicate** | Same assertion at same layer — **none found** |
| **no further split** | Already co-located / partitioned by contract |

## Executive summary

| Surface | Tests (approx) | Disposition |
|---------|----------------|------------|
| `tests/api.rs` | 156 | **DEFER-split** — sole kitchen sink; clusters below |
| `tests/tls.rs` | 2 | **KEEP** — process HTTPS + LAN APK black-box |
| `src/store/tests/*` | 34 | **KEEP** + **no further split** (already partitioned) |
| `src/offline_migrate/**` `#[cfg(test)]` | 88 | **KEEP** + **no further split** (module-local) |
| `src/lib.rs`, `rate_limit`, `handlers/*`, `model` unit | 31 | **KEEP** — co-located unit contracts |
| `deploy/test-*.sh` | 8 scripts | **KEEP** (out of Rust inventory; CD/package guards) |

**Exact-duplicates:** none (no identical test function names across files; store vs HTTP layers are complementary, not copies).

**Splits landed this ticket:** **no** — inventory only. Store/offline_migrate already partitioned; `api.rs` mechanical module split is optional follow-on (see candidates).

---

## 1. HTTP / process integration — `tests/`

### 1.1 `tests/tls.rs` (~261 LOC, 2 tests) — **KEEP**

Process-level server spawn (real ports, cert files, restart). Complements Axum `ServiceExt` tests in `api.rs` (which do not exercise rustls accept path).

| Test | Contract |
|------|----------|
| `public_endpoint_is_https_only_and_keeps_the_same_certificate_across_restart` | Public port HTTPS-only; setup-status capabilities; internal ready; cert identity stable across kill/restart |
| `configured_lan_apk_listener_serves_plain_http_and_shuts_down_with_the_server` | Optional LAN APK HTTP listener serves and dies with process |

**Note:** Uses local `tempfile` + generated certs — never family NAS.

### 1.2 `tests/api.rs` (~14 427 LOC, 156 tests) — **DEFER-split**

**Harness:** `Rig`, `app_for`, HTTP helpers, session/client fixtures ≈ **L1–441** (~36 top-level helper fns + structs).  
**Style:** single integration crate via `tower::ServiceExt` (in-process router), not full TLS stack.

#### File disposition

| Area | Disposition | Notes |
|------|-------------|-------|
| Shared harness L1–441 | KEEP (extract to `harness` if split) | Do not delete |
| Contract clusters below | DEFER-split | Target modules listed |
| Cross-layer vs `store/tests` | KEEP both | HTTP ACL/envelope vs store LWW/freeze |

#### Contract clusters → split candidates

If/when splitting: prefer **one Cargo integration binary** so harness stays shared:

```
tests/api/
  main.rs              # mod harness; mod <clusters>;
  harness.rs           # Rig, request helpers, fixtures (from L1–441)
  health_setup.rs
  lan_apk.rs
  disaster_restore.rs
  app_update.rs
  schema_startup.rs
  family_create.rs
  session_refresh.rs
  owner_auth.rs
  member_join.rs
  membership_admin.rs
  sync_wire.rs
  pull_cursor.rs
  baby_acl.rs
  media.rs
  family_delete.rs
  protocol_cutover.rs
  atomic_bundle.rs
  reconcile.rs
  record_author.rs
  care_plan.rs
  custom_item.rs
```

(Alternative: multiple `tests/api_*.rs` binaries each `mod common` — higher harness churn; not preferred.)

| Cluster / target module | N | Disposition | Contracts (test names) |
|-------------------------|---|-------------|------------------------|
| **health_setup** → `health_setup.rs` | 10 | DEFER-split | `liveness_and_readiness_initialize_private_single_data_root`, `internal_router_exposes_only_health_and_readiness`, `setup_status_exposes_only_the_empty_instance_contract`, `setup_status_switches_to_configured_without_exposing_family_metadata`, `setup_status_reports_maintenance_without_readiness_or_family_details`, `readiness_reports_degraded_when_database_is_not_queryable`, `readiness_reports_degraded_when_media_directory_is_not_writable`, `public_health_is_cheap_while_readiness_is_cached_and_expires`, `health_advertises_atomic_bundle_capability`, `health_advertises_record_membership_author_capability` |
| **lan_apk** → `lan_apk.rs` | 5 | DEFER-split | `lan_apk_download_origin_rejects_everything_except_bare_http_port_8767`, `lan_install_router_is_optional_and_exposes_no_sync_or_health_surface`, `lan_install_page_uses_only_verified_release_metadata_and_clears_the_invite_fragment`, `lan_install_page_honestly_disables_download_for_missing_or_unverified_apk`, `lan_apk_download_is_anonymous_integrity_checked_and_non_cacheable` |
| **disaster_restore** → `disaster_restore.rs` | 9 | DEFER-split | `disaster_restore_rejects_a_configured_server_even_with_the_root_password`, `disaster_restore_write_paths_require_supported_client_version`, `disaster_restore_write_paths_fail_open_without_verified_channel`, `family_create_and_disaster_restore_have_exactly_one_provisioning_winner`, `concurrent_restore_manifest_replay_serializes_conflicting_content`, `disaster_restore_is_staged_restart_safe_atomic_and_reauthors_history`, `disaster_restore_expires_after_twenty_four_hours_and_startup_cleans_staging`, `disaster_restore_commit_mints_access_expiry_from_commit_time`, `disaster_restore_rejects_manifest_tampering_without_activating_a_family` |
| **app_update** → `app_update.rs` | 13 | DEFER-split | `app_update_metadata_*`, `app_update_apk_*`, `app_update_cache_*`, `client_update_required_*`, `client_version_gate_fail_open_*` (13 names; see inventory extract) |
| **schema_startup** → `schema_startup.rs` | 2 | DEFER-split | `current_schema_version_restarts_with_credentials_and_entities`, `future_database_schema_version_fails_closed_without_mutation` |
| **family_create** → `family_create.rs` | 9 | DEFER-split | create idempotency/concurrency, root password, bootstrap secret, create/rate limits, display-name norm shared with members |
| **session_refresh** → `session_refresh.rs` | 6 | DEFER-split | rotation replay, concurrent same request_id, revoke-on-wrong-id, no time expiry |
| **owner_auth** → `owner_auth.rs` | 3 | DEFER-split | `owner_login_*`, `owner_takeover_*`, `root_password_rotation_on_restart_*` |
| **member_join** → `member_join.rs` | 8 | DEFER-split | member request approve/bind/reject/cancel/expire, login grants, pending bounds, owner reject approved unclaimed |
| **membership_admin** → `membership_admin.rs` | 11 | DEFER-split | members list/redaction, hard delete anonymize, leave, rename workflow, device revoke/logout, family name, membership projection |
| **sync_wire** → `sync_wire.rs` | 9 | DEFER-split | legacy invite/join absent, generation envelopes, push retired, obsolete payloads, timestamp push rejects, ordinary routes fail-closed |
| **pull_cursor** → `pull_cursor.rs` | 6 | DEFER-split | LWW/cursor/generation, paging, deleted baby dep order, fulfillment dep reemit, omit until media committed |
| **baby_acl** → `baby_acl.rs` | 4 | DEFER-split | nickname bounds, strict atomic entity, member cannot mutate baby, stale avatar snapshot |
| **media** → `media.rs` | 10 | DEFER-split | bytes/size/ACL/immutable assoc, metadata bounds, stream limit vs delete, orphan cleanup, corrupt ready |
| **family_delete** → `family_delete.rs` | 1 | DEFER-split (or fold into membership_admin) | `family_delete_requires_owner_name_and_root_and_persists_terminal_reason` |
| **protocol_cutover** → `protocol_cutover.rs` | 5 | DEFER-split | deferred fulfillment evidence, forced update channel, missing manifest |
| **atomic_bundle** → `atomic_bundle.rs` | 26 | DEFER-split | stage/commit/media/LWW repair, membership binding, care_plan/custom deps, tombstone, baby+avatar |
| **reconcile** → `reconcile.rs` | 4 | DEFER-split | authenticated/bounded/rate-limited reconcile + full-resync checkpoint + dependency cycle |
| **record_author** → `record_author.rs` | 2 | DEFER-split | creator/owner manage; same-membership second device |
| **care_plan** → `care_plan.rs` | 9 | DEFER-split | ACL, frozen fulfillment, next-feed NAS winner, candidate freeze, leave/admin |
| **custom_item** → `custom_item.rs` | 4 | DEFER-split | tombstone history, creator stamp, member ACL rename/delete, layout fields rejected |

App-update cluster (explicit names for searchability):

- `app_update_metadata_requires_session_and_returns_deploy_file`
- `app_update_metadata_missing_file_is_not_found_for_authenticated_session`
- `app_update_metadata_rejects_min_supported_above_version_code`
- `app_update_apk_requires_session_and_matches_metadata_sha256`
- `app_update_cache_invalidates_when_deploy_files_change`
- `app_update_apk_rejects_sha256_mismatch`
- `app_update_apk_missing_file_is_not_found`
- `client_update_required_rejects_pull_when_version_header_missing_or_below_min`
- `client_update_required_rejects_media_get_when_version_header_missing_or_below_min`
- `client_update_required_still_allows_authenticated_app_update_download`
- `client_version_gate_fail_open_without_app_update_metadata`
- `client_version_gate_fail_open_when_metadata_present_but_apk_missing`
- `client_version_gate_fail_open_when_apk_sha256_mismatches_metadata`

Family-create / rate cluster:

- `family_create_is_strict_idempotent_and_restart_safe`
- `family_create_retry_never_reissues_credentials_after_session_rotation`
- `concurrent_owner_create_commits_exactly_one_family_membership_device_and_session`
- `family_create_rejects_a_second_owner_claim_without_mutating_the_first`
- `family_create_requires_the_root_password_and_never_reopens_configured_setup`
- `family_create_uses_the_same_display_name_normalization_as_member_requests`
- `bootstrap_secret_gates_family_create_when_configured`
- `failed_root_passwords_share_a_per_source_budget_across_admin_endpoints`
- `create_limit_is_scoped_without_losing_global_protection`

#### Why not split in this ticket

1. Inventory-first (ticket acceptance); store side already clean.
2. Harness is tightly shared (~440 LOC + 36 helpers); a correct split is mechanical but high-churn with **zero coverage gain**.
3. Q2=A: classify before delete; no silent assertion loss — defer move until a dedicated layout PR if navigability demands it.

---

## 2. Store unit/integration — `src/store/tests/*`

**Wiring:** `src/store/mod.rs` → `#[cfg(test)] mod tests;` → `tests/mod.rs` partitions modules.  
**Harness:** `test_support.rs` (`entity`, `family`, `owner_principal`, `publish_bundle`, …).

### Sign-off: **no further split**

Partition already matches store capability packages (`schema`, identity login, `pull`, `reconciliation`, `bundles`). Do not re-flatten or invent LOC-based Structure tests.

| File | N | Disposition | Contract cluster |
|------|---|-------------|------------------|
| `schema_tests.rs` | 4 | **KEEP** | Fresh open / fail-closed unsupported version / wrong shape / nullable membership name |
| `identity_login_tests.rs` | 2 | **KEEP** | Owner pending-request list keeps approved unclaimed; open-request limit counts them |
| `pull_tests.rs` | 6 | **KEEP** | Deferred legacy fulfillment; anonymized deferred evidence; custom_item before record; parent co-group media at page boundary; byte+count page bounds; entity too large for any page |
| `reconciliation_tests.rs` | 5 | **KEEP** | Exact publish vs remote winner vs permanent ACL; latest atomic manifest only; removed avatar → absent tombstone; cumulative live media across deltas; disaster-restored head without bundle history |
| `bundles_tests.rs` | 17 | **KEEP** | Bundle stage/commit rules: media caps, custom_item live refs, fulfillment freeze/replay/tombstone, cross-baby reject, timestamp limit — store path without HTTP |
| `test_support.rs` | 0 | **KEEP** | Fixtures only |
| `mod.rs` | 0 | **KEEP** | Module list |

### Store ↔ HTTP layering (not duplicates)

| Concern | Prefer store | Prefer `tests/api` |
|---------|--------------|---------------------|
| LWW / freeze / deferred evidence algebra | `bundles_tests`, `pull_tests`, `reconciliation_tests` | envelope, status codes, session binding |
| Schema open / shape | `schema_tests` | restart with live credentials via router |
| Member request open-limit bookkeeping | `identity_login_tests` | full approve/claim/HTTP ACL |

No **exact-duplicate** merge recommended: deleting either layer would drop failure signal (SQL/transaction vs wire).

---

## 3. Offline migrate — `src/offline_migrate/`

Tests live in `#[cfg(test)] mod tests` beside production modules + shared `test_support.rs`.

| Module | N | Disposition | Contract cluster |
|--------|---|-------------|------------------|
| `boundary.rs` | 7 | **KEEP** | ADR/README/PRD boundary text, CLI help no secret leak |
| `inventory.rs` | 17 | **KEEP** | Target user_version coupling, table/field disposition algebra, source v3 allowlist |
| `migrator.rs` | 23 | **KEEP** | migrate success + fail-closed shapes; departed anonymize; reauth; end-to-end owner login on migrated out |
| `media.rs` | 12 | **KEEP** | Authority media copy integrity (path, size, sha256); staging orphans ignored |
| `cli.rs` | 20 | **KEEP** | parse/usage; dry-run/migrate/validate I/O guards; help text for cutover aliases |
| `cutover.rs` | 5 | **KEEP** | Cutover step order + help/runbook/script constant alignment |
| `live_cutover.rs` | 4 | **KEEP** | Evidence file set, APK smoke order, probe requires LAN HTTPS + docker healthcheck |
| `test_support.rs` | 0 | **KEEP** | Fixtures |
| `mod.rs` | 0 | (cfg only) | — |

**Sign-off: no further split** — already one contract surface per file. Do not merge into a kitchen-sink migrator test file.

---

## 4. Other unit tests (co-located)

| Path | N | Disposition | Contract |
|------|---|-------------|----------|
| `src/lib.rs` | 8 | **KEEP** | HMAC tokens, config limits, constant-time eq, private file/secret write, schema preflight no sidecar, permission hardening |
| `src/rate_limit.rs` | 1 | **KEEP** | Scoped limiter + global fallback |
| `src/handlers/sync.rs` | 1 | **KEEP** | Reconcile response serialization byte bound |
| `src/handlers/media.rs` | 4 | **KEEP** | Atomic media hard-link fallback, dir sync, pending cleanup retry |
| `src/handlers/app_update.rs` | 4 | **KEEP** | Metadata normalize min/version; negative cache; last-good floor |
| `src/model.rs` | 13 | **KEEP** | Record/care_plan schema v2, atomic roots, fulfillment pair, Android canonical wire, closed type sets, next_feed marker fixture |

---

## 5. Deploy shell tests (noted, not Rust gates)

Under `tools/lezi-sync/deploy/`: `test-init-tls.sh`, `test-remote-deploy-tls-guard.sh`, `test-push-and-deploy-tls-bootstrap.sh`, `test-package-nas-app-update.sh`, `test-package-nas-lan-apk-download.sh`, `test-remote-deploy-app-update-atomic.sh`, `test-copy-back-nas-data.sh`, `test-live-cutover-probe.sh`.  
**KEEP** as CD/package contracts; out of `cargo test` inventory. Certificate tests must stay off family NAS data bind (AGENTS.md).

---

## 6. Split candidates summary (if a follow-on lands)

| Priority | Action | Risk |
|----------|--------|------|
| P0 done | Inventory map (this file) | — |
| P1 optional | `tests/api.rs` → `tests/api/{main,harness,...}.rs` by clusters above | Mechanical; must keep all 156 tests green |
| P2 not needed | Further store/offline_migrate splits | Already partitioned |
| Never | Delete store tests that “look like” api tests without retained-coverage note | Coverage loss |

**exact-duplicate list:** empty.

---

## 7. Acceptance checklist (ticket 09)

- [x] Map at `.scratch/test-redundancy-cleanup/maps/lezi-sync-tests.md`
- [x] Split candidates with target module names (`tests/api/*.rs` table)
- [x] Splits landed: **none** (no production/test code change)
- [x] Explicit **no further split** for `src/store/tests/*` and `src/offline_migrate/**`
- [x] No NAS CD; no family-NAS cert destructive work
)
