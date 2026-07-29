# 23 — 汇总聚合移出主线程并降低重复扫描

**What to build:** 将首页/记录汇总的分组、区间裁剪与统计移到可取消的后台计算，并用单次分区替代对完整记录集的重复扫描。

**Blocked by:** None — can start immediately

**Status:** complete

**Size:** M

## Acceptance criteria

- [x] 大批 Record 的过滤、按类型分组、睡眠裁剪与统计计算不在主线程执行。
- [x] 一次输入快照先完成单次分区/索引，再生成各摘要；不为每个卡片重复遍历全部记录。
- [x] 新输入、宝宝切换或页面销毁会取消旧计算，较旧结果不会覆盖较新状态。
- [x] 空数据、开放区间、跨日区间、自定义类型与未知兼容类型的输出与现有产品规则一致。
- [x] 大夹具回归测试同时断言数值等价、取消安全和扫描/复杂度边界，而不只测运行时间。
- [x] UI 在计算期间显示稳定已有值或明确加载态，不因线程切换闪空或重复动画。

## Implementation notes

- `SummaryAggregationEngine` 在 `Dispatchers.Default` 上处理一个不可变 Record 快照；`calculateLatest` 与 ViewModel 的 `flatMapLatest` 分别取消同一查询的新快照和宝宝/日期/范围切换，`WhileSubscribed()` 在页面停止收集时立即取消。
- `CareAggregation.window` 每个输入 Record 只读取一次，先建立 DST 安全的自然日桶；日/周/月、七日详情及上周比较均从同一窗口切片。记录循环和睡眠跨日裁剪循环都设有协作式取消点。
- 初次计算显示明确加载态；已有结果在下一次计算完成前保持稳定。未知 payload fail-closed，不贡献统计，自定义类型仍保留但不进入现有摘要定义。
- 摘要产品定义没有变化，因此未修改 PRD。线程、取消与复杂度合同由 `CareAggregationWindowTest` 和 `SummaryAggregationEngineTest` 固定，不新增 ADR。

## Validation

运行聚合纯逻辑、ViewModel 调度与大夹具测试，以及应用编译和静态检查；低端模拟设备做滚动 smoke。

- `./gradlew :feature:summary:testDebugUnitTest :domain:testDebugUnitTest --tests com.lezi.babylog.domain.CareAggregationWindowTest --tests com.lezi.babylog.domain.WeekSummaryTest --no-daemon`：通过。
- 3,000 条固定夹具与旧参考输出逐字段相等（9,000 ml、3,000 次、30 日序列）；3,000 条输入读取恰好 3,000 次；100,000 条阻塞夹具证明取消后不全量读取、不发布旧结果，新请求只发布新值。
- `./gradlew :feature:summary:lintDebug :app:assembleDebug --no-daemon`：通过。
- API 35 x86_64 模拟器（约 2.5 GB RAM，1080×2400）安装 Debug APK 后进入汇总页，滚动经过喂养、睡眠、尿布卡并回滚；应用进程保持存活，最终 crash buffer 为空。

## Documentation Gate

若摘要定义未变化无需改 PRD；把计算线程与复杂度约束记录在架构文档或测试说明中。
