# Test redundancy inventory (Android/Kotlin)

**Date:** 2026-08-05  
**Policy:** [spec.md](./spec.md)  
**Corpus:** ~**258** `*Test.kt` / `*DeviceTest.kt` files; ~**70k** LOC in tests (incl. kitchen sinks).  
**Layers:** ~222 JVM unit; ~36 androidTest/device.

Classification is by **contract cluster**. Kitchen-sink **internals** are not method-audited (Q2=A).

---

## 1. Kitchen sinks — DEFER (Q2=A)

| File | ~LOC | Contract cluster | Disposition |
|------|------|------------------|--------------|
| `sync/.../RealSyncPortTest.kt` | 11158 | SyncPort façade / multi-scenario integration | **DEFER** |
| `domain/.../CareLogTest.kt` | 8402 | CareLog orchestration kitchen sink | **DEFER** |
| `sync/.../engine/ReplicaSyncEngineTest.kt` | 2537 | Replica engine | **DEFER** |
| `sync/.../backend/HttpSyncBackendTest.kt` | 2190 | HTTP backend | **DEFER** |
| `sync/.../session/FamilySessionCoordinatorTest.kt` | 1479 | Session coordinator | **DEFER** |
| `domain/.../family/FamilyWizardControllerTest.kt` | 1406 | Family wizard | **DEFER** |
| `sync/.../session/SyncPreferencesTest.kt` | 1332 | Sync preferences persistence | **DEFER** |
| `feature/log/.../composer/QuickRecordDraftTest.kt` | 1197 | Composer draft rules | **DEFER** (large but not cross-module sink) |
| Other ≥500 LOC behavioral suites | — | Local clear, media, timer, calendar smoke, … | **DEFER** method-level dedupe |

Inventory note only: greenfield issue 11 flags these as anti-patterns for *new* work; **existing assertions stay** this round.

---

## 2. Non-critical process code (过程/脚手架)

### 2.1 Dirty tree WIP (uncommitted) — PROCESS-FIX (Q8=C)

| Path | What it is | Disposition | Retained coverage plan |
|------|------------|-------------|------------------------|
| `FamilyMembersListUi.kt` `testTag("members_devices_list")` | Test-only tag; not user-visible | **PROCESS-FIX:** remove tag **if** empty-state can be asserted via public text/semantics + scroll-to-text or smaller fixture | Empty-state copy + revoke/no-technical-id still in `FamilyMembersDevicesPageDeviceTest` |
| `FamilyMembersDevicesPageDeviceTest` `performScrollToNode` | Scroll harness for off-screen「暂无设备」 | **PROCESS-FIX:** prefer `hasText` + scroll without product testTag, **or** shrink fixture so node is on-screen | Same device test’s owner roster assertions; JVM `FamilyLazyMembersContractTest` owns empty_devices **model** |
| `RecordComposerDiscardDeviceTest` `import material3.Button` | Compile fix for harness using raw `Button` | **PROCESS-FIX:** keep import while device suite lives; optional harness use of `Lezi*` later | Device interaction tests below |

**Unrelated dirty (out of task):** `.scratch/README.md`, `gradle.properties`, greenfield dirs, ui-smoke PNGs.

### 2.2 Test-only semantics tags in production UI

Product `testTag(...)` used solely for tests (examples: `members_devices_list` dirty; existing `device_overflow_menu`, `members_manage_menu`, layout editor tags).  

| Rule | Disposition |
|------|-------------|
| Tag required for a11y-equivalent or stable public test seam documented in PRD | **KEEP** |
| Tag only to scroll/lazy find process text | **PROCESS-FIX** / remove with fixture change |

Not bulk-deleted this wave without per-tag audit.

---

## 3. Source-string Structure / Contract — Q3=B + Q7

### 3.1 High-confidence **DELETE** candidates (Wave 1)

These primarily `readText` / `walkTopDown` / string `contains` on production sources. Under locked policy they are default-delete. **Coverage receipts** below; approve before delete.

