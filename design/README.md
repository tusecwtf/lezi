# Design tokens & OD sync

## Source of truth

- Open Design project: `leji-prd-prototype`
- Checked-in snapshot: `prototype/`
- Shared tokens: `design/tokens.json`
- Compose consumption: `designsystem/` (`Tokens.kt`, `Theme.kt`, `Components.kt`)

## Two-template contract

| Key | Product label | Design source | Role |
|-----|---------------|---------------|------|
| `warm` | 温暖卡片 | `leji-prd-prototype` / `design/tokens.json` | Original, default template |
| `journal` | 紧凑记录簿 | `lezi-piyolog-template-v2` / `design/template-v2/` | Dense log and chart-grid template |

Both keys render the same routes, records, forms, state and persistence. A template may change tokens, component geometry, information density and chart marks; it must not fork business logic or store separate data. The selection is local-only in `SettingsLocal.visualStyle` and is available under 菜单 → 显示 → 界面模板.

The second template uses the collected public UI screenshots only to study density, hierarchy and chart grammar. It keeps the 乐记 brand and does not ship reference-product names, mascots, illustrations or icon assets.

Both templates also share the one-handed interaction contract: a fixed bottom
quick-record dock, a persisted left/right thumb preference, one-tap pee and
sleep actions, two-tap nursing/formula paths, a scroll-safe “more” sheet, and a
fixed full-width save action on long record forms. Visual style may restyle the
dock, but must not move it back into scrolling content.

Canvas baseline: **390 × 844**.

## Sync flow (every OD change)

1. Edit the matching prototype in Open Design (`leji-prd-prototype` or `lezi-piyolog-template-v2`).
2. Copy/update the matching snapshot (`prototype/` or `template-v2/`).
3. Diff classify: tokens · components · layout · interaction.
4. Update Compose:
   - tokens → `design/tokens.json` + `designsystem/Tokens.kt` / `Theme.kt`
   - components → `designsystem/Components.kt`
   - screens → matching `feature/*`
5. Run:
   - `./gradlew test`
   - `./gradlew :app:installDebug` + `lezi-emu shot`
   - Compose Previews under `ComponentPreviews.kt` (load / empty / normal / recording / error)
6. Prefer two commits in one PR: `design prototype` then `compose implementation`.

## Long-term generator (optional)

```
design/tokens.json
        ├── prototype/tokens.css
        └── designsystem/GeneratedTokens.kt
```

Colors, radii, type scale, and spacing can be generated; navigation, persistence, timers, permissions, and Android back stack stay hand-written.

## Shared Compose kit

| Component | Role |
|-----------|------|
| `LeziCard` | Surface card, soft border, press scale |
| `SummaryMetric` | Today glance cell |
| `TimelineLane` / `TimelineRailCard` | 24h three-lane rail |
| `RecordRow` | Timeline cell |
| `QuickRecordButton` | OD quick grid tile |
| `OneHandQuickDock` | Fixed handed dock shared by both templates |
| `StateContainer` | empty / loading / error / recording / success |
| `LeziPrimaryButton` / `LeziSecondaryButton` | ≥48dp CTAs |
