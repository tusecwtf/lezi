# APK 升级门闩 · 同步兼容

Status: done — tickets ready-for-agent (to-tickets, approved 2026-08-04)

## Goal

Home-LAN multi-device upgrades stay recoverable and honest:

1. Forced clients always reach an **installable** package when the server channel has one (or a clear channel-failure shell).
2. Force shell does not deadlock **session recovery** or one-shot download auth retry; LAN invite path is a documented escape when auth update is dead.
3. `min_supported` only forces when a **verified** app-update channel exists; CD does not flash “new floor + bad package”.
4. setup-status may **add** fields without bricking existing apps as non-lezi.
5. Disaster restore write paths use the same client version floor as sync.
6. Wire-breaking releases raise min before new shapes are published (process; no dual-read wire).

## Tickets (vertical slices)

See [ISSUES.md](./ISSUES.md). Dependency order: **01 → 02**; **03–06** independent of 01/02.

## Release boundary

- Keep closed wire / exact `schema_version` policy (ADR-0008); ticket 06 is discipline, not skip-unknown.
- Keep force surface non-dismissible for main features (no “稍后” bypass) unless product later opens local-only.
- Do not rotate TLS identity in ordinary CD; do not invent bootstrap secrets.
- Empty-family disaster restore (ADR-0014) unchanged except client version gate on write paths.

## Must

- [x] 01 installable forced package after CUR when newer metadata exists
- [x] 02 reauth / 401-retry / 8767 guidance under force
- [x] 03 min gate tied to verified channel + safe app-update publish
- [x] 04 additive setup-status parse
- [x] 05 restore version gate
- [x] 06 wire-break → raise min checklist

## Out of scope

- Play In-App Updates; debug applicationId self-update
- Full dual-read / skip-unknown wire protocol
- Force shell process-durable persistence (optional follow-up)
- Local-only care logging under force (unless product reopens)
- Full dual-device matrix (minimal smoke per ticket is enough)

## Validation (program-level)

- [x] lezi-sync: `cargo fmt --check`, `cargo test --locked`, clippy `-D warnings` when server/deploy touched
- [x] Client JVM tests for force/CUR/setup as claimed by tickets
- [x] Deploy script tests when ticket 03 touches packaging/CD helpers
- [x] Minimal joined release-path smoke for force install when 01–02 land

## Source audit

- `docs/reviews/2026-08-04-apk-upgrade-gate-and-sync-compat-audit.md` (local)
- PRD: `docs/prd/tech.md` §4.2, `docs/prd/sync-trusted-endpoint.md` §7.5