| Suite | Nature | Unique regression story? | Retained coverage if deleted | Wave 1 |
|-------|--------|--------------------------|------------------------------|--------|
| `app/.../ContractSupersededSurfacesTest.kt` (~211 LOC, 6 tests) | Full-tree source absence/presence: dead controllers, NextFeed alarm files, glyph v1, cleartext/network XML, Wi‑Fi SSID strings, legacy invite/join, PullToRefreshBox count | **Partial:** dead-path reintroduction; cleartext/transport is security-adjacent but still **string scan** | Manifest/network XML can be **REWRITE** later as file-existence + parsed XML assertions in a tiny dedicated test **if** desired; behavioral coverage for wizard/next-feed already in domain/feature tests; invite/join removal is schema+PRD level | **DELETE** whole class **or** split: keep only `releaseTransport…` as REWRITE candidate — **recommend DELETE all** under Q3=B; flag transport as optional Wave 3 REWRITE |
| `designsystem/.../UiAuditPathContractTest.kt` (~414 LOC) | Source strings for onboarding AnimatedContent, bottom nav onClick shape, bare Material re-scan, dialog prefer LeziAlertDialog, magic durations, growth stroke, etc. | Bottom nav empty-`onClick` was a real regression (commented in file) | No Compose device test for short-press nav found in inventory; **gap if fully deleted** | **KEEP-PARTIAL →** mark `bottom NavigationBarItem…` as **REWRITE** (device/semantics or small policy object); **DELETE** pure source-layout tests (AnimatedContent spacedBy, stroke literals, duration greps). Bare Material methods **DELETE** with BareMaterial suite (Q7) |
| `designsystem/.../ClockDialNursingChromeContractTest.kt` | Source forbids bare Material + geometry string constants | Overlaps BareMaterial + token tests | `NursingConfirmInput` / timer draft JVM; geometry not user-contract | **DELETE** |
| `designsystem/.../NursingConfirmSurfaceContractTest.kt` | Source surface wiring strings | Low | Model + timer/composer draft tests | **DELETE** |
| `designsystem/.../NextFeedPhotoQrChromeContractTest.kt` | Source chrome consumption strings | Low | `NextFeedPlanFlowTest` (model), device `NextFeedPlanFlowDeviceTest` | **DELETE** source parts; if any pure token asserts exist, **KEEP-PARTIAL** |
| `designsystem/.../PhotoPreviewDialogTest.kt` | Source shared preview chrome | Medium (duplicate preview was audit finding) | `LocalPhotoLoaderTest` + device smoke; product still uses shared dialog | **DELETE** under Q3=B (accept gap) **or** REWRITE one JVM API test on shared preview API if public |
| `designsystem/.../JournalThemeAndDockPolicyContractTest.kt` | Mix token + source | Token parts valuable | Split | **KEEP-PARTIAL:** keep runtime token/policy asserts; **DELETE** `read()` source contains |
| `designsystem/.../RecordDensityEmptyContractTest.kt` | Mix | Same | Split | **KEEP-PARTIAL** |
| `designsystem/.../WeakSurfacesContractTest.kt` | Token grid **KEEP**; Settings/Export/Widget **source contains** | Ticket 11 polish | `LeziMenuIcon` values tested in first method | **KEEP-PARTIAL** |
| `feature/summary/.../SummaryDensityEmptyContractTest.kt` | Almost pure source strings for copy + density | Empty copy is user-visible but asserted via source | Prefer later UI/policy object; aggregation engines already tested | **DELETE** under Q3=B (copy drift risk accepted) **or** REWRITE empty-copy constants if extracted |
| `designsystem/.../LeziTextFieldLineModeTest.kt` | **KEEP** pure mode math; last test reads product call sites | Call-site scan is Structure | Mode unit tests stay | **KEEP-PARTIAL:** **DELETE** `product multi-line note…` source scan only |
| `designsystem/.../BareMaterialWhitelistContractTest.kt` + `DesignsystemSourceFixtures` scan usage | Synthetic + live tree bare Material scan | **Chrome policy gate** (user chose Q7=delete default) | **None automated after delete** | **DELETE** suite + stop calling scan from UiAudit; **optional Wave 3:** detekt/lint rule — out of round unless approved REWRITE |
| `UiAuditPathContractTest` bare Material methods | Duplicate of BareMaterial | No | Same | **DELETE** with BareMaterial |

### 3.2 **KEEP** (named *Contract* but not Structure)

