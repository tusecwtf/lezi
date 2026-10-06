# 02: W1 服务端 per-head census 缓存 + close_empty_open_conflicts 移位

**What to build:** 服务端 live census 从「每个 pull 页全家庭扫描+排序+SHA-256(family 锁内)」
改为「每 (family, head) 至多重算一次、其余 O(1) 命中」;pull 读路径上的空冲突闭合移入写事务,
存量残留由启动/周期维护兜底。对外行为逐字节不变——0.4.8 及更老客户端零感知,revert 即回滚。

**Blocked by:** 01(需要 W1 落地前的 before 数字)

**Status:** ready-for-agent

- [x] AppState 内 per-family 缓存条目(代际标识, head_rev, census),短临界区互斥;读在既有 family 锁内的 blocking 段(落点经核改在 Store 内,见落地记录第 1 条)
- [x] 失效仅两条规则:全部 rev 自增的唯一通道单点失效;灾难恢复/导入/匿名化类绝对 rev 写入无条件删条目(防回退后 rev 复用撞旧值)
- [x] 只读连接不写缓存
- [x] store 全部公共写 API 的失效黄金测试:逐个调用后断言缓存 rev == family_meta.rev 且 census == 全量重算黄金值
- [x] 进程重启后每 family 惰性重建恰一次;多 family 条目互相隔离
- [x] close_empty_open_conflicts 移入 commit 派发末尾 / resolve / withdraw 事务(拆接受事务参数的内部函数),保留「闭合推进 head 与实体 rev」的投递语义
- [x] 存量/恢复残留的 empty-open 行由启动/周期维护闭合(媒体 GC 同款先例):只 pull 不写的设备也能收到 conflict_summary 消失
- [x] golden envelope:census 开/关、改前改后,同输入响应逐字节相同
- [x] 复测 01 的差值指标:空页 pull 服务端耗时显著下降,after 数字入档
- [x] cargo fmt --check / test --locked / clippy -D warnings 全绿

## 落地记录(2026-09-06,S 轨)

**Commit:** `perf(sync-store): cache live census per family head`(见 git log)

**实现形状:**

- 新模块 `src/store/live_census_cache.rs`:`Arc<LiveCensusCache>`(Store 字段,Clone
  共享;临界区 O(1)),`CacheEntry = {epoch, head_rev, census}` + 每家族失效代际
  `epoch`;miss 返回 rebuild token,install 时校验 epoch——「回退后 rev 复用撞旧值」
  (A4/B7)与「rebuild 与失效竞态装帧」都被结构挡住;epoch 不用协议 generation
  (restore 不改它)。锁中毒按展开处理(失效永不因 panic 被跳过)。
- **单点失效收敛**:三条 `rev=rev+1` 路径收敛——`causal::advance_rev` 为唯一漏斗
  (内含失效;staging 的 `advance_family_rev` 副本删除并委托;`source_relations::
  bump_relation_member_revisions` 内联失效);`EvalContext` 与自由函数以
  `&LiveCensusCache` 参数贯通。绝对写入(restore:117、bundles commit:2301、
  hard_delete_membership 的 anonymize)在拥有 Store 的方法里无条件失效。
  `offline_migrate` 是离线割接工具,对 raw Connection 操作、同进程无 Store 实例,
  无可失效的缓存;后开的 Store 缓存天然为空(已在缓存 doc 与测试注释里论证)。
  失效先于事务提交触发——回滚只多一次重建,永不出错值。
- **读路径**:`pull_with_final_envelope_size` 不再跑 empty-open 闭合(每 pull 少一次
  conflicts 扫描);census 改 `live_census_at_head`:hit 即返回、miss 重建一次并按
  read_only 门控安装。
