# Spec 02 · APK 1.0.0 UI/UX freeze (S-freeze)

**Status:** ready-for-agent  
**Product version pin:** Greenfield `1.0.0` (`versionCode` 100) · `com.lezi.babylog.gf`  
**Engineering companion (not the tracker):** `greenfield/docs/specs/02-apk-visual-parity.md` §2.4 / §10  
**Evidence:** `greenfield/docs/specs/baselines/` · agent review in `.../baselines/review-notes.md`  
**Tracker convention:** local Markdown under `.scratch/` (see `docs/agents/issue-tracker.md`)

---

## Problem Statement

Families using 0.3.x expect the greenfield rewrite to feel like 乐记 on the main logging path—not a different product shell. After V0–V3 UI work, implementers still need a **clear freeze gate** for shipping **1.0.0**: what must match 0.3.x interaction structure, and what chrome differences are accepted so the product line can stop open-ended visual polish.

Without a single frozen scene and explicit residuals, agents keep treating “looks like 0.3.x” as unbounded (full blind chrome parity), blocking a clean 1.0.0 design lock even when confirm-before-write and dock/Composer identity already work.

## Solution

Freeze Greenfield **1.0.0 shell quality** on **one dual-install scene (S-freeze)**:

1. Empty-data **记录** home (warm template) shows five-tab IA + dock four slots + 更多.  
2. Opening **配方奶** Composer shows type identity, milk amount control, time control, primary confirm CTA, and cancel/close without writing.  
3. **写前确认** remains mandatory (护理记录 only after confirm).

Full “cannot tell packages apart” blind review (§2.3 of the engineering Spec 02) is **deferred post-1.0.0**. Residuals (top bar chrome, day-summary chips, icon pack, dense formula chips) are **accepted** for 1.0.0.

Agents re-verify S-freeze with existing baseline capture (and optional motion clips); they do **not** invent pixel CI or NAS work for this freeze.

## User Stories

1. As a caregiver, I want the 记录 tab and bottom dock to feel like 乐记’s logging home, so that I can start a care fact without relearning navigation.  
2. As a caregiver, I want four quick dock slots plus 更多, so that common types stay one tap away.  
3. As a caregiver, I want an empty day to tell me to use the dock, so that I am not stuck on a blank screen.  
4. As a caregiver, I want 配方奶 to open a dedicated Composer, so that bottle feeds are logged as the correct 护理记录 type.  
5. As a caregiver, I want a clear milk amount control in Composer, so that the feed volume is explicit before confirm.  
6. As a caregiver, I want a clear record-time control (dial or equivalent path), so that I can correct when the feed happened.  
7. As a caregiver, I want a primary confirm/save action, so that I deliberately create a 护理记录.  
8. As a caregiver, I want cancel/close without writing, so that drafts do not become facts.  
9. As a caregiver, I want confirm-before-write on every new log, so that accidental taps do not pollute the timeline.  
10. As a caregiver, I want five top-level tabs (记录 / 汇总 / 成长 / 账户 / 菜单), so that the app map matches 0.3.x habits.  
11. As a caregiver using warm template, I want a calm open home, so that daytime logging feels light.  
12. As a reviewer (agent), I want one named freeze scene, so that 1.0.0 is pass/fail without subjective full-app chrome debates.  
13. As a reviewer, I want dual-install screenshots as evidence, so that structure parity is auditable offline.  
14. As a product owner, I want residuals listed explicitly, so that post-1.0.0 polish tickets do not reopen the freeze gate.  
15. As a product owner, I want full blind chrome parity deferred, so that 1.0.0 can ship structure without pretending packages are indistinguishable.  
16. As a developer, I want writes only through Care write seams with confirm, so that UI freeze work cannot bypass 护理记录 rules.  
17. As a developer, I want existing Uiux unit gates to stay green, so that motion/token regressions fail CI without pixel tests.  
18. As a developer, I want optional motion screenrecords, so that interaction language can be reviewed without becoming a merge gate.  
19. As a caregiver on journal or dark (non-freeze), I still want the same confirm path later, so that freeze does not imply those templates are abandoned—only out of the 1.0.0 gate.  
20. As a family still on 0.3.x data, I want this freeze **not** to claim data migration is done, so that I do not assume Spec 01 is finished.  
21. As a release owner, I want `1.0.0` / versionCode 100 pinned on the freeze record, so that the accepted shell is unambiguous.  
22. As an agent picking up polish later, I want §2.3 residual list, so that I know top bar / E3 icons / E4 chips / dense chips are follow-ons.  
23. As a caregiver, I want left-swipe edit / right-swipe delete behavior to remain available after freeze (evidence exists), so that timeline edit does not regress—even if not the freeze scene.  
24. As a caregiver, I want 计时 and 布局 to remain reachable from the log shell, so that freeze does not remove those surfaces.  
25. As a QA agent, I want S-freeze reproducible via the baseline capture script, so that re-runs do not depend on tribal knowledge.  
26. As a maintainer, I want tracker status `ready-for-agent` only when the freeze criteria are machine-checkable, so that AFK agents do not stall.  
27. As a caregiver, I want relative time and type glyph circles to remain readable on seeded rows, so that post-log review still works (supporting evidence, not freeze hard gate).  
28. As a product owner, I want “1.0.0 shippable shell ≠ 无感替换 complete” stated in the tracker, so that cutover messaging stays honest.  
29. As a developer, I want no requirement to copy 0.3.x trademark assets, so that freeze stays legal and maintainable.  
30. As a developer, I want no production NAS CD in this workstream, so that UI freeze cannot trigger family outage.  
31. As a caregiver with reduce-motion, I want non-essential motion to collapse safely (token path), so that accessibility is not traded for freeze.  
32. As a dual-install reviewer, I want GF and legacy both launchable on one emulator, so that S-freeze comparison is fair.  
33. As an agent, I want waived items documented as accepted residuals, so that failing blind chrome tests do not reopen 1.0.0 shell code.  
34. As a future non-implementer reviewer, I want optional §2.3 pass criteria still written down, so that true 无感 can be re-attempted later without redesigning the gate.  
35. As a caregiver logging 配方奶, I want amount steppers and time dial to coexist with a11y steppers, so that motor and visual paths both work.  
36. As a release engineer, I want assembleDebug and Uiux tests as regression floor, so that freeze is not docs-only.  
37. As a product owner, I want Spec 01 left parallel, so that data cutover remains a separate decision.  
38. As an agent writing polish issues, I want this spec to forbid expanding freeze scope into full feature rewrites.  
39. As a caregiver, I want menu/search/sync entry points to remain present on the log chrome where GF already has them, so that freeze does not strip utility chrome.  
40. As a maintainer, I want comments on this ticket to record any re-run of S-freeze with date and device serial.

