# iOS co-development: parity inventory and acceptance plan

> Status: **planned, not implemented or accepted**. This is an explicitly prospective
> companion to the current Android specification, not a claim that iOS is delivered.
> Audited public Android baseline: `cfd9c878`, 2026-10-19. Build identity remains
> authoritative in `app/build.gradle.kts` and `config/android-release-compatibility.json`.
> Scope: full product parity, native iOS client, same existing Rust family service,
> mixed Android/iOS families. Android remains unchanged during this initial work.
> Architecture decision: [ADR-0025](../adr/0025-ios-co-development.md).
> Foundation and a bounded unsigned development host are implemented on
> `feat/ios-parity-20261019`; the complete app and real Android/iOS/server
> acceptance remain unimplemented/unverified. The draft PR is for review, not
> merge or release. See the [offline record expansion plan](ios-offline-records.md)
> for the implemented checkpoint versus prospective work.

### Initial foundation evidence (2026-10-19)

The additive Swift package now contains typed handshake/heartbeat/rejection
decoders, portable care payload/time/sleep rules, four theme palettes, navigation
composition and an in-memory confirmation boundary. The integrated Linux
Swift 6.4.1 run passed 41 XCTest cases (17 contract, 12 domain, 12 presentation)
including the review regression additions. This is a foundation checkpoint,
not acceptance of any complete application row below. At that initial checkpoint there was no installable app, durable iOS repository,
production transport or mixed-client proof; the later offline checkpoint below
supersedes only the bounded storage/development-host gaps.

Android runtime, Rust runtime and shared corpus bytes remain unchanged. The initial macOS workflow tested the package and compiled library targets
against the iOS Simulator SDK. The later checkpoint below records actual Apple
evidence; this historical foundation count does not describe the latest run.

### Offline vertical slice checkpoint (2026-10-19)

The first published foundation head `125d326a` passed Android run
`39270410111` and macOS/iOS-library run `39270410213`. The PR synthetic merge
tree was verified identical to that head. These results do not cover later code.