| Suite | Why KEEP |
|-------|----------|
| `feature/family/.../FamilyLazyMembersContractTest.kt` | Pure roster flatten/privacy **behavior** (no source read) |
| `feature/growth/.../GrowthLazyHistoryContractTest.kt` | Pure order helper |
| `feature/widget/.../WidgetComposerContractTest.kt` | Need verify if source-scan; default **KEEP** if API-level |
| `core/model/.../NextFeedPlanMarkerContractTest.kt` | Domain marker contract |
| Most `*PolicyTest`, `*StateTest`, codec/mapper tests | L1 behavior |

### 3.3 MotionDensityTokensTest

| Methods | Disposition |
|---------|-------------|
| `nonEssentialMillis` / density table numeric | **KEEP** |
| `tokens.json` read parity | **KEEP** (design token contract, not product source layout) |

---

## 4. Cross-layer (JVM vs Device) — Q4=B + Q5=C

### 4.1 Device suite matrix (all ~36)

| Device suite | Q5 keep reason? | Related JVM | Disposition |
|--------------|-----------------|-------------|-------------|
| `FamilyAccountAffordanceSemanticsTest` | **a11y/semantics** | affordance JVM if any | **KEEP** |
| `FamilyMembersDevicesPageDeviceTest` | UI copy + revoke interaction; empty_devices partially in `FamilyLazyMembersContractTest`; **no formal flaky ticket** but handoff notes off-screen | LazyMembers + timeout + copy tests | **KEEP** suite; **PROCESS-FIX** scroll/tag (Q8). Under strict Q5, empty-state-only could compress — **do not drop whole suite** (owner ACL/copy/fontScale unique) |
| `MemberApprovalWaitingDeviceTest` | Waiting UI + time | Host/policy JVM | **KEEP** (interaction + copy) |
| `OwnerTakeoverConfirmationDeviceTest` | Confirmation copy UI | policy | **KEEP** (short; confirmation chrome) |
| `RecordComposerDiscardDeviceTest` | Compose sheet restore/focus/busy — **beyond** `DiscardPolicyTest` pure rules | `RecordComposerDiscardPolicyTest` | **KEEP** device (UI regression class: sheet/focus); policy JVM **KEEP**. Not COMPRESS-delete device under Q5 (focus/restore is UI failure signal). Optional later thin if restore covered elsewhere |
| `ManagementActionAccessibilityDeviceTest` | **a11y** | `ManagementActionSemanticsTest` + `StateTest` | **KEEP** device; JVM **KEEP** (different signal) |
| `LayoutAccessibilityDeviceTest` | **a11y** | layout policy JVM | **KEEP** |
| `LayoutConfigurationRecreationDeviceTest` | **config recreation** (system) | session stores JVM | **KEEP** |
| `LayoutMotionHapticsDeviceTest` | haptics/clock | feedback JVM | **KEEP** (device-only signal) |
| `LayoutLocalDeletedDeviceTest` | fontScale + small viewport + scroll | policy | **KEEP** (system density/font) |
| `LayoutCategoryDragDeviceTest` | gesture matrix | `LayoutDragSessionTest` etc. | **COMPRESS-candidate:** drag **rules** in JVM session tests; device proves hit-testing. Under Q5 strict: **no named flaky ticket** → **COMPRESS** Wave 2 = delete device **if** JVM drop intents fully cover matrix; **else KEEP**. Inventory default: **Wave 2 review**, lean **KEEP** drop-matrix (hit test unique) |
| `LayoutEditDropMatrixDeviceTest` | same | DragSession | same as above |
| `LayoutDragGuidanceDeviceTest` | recreation + hint UI | guidance JVM | **KEEP** recreation aspects; hint copy could be JVM |
| `LayoutEdgeAutoScrollDeviceTest` | edge scroll while held | `LayoutEdgeAutoScrollPolicyTest` | **COMPRESS-candidate Wave 2:** policy JVM exists; device is integration. Default **KEEP** one smoke **or** delete if policy+session enough — **flag REVIEW** |
| `LayoutUndoDeviceTest` | snackbar timing/token UI | undo session/state JVM | **KEEP** (Material timing UI) |
| `LayoutTargetRegistrationComposeTest` | recomposition bounds | — | **KEEP** (Compose registration) |
| `BoundedPhotoPipelineDeviceSmokeTest` | **real files/Bitmap** | importer JVM | **KEEP** |
| `LargeMediaResourceDeviceTest` | **real decode/bounds** | photo policy JVM | **KEEP** |
| `SettingsAffordanceSemanticsTest` | **a11y** | — | **KEEP** |
| `AndroidSystemCalendarPortSmokeTest` | **system calendar provider** | policy/lookup JVM | **KEEP** |
| `AndroidSystemCalendarPermissionDeniedSmokeTest` | **permission** | — | **KEEP** |
| `CalendarEmptyDayStateTest` | Compose empty day | `CalendarMonthStateTest` | **COMPRESS-candidate Wave 2** if state fully JVM |
| `NursingTimerLocalClearDeviceTest` | **real Service + notification** | `NursingTimerCleanupSessionTest` | **KEEP** device (system); JVM **KEEP** |
| `ExternalNavigationTrustDeviceTest` | forged intents + UI confirm | `ExternalNavigationTrustPolicyTest` | **KEEP** both (policy vs Activity intent) |
| `LocalDataContractMigrationDeviceTest` | **real files/DataStore** | detection JVM | **KEEP** |
| `LocalDataUpgradeDeviceTest` | upgrade path device | planner JVM | **KEEP** |
| `LeziMotionScaleDeviceTest` | system animator scale | Motion tokens JVM | **KEEP** |
| `LocalPhotoLoaderDeviceSmokeTest` | real loader | `LocalPhotoLoaderTest` | **KEEP** smoke |
| `NextFeedPlanFlowDeviceTest` | Compose flow UI | `NextFeedPlanFlowTest` model | **KEEP** thin UI |
| `TimelineDstDeviceTest` | DST device clock | axis JVM tests | **KEEP** (device clock) |
| Room `*RoomTest` / `FreshDatabaseTest` / `TimelineWindowInvalidationDeviceTest` | **real Room** | some JVM store tests | **KEEP** (instrumented DB is L2 device) |

