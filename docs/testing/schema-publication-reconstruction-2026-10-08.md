# Maintenance publication reconstruction, 2026-10-08

Scope: the existing schema-11/12-to-13 cutover's APK/metadata publication and pre-open rollback (US-037, V16), following ordinary-deployment reconstruction at `ee2a9c5dd28132af3f0bee2886e74e24dfbe9170`. No converter, schema change, deployment or live maintenance was performed.

## Reproduced defect

The real prepublish remote body from `ee2a9c5` was executed against synthetic files and Docker adapters. The adapter sent SIGTERM to its own recorded remote-shell child immediately after publication. The shell exited with signal 15, both new files remained, and the exact old pair was still present in the snapshot directory. The ERR-only handler did not restore that pair. `evidence/2026-10-08-schema-publication/baseline-term-reproduction.log` records that failed baseline.

Source review also found that developer-side mutation intent was saved only after SSH returned and the marker probe succeeded. A lost response or interruption could therefore leave outer cleanup without the information needed to restore.

## Repair

- The new `schema-update-pair.sh` requires the outer data-bind-derived publication owner lease on every call. It is included in the package's closed inventory, checksums, executable staging and current-helper equality checks.
- Private snapshots and readiness/mutation markers precede final-file changes. APK and metadata are fully staged, flushed and byte-hash checked before their two renames; final bytes are checked again. The existing old-container download hash gate remains before any stop.
- HUP/INT/TERM and copy, rename, helper, final-check or release errors invoke the same verified rollback. Snapshot bytes survive successful publication and lost helper responses, enabling the existing pre-open recovery phase to use the same helper idempotently. Failed rollback retains evidence and does not claim restore completion.
- Developer-side uncertain mutation intent is now flushed before SSH starts, independently of the later remote-marker query. Existing outer failure handling therefore attempts recovery and keeps its lease if recovery cannot be established.
- Ordinary and maintenance helpers use one short-lived operation lock to serialize actual writers, in addition to their workflow owner leases. A lost Docker/SSH response cannot start a concurrent rollback while the first helper might still be running. Killed helpers leave a refusal marker; no automatic lock stealing or stale-state deletion was introduced.
- Ordinary deployment refuses an incomplete maintenance publication, and maintenance preparation refuses an incomplete ordinary publication or legacy staging/rollback files.

## Validation boundaries

The new fixture extracts the actual production SSH body and invokes the actual helper, with real temporary filesystem copies, renames, hashing and marker operations. Docker, curl and chown are adapters; no actual container, root/uid-10001 privilege boundary, SSH connection, NAS filesystem, power loss or migration is represented by these passes. Signals target only the fixture's own recorded child, with no cross-process `/proc` observation.

Coverage includes 25 primary fault/success cases: each snapshot/copy/rename before and after, helper before/after, final verification, release before/after, failed/wrong live download, HUP/INT/TERM, SIGKILL retry refusal and rollback failure. Additional controls exercise two idempotent pre-open restores, an active/interrupted helper refusal, two owner-lease rejections and the real developer phase's durable intent before a failed SSH result. The ordinary transaction suite adds its own active-helper refusal to the previous 21 cases.

The final local sweep passed 17/17 drivers: 14 deployment fixtures, two Docker-build helper fixtures and the integration-launcher fixture. All 22 ordinary-publication cases and all 25 primary maintenance cases passed, along with the additional controls described above. Shell syntax checks and `git diff --check` passed.

Raw driver logs, per-driver statuses and exact tested script SHA-256 values are in `evidence/2026-10-08-schema-publication/`. The GitHub shell workflow explicitly includes the new driver. Historical reports retain their original source identities and counts.

The remaining real Docker/NAS, signed release, actual schema-rehearsal service and broader process/platform gates stay open. In particular, the new source candidate's 0.5.5 rollback identity is still not invented or authorized by these script changes. The absent process-death observer drivers remain a separate, honestly reported validation blocker.
