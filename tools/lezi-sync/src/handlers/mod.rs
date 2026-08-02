//! Crate-private HTTP route handlers, partitioned by domain.
//!
//! Router assembly and [`crate::AppState`] stay in the crate root (`build_apps`);
//! this package only declares domain modules — **no** catch-all re-export barrel.
//! Import by domain at the assembly site, e.g. `handlers::identity::create_family`
//! or `use handlers::{identity, media, …}` then `identity::create_family`.
//!
//! ## Visibility rule (for ticket 33 codification)
//!
//! - Nested domain modules are `pub(crate)` so the crate root can path to them.
//! - HTTP route entrypoints are `pub(crate)` so `build_apps` can bind them via
//!   domain-qualified paths. (Flat sibling modules such as [`crate::members`] use
//!   `pub(super)` because their parent *is* the crate root; nested `handlers::*`
//!   cannot use `pub(super)` for the same role without a re-export façade.)
//! - Reserve `pub(crate)` helpers for true cross-module seams (e.g.
//!   [`media::media_entity_is_pullable`], [`app_update::load_app_update_metadata`]).
//! - Private DTOs and normalize helpers stay module-private.
//!
//! ## Module ownership
//!
//! - [`health`] / [`app_update`] / [`identity`] / [`sync`] — request handlers only.
//! - [`media`] — HTTP media/bundle routes **and** media-root process lifecycle
//!   (`collect_orphan_family_media`, `retry_committed_pending_bundle_media_cleanup`)
//!   called from `build_apps` at startup, not route entrypoints.
//! - Family-member admin routes remain in [`crate::members`] (not merged back).

pub(crate) mod app_update;
pub(crate) mod disaster_restore;
pub(crate) mod health;
pub(crate) mod identity;
pub(crate) mod media;
pub(crate) mod sync;
