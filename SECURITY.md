# Security Policy

## Supported versions

Security fixes target the latest `master` build and the most recent tagged
release when tags exist. Older app versions are not backported unless a
release still ships to a device we actively maintain.

## What to report

Please report:

- Credential or token leakage (family invite, bootstrap secret, keystore)
- Authz bypass on `tools/lezi-sync` (join, pull/push, media, member admin)
- Cross-family or cross-device data exposure
- Unsafe defaults that would publish the sync port to the public internet
- Client bugs that exfiltrate care records, photos, or SSID data off-device
  without user intent

Out of scope for a private family app (unless they enable remote compromise):

- Physical access to an unlocked phone
- Rooted device / debuggable sideload on the reporter's own handset
- LAN attacker on an already-trusted home Wi‑Fi **without** further privilege
  beyond what the documented threat model allows — still useful to hear about
  if it escalates to other homes or the public internet

## How to report

This repository is private. Prefer one of:

1. A private note to the repository owner on the channel you already use for
   this project
2. A GitHub Security Advisory on this repo if you have access
   (**Security → Advisories → New draft advisory**)

Do **not** open a public issue with exploit details, tokens, or family data.

Include: affected component (app / `lezi-sync` / both), version or commit,
reproduction steps, and impact. Redact real baby names, photos, and host
secrets.

## Handling secrets in this project

| Secret | Where it lives | Git? |
|--------|----------------|------|
| Release keystore + passwords | `*.jks` / `keystore.properties` (local) | **Never** (gitignored) |
| Android SDK path | `local.properties` | **Never** (gitignored) |
| Family owner / member tokens | App encrypted storage + NAS volume | **Never** in git |
| `LEZI_BOOTSTRAP_SECRET` | NAS / compose env | **Never** in git |

If a secret was committed: rotate it immediately, purge history on every
remote, and treat any published APK signed with a leaked keystore as untrusted.

## Sync threat model (short)

- Home-LAN only by product intent: SSID allowlist + reachable NAS + app
  foreground. No cloud account is required for core logging.
- Do not map port `8765` to the public internet without TLS reverse proxy and
  operator-reviewed exposure.
- Details: [`docs/prd/sync-home-lan.md`](docs/prd/sync-home-lan.md).

## Medical disclaimer

Growth charts and care logs are **not** medical devices or diagnoses. Security
reports about clinical accuracy are out of scope for this policy.
