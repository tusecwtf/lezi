# UI drawing polish — designsystem hardening & next-pass polish

Status: complete

## Problem Statement

After the 2026-08-04 UI audit remediation and chrome componentization, the app’s
main paths look coherent on light×warm smoke shots, but drawing quality is still
uneven under the surface:

1. **Design-system chrome is only half-closed.** Product modules cannot call raw
   Material buttons/fields (contract-tested), yet several designsystem surfaces
   still use bare Material controls on daily paths (clock dial, nursing confirm,
   next-feed plan, photo preview close, member-login QR confirm). Users still
   see mixed button languages on high-frequency flows.
2. **Spacing and density drift.** Tokens include off-grid values and many leaf
   `dp` literals remain on the timeline, summary charts, and chrome. Warm and
   journal templates do not yet own distinct, named density tables for the same
   structural pads.
3. **Lists and motion are incomplete.** Growth history and family member/device
   lists still use non-lazy columns (no recycling, no item animation). Animation
   durations are scattered; system “reduce motion” is not respected.
4. **Compose stability is opportunistic.** Hot timeline/log/summary draw inputs
   are not systematically treated as stable; scroll-derived UI is not gated with
   derived state where it would limit recomposition.
5. **Local photo loading is correct but uncached.** Decode is bounded and
   cancel-safe; without a bounded LRU, repeated thumbnail paths may re-decode
   under scroll or re-open.
6. **Acceptance evidence is thin.** Smoke screenshots are mostly light×warm;
   empty states still read like spinners; widget and export surfaces lag the new
   chrome language.

Caregivers should experience one visual language, smooth day-chart and list
interaction, honest empty/loading states, and no chrome regressions—across warm
and journal templates and light/dark—without changing family sync, Room schema,
or write semantics.

## Solution

A single program tracker that finishes the drawing stack:

- Close designsystem-internal chrome so every user-visible control goes through
  Lezi wrappers; extend the audit path contract with an explicit wrapper
  whitelist.
- Normalize spacing onto a 4/8 grid with **template-specific density**: warm more
  open, journal more compact; migrate high-frequency draw surfaces first.
- Convert growth history and members/devices lists to lazy lists with stable keys
  and item animation.
- Introduce shared motion tokens; route main transitions through them; honor
  system reduce-motion.
- Stabilize hot-path UI models and use derived state for scroll-derived chrome.
- Implement a **conservative bounded photo LRU** (entry cap + pixel budget,
  fullscreen limited, recycle on eviction) under the existing local photo
  contract.
- Light visual polish for empty states, menu icon weight, widget, and export;
  require a warm/journal × light/dark screenshot matrix for acceptance.
- Allow UI plus **presentation-format** helpers (dates, weight, duration display)
  when consistency needs them; freeze wire, Room schema, CareLog write paths,
  and lezi-sync.

## User Stories

1. As a caregiver, I want every confirm/cancel control to look like the same Lezi
   buttons, so that high-frequency sheets do not feel like a different app.
2. As a caregiver using the clock dial, I want dial actions to match the rest of
   the composer chrome, so that adjusting record time feels native.
3. As a caregiver finishing a nursing session, I want order chips and fields to
   use the same control language as the quick-record sheet, so that rules feel
   consistent.
4. As a caregiver acknowledging next-feed plan prompts, I want dialog actions to
   match other alerts, so that I trust the action hierarchy.
5. As a caregiver previewing record photos, I want close/navigation actions to
   match app chrome and remain readable on light photos, so that I can dismiss
   safely.
6. As an admin showing a member-login QR, I want confirm/manual-join fields to
   match trusted onboarding chrome, so that join UX does not fork visually.
7. As a caregiver on the warm template, I want slightly roomier card and header
   padding, so that the cream card language feels calm.
8. As a caregiver on the journal template, I want tighter structural padding, so
   that the compact logbook density stays true.
