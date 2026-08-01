# Maintenance window (ticket 07)

- window_start_utc: 2026-07-31T18:31:27Z (approx; stop container)
- window_end_utc: 2026-07-31T18:44:04Z
- operator: implement agent on main tree
- package: dist/lezi-sync-0.3.0-nas (LEZI_SKIP_PACKAGE=1)
- image: lezi-sync:0.3.0 (TLS stack; post-load id differs from pre-cutover HTTP image)
- pre-cutover image saved: NAS /tmp/lezi-sync-releases/pre-cutover/lezi-sync-pre-cutover.tar
- pre-cutover image id: sha256:b11a1c1a9b9bc23261c0774364729b9a8c452155e4cd0e9892aea1f16750a952
- NAS backup path: /tmp/lezi-sync-releases/pre-cutover-data-20260731T183137Z
- local RO copy-out: $HOME/lezi-nas-backups/lezi-data-ticket07-precutover
- migrate out: $HOME/lezi-nas-backups/lezi-data-ticket07-out (user_version=11, validate ok)
- data remediations (migrate-in clone only; RO backup unchanged):
  - ActiveDisplayNameKeyConflict: membership 351bc152… display_name `<display_name_a>` → `<display_name_b>`
- copy-back: docker-assisted path (host cannot chown 10001 / write parent)
- bootstrap: migration-time new root password forwarded with LEZI_FORWARD_BOOTSTRAP_SECRET=1 (value not recorded here)
- SPKI SHA-256: BB:A1:05:DE:62:4F:C6:2E:88:4B:F1:AD:3A:CB:EC:5D:28:5A:4C:D1:76:96:D3:A6:CA:6E:9E:8D:6F:DB:9E:45
