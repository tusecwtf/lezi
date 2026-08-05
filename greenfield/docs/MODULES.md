# Greenfield module & library defaults

**Locked for implementation (ticket 02).**

## Android (`greenfield/android`)

| Module | Package | Role |
|--------|---------|------|
| `:kernel` | `com.lezi.gf.kernel` | Pure types, IDs, clock, result tokens; no Android |
| `:care` | `com.lezi.gf.care` | Records, plans, aggregation, timer, photos, custom items layout |
| `:family` | `com.lezi.gf.family` | Babies, local account projection (no wire client) |
| `:settings` | `com.lezi.gf.settings` | Local display/a11y/about prefs |
| `:syncsession` | `com.lezi.gf.syncsession` | **Only** wire client, TOFU/SPKI, reconcile, update coordination |
| `:app` | `com.lezi.gf.app` | Composition root, Compose UI, Android adapters |

**DI:** manual composition root in `:app` (`AppContainer`). No Hilt.

**Persistence:** pure-Kotlin repository interfaces; default JSON file store under app files dir (L1/L2 use in-memory).

**Dependency rule:** verticals depend only on `:kernel`. Verticals do **not** depend on each other. Only `:app` assembles them. Wire HTTP lives only in `:syncsession`.

**applicationId:** `com.lezi.babylog.gf` · **versionName:** `1.0.0`

**Default endpoint:** `https://127.0.0.1:18765` (never family NAS).

## Server (`greenfield/sync-server`)

| Crate | Role |
|-------|------|
| `lezi-gf-sync` | Single binary; thin handlers + deep store |

**Stack:** Axum + rusqlite + rustls. **Port:** HTTPS 18765 (+ HTTP readiness 18766 internal). **Data:** `greenfield/.data/` (override `LEZI_GF_DATA`). **Version:** `1.0.0`.

**No path dependency** on `tools/lezi-sync`.
