# 07 — 批量、原子并可观察地投影 Record + Wake + wake media

Status: implemented — JVM/lint/assemble and Android-test compile gates pass; no device attached

Priority: P1

Audit baseline: `38cfbe7dbcf1f410237dce77b50ec1bd4854d8a6`

Blocked by: 06 — 两票都修改 Summary/Timeline 读取 seam，必须串行。

## Findings

- `CareLogQueries.kt:36-51` 的 Summary Flow 只由 `recordDao.observeRange` 失效，但 projection 在
  `:102-108` 逐 Sleep 读取 Wake。仅新增/撤回/选择 Wake 不改 records 表，Summary 会继续把睡眠
  累计到 now，直到无关 Record 触发 emission。
- `WakeObservationCoordinator.kt:86-113,387-394` 每个 Sleep 查 observations，可能再查 open sleeps，
  并按 observation 查媒体，约 `1 + S + W` SQL。
- `TimelineWindowDao.kt:17-41` 声称一次 invalidation 后固定三次 batch read，但
  `TimelineWindowRepository.kt:202-208` 在该 snapshot transaction 外逐 Sleep 补 Wake，既退化 N+1，
  也可能混合两个 Room revision。

## Interface boundary

建立一个 bounded read-projection Interface：按 baby/window/root 集合，在一个 Room snapshot 中批量
读 Record、WakeObservation 与 wake media，并产出已投影 Record。Summary 与 Timeline 复用该
Interface；不可各自复制 effective-wake 规则。

## Acceptance

- [x] Wake create/edit/withdraw/select 任一单表变化都会触发 Summary 与 Timeline 新 emission，
  不需要修改 Sleep row 或等待其它记录
- [x] Record/Wake/wake-media 来自同一 Room transaction/revision；并发写时不输出半新半旧 snapshot
- [x] SQL statement count 对 S 个 Sleep/W 个 Wake 保持常数级批量，不随 S/W 增长
- [x] effective explicit selection、暂定最早合法、withdrawn/illegal filtering 与 open Sleep 规则只有
  一个 owner，Summary/Timeline/详情结果一致
- [x] window query 包含跨窗 Sleep 所需 root，但不会把窗外普通记录计入 aggregation

## Validation

- [x] Room integration regressions 覆盖仅写 wake table 的 projection emission；已编译，无设备可执行
- [x] query-count、并发 revision barrier、Summary/Timeline parity regressions 通过 JVM 门禁或已编译
- [x] 月级 Sleep/Wake fixture 在固定 timeout 内完成
- [x] Android JVM/lint/assemble 通过；Android instrumentation 已编译，无连接设备可执行

## Implementation evidence (fixed HEAD `b2b9c022a5626f0589117637c65a66e8872d3512`)

- `RecordWakeProjectionDao` is the bounded read seam. Its Room implementation observes a lightweight
  invalidation signal, then reads Record, WakeObservation and wake media inside one transaction. A
  record projection uses exactly three batch SELECTs; a complete timeline snapshot adds two SELECTs
  for plan and log media. No query count grows with the number of Sleep/Wake rows.
- `projectSleepInterval` is the single effective-wake owner. Summary, Timeline, detail and open-Sleep
  shortcuts consume its projected flags; raw SQL only constrains family/baby/root/window/surface and
  returns withdrawn, illegal and stale-selection candidates for the canonical Kotlin decision.
- Summary and Timeline Flow regressions cover wake create, edit, earlier provisional replacement,
  explicit selection and withdrawal. Other regressions cover cross-window Sleep roots without
  aggregating ordinary out-of-window records, cross-Sleep selected pointers, withdrawn/illegal
  fallback, active `conflict_not_adopted` exclusion, and an all-open 744-Sleep linear-time fixture.
- Real Room `QueryCallback` regressions assert projection=3 and timeline snapshot=5 SELECTs for
  1/100/744 roots; the revision-barrier regression accepts only internally consistent Record/Wake/media
  snapshots. These instrumentation regressions compile, but could not run without an attached device.
- Final post-review `./gradlew test` — pass (872 tasks; 1m21s).
- Final post-review `./gradlew lintDebug :app:assembleDebug :app:compileDebugAndroidTestKotlin
  :core:database:compileDebugAndroidTestKotlin :feature:summary:compileDebugAndroidTestKotlin
  :feature:log:compileDebugAndroidTestKotlin` — pass (849 tasks; 10m06s).
- `adb devices -l` — no attached device. Instrumentation validation therefore remained compile-only.
- Fixed-point review: Standards 0 hard / 0 judgement; Spec 0 hard / 0 judgement.
