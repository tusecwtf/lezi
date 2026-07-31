//! Private offline v3→current migration contract (family NAS ops only).
//!
//! Crate-internal inventory + one-shot migrator. **Not** wired into
//! lezi-sync startup; daily open remains fail-closed on the current schema only.
//!
//! Ticket 02 implements [`migrator::migrate_v3_database`]. Media path bytes and
//! root-password injection are tickets 03 / 04. CLI wiring is ticket 05 — until
//! then the surface is only unit-tested (`dead_code` allowed).

#![allow(dead_code)]

pub(crate) mod inventory;
pub(crate) mod migrator;

// Re-export for subsequent tickets (`crate::offline_migrate::…`).
#[allow(unused_imports)]
pub(crate) use inventory::*;
#[allow(unused_imports)]
pub(crate) use migrator::{migrate_v3_database, MigrateError, MigrateReport};
