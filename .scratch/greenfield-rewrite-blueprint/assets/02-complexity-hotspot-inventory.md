# 02 — Complexity & accidental-coupling inventory (read-only)

**Date:** 2026-08-05  
**Scope:** current monorepo tree (Android multi-module + `tools/lezi-sync`)  
**Nature:** facts the rewrite blueprint must confront — **not** fix proposals  
**Primary sources:** live source tree; `docs/prd/tech.md` §2; `docs/reviews/2026-07-29-redundancy-and-ui-review.md`; `docs/reviews/2026-08-01-full-codebase-audit.md`; `docs/reviews/ui-audit-2026-08-04.md`; ADRs under `docs/adr/`; `CONTEXT.md`  
**Method:** package/layout survey + end-of-file line probes on large surfaces (agent environment has no shell `find`/`wc`; counts are **exact EOF line numbers** from `read_file`, or **≥** lower bounds where still open). Spot-checks of dual-track symbols vs July review. No product code modified.

**Version context:** product line 0.3.7 / `tech.md` module map. Package locality remediation (2026-08 audit) already split several former gods (`CareLog`, `LogScreen`, lezi-sync `store.rs`); residual cost is concentrated in **sync engine + contract tests + NAS store façade + publish/reconcile paths**.

---

## 1. Executive read

| Rank | Hotspot | Layer | ~Lines | Why expensive for rewrite |
|-----:|---------|-------|-------:|---------------------------|
| 1 | `tools/lezi-sync/tests/api.rs` | tests / lezi-sync | **14 065** | Monolithic HTTP/API contract suite; map forbids 1:1 port as greenfield contract |
| 2 | `sync/.../RealSyncPortTest.kt` | tests / sync | **11 136** | Process-level sync rig + in-memory DAOs; shotgun surface of `RealSyncPort` |
| 3 | `domain/.../CareLogTest.kt` | tests / domain | **8 403** | Domain façade contract + large fake graph; couples calendar/timer/clear/photos |
| 4 | `sync/.../engine/ReplicaSyncEngine.kt` | sync | **~2 828** | Pull/apply/plan/media/LWW/creator-ack; highest client sync review cost |
| 5 | `sync/.../RealSyncPort.kt` | sync | **2 340** | Deep façade: lifecycle, force shell, clear, family session, app-update, replica |
| 6 | `tools/lezi-sync/src/model.rs` | lezi-sync | **2 317** | Wire payload allowlists + record/care-plan nested schema + embedded tests |
| 7 | `tools/lezi-sync/src/store/bundles.rs` | lezi-sync | **2 170** | Atomic stage/commit + LWW validate + fulfillment freeze |
| 8 | `sync/.../backend/HttpSyncBackend.kt` | sync | **~1 600+** | Full wire client: TLS/TOFU, streams, bounds, JSON maps |
| 9 | `app/.../MainActivity.kt` | app | **1 546** | Composition root: nav, force shell, composer/timer handoff, tabs |
| 10 | `domain/.../family/FamilyWizardController.kt` | domain | **~1 537** | Shared create/join/QR/reclaim state machine + Chinese UX feedback |

Secondary but rewrite-relevant: `FamilyScreen` (~1 368), `CareLog` façade (~1 111), `SettingsScreen` (~1 094), `Daos.kt` (~1 091), `CalendarScreen` (~999), `RecordMutationCoordinator` (~944), offline migrator (≥1 1xx), identity `session.rs` (~922), `FamilySessionCoordinator` (~838), layout edit stack (~760+ files), composer purpose fields (~788), `EphemeralPublishPipeline` (~708).

---

## 2. Hotspot table by layer

Line counts are **source lines including comments/blank** at survey time.

### 2.1 `app/`

