//! Private offline v3→current migration contract (family NAS ops only).
//!
//! Crate-internal inventory + one-shot migrator. **Not** wired into
//! lezi-sync startup; daily open remains fail-closed on the current schema only.
//!
//! - Ticket 02: [`migrator::migrate_v3_database`] (DB only)
//! - Ticket 03: [`media::migrate_v3_data_dir`] + [`media::media_file_relative_path`]
//! - Ticket 04: root-password injection
//! - Ticket 05: CLI / exit codes
//!
//! Surface is crate-internal and unit-tested (`dead_code` allowed until CLI).

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
pub(crate) use migrator::{migrate_v3_database, MigrateError, MigrateReport};
