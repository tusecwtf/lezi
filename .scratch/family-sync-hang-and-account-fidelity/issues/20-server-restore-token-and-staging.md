# 20 — Server: restore access expiry and open staging GC

**What to build:** Disaster-restore access tokens are valid for a usable window
**after commit** (mint or refresh expiry at commit, not only from batch start).
Open staging bundles have TTL/GC or equivalent so abandoned mid-upload packages
cannot permanently brick push at the open-staging cap.

**Blocked by:** None — can start immediately; **requires lezi-sync release**.

**Status:** complete — accepted on `6b278242`

- [x] Commit returns access expiry in the future relative to commit time (or
      client can refresh without immediate 401).
- [x] Open staging cap cannot permanently brick a family after many abandoned
      stages without operator DB surgery (TTL, replace, or GC).
- [x] Approved member requests that cannot be claimed have an owner-visible
      recovery or automatic release of reserved display name (at least document
      + implement one: reject/cancel approved or expire reservation early on
      claim failure class).
- [x] Rust tests for commit-time access TTL; staging GC/TTL behavior.
