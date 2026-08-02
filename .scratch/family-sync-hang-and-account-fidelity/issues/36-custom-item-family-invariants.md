# 36 — Custom-item family invariants

**What to build:** Family-wide live custom defs respect max 10 on server (and client after pull). Layout slots bind a stable identity (clientUuid) across clear/rejoin. Creating a custom record requires a live def (like care plans). Unique clientUuid index.

**Blocked by:** Soft with 17 (settings busy).

**Status:** ready-for-agent

- [ ] Two devices cannot permanently push >10 live defs.
- [ ] Dock custom:N survives wipe without binding the wrong item.
- [ ] Tombstoned def cannot create new local record that only fails on push.