9. As a caregiver panning the three-day timeline, I want hit-testing and drawing
   to stay aligned and smooth, so that type filters and marks remain trustworthy.
10. As a caregiver viewing summary charts, I want bar/line marks sized in density-
    independent units, so that charts look correct on high-DPI phones.
11. As a caregiver with a long growth history, I want the list to scroll without
    jank and animate item changes, so that editing measurements feels polished.
12. As a family admin with many members and devices, I want the members list to
    recycle and animate, so that the account surface stays responsive.
13. As a caregiver switching tabs or layout-edit mode, I want transitions to use
    a consistent motion language, so that the app feels intentional rather than
    patched.
14. As a caregiver who enabled system “reduce motion”, I want non-essential
    transitions shortened or disabled, so that the app respects accessibility
    settings.
15. As a caregiver scrolling the record timeline while swipe-rows are open, I
    want derived scroll state to collapse rows without recomposing the whole
    screen tree more than needed, so that scrolling stays fluid.
16. As a caregiver reopening the same photo thumbnails, I want bounded cache hits
    when safe, so that composer and audit thumbs appear faster without unbounded
    memory growth.
17. As a caregiver opening a fullscreen photo, I want at most a tiny fullscreen
    cache and strict eviction, so that large bitmaps do not push the process into
    OOM.
18. As a caregiver on an empty day, I want an empty state that does not look like
    a loading spinner, so that I know I can add the first record.
19. As a caregiver on an empty summary range, I want empty chart cards to match
    the same empty language, so that empty ≠ calculating.
20. As a caregiver using the menu, I want icon weights and row chrome consistent,
    so that settings feel finished.
21. As a caregiver using the home widget, I want spacing and type to align with
    in-app tokens as far as widget constraints allow, so that the widget feels
    on-brand.
22. As a caregiver exporting data, I want primary/secondary actions and preview
    chrome to match Lezi buttons and motion, so that export is not a leftover
    Material page.
23. As a caregiver in dark mode on warm, I want semantic colors and bottom-nav
    indicators to stay readable (no regression of prior contrast fixes), so that
    night feeds remain usable.
24. As a caregiver in dark mode on journal, I want the same contrast discipline,
    so that template switch does not reintroduce dim success/warning marks.
25. As a caregiver switching babies, I want theme accent headers and readable
    status-bar icons to keep working after spacing changes, so that multi-baby
    chrome stays safe.
26. As a caregiver filtering the day chart, I want legend and lane highlight
    motion to use shared motion tokens, so that selection feedback feels unified.
27. As a caregiver using search, I want list item animation and loading/empty
    crossfades to stay consistent with the record timeline patterns, so that
    secondary surfaces match primary ones.
28. As a developer agent, I want a single contract test seam that bans bare
    Material outside wrapper implementations, so that chrome cannot regress.
29. As a developer agent, I want presentation format helpers for date/weight/
    duration when two screens disagree, so that caregivers never see two formats
    for the same baby on one journey.
30. As a release reviewer, I want a warm/journal × light/dark screenshot matrix
    under scratch smoke, so that acceptance is evidence-based.
31. As a caregiver with TalkBack, I want icon-only controls on migrated surfaces
    to keep content descriptions and test tags, so that accessibility does not
    regress while chrome moves.
32. As a caregiver on the record dock, I want touch targets to remain at least
    the shared touch minimum after density token changes, so that one-handed use
    stays reliable.
33. As a caregiver dragging layout-edit tiles, I want existing layout-edit
    behavior preserved while surrounding chrome tokens move, so that arrangement
    work is not destabilized.
34. As a family member on a slow device, I want photo cache eviction to free
    bitmaps promptly, so that memory pressure from previews is bounded.
35. As a product owner, I want SummaryMetric (warm cards) and RecordSummaryStrip
    (journal strip) to remain separate languages, so that template identity is
    not collapsed.
