//! Private offline v3→current migration contract (family NAS ops only).
//!
//! Crate-internal inventory + one-shot migrator. **Not** wired into
//! lezi-sync startup; daily open remains fail-closed on the current schema only.
//!
//! ## Surface (by ticket)
//!
//! - **02** — [`migrator::migrate_v3_database`] transforms v3 `lezi.db` → current.
//! - **03** — [`media::migrate_v3_data_dir`] + [`media::media_file_relative_path`].
//! - **04** — same DB call requires an ops-provided **new root password**; writes
//!   `families.owner_root_fingerprint` and regenerates `server.secret` beside the
//!   dest DB so current lezi-sync can `/ready` and owner can re-login.
//! - **05** — [`cli`] (`lezi-sync offline-migrate …`): copy-out help, dry-run,
//!   migrate `--in`/`--out`, validate out/ preflight, process exit codes.
//! - **06** — [`cutover`]: fixed maintenance-window step order, copy-back help,
//!   runbook + script pointers (does **not** execute live cutover; see ticket 07).
//! - **07** — [`live_cutover`]: evidence checklist, APK smoke steps, live-cutover
//!   help + probe script pointer (execution evidence under tracker `evidence/07/`).
//! - **21** — [`boundary`]: ADR/README/DEPLOY/PRD + CLI help seams; locks prep-before-
//!   window vs cutover fixed order (does **not** add a second migrator).
//!
//! ## Re-auth after cutover (no silent restore)
//!
//! See [`migrator::REAUTH_OPS_NOTE`]. Owner uses the migration-time new root
//! password (`LEZI_BOOTSTRAP_SECRET`); members use current request/approve or
//! login-grant flows. All pre-migration credentials, invites, and device
//! sessions are void.
//!
//! ## Departed memberships (v3 `left_at IS NOT NULL`)
//!
//! Inventory row filter drops departed membership rows (hard-delete disposition).
//! Retained Record / CarePlan / CustomItem / FulfillmentCandidate facts (and
//! committed bundle roots/stagers) that reference a departed membership have
//! author/submitter/stager fields anonymized to match live
//! `hard_delete_membership`. Device/credential/request/session shells stay
//! TargetOnlyEmpty — never copy departed identity trees. Active Owner uniqueness
//! and active display-name conflicts remain fail-closed; departed rows do not
//! participate.

#![allow(dead_code)]

pub(crate) mod boundary;
pub(crate) mod causal;
pub(crate) mod cli;
pub(crate) mod cutover;
pub(crate) mod inventory;
pub(crate) mod live_cutover;
pub(crate) mod media;
pub(crate) mod migrator;
pub(crate) mod v11;

#[cfg(test)]
pub(crate) mod test_support;

// Re-export for subsequent tickets (`crate::offline_migrate::…`).
#[allow(unused_imports)]
pub(crate) use cli::{main_from_args, parse_args, run, validate_out_data_dir, CliCommand};
#[allow(unused_imports)]
pub(crate) use cutover::{
    cutover_help_text, cutover_maintenance_steps, COPY_BACK_RUNBOOK, COPY_BACK_SCRIPT,
};
#[allow(unused_imports)]
pub(crate) use inventory::*;
#[allow(unused_imports)]
pub(crate) use live_cutover::{
    live_cutover_apk_smoke_steps, live_cutover_evidence_required_files, live_cutover_help_text,
    EVIDENCE_DIR_REL, LIVE_CUTOVER_PROBE_SCRIPT,
};
#[allow(unused_imports)]
pub(crate) use media::{
    cleanup_migrator_data_dir_outputs, media_file_relative_path, migrate_v3_data_dir,
};
#[allow(unused_imports)]
pub(crate) use migrator::{
    migrate_v3_database, MigrateError, MigrateReport, MIN_NEW_ROOT_PASSWORD_LEN, REAUTH_OPS_NOTE,
};
#[allow(unused_imports)]
pub(crate) use v11::{migrate_v11_data_dir, migrate_v11_database, SOURCE_V11_USER_VERSION};
