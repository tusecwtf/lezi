# Server hot-path measurement

当前整合状态与验收边界见[2026-10-13 整合交接](../handoff/2026-10-13-integration-acceptance.md)；本文保留其历史切片的范围与测量结果。

This probe measures existing-database initialization through the production
`Store::open`, `build_server_apps`, and
`RateLimiter::check_and_record_in` implementations in the shipped Cargo release
profile (`opt-level = "z"`, LTO, one codegen unit). It is not an HTTP throughput
benchmark, a NAS measurement, or a claim about complete sync-round latency.

## Reproduce

1. Start the baseline from the public `9e47fd6c` snapshot, then apply only
   the tracked, test-only harness patch. Do not use a later integration commit
   as the baseline, since it may contain unrelated production changes.

   ```sh
   candidate_root="$PWD"
   git worktree add --detach ../lezi-perf-baseline 9e47fd6c
   git -C ../lezi-perf-baseline apply \
     "$candidate_root/docs/dev/server-hotpaths-harness.patch"
   cd ../lezi-perf-baseline/tools/lezi-sync
   cargo test --release --locked --lib --no-run
   ```

   Copy the executable printed for `unittests src/lib.rs` to a separate
   `baseline-tests` path. Build the candidate worktree with the same command
   and toolchain, and copy its **lib-test executable** to `candidate-tests`.
   Do not pick another executable from `target/release/deps`: builds that also
   include API tests produce a server binary there as well. Keep `Cargo.lock`
   unchanged. The patch changes no production implementation and is checked
   against the public-baseline-equivalent snapshot.
2. Stop other builds/benchmarks and run from the candidate repository root:

   ```sh
   python3 scripts/benchmark-server-hotpaths.py \
     /absolute/baseline-tests /absolute/candidate-tests \
     /absolute/new-results-directory --rounds 15 --records 0 1000 10000 \
     --initialization-only
   ```

The script creates synthetic fixtures in a temporary directory through the real
family/causal APIs, validates them through server startup, and copies the same
fixture for each trial. No real family data, media, credentials, external server,
TLS identity, or deployment target is used. `0` means one family and one baby but
no records. Fixture/executable hashes, logs, every raw sample (`samples.csv`), and
summaries (`summary.json`) are retained in the chosen output directory.

Baseline/candidate execution order alternates each round. Store trials contain
41 opens, initialization trials 9 starts; the first sample is a fresh process
with a cold process-local schema cache, and later samples have a warm cache.
The filesystem/page cache is not forcibly evicted. Startup timing includes
schema checks, maintenance, authority-graph validation, and router construction;
it excludes TLS loading, socket binding, and network request handling. All
measured opens use an existing current-schema database; first-ever empty-database
creation is not claimed to improve.

Omit `--initialization-only` to include the unchanged limiter as well.
Limiter trials use existing heartbeat (30/device, 300/family) and causal-commit
(120/principal, 1200/family) limits. Each sample includes 500 complete windows,
including exact-boundary expiry and rejected over-limit attempts. Light
heartbeat windows contain 5 calls from one scope; busy windows rotate 10 scopes
for 330/1320 calls. Each process reports 21 batch averages (nanoseconds/call),
with the first batch reported separately. These are batch-average distributions,
not individual-request tail latency.

Each ignored probe asserts successful initialization or the exact accepted-call
count. The normal suite keeps the existing behavioral tests; it does not enforce
noisy wall-clock thresholds. Run the normal Rust gates separately, plus the
repository-required isolated Android/server seams.

## Scope and tradeoffs

- Cache only the normalized expected schema derived from compile-time SQL and
  bundled SQLite. Continue re-reading and comparing each actual database on
  every preflight/open, including read-only opens. Failed construction is not
  cached. Concurrent initial callers can do duplicate construction before one
  immutable value is retained. No pool or new transaction/connection semantics.
- The cached schema has 53 objects and 15,640 bytes of string content for this
  schema, plus string/container allocation overhead. It remains for process
  lifetime; the first construction still costs the same as before.

## Rejected limiter experiments (not delivered)

Two sequence-equivalent alternatives were evaluated and discarded. Both compared
outcomes and internal queue state against the original retain model over 8
configurations × 6,514 requests, including clock rollback, exact expiry, repeated
timestamps, alternating past/future timestamps, zero/negative windows, and i64
saturating boundaries. Passing correctness did not justify a typical-load cost.

| Non-delivered variant | Light heartbeat baseline → candidate | Median change | Faster paired rounds |
|---|---:|---:|---:|
| Remove expired/future entries from deque ends | 241 → 254 ns/call | 5.4% slower | 0/15 |
| Also clear fully expired windows directly | 249 → 264 ns/call | 6.0% slower | 4/15 |

