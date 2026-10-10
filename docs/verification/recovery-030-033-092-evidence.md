# Recovery acceptance evidence: US-030 / US-033 / US-092

Base revision: `6c4dea2b8373110727e70ad3ef9485abe96c4572`. Prepared 2026-10-10 UTC.
This document initially describes source coverage and a pending execution plan, not test PASS.

## US-030: durable restart matrix

`tests/restore_authority_reconstruction.rs` adds separate ordinary and restore tests.
Each uses a new synthetic TempDir per state, production Router/Store, drops the original
Router, rebuilds from the durable tree, calls `/ready`, then pulls and checks wake
parent/text/withdrawal/observer/version and media owner/kind/tombstone/hash/bytes.
This is persistent in-process rebuild evidence, not operating-system process-kill proof.

| Path | Text | Photo | Withdrawn with photo | Media tombstone |
|---|---|---|---|---|
| Ordinary | ordinary commit | prepare + ordinary commit | prepare + ordinary commit | ordinary photo commit, then CAS removes attachment |
| Restore | restore commit | restore upload + commit | restore upload + commit | restore live photo + commit, then ordinary CAS removes attachment |

Important contract boundary: direct restore manifest import of media tombstones is
rejected first by historical-context validation in `handlers/disaster_restore.rs`:
HTTP 422 with detail `restore tombstone is not retained source-relation history`
and no code field. Media cannot enter the retained-history set, so this control does
not reach the later `restore_lossless_unsupported` guard. The separate negative
control preserves the actual early rejection.
The last restore cell proves a restored authority can later create a media tombstone
and restart; it does **not** prove direct media-tombstone import succeeds. If US-030 is
interpreted to require the latter, that requirement remains in conflict with the
current lossless restore contract. These tests do not relax it.

No family deletion, cancellation/retirement race, sole-copy deletion policy, or NAS
operation is part of this matrix. Each legal photo cell additionally corrupts only its synthetic saved projection
with an unknown kind, wrong parent type, or missing parent. All six controls must
fail startup. Existing runtime malformed-kind/parent controls remain complementary.

## US-033: invocation ownership matrix

`offline_migrate/v13.rs::failure_ownership_matrix` adds:

- 15 selected phase failures: schema11 after intermediate v11 rebuild, and both
  schema11/12 after database rebuild, secret/media/TLS copies, authority validation,
  copied-assets validation, and immediately before publication.
- Four real failure cases: nonempty output, operator-owned lease, unsupported schema,
  and missing authority head.
- Concurrent same-output (one success) and different-output (two successes) controls.

Fault injection is `cfg(test)` and thread-local, with no environment/CLI/production
control. Every injected result must identify the selected phase, preventing an earlier
failure from masquerading as coverage. The entire temporary parent tree is hashed,
including the source, two historical staging names, and UUID-looking operator-owned
leftovers. Failed calls must leave the digest identical, demonstrating no leaked owned
stage/lease and no deletion/adoption of unknown paths. Concurrent success tests remove
only their exact successful output paths before comparing the original parent digest.
Existing historical-name collision and symlink-ancestor tests remain complementary.
No real backup, migration, remote, permission change, or security bypass is used.

## US-092: original contract and preserved evidence

Spec lines 284–285 require isolated synthetic 100/1,000/10,000 media, ascending/reverse/
shuffled order, actual bytes/hash counts, status/cancel latency, restart cost, and an
explicit budget before any capacity claim. They do **not** specify a 30-second or
120-second product startup SLA. The historical 30-second readiness observation was
borrowed from a helper; the later 120-second window was separately labeled diagnosis.
Neither may silently become a product SLA.

Read-only evidence inspected: the restored `lezi-performance-checkpoint/us092` package,
including `REPORT.md`, `README.md`, `summary.json`, `formal/report.json`,
`formal/10000-shuffled-clean/result.json`, `startup-timeout-observation.json`, and
`supplemental-attestation.json`. The original pinned production revision is
`9a74ce7426c780b91e02c07e93b55929d6382072`, **not** final 6c4dea2.

Preserve original accounting: **16 PASS / 1 FAILED / 1 UNRUN**. The 10,000-shuffled clean
cell committed and verified contents but failed its post-commit readiness observation.
Its failed-start PID, final exit code, and first commit response are unavailable.
The traceback establishes connection refusal after the 30-second observation; it does
not establish a crash, exact receipt replay, or root cause. Later ~5.4s successful
starts cannot reconstruct missing facts. Nine application-counter cells were eventually
observed, with the missing observer cell clearly supplemental. Originals were synthetic
64-byte bodies, not decodable photos or NAS throughput evidence. Scoped original-body
work was 2S read and 3S SHA input; status/restart progress reconstruction read zero original
bodies. This is historical measured evidence, not an executed measurement on 6c4dea2.

### Proposed final-6c verification, pending explicit execution window/scope

1. Preserve and hash existing reports; do not reopen the original failed data root
   writable or retry any previously blocked observation endpoint/instrumentation route.
2. Build/pin the exact intended final source and binary in the permitted heavy window.
   Use fresh private synthetic roots only. First run a bounded functional control via
   existing authorized production HTTPS routes: original-body identity, full hash
   validation, clean startup, stable status, commit, controlled owned-child restart,
   ready, and receipt replay. Store PID immediately from the owned Popen handle,
   monotonic timestamps, each readiness result, exit/wait status, and a redacted
   equality attestation for the first response versus replay. Never publish tokens.
3. If authorized, rerun the nine clean scale/order cells using the same 64-byte fixture
   and original **30-second observation**, without calling it an SLA. Record status,
   cancel and restart elapsed times, exact binary/source, and all failures. Do not use
   the 120-second diagnostic window to turn a 30-second miss into PASS. Cancellation
   remains a sequential synthetic branch; no US-086 concurrent retirement group.
4. Actual body-read/hash instrumentation requires explicit clearance of the precise
   observation mechanism against prior blocked routes. Until then do not reintroduce
   an observer under a new filename or alternate entry point. Clean elapsed-time and
   public result validation cannot substitute for measured read/hash counts.
5. Stop on an access denial, unexpected resource condition, or the agreed observation
   budget. Preserve failure evidence. Do not retry denied `/proc` I/O via another path,
   identity or permissions. Existing report exclusions remain: no SQLite/VFS attribution,
   cold-cache/NAS/codec/power-loss claim, no retrospective explanation of the old failure.

Any future supported-workload/hardware and startup/capacity promise needs an explicit
engineering acceptance budget. This report does not create a new user policy decision;
bounded synthetic measurements can be reported without inventing such a promise. US-092 is not declared closed by this preparation.

## Prepared validation status

Direct rustfmt on both changed Rust files and `git diff --check` passed. No Cargo
compile/test, production workload, observer or US-092 rerun was executed in this
preparation. Focused and aggregate runtime results must be appended after execution.
