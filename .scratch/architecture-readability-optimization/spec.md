# Spec: 架构可读性优化（审查面压缩）

**Status:** ready-for-agent

Feature: `architecture-readability-optimization`  
Architecture review HTML: `/var/tmp/zhangtianshu-tmp/architecture-review-20260801180255.html`  
Grill lock date: 2026-08-01  
Validation HEAD at publish: `0aa225bf25b21315dec3a84983124086ff9858d1`

## Problem Statement

维护者与 agent 审查乐记时，大量时间花在**无产品契约价值的结构守卫**、**账户页五流混装的浅 interface**、以及 **SyncPort/tech.md 上已死或过时的表面**上。这些不直接造成用户可见错误，但抬高每次改动的阅读成本，并与「interface 即测试面」原则冲突。

并行架构巡检（deep-module 词汇 + 对抗核验）确认：多数「god-module 大拆」指控不成立（`SyncPort`/`RealSyncPort`/`LogRoute`/`Store` 仍是 deep façade 或有意 composition root）。真正值得做的是**删除无契约测试、按调用流切开账户 host、把 QR 沉入已有向导 seam、修剪死方法、对齐文档边**。

## Solution

交付一个**只动 Android 审查面**的可读性程序（用户确认范围 **A**）：

1. 删除全部 7 个 `*StructureTest`（源码字符串/行数上限，无 PRD 行为）。
2. 成员 QR 四操作沉入 `FamilyWizardController`；账户与引导错误文案统一 `familySyncError`。
3. `FamilyViewModel` 按调用流拆为 **Overview / MembersDevices / Wizard(+QR)** 三缝；宝宝档案与 app-update 保持薄委托，不新造 port。
4. 从 `SyncPort` 删除生产零调用的 `isEnabled` / `saveServer` / `pull` / `push`；不拆 `AppUpdatePort`。
5. `docs/prd/tech.md` 模块依赖边与 Gradle 真相对齐；去掉不存在的 `core:image`。
6. 清理 `FakeSyncBackendTest`、`RecordSettingsMenuTest` 反射段、`UiPlaceholders`。

**不进本批：** `lezi-sync` `lib.rs`/`store.rs` 私有文件拆分、server retired 422 路由、NAS CD、版本 bump。

## User Stories

1. As a maintainer, I want StructureTests gone, so that refactors are not blocked by filename/line-count guards.
2. As a maintainer, I want account code split by screen flow, so that changing 成员/设备 does not require loading wizard and app-update commands.
3. As a maintainer, I want one FamilyWizardController QR path, so that claim/verify bugs are fixed once for 账户 and 引导.
4. As a caregiver using 引导 or 账户, I want the same human-readable family-sync error when QR login fails, so that messaging is consistent.
5. As a maintainer, I want SyncPort to omit dead pull/push/saveServer/isEnabled entrypoints, so that reviewers do not treat them as product paths.
6. As a maintainer, I want tech.md dependency edges to match Gradle, so that doc-based review does not flag false wrong edges.
7. As a maintainer, I want FakeSyncBackendTest and reflection rename guards removed or rewritten, so that tests only defend shared contracts.
8. As a maintainer, I want UiPlaceholders eliminated if unused or inlined, so that placeholder noise leaves the review surface.
9. As an agent implementing tickets, I want explicit non-overlap with `full-codebase-audit-20260801-remediation` tickets 16/17/18, so that two trackers do not fight.
10. As a reviewer, I want app-update to remain on the family-server seam, so that PRD tech.md §4.2 and trusted session/TLS are not reopened.
11. As a maintainer, I want NoOp\* defaults either internal or test-scoped where practical, so that main source does not look like alternate production DI.
12. As a caregiver, I want no behavior change to 护理记录 / 护理计划 / 同步状态 / 账户概览 product rules, so that this program is structure-only.
13. As a maintainer, I want CareLogTest and existing behavior tests to remain the contract surface after StructureTest deletion.
14. As a maintainer, I want MembersDevices host to own roster refresh and member/device commands only.
15. As a maintainer, I want Overview host to own 账户概览 projection, one-line 同步状态, and optional update banner entry only.
16. As a maintainer, I want baby profile avatar I/O to stay a thin adapter, not a fourth deep port in this batch.
17. As a maintainer, I want lezi-sync private locality deferred to a Later note, so that NAS CD risk stays out of this program.
18. As an agent, I want each ticket to skip inventing new Gradle modules unless required for the three hosts.
19. As a maintainer, I want feature→feature dependency still forbidden after the split.
20. As a maintainer, I want `pull`/`push` to remain on SyncBackend internal seam only after SyncPort prune.

## Implementation Decisions

### Grill locks (2026-08-01)

| # | Decision | Lock |
|---|----------|------|
| Q1 | Program scope | **A** Android review surface; lezi-sync file split = Later |
| Q2 | FamilyViewModel split | **A** Overview / MembersDevices / Wizard+QR three seams |
| Q3 | StructureTests | **A** delete all seven |
| Q4 | SyncPort prune | **A** remove `isEnabled`, `saveServer`, `pull`, `push`; keep `cleanupAppUpdateStaging`; NoOp internal or test |
| Q5 | QR errors | **A** both entrypoints use `familySyncError` by default |
| Q6 | Speculative cleanup | **C** include FakeSyncBackendTest, RecordSettingsMenuTest, UiPlaceholders |

### Architecture vocabulary

Use **module / interface / implementation / depth / seam / adapter / leverage / locality**. Do not introduce fake seams (one adapter only). Deletion test before adding modules.

### Domain vocabulary