36. As a developer, I want local photo cache identity to include path, target,
    source size, and orientation, so that EXIF/size changes cannot serve a stale
    bitmap.
37. As a caregiver, I want chart empty vs calculating states to remain distinct
    after motion work, so that I never think data is missing when it is still
    loading.
38. As a developer agent, I want this program not to touch sync wire, Room
    schema, or NAS CD, so that UI polish cannot ship a protocol regression.

## Implementation Decisions

### Tracker shape (grilled)

- Single program slug: `ui-drawing-polish-20260804`.
- Phased tickets 01…N with explicit dependencies; agents run tickets in order
  unless a ticket states it is parallel-safe.
- Status line uses triage vocabulary (`ready-for-agent`, etc.).

### Release boundary (grilled)

- **In scope:** designsystem, core.ui, feature UI, app shell, widget UI,
  presentation-format helpers (date/weight/duration display consistency).
- **In scope docs:** `docs/prd/ui.md` (tokens/motion/empty language),
  `docs/prd/local-photo-loading.md` (cache section), optionally a short note in
  `docs/reviews` only if humans want a close-out; product authority remains PRD.
- **Out of wire/schema:** no Room `user_version` change, no lezi-sync protocol,
  no CareLog write/sync semantics, no NAS CD, no TLS, no bootstrap secrets.
- Domain modules may gain **display-only** helpers if that is the existing seam
  for formats; no new write APIs.

### Phase A — designsystem chrome closure (grilled A)

- Migrate all user-visible designsystem call sites off bare Material
  `Button` / `TextButton` / `OutlinedButton` / `FilterChip` / `OutlinedTextField`
  / `Switch` / `IconButton` / `DatePickerDialog` / raw `AlertDialog` to the
  existing Lezi wrappers (or new thin wrappers if a gap is real).
- Known residual surfaces to clear: clock dial chrome, nursing confirm fields,
  next-feed plan dialogs, photo preview actions, member-login QR confirm surface.
- **Whitelist:** only the wrapper implementation bodies may call Material
  primitives. Product modules remain banned (already). Contract extends to
  designsystem sources with whitelist paths/symbols.
- Preserve busy/leading-icon behaviors already on Lezi primary/secondary/text
  buttons.
- Do not merge warm SummaryMetric and journal RecordSummaryStrip.

### Phase B — spacing density tables (grilled A + template split)

- Structural pads normalize onto the 4/8 grid.
- **Warm = more open; journal = more compact** for the same structural roles
  (card pad, top-bar horizontal inset, timeline section gaps, panel content
  padding). Prefer a small named density table keyed by visual style rather than
  scattering `if (journal)` literals.
- High-frequency first: top bar, timeline rail, summary chart cards, confirm
  chrome, quick dock structural gaps.
- Touch minimum remains the shared touch token (48dp class); density changes
  must not shrink interactive targets below it.
- Alpha hairlines continue to use the shared hairline helper; no new ad-hoc
  alpha ladders without named tokens.

### Phase C — lazy lists (grilled A)

- Growth history list → lazy list with stable keys; item animation when the
  foundation API used elsewhere applies.
- Family members and devices lists → lazy list with stable keys and item
  animation where rows insert/remove.
- Preserve overflow menus, destructive styling, test tags, and semantics from
  the prior members-row remediation.
- Do not lazy-wrap non-scrolling tiny columns that never leave the viewport if
  doing so only adds complexity without benefit—but growth history and members
  pages are in scope as agreed.

### Phase D — motion + hot-path stability (grilled A)

- Introduce shared motion tokens (fast / base / emphasized) for durations used by
  main transitions (nav host fades, layout-edit, wizard steps, crossfades,
  color feedback on primary timer controls, range tabs).
- Replace scattered magic millisecond values on those paths with token
  references.
- **Reduce motion:** when the system accessibility reduce-motion setting is on,
  non-essential transitions shorten or become instant; essential state changes
  remain understandable.