| Path | ~Lines | Why expensive |
|------|-------:|---------------|
| `app/src/main/kotlin/com/lezi/babylog/MainActivity.kt` | 1 546 | Single composition root: onboarding gate, bottom nav, settings subroutes, calendar/export/timer routes, `TimerHandoffSeed` bridge, forced-update shell, system bars. Any greenfield shell rewrite collides here. |
| `app/.../AppHeader.kt` | ~754 | Global date header + sleep moon + header calendar dialog; magic dp / token drift noted in UI audit. |
| `app/.../LocalDataRecoveryScreen.kt` + upgrade steps | smaller | In-place upgrade / recovery path is product-critical but narrow; couples app + datastore + sync credentials. |

**Layer note:** only `app` may depend on all features (intentional). Handoff log↔timer is enforced via `core:model.TimerHandoffSeed` + app root — intentional anti-feature-edge (tech.md §2.1).

### 2.2 `feature/*`

| Path | Module | ~Lines | Why expensive |
|------|--------|-------:|---------------|
| `feature/log/.../layout/LayoutEditMode.kt` (+ session/drag/undo siblings) | log | 760 + cluster | Layout-edit is a product mini-app: drag, undo, dock, catalog, device snapshot write. |
| `feature/log/.../composer/QuickRecordPurposeFields.kt` | log | 788 | Per-type field UI; nursing/amount/choice strips; dual-entry risk with timer confirm. |
| `feature/log/.../composer/RecordComposer.kt` | log | 613 | Composer host: save/delete/convert/next-feed/handoff; configuration-rebuild history. |
| `feature/log/.../LogScreen.kt` | log | 570 | Slimmed shell (was ~2k); still orchestrates layout edit, dialogs, pull-to-refresh, timeline. |
| `feature/log/.../LogViewModel.kt` | log | ~462 | Timeline combine + publish chrome labels via sync helpers. |
| `feature/family/.../FamilyScreen.kt` | family | 1 368 | Account shell: wizard dialogs, members, delete family, QR, network settings. |
| `feature/settings/.../SettingsScreen.kt` | settings | 1 094 | Dense settings surface + clear-records + theme + custom items entry. |
| `feature/settings/.../calendar/CalendarScreen.kt` | settings | 999 | Month grid, conflict UI, care-plan actions into composer. |
| `feature/timer/.../TimerViewModel.kt` | timer | 692 | FGS lifecycle, completion SavedState, handoff accept, next-feed offer. |
| `feature/timer/.../NursingCompletionSheet.kt` + draft | timer | dual surface | Completes nursing outside composer; now uses designsystem confirm chrome (partially unified). |
| `feature/onboarding/...` | onboarding | thin | Second host of the same `FamilyWizardController` (intentional dual entry, dual UI chrome). |
| `feature/summary|growth|search|export|widget` | various | smaller | Lower structural cost; growth has own canvas; widget projection via domain. |

**feature↔feature Gradle edges:** none observed (`implementation(project(":feature:…"))` only from `app`). Shared work goes domain / core / designsystem / sync.

### 2.3 `domain/`

| Path | ~Lines | Why expensive |
|------|-------:|---------------|
| `domain/.../CareLog.kt` | ~1 111 | **Deep façade retained by policy** (tech.md §2.1): still public entry for many write/query paths + entity `toModel` mappers + Chinese validation messages. Subpackages hold real work. |
| `domain/.../family/FamilyWizardController.kt` | ~1 537 | Session establishment UX state machine in domain; imports many `sync.*` types; Chinese feedback strings. |
| `domain/.../carelog/RecordMutationCoordinator.kt` | 944 | Record/sleep/nursing/fulfillment mutations + open-sleep critical section. |
| `domain/.../careplan/CarePlanCoordinator.kt` | (substantial) | Plan CRUD, fulfill skew, calendar projection hooks. |
| `domain/.../localdata/LocalDataClearCoordinator.kt` | medium | Clear scopes + finish barriers; couples timer/widget/calendar cleanup ports. |
| `domain/.../carelog/PhotoAttachmentReconciler.kt` | 178 | **Parameterized** owner (Record\|CarePlan) — July twin largely retired. |
| Other subpackages (`growth`, `export`, `catalog`, `timeline`, `calendar`) | smaller | Clearer locality post-partition. |

