# 0.3.10 近邻落选 + 墓碑永胜 + 同步 chrome — issues

Spec: [`spec.md`](./spec.md)  
Tracker status: **implementation-complete** (awaiting user-confirmed NAS CD / signed release packaging)

## Graph

```text
01 ──► 02 ──► 03 ──┐
                   ├──► 04
05 ────────────────┤
06 ────────────────┘
```

| # | File | Status | Blocked by |
|---|------|--------|------------|
| 01 | [`issues/01-record-tombstone-wins.md`](./issues/01-record-tombstone-wins.md) | done | — |
| 02 | [`issues/02-neighbor-adjudication-on-commit.md`](./issues/02-neighbor-adjudication-on-commit.md) | done | 01 |
| 03 | [`issues/03-client-settle-and-neighbor-toast.md`](./issues/03-client-settle-and-neighbor-toast.md) | done | 01, 02 |
| 05 | [`issues/05-data-pages-unified-transient-sync-chrome.md`](./issues/05-data-pages-unified-transient-sync-chrome.md) | done | — |
| 06 | [`issues/06-member-last-sync-visible-to-all-roles.md`](./issues/06-member-last-sync-visible-to-all-roles.md) | done | — |
| 04 | [`issues/04-release-0.3.10.md`](./issues/04-release-0.3.10.md) | in-progress | 01, 02, 03, 05, 06 |

## Verification evidence

- Rust: `cargo fmt --check`, `cargo test --locked` (all green incl. neighbor/tombstone store + API), `cargo clippy -D warnings`
- Android: `:sync:testDebugUnitTest` + compile of designsystem / family / log / summary / growth / app
- Versions: Android `0.3.10` / versionCode `17`; lezi-sync `0.3.10`; `app-update.json` version fields aligned (sha256 still from prior signed APK — re-sign + repackage before CD)

## Frontier

Release packaging + user-confirmed NAS CD remain for ticket **04**. Do not run `push-and-deploy.sh` without confirmation.
