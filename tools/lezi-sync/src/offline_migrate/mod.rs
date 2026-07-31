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
//! - **05** — CLI / process exit codes.
//!
//! Until CLI wiring lands the surface is unit-tested (`dead_code` allowed).
//!
//! ## Re-auth after cutover (no silent restore)
//!
//! See [`migrator::REAUTH_OPS_NOTE`]. Owner uses the migration-time new root
//! password (`LEZI_BOOTSTRAP_SECRET`); members use current request/approve or
//! login-grant flows. All pre-migration credentials, invites, and device
//! sessions are void.

#![allow(dead_code)]

pub(crate) mod inventory;
pub(crate) mod media;
pub(crate) mod migrator;

// Re-export for subsequent tickets (`crate::offline_migrate::…`).
#[allow(unused_imports)]
pub(crate) use inventory::*;
#[allow(unused_imports)]
pub(crate) use media::{media_file_relative_path, migrate_v3_data_dir};
#[allow(unused_imports)]
pub(crate) use migrator::{
    migrate_v3_database, MigrateError, MigrateReport, MIN_NEW_ROOT_PASSWORD_LEN, REAUTH_OPS_NOTE,
};