- Hot-path stability: mark or structure timeline lane inputs, log row models, and
  summary chart draw inputs so Compose can skip avoidable recomposition
  (immutable/stable UI models on those paths). Use derived state for
  scroll-derived chrome (e.g. swipe collapse, scroll-dependent affordances).
- No app-wide annotation storm; no Macrobenchmark CI gate in this program.

### Phase E — bounded photo LRU (grilled C + conservative A)

- Implement cache under the existing local photo loader seam only.
- Cache key identity: path, target (thumbnail vs fullscreen), source width/height,
  orientation (already named identity in the photo-loading contract).
- **Conservative bounds:** small thumbnail entry cap (order of ~24) **and** a
  total decoded pixel/byte budget (order of ~16–32 MiB); fullscreen entries
  capped at 1–2; eviction recycles bitmaps.
- Cancel still drops in-flight work; a cancelled decode must not populate cache
  with a recycled or wrong-key bitmap.
- Update the local photo loading PRD section: cache is now allowed under these
  bounds (replace “no global cache” with the bounded policy).
- No disk cache; memory only.

### Phase F — empty states, weak surfaces, screenshot matrix (grilled A)

- Empty states: replace spinner-like empty marks with an explicit empty language
  distinct from loading/calculating (record day empty, summary range empty at
  minimum).
- Menu row icon weight consistency light pass.
- Widget + export: align spacing/type/buttons with Lezi chrome within platform
  widget limits; export keeps primary/secondary hierarchy and preview motion
  already partially done—close remaining gaps.
- **Acceptance matrix required:** screenshots for warm×light, warm×dark,
  journal×light, journal×dark covering at least: record (empty or with data),
  summary, growth, family/account, menu; store under
  `.scratch/ui-drawing-polish-20260804/smoke/` (or extend `ui-smoke` with a
  dated subfolder linked from this tracker).
- No full marketing redesign; no new illustration system beyond simple empty
  marks consistent with placeholder mark language.

### Canvas / chart drawing (included under B/D)

- Chart and timeline mark sizes continue to use density conversion (no raw px
  radii).
- Prefer remembered geometry for paths where draw is hot; keep hit-testing and
  draw on the same axis helpers (three-day rail contract unchanged).
- Dense month bars: avoid unreadable micro labels (hide or show on selection)
  without changing aggregation math.

### Presentation format layer (grilled B)

- When the same baby meta (birthday, weight, duration) still disagrees across
  settings/family/log surfaces, converge through existing presentation helpers
  rather than one-off string builds in feature UI.
- Do not invent a second formatting library.

### Test seams (grilled A)

1. **Primary gate:** extend `UiAuditPathContractTest` (designsystem chrome
   whitelist, motion token presence, density table presence, photo cache policy
   guards as source contracts where appropriate).
2. **JVM behavior tests:** wrapper line-mode style tests; photo cache
   eviction/identity; pure chart layout math; reduce-motion selection if pure.
3. **Device/screenshot acceptance:** matrix above; no Macrobenchmark hard gate.

### Architecture / ADR

- No new ADR required if behavior stays within existing UI PRD and photo-loading
  PRD; update those docs instead.
- If cache policy is considered a product contract change, document it in
  `docs/prd/local-photo-loading.md` (authoritative for local photos).
- Respect glossary: care records, day chart, quick-record dock, warm/journal
  templates, trusted endpoint—do not rename.

### Explicit non-merges

- SummaryMetric vs RecordSummaryStrip remain two languages.
- Three-day timeline axis / DST math is not redesigned—only density, motion, and
  draw hygiene.
- Journal vs warm are not collapsed into one visual template.

## Testing Decisions

### What good tests look like

- Assert **external contracts and user-visible policy**, not private Compose
  node trees of every screen.
- Prefer source-level audit contracts for “no bare Material outside whitelist”
  (prior art: `UiAuditPathContractTest`).
