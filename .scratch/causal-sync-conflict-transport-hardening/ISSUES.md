# 因果同步冲突与传输硬化 — issues

Status: in-progress — H01–H17 and R12/R17/R18/R19 are implemented; H18 is the serial frontier

Spec: [`spec.md`](./spec.md)

Release target: Android/server `0.4.0`; versionCode `21`; Room `28`; local-data contract `5`;
server schema `13`; protocol/min-supported floor `21`.

Audit baseline: `332f160d79c439546c6c0e6f83b35a40fe04aa3a`

## Serial execution order

```text
H01 → R12 → R17 → R18 → H02 → H03 → H04 → R19
    → H05 … H27
    → H28 server offline-migrate → H29 guarded schema-cutover CD → H30 isolated rollback rehearsal
    → H31 … H43 → lossless-family-causal-sync/09 production cutover
```

`R12/R17/R18/R19` live in the repository-audit tracker. Ordinary CD never runs migration; H29/H30 define a
separately authorized maintenance flow. The table records minimal blockers; numbered serial execution is safe.

## Tickets

| # | File | Status | Blocked by |
|---|------|--------|------------|
| 01 | [`01-freeze-conflict-v2-contract`](./issues/01-freeze-conflict-v2-contract.md) | implemented (review/gates pass) | — |
| 02 | [`02-deterministic-nway-conflict-snapshot`](./issues/02-deterministic-nway-conflict-snapshot.md) | implemented (review/gates pass) | 01, R17, R18 |
| 03 | [`03-choice-only-authoritative-resolution`](./issues/03-choice-only-authoritative-resolution.md) | implemented (review/gates pass) | 02 |
| 04 | [`04-causal-tombstone-restore`](./issues/04-causal-tombstone-restore.md) | implemented (review/gates pass) | 03 |
| 05 | [`05-record-snapshot-room-projection`](./issues/05-record-snapshot-room-projection.md) | implemented (review/local gates pass; device Room residual) | 03, 04, R19 |
| 06 | [`06-record-choice-resolver`](./issues/06-record-choice-resolver.md) | implemented (review/local gates pass; device interaction residual) | 05 |
| 07 | [`07-family-conflict-inbox`](./issues/07-family-conflict-inbox.md) | implemented (review/local gates pass; device interaction residual) | 06 |
| 08 | [`08-conflict-page-persistence`](./issues/08-conflict-page-persistence.md) | implemented (review/local gates pass; device Room residual) | 05, R18 |
| 09 | [`09-conflict-freshness-refresh-ui`](./issues/09-conflict-freshness-refresh-ui.md) | implemented (review/local gates pass; device interaction residual) | 06, 07, 08, R19 |
| 10 | [`10-record-frozen-envelope-commit-first`](./issues/10-record-frozen-envelope-commit-first.md) | implemented (review/final gates pass; device Room residual) | 05 |
| 11 | [`11-provider-roots-commit-first`](./issues/11-provider-roots-commit-first.md) | implemented (review/final gates pass; device Room residual) | 10 |
| 12 | [`12-careplan-no-media-commit-first`](./issues/12-careplan-no-media-commit-first.md) | implemented (review/final gates pass; device Room residual) | 11 |
| 13 | [`13-wake-observation-commit-first`](./issues/13-wake-observation-commit-first.md) | implemented (review/final gates pass; device Room residual) | 11 |
| 14 | [`14-authenticated-sync-handshake`](./issues/14-authenticated-sync-handshake.md) | implemented (review/final gates pass; device instrumentation residual) | 12, 13 |
| 15 | [`15-retry-after-full-jitter`](./issues/15-retry-after-full-jitter.md) | implemented (review/final gates pass; device instrumentation residual) | 14 |
| 16 | [`16-gzip-bounded-pull`](./issues/16-gzip-bounded-pull.md) | implemented (review/final gates pass; device Room instrumentation residual) | 14, 15 |
| 17 | [`17-streaming-preimage-receipt`](./issues/17-streaming-preimage-receipt.md) | implemented (review/final gates pass; device instrumentation residual) | 14, 15 |
| 18 | [`18-android-immutable-media-spool`](./issues/18-android-immutable-media-spool.md) | ready-for-agent | 10, 17 |
| 19 | [`19-server-receipt-media-commit`](./issues/19-server-receipt-media-commit.md) | ready-for-agent | 17 |
| 20 | [`20-android-media-settlement`](./issues/20-android-media-settlement.md) | ready-for-agent | 18, 19 |
| 21 | [`21-record-media-commit-first`](./issues/21-record-media-commit-first.md) | ready-for-agent | 20 |
| 22 | [`22-baby-avatar-commit-first`](./issues/22-baby-avatar-commit-first.md) | ready-for-agent | 21 |
| 23 | [`23-careplan-media-commit-first`](./issues/23-careplan-media-commit-first.md) | ready-for-agent | 22 |
| 24 | [`24-server-media-staging-gc`](./issues/24-server-media-staging-gc.md) | ready-for-agent | 19, 23, R19 |
| 25 | [`25-commit-response-contraction`](./issues/25-commit-response-contraction.md) | ready-for-agent | 12, 13, 23 |
| 26 | [`26-delete-legacy-reconcile`](./issues/26-delete-legacy-reconcile.md) | ready-for-agent | 25 |
| 27 | [`27-final-schema-capability-activation`](./issues/27-final-schema-capability-activation.md) | ready-for-agent | 09, 15, 16, 23, 24, 25, 26, R19 |
| 28 | [`28-server-schema13-offline-migrate`](./issues/28-server-schema13-offline-migrate.md) | ready-for-agent | 27 |
| 29 | [`29-schema-cutover-cd-orchestration`](./issues/29-schema-cutover-cd-orchestration.md) | ready-for-agent | 28 |
| 30 | [`30-isolated-schema-cutover-rehearsal`](./issues/30-isolated-schema-cutover-rehearsal.md) | ready-for-agent | 29 |
| 31 | [`31-primary-carelog-real-server-seam`](./issues/31-primary-carelog-real-server-seam.md) | ready-for-agent | 30 |
| 32 | [`32-nway-field-null-acceptance`](./issues/32-nway-field-null-acceptance.md) | ready-for-agent | 31 |
| 33 | [`33-delete-restore-race-acl-acceptance`](./issues/33-delete-restore-race-acl-acceptance.md) | ready-for-agent | 32 |
| 34 | [`34-retry-lost-response-acceptance`](./issues/34-retry-lost-response-acceptance.md) | ready-for-agent | 15, 31 |
| 35 | [`35-pull-page-fault-acceptance`](./issues/35-pull-page-fault-acceptance.md) | ready-for-agent | 16, 31 |
| 36 | [`36-resource-saturation-acceptance`](./issues/36-resource-saturation-acceptance.md) | ready-for-agent | 31, R19 |
| 37 | [`37-media-source-spool-fault-acceptance`](./issues/37-media-source-spool-fault-acceptance.md) | ready-for-agent | 18, 31 |
| 38 | [`38-media-receipt-fault-acceptance`](./issues/38-media-receipt-fault-acceptance.md) | ready-for-agent | 19, 20, 31 |
| 39 | [`39-media-branch-performance-acceptance`](./issues/39-media-branch-performance-acceptance.md) | ready-for-agent | 21, 22, 23, 24, 31, 37, 38 |
| 40 | [`40-apk-upgrade-preservation-acceptance`](./issues/40-apk-upgrade-preservation-acceptance.md) | ready-for-agent | 27 |
| 41 | [`41-process-death-recovery-acceptance`](./issues/41-process-death-recovery-acceptance.md) | ready-for-agent | 09, 10, 20, 40 |
| 42 | [`42-conflict-device-interaction-acceptance`](./issues/42-conflict-device-interaction-acceptance.md) | ready-for-agent | 07, 09, 41 |
| 43 | [`43-final-local-review-handoff`](./issues/43-final-local-review-handoff.md) | ready-for-agent | 32–42 acceptance leaves |

