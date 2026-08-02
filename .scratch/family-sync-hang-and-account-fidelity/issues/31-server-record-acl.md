# 31 — Server ForbiddenRecord for member manage of foreign records

**What to build:** lezi-sync rejects member update/delete/restore of records whose effective creator is not self (owner may manage all), matching care_plan/custom_item ACL. Record log media writes follow the same author rule. Existing API tests that expect member rewrite of owner records are split into author-freeze vs forbidden content change.

**Blocked by:** None — lezi-sync release.

**Status:** ready-for-agent

- [ ] Member cannot LWW-edit or tombstone another member/owner’s record.
- [ ] Same-membership second device may still manage own author rows.
- [ ] Record-attached log media requires creator or owner.
- [ ] API regression tests 403 ForbiddenRecord (or product-equivalent).