Keep CONTEXT.md terms: 家庭向导, 账户概览, 同步状态（概览）, 家庭 membership, 家庭设备, 邀请家人 (QR), 离线模式. Do not rename product concepts in this program.

### Coordination with `full-codebase-audit-20260801-remediation`

| Audit ticket | Relationship |
|--------------|--------------|
| **16** remove dead familyId pull/push | **Same seam as ticket 04 here.** Implement once. Prefer this tracker’s 04 if claimed first; audit 16 becomes complete-by-duplicate with pointer, or 04 defers if 16 already merged. |
| **17** align module dependency docs | **Same seam as ticket 05 here.** Same once-only rule. |
| **18** split SyncPort by capability | **Out of scope / narrowed.** Grill + adversarial review rejected kitchen-sink split and AppUpdatePort extraction. This program only prunes dead methods. Leave audit 18 open only if product later reopens full capability split; do not implement 18’s large expand–migrate–contract in this tracker. |

### Modules touched

- `domain`: `FamilyWizardController` (+ gateway types as needed); no CareLog behavior change.
- `feature/family`: split hosts; screen wiring.
- `feature/onboarding`: thin host calling shared wizard QR ops; error mapping.
- `sync`: `SyncPort` / `RealSyncPort` / NoOp\* / tests for removed methods.
- `feature/settings` or callers of removed SyncPort methods: compile fix only.
- `core/ui`: `UiPlaceholders`.
- `docs/prd/tech.md`: dependency edge list + drop phantom `core:image` if still listed.
- Tests: delete seven StructureTests; adjust FakeSyncBackendTest / RecordSettingsMenuTest.

### Family three-seam shape

1. **AccountOverviewHost (name flexible)** — 账户概览 read model, pull-to-refresh/sync status projection, optional app-update banner **entry** (install/check may still call SyncPort).
2. **MembersDevicesHost** — list/refresh members & devices, approve/reject/bind, rename, revoke, remove member, QR **create** for invite (admin), leave/logout/delete family commands that belong on that page.
3. **FamilyWizardController (existing)** — connect/trust/keep offline/forget + **verify/claim/cancel/retry member login QR**. Account and Onboarding hosts only launch + map errors.

Do **not** add `FamilyMemberManager` that only forwards the same 16 methods (shallow). Prefer fewer public commands per host.

Baby profile edit + avatar file transactions: remain callable from overview or existing dialogs as **thin** methods or a tiny helper; not a new Gradle module.

### SyncPort prune

Remove from public `SyncPort` (and RealSyncPort / NoOpSyncPort / fakes/tests):

- `isEnabled()`
- `saveServer(baseUrl)`
- `pull(familyId)`
- `push(familyId)`

Keep app-update surface on SyncPort per PRD. `cleanupAppUpdateStaging` stays. Internal engine continues to use `SyncBackend.pull/push`.

NoOp classes: prefer `internal` and/or move to `src/test` when DI/default-parameter mechanics allow without new seams.

### StructureTests to delete

- `domain/.../RecordMutationCoordinatorStructureTest.kt`
- `domain/.../CarePlanCoordinatorStructureTest.kt`
- `domain/.../BabyFamilyProfileCoordinatorStructureTest.kt`
- `domain/.../CareLogQueriesStructureTest.kt`
- `feature/log/.../LayoutEditHostStructureTest.kt`
- `feature/log/.../LogScreenStructureTest.kt`
- `feature/log/.../RecordComposerStructureTest.kt`

### Later (explicit non-tickets)

- lezi-sync `lib.rs` / `store.rs` / `model.rs` private submodule locality (keep deep façade).
- retired ordinary push/media 422 route removal (ADR-0008 adjacent; needs wire policy pass).
- Audit ticket 18 full capability port split.

## Testing Decisions

- Good tests defend **observable contracts** through the module interface (wizard outcomes, member command results, sync trigger behavior, error mapping), not file paths or line caps.
- After StructureTest deletion, rely on existing `CareLogTest`, layout/composer behavior tests, family policy tests, `FamilyWizardController` / gateway tests.
- Add/extend tests for: QR ops on `FamilyWizardController` (success/failure, cancellation); unified `familySyncError` mapping on both hosts; Overview vs MembersDevices command surfaces do not require the other host for their page-level actions; SyncPort compile + tests updated for removed methods.
- Prior art: `FamilyWizardController` tests, `AccountFamilyWizardAdapterTest`, `FamilyErrorCopyTest` (behavior parts), `RealSyncPortTest`, `ContractSupersededSurfacesTest`.
- Do not add new StructureTests.

## Out of Scope

- lezi-sync runtime/private file splits and NAS deploy.
- Product behavior changes to 护理记录, 护理计划, 计划履行, atomic media bundles, trusted endpoint rules.
- Creating `AppUpdatePort` or splitting SyncPort into many capability ports (audit 18).
- feature↔feature Gradle dependencies.
- Version bumps, release APK, Play flavor work.
- Deleting `prototype/` or `backup/` trees.
- Rewriting `CareLog` façade or coordinator extraction (already done).

## Further Notes

- Review artifact (not in git): `architecture-review-20260801180255.html` under TMPDIR.
- Top recommendation order from review: StructureTests → Family flow split + QR → SyncPort prune + tech.md → (later) lezi-sync locality.
- Execution: prefer serial on family/sync files; 01 and 06 can parallel with 04/05 if ownership is clear.

## Global acceptance gates

- `./gradlew test` relevant modules + `:app:assembleDebug` + `lintDebug` after structural tickets.
- No new `*StructureTest` files.
- tech.md edge table matches `feature/*/build.gradle.kts` sync deps.
- Tracker complete only when tickets 01–06 are done or explicitly superseded by audit duplicates with comments.