### 4.2 Cross-layer summary counts

| Bucket | Count (approx) | Action |
|--------|----------------|--------|
| Device **KEEP** (a11y/system/real resource/config) | majority of 36 | no compress |
| Device **PROCESS-FIX** only | family members scroll/tag | Wave 1 |
| Device **COMPRESS / REVIEW Wave 2** | layout drag matrix, edge scroll, calendar empty day (subset) | need method-level retained coverage before delete |
| Discard device vs policy | complementary | **KEEP both** this round |

---

## 5. Module clusters — default KEEP (no delete wave)

Healthy L1/L2 style; not flagged Structure unless noted.

| Cluster | Modules / examples | Notes |
|---------|-------------------|--------|
| Model/codecs | `core/model/*Test` | KEEP |
| Datastore/settings clear | `core/datastore/*` | KEEP |
| Database JVM + Room device | `core/database/*` | KEEP both layers (Room needs device) |
| Care aggregation / search / photos reconciler | `domain/carelog/*` (ex CareLogTest sink) | KEEP |
| Timeline window | domain + feature log timeline | KEEP; DST covered multi-layer intentionally |
| Composer (non-discard) | draft/photo/saved state/session gate | KEEP; large files DEFER internal dedupe |
| Layout **JVM** | drag session, undo, policy, writer | KEEP; primary under Q4 |
| Family host/copy/network | feature/family unit | KEEP |
| Sync focused (non-sink) | wire mapper, QR, media prepare, app update, availability | KEEP |
| Timer unit | completion, handoff, restoration | KEEP |
| Widget / export path security | export path traversal tests are **behavior** | KEEP |
| Onboarding / search VM | | KEEP |
| Growth reference catalog | may read assets — not Structure layout | KEEP |

---

## 6. Non-critical process vs critical contract (executive)

### Non-critical process (cleanup targets)

1. Uncommitted testTag + scroll harness (family).  
2. Source-string Structure/Contract suites (§3.1).  
3. BareMaterial source scanner suite (Q7 — **accepts temporary loss of chrome gate**).  
4. Device tests that only re-drive JVM-known drag **intent** matrices without hit-test necessity (Wave 2 review).  
5. Duplicate BareMaterial assertions inside UiAuditPath.

