# Test redundancy cleanup — spec (policy lock)

**Status:** inventory complete; **awaiting approval of Wave 1 delete/refactor list** before any production or test deletion.  
**HEAD pinned at inventory:** `b64c74aa` (re-check `git status` before edit).  
**Scope:** Android/Kotlin only (`app`, `core`, `domain`, `designsystem`, `feature/*`, `sync`). **Out:** `tools/lezi-sync` Rust tests (另票).

## Goals

Audit and simplify redundant tests **without weakening PRD/spec behavior contracts**. Inventory by **observable contract + failure signal**, not filename or line count.

## Locked decisions (grill 2026-08-05)

| ID | Decision |
|----|----------|
| Q1 | Full-repo behavior-contract inventory + batched cleanup (**C**) |
| Q2 | Kitchen-sink files: **classify only**; no split/delete of assertions this round (**A**) |
| Q3 | Source-string Structure/Contract: **default delete**; rewrite first only if unique regression + no other coverage (**B**) |
| Q4 | Cross-layer: **prefer one layer**, JVM first (**B**) |
| Q5 | Keep Device when: system/a11y/real resources **or** documented UI regression history (**C**) |
| Q6 | Rust out of scope (**A**) |
| Q7 | Intentional scanners (incl. BareMaterial) treated under Q3=B (**default delete**) |
| Q8 | Dirty WIP: drop test-only `testTag` if possible; public seam / smaller fixture for empty-state; keep Composer `Button` import or change harness (**C**) |
| Q9 | Short inventory gate → execute: scaffolding → Structure deletes → compressible device → defer rest (**B→A**) |

## Hard constraints

- Do not weaken PRD/ADR/CONTEXT behavior to cut test count.
- Do not stage/commit unrelated WIP (`.scratch` greenfield/pngs, `gradle.properties`, etc.) unless this tracker’s owned files.
- Kitchen sinks (`RealSyncPortTest`, `CareLogTest`, …): **defer** (inventory tag only).
- Deletion requires an explicit **retained coverage** note.
- AGENTS/tech ban product-less StructureTests (path/line-count/source-layout as contract).

## Disposition vocabulary

| Tag | Meaning |
|-----|---------|
| **KEEP** | Unique contract / justified layer; leave as-is this round |
| **KEEP-PARTIAL** | Keep behavior/token parts; delete source-string parts |
| **DELETE** | High-confidence removable under locked policy (needs approval) |
| **REWRITE** | Unique regression story; replace with seam behavior test then delete source scan |
| **COMPRESS** | Cross-layer: keep JVM (or thinner device); drop redundant half |
| **PROCESS-FIX** | Process scaffolding (testTag, missing import); not product behavior |
| **DEFER** | Kitchen-sink / low-confidence / needs deeper method-level audit later |

## Execution waves (after approval)

1. **Wave 1 — process + Structure high-confidence DELETE/PROCESS-FIX** (this inventory §3–4).  
2. **Wave 2 — cross-layer COMPRESS** (device without a11y/system/regression story).  
3. **Wave 3 — remaining Structure REWRITE candidates** (only if coverage gap proven).  
4. **Out of round — DEFER** kitchen sinks + Rust.

## Non-goals this round

- Splitting `RealSyncPortTest` / `CareLogTest` / `ReplicaSyncEngineTest` / `HttpSyncBackendTest`.
- 1:1 greenfield test migration.
- NAS CD (pure test cleanup).
- Pixel screenshot CI.
