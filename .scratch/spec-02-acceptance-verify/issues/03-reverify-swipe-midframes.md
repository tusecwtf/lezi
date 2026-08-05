# 03 — 重验 11–13 滑动中途静帧

**Status:** ready-for-agent

## Parent

[spec-02-acceptance-verify/spec.md](../spec.md)

## What to build

With multi-row seed and next-feed dismissed:

- **swipe-half-reveal** — still at half-trip edit reveal (green intent)
- **swipe-full-edit** — still at full-trip left → edit Composer open
- **swipe-full-delete** — still at full-trip right → delete confirm visible (cancel, no write)

Score interaction + motion language from mid-frames + short video.

## Acceptance criteria

- [ ] Three scene ids have mid-state gf stills (legacy best-effort but not empty home only)
- [ ] Matrix 11–13 updated from residual/weak static to verified or honest partial
- [ ] Cancel delete does not write

## Blocked by

01
