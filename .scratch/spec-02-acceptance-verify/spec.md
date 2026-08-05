# Spec 02 follow-on · Dual-scene acceptance verification (close evidence gaps)

**Status:** ready-for-agent  
**Parent inventory:** [../spec-02-dual-scene-review/uiux-diff-spec.md](../spec-02-dual-scene-review/uiux-diff-spec.md)  
**Parent campaign:** [../spec-02-dual-scene-review/spec.md](../spec-02-dual-scene-review/spec.md)  
**1.0.0 freeze (closed):** [../spec-02-apk-1.0-freeze/spec.md](../spec-02-apk-1.0-freeze/spec.md)  
**Engineering pointer:** `greenfield/docs/specs/03-uiux-diff-inventory.md`  
**Evidence:** `greenfield/docs/specs/baselines/scenes/`

---

## Problem Statement

昨晚的多场景双装验收**扫过了 P0 场景并写出了差异 inventory**，但验收**没有验满**：部分场景 legacy 未打开目标面、滑动/布局/计时 idle 静帧停在错误状态、P1 未跑、§2.3 盲测延期。用户无法依赖「票 done = 双端证据干净」。需要把**未验满项**收成可执行 tracker，补强 harness 与证据，再决定 polish 优先级——**不**重开 1.0.0 S-freeze。

## Solution

跑一轮 **acceptance verification**：以 inventory 的 partial 场景为入口，修 dual-capture harness（中途截帧、关掉 legacy 下次喂养弹层、seed 失败 fail-closed），重抓 08/10/11–13/15/17 等薄弱证据，更新矩阵为 **verified / partial / blocked**，并对用户可见 debug 文案（睡眠 epoch、汇总 D0 dump、PENDING）做 **P0 polish**（仍走 confirm-before-write）。P1 汇总/成长/模板与 §2.3 盲测单独票，默认不挡本批「证据干净」。

## User Stories

1. As a product owner, I want every P0 scene to have honest dual-install evidence, so that “done” means both APKs were actually reviewed on the target surface.
2. As a caregiver, I want sleep and summary screens free of epoch and debug dumps, so that the app does not look unfinished during daily use.
3. As a caregiver, I want plan/pending copy in clear Chinese, so that I am not shown English status tokens like PENDING.
4. As an agent, I want a harness that fails closed when seed or legacy open fails, so that empty or wrong stills are not marked pass.
5. As an agent, I want mid-gesture stills for swipe half-reveal, full edit, and full delete, so that motion and interaction can be scored without scrubbing only.
6. As an agent, I want legacy “安排下次喂养” dismissed before capture, so that seeded log-home and swipe scenes show the timeline.
7. As an agent, I want sleep Composer open on both GF and legacy, so that structure and confirm paths can be compared.
8. As an agent, I want breast Composer vs timer entry verified on both packages, so that entry residual is evidence-based not guessed.
9. As an agent, I want timer idle and running stills that actually show the timer surface on GF, so that package-switch pollution is not scored as product UI.
10. As an agent, I want layout editor enter/drag/exit stills, so that E6 and A3 are not only claimed from older baselines.
11. As a reviewer, I want matrix rows marked verified vs partial, so that inventory honesty survives ticket status flips.
12. As a reviewer, I want confirm-before-write re-checked on formula seed and delete cancel paths, so that automation never skips confirm.
13. As a developer, I want P0 polish limited to user-visible debug and product copy, so that chrome non-compare items stay out of this batch.
14. As a developer, I want top-bar, E3 icons, and E4 empty chips left waived, so that 1.0.0 is not reopened.
15. As a product owner, I want an explicit §2.3 retry recommendation after verification, so that blind chrome work is scheduled only when residuals shrink.
16. As a product owner, I want P1 summary/growth/template scenes optional, so that P0 evidence cleanup is not blocked by chart data seeding.
17. As a QA agent, I want side-by-side or dual stills under stable scene ids, so that re-runs do not invent new folders.
18. As a QA agent, I want large mp4s review-local and gitignored, so that the repo stays usable.
19. As a caregiver, I want cancel/back on Composer to write nothing, so that open-entry safety matches domain rules.
20. As a caregiver, I want formula defaults and confirm CTA already aligned, so that re-verification does not regress 120ml / chips / 确认记录.
21. As an implementer, I want one highest seam (dual-scene harness + domain confirm), so that verification does not grow new frameworks.
22. As an implementer, I want residual IDs (R-*) stable across tickets, so that polish tickets can reference inventory without renumbering.
23. As a release manager, I want S-freeze left PASS, so that 1.0.0 shell freeze remains the ship gate.
24. As a future agent, I want comments on each issue when evidence is re-captured, so that the next handoff does not re-run blind.
25. As a designer, I want motion language still judged by class not ms CI, so that emulator jitter is not fail.
26. As a developer, I want unit tests for product-copy pure helpers where applicable, so that epoch formatting cannot regress silently.
27. As a family caregiver on LAN, I want no production NAS CD in this work, so that acceptance stays on emulator dual-install.
28. As an agent, I want missing legacy package to fail closed, so that GF-only runs are not labeled dual-pass.
29. As a product owner, I want a short rollup after verification, so that remaining polish is ordered by severity.
30. As a contributor, I want tracker docs under `.scratch/` only, so that GitHub Issues are not required.