- Prefer pure JVM tests for cache identity/eviction and chart slot math.
- Device tests only where gesture/accessibility already has priors (layout-edit,
  members overflow)—do not require a full connected suite for every spacing
  tweak.
- Screenshot matrix is **human acceptance evidence**, not a flaky CI golden
  unless the repo already has screenshot CI (it does not as a hard gate today).

### Modules under test

- designsystem (contract + form/photo/motion-related unit tests)
- core.ui (presentation helpers if touched)
- feature/log, feature/summary, feature/growth, feature/family, feature/export,
  feature/settings, feature/widget, app shell (compile + targeted tests)
- No lezi-sync Rust tests required for this program

### Prior art

- `UiAuditPathContractTest` — chrome and path regression guards
- `LeziTextFieldLineModeTest` — wrapper policy unit tests
- Photo preview / local photo device smoke patterns
- Layout-edit and members androidTest patterns for semantics/tags
- Summary bar slot layout unit tests

### Program-level validation

- `./gradlew :app:assembleDebug test lintDebug` green
- Contract tests green with expanded whitelist rules
- Emulator or device: screenshot matrix captured and linked from tracker
- No secret/signing/dist artifacts committed

## Out of Scope

- lezi-sync protocol, NAS CD, TLS identity, bootstrap secrets
- Room schema / `user_version` / migrations
- CareLog write paths, sync reconcile/publish planning, outbox
- Macrobenchmark / Compose Compiler metrics CI gates
- Full visual redesign, new brand illustration pack, marketing site
- Merging warm and journal into one template
- Merging SummaryMetric and RecordSummaryStrip
- Redesigning three-day axis domain math or DST rules
- Disk-backed photo cache or network image stack
- i18n extraction of Chinese copy (unless a touched string is already wrong)
- Widget capabilities beyond layout/token alignment (no new timeline widget)

## Further Notes

### Grilled decisions (2026-08-04)

| # | Decision | Choice |
|---|----------|--------|
| 1 | Tracker shape | Single program + phased tickets |
| 2 | designsystem bare Material | Full migrate + contract whitelist |
| 3 | Spacing grid | Normalize 4/8 even if micro visual change |
| 4 | Growth/members lists | Convert to Lazy in this program |
| 5 | Motion/stability | Motion tokens + hot-path stability only |
| 6 | Photo cache | Implement bounded LRU |
| 7 | Acceptance | Required screenshot matrix + empty/weak-surface polish |
| 8 | Layer boundary | UI + presentation-format helpers |
| 9 | Density direction | Warm open / journal compact (template tables) |
| 10 | LRU size | Conservative entries + pixel budget |
| 11 | Test seams | Contract + JVM + screenshots; honor reduce-motion |

### Relationship to prior work

- Builds on 2026-08-04 UI audit close-out and follow-on chrome componentization
  (`Lezi*` buttons/fields, path contract, contrast/animation P0–P3).
- Does **not** reopen hang-fidelity or sync-reconcile trackers except where UI
  chrome touches shared dialogs already migrated.
- Prior design-system sweep ticket in codebase-audit remediation is historical;
  this program is the next dedicated drawing/polish pass.

### Ticket order (to-tickets, serial spine)

See `ISSUES.md`. Vertical slices, default one-at-a-time:

01 expand motion+density tokens → 02 photo LRU (after 01) →
03 dial+nursing chrome (**hard-blocked by 01**) → 04 next-feed/photo/QR chrome →
05 contract ban bare Material → 06 record density+empty → 07 summary →
08 growth lazy → 09 family lazy → 10 shell motion+reduce-motion →
11 menu/widget/export → 12 presentation format if needed → 13 screenshot close-out.

### Seams check (user-confirmed via decision 11)

Primary seam remains the UI audit path contract; secondary JVM policies; tertiary
screenshot acceptance. No fourth performance harness in this program.
