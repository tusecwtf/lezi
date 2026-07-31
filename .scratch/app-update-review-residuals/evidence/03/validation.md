# Ticket 03 · 拒绝 minSupported > versionCode

- Date: 2026-07-31 (Asia/Shanghai)
- Tracker: `.scratch/app-update-review-residuals`
- Ticket: `issues/03-reject-min-supported-gt-version.md`
- Validation base (pinned): `55c18eb8c46549d2d7aae0de96a2b993d10bb9d8`
- Worktree changes (uncommitted at validation; Commit phase owns the atomic commit):
  - `tools/lezi-sync/src/lib.rs`
  - `tools/lezi-sync/tests/api.rs`
  - `tools/lezi-sync/deploy/package-nas.sh`
  - `tools/lezi-sync/deploy/DEPLOY.md`

## Disposition

Dual-gate: illegal `min_supported_version_code > version_code` cannot ship or serve.

1. **package-nas** Python validator fail-closed: `version_code` in `1..=i32::MAX`,
   `min_supported` in `0..=i32::MAX`, and `min_supported <= version_code`.
2. **lezi-sync** `normalize_app_update_metadata` rejects the same deadlock; load logs
   `tracing::error` with path + detail (matches APK integrity log style).
3. **GET /v1/app-update** with deadlock metadata → 500 (not a valid update channel);
   client version gate fail-opens so bad metadata does not brick sync forever.
4. **DEPLOY.md** documents the invariant.

## Automated gates

```bash
cd tools/lezi-sync
cargo fmt --all -- --check
cargo test --locked app_update_
cargo test --locked client_update_required
cargo test --locked client_version_gate
cargo clippy --all-targets --all-features -- -D warnings
```

Covered cases:

| Case | Assertion |
|------|-----------|
| normalize min > version | `INTERNAL_SERVER_ERROR` detail, unit test |
| normalize min == version | OK (legal force floor) |
| GET /v1/app-update min > version | 500; not 200 body |
| invalid channel + pull | version gate fail-open → pull OK |
| existing force-upgrade paths | still allow app-update download below min |

## package-nas smoke (no docker)

Inline Python extract of the embedded validator: good/equal OK; min>version and
`version_code=0` / `version_code > i32::MAX` non-zero exit.

## Out of scope / deferred

- `docs/prd/tech.md` one-liner for product authority → ticket **06**
  (this ticket AC only required DEPLOY/runbook).
