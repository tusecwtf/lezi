# RESULT · ticket 07 live cutover

**status: PASS**

| Criterion | Result |
|-----------|--------|
| Maintenance window per runbook; live health/ready current + HTTPS | PASS — version 0.3.0 HTTPS :8765; see health.json |
| Local APK endpoint trust + owner login | PASS — TOFU SPKI + owner root password on emulator |
| Pre-migration authoritative records visible; media sample | PASS — 16 alive records + media bytes via API; timeline UI |
| New write syncs | PASS — bundle commit formula found on pull |
| evidence/07 records version, window, result | PASS — this directory |

Rollback: not required (cutover accepted). Pre-cutover image tar retained on NAS under /tmp/lezi-sync-releases/pre-cutover/.

Notes:
- Host data bind is mode 700 uid 10001; copy-out uses docker tar; copy-back uses docker-assisted swap; remote-deploy app-update install uses docker as 10001.
- remote-deploy health check initially failed (cacert unreadable by SSH user) while container was healthy; fixed to fall back to docker exec / curl -k.
- Display-name conflict remediations applied on migrate-in clone only.
