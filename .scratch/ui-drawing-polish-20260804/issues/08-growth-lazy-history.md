# 08 — Growth: lazy history + surface polish

**What to build:** Growth measurement history scrolls as a lazy list with stable
keys and item animation; list feels consistent with the record timeline when
rows appear or leave. Growth chrome stays on Lezi controls.

**Blocked by:** 07 — Summary density + empty/calculating honesty.

**Status:** done

- [x] History is a lazy list with stable keys
- [x] Item animation applied for insert/remove where foundation API allows
- [x] Edit/save/delete flows still work; test tags/semantics preserved
- [x] No schema or growth reference-curve math changes
- [x] `./gradlew :feature:growth:test :app:assembleDebug` green
