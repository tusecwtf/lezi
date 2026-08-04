# 11 — Weak surfaces: menu / widget / export

**What to build:** Menu row icons feel one weight language; home widget padding/
type align with tokens within widget limits; export primary/secondary actions
and preview match Lezi chrome and motion so secondary surfaces no longer look
like leftovers.

**Blocked by:** 05 — Contract ban bare Material; 10 — Shell motion + reduce-motion.

**Status:** done

- [x] Menu icon treatment is consistent across rows
- [x] Widget / widget config spacing and type token-aligned where platform allows
- [x] Export action hierarchy + busy/preview behavior match Lezi patterns
- [x] No new widget product features; no export domain changes
- [x] `./gradlew :feature:settings:test :feature:export:test :feature:widget:test :app:assembleDebug` as applicable

**Done notes:** `LeziMenuIcon` well/glyph tokens + Outlined menu glyphs; `WidgetChrome`
maps Glance/config pads/type to `LeziSpacing`/`LeziTypography`; `exportActionChrome`
gives PDF primary / TXT secondary busy ownership; export preview uses
`leziMotionMillis` Base/Fast. Focused JVM tests green; `:app:compileDebugKotlin` green
(assemble not required for this UI-only slice).
