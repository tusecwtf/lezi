# 03 — 对齐独立媒体包的根发布回执

**What to build:** 独立 log/avatar 媒体发布抬高 root `updatedAt` 时，用同一个 `rootUpdatedAt` 条件确认本地根；本地水印、dirty/outbox 与 NAS LWW 不能分叉。

**Source:** `AUDIT-20260801-P1-03`  
**Blocked by:** 02 — media receipt CAS must be stable first  
**Status:** done  
**Size:** M

## Design notes (public seams)

1. `OutboxPushPipeline.pushStandaloneLogMediaBundles` / `pushBabyAtomicBundle` — after atomic commit of elevated root, call DAO CAS ack with the same `rootUpdatedAt` used on the wire (not media-only ack / not outbox-row time alone).
2. `RecordDao` / `CarePlanDao.acknowledgeSyntheticRootPublication(expectedLocal, published)` — monotonic `familyPublishedUpdatedAt`, advance local `updatedAt` when content epoch matches, keep dirty on concurrent newer content.
3. `BabyDao.acknowledgeSyntheticRootPublication` — equivalent watermark via CAS-advanced `updatedAt` + dirty clear (no separate receipt column).
4. RealSyncPort / Outbox push path observable via `sync(LocalWrite)` after media-only dirty roots.

## Acceptance criteria

- [x] Record、CarePlan 的 standalone log bundle commit 后记录精确 `rootUpdatedAt` 的家庭根回执，不再只 ack media。
- [x] avatar-only Baby bundle 同样以发布使用的 `rootUpdatedAt` 确认 Baby 根，而不是对不存在的 `babyRow` 静默跳过或用旧 outbox 时间。
- [x] 根确认必须 CAS 期望本地 revision/content epoch；并发根编辑时不覆盖新内容、不错误清 dirty，并为较新根保留/重建 outbox。
- [x] 成功确认后下一次本地根编辑一定产生严格大于 NAS 已发布根的修订。
- [x] 注入“远端 commit 成功、进程在本地 ack 前崩溃”后，重试用确定 bundle ID 收敛且不重复/丢失媒体。
- [x] Record/CarePlan 的 `familyPublishedUpdatedAt`、Baby 的等价水印及 `markSynced` 语义由真实 DAO 测试覆盖。
- [x] 同一根多组 standalone media 的顺序不会让较旧回执倒退较新水印。

## Validation

- `:sync:testDebugUnitTest` green (includes new standalone/avatar synthetic-root cases).
- Room regressions: `core/database/.../SyntheticRootPublicationRoomTest.kt` (instrumentation; not run in this worktree without a device).
- Docs: ADR-0008 + `docs/prd/data-model.md` synthetic root receipt language.

## Documentation Gate

- [x] 更新 ADR-0008/数据模型的根发布回执说明，使 Baby 与 Record/CarePlan 的合成根行为明确。

## Out of scope

不引入媒体-only wire；仍保持 Baby/Record/CarePlan 为 atomic root。
