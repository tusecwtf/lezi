# 2026-08-02 全库诊断修复

Status: ready-for-agent

| # | Issue | Status | Blocked by |
|---|-------|--------|------------|
| 01 | [Server blocking I/O off tokio workers](./issues/01-server-blocking-io-spawn-blocking.md) | ready-for-agent | — |
| 02 | [Bundle media lock scope + server timeouts](./issues/02-server-bundle-media-lock-timeouts.md) | ready-for-agent | — |
| 03 | [Client upload write watchdog](./issues/03-client-upload-write-watchdog.md) | ready-for-agent | — |
| 04 | [APK download outside sessionMutex](./issues/04-apk-download-outside-session-mutex.md) | ready-for-agent | — |
| 05 | [Startup gate: single verify, off main thread](./issues/05-startup-gate-double-verify-main-thread.md) | ready-for-agent | — |
| 06 | [Timeline invalidation observes fulfillment_candidates](./issues/06-timeline-invalidation-fulfillment-candidates.md) | ready-for-agent | — |
| 07 | [CustomItemCatalog update/move transactions](./issues/07-custom-item-catalog-transactions.md) | ready-for-agent | — |
| 08 | [Timer restore: no cross-boot elapsedRealtime](./issues/08-timer-restore-cross-boot-elapsed.md) | ready-for-agent | — |
| 09 | [Sync UX hygiene](./issues/09-sync-ux-hygiene.md) | ready-for-agent | — |
| 10 | [Data screens reauth + milk summary fields](./issues/10-data-screens-reauth-and-milk-summary.md) | ready-for-agent | — |
| 11 | [Design-system consistency sweep](./issues/11-design-system-consistency-sweep.md) | ready-for-agent | — |

## Frontier

全部可立即开工;模块互不重叠(01–02 Rust,03–04 sync/,05–08 core+domain+timer,09 跨
sync+feature,10–11 feature+designsystem+core/model)。

## Notes

- 诊断原始结论见各票「Findings」;与 `family-sync-hang-and-account-fidelity` 的 09/10
  重叠面在本票 09/10 中标注,只做最小对照修复。
