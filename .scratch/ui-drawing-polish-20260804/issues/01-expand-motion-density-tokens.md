# 01 — Expand motion + density token tables

**What to build:** Ship shared motion durations (fast / base / emphasized) and
warm-open / journal-compact structural density tables on the 4/8 grid, **beside**
existing spacing tokens so nothing must migrate yet. Caregivers see no required
visual change; agents get one vocabulary for later tickets.

**Blocked by:** None — can start immediately.

**Status:** done

- [x] Motion tokens exist (three duration tiers) and are documented for main transitions
- [x] Density table (or dual structural tokens) exists: warm more open, journal more compact; values on 4/8 grid
- [x] Existing screens still compile and look unchanged without consuming the new tables
- [x] Audit-path contract (or unit assertion) proves motion + density symbols exist so they cannot vanish casually
- [x] `./gradlew :designsystem:test :app:assembleDebug` green