**Dependency:** `domain → :sync` intentional for `notifyLocalChanges`, wizard gateway, session/role, clear callbacks (tech.md §2.2).

### 2.4 `sync/`

| Path | ~Lines | Why expensive |
|------|-------:|---------------|
| `engine/ReplicaSyncEngine.kt` | ~2 828 | Capture → ephemeral plan → apply pull → media materialize → technical tombstones; owns multi-entity consistency. |
| `RealSyncPort.kt` | 2 340 | Deep public façade (tech.md forbids shallow split as product goal): force update shell, availability, replica, identity terminal clears, disaster restore resume, Chinese publish chrome helpers. |
| `backend/HttpSyncBackend.kt` | ~1 600+ | Wire codec + upload watchdog + response size limits. |
| `SyncPort.kt` | 629 | Wide interface + `NoOpSyncPort` defaults; many family/admin/app-update methods. |
| `engine/EphemeralPublishPipeline.kt` | 708 | **Twin** `pushRecordAtomicBundle` / `pushCarePlanAtomicBundle` (mechanical duplication of intentional dual roots). |
| `session/FamilySessionCoordinator.kt` | ~838 | Create/join/login/rename/device commands under barrier. |
| `media/AtomicMediaBundlePublisher.kt` | medium | Commit receipts / concurrent domain write tension (audit history). |
| `appupdate/*` | medium | Install identity + **product Chinese copy** living on sync seam. |
| `clear/*`, `availability/*`, `qr/*`, `disasterrecovery/*` | medium | Orthogonal capabilities still behind one façade. |

**Intentional sideways seam:** features and domain may depend on `:sync` (tech.md §2.2). Rewrite must re-decide whether one “sync module” remains the kitchen sink.

### 2.5 `core/*` + `designsystem`

| Path | ~Lines | Why expensive |
|------|-------:|---------------|
| `core/database/.../Daos.kt` | 1 091 | All Room query surface; synthetic root publication helpers; media CAS paths. |
| `core/database/.../Entities.kt` | 277 | Schema entities (Room v26 / data contract v3). |
| `core/database` migrations / `schemas/*.json` | — | In-place upgrade contract (ADR-0012); greenfield may reset schema but must plan migration. |
| `core/model` payload codecs, `TimerHandoffSeed`, nursing orders | many small files | Cross-cutting pure rules; next-feed marker shared with Rust via fixture. |
| `core/ui/.../AppUpdateOutcomeDialogs.kt` | small | **Depends on `:sync`** for update types/copy — layer inversion vs pure UI kit. |
| `designsystem/Theme.kt`, tokens, timeline, nursing confirm, photo preview | medium | Dual warm/journal templates; token drift history (hardcoded lane colors in log path). |

**Doc/code edge:** `core:ui/build.gradle.kts` has `implementation(project(":sync"))`, but `docs/prd/tech.md` §2.2 **does not list** `core:ui → sync`. That is live drift (see §4).

### 2.6 `tools/lezi-sync` (Rust)

| Path | ~Lines | Why expensive |
|------|-------:|---------------|
| `src/store/bundles.rs` | 2 170 | Atomic bundle validate/commit, LWW, fulfillment pair freeze. |
| `src/model.rs` | 2 317 | Closed wire schema + nested payload validation (+ cfg tests). |
| `src/store/identity/session.rs` | ~922 | Create family, owner login, sessions, devices. |
| `src/store/identity/login.rs` | ~695 | Member login requests/grants. |
| `src/store/identity/membership_admin.rs` | ~517 | Rename/hard-delete/delete family. |
| `src/store/pull.rs` | ~465 | Pull pagination + dependency co-group. |
| `src/store/reconciliation.rs` | ~329 | Authoritative head reconcile. |
| `src/store/mod.rs` | ~448 | **Deep `Store` façade** retained; modules are crate-private partitions, not thin adapters. |
| `src/handlers/sync.rs` | ~305 | HTTP reconcile/pull/bundle routes. |
| `src/lib.rs` | ≥840 | Router, auth, bootstrap secret, client version gate. |
| `src/offline_migrate/migrator.rs` | ≥1 1xx | One-shot maintenance CLI (ADR-0013); not runtime auto-migrate. |
| Former monofile `store.rs` (~6.5k in 08-01 audit) | n/a | **Partitioned** into `store/*` — complexity redistributed, not eliminated. |

