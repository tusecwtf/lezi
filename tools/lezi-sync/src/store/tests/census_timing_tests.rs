//! W0 timing evidence for the full-family live census rebuild (ticket 01).
//!
//! These are measurement tests, not gates: they are `#[ignore]`d so the
//! default `cargo test` run stays fast, and the numbers only count when taken
//! in the shipped release profile on a quiet machine. Run them with:
//!
//! ```text
//! cargo test --release --lib census_timing -- --ignored --nocapture
//! ```
//!
//! Seeding bypasses the commit pipeline and inserts `entities` rows directly:
//! the census is a pure read over that one table, so the seed shape (not the
//! write path) is what the measurement must control.

use std::time::{Duration, Instant};

use rusqlite::{params, Connection};

use super::super::*;
use super::test_support::*;
use tempfile::TempDir;
use uuid::Uuid;

/// Live-row mix per 22 rows, roughly matching a record-heavy family
/// (records ~64%, media ~14%, plans/items ~18%, wake ~5%).
const SEED_TYPE_CYCLE: [&str; 22] = [
    "record",
    "record",
    "record",
    "record",
    "record",
    "record",
    "record",
    "record",
    "record",
    "record",
    "record",
    "record",
    "record",
    "record",
    "media",
    "media",
    "media",
    "care_plan",
    "care_plan",
    "custom_item",
    "custom_item",
    "wake_observation",
];

fn seed_live_rows(connection: &Connection, family_id: &str, rows: usize) -> BTreeMap<String, u64> {
    let mut expected_counts: BTreeMap<String, u64> = BTreeMap::new();
    let mut insert = connection
        .prepare_cached(
            "
            INSERT INTO entities(
                family_id, entity_type, client_uuid, updated_at,
                deleted_at, payload_json, rev
            ) VALUES (?1, ?2, ?3, ?4, NULL, '{}', ?5)
            ",
        )
        .unwrap();
    connection.execute_batch("BEGIN IMMEDIATE").unwrap();
    for index in 0..rows {
        let entity_type = SEED_TYPE_CYCLE[index % SEED_TYPE_CYCLE.len()];
        let client_uuid = Uuid::new_v4().to_string();
        insert
            .execute(params![
                family_id,
                entity_type,
                client_uuid,
                1_700_000_000_000i64 + index as i64,
                (index + 1) as i64
            ])
            .unwrap();
        *expected_counts.entry(entity_type.to_owned()).or_insert(0) += 1;
    }
    connection.execute_batch("COMMIT").unwrap();
    expected_counts
}

fn median(mut samples: Vec<Duration>) -> Duration {
    assert!(!samples.is_empty());
    samples.sort();
    samples[samples.len() / 2]
}

struct RebuildSample {
    median: Duration,
    min: Duration,
    max: Duration,
}

fn measure_census_rebuild(
    connection: &Connection,
    family_id: &str,
    iterations: usize,
    warmup: usize,
) -> RebuildSample {
    let mut samples = Vec::with_capacity(iterations);
    for index in 0..(iterations + warmup) {
        let started = Instant::now();
        let census = pull::compute_live_census(connection, family_id).unwrap();
        let elapsed = started.elapsed();
        if index >= warmup {
            samples.push(elapsed);
        }
        std::hint::black_box(&census);
    }
    RebuildSample {
        median: median(samples.clone()),
        min: samples.iter().min().copied().unwrap(),
        max: samples.iter().max().copied().unwrap(),
    }
}

fn report(rows: usize, iterations: usize, warmup: usize, sample: &RebuildSample) {
    println!(
        "census_rebuild_timing: rows={rows} iterations={iterations} warmup={warmup} \
         median={:.3?} min={:.3?} max={:.3?}",
        sample.median, sample.min, sample.max
    );
}

fn run_census_rebuild_timing(rows: usize, iterations: usize, warmup: usize) {
    let directory = TempDir::new().unwrap();
    let db_path = directory.path().join("lezi.db");
    let store = Store::open(&db_path).unwrap();
    let family_id = family(&store);
    let connection = Connection::open(&db_path).unwrap();
    let expected_counts = seed_live_rows(&connection, &family_id, rows);

    let sample = measure_census_rebuild(&connection, &family_id, iterations, warmup);
    report(rows, iterations, warmup, &sample);

    // Sanity: the timed read is the real census, not a broken query.
    let census = pull::compute_live_census(&connection, &family_id).unwrap();
    let entries = serde_json::to_value(&census).unwrap();
    for (entity_type, count) in &expected_counts {
        assert_eq!(
            entries[entity_type.as_str()]["count"],
            serde_json::json!(count),
            "census count drifted for {entity_type}"
        );
    }
    let seeded_total: u64 = expected_counts.values().sum();
    assert_eq!(seeded_total, rows as u64);
}

#[test]
#[ignore = "timing evidence, not a gate: cargo test --release --lib census_timing -- --ignored --nocapture"]
fn census_rebuild_median_at_1k_rows() {
    run_census_rebuild_timing(1_000, 101, 5);
}

#[test]
#[ignore = "timing evidence, not a gate: cargo test --release --lib census_timing -- --ignored --nocapture"]
fn census_rebuild_median_at_10k_rows() {
    run_census_rebuild_timing(10_000, 51, 5);
}
