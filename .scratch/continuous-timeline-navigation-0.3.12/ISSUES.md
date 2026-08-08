# 0.3.12 · 连续时间条与最近 24 小时 — tracker

Status: ready-for-agent

Spec: [`spec.md`](./spec.md)
Target: Android **0.3.12 / versionCode 19**；`lezi-sync:0.3.12`

## Graph

```text
01 ──► 02 ──┐
            ├──► 04 ──► 05 ──► 06
03 ─────────┘
```

| # | File | Status | Blocked by |
|---|------|--------|------------|
| 01 | [`issues/01-continuous-timeline-interaction.md`](./issues/01-continuous-timeline-interaction.md) | complete | — |
| 02 | [`issues/02-global-date-filter-and-timeline-experience.md`](./issues/02-global-date-filter-and-timeline-experience.md) | complete | 01 |
| 03 | [`issues/03-all-version-lossless-apk-upgrade.md`](./issues/03-all-version-lossless-apk-upgrade.md) | complete | — |
| 04 | [`issues/04-build-and-package-0.3.12-release-candidate.md`](./issues/04-build-and-package-0.3.12-release-candidate.md) | ready-for-agent | 01, 02, 03 |
| 05 | [`issues/05-signed-historical-upgrade-device-acceptance.md`](./issues/05-signed-historical-upgrade-device-acceptance.md) | ready-for-agent | 04 |
| 06 | [`issues/06-nas-deploy-and-release-smoke.md`](./issues/06-nas-deploy-and-release-smoke.md) | ready-for-agent | 05 + explicit CD confirmation |

## Frontier

Ticket **04** is the current frontier after the interaction and all-version upgrade chains converged.
Ticket **06** must not
execute NAS replacement until ticket 05 is accepted and the user gives a fresh explicit CD confirmation.

## Release boundary

- No Room/local-data/wire/server-schema change; preserve each source version's existing data, family
  session and endpoint trust across the in-place upgrade.
- Keep `min_supported_version_code = 16`; it gates sync only. Every previously released production
  version must retain a signer/hash-verified path to the latest APK, using LAN `8767` recovery when the
  old client cannot use the authenticated HTTPS update flow.
- 0.3.12 is optional for supported clients. Clients below 16 may remain sync-blocked before upgrade;
  after installing 0.3.12 they must pass normal TLS certificate/SPKI verification and regain sync.
- Local implementation starts from frontier tickets 01 and 03; each ticket remains governed by
  the complete `spec.md` contract.
- NAS CD and container replacement require a fresh explicit user confirmation after all local gates pass.
- Tracker/spec completion alone is not APK, device or NAS acceptance.
