# 42 — 验收冲突设备交互

**What to build:** 在设备/模拟器上验收家庭 badge/inbox、五类根、Record 上下文、分页、offline/freshness、ACL 与可访问提交状态。

**Blocked by:** 07、09、41

**Status:** implemented (local and API 35 device interaction acceptance pass)

## Contract slice

这里的“旋转”仅指 Android configuration change/屏幕旋转，不是 TLS certificate rotation。UI 使用隔离服务/fixture，不连接家庭 NAS。

## Implementation sequence

1. 安装当前 debug/release candidate 并注入五类根/tombstone conflicts。
2. 遍历家庭 badge/inbox 与 Record contextual route。
3. 验证分页、offline、stale refresh、configuration change。
4. 验证 author/Owner/other ACL、labels、focus 与 disabled submit。

## Acceptance

- [x] badge/count/list/detail/choice 流程可操作
- [x] provenance/media/deleted/freshness/error 文案可访问
- [x] offline/incomplete/stale/unauthorized 不可提交
- [x] configuration change/process recreation 不丢合法状态

## Validation

- [x] H42 public-state matrix 与最小 Compose acceptance source 已加入
- [x] Compose connected/device tests 与截图/交互 receipts 完整
- [x] 记录 API/device/APK 与隔离 fixture 边界

## Evidence

- Base: `93de09a1cd3d19df94205a9c1c7be412b68b5df0`; H42 feature/test commit:
  `0d81dbb6` (`test(family): add H42 conflict device matrix`). Only two H42-owned
  acceptance sources changed; no production UI, domain, sync, server, or H43 files changed.
- Existing H07/H09 device sources were confirmed as the authoritative detail/choice/freshness
  coverage: `ConflictInboxDeviceTest` covers five roots, tombstone/media/actor fallback,
  count/list and conflict-ID routing; `ConflictResolverDeviceTest` covers complete detail,
  provenance/media/auto-merge, explicit choice, offline/unauthorized disabled submit, and
  stale/refresh/error evidence. Existing `app/ConflictOverlayStateTest` proves Family inbox
  and Record contextual entry both open the same canonical resolver overlay by conflict ID.
- New `H42ConflictAcceptanceMatrixTest` adds the missing public-state contract: incomplete
  paged snapshot is rejected before resolver entry; only complete online authorized state with
  every choice selected can submit; offline/expired/unauthorized/terminal-error states remain
  read-only; selected choice and resolution mutation identity survive SavedState recreation.
- New `H42ConflictDeviceAcceptanceTest` adds the smallest connected source: one joined-family
  badge count of five opens the inbox, and the same five Baby/Record/CarePlan/CustomItem/
  WakeObservation rows preserve one-item-per-root count, stable tombstone/deletion labels,
  actor/media availability labels, accessibility descriptions, and conflict-ID clicks.
- Gates run:
  - `./gradlew :feature:family:testDebugUnitTest --tests com.lezi.babylog.feature.family.conflict.H42ConflictAcceptanceMatrixTest :feature:family:compileDebugAndroidTestKotlin --no-daemon` — PASS (3/3 JVM tests; AndroidTest compile).
  - H42-related existing JVM regression matrix across `:feature:family`, `:app`, `:domain`,
    and `:sync` (conflict host/session/attempt/badge, shared overlay, inbox/presentation/draft,
    and snapshot paging) — PASS; `BUILD SUCCESSFUL`.
  - `git diff --check` — PASS.
- `adb devices -l` (escalated read-only probe) — `List of devices attached` with no devices.
- **Historical residual closure:** the prior no-device limitation is closed by the API 35
  receipts below. Family NAS/CD and TLS cutover remain outside H42.

### 2026-08-13 device closure

- API 35 direct instrumentation ran `H42ConflictDeviceAcceptanceTest`,
  `ConflictInboxDeviceTest`, and `ConflictResolverDeviceTest`: `OK (6 tests)`.
  The receipt covers the joined-family badge → same five-root inbox transition, conflict-ID
  routing, tombstone/media/actor accessibility descriptions, full stable/branch provenance,
  explicit choice before submit, and offline/unauthorized/freshness/error read-only states.
- `4df9b4ea` corrects Compose test semantics only: content-description checks explicitly use
  substring matching and each test owns one composition while state changes. No product UI
  or conflict policy was changed.
- `c8c8baf0` makes the minimal acceptance source persist reproducible screenshots. The API 35
  run produced and visual inspection accepted:
  `h42-family-conflict-badge.png` (1080×126, SHA-256 `6df85bf9…127e`) and
  `h42-five-root-conflict-inbox.png` (1080×1465, SHA-256 `758ed927…5030`). The PNGs remain
  ephemeral test evidence under `/tmp`; no generated image was committed.

## Out of scope

不做视觉重设计、TLS rotation 或生产 smoke。