### 2.7 Tests (giant contract surfaces)

| Path | ~Lines | Why expensive |
|------|-------:|---------------|
| `tools/lezi-sync/tests/api.rs` | **14 065** | End-to-end server API matrix; single file owns identity + bundles + revoke + rename + fulfillment… |
| `sync/src/test/.../RealSyncPortTest.kt` | **11 136** | Fake backend + memory DAOs + full sync lifecycle; includes rig helpers mid-file. |
| `domain/src/test/.../CareLogTest.kt` | **8 403** | Broad CareLog + fakes (settings, calendar, media, clear). |
| Module unit tests (log composer, timer, family wizard, store unit mods) | many | Healthier locality; still some StructureTest-style residual comments (growth/family “not gated by product-less StructureTests”). |
| designsystem contract tests | small | Some assert **source strings** (e.g. dead `JournalSummaryStrip` absence) — policy now forbids new product-less StructureTests. |

**Rewrite policy already locked in map:** do **not** 1:1 port giant contract files; extract thin golden paths (tickets 08/11).

---

## 3. Accidental coupling edges (with evidence)

These are **edges or twin implementations that raise change cost** without being the primary domain seams.

| Edge | Evidence | Cost |
|------|----------|------|
| **`core:ui → :sync`** | `core/ui/build.gradle.kts` `implementation(project(":sync"))`; `AppUpdateOutcomeDialogs.kt` imports `sync.AppUpdateMetadata` + `sync.appupdate.*` | UI infrastructure depends on product sync types/copy; tech.md §2.2 omits this edge → doc/code drift + inverted layer. |
| **UI product copy in `:sync`** | `AppUpdateUiCopy.kt` Chinese strings; `RealSyncPort.kt` `localRecordPublishLabel` / `localCarePlanPublish*`; `FamilyUploaderResolution` / session error copy | Sync module owns form UX language; i18n and UI redesign require sync edits. |
| **Family wizard state machine in domain + dual UI hosts** | `FamilyWizardController.kt` + `feature/family` + `feature/onboarding` shells; Chinese `userMessage()` in domain | Domain encodes presentation feedback; two hosts risk chrome drift (QR projection helper mitigates some). |
| **`domain → :sync` deep type imports** | `FamilyWizardController` imports CreateFamilyResult, QR payloads, SetupProbeResult, SyncPort…; `CareLog` / coordinators import `SyncPort`, `FamilyRole`, `PolicyClock` | Domain layer not pure; hard to unit-test without sync types. (Seam intentional; **width** is accidental.) |
| **Record vs care-plan publish twin** | `EphemeralPublishPipeline.pushRecordAtomicBundle` / `pushCarePlanAtomicBundle` | Mechanical twin of intentional dual roots (ADR-0001/0005); protocol change doubles edits. |
| **Custom item manage dual entry** | `settings/.../CustomItemSettingsDialog.kt` (live from SettingsScreen) vs `log/.../LayoutCustomManageDialog.kt` (layout edit) | Same catalog CRUD chrome twice; ACL/hide rules may diverge. |
| **Nursing confirm dual entry (mitigated, still dual surface)** | Composer nursing fields (`QuickRecordPurposeFields` / draft) vs `timer/NursingCompletionSheet` + `NursingCompletionDraft`; designsystem now has `LeziNursingConfirmFields` / confirm chrome | Product path still two hosts; order chips / single-side history still a known UX risk (July U2). |
| **Clear-scope enum stacking** | `LocalDataClearScope` (database) + replica clear coordinator + pending cleanup stores; domain typealias `LocalRecordsClearCommittedException = LocalClearCommittedException` | Dual **exception types retired** to alias; scope names still multi-layer. |
| **Log → sync for publish chrome** | `LogViewModel.timelineRecordPublishLabel` → `localRecordPublishLabel` in RealSyncPort.kt | Feature depends on sync module for local-only labels (listed in tech.md as intentional; still couples UI string policy to sync). |
| **App composition handoff** | `MainActivity` timer route reads `TimerHandoffSeed` from previous back stack | Correct anti-feature-edge, but concentrates integration in MainActivity size. |
| **Payload schema dual language** | Kotlin `RecordPayloadCodec` / model + Rust `model.rs` nested validators; next-feed marker fixture shared | Drift risk on every type/field; rewrite wire redesign (ticket 07) must single-source or generate. |
| **Token / template drift (UI)** | July/UI audits: hard-coded timeline ARGB in log path; journal primary vs baby accent; PRD dock mirror text vs CONTEXT “no dock mirror” | Not a Gradle edge; product contract inconsistency across docs and tokens. |
| **Settings dead dual dialogs (status: largely retired)** | July R2 cited slots/all-items dialogs without production call sites; current settings tree shows `record/CustomItemSettingsDialog` + `RecordSettings` — **verify before assuming dead code remains** | Residual risk if any androidTest-only dialogs still compile. |

