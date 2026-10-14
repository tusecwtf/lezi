# Integrated server verification, 2026-10-13

## Result

The exact integrated Rust tree at `c5e541b595079fd915f44d0250417db3e10dd5de` passed formatting, the full locked Rust test suite, and warnings-as-errors Clippy. The full suite passed **368 library + 208 API + 1 golden corpus + 5 TLS tests** (582 total, zero failures). Six timing-only tests remain ignored in the normal suite; all six were explicitly executed afterward using the final release executables. No previously active test was skipped.

All Rust source/test files plus Cargo.toml and Cargo.lock were SHA-256 checked unchanged between gate start and final release build. The complete gate logs and source-hash manifest were retained separately. This public evidence directory contains the report, all 4,500 raw comparison samples, their summary and sanitized metadata; it does not include test executables or gate logs. The Rust tree is unchanged through integration runtime commit `cf787d7a16dc6520a6fcab60b3e5221999c0d76c` (`git diff c5e541b cf787d7a16dc6520a6fcab60b3e5221999c0d76c -- tools/lezi-sync` is empty).

## Independent source review

- The schema expectation cache reuses only immutable compile-time SQL / bundled-SQLite expectations. Each actual database's user_version and normalized objects are read and compared on each open/preflight, including read-only opens. Construction errors are not cached. Existing schema, wire, transaction, and FULL durability rules are unchanged.
- All five diagnostics/handler production files were byte-identical to the independently tested observation slice. The fifteen existing async guards remain owned by blocking work and are returned with successful results to preserve the original normal unlock points. The fix does not add new locking coverage to unrelated routes or forcibly stop cancelled SQLite/filesystem work.
- The entire original rate_limit.rs is an unchanged prefix of the integrated file; the only addition is a cfg(test) timing module. Both abandoned prune implementations remain absent.
- Schema, limiter, and runtime timing source hashes match the final schema-only experiment archive. The schema cache and diagnostics/guard changes touch distinct behavior and retain their respective regression tests after integration.

## Integrated 15-round release comparison

Same machine/toolchain/profile, alternating baseline and integrated candidate order, 0/1,000/10,000 synthetic records. The exact existing baseline executable SHA-256 was checked against the original experiment metadata. The candidate includes the schema cache AND diagnostics/guard slice (probe default None). At each size/variant: 15 cold-process samples, 600 warm Store-open samples, and 120 warm server-initialization samples. The process-local cache is cold for the first sample; OS/page caches are not forcibly evicted.

All **4,500 raw samples** were independently re-aggregated to reproduce every n/min/median/p95/max field and every faster paired-round count. Data is in [samples.csv](samples.csv), [summary.json](summary.json), and [metadata.json](metadata.json). Metadata retains fixture/executable hashes; per-trial logs are retained separately. Only absolute executable paths and a private-history baseline alias were sanitized; raw samples and summary values are byte-identical to the recorded evidence.

| Operation | Rows | Cache | Baseline median / p95 ms | Integrated median / p95 ms | Median reduction | Faster rounds |
|---|---:|---|---:|---:|---:|---:|
| Server init | 0 | cold | 13.261 / 21.585 | 9.340 / 11.153 | 29.6% | 15/15 |
| Server init | 0 | warm | 12.368 / 15.162 | 6.323 / 7.925 | 48.9% | 15/15 |
| Server init | 1,000 | cold | 21.221 / 30.658 | 16.752 / 18.682 | 21.1% | 15/15 |
| Server init | 1,000 | warm | 18.524 / 23.997 | 12.129 / 14.816 | 34.5% | 15/15 |
| Server init | 10,000 | cold | 105.234 / 120.026 | 103.251 / 128.372 | 1.9% | 8/15 |
| Server init | 10,000 | warm | 89.943 / 101.319 | 84.319 / 102.807 | 6.3% | 12/15 |
| Store open | 0 | cold | 5.878 / 7.200 | 4.113 / 7.794 | 30.0% | 14/15 |
| Store open | 0 | warm | 4.996 / 6.062 | 1.164 / 1.566 | 76.7% | 15/15 |
| Store open | 1,000 | cold | 5.861 / 6.499 | 3.747 / 5.136 | 36.1% | 15/15 |
| Store open | 1,000 | warm | 5.019 / 6.247 | 1.171 / 1.562 | 76.7% | 15/15 |
| Store open | 10,000 | cold | 5.674 / 10.083 | 3.990 / 5.404 | 29.7% | 15/15 |
| Store open | 10,000 | warm | 5.082 / 6.608 | 1.127 / 1.563 | 77.8% | 15/15 |

Warm Store opens retain a **76.7–77.8% lower median**, with all 15 paired rounds faster at every size. The 1k-row cold server initialization median is **21.221 → 16.752 ms (21.1% lower)**, faster in all 15 rounds. The 10k-row cold initialization improvement is not robust: 105.234 → 103.251 ms, only 8/15 rounds faster, and observed p95/max worsened from 120.026 to 128.372 ms. No stable improvement is claimed there. These findings are consistent with the earlier schema-only comparison.

These are existing-database Store/server-initialization measurements, not first-install creation, HTTP throughput, LAN/TLS/NAS, complete sync-round latency, or phone UI/PSS results. Fifteen cold samples make the reported p95 the observed maximum; it is not a reliable production tail estimate. Startup includes schema checks, maintenance, graph validation, and router construction but not TLS/socket setup. Small cold Store samples also remain noisy (0-row candidate max exceeds baseline).

## Explicitly executed timing tests

- runtime_hotpath_timing: fixture seed + all 180 paired store/startup trials above passed.
- census_rebuild_median_at_1k_rows: median 0.478043 ms, min 0.460037, max 0.509559.
- census_rebuild_median_at_10k_rows: median 6.085 ms, min 5.268, max 9.203.
- pull_census_on_off_end_to_end_delta: 1,001 live rows; on median 0.577469 ms, off 0.568707, delta 0.008762; first cold census pull 2.103 ms.
- rate_limiter_timing: unchanged production limiter, all three exact accepted-count workload assertions passed.
- sync_stage_timing: 5 warmups then 30 raw heartbeat/pull request+stage samples, passed. This is a sampler sanity check with opt-in instrumentation, not before/after evidence. Overlapping lock/queue/work durations must not be summed into request time.

Whole-millisecond trace values of zero in the original pull test mean below its resolution, not zero work. The census/pull figures are sanity measurements, not optimization claims.

## Artifacts and remaining boundary

`integrated-lezi-sync-debug` is the source-pinned service executable for isolated Android/server seams (SHA-256 `056eeda3462b163b882ff49bd686269cd4451703171adb48d9bcad8bec017dff`). The release lib executable hash is also retained in metadata. Full Android/dual-side validation remains pending; the Rust gates do not replace it. See the [integration handoff](../../../handoff/2026-10-13-integration-acceptance.md). This verification did not touch a user's computer, ADB, a NAS, or any deployment target and is not evidence of deployment.
