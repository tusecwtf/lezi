# 36 — Custom-item family invariants

**What to build:** Family-wide live custom defs respect max 10 on server (and client after pull). Layout slots bind a stable identity (clientUuid) across clear/rejoin. Creating a custom record requires a live def (like care plans). Unique clientUuid index.

**Blocked by:** Soft with 17 (settings busy).

**Status:** implemented — awaiting live acceptance on `6b278242`

- [x] Two devices cannot permanently push >10 live defs.
- [x] Dock custom:N survives wipe without binding the wrong item.
- [x] Tombstoned def cannot create new local record that only fails on push.

**Residual:** server/client caps, stable migration identity, and tombstone guards are
accepted by static and automated evidence; family-device acceptance remains:

- [ ] On two real devices, exercise concurrent custom creation, wipe/rejoin layout
      restoration, and tombstoned-definition record rejection.
