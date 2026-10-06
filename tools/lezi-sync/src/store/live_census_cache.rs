//! Per-family in-process cache for the wire §1.4 live census (0.5 design §1.1).
//!
//! Failure-safety contract: the cache is a pure memo keyed by
//! `(family_id, family_meta.rev)`. It has exactly two invalidation rules and
//! never folds writes incrementally:
//!
//! 1. every `family_meta` rev increment funnels through
//!    [`crate::store::causal::advance_rev`] / `advance_rev_by`, which
//!    invalidates the family entry (staging and source-relation increments
//!    delegate to that same funnel);
//! 2. every absolute (non-increment) rev write — disaster restore, bundle
//!    import, membership anonymization — invalidates the family entry
//!    unconditionally, so a rollback that reuses a previous rev value can
//!    never collide with a cached entry (review A4/B7).
//!
//! A missed or spurious invalidation therefore costs at most one extra
//! rebuild — a stale census is never presented as fresh. Two mechanisms
//! enforce that: entries are deleted on invalidation, and each entry carries
//! the per-family invalidation `epoch` it was built under; a rebuild started
//! before an invalidation is refused at install time instead of overwriting
//! the fresh state. The server protocol generation is deliberately NOT used
//! as the epoch: it does not change across disaster-restore watermarks.
//!
//! The cache lives behind the `Store` handle (shared by all clones via
//! `Arc`), so invalidation sits in the same ownership domain as every rev
//! write. Read-only stores never install entries. Entries are rebuilt lazily
//! once per (family, head) per process; the same-head lookups afterwards are
//! O(1) clones under a short mutex.

use std::collections::HashMap;
use std::sync::Mutex;

use super::LiveCensus;

#[derive(Debug, Default)]
struct FamilySlot {
    /// Monotonic per-family invalidation generation; bumped on every
    /// invalidation. Entries built under an older epoch are dropped rather
    /// than served or installed.
    epoch: u64,
    entry: Option<CachedCensus>,
}

#[derive(Debug, Clone)]
struct CachedCensus {
    epoch: u64,
    head_rev: i64,
    census: LiveCensus,
}

/// Result of a cache probe at a given head.
#[derive(Debug)]
pub(in crate::store) enum CensusLookup {
    /// A live entry matched `(family_id, head_rev)`; serve this clone.
    Hit(LiveCensus),
    /// No usable entry; rebuild and install through the returned token.
    Miss(CensusRebuildToken),
}

/// Captures the family epoch observed at miss time so the rebuild can be
/// refused if an invalidation happened while the census was being computed.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(in crate::store) struct CensusRebuildToken {
    epoch: u64,
}

#[derive(Debug, Default)]
pub(in crate::store) struct LiveCensusCache {
    families: Mutex<HashMap<String, FamilySlot>>,
}

impl LiveCensusCache {
    pub(in crate::store) fn new() -> Self {
        Self::default()
    }

    pub(in crate::store) fn lookup(&self, family_id: &str, head_rev: i64) -> CensusLookup {
        let families = self.lock();
        match families.get(family_id) {
            Some(slot) => match &slot.entry {
                Some(entry) if entry.epoch == slot.epoch && entry.head_rev == head_rev => {
                    CensusLookup::Hit(entry.census.clone())
                }
                _ => CensusLookup::Miss(CensusRebuildToken { epoch: slot.epoch }),
            },
            None => CensusLookup::Miss(CensusRebuildToken { epoch: 0 }),
        }
    }

    /// Installs a freshly computed census unless the family was invalidated
    /// between the miss and the rebuild (`token` no longer current). The
    /// caller must not install from read-only stores.
    pub(in crate::store) fn install(
        &self,
        family_id: &str,
        token: CensusRebuildToken,
        head_rev: i64,
        census: LiveCensus,
    ) {
        let mut families = self.lock();
        let slot = families.entry(family_id.to_owned()).or_default();
        if slot.epoch != token.epoch {
            return;
        }
        slot.entry = Some(CachedCensus {
            epoch: slot.epoch,
            head_rev,
            census,
        });
    }

    /// The single invalidation point for rule 1 and rule 2: delete the entry
    /// and bump the epoch so any in-flight rebuild is refused at install.
    /// Safe to call before the surrounding transaction commits — a rolled
    /// back transaction only costs one extra rebuild.
    pub(in crate::store) fn invalidate(&self, family_id: &str) {
        let mut families = self.lock();
        let slot = families.entry(family_id.to_owned()).or_default();
        slot.epoch = slot.epoch.wrapping_add(1);
        slot.entry = None;
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, HashMap<String, FamilySlot>> {
        // Invalidation must stay sound even if a reader panicked mid-critical
        // section, so poisoning is unfolded rather than skipped.
        self.families
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    /// Test probe: the cached head rev when a live (non-stale) entry exists.
    #[cfg(test)]
    pub(in crate::store) fn test_cached_head_rev(&self, family_id: &str) -> Option<i64> {
        let families = self.lock();
        let slot = families.get(family_id)?;
        let entry = slot.entry.as_ref()?;
        (entry.epoch == slot.epoch).then_some(entry.head_rev)
    }
}
