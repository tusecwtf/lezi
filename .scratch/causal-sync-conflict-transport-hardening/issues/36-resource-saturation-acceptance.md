# 36 — 验收冲突资源饱和主缝

**What to build:** 消费 external 12/17/18/19 已有证据，并在公共主缝补一条 branch cap、paged snapshot 与 resolution retention 的跨层 smoke。

**Blocked by:** 31；[`external 19`](../../repository-dedup-algorithm-audit-20260809/issues/19-resolution-metadata-retention.md)

**Status:** implemented

## Contract slice

不重写资源矩阵；主缝只证明 saturation typed result 到 Android pending/inbox、完整分页仍可读、resolution 后 metadata 收缩且 replay 保留。

## Implementation sequence

1. 核对 external 12/17/18/19 exact-HEAD receipts。
2. 构造一个达到 branch cap 的 root 并观察 Android 状态。
3. 遍历全部 snapshot pages 后 resolution。
4. 触发 retention 并验证 replay/audit 与资源收缩。

## Acceptance

- [x] cap 不隐藏 durable branches，Android 诚实 pending
- [x] full-set pages 完整且 partial resolution 不可用
- [x] retention 不破坏 stable fact/replay/provenance

## Validation

- [x] 一条 isolated cross-layer saturation smoke 通过
- [x] external receipts/HEAD/limits 被引用

## Out of scope

不重复外部高基数/query/GC 单测矩阵。

## Evidence

- **HEAD:** `b55a93b00d237dd3ee3818bfb94fb9d70430ad6d`
- **External exact-HEAD receipts (implementation landings):**
  - R12 admission/branch cap `c1e05d6b3996f93549c3562961ddda356c40b605`
    (`fix(sync-server): bound causal conflict admission`)
  - R17 bounded head loader `86c7fab0d37ce3ab7ae6e6a4e261d46d835476f8`
    (`fix(sync-server): batch load conflict heads`)
  - R18 snapshot receipt/page `0e4f429ffbc77851339d0c8b02ce235fea6136ee`
    (`fix(sync-server): persist conflict detail pages`)
  - R19 resolution metadata retention `a5b257d2e529206e5bf42eae18d38bc22efdaa2d`
    (`fix(sync-server): bound conflict retention`)
- **Frozen limits cited by smoke:** open-branch cap `64`; page heads `16` → `4` pages at cap;
  retention grace `24h` (`RESOLVED_METADATA_GRACE_SECONDS`); typed saturation
  `causal_open_branch_limit_reached` / `scope=root`.
- **Fixture path:**
  - `domain/src/test/kotlin/com/lezi/babylog/domain/CareLogRealServerSeamResourceSaturationTest.kt`
  - H31 seam: `CareLogRealServerSeamSupport` / `IsolatedLeziSyncServer`
  - Fake inbox seam fix: `domain/.../carelog/FakeCausalClearDaos.kt`
    (`FakeConflictSummaryDao` now publishes open summaries to `observeInboxProjection`)
- **How to run:**
  ```bash
  ./gradlew :domain:testDebugUnitTest \
    --tests com.lezi.babylog.domain.CareLogRealServerSeamResourceSaturationTest \
    --tests com.lezi.babylog.domain.carelog.ConflictInboxProjectionTest \
    :sync:testDebugUnitTest \
    --tests com.lezi.babylog.sync.RealSyncPortConflictSnapshotPagingTest
  ```
- **Case coverage (single smoke):**

  | Step | Kind | Seam | Assert / receipts |
  |---|---|---|---|
  | cap fill | R12 | CareLog→real-server | 64 same-base forks settle branched; open conflict retained |
  | overflow | R12→Android | LocalWrite | 65th fork stays `syncDirty`; settle fails with 429/throttle/budget text; durable conflict still discoverable |
  | inbox | H07 | CareLog inbox | open conflict appears with `ConflictRootType.Record` |
  | full pages | R17/R18 | `RealSyncPort.fetchConflictSnapshot` | complete page-0; 64 distinct branches; 4×16 page math |
  | partial closed | R18/H06 | `ConflictResolverDraft` | incomplete page fails open; complete draft `Current` but not auto-submittable |
  | resolve | H03/H31 | CareLog choice-only | preferred `/note` accepted; peers converge; inbox clears |
  | retention | R19 | SQLite marker advance + port exact replay | branches/snapshot receipts → 0; resolution/stable/complete marker retained; drift rejected |

- **Related green:** `ConflictInboxProjectionTest`, `RealSyncPortConflictSnapshotPagingTest`.
- **Isolation:** developer-owned isolated TLS lezi-sync child + loopback only; no NAS/production certs.
- **Residuals:** does not re-run external high-cardinality Store/API matrices (R12–R19 unit ownership stays external).
  Device interaction residual remains later H42.