The published checkpoint `1e5802a6eeaec8521b89e812e33c9e328e66c736` adds
schema-2 local payload codecs, a bounded baby canonical/hash contract, real
SQLite offline baby/pee persistence with indexed keyset paging, and a SwiftUI
create/select/confirm/read flow. [Android run 39282387623](https://github.com/tusecwtf/lezi/actions/runs/39282387623)
and [Apple run 39282387612](https://github.com/tusecwtf/lezi/actions/runs/39282387612)
are green for this head (verified 2026-10-19 16:25 UTC). Apple ran **93 package
tests plus one Simulator UI smoke test** and built the
unsigned `LeziDevelopment` host. These results supersede the previously pending
Apple evidence, not the complete product acceptance matrix.

The earlier **dual-green** checkpoint **`f7ca7571`** covered 13 forms:
Android **5,908 tests**, Apple **151 package tests plus four Simulator UI tests**.
It supports real create/edit/soft-delete UI for 13 ordinary types: pee, poop,
both_diaper, diary, bath, walk, cough, rash, vomit, injury, medicine, hospital,
and vaccine. Original-baby/revision ownership, dirty exit, commit-before-success
and bounded restart persistence are exercised. The earlier 93+1 result is historical.

The earlier **dual-green** growth checkpoint **`3f8e449`** adds five growth forms (height, weight, head, chest,
foot_size), for **18 verified bounded UI types**. [Android run 39318117835](https://github.com/tusecwtf/lezi/actions/runs/39318117835)
passed **5,908 tests**; [Apple run 39318117841](https://github.com/tusecwtf/lezi/actions/runs/39318117841)
passed **159 package tests plus five Simulator UI tests** (863.188 seconds total UI,
147.704 seconds growth; artifact `12218688971`). Unchanged
growth payload precision is preserved rather than copying Android's lossy
two-decimal editor refill; changed input uses the shared Core conversion rules.

The latest verified **dual-green** checkpoint is **`10016b6a`**, with **22 bounded
UI types** (including milk and temperature), native schema **3**, and dedicated
sleep storage. [Apple run 39329817431](https://github.com/tusecwtf/lezi/actions/runs/39329817431)
passed **199 package tests + seven Simulator UI tests**;
[Android run 39329817430](https://github.com/tusecwtf/lezi/actions/runs/39329817430)
passed **5,908 tests**. UI duration was 1,450.014 seconds (milk 249.255 seconds,
temperature 184.565 seconds); artifact `12225776079` contains the acceptance
evidence. Schema **1/2→3** migration preserves data atomically. Storage supports
26 ordinary no-media types plus dedicated sleep commands. Fresh schema-3 host
launch is verified, but **sleep UI is not exposed or accepted**. Database schema
and payload document schema versions are separate concepts.

Eight explicit sleep
commands handle start/completed creation, wake creation, start/completed editing,
wake update/withdrawal, and effective-wake selection. Dedicated reads batch-project
sleep and wake evidence. No family session or observer is fabricated, and **sleep
UI is not open**; custom/media writes remain unsupported. Completed creation uses
stronger two-root atomicity; closed projections cannot accidentally reopen during
start editing; replay is strict; independent wake notes are not changed by sleep
note edits. See the [offline expansion plan](ios-offline-records.md) for exact APIs
and scope differences.

**Current local, unpublished Food expansion:** baby_food, snack and drink bring
UI to **25 types**. The Food slice passed **190 Linux tests**. Its two new
Apple-only model tests and eighth UI scenario await Apple validation. The proposed
eight-class UI suite with two parallel Simulator workers is also unverified; the
seven-test green run above does not validate that parallel execution setup.
Food keeps untouched optional legacy amounts exactly (nil, empty, Chinese,
full-width and whitespace), normalizes only new/changed amounts, and requires a
parseable amount only for new/changed baby-food facts. Guidance uses the original
baby's birthday and the record's local date, with Java-compatible completed
calendar months; suggestion buttons change only content.

A separate **local schema-4 custom-storage workstream** is ready but is excluded
from this Food publication. Its combined local 195-test result is not the Food
slice's 190-test result and is not Apple or published evidence. Custom workflows
remain pending separate integration and validation; no custom UI is claimed.

Milk recents use one three-type SQLite snapshot and
keyset traversal beyond the visible page. As in Android, the history query filters
valid amount 1–999, not full optional-field validation; persistence retains its
full validation gates. Step is a global device-local AppStorage preference. This
host does not implement Android's separate day-list lastAmount launch override.

The development host remains bounded: no family, production transport, signed-device
or full-product support is implied. The local Food slice leaves manual nursing as the remaining ordinary form;
storage support does not expose that form or dedicated sleep UI. Concrete custom workflows remain
incomplete. The full product matrix stays open until each row has complete evidence.

Native stale-edit CAS and revision-overflow refusal, and opaque preservation of
nonstandard local JSON escape cases, are explicitly documented differences.
They must not be presented as proven identical Android behavior.

## 1. Meaning of parity and authority

A port is complete only when a user can perform the same supported care and family
workflows, see equivalent Chinese terminology and data, recover from the same
failures, and exchange facts with the unchanged Android client. A navigation demo,
matching screenshots, a JSON decoder, or passing mock-server tests is insufficient.

Authority order remains [README](README.md): product and cross-layer contracts,
accepted ADRs, terminology in `CONTEXT.md`, actual implementation for signatures.
Read `contracts/ui.md`, `contracts/data-model.md`, `contracts/causal-sync-wire.md`,
`contracts/sync-trusted-endpoint.md`, `contracts/local-photo-loading.md` and
`layers/features.md` together. This inventory adds acceptance obligations; it does
not redefine the frozen JSON key sets or fix discrepancies by changing Android.
Where an older prose detail conflicts with current implemented behavior, record
and resolve the discrepancy before coding; do not resurrect a retired flow. For
example onboarding uses scanning or typed server address, not a paste-invite flow.

Three categories:

- **P — behavior parity:** same user outcome, state transition, fields, constraints,
  authorization, ordering, error meaning and consistency. Must pass on both clients.
- **V — visual/interaction parity:** reproduce brand tokens, templates, screen
  hierarchy, primary controls and gestures at equivalent logical size. Platform
  fonts, safe areas and native system dialogs may differ; arbitrary redesign is not
  parity. Golden images are reviewed side by side, not compared pixel-for-pixel
  across different OS renderers.
- **A — platform adaptation:** OS APIs/distribution differ. Each adaptation needs
  explicit outcome tests and an accepted difference below. It must not silently
  remove a product capability or weaken security/durability.

All matrix rows start **NI (not implemented / no iOS acceptance evidence)**. Source
inspection is not test completion. A later skeleton or fixture parser does not
advance the whole row. Track implementation, unit/contract, integration, UI and
physical-device evidence separately. No row is “done” based on a screenshot alone.

### Source locator convention

`A/` = `app/src/main/kotlin/com/lezi/babylog/`;
`F/<feature>/` = `feature/<feature>/src/main/kotlin/com/lezi/babylog/feature/<feature>/`;
`D/` = `domain/src/main/kotlin/com/lezi/babylog/domain/`;
`M/` = `core/model/src/main/kotlin/com/lezi/babylog/core/model/`;
`U/` = `core/ui/src/main/kotlin/com/lezi/babylog/core/ui/`;
`S/` = `sync/src/main/kotlin/com/lezi/babylog/sync/`;
`DS/` = `designsystem/src/main/kotlin/com/lezi/babylog/designsystem/`.
These are real baseline paths; named functions are implementation anchors, not a
second copy of their signatures.

## 2. Navigation, styling and local experience matrix

| ID | Android route / source | Required iOS outcome and objective scenario | Class | Status |
|---|---|---|---|---|
| UI-01 | `A/MainActivity.kt`: `TopDest`, `rootChromeVisibility`, `isFullScreenPushRoute` | Five tabs in order: 记录 (`log`), 汇总 (`summary`), 成长 (`growth`), 账户 (`family`), 菜单 (`settings`). `timer*`, `search`, `export`, `calendar` own full-screen chrome. Fresh, joined-without-baby and joined-with-baby launches land in equivalent states; no transient onboarding on returning launch. | P/V | NI |
| UI-02 | `DS/Tokens.kt`, `Theme.kt`; `design/tokens.json`; UI §2 | Warm default and journal template, per-baby theme, light/dark/system, same token roles, fixed dock and Chinese labels. Capture all five tabs and confirmation sheets in warm/journal × light/dark; switch templates mid-session without changing data or timer. | P/V | NI |
| UI-03 | `A/AppHeader.kt`, `BottomNavGestures.kt`; `MainActivity.kt`: `nextSiblingId` | Nickname tap and bottom-tab long press switch babies; preserve correct age/date navigation behavior. Failure must retain valid context and avoid claiming success. Exercise 0/1/3 babies and failed persistence; only confirmed selection changes summaries/composer. | P/V | NI |
| UI-04 | `F/log/dock/LogQuickDock.kt`, `QuickRecordSlots.kt`; `M/DeviceLayoutSnapshot.kt` | Four absolute left-to-right slots plus fixed 更多; fresh slots 尿尿/睡眠/母乳/配方奶. Hand preference does not mirror slot order. Empty slots do not write or promise a record action. Every shortcut opens a composer before writing. | P/V | NI |
| UI-05 | `F/log/layout/LocalLayoutEditPolicy.kt`, `LayoutEditSession.kt`, `LayoutEditDragSession.kt`, `LayoutEdgeAutoScrollPolicy.kt` | Full-screen edit, category/within-category order, assign/swap/clear slots, hide/restore, fixed 更多, bounded hidden area, edge auto-scroll with fixed-target precedence. Drag, accessibility actions and keyboard alternatives feed one reducer. Hiding changes local layout only, never shared definitions/history. | P/V | NI |
| UI-06 | `F/log/layout/DeviceLayoutSnapshotWriter.kt`, `LayoutUndoSession.kt`, `LayoutUndoWriteTracker.kt`; `LogViewModel.kt` | Whole-snapshot persistence gates exit. Inject latest-save failure/out-of-order completion; remain editing with retry. Undo only after successful original write, exact token/before snapshot, stable expiry across view reconstruction, invalidated by subsequent intent, no false undo success on failed write. | P | NI |
| UI-07 | `M/ElderMode.kt`; `DS/Tokens.kt`, `Haptics.kt`; UI §2, §10 | Elder l1/l2/l3 follows the documented replacement scaling semantics; large targets, readable charts, non-overlap, accessible sleep status. Test VoiceOver, largest supported text, reduced motion, hardware keyboard/Switch Control and small/large supported portrait iPhones. Icons have labels; colors alone never convey status. | P/V/A | NI |
| UI-08 | `U/failure/`, `FailureExplanationDialog`; `DS/ActionStateComponents.kt` | Match four-part failure explanations and retry meaning, progress/success/blocked states and Chinese record labels; never expose raw enum names or treat failure as empty data. Inject transport/storage/permission failures at each primary action. | P/V | NI |

## 3. Care, composer, timeline and timer matrix

| ID | Android source / seam | Required iOS outcome and objective scenario | Class | Status |
|---|---|---|---|---|
| CARE-01 | `M/RecordType.kt`, `RecordPayload.kt`; `F/log/composer/QuickRecordPurposeFields.kt`, `QuickRecordPurposeTextFields.kt` | Full catalog: nursing, formula, pumped_feed, pump_express, pee, poop, both_diaper, sleep, temperature, diary, bath, walk, cough, rash, vomit, injury, medicine, hospital, height, weight, baby_food, snack, drink, head, chest, foot_size, vaccine and concrete custom items. Table-driven create/edit/read/export round trip for every type and boundary payload. No generic custom placeholder or invented milk inventory. | P/V | NI |
| CARE-02 | `F/log/composer/RecordComposerContract.kt`, `RecordComposerSessionGate.kt`, `RecordComposerCompletion.kt`, `RecordComposerDiscardPolicy.kt`; `D/CareLog.kt`: `addRecord`, `updateRecord` | Freeze first tap time, prefill type-specific fields, common note/photos/time dial, fixed reachable confirm. Opening/cancel/back does not write; dirty discard confirmation, save single-flight, errors retain draft. View-only sessions permit photo preview but no edit/delete. Test rapid confirm, baby/ACL changes and late completions. | P/V | NI |
| CARE-03 | `M/FoodAmount.kt`, `NursingConfirmInput.kt`; `F/log/composer/BabyFoodGuidance.kt`, `QuickRecordSheetControls.kt` | Formula default 5 ml step follows settings; nursing L/R/LR/RL; pee small/medium/large default medium; stool three fields all-or-none. Food full-width normalization, 半碗 rule, malformed number rejection, ≤10^6 limit, unchanged legacy amount preservation. Temperature advice/disclaimer and permission to disable follow current product, not new medical logic. | P/V | NI |
| CARE-04 | `D/carelog/RecordMutationCoordinator.kt`, `CareLogQueries.kt`, `RecordSearch.kt`; `F/log/timeline/TimelineRecordOpen.kt` | Author/Owner ACL applied at mutation time, not only hidden buttons. Save/delete/read-only and record→plan conversion preserve stable identities and photos. Recent notes scoped to baby+type. Exercise stale editor after membership revoke and another-device edit. | P | NI |
| CARE-05 | `F/log/timeline/TimelineInteraction.kt`, `LocalDayGrid.kt`, `TimelineExperience.kt`; `DS/TimelineAxis.kt`, `TimelineRailFling.kt` | Continuous absolute rail with three tracks; today [now−24h,now], historic full local day, unlimited past drag, now clamp, inertia, sticky ≥¼ selected-day rule. Date change leaves pixels stationary. Future/now/midnight, DST gap/repeat and cross-midnight hit tests share the same instant axis. Type filters survive date changes, clear on baby switch. | P/V | NI |
| CARE-06 | `D/carelog/WakeObservationCoordinator.kt`, `SleepPresentation.kt`; `M/SleepProjection.kt`; `CareLog.sleepDown/sleepUp/confirmSleep/recordWakeObservation` | SleepStart and independent WakeObservation; earliest legal provisional wake, author/Owner confirmation, overlapping starts retained as anomalies. Joined ongoing sleep older than one hour since successful sync uses uncertain dashed tail after lastSuccessAt. Test two offline caregivers recording wakes, wake-before-start rejection/handling and retained history. | P/V | NI |
| CARE-07 | `D/carelog/SourceRelationCoordinator.kt`, `SuspectedDuplicate*.kt`, `CareAggregation.kt` | Near-neighbor same-type records preserve all sources/photos; source relation is not a tombstone. Before relation pull, duplicate bounds; afterward one display-version aggregate. Medicine/vaccine names and custom identity partition groups. Test both clients recording neighboring facts, Owner display priority and subsequent relation convergence. | P | NI |
| TIME-01 | `F/timer/TimerState.kt`, `TimerScreen.kt`, `NursingTimerRemoteTransitions.kt`, `TimerBackPolicy.kt` | Left/right start/switch/pause/retry and accumulated durations, start/end record-time preference, setting to hide timer entry. Native lifecycle design must report truthful running/paused/recoverable states; no record merely from timing. Suspend/lock/resume and clock changes cannot create invented elapsed care time. | P/V/A | NI |
| TIME-02 | `F/timer/TimerViewModel.kt`, `NursingCompletionDraft.kt`, `TimerCompletionUi.kt`, `TimerServiceRecovery.kt`; `D/CareLog.completeNursing` | Freeze finish instant/durations before confirmation; edit final time/order/ml/note. Durable completionClientUuid, single-flight transaction, replay after termination without duplicate record. Durable post-save state gates timer clear, next-feed offer and exit; storage failure cannot leave ghost running timer or claim save twice. | P | NI |
| TIME-03 | `M/TimerHandoffSeed.kt`; `F/log/composer/ComposerTimerHandoff.kt`; `F/timer/TimerViewModel.kt` | Transfer baby/plan/note/amount/ordered media ownership only after timer accepts. Completion rereads current plan photos in transaction and clones independent record media identity; replay does not clone twice. Discard removes composer-owned files only. Test plan photos edited remotely while timer is open. | P | NI |

## 4. Plans, review, growth, export and local settings matrix

| ID | Android source / seam | Required iOS outcome and objective scenario | Class | Status |
|---|---|---|---|---|
| PLAN-01 | `D/careplan/CarePlanCoordinator.kt`, `M/NextFeedPlanFlow.kt`; `F/settings/calendar/CalendarScreen.kt` | Future intent for each enabled concrete type; no fact/aggregate/timer mutation before fulfillment. Calendar dates, due/overdue display, edit/delete, fulfillment candidates and related record identity. Test two offline devices fulfilling one plan, then server authority convergence without duplicate totals. | P/V | NI |
| PLAN-02 | `DS/NextFeedPlanFlow.kt`; `D/careplan/CarePlanCoordinator.kt` | After nursing/formula/pumped-feed fact, offer interval/adjusted next plan. At most one open plan from this flow per baby, stable identity on update; no fabricated amount/duration. 不提醒 leaves fact only. Test repeated offer, dismissal and post-save recovery. | P/V | NI |
| PLAN-03 | `F/settings/calendar/CarePlanReminder.kt`, `SystemCalendarSetup.kt`, `AndroidSystemCalendarPort.kt`; `D/calendar/SystemCalendarConfigurationCoordinator.kt` | Local non-exact reminder and optional system-calendar copy, graded disclosure and one reminder source. Permission denial/revocation and external-calendar disappearance do not roll back shared plan. Reboot/relaunch restores only intended side effects; deletion/clear cannot delete a newer reminder. | P/A | NI |
| REVIEW-01 | `F/summary/SummaryScreen.kt`, `SummaryAggregation.kt`, `SummaryChartAccessibility.kt`; `D/carelog/CareAggregation.kt` | Day/rolling seven-day review, feeding volume/time, sleep, excretion, temperature; food Top3+其他 and missing-amount semantics. Shared iOS aggregate implementation used by log, summary, rail, widget. Fixed-time fixtures cover future facts excluded, ongoing/cross-midnight/DST sleep clipping and duplicate bounds. Match Android outputs exactly. | P/V | NI |
| REVIEW-02 | `F/growth/GrowthScreen.kt`, `GrowthMeasurementWriteCoordinator.kt`, `GrowthReferenceCatalog.kt`; `D/growth/GrowthMeasurements.kt` | Weight and length under 2/height from 2; offline Chinese WS/T 423—2022 sex-specific P3/P50/P97, actual age, no premature correction. No default band for unknown sex, ≥7 years or excluded clinical contexts. Head/chest/foot stay ordinary facts. Compare catalog and interpolation fixtures, age boundaries, chart labels and write validation. | P/V | NI |
| REVIEW-03 | `F/search/SearchScreen.kt`, `SearchRepository.kt`; `D/carelog/RecordSearch.kt` | Search scoped baby, Chinese type/custom historical names, note/text and same composer/viewer. A failed refresh may retain only successful same-query/baby/ACL snapshot and force read-only. First error is not empty success; query/baby/identity change invalidates old result and late response. | P/V | NI |
| REVIEW-04 | `F/export/ExportScreen.kt`, `ExportFileGenerator.kt`, `PdfExport.kt`, `ExportCacheCleanup.kt`; `D/export/ExportPort.kt` | Same selectable content/range and TXT/PDF semantics, Chinese typography, attachments and sleep/plan/source labels per export contract. System sharing with temporary file lifetime/cleanup; no permanent public file or silent upload. Compare normalized TXT content and PDF visual pages for all record types, long notes and photos. | P/V/A | NI |
| SET-01 | `F/settings/SettingsScreen.kt`, `record/RecordSettings.kt`; `core/datastore/.../SettingsStore.kt` | All implemented recording/display settings including formula steps, enabled types, timer visibility, next-feed interval, date/age/time/unit display, theme/template/elder/handedness. Enumerate actual SettingsStore keys before implementation; values persist/restart and remain device-local rather than family-synced. Test two clients with deliberately different preferences. | P/V | NI |
| SET-02 | `D/catalog/CustomItemCatalog.kt`; `U/CustomItemManageDialog.kt`; `F/log/layout/LayoutCustomManage.kt` | Create/rename/icon/order/delete up to 10 shared definitions; permissions preserved. Historical record title/icon snapshot unchanged after definition edit/delete; local visibility/order and dock remain independent. Test offline concurrent definition changes and old record export. | P/V | NI |
| SET-03 | `F/settings/record/ClearRecordsCopyPolicy.kt`, `command/SettingsCommandState.kt`; `D/localdata/LocalDataClearCoordinator.kt`; `S/clear/LocalReplicaClearCoordinator.kt` | Two-step local record/plan/candidate/media/reminder clear retains babies, definitions and family session. Joined copy explicitly warns server retains data and resync may restore it. Epoch/barrier prevents concurrent writes/sync races. Partial calendar cleanup must remain retryable, never false success; durable restart recovery continues same operation. No exposed all-data wipe added. | P/V/A | NI |
| WIDGET-01 | `F/widget/CareWidget.kt`, `WidgetStateStore.kt`, `WidgetSummarySource.kt`, `WidgetComposerContract.kt`, `WidgetConfigurationActivity.kt` | Each instance binds its own baby and shortcuts, shows nickname and recent feeding/sleep/excretion using same aggregate rules. Tap opens valid composer with confirmation, never writes directly. Test two widget instances, deleted baby/type, stale snapshot, locked device and fresh install. Home-screen privacy intent preserved; no automatic lock-screen exposure. | P/V/A | NI |

## 5. Family, trust, synchronization and recovery matrix

| ID | Android source / seam | Required iOS outcome and objective scenario | Class | Status |
|---|---|---|---|---|
| FAM-01 | `F/onboarding/OnboardingScreen.kt`, `OnboardingViewModel.kt`; `D/family/FamilyWizardController.kt`; `F/family/wizard/AccountFamilyWizardHost.kt` | New-baby/family entry, typed HTTPS endpoint or scanned member-login QR, root administrator setup/login, staged first-baby completion. Back/retry/session expiry preserve correct step. Never leak root credential into normal sync or treat device session as membership. | P/V | NI |
| FAM-02 | `S/session/`, `S/qr/`; `U/memberloginqr/`; `SyncPort.probeEndpoint/trustCertificate/claimMemberLoginQr` | Trust candidate fingerprint confirmation, HTTPS origin/SPKI identity, strict QR payload plus landing_url wrapper, single-use and expiration. Reject plaintext family APIs, tampered QR, trust change, wrong-family reconnect and malicious external navigation; no SSID/location-based trust. | P/A | NI |
| FAM-03 | `F/family/overview/AccountPageContent.kt`, `FamilyOverview.kt`; `SyncPort.renameFamily/updateMyDisplayName` | Family/name/Owner star, shallow sync state, pending badge and retries, baby section and family network access. Server-provided request expiry is displayed honestly; local clock cannot declare remote expiry. Family rename propagates through server watermarks. | P/V | NI |
| FAM-04 | `F/family/members/MembersDevicesHost.kt`, `MembersDevicesDialogs.kt`; `SyncPort` login approval, rename, device methods | New-member approval, bind existing member, reject/cancel request, member QR login, multiple independent devices, member/device renaming, rename approvals, revoke/logout/leave/remove/delete according to Owner/member ACL. Test iOS Owner with Android members and inverse, one device revoked while same member's second remains valid. | P/V | NI |
| FAM-05 | `F/family/baby/`, `overview/BabyProfileAvatarSave.kt`; `D/family/BabyFamilyProfileCoordinator.kt`, `BabyLocalLayoutCommands.kt` | Admin-only shared nickname/sex/birthday/birth weight/avatar/create/delete/merge behavior; local theme/order/current selection separately immediate. Dirty profile discard, busy save lock, failed-save draft/owned-avatar retention, duplicate nickname validation. Member cannot mutate shared Baby through crafted requests. | P/V | NI |
| FAM-06 | `F/family/networksettings/FamilyNetworkSettingsHost.kt`; `SyncPort.probeReconnectEndpoint/reconnectOwner/requestReconnectMember` | Verify candidate endpoint and rejoin only same family, preserve local care replica and honor certificate trust. Network settings refresh is authenticated heartbeat; availability is not authorization. Test changed address same server, wrong family, expired session and unreachable server without data loss. | P/V | NI |
| SYNC-01 | `S/SyncPort.kt`, `RealSyncPort.kt`, `backend/HttpSyncBackend.kt`, `engine/ReplicaSyncEngine.kt` | Exact protocol/capability/principal/session handshake before directory/mutation. Foreground/refresh pulls then commit-first; LocalWrite push-only never advances pull cursor. Offline write commits locally then notifies. Entering background cancels network/heartbeat. Fail closed on unknown closed keys/enums and mismatched generation. | P | NI |
| SYNC-02 | `S/engine/SyncWireMapper.kt`, `PendingPublishInspector.kt`; `M/RecordPayload.kt` | Stable client UUIDs and canonical payload/hash/base revision semantics; no local row id/path crosses wire. Atomic Baby+avatar, Record+photos, CarePlan+photos, WakeObservation and fulfillment units. Lost response/restart replays same frozen envelope; accepted/merged/branched settle correctly. | P | NI |
| SYNC-03 | `S/heartbeat/SyncHeartbeatEngine.kt`, `ForegroundRoundFuse.kt`; `S/availability/` | Existing 60s baseline/300s quiet cap ±20% jitter, 8s foreground debounce, capability discovery/404 stop and three-key `!=` comparison; no-progress fuse prevents infinite work. Remote updates visible within documented cadence plus a round, not promised instantaneous. Deterministic-clock tests cover rollback watermarks and identity reset. | P | NI |
| SYNC-04 | `S/engine/ReplicaSyncEngine.kt`; wire pull/census contracts | Pagination with atomic page application, no cursor advance on failed media or invalid references, replay across pages, same-page duplicate rejection, generation reset, bounded cursor-zero live-census repair and durable receipts. Inject disconnect after page commit and process termination before checkpoint; converge without drops/duplicates. | P | NI |
| SYNC-05 | `F/family/conflict/ConflictInboxRoute.kt`, `ConflictResolverRoute.kt`, `LocalUnresolvedResolverRoute.kt`; `D/carelog/ConflictResolutionCoordinator.kt`; `S/conflict/` | Full N-way immutable branch snapshots, canonical preservation, choice-only resolution and ACL. Stale/expired token requires new snapshot, never blind retry. Pending badge includes conflicts and actionable local misalignment; local-only dismissal never fabricates conflict_id or tells server to delete. Test 3-way conflict, delete-vs-edit, withdrawal and concurrent resolution. | P/V | NI |
| MEDIA-01 | `F/log/photo/BoundedRecordPhotoImporter.kt`, `RecordPhotoStore.kt`; composer photo lifecycle; `DS/AndroidLocalPhotoLoader.kt`; `M/RecordPhotoResourcePolicy.kt` | All record types support up to 3 new photos; private copies, bounded oriented thumbnail/full-screen decoding, swipe/remove, cancellation ownership and failure placeholders. Save/import serialized. Test camera/picker cancel, huge/rotated/corrupt image, permission denial, save during import and late result after session closes. | P/V/A | NI |
| MEDIA-02 | `S/media/ImmutableMediaSpool.kt`, `MediaContentIdentity.kt`; `S/engine/ReplicaSyncEngine.kt`; local-photo contract | Record+complete media atomic visible publication. Verify SHA-256/length with bounded streaming, 10 MiB receive limit, durable ownership markers, fsync/close before DB reference, referenced-file protection and bounded orphan recovery. Interrupt upload/download/commit and kill app; never show partial bundle or remove referenced media. | P | NI |
| RESTORE-01 | `S/disasterrecovery/`; `SyncPort.prepare/start/resume/commit/cancelDisasterRecovery` | Existing Owner's complete replica may restore only verified empty server: prepare→start→resume→commit/cancel, secure recovery credential, direct-base causal semantics and all media. No merge of configured families. Test resumed upload, missing media, occupied target and wrong credentials on isolated instance; other Android/iOS client then joins rebuilt family. | P/V | NI |
| RESTORE-02 | `A/LocalDataUpgradeModule.kt`, `LocalDataSnapshotStore.kt`, `LocalDataRecoveryScreen.kt`; `PersistentSideEffectRehydrator.kt` | iOS own versioned local store with checked backup/migration/recovery gate before app, reminders or widgets read. Preserve data across every released iOS update; Android Room files are not iOS migration input. Fail closed on disk-full/future/inconsistent storage; never clear data to make launch succeed. | P/A | NI |
| REL-01 | `S/appupdate/`; `tools/lezi-sync/src/lib.rs`, `handlers/app_update.rs`; Android compatibility config | Independent iOS bundle/signing/build/channel policy, Android APK channel unchanged, safe unsupported/update-required UX. Numeric version-floor obstacle in §7 resolved before claiming production compatibility; never install APK or spoof Android release identity. | P/A | NI |

## 6. Native implementation boundary and accepted-difference register

Proposed implementation is isolated under `ios/`: Swift domain/value types and
state reducers, transaction-backed private storage, Swift sync transport/engine,
SwiftUI product UI and native integration adapters. UI and widgets must share the
same iOS domain implementation, not duplicate calculation rules. Apple credentials,
provisioning profiles and generated builds remain outside tracked source.

| Surface | Required adaptation | Parity condition / unresolved decision |
|---|---|---|
| Android foreground timer service/notification | Native timestamp/session persistence and iOS-supported presentation; evaluate Live Activities separately if desired | Do not assume unlimited background execution or an Android-style FGS. Define suspend/termination recovery explicitly, preserve completion/duration correctness and truthful state. No invented running acknowledgement. Product review and physical-device evidence required. |
| Local calendar/reminders | Native calendar and local-notification adapters | Same disclosure, denied/revoked permission handling, single reminder source, owned-event cleanup and restart guarantees. OS delivery timing is not exact and cannot be promised. |
| Widgets | WidgetKit extension, shared safe snapshot store and native deep link | Same per-instance baby/shortcut semantics and confirmation-only writes. OS refresh budget differs; display freshness honestly. No background family-sync loophole or lock-screen exposure by default. |
| Camera/photos/share/PDF | Native picker/capture/share sheet and bounded image/PDF APIs | Same private-copy ownership, cancellation, accessibility and data output. OS chrome and exact typography may differ, domain payload does not. |
| Trust and secrets | Platform TLS challenge/pinning and protected credential storage | Same user-confirmed endpoint/SPKI and scope; never global trust bypass, arbitrary certificate acceptance or a broad plaintext exception. Local-network permission must have an honest explanation and denied-state recovery. |
| Navigation/fonts/input/haptics | SwiftUI custom product chrome plus native safe-area/input/accessibility integration | Same screen hierarchy, four-slot dock, time interaction and exit/save rules. OS back gestures must run the same discard guards. Minimum product hit targets retain ≥48 logical-unit intent, enlarged elder controls; reduced motion and system haptic choice respected. |
| App updates/invite first install | Approved Apple distribution route, to be selected | Android LAN APK installer cannot ship an iOS app. Existing QR authentication can be shared; its Android first-install landing page cannot be presented as iOS installation support. Keep current Android flow untouched. |

Minimum supported iOS version, supported iPhone sizes, bundle ID/team, distribution
channel and notification/widget entitlements are **open release choices**, not
facts already established by Android code. Verify current Apple requirements from
official documentation at implementation/release time. No claimed App Store,
TestFlight, signing or physical-device validation is implied by this plan.

## 7. Release identity and compatibility gate

The current server uses `x-lezi-client-version-code` in
`tools/lezi-sync/src/lib.rs` (`parse_client_version_code` and the minimum-version
gate). The effective minimum is tied to verified Android APK metadata in
`handlers/app_update.rs`; APK package/signature/version/hash verification is an
Android release guarantee, not a generic client compatibility registry. The frozen
handshake accepts only `causal_sync_v2`, whereas setup-status capabilities have
incremental semantics. These two capability lists must not be conflated.

An iOS build number is not automatically an Android versionCode. Hardcoding today's
Android code, reporting an arbitrarily huge number, or adding `platform: ios` to a
closed handshake just to pass the server gate is not an acceptable release design.

Before production enrollment, choose and document one reviewed compatibility policy:

1. A genuinely platform-neutral protocol compatibility level supported by the
   unchanged server, with explicit mapping for every iOS release and correct
   behavior when the Android-backed floor changes; demonstrate this is sufficient
   without falsely claiming APK identity; or
2. A separately authorized additive server release/version policy that distinguishes
   platform channels without changing accepted Android requests/responses. Keep
   the existing Android floor and APK guarantees intact and prove old Android
   interoperability before rollout. Any wire/closed-key/floor change uses AGENTS
   proof class E and coordinated release, not an incidental port edit.

Neither option is approved or implemented here. Current-server transport experiments
must be clearly labeled isolated tests and must not become a production bypass.
The library/codec scaffold can proceed while this gate is open; claiming the iOS
client is fully compatible or ready to use with the family NAS cannot.

## 8. Test architecture and evidence

### 8.1 Shared contract fixtures, independent implementations

Consume existing `config/conflict-v2-golden.json`, its schema and
`config/next-feed-plan-marker.v1.json` without changing Android expectations or the
historical frozen fixture identity. Build additional reviewed language-neutral
fixtures under an isolated iOS test tree first. Every fixture records input,
fixed clock/timezone, authority, expected output/error and source contract. Needed
sets: all record payloads, canonical JSON/number/null behavior and hashes, UUID
references, sleep/wake, fulfillment, source relations, media manifests, ACL,
aggregation, dates/DST, growth tables, layout reducer and error classification.

Swift tests must read the same golden bytes as Kotlin/Rust tests, including malformed
closed-key/capability cases; hand-transcribing a convenient subset is insufficient.
Differential tests compare semantic results and canonical bytes where the wire
requires them. Room schema equality or shared code is not required; wire/domain
outcome equality is. Never serialize Swift-local IDs or file paths.

### 8.2 Real isolated three-participant acceptance

Use this tree's Rust binary with a fresh `mktemp` data root, isolated loopback/LAN
test port, synthetic family and generated test TLS certificate. Pin exact server
and Android commit/build identity in the report. Reuse the discipline of
`IsolatedLeziSyncServer`, `RealServerQuietRoundSmokeTest`,
`RealServerMediaReceiptFaultSeamTest` and `CareLogRealServerSeam*`; their existing
Android passes alone do not prove iOS works.

Participants must include a real Android client/harness using the current Android
implementation and a real iOS client/harness using production Swift sync and
storage. For N-way tests add a third independent replica/session. A Swift mock
pretending to be Android is not mixed-client proof. Run both creation directions:
Android Owner→iOS member and iOS Owner→Android member.

Mandatory scenarios and observable pass conditions:

1. Android creates every entity/payload/media combination; iOS receives and edits
   permitted fields; Android receives it back with identical stable IDs, facts,
   digest references and aggregate outputs. Repeat reversed.
2. Both offline create/edit/delete, then reconnect in both orders. Compare live
   entity sets, canonical projections, causal branches/receipts and pending counts;
   no dropped or duplicated facts, and quiet rounds converge to zero pending units.
3. Concurrent same-UUID three-way edit, delete/edit, stale resolution and source
   alignment; both UIs show equivalent options, ACL and eventual projection.
4. Two caregivers sleep/wake and fulfill the same plan; verify authoritative
   outcome, retained observations/candidates/photos and no double counting.
5. Disconnect/timeout after upload, commit acceptance, pull page persistence and
   before acknowledgement. Restart both clients and retry; idempotency holds,
   no partial bundles become visible, cursor never skips uncommitted data.
6. Revoke one device, expire/refresh session, rename family/member, change endpoint
   and server generation; unauthorized writes fail, other devices remain valid,
   recovery is bounded and visible. No background requests after suspension.
7. Restore a full Owner replica into another empty isolated server, resume after
   kill, then join both platform clients and compare complete care/media data.
   Nonempty target is rejected before destructive mutation.
8. Exercise verified update-floor metadata absent/present/raised and handshake
   mismatch. Verify reviewed iOS policy and unchanged Android update behavior;
   a passing low-floor setup alone is not release compatibility evidence.

Certificates/TOFU replacement, expiry and mismatch tests **never** touch the family
NAS, production container, TLS pair or data root. No NAS deploy, migration,
container replacement, credential rotation or public VPS work is authorized by
this plan. No production family data in fixtures, logs, screenshots or CI artifacts.

### 8.3 macOS CI and manual iPhone gates

CI in `.github/workflows/ios-core.yml` now tests the Swift package, compiles the
Apple library code, builds the unsigned SwiftUI development host and runs its
bounded Simulator UI scenarios. The `10016b6a` checkpoint above has green Apple
and Android evidence for 22 offline forms plus schema-3 sleep storage, with no
sleep UI. The local 25-form Food expansion and eight-class/two-worker UI execution
setup still await publication and Apple CI. This does not validate the complete app, widget extension,
signed device installation or real mixed-client sync. The complete application
gates below remain required; later unverified code cannot inherit this result.

A macOS runner with a pinned reviewed Xcode toolchain must build the actual app and
widget targets, execute Swift unit/golden/storage/transport tests and simulator UI
tests, then run real-server integration tests. Record toolchain, simulator OS,
server/Android/iOS revisions, commands, exit codes and artifact links. Linux parsing
or Swift package compilation is useful but cannot prove UIKit/SwiftUI/WidgetKit
builds or signed-device behavior. Signing/distribution credentials are a separate
operator setup gate, never committed.

Physical iPhone checklist: install/update without losing existing data; warm/journal
and dark/elder modes; local-network prompt denied/granted; camera/photo/calendar/
notification permissions denied then restored; lock/suspend timer then resume;
force-quit/relaunch during save/import/sync; two widget configurations; background
network cancellation; certificate mismatch; airplane-mode edits; VoiceOver and
largest text; mixed Android+iPhone family; share TXT/PDF through system sheet.
Collect synthetic-data screenshots and transition recordings for UI-01 through
REL-01, including failure states. Emulator/simulator success does not check these
physical-device gates off.

### 8.4 Completion rule and implementation order

1. Inventory/signoff and release-identity decision; no Android refactor.
2. Native project foundation, private transactional storage and contract fixtures.
3. Complete family/trust/sync/media vertical slice against isolated real service.
4. Care composer, plans, sleep/timer and deterministic aggregation; mixed-client
   destructive/retry/concurrency scenarios before relying on real family usage.
5. All remaining screens, local preferences, widgets/system integration and full
   visual/accessibility parity; no permanent “later” feature exclusions.
6. macOS CI, physical iPhone acceptance, release policy and user review.

A row becomes accepted only with reviewed implementation and reproducible relevant
unit, real-service and UI/device evidence. Track any platform difference explicitly,
with the same outcome or an owner-approved revised contract. “Not supported on iOS”
is a blocker, not completion. Android baseline/regression evidence must show no
unrequested source or behavior changes. A future Android release requires a new
parity diff against its source/specs; this snapshot inventory must not silently be
called “latest” forever.