- **close 移位**:拆 `close_empty_open_conflicts_in_tx(&Transaction)`;落点 =
  causal_commit_durable 尾(含 exact-replay 出口)、resolve_conflict 尾(含 replay
  出口)、withdraw 两条出口;保留「head+entity rev 推进」投递语义。残留兜底 =
  `Store::close_leftover_empty_open_conflicts`(逐 family IMMEDIATE 事务,避免
  snapshot-upgrade BUSY)+ `start_empty_open_conflict_closure_maintenance`(首 tick
  即启动兜底,此后周期 600s;不复用媒体 GC 的 60s,也不更频——残留只在
  family 停写时出现,闭合无时效压力;maintenance_read_only 不 spawn)。
- **零 wire/零 schema**:PullQuery/PullResponse/serde 与 pull_response_size 预算
  公式一字未动。

**测试(新增 census_cache_tests 23 个 + api.rs 2 个 + 重定位 2 个):**

- 黄金失效矩阵:funnel 各路径(accepted commit、media 投影 commit、branched
  bump、rename_family、staging promote、declare/resolve source relation)、绝对
  写入(commit_bundle、hard_delete_membership、disaster_restore)、非 rev 写不误
  失效(stage_bundle、gc 两种)、create_family 从空开始;hit/rebuild/全量重算三者
  字节相等 + probe rev == family_meta.rev。
- 重启惰性重建恰一次、多 family 隔离(production 单 family/库,用 SQL 嫁接兄弟
  family 验证)、read_only 不安装、Store Clone 共享缓存、缓存级 epoch/rev 复用
  守卫、残留维护闭合(store 级 + api.rs 启动维护 e2e)、census 开/关 e2e 字节
  黄金(`pull_census_cache_keeps_envelopes_byte_identical`)。
- 重定位的两个既有测试(时序差异为设计内):`caught_up_pull` golden(envelope
  语句数 2→1,断言读路径不再扫 conflicts);`leftover_empty_open_conflict*`
  (pull 闭合 → 维护闭合,断言残留 pull 不闭合、维护闭合推进 head 并向
  caught-up 设备重投递、幂等);api.rs `two_clients_restore_..._across_restart`
  的合成 fixture 移到 restart 之后并用 canary 行同步 sweep 已跑过(否则 sweep
  会按设计闭合它)。

**after 数字(摘要,详见 notes/02-after-numbers.md):** census 开/关差值
0.51–0.68ms → **≈0(0–3µs)**;census 开 1.13–1.44ms → 0.37–0.50ms;census 关
0.62–0.78ms → 0.38–0.50ms(读路径减负);冷启动首拉 ≈2ms(每 head 一次)。
Tier 2 复活条件仍未触发。

**与 design.md §1.1 的偏离:**

1. 缓存落点在 **Store 内**而非字面的「AppState 内」:全部 rev 写入都在 Store 方法
   内,失效钩子必须与其同所有权域;Store 是 Clone,`Arc` 字段让所有 handle 共享
   同一缓存;若放 AppState 需把失效回调穿越每个写路径,plumbing 更差。编排指令
   明确允许此选择。
2. 周期维护常数取 **600s** 新起(设计要求「新起、别复用 60s、别太频、给理由」):
   残留只出现在 family 停写后,闭合无用户可见时效压力(0.4.8 里任意一次 pull 也会
   闭合),600s 上界足够,且远低于媒体 GC 的扫描频率。
3. 闭合落点额外盖住 commit 派发与 resolve 的 exact-replay 出口(同属设计说的
   「派发事务末尾」,多一次免费投递机会)。

**门禁:** fmt --check / cargo test --locked(全 target 绿:lib 348 + api 200 +
其余)/ clippy --all-targets --all-features -D warnings 全绿。

**残留风险:** 维护 sweep 的首次 tick 在极繁忙的进程上可能延迟(异步任务),e2e
已用轮询 + canary 消除测试竞态;生产语义无竞态面(sweep 幂等、IMMEDIATE 事务带
10s busy_timeout)。缓存使同进程多 Store 句柄共享状态,跨进程写(如绕过
offline-migrate 合同在服务运行时迁库)不在缓存模型内——维持离线工具合同即可。
