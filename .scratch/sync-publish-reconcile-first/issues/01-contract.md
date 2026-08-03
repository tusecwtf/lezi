# 01 — Contract & ADR: reconcile-first ephemeral publish plan

**What to build:** Write the product/architecture contract (ADR or PRD sync
section pointer) for publish planning so implementation tickets share one
vocabulary.

**Blocked by:** None.

**Status:** complete

## Contract (locked)

1. **Long-lived intent** = Room care entities (and media rows/files), not a
   durable outbox queue.
2. **Each sync cycle** = `reconcile (pull)` → `plan (ephemeral)` → `push
   (atomic bundles)` for **Owner and Member**.
3. **Remote compare v1** = incremental pull + local entity diff; no full-family
   snapshot API.
4. **NAS protocol** unchanged.
5. **Pending count** for shallow status derives from “still needs publish”
   Room state after last reconcile, not residual outbox row count.
6. **Upgrade** must preserve Room/media and hand off any pre-migration outbox
   identities into needs-publish eligibility (see ticket 03).

## Deliverables

- [x] ADR under `docs/adr/` (or agreed PRD § pointer) stating the above.
- [x] Glossary terms if needed: publish plan, reconcile, needs-publish vs
      `syncDirty` (implementation may keep `syncDirty` as a marker, but it is
      not a second queue of envelopes).
- [x] Explicit non-goals: head-by-uuid, full snapshot, server LWW rule changes.

Implemented by ADR-0016, `CONTEXT.md`, and the PRD write/CAS contract updates.

## Comments

- Grilled 2026-08-04 with user; experimental client patch reverted first.