### Critical — do not treat as process

1. Kitchen-sink **contents** (defer, don’t gut).  
2. Room/instrumented DB, TLS/openssl, calendar provider, timer Service/notification.  
3. A11y custom actions / fontScale / touch target device tests.  
4. Pure domain policy and pure token numeric tests.  
5. Discard **policy** JVM + discard **sheet** device (different signals).  
6. External navigation policy + forged intent device.

---

## 7. Wave 1 proposal (needs explicit approval)

**Do not execute until approved.**

### 7.1 PROCESS-FIX (owned dirty paths only)

1. Family empty-state: remove `members_devices_list` testTag from product if alternative works; adjust device test scroll/fixture (Q8=C).  
2. Composer: keep `Button` import **or** switch harness control; do not leave compile-broken HEAD.

### 7.2 DELETE (Structure / scan)

| Item | Retained coverage (claim) |
|------|---------------------------|
| `ContractSupersededSurfacesTest` (all) | Domain/feature tests for live surfaces; dead code absence becomes unenforced (accepted under Q3=B). Optional later: network security XML unit parse. |
| `ClockDialNursingChromeContractTest` | Model + timer/composer tests; chrome wrappers exist as code |
| `NursingConfirmSurfaceContractTest` | Same |
| Source-only methods in Weak/RecordDensity/Journal/LeziTextField product scan / SummaryDensityEmpty / PhotoPreviewDialog / NextFeedPhotoQr (source parts) | Token KEEP methods; model/device next-feed; photo loader tests |
| `BareMaterialWhitelistContractTest` + UiAudit bare-Material methods | **No automated replacement** (explicit Q7 risk) |
| UiAudit source-layout methods except bottom-nav | Bottom-nav → REWRITE or accept gap |

### 7.3 REWRITE (only if you reject bare gaps)

| Item | Suggested replacement |
|------|----------------------|
| Bottom nav short-press owner | Small device/semantics test on `MainActivity` nav **or** extract click owner pure function |
| Release cleartext / network security | Parse XML fixtures without full-tree walk |
| Bare Material policy | Detekt/lint rule (out of default Wave 1) |

### 7.4 Explicit non-goals Wave 1

- No kitchen-sink edits.  
- No layout device deletes.  
- No Rust.  
- No unrelated dirty commit.

---

## 8. Wave 2 — landed (`f355ad32` parent / this tree)

| Deleted device | Receipt |
|----------------|---------|
| `LayoutCategoryDragDeviceTest` | `LayoutDragSessionTest` category* + `LocalLayoutEditPolicyTest` MoveCategoryToIndex |
| `LayoutEditDropMatrixDeviceTest` | `LayoutDragSessionTest` locked-more / gap / clear / restore / assign |
| `LayoutEdgeAutoScrollDeviceTest` | `LayoutEdgeAutoScrollPolicyTest` |
| `CalendarEmptyDayStateTest` | JVM `CalendarEmptyDayChromeTest` + `calendarEmptyDayMessage/ActionLabel` helpers; schedule dates in `CalendarMonthStateTest` |

**Accepted gap:** no Compose long-press→intent wiring device smoke for layout drop matrix (session unit owns rules).

---

## 9. Metrics (rough)

| Class | Files (order of magnitude) |
|-------|----------------------------|
| DEFER kitchen / large | ~10–15 files dominate LOC |
| Structure DELETE / KEEP-PARTIAL | ~12–15 suites |
| Device KEEP | ~30 |
| Device COMPRESS review | ~3–5 |
| PROCESS-FIX dirty | 3 paths |
| Default KEEP remainder | ~200 files |

---

## 10. Approval checkbox

- [ ] Policy still matches [spec.md](./spec.md)  
- [ ] Approve Wave 1 §7.1 PROCESS-FIX  
- [ ] Approve Wave 1 §7.2 DELETE list (edit if BareMaterial must stay)  
- [ ] Approve/reject §7.3 REWRITE before delete for bottom-nav / cleartext  
- [ ] Confirm BareMaterial gap acceptable  

After checkboxes, implement with narrow patches + module tests/compile; commit only task-owned paths.