External owners: [`R12 admission`](../repository-dedup-algorithm-audit-20260809/issues/12-bounded-conflict-resources.md),
[`R17 loader`](../repository-dedup-algorithm-audit-20260809/issues/17-bounded-conflict-head-loader.md),
[`R18 receipt/page`](../repository-dedup-algorithm-audit-20260809/issues/18-conflict-snapshot-receipt-pagination.md) (implemented), and
[`R19 retention`](../repository-dedup-algorithm-audit-20260809/issues/19-resolution-metadata-retention.md).
Production schema cutover/CD remains owned by
[`lossless-family-causal-sync/09`](../lossless-family-causal-sync/issues/09-two-client-cutover-release-and-acceptance.md).

## Frontier

H17 is implemented. H18 is the next executable DAG frontier; H18–H43 remain open and follow
the serial order above. H27
completes local 0.4.0 schemas/capability; H28–H30 prove non-destructive backend migration and guarded CD
locally; H43 hands evidence to release 09.

## Program invariants

- APK upgrade never clears Room or trust/session state; server migration never writes the source data root.
- Server startup and ordinary CD accept exact schema 13 only; migration is explicit maintenance-window copy-out.
- Preserve TLS identity, bootstrap secret, APK signer and full rollback state; no production action without confirmation.
- H43 may hand off evidence but cannot deploy or declare production acceptance.