## Implementation Decisions

### Seams (highest, fewest)

1. **Dual-scene capture harness** — single entry for re-runnable dual evidence per scene-id (seed profiles, fail-closed legacy, mid-path stills). Prefer evolving this harness over new frameworks.
2. **Care write path** — all seeds and delete/confirm stay on existing Care confirm-before-write; automation must tap real confirm labels (确认记录 / 确认睡下 / …).
3. **Product copy surface** — sleep clock labels, summary empty state, plan pending labels: UI-only polish without new domain write APIs.

*(Checked with product intent from prior session: acceptance-first; UI only for user-visible debug / already-agreed align; no S-freeze reopen.)*

### Other decisions

- Tracker slug: `spec-02-acceptance-verify` (does not replace dual-scene-review inventory; **continues** it).
- Matrix truth: inventory scores that were partial remain partial until re-capture proves verified.
- Weak scenes to re-verify first: composer-sleep, composer-breast-vs-timer, swipe-half-reveal, swipe-full-edit, swipe-full-delete, timer-enter-idle, layout-editor-drag; also re-check log-home-seeded-row after next-feed dismiss.
- P0 polish in-scope: R-sleep-debug, R-summary-debug, R-plan-ui (Chinese, no PENDING).
- Out of this batch unless explicit: full §2.3 blind, E3 icon pack, E4 five chips, top-bar blue language, P1 chart scenes unless harness already supports them cleanly.
- Evidence naming unchanged: `scenes/<scene-id>/{gf,legacy,compare}.*`.

## Testing Decisions

- Good tests assert **external behavior**: correct surface visible on still/video, confirm required before write, cancel no-write, no raw epoch/debug dump in caregiver-facing copy.
- Prefer dual-install stills + short mp4 over Compose pixel tests.
- Unit tests for pure format helpers (sleep start label, pending label) where logic is pure; prior art: `UiuxP0LogComposerTimerTest`, `UiuxVisualParityTest`.
- Re-run harness per scene-id; fail if legacy missing or seed confirm misses.
- Do not treat “ticket Status done” from the prior batch as verified without new stills.

## Out of Scope

- Spec 01 data migration  
- Production NAS CD / push-and-deploy  
- Pixel CI / image hash gates  
- Reopening S-freeze as fail for waived chrome  
- Full non-implementer §2.3 blind unless residuals shrink enough to recommend it  

## Further Notes

- Prior honest audit: not all problems fully verified; this spec exists **because** of that.
- Commits already landed: dual harness + formula align (`feat(gf): dual-scene…`); inventory docs (`docs(gf): publish P0…`).
- Domain language: 护理记录, 写前确认, Composer, 底坞, 三日轴 — see `CONTEXT.md`.

## Comments

- 2026-08-06: Published via to-spec from acceptance-gap discussion; continue dual-install verification on emulator.