## Implementation Decisions

1. **Freeze gate is S-freeze only** — dual-install empty 记录 home (warm) + 配方奶 Composer; not full-app visual equality.  
2. **Engineering long-form Spec 02** remains under `greenfield/docs/specs/` as the UI inventory (E/T/A lists, phases). **This tracker file** is the issue-tracker publication for freeze/status/agent pickup.  
3. **Accepted residuals for 1.0.0:** top-bar language (blue bar + day age + five summary chips vs cream + three-day axis), E3 type icons (Material vs glyph circles), E4 empty day-summary strategy, denser legacy formula chips / optional prep fields, full §2.3 blind pass.  
4. **Required structure:** five tabs; dock 4+更多; Composer identity for 配方奶; amount + time + confirm + cancel; confirm-before-write.  
5. **GF element bar for freeze scene:** TimeDial on Composer path; full-width (or primary-path) confirm control; no new write API.  
6. **Writes** stay on existing Care / Family service seams only; UI freeze must not introduce direct DAO writes.  
7. **Version pin:** product line `1.0.0` / code 100 / application id greenfield package.  
8. **Capture tooling:** existing dual-install screenshot script is the static evidence path; optional motion prototype script is non-gating.  
9. **No pixel CI;** no mandatory non-implementer blind for 1.0.0; agent dual-install review is enough for freeze.  
10. **Post-1.0.0 polish** reopens only residual list / §2.3—not the S-freeze definition—unless product explicitly changes the freeze scene.  
11. **Out of product line:** production NAS deploy, Spec 01 migration tools, package-id takeover of 0.3.x.

## Testing Decisions

**What good tests look like:** assert user-visible structure and write semantics; do not assert pixel hashes, exact Material colors, or emulator-vs-device millisecond animation equality.

| Seam | Level | Assertion |
|------|-------|-----------|
| **S-freeze dual-install** | L4 agent / human | Structure checklist in §2.4 companion: IA + Composer identity + CTAs; evidence PNGs present |
| **Care write path** | L1/L2 existing | Confirm-before-write; cancel does not create 护理记录 |
| **Uiux / token contracts** | L1 existing | Motion tokens and UI parity unit tests stay green; assembleDebug succeeds |

Prior art: greenfield `Uiux*` unit tests; baseline capture script from Spec 02 work; golden G* paths remain orthogonal and must not go red from freeze docs.

**Not tests for this spec:** full dual-package blind chrome; legacy motion automation completeness; pixel diff CI.

## Out of Scope

- Spec 01 backend/database migration and offline-migrate tooling  
- Production NAS CD / family endpoint cutover  
- Pixel-level CI and forced screenshot hash gates  
- Full §2.3 multi-scene blind “cannot tell packages apart” as a 1.0.0 blocker  
- Copying trademark 小鸡 assets or 0.3.x package overlay install  
- Rewriting summary/growth/account stacks for chrome parity  
- Expanding freeze into a new multi-week UI rewrite without a new ticket

## Further Notes

### Agent freeze already executed (2026-08-05)

- Device: `emulator-5554` · API 35 · 1080×2400  
- S-freeze: **PASS** (structure + confirm path)  
- §2.3 full blind: **FAIL / deferred** (chrome distinguishable)  
- Details: `greenfield/docs/specs/baselines/review-notes.md` and engineering Spec 02 §10  

### Honest product line statement

**1.0.0 freezes a shippable logging shell, not complete 无感替换.**  
无感替换 still needs Spec 01 ∧ full Spec 02 §2.3 ∧ ops switch.

### Suggested agent follow-ups (separate issues if needed)

1. Re-run S-freeze capture after any log/Composer chrome change; refresh baselines.  
2. Optional post-1.0.0 polish issues for residual chrome (top bar, E3, E4, chips).  
3. Optional non-implementer §2.3 when product wants true blind pass.  
4. Do not fold motion mp4s into git by default (large); keep harness only if useful.

### Relationship to tickets 01–41

`greenfield-implementation` 01–41 are **done** for product line 1.0.0 function. This tracker item is **follow-on freeze documentation / gate**, not a reopening of those vertical slices.

## Comments

- 2026-08-05: Published via `to-spec` after agent dual-install S-freeze; corrected publication channel to `.scratch/` (engineering notes remain under `greenfield/docs/specs/`).

## Follow-on

Multi-scene dual video/UI review tracker: [../spec-02-dual-scene-review/](../spec-02-dual-scene-review/) (does not reopen S-freeze).
