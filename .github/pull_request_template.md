## Summary

<!-- What changed and why (1–5 sentences). -->

## Linked ticket (local)

<!-- Source of truth is .scratch/, not GitHub Issues. -->

- Spec / issue path: `.scratch/<feature>/…`

## Type

- [ ] Feature
- [ ] Fix
- [ ] Refactor / cleanup
- [ ] Docs / agent / tooling
- [ ] License / repo hygiene
- [ ] Sync contract (`:sync` / `tools/lezi-sync` / PRD)

## Checklist

- [ ] `./gradlew test` passes locally (or CI green)
- [ ] If `tools/lezi-sync` changed: `cargo test --locked` and `cargo clippy --all-targets --all-features -- -D warnings`
- [ ] No secrets (`keystore.properties`, `*.jks`, bootstrap tokens, real SSID/family data)
- [ ] Contribution is MIT-compatible ([`LICENSE`](../LICENSE))
- [ ] Product docs updated when behavior or wire format changed (`docs/prd/`, ADRs)
- [ ] Domain terms match `CONTEXT.md` (记录 vs 计划, 同步包, etc.)
- [ ] After merge: sync `master` to LAN `origin` if that mirror is in use

## Sync / data (if applicable)

- [ ] Record or plan **photo packages** stay atomic (no metadata-only visible state)
- [ ] Wire / migration compatibility called out for older clients or NAS images
- [ ] Home-LAN gates (SSID / foreground / endpoint) not weakened by accident

## Test plan

<!-- How you verified: unit tests, emulator steps, dual-device, Docker health, etc. -->

1.
