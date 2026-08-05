# 21 — 总评卷 · 矩阵 + residual + §2.3

**Status:** ready-for-agent

## Parent

[spec-02-dual-scene-review/spec.md](../spec.md) · freeze [../spec-02-apk-1.0-freeze/spec.md](../../spec-02-apk-1.0-freeze/spec.md)

## What to build

**Acceptance-only rollup.** Fill the scene matrix from tickets 03–17 (and 18–20 if done). Produce:
- Green/yellow/red (or pass/residual/fail) per scene band
- Consolidated residual list (candidates for polish tickets)
- Recommendation: whether to retry full §2.3 blind chrome now, later, or not yet
- Explicit statement that 1.0.0 S-freeze remains closed

**Do not** edit Compose UI inside this ticket. Open separate polish issues if fixes are wanted.


## Acceptance criteria

- [ ] Matrix complete for all finished scene tickets
- [ ] Residual list consolidated with scene IDs
- [ ] §2.3 retry recommendation recorded
- [ ] No UI code changes in this ticket’s deliverable
- [ ] Links to evidence roots updated in engineering baselines README or review-notes (docs only)


## Blocked by

03–17 (P0). Include 18–20 if those P1 tickets completed.
