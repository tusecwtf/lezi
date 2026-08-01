# Owner API smoke (ticket 07)

- endpoint: https://192.168.50.4:8765
- protocol: HTTPS (cacert from data-bind tls/server.crt via docker exec)
- setup-status: family_state=configured, trusted_https_endpoint_v1
- owner login: OK with migration-time new root password (x-lezi-bootstrap-secret)
- membership_id: redacted
- family_name: redacted
- pull cursor=0 + generation + x-lezi-client-version-code:6:
  - entity_count: 22 (pre-write; type mix includes baby/record/care_plan/media)
  - sample record catalog keys (non-identifying): weight, height, formula, pee, sleep
  - media GET /v1/media/<uuid> → 200, ~51 KiB (avatar; uuid redacted)
- new record: POST /v1/bundles + commit formula note "ticket07 cutover smoke write" → found on pull
- secrets: redacted (never printed)
