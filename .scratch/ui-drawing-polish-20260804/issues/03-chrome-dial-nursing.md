# 03 — High-frequency chrome: dial + nursing

**What to build:** Clock dial and nursing confirm surfaces use the same Lezi
buttons, fields, and chips as the quick-record composer. Completing a nursing
timer or adjusting time no longer shows mixed Material/Lezi chrome.

**Blocked by:** 01 — Expand motion + density token tables.

**Status:** done

- [x] Clock dial actions/fields go through Lezi wrappers (no bare Material on that surface)
- [x] Nursing confirm chips/fields/actions go through Lezi wrappers
- [x] Busy states, validation, test tags, and content descriptions preserved
- [x] Dial geometry / nursing validation rules unchanged (chrome only)
- [x] `./gradlew :designsystem:test :feature:timer:test :app:assembleDebug` green for touched modules
