# 06 — 用全局有界解释替代疑似重复 bounds 笛卡尔积

Status: implemented — JVM/lint/assemble and Android-test compile gates pass; device presentation remains post-01

Priority: P1

Audit baseline: `38cfbe7dbcf1f410237dce77b50ec1bd4854d8a6`

Blocked by: `post-0.3.13-review-remediation/01` 的 bounds/UI 实现稳定；本票不另拥有产品文案。

## Findings

- `SuspectedDuplicateGrouping.kt:88-124` 为每个 shard 建 `O(n²)` adjacency；timeline presentation 又会
  对每条 record 线性搜索 group/member。
- Summary 查询精确自然日窗口（`SummaryScreen.kt:235-256`），没有固定 30 分钟 instant halo。
  `D-1 23:50` 与 `D 00:10` 应成组，但 D 日查询只看见后一条。
- `SuspectedDuplicateBounds.kt:68-89` 逐日独立取 min/max 再相加，允许同一 group 在 range 不同日
  选择不同 display。100 ml/120 ml 跨午夜的真实全局总量集合是 100、120、220，当前下界可算成
  不存在的 0。
- `:102-124` 物化每组“所有 singleton + full set”的笛卡尔积；20 个二元组为 `3^20` lists。
  `SummaryAggregation.kt:90-125` 还计算 range/detail/anchor 三轮，`:323-325` 的 detail bounds 随后丢弃。

## Interface boundary

一个 duplicate projection Module 同时拥有 halo grouping、一次全 range interpretation 与各指标 bounds。
调用者提供目标窗口，Module 自己扩展候选 halo、但只裁剪目标指标。实现必须复用
`CareAggregation` 的指标语义，不复制第二套数值定义。

## Acceptance

- [x] grouping 使用排序窗口/有界 union 方案；timeline 用 UUID→group/role index，避免全 pair 与重复扫描
- [x] 以 instant 扩展精确 ±30 分钟 halo；DST 23/25 小时日不使用固定 24h 猜测；指标仍只计目标窗口
- [x] 每个 open group 在整个 range 只选择一次 singleton 或 full-set 解释，不得逐日换 winner
- [x] 不物化 interpretation list；时间/空间复杂度有文档化 polynomial 上界并支持 coroutine cancellation
- [x] 小规模 exhaustive oracle 与新算法对 formula/pumped/nursing/feed count、pee/poop、sleep、temperature
  等所有公开 bounds 完全一致；`hasUncertainty` 覆盖全部公开不确定指标
- [x] 删除未消费的 detail-bounds 计算，或把它接到真实可观察 surface；不得保留 `UNUSED_VARIABLE` façade
- [x] resolved source 仍过滤为单值，group identity 与 30 分钟 inclusive 边界不改变

## Validation

- [x] 首尾边界、跨午夜、DST、两日 100/120 反例与 chain component tests 通过
- [x] property/exhaustive-oracle tests 通过；100+ groups 在固定 timeout/memory budget 内完成并可取消
- [x] Summary/Log parity 与现有 domain aggregation regressions 通过
- [x] Android JVM/lint/assemble 通过；产品呈现的 device gate 仍归 post-01

## Implementation evidence (fixed HEAD `50b20abbd54d71af76fca50719b23b7714f82e50`)

- `SuspectedDuplicateProjection` is the single public calculation seam. It owns source filtering,
  exact instant halo selection, open grouping, whole-range bounds, and the immutable timeline
  UUID→group/role index. Summary performs one range projection; Log reuses one day projection.
- The grouping oracle exhaustively compared roughly 270,000 small shards (2–5 records, three
  authors, 0/30/31/60-minute timestamps) with the definition's full pair graph. The bounds oracle
  compared all `3^7 = 2,187` interpretations across every public metric. A 120-group fixture
  completed inside a 5-second timeout; a blocked 100,000-record snapshot stopped after cancellation.
  A review hardening regression also proves grouping, bounds preprocessing, and timeline indexing
  check cancellation before scanning their immutable snapshots.
- Final post-review `./gradlew test` — pass (872 tasks; 2m43s).
- Final post-review `./gradlew lintDebug :app:assembleDebug` — pass (816 tasks; 4m15s).
- `./gradlew lintDebug :app:assembleDebug :app:assembleRelease
  :feature:log:compileDebugAndroidTestKotlin :feature:summary:compileDebugAndroidTestKotlin` — pass
  before the cancellation-only hardening (1,475 tasks; 11m13s; signed Release APK verification and
  instrumentation compile included). The final full JVM gate recompiles the changed domain for both
  debug and release variants; no second Release packaging run was needed.
- `adb devices -l` — no attached device. Instrumentation was compile-only; the product presentation
  device gate remains owned by `post-0.3.13-review-remediation/01`.
