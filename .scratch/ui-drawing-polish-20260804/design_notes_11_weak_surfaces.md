# Design notes — 11 weak surfaces: menu / widget / export

## Public seams under test

Observed without reaching private Compose trees. Expected values from ticket 11 /
shared `LeziSpacing` + `LeziTypography` + `LeziMotion` product literals.

1. **Menu icon treatment (one weight language)**
   - Named token object `LeziMenuIcon` (designsystem):
     - `WellSize` = 40.dp (10×4 grid)
     - `GlyphSize` = `LeziSpacing.Lg` (20.dp) — fixed glyph box so filled/outlined
       rows paint the same optical weight
   - `SettingsMenuRow` consumes those sizes for the leading well + `Icon` glyph
     (no ad-hoc `40.dp` / default Material icon size drift).
   - Menu row glyphs use **Outlined** Material icons (same family) so stroke weight
     matches across Search / Export / Calendar / Display / Add / Danger rows.
   - Danger tint stays error; non-danger uses onSurface. Touch min remains
     `LeziSpacing.Touch`. Chevron affordance unchanged.

2. **Widget + config spacing/type (platform limits)**
   - Pure `WidgetChrome` object maps Glance + config pads/type to `LeziSpacing` /
     `LeziTypography` steps (pad H/V, stack gap, action pad, title = Label 13sp,
     body = Meta 12sp). Glance cannot host Compose `TextStyle`; it uses the
     token **sizes** only.
   - `CareWidget` content pads/type read `WidgetChrome` (no leftover 12/8/14/4
     bare literals on those roles).
   - Widget configuration screen titles/body use `LeziTypography` + row min
     height `LeziSpacing.Touch`; primary save stays `LeziPrimaryButton`.
   - No new widget product features (still summary + configurable quick actions).

3. **Export action hierarchy + busy / preview motion**
   - Pure `exportActionChrome(busyFormat)` presentation:
     - PDF = primary busy owner; TXT = secondary
     - Only the active format shows “正在生成…” + `busy=true`
     - Controls disabled while any export is in flight
   - Preview `AnimatedVisibility` uses `leziMotionMillis(LeziMotion.Base|Fast)`
     enter/exit (respects reduce-motion from ticket 10).
   - No export domain / FileProvider / format generator changes.

## Out of scope

- Phase A bare-Material ban expansion for Checkbox/RadioButton (still residual
  on calendar + widget config selection chrome; not this light pass).
- Summary/growth/family list polish (07–09).
- Screenshot matrix (13).
- CareLog write, sync wire, Room schema, NAS CD.