---

## 4. Doc / code drift (facts)

| Drift | Doc claim | Tree fact |
|-------|-----------|-----------|
| Sync dependency table | `tech.md` §2.2 lists app/domain/log/family/onboarding/growth/settings/summary → sync | **Also** `core:ui → sync` (unlisted). |
| God sizes (historical) | 07-29: CareLog ~3k, LogScreen ~2k, store.rs ~6.5k | **Partitioned:** CareLog ~1.1k façade + subpackages; LogScreen ~570 + packages; store → `store/*`. Residual gods are ReplicaSyncEngine, RealSyncPort, tests, bundles. |
| Dock preferred-hand | Older `docs/prd/ui.md` / design notes vs `CONTEXT.md` “坞不再镜像” | CONTEXT is terminology authority; PRD/design may lag (July U1). Rewrite UI goldens must pick one. |
| Media model | July R12: `MediaAsset` missing plan/kind | **Current** `Models.kt` MediaAsset has record/carePlan/baby XOR + kind/syncDirty — fixed relative to July. |
| Photo reconcile twins | July R8 CareLog twin methods | **Current** `PhotoAttachmentReconciler` parameterized owner — fixed. |
| StructureTests | AGENTS/tech: ban product-less StructureTests | Some designsystem tests still assert source absence of dead APIs; comments elsewhere reject layout StructureTests. |

---

## 5. Intentional seams vs accidental redundancy

### 5.1 Intentional (ADR / tech.md — do not “merge away” as simplification)

| Seam | Anchor | Note for rewrite |
|------|--------|------------------|
| Care plan ≠ care record | ADR-0001 | Separate entities, fulfill → fact, conflict-not-adopted |
| Photos as attachments with XOR ownership | ADR-0003 | Record vs plan media rows; shared chrome OK, ownership not |
| Atomic sync packages | ADR-0005 / 0008 / 0016–0017 | Bundle stage/put/commit; reconcile-before-plan |
| Share custom **definitions**, not layout | ADR-0006 | Catalog sync vs device slots/order/hide |
| Membership ≠ credentials | ADR-0007 | Session tokens vs membership UUID |
| Fresh-current product contracts | ADR-0008 | No long dual protocol; schema fail-closed |
| Trust server identity not SSID | ADR-0010 | TOFU/SPKI |
| Local data preserved across APK replace | ADR-0012 | Room migration chain / data contract versions |
| Offline-migrate is maintenance window only | ADR-0013 | CLI, not auto server upgrade |
| Owner restore only empty servers | ADR-0014 | Disaster recovery |
| Isolated LAN invite-install | ADR-0015 | Port 8767 path |
| Gradle modules + deep façades | tech.md §2.1 | Keep module map; `CareLog` / `SyncPort`/`RealSyncPort` / lezi-sync `Store` are deep façades by policy |
| `feature` mutual independence | tech.md §2.1 | No feature↔feature project deps |
| `:sync` sideways dependency | tech.md §2.2 | Listed callers may implement(project(":sync")) |
| Timer handoff via model + app root | tech.md §3 timer | Not feature:log → feature:timer |
| Dual visual templates warm/journal | PRD ui / designsystem | Two shells, shared semantics |
| Dual wizard **entries** (Onboarding \| Account) | FamilyWizardEntry | Same controller, two hosts — intentional product surface |

