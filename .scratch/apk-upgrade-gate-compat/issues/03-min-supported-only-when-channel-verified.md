# 03 — 仅在更新通道可用时抬高版本门槛

**What to build:** The server only enforces `min_supported_version_code` on authoritative sync (pull / media / bundles) when a **verified** app-update package channel is available. A half-deployed channel (metadata alone, missing or integrity-failing APK) must not brick the family into forced upgrade with nothing to install. Deploying app-update artifacts must not expose a live window of “new floor + old/broken package” under an already-running service (atomic pair publish or equivalent). Missing metadata still fails open for sync.

**Blocked by:** None — can start immediately.

**Status:** done

- [x] Valid min in metadata but APK missing/hash mismatch → no `client_update_required` from the version gate (or an explicit channel-broken path that does not claim install is available)
- [x] Verified package present + client below min → still `client_update_required` on gated routes; app-update routes remain usable for clients below min
- [x] No metadata → sync still fail-open
- [x] CD/app-update install path has no operator-visible intermediate “new min, bad package” state for the running service
- [x] Server/deploy tests cover verified vs metadata-only gate behaviour

## Design notes (public seams)

1. **HTTP version gate seam** — `require_supported_client` on pull / media / bundles: enforces via `AppUpdateCache::min_supported_if_verified` (verified pair, or last-known-good floor retained across mid-promote integrity miss). Metadata-only / never-verified / channel gone → fail-open. Negative stamp cache avoids re-hashing a permanent half-deploy on every request; gate logs once per stamp pair on fail-open or retain.
2. **App-update routes** — still ungated for clients below min when a verified package exists. `GET /v1/app-update` and APK both require `load_verified` so clients never dual-tier a force floor without an installable package.
3. **CD seam** — `remote-deploy.sh` atomic pair publish: stage both artifacts, promote APK then metadata; smoke `deploy/test-remote-deploy-app-update-atomic.sh` (static contract + direct path + mock-docker direct-fail→fallback recovery).
