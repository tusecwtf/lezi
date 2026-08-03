# 2026-08-02 全库诊断修复

Status: complete — implementation `6b278242`; final regression `d160fe68`

| # | Issue | Status | Blocked by |
|---|-------|--------|------------|
| 01 | [Server blocking I/O off tokio workers](./issues/01-server-blocking-io-spawn-blocking.md) | complete (absorbed) | — |
| 02 | [Bundle media lock scope + server timeouts](./issues/02-server-bundle-media-lock-timeouts.md) | complete (absorbed) | — |
| 03 | [Client upload write watchdog](./issues/03-client-upload-write-watchdog.md) | complete | — |
| 04 | [APK download outside sessionMutex](./issues/04-apk-download-outside-session-mutex.md) | complete | — |
| 05 | [Startup gate: single verify, off main thread](./issues/05-startup-gate-double-verify-main-thread.md) | complete | — |
| 06 | [Timeline invalidation observes fulfillment_candidates](./issues/06-timeline-invalidation-fulfillment-candidates.md) | complete | — |
| 07 | [CustomItemCatalog update/move transactions](./issues/07-custom-item-catalog-transactions.md) | complete (`d160fe68`) | — |
| 08 | [Timer restore: no cross-boot elapsedRealtime](./issues/08-timer-restore-cross-boot-elapsed.md) | complete | — |
| 09 | [Sync UX hygiene](./issues/09-sync-ux-hygiene.md) | complete | — |
| 10 | [Data screens reauth + milk summary fields](./issues/10-data-screens-reauth-and-milk-summary.md) | complete | — |
| 11 | [Design-system consistency sweep](./issues/11-design-system-consistency-sweep.md) | complete | — |

## Frontier

01–02 由 hang-fidelity tracker 的 server 波次吸收；03–11 的实现与既有回归在
`6b278242` 验收，07 的并发同名 update 补充回归在 `d160fe68` 通过后收口。

## Acceptance evidence

Fix/Must/validation bullets are expressed as checkboxes. Rust gates, JVM tests,
`connectedDebugAndroidTest` (ticket 06), assemble/lint, Release APK, device launch,
and page smoke are accepted on `6b278242`; ticket 07 focused concurrent-name
regression is accepted on `d160fe68`. No audit-remediation checkbox remains open.

## Notes

- 诊断原始结论见各票「Findings」;与 `family-sync-hang-and-account-fidelity` 的 09/10
  重叠面在本票 09/10 中标注,只做最小对照修复。
