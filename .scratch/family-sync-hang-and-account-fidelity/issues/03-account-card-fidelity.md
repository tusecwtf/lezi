# 03 — Align account family card with backend

**What to build:** The account family overview matches real capabilities and
state. **Pick one product path before coding:**

- **Path A (honest card):** Owner card never claims on-card rename; rename only
  from members page; no fake tappable chrome.
- **Path B (wire rename):** Owner can rename shared family name from the card
  with busy/success/failure using the existing rename dialog.

Member/device entry shows loading and error instead of a permanent “查看家人”.
Unjoined and waiting keep a single primary path. Sync status **line depth** is
ticket **09** (do not re-own outbox pending projector here).

**Blocked by:** Soft-serial with 09/10 on account chrome; can start immediately
after path A/B is chosen.

**Status:** complete — accepted on `6b278242`

- [x] Path A or B implemented and documented in PR.
- [x] Roster entry: loading label while fetching; error + retry when failed.
- [x] Sync line remains non-interactive result text.
- [x] Semantics: unjoined | waiting | Owner | Member primary paths.