Both reduced busy-window cost substantially, but that gain does not justify the
light-load regression. Production rate limiting remains exactly the baseline
implementation. The abandoned implementation and its redundant model test are
not included in the delivered source. Their raw observations and source hashes
remain in the experiment evidence, marked non-delivered.

## Recorded schema-only result (2026-10-13)

Environment: same cloud worker, Linux x86_64, 9 available vCPUs, reported AMD EPYC
9V74; Rust 1.99.0; bundled SQLite and locked dependencies. Other local builds and
benchmarks were stopped. Fifteen alternating baseline/candidate rounds covered
0/1,000/10,000 synthetic records. These are existing-database measurements, not
fresh installation or LAN/NAS latency. Only `store/schema.rs` differs in the
measured production code; the separate diagnostics/guard slice was not included
in this experiment and must be verified after integration.

Per variant and data size: 15 cold-process samples, 600 warm `Store::open`
samples, and 120 warm server-initialization samples. P95 for the 15 cold samples
is the observed maximum, not a reliable production-tail estimate. The complete
raw CSV and JSON summary retain every sample, min/max, and paired-round results.

| Operation | Records | Process cache | Baseline median / p95 (ms) | Schema-only median / p95 (ms) | Faster paired rounds |
|---|---:|---|---:|---:|---:|
| Server initialization | 0 | cold | 13.037 / 18.181 | 9.032 / 12.288 | 15/15 |
| Server initialization | 0 | warm | 12.374 / 15.104 | 6.271 / 8.061 | 15/15 |
| Server initialization | 1,000 | cold | 21.016 / 27.821 | 16.358 / 19.563 | 15/15 |
| Server initialization | 1,000 | warm | 18.251 / 22.198 | 12.235 / 14.818 | 15/15 |
| Server initialization | 10,000 | cold | 101.747 / 153.040 | 101.842 / 116.709 | 6/15 |
| Server initialization | 10,000 | warm | 88.420 / 100.062 | 85.119 / 96.984 | 12/15 |
| Store open | 0 | cold | 5.934 / 7.545 | 3.777 / 6.368 | 14/15 |
| Store open | 0 | warm | 4.958 / 6.264 | 1.150 / 1.538 | 15/15 |
| Store open | 1,000 | cold | 5.967 / 9.716 | 4.226 / 12.188 | 12/15 |
| Store open | 1,000 | warm | 4.880 / 5.990 | 1.148 / 1.589 | 15/15 |
| Store open | 10,000 | cold | 5.956 / 11.496 | 3.903 / 11.431 | 14/15 |
| Store open | 10,000 | warm | 4.976 / 6.024 | 1.145 / 1.565 | 15/15 |

Warm Store opens improved 76.5–77.0% in the aggregate medians, with every paired
round faster at all three sizes. The 1,000-record cold server-initialization
median improved 22.2% (21.016 → 16.358 ms), also faster in all 15 paired rounds.
The 10,000-record cold initialization median was effectively unchanged
(101.747 → 101.842 ms; only 6/15 paired rounds faster): no robust improvement is
claimed for that case. Individual cold Store opens remained noisy, including a
higher observed maximum at 1,000 records. This is not an all-request latency or
HTTP-throughput claim.

### Correctness and explicitly run measurement tests

Final schema-only gates: `cargo fmt --all -- --check`, `cargo test --locked`, and
`cargo clippy --all-targets --all-features -- -D warnings` passed. Counts: 363 lib,
204 API, 1 golden-corpus, and 5 TLS tests passed. Five ignored tests in the normal
run are measurement-only (three existing, two newly added); all five were also
executed explicitly using the final release artifacts. No existing test or gate
was removed or changed to ignored. The new behavior test warms schema validation,
then drops/adds an index, adds a view/trigger, or changes `user_version`; preflight,
read-write open, and read-only open all reject the drift without changing the DB
bytes, and a subsequent valid database still opens.

Existing measurement commands (run directly through their built release test
executables during the quiet window to avoid any intervening build):

```sh
cargo test --locked --release --lib census_timing -- --ignored --nocapture --test-threads=1
cargo test --locked --release --test api pull_census_on_off_end_to_end_delta -- --ignored --nocapture --test-threads=1
```

- Census rebuild, 1k rows: median 0.512 ms, min 0.452 ms, max 0.754 ms
- Census rebuild, 10k rows: median 5.739 ms, min 5.324 ms, max 7.343 ms
- Quiet pull over 1,001 live rows: census-on median 0.615 ms, off 0.585 ms,
  delta 0.030 ms; first cold census pull 2.129 ms
- The existing trace reports whole milliseconds; its printed zero means below
  that resolution, not zero work

These existing census/pull figures are sanity measurements, not before/after
claims for the schema optimization. The separate unchanged limiter probe also
passed its exact acceptance-count assertions. The repository-required isolated
Android/server seams remain an integration gate; these Rust results do not
replace them.
