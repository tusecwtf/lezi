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
- Client bugs that exfiltrate care records, photos, or endpoint credentials off-device
  without user intent

Out of scope for a private family app (unless they enable remote compromise):

- Physical access to an unlocked phone
- Rooted device / debuggable sideload on the reporter's own handset
- Network attacker who cannot defeat the explicitly trusted HTTPS endpoint or
  obtain a device session — still useful to hear about if the behavior weakens
  those boundaries or enables public-internet exposure

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
| Release APK signer certificate SHA-256 | `config/release-apk-signer-sha256.txt` (public pin only) | **Yes**; changing it requires an authorized signing-key rotation |
| Android SDK path | `local.properties` | **Never** (gitignored) |
| Family owner / member tokens | App encrypted storage + NAS volume | **Never** in git |
| `LEZI_BOOTSTRAP_SECRET` | Persistent NAS `config/lezi-sync.env`; exact process-env pass-through at start; `age` ciphertext off-repo | **Never** plaintext or ciphertext backup in git |
| NAS TLS certificate | Persistent `/data/tls/server.crt`; public SHA-256/SPKI pins may be documented separately | **Never** commit the production certificate file; keep the exact pair in NAS encrypted backup |
| NAS TLS private key | Persistent `/data/tls/server.key`; integrity-protected NAS `age` ciphertext off-repo, restored only against independent public pins | **Never** in git, image, release package, or logs |

The decrypting age identity is kept separately in a password manager or offline medium. The
developer machine needs only its public recipients file for CD backups. NAS restore also requires
independently recorded production certificate SHA-256 and SPKI pins. Pre-TLS rollback-state restore likewise requires the
independently recorded ciphertext SHA-256 printed at capture time; a checksum stored beside the
ciphertext is not an authenticity anchor. NAS details:
[`tools/lezi-sync/deploy/DEPLOY.md`](tools/lezi-sync/deploy/DEPLOY.md) § Credential backup and restore.

The tracked `.env.example` deliberately leaves `LEZI_BOOTSTRAP_SECRET` empty. Generate a local
random value when an explicitly authorized fresh deployment needs one; never reuse a public example
placeholder as an owner password.

If a secret was committed: rotate it immediately, purge history on every
remote, and treat any published APK signed with a leaked keystore as untrusted.

## Sync threat model (short)

- Sync trusts an explicit HTTPS endpoint (system PKI or a user-confirmed SPKI
  pin) and uses independent, revocable device sessions. SSID/BSSID is not read
  or used as an identity boundary; synchronization remains foreground-only.
- Do not map LAN sync port `8765` or anonymous plaintext invite-install port
  `8767` to the public internet without an operator-reviewed firewall exception.
  Plaintext readiness port `8766` is container-internal only and is not
  published on the NAS host.
- Details: [`docs/spec/contracts/sync-trusted-endpoint.md`](docs/spec/contracts/sync-trusted-endpoint.md).

## Medical disclaimer

Growth charts and care logs are **not** medical devices or diagnoses. Security
reports about clinical accuracy are out of scope for this policy.
