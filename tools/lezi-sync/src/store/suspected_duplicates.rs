//! Shared contract for client-visible suspected duplicate source groups.
//!
//! These values only validate explicit source-relation components. They never
//! select a winner, delete a record, or turn an ordinary tombstone into a
//! duplicate signal.

/// Inclusive grouping window on the record timestamp.
pub(crate) const SUSPECTED_DUPLICATE_WINDOW: i64 = 30 * 60 * 1000;

const SUSPECTED_DUPLICATE_TYPES: &[&str] = &[
    "nursing",
    "formula",
    "pumped_feed",
    "pump_express",
    "baby_food",
    "snack",
    "drink",
    "pee",
    "poop",
    "both_diaper",
    "temperature",
    "bath",
    "medicine",
];

pub(crate) fn is_suspected_duplicate_type(record_type: &str) -> bool {
    SUSPECTED_DUPLICATE_TYPES.contains(&record_type)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn contract_keeps_exact_types_and_inclusive_thirty_minute_window() {
        assert!(is_suspected_duplicate_type("bath"));
        assert!(is_suspected_duplicate_type("nursing"));
        assert!(!is_suspected_duplicate_type("sleep"));
        assert!(!is_suspected_duplicate_type("custom"));
        assert_eq!(SUSPECTED_DUPLICATE_WINDOW, 1_800_000);
    }
}
