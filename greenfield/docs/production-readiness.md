# Greenfield production-readiness (replaceability bar)

**Date:** 2026-08-05  
**Product line:** greenfield Android `1.0.0` + `lezi-gf-sync` `1.0.0`  
**Default endpoint:** `https://127.0.0.1:18765` — **never** family NAS by default.

## Status vs production-replaceable criteria

| # | Criterion | Status | Ship path |
|---|-----------|--------|-----------|
| 1 | Durable care/family state; corrupt fail-closed | **Met** | `AtomicFileStore` temp→fsync→rename; corrupt load → gate; **`persist()` no-op when corrupt** so empty RAM cannot wipe damaged files |
| 2 | Multi-device LWW + atomic media + SPKI hard-block | **Met** | Server `upsert_lww`; client `EntityMerge`; **`PhotoWireCodec` reads real file bytes** (no `dGVzdA==` stub); incomplete → reject/drop; SPKI hard-block |
| 3 | Payload fail-closed | **Met** | `PayloadValidation` on confirm/edit; server `validate_record_payload` + known type keys; unknown push kinds rejected |
| 4 | No kitchen-sink mega suites / mega façades | **Met** | Path-scoped tests only; vertical modules care/family/syncsession |

## Explicit non-cutover / ops-only (not claimed here)

- **Live family NAS CD** of 0.3.x `tools/lezi-sync` (push-and-deploy, TLS identity non-rotation on production bind).
- **Legacy Room offline-migrate** of 0.3.x DBs into greenfield (fresh greenfield data root / new line).
- **Replacing the running family container** with `lezi-gf-sync` without an operator-owned cutover plan.
- Pixel-perfect UI parity with 0.3.x (already gated separately; not a production data-plane requirement).

## Follow-on specs (seamless cutover split)

| Spec | Path |
|------|------|
| Backend / DB migration | [`specs/01-backend-data-migration.md`](./specs/01-backend-data-migration.md) |
| APK visual parity (feel · elements · motion) | [`specs/02-apk-visual-parity.md`](./specs/02-apk-visual-parity.md) |
| Index | [`specs/README.md`](./specs/README.md) |

## Multi-device winner rule (documented)

For the same `client_uuid` entity:

1. Compare `updated_at_ms` (higher wins).  
2. On equal timestamps, the **incoming** push / remote apply wins.  
3. Incomplete atomic photo packages are never family truth (reject on push; drop on pull apply).

## How to verify locally

```bash
cd greenfield/android && ./gradlew test :app:assembleDebug
cd greenfield/sync-server && cargo test --locked
# optional LiveWire: run lezi-gf-sync on 18765, then LiveWireG3G10Test
```

## Architecture note

Kitchen-sink original suites (`api.rs` 14k, `RealSyncPortTest`, `CareLogTest`) and SyncPort/CareLog mega-façades are **not** reintroduced. Production safety is path-scoped pure logic + thin server store.
