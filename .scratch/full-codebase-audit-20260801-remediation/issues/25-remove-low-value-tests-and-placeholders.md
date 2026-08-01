# 25 — 清理低价值测试与占位 API

**What to build:** 测试列表与 core UI 不再出现只测 fake 自身、源码 rename detector、
反射实现落点或无调用方占位常量；保留真正守公共合同的回归。

**Source:** merged readability 06
**Blocked by:** None — can start immediately
**Status:** done
**Size:** S–M

## Acceptance criteria

- [x] `FakeSyncBackendTest` 删除自证 fake 的断言，或改为同一 `SyncBackend` contract suite 的真实价值测试。
- [x] `RecordSettingsMenuTest` 删除反射/源码 rename detector；若存在用户菜单合同，以 public seam 行为测试表达。
- [x] `UiPlaceholders` 无生产调用则删除；只有单一调用时内联并移除噪音 API。
- [x] 不删除 failure、并发、恢复或用户可观察行为测试；无坏引用和替代 StructureTest。

## design_notes (seams)

Public seams kept (not rewritten as structure guards):

- `SyncBackend` / production path contracts remain in `HttpSyncBackendTest`, `RealSyncPortTest`,
  `AtomicMediaBundlePublisherTest`, `RefreshingSyncBackendTest` (use Fake only as double).
- Thin `FakeSyncBackendTest` fidelity suite pins double drift for equal-updatedAt LWW, member
  avatar ACL + ref integrity, fulfillment freeze vs forged stamps, dual-client stage→commit
  package isolation, and canonical author stamping — not self-tautology rename/wire-only cases.
- Settings record knobs: `RecordAndShortcutSettingsTest` calls `core.ui` /
  `RecordType.availableForNewEntry` directly (no production-main test helpers);
  dialog is single-page `RecordSettingsDialog` → `PerItemSettingsBody` + `PlanCalendarSettingsBody`
  (no multi-destination hub left to test).
- Compose semantics: `UiTags` (ROOT / ONBOARDING / ONBOARDING_OFFLINE_MODE / LOG_HOME) — multi-site
  production `testTag` callers; file renamed from misleading `UiPlaceholders.kt`.

Removed (low value only):

- Self-proving / portable-wire-only Fake cases (rename, pure push/pull cursor smoke without dual-client
  or ACL/freeze/LWW pin). High-value Fake fidelity cases retained as thin contract suite above.
- Entire `RecordSettingsMenuTest` — reflection rename detector for
  `recordShortcutHubDestinations` + tautological enum.entries listing.
- Dead test-only `RecordSettingsSection` / `recordSettingsSections()` and production-main
  `tryMoveItemAcrossCategory` / `builtInKeysInSection` test helpers.

No new StructureTest / reflection guards added.

## Validation

运行 sync、feature/settings、core/ui 相关 tests、`:app:assembleDebug`、`lintDebug`，
并用 `rg` 复核被清理 symbol/反射守卫。

## Documentation Gate

无。

## Out of scope

不移动 Settings package（Ticket 30），不重写 FakeSyncBackend 生产合同。
