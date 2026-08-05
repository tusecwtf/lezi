# 03 — 写后下次喂养 / 计划体验对齐

**Status:** ready-for-agent

## Parent

[spec-02-gf-legacy-align/spec.md](../spec.md)

## What to build

After confirming a 配方奶 护理记录, the **next-feed / care-plan** moment matches 0.3.x caregiver expectation: either a post-write offer to schedule next feed (legacy-style) **or** an in-timeline 待履行 path that is equally clear and not surprising. Domain plans still use confirm-before-write; no silent plan without caregiver intent where product requires it.

**Product default for this ticket:** prefer **legacy-style post-write offer** if implementable without breaking GF plan model; otherwise polish in-row 待履行 to parity clarity and document residual.

## Acceptance criteria

- [ ] After formula confirm, caregiver sees a clear next-step for plan/next feed (dialog and/or row)
- [ ] Skip/dismiss does not leave confusing English or debug state
- [ ] Fulfill/skip (if present) still confirm-safe
- [ ] Dual still of post-write moment GF vs legacy; residual R-next-feed updated

## Blocked by

None — can start immediately