### 5.2 Accidental redundancy / over-width (candidates for greenfield to avoid, not “must preserve”)

| Item | Status at survey | Note |
|------|------------------|------|
| Giant test monoliths (api.rs, RealSyncPortTest, CareLogTest) | **Present** | Map already forbids 1:1 port |
| SyncPort / RealSyncPort kitchen-sink surface | **Present** | App-update + account + replica + clear + disaster in one type |
| Record/plan **mechanical** push twins | **Present** | Domain separation intentional; code twin not required |
| Custom item settings vs layout manage dialogs | **Present** | Dual chrome |
| Nursing composer vs timer completion surfaces | **Partially unified** via designsystem; dual hosts remain | |
| UI Chinese strings in sync/domain | **Present** | Copy locality accident |
| core:ui depending on sync | **Present** | Layer inversion |
| store.rs single file | **Fixed by partition** | Façade still deep |
| CareLog 3k god file | **Mitigated** to façade + packages | Façade still wide API |
| LocalClear dual exception classes | **Retired** to typealias | |
| MediaAsset model incompleteness | **Fixed** | |
| Photo reconcile record/plan code twin | **Parameterized** | |
| Dead layout settings dialogs | **Likely retired** | Confirm if any compile-only leftovers |
| Product-less StructureTests | **Policy bans new ones** | Residual string contracts in designsystem tests |

---

## 6. Known god surfaces (named for later layering)

1. **`ReplicaSyncEngine`** — client family truth convergence.  
2. **`RealSyncPort` + `SyncPort`** — process coordinator + public API width.  
3. **lezi-sync `Store` + `bundles` + `model` wire validation** — server truth + schema.  
4. **`FamilyWizardController` + Family/Onboarding hosts** — identity UX.  
5. **`MainActivity` composition root** — navigation + force shell + handoff.  
6. **Log layout-edit + composer stack** — primary capture UX.  
7. **Timer FGS + completion SavedState** — reliability surface.  
8. **Test gods** — `api.rs`, `RealSyncPortTest`, `CareLogTest` as *de facto* specifications (risky to treat as portable contracts).

---

## 7. Implications for later tickets (pointer only — not decisions)

- **Layering (ticket 06):** decide whether greenfield keeps a sideways “sync” mega-module or splits ports by capability while preserving ADR domain concepts.  
- **Wire/schema (ticket 07):** `model.rs` + Kotlin codecs + next-feed marker are the dual-language tax; redesign should avoid long dual protocol (ADR-0008).  
- **Thin E2E goldens (ticket 08/11):** replace mega tests with path-scoped goldens; do not transplant RealSyncPortTest/api.rs wholesale.  
- **UI goldens (ticket 09/10):** MainActivity + log + family wizard + force shell + timer completion are high-value screenshot paths; token dual-template drift is a consistency risk.  
- **Migration checklist (ticket 12):** Room data contract + offline-migrate CLI are intentional ops edges separate from ordinary CD.

---

## 8. Survey limits

- No full `find | wc -l` tree ranking; prioritization used known review targets + package lists + EOF probes.  
- Sub-500-line files largely omitted unless they participate in coupling edges.  
- Reviews under `docs/reviews/` are local/gitignored drafts — used as secondary evidence, not product authority.  
- Correctness P1 list from 08-01 audit is **not** re-verified here; this ticket is structural inventory only.
