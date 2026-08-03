# 31 — Server ForbiddenRecord for member manage of foreign records

**What to build:** lezi-sync rejects member update/delete/restore of records whose effective creator is not self (owner may manage all), matching care_plan/custom_item ACL. Record log media writes follow the same author rule. Existing API tests that expect member rewrite of owner records are split into author-freeze vs forbidden content change.

**Blocked by:** None — lezi-sync release.

**Status:** implemented — awaiting live acceptance on `6b278242`

- [x] Member cannot LWW-edit or tombstone another member/owner’s record.
- [x] Same-membership second device may still manage own author rows.
- [x] Record-attached log media requires creator or owner.
- [x] API regression tests 403 ForbiddenRecord (or product-equivalent).

**Residual:** ACL rules, API regressions, and the ordinary 0.3.5 NAS CD are
accepted; deployed-family identity acceptance remains:

- [ ] On deployed 0.3.5, verify a real Member receives ForbiddenRecord for
      foreign record/media mutation while the same membership’s second device
      remains allowed.
