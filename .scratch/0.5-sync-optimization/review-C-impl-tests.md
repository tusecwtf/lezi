---
title: 0.5 流式同步设计对抗审查 C 轴——实现可行性与测试完备性
date: 2026-09-06
status: review-only（只读审查；唯一产出是本文）
reviewer: 对抗审查员 C（实现可行性与测试完备性轴）
target: `.scratch/0.5-sync-optimization/design.md`（draft-v1，2026-09-06）
inputs: `.scratch/0.5-sync-optimization/research.md`（2026-09-06）
evidence-base: 2026-09-06 tree 实读（0.4.8 / versionCode 29 / Room 29 / server schema 13 / lezi-sync 0.4.8）
---

# 对抗审查 C：实现可行性与测试完备性

> 所有 `path:line` 为 2026-09-06 tree 实读。行号为当前 HEAD，后续 rebase 需重核。
> 本轴不裁决收敛正确性（轴 A）与 wire 冻结（轴 B），但 C1/C3 的结论会反过来缩小 B 轴的裁决面。

## 0. 结论速览

| 编号 | 严重度 | 一句话 |
|---|---|---|
| C1 | **P1** | W2 服务端 XOR 写路径折叠是过度工程：`per-head 缓存 + 写路径失效 + 惰性重建`已满足 V1/V3，成本差 ≈ 每活跃 head 一次 <3ms 扫描 |
| C2 | **P1** | 客户端 XOR 折叠 + InvalidationTracker 兜底复杂度不可控且兜底自身有缝；「本轮零应用谓词复用 + 否则单次 O(n) 重算」在本仓库家庭规模下已达标 |
| C3 | **P1** | 若 C1+C2 成立，`census_algorithm=xor_v1` 的 wire 变更整体不必要；且 `PullQuery` 有 `deny_unknown_fields`，老服务器对新参数是**硬 422 拒绝**，能力门是强制而非礼貌 |
| C4 | P2 | `close_empty_open_conflicts` 移写路径的落点与 bump 行为已核清；需补「存量 empty-open 残留 + 只 pull 设备永不闭合」的启动维护缝 |
| C5 | P2 | 服务端缓存并发落点：AppState 内短临界区 Mutex，读写都在 family 锁内既有 `run_blocking` 位置；双短语并存内存 KB 级，非问题 |
| C6 | P2 | keep-alive 轮末拆除动机是合同/身份卫生而非电池；心跳探针本就**不拆**把手 → 90s TTL 有真实但小的收益，无无线电成本 |
| C7 | P2 | W4 的限流担忧不成立（媒体 GET 无 limiter，不会 429）；真实约束是 family 锁串行化、3×10MiB 堆峰值、共享计数器原子性、staging 原子合同 |
| C8 | **P1** | tip-skip 谓词不完备：漏 `pendingGenerationResync`、pending local-clear、resetReceipt、fuse 释放四个缝；跳过握手的安全底线是心跳端点仍过 `require_supported_client`，需写进设计 |
| C9 | P2 | 「最近一次心跳结果」的存取建议：RealSyncPort 内 volatile quiet-proof 记录；新鲜度锚点应是「上次成功整轮」而非仅 last heartbeat |
| C10 | P2 | W0 完全可行且基建现成（`IsolatedLeziSyncServer` 就是隔离实例 harness）；服务端缺延迟观测，用 TraceLayer DEBUG / census 开关差值法 / store 级计时测试三招补齐 |
| C11 | P1 | W1-W5 测试计划各有实质缺口；W1 的「失效路径枚举」和 W2 的「随机化等价」必须 seed 化并双侧共享黄金 vector |
| C12 | P2 | compat 矩阵 4 格的测试落点：新+新/老+新用 `IsolatedLeziSyncServer` 真服务端，新+老用 FakeSyncBackend，老+老靠 golden envelope 不必起老二进制 |
| C13 | P2 | 工作量粗估与最可能超支项：W2 原案（大）为最大超支源；采纳 C1/C3 后 0.5 整体降为 2×中 + 3×小 |

---

## 1. 攻击明细

### C1（P1）服务端 XOR 写路径折叠是过度工程，per-head 失效重建即可达标

**证据**
- 现状：census 在**每次 pull 请求**全量重算（`tools/lezi-sync/src/store/pull.rs:913-949` 的 `compute_live_census`，`pull.rs:1131-1135` 调用；无 `(family_id, deleted_at)` 索引，`store/schema.rs:211` 只有 `entities_family_rev`）。wire 已定义 census 是「同一 head 的纯读导出」（`docs/spec/contracts/causal-sync-wire.md:139-141`）。
- 设计：W1 是「读路径 O(1) 缓存 + miss 惰性重建」+「写路径折叠增量推进缓存 rev → 稳态永不重建」（design.md §1.2）；design.md 自己把「漏一条写路径 → 陈旧摘要」列为对抗审查重点（§2 W1 风险行）。
- 写路径事实：所有 bump 都走 `advance_rev`（`store/causal.rs:1020-1028`，`UPDATE family_meta SET rev = rev + 1`）；`bump_entity_rev_only`（`causal.rs:2058-2071`）也调 `advance_rev`。即**失效判定可以只锚定一个函数**：`advance_rev` 一处失效缓存即可，无需逐路径折叠。

**推理（量化）**
- 真实家庭 ≈770 rev（research §5 校准）。重建 = SQLite 扫 770 行 + 排序 + SHA-256(≈38KB) ≈ **1-3ms（NAS ARM）**。
- 缓存版（失效+惰性重建）的成本结构：安静轮（head 不变，占绝大多数轮）O(1) 命中；活跃轮（有 commit/resolve 的 head）**每个新 head 恰好 1 次重建**——N 页轮付 1 次而非 N 次，已把「O(家庭规模)×轮频」降为「O(家庭规模)×活跃 head 数」，V3 的意图（不得 O(家庭规模)×轮频）已满足。
- XOR 折叠版相对再省的：每活跃 head 那 1 次 <3ms 扫描。代价：必须在**每个**改变活集的写路径（commit 各状态、restore batch、media promote、`resolve_empty_open_conflict`、`close_empty_open_conflicts` 自身……）正确折叠，漏一条即陈旧摘要 → 客户端假失配 → 一次全量重走 + 假诊断。这是设计自认的头号风险，买到的收益接近测量噪声。

**修复建议**
1. W1 服务端只做：`AppState` 内 `Mutex<HashMap<family_id, (head_rev, LiveCensus)>>`；读路径 hit O(1)，miss 惰性重建（V1 例外 a）；**在 `advance_rev` 单点失效**（或返回新 head 后由 store 层删除该 family 条目）。不实现写路径 XOR 折叠。
2. 把「XOR 折叠使稳态永不重建」从 W2 服务端职责中删除；XOR 是否做，改为由 W0 数字 + 家庭规模增长预期裁决（见 C3）。
3. 若坚持折叠版，至少把「缓存 rev == family_meta.rev」断言做成 debug_assert + 一条黄金测试枚举 store 全部公共写 API（见 C11-W1）。

### C2（P1）客户端「零 Room schema 变更」路线：InvalidationTracker 兜底不可控，谓词复用已足够

**证据**
- 全仓库**没有任何** InvalidationTracker 使用（grep `InvalidationTracker|addInvalidationCallback` 零命中，build/ 除外）——设计是引入全新基建。
- 客户端 census 只在**每整轮 head 处比对一次**：`reconcileLiveCensusAtHead`（`sync/src/main/kotlin/com/lezi/babylog/sync/engine/ReplicaSyncEngine.kt:2290-2319`）先过 `hasPendingPublishUnits()` 门（`:2417-2451`，仅 6 表 `listPendingSync` 扫描），失配才置 cursor 0 一次重走。
- 重算成本：`localLiveCensusEntries()`（`:2333-2349`）7 表 `listAllIncludingDeleted` + 过滤 + 排序 + SHA-256。770 行 ≈ 10-30ms/轮；**7700 行（10× 家庭增长）≈ 100-300ms，仍每轮仅一次**。
- InvalidationTracker 语义：回调在**事务提交后**由 Room 的 IO executor **异步、合并**派发，只有表级粒度，不携带行数据；「写提交 → 回调派发」的窗口内，比较线程读缓存会把「实际已脏」当「干净」。设计的兜底句「标脏但已知缝未折叠 → 单次重算」**依赖回调已派发**，窗口内不成立——除非再叠加一层同步标脏（等于把折叠缝重新枚举一遍）。即：InvalidationTracker 兜底既引入新基建，又**不能闭合它声称要闭合的缝**。

**推理**
- 设计 §1.2 列的折叠缝（pull 整页应用 `ReplicaSyncEngine.kt:2102-2156`、settlement 回声 `CausalSettlement.kt:321-395`、本地发表写、冲突选择、清理路径）中，前两类都是同步引擎自己跑的、可计数的事务；真正的不可控缝是 **UI 线程本地写在 pull 进行中落下**。这个缝今天的全量重算路径同样存在（新行立即可见但尚未发表；今日被 `hasPendingPublishUnits` 部分遮挡的程度取决于 pending 标记的落库时机），后果有界：一次假失配 → 一次重走 → 下轮 pending 门生效或发布后收敛 → 最多一条假诊断。与兜底方案引入的「缓存陈旧」是同一后果类别。
- 「本轮零应用谓词」是更简单的等效物：一轮 pull 中所有页 `entities` 全空 + `deferredUnresolved` 空 + cursor 未动 + `!hasPendingPublishUnits()` + `!hasPendingGenerationResync()` → 活集必然未变，复用上次 (generation, entries)；否则单次 O(n) 重算。不需要 InvalidationTracker、不需要折叠缝枚举、失败模式退化为现状（重算）。

**修复建议**
1. W2 客户端收缩为：进程内 `(pullGeneration, entries)` 复用 + 零应用谓词 + 否则单次重算；**放弃 InvalidationTracker 方案**（写明理由：异步派发窗口使兜底失效）。
2. 设计 §1.2 补一句量化：「本仓库家庭规模下，单次重算 10-300ms/变更轮，不构成 V3 违例」；若未来规模增长到 10^4-10^5 行再评估短语切换。
3. 残余竞态（pull 途中用户本地写）作为已知有界风险记录，用一条测试钉住行为（假失配 → 恰一次重走 → 无风暴，可挂在 `ReplicaSyncEngineCensusReconcileTest`）。

### C3（P1）xor_v1 的 wire 变更可能整体不必要；`deny_unknown_fields` 使老服务器对新参数硬拒绝

**证据**
- `PullQuery` 带 `#[serde(deny_unknown_fields)]`（`tools/lezi-sync/src/handlers/sync.rs:1036-1050`）。serde_urlencoded 对未知 query 参数的行为由此定为 **422 QueryRejection**，不是忽略。design.md §6/轴 B 把「忽略 vs 拒绝」列为待核——本轴已核死：**拒绝**。
- 客户端 `pull` 请求参数是手拼 URL（`sync/src/main/kotlin/com/lezi/babylog/sync/backend/HttpSyncBackend.kt:704-710`），`include_live_census=true` 硬编码；新增参数同为机械改动，但能力门控因此从「礼貌」变「强制」：新客户端对未见 `census_xor_v1` 能力的服务器发 `census_algorithm` = 直接全量 pull 失败。

**推理**
- 若采纳 C1（服务端 per-head 缓存）+ C2（客户端谓词复用），双端校验成本已 O(1)/安静轮、O(小常数)/变更轮，V1/V3 达标。xor_v1 的剩余收益只有「活跃 head 的那一次 O(n) 重建/重算」，在本仓库规模下不可测量；而它的账单是：D1/D4 两个未决决策、`setup-status` 能力广播、4 格 compat 矩阵、双侧等价性随机化测试、wire 文档 §1.4 增补、ADR-0024、能力门是硬门（上述 deny_unknown_fields）。
- 「老客户端 + 新服务端」格（每 head 缓存重算一次旧短语）在 C1 下已经免费成立——**W1 单独落地就完成兼容矩阵的两格**。

**修复建议**
1. D4 建议改倾向为 **b**：0.5 只做零 wire 项；xor_v1 从「0.5 核心」降级为「测量驱动的后续项」，触发条件写死（W0 数字显示 census 重算占比显著，或家庭规模超阈值）。
2. 若 owner 仍要 0.5 内做 xor_v1：设计必须把「老服务器 422 硬拒绝」写进 §1.3（能力门 = 强制前置），并按 C11-W2 补硬拒绝测试。此项归轴 B 复核。

### C4（P2）`close_empty_open_conflicts` 移写路径：落点、bump 行为与残留缝

**证据**
- 现状：`pull_with_final_envelope_size` 每次调用先跑 `close_empty_open_conflicts`（`store/pull.rs:1110-1116`，`read_only` 时不跑）；它自开 `unchecked_transaction`，对每个 leftover 调 `resolve_empty_open_conflict` + `bump_entity_rev_only`（`store/causal.rs:1561-1596`；`bump_entity_rev_only` → `advance_rev` 推进 family head，`causal.rs:2058-2071, 1020-1028`）。
- 写事务结构：commit 在 family 锁内单一 `run_blocking` 事务（`handlers/sync.rs:595-600`；`store/causal.rs:3131-3305`）；resolve/withdraw 各自锁内事务（`sync.rs:760-790, 902-920`）。

**推理与修复建议**
- 落点：把 `close_empty_open_conflicts` 拆为「接受 `&Transaction` 的内部函数」+ 现有包装；在 **commit dispatch 事务末尾**与 **resolve/withdraw 事务内**调用。它对每个 leftover 都 `advance_rev`（head 前进）——语义与现状一致，保留即可；在 C1 的缓存方案下这类 bump 只需触发失效（活集未变，重建结果同值）。
- 残留缝：现状的读路径清理还兜住「老版本服务器残留 / 崩溃残留」的 empty-open 行；移走后，一个**只 pull 不写**的设备所在的存量家庭将永远收不到 conflict_summary 的闭合。修复：服务端已有周期维护先例（`CAUSAL_MEDIA_GC_MAINTENANCE_INTERVAL_SECONDS`，`lib.rs:75`），把 `close_empty_open_conflicts` 挂进启动/周期维护，或读路径仅在「已知存在 leftover」标记下运行。
- 测试：存量残留家庭 + 只 pull 设备 → 启动维护后 summary 闭合（server 端 store 测试即可钉住）。

### C5（P2）服务端缓存的并发落点与双短语并存成本

**证据**
- 锁纪律：pull handler 在 family tokio Mutex 内做一次 `run_blocking`（`sync.rs:1212-1218`），census 计算发生在其中；心跳同样「锁内只做两读」（`sync.rs:200-241`）。
- 设计提出双短语并存（旧 SHA-256 每头重算 + 新 XOR 折叠，design.md §1.2）。

**推理与修复建议**
- 缓存结构放 `AppState`，`std::sync::Mutex<HashMap<family_id,(i64,LiveCensus)>>`，临界区 O(1)；读写都发生在既有 family 锁内的 `run_blocking` 位置，不引入新的锁序（family tokio Mutex → 短临界区 std Mutex，方向单一，无死锁面）。重建成本延长 family 锁持有的量 = 现状每页全量重算的量，净改善为正。
- 双短语并存：每 family 7×(count + 32B digest) ×2 短语 ≈ KB 级，内存非问题；真正的成本是**两套维护逻辑**（C1/C3 建议直接砍掉 XOR 侧）。若保留，旧短语值可以由同一活集数据派生（缓存存 live keys 而非单一短语，两个短语都 O(keys) 现算，仍是每 head 一次）——这是比「并存两份缓存」更简单的形状。
- `run_blocking` 池：axum/tokio 默认 blocking 池 512 线程，现状已在每 pull 用，缓存不改变占用模式。

### C6（P2）keep-alive 轮末拆除的动机与 90s 保留的真实收益

**证据**
- 拆除合同原文：「Disconnect handles for authenticated 2xx exchanges that were not torn down. [open] still runs every time; **this is not a connection pool**.」（`HttpSyncBackend.kt:144-149` 注释）；`releaseForegroundKeepAlive` 全部把手 `disconnect()`（`:170-174, 1946-1976`）。
- 拆除调用面共 6 处：轮末 `ReplicaSyncEngine.synchronize` finally（`ReplicaSyncEngine.kt:197-206`）+ `RealSyncPort` 冲突详情/resolve/withdraw/declare/resolve-group 各 finally（`RealSyncPort.kt:1194-1244`）。动机注释只讲「不是连接池」的合同整洁与身份驱逐（`prepareKeepAlive` 按 origin+SPKI 身份驱逐，`:1900-1907`），**没有任何无线电/电池措辞**。
- 关键事实：心跳探针走同一 `withKeepAlive` 且**从不调用 release**（`HttpSyncBackend.kt:1360-1373`；`SyncHeartbeatEngine.kt:206-244` 明言 "never holds the foreground keep-alive" 指的是合同语义而非拆除）→ 心跳的把手留在 undetached 列表，下一轮 `prepareKeepAlive` 同身份不驱逐——**平台池里的心跳 socket 本来就活到下一个整轮的轮末拆除为止**。
- 平台池：Android 的 `HttpURLConnection` 底层 KeepAliveCache 空闲超时 ≈ 5 分钟（AOSP 内嵌 okhttp ConnectionPool 默认），90s TTL < 平台池超时，不会双花。空闲 keep-alive 连接默认不发任何包（无 TCP keepalive 定时器）→ **无无线电唤醒/电池成本**。
- 服务端：hyper/rustls 默认不关 idle 连接；`TimeoutLayer` 是请求级 300s（`lib.rs:78, 967-973`），不覆盖 idle。

**推理与结论**
- 拆除动机判定：**纯合同/身份卫生，非电池**。D3 选 a（90s TTL）成立：收益 = 省去「每轮 TCP+TLS 重付」（心跳 30-300s 节奏下 socket 会被持续复用，基本消灭重连；LAN 每次约 5-15ms + radio tail 机会成本），风险 = 服务器端偶发回收后的首请求 IOException，由既有 H15/预算重试吸收（`SyncRetryPolicy.kt`）。
- 注意两处不要漏改：`RealSyncPort` 的 5 处非轮次 finally（冲突路径）也应换成 TTL 语义或保留（冲突是稀有路径，保留亦可）；TTL 驱逐用单协程惰性扫描，不新建线程。SPKI/origin 身份变化时的立即驱逐语义（`prepareKeepAlive`）必须原样保留——这是 TOFU 安全缝，不是性能件。

### C7（P2）W4 媒体并行：429 担忧不成立，真实约束是锁、内存与预算语义

**证据**
- 服务端唯一 limiter 是 heartbeat 专用（`lib.rs:73, 80` `DEFAULT_HEARTBEAT_RATE_LIMIT=30/60s`；挂载点 `handlers/sync.rs:215-223`）+ create/member 的身份类 limiter；`get_media`（`handlers/media.rs:392-429`）**没有任何 limiter** → 「4 张照片 2 并发撞 429」不成立，design.md W4 的「需核」可以关闭为「已核：不计入」。
- `get_media` 在 family tokio Mutex 内只做 metadata 读，`fs::read` 全量读内存在锁外 → 2-3 并发 GET 之间仅 metadata 段轻微串行；与同家庭其他设备的 commit/心跳互不放大。
- 客户端：`downloadMissingMedia` 串行循环带共享计数 `attempted ≤ 3`、`decodedBytes ≤ 8MiB`（`ReplicaSyncEngine.kt:3020-3081`，常量 `:3521-3523`）；预算 = 共享 cycle ElapsedBudget（`ElapsedBudgetContext.remainingMillis` 只读，协程 context 继承对并发 worker 天然安全）+ MediaGet 单次 30s/8s/1 次（`FamilyHttpBudget.kt` MediaGet）。取消：每连接 `Job.invokeOnCompletion { disconnect() }`（`HttpSyncBackend.kt:1889-1893`）+ worker 取消传播；404-per-media continue 语义按 media 独立保留。
- `stageLogMediaDownloads`（`:1480-1516`）是页应用**前置**（原子接收合同 `:529-537`：全部字节先落 staging 再 Room apply）。

**推理与修复建议**
1. 内存：3 并发 × 10MiB 响应上限 = 30MiB JVM 堆峰值，可接受但写进设计；并发上限建议 `Semaphore(2)`（设计中「2-3」取保守端，NAS 是单机家庭服务）。
2. 并发下 `attempted/decodedBytes` 改原子或由信号量 + 预取检查保证「不超 3 次/8MiB」；worker 数 ≤ 上限本身使该约束平凡成立，但需要测试钉住（C11-W4）。
3. staging 并行化：下载段并行、**staged map 组装与 Room apply 保持单线程**；任一 worker 非 404 失败 → 整页弃（既有语义）。历史缺图路径（`downloadMissingMedia`）并行化更安全，建议 W4 先只做这一处，staging 段二期。

### C8（P1）tip-skip 谓词不完备：四个漏缝 + 版本门论证

**证据**
- 一轮 Foreground 不只是握手+pull：入口有 `resetReceiptJournal.load`、`requireRemoteAllowed`、`cleanupPendingTombstones`（`ReplicaSyncEngine.kt:225-241`）；`sync()` 先 `recoverPendingLocalClearLocked()`（`RealSyncPort.kt:1832`）；LocalWrite 的 `AuthorityProofException`/`SyncHttpException` 会 `markPendingGenerationResync()`（`ReplicaSyncEngine.kt:340-342, 356-362`），Foreground 轮末才 `clearPendingGenerationResync()`；fuse 的 real-trigger 释放发生在 `sync(PullToRefresh)`（`RealSyncPort.kt:1810-1815`）。
- 版本/能力门：握手做协议+能力精确匹配（`ReplicaSyncEngine.kt:395-413`）；但心跳端点**同样过 `require_supported_client`**（`sync.rs:214`）——服务端版本 floor 在心跳上仍被强制。
- 心跳三键含 `generation/head_rev/directory_generation`（`sync.rs:204-253`），判定 `!=` 不判 `>`（`SyncHeartbeatEngine.kt:94-105`）。

**推理**
- 若谓词只查「心跳三键 + 无未发布 + 无缺图」，会在 `pendingGenerationResync` 置位、pending local-clear（设备/成员/家庭删除恢复）未完成、reset receipt 未消费时**错误跳过整轮**，推迟恢复动作——这正是 tip-skip 最不能省的轮。
- 跳过握手 = 跳过 `requireCompatible` 的能力精确匹配。安全底线存在（心跳仍过服务端 floor 门，破玻璃升级会 4xx 打断心跳并路由 `ClientUpdateRequiredException`），但「同 generation 的能力退化」在纯跳过窗口内无网；窗口（D2）把暴露面限制在 ≤120s/300s。

**修复建议**
1. tip-skip 谓词（显式写进设计）：`!hasPendingGenerationResync()` && 无 pending local-clear && resetReceiptJournal 空 && `!hasPendingPublishUnits()` && 无缺图（receipt 过滤后）&& 心跳证明新鲜 && 自**上次成功整轮（含握手）**≤ D2 窗口。
2. 新鲜度锚点：窗口从「last successful full cycle 完成时刻」起算（心跳只做「head 未动」的负向证明，不做能力证明），而非仅从 last heartbeat（C9）。
3. 设计补一句安全论证：跳过握手期间版本 floor 由心跳端点强制（`sync.rs:214`），能力精确匹配的缺失以窗口为界。
4. tip-skip 触发时 fuse/状态语义：`Foreground` 触发的 fuse 释放、`Syncing` 状态闪烁、`lastServerHealthyAt` 都不得因跳过而变脏——列进测试矩阵（C11-W3）。

### C9（P2）「最近一次心跳结果」的存取与新鲜度实现点

**证据**：心跳 loop 拿到 `SyncHeartbeatBeat.Answered` 后只做 `publishHeartbeatAvailability` + `kickHeartbeatRound`（`RealSyncPort.kt:1056-1079`）；kick 只 `pullRequested.set(true)` + `syncSignal.trySend`（`:1075-1079`）。心跳与 sync 通过 `syncMutex` 无互斥（心跳不经 syncMutex）。

**修复建议**
- 在 RealSyncPort 加 `@Volatile var lastQuietProof: QuietProof?`（`data class QuietProof(generation, headRev, directoryGeneration, fullCycleCompletedAtMillis, atMillis)`）；Answered(no-change) beat 更新 `atMillis`；每次成功整轮 finally 更新 `fullCycleCompletedAtMillis`；`sync(Foreground)` 在进入 `syncMutex` 前读 volatile 判定。心跳在飞（未返回）时窗口判定自然偏保守——无需额外同步。
- D2 窗口常量与 W5 退避顶共享来源（同处定义 + 一致性测试），避免「W5 改 300s、窗口停在 120s」的漂移。

### C10（P2）W0 度量可行性：基建现成，缺的是服务端延迟观测

**证据**
- 隔离实例 harness 已存在且完全符合证书隔离规则：`sync/src/test/kotlin/com/lezi/babylog/sync/IsolatedLeziSyncServer.kt`（mktemp 数据根 + openssl 本地证书 + `LEZI_TLS_CERTFILE/KEYFILE` 直供证书，**无需** `LEZI_ALLOW_TLS_BOOTSTRAP`；free loopback 端口；进程关闭即删数据根）。shell 侧模板：`deploy/test-*.sh` 系列与 `deploy/init-tls.sh`（`LEZI_ALLOW_TLS_BOOTSTRAP=1` 仅限隔离实例，AGENTS.md 证书隔离条款）。
- 服务端延迟观测：公共路由 `TraceLayer::new_for_http()`（`lib.rs:973`），tower-http 的耗时行在 **DEBUG**，INFO 无延迟输出；`lib.rs` 无 pull 专属计时（`Instant::now/elapsed` 仅维护任务 `:446`）。`curl -w %{time_total}` 只能测端到端。

**修复建议（最小 instrumentation）**
1. 端到端差值法区分服务端内部耗时：同一空页 `include_live_census=true` vs `false` 各测 N 次，差值 ≈ census 服务端成本（两者其余路径逐字节同构）。
2. 或 `RUST_LOG=lezi_sync=debug`（tower-http latency 行）拿每请求服务端耗时；一行配置，零代码。
3. store 级计时测试（推荐主证据）：`store/tests/pull_tests.rs` 风格 + ignored `#[test]` 在 1k/10k 行种子上循环 `compute_live_census` 取中位数——这直接产出 C1 需要的「重建成本」数字，无需网络。
4. W0 产出写入 design §7 时注明测量环境（开发者机器 vs NAS ARM），两者不可混用。

### C11（P1）测试计划完备性：各 workstream 已列 vs 缺口

**可复用的现有基建（直接点名）**
- 服务端：`tools/lezi-sync/src/store/tests/{pull_tests,causal_tests,test_support}.rs`（census 黄金值/活集/墓碑测试已在 `pull_tests.rs:619-765`）、`conflict_v2_contract_fixture.rs`、`tests/api.rs`。
- 客户端：`sync/src/test` 的 `ReplicaSyncEngineCensusReconcileTest`、`HttpSyncBackendPullWireTest`、`HttpSyncBackendKeepAliveTest`（keep-alive 语义已有测试）、`HttpSyncBackendHeartbeatTest`、`RealSyncPortHeartbeatLoopTest`、`RealSyncPortAppUpdateTest` + `appupdate/`（AUDIT-20260801-P1-01 组）、`FamilyHttpDeadlineAcceptanceTest`、`MediaBranchPerformanceAcceptanceTest`、`FakeSyncBackend`、`RealSyncPortTestSupport.RecordingSyncBackend`、`PullTestSupport`、`DeterministicHttpsFaultProxy`、`IsolatedLeziSyncServer`（真服务端 seam）。
- 注意：任务书与设计提到的 `FakeCausalClearDaos` 在 tree 上**不存在**（grep 零命中）；clear 域的实际基建是 `LocalReplicaClearCoordinatorTestSupport`、`LocalReplicaClear*Test`。设计与票不应引用不存在的名号。

**W1（服务端缓存 + close 移位）**
- 已列：缓存失效正确性（design §2）。
- 缺口：(a) **失效路径枚举黄金测试**——对 store 全部公共写 API（commit 各状态、restore batch、media promote、resolve/withdraw、source-relations、close_empty_open_conflicts 自身）逐个调用后断言「缓存 rev == family_meta.rev 且 census == 全量重算黄金值」；(b) 进程重启 → 惰性重建一次；(c) 多 family 隔离（一家的写不失效另一家条目）；(d) `read_only` store 不写缓存不跑 close（`pull.rs:1112` 分支）；(e) close 移位后「存量残留 + 只 pull 设备」行为测试（C4）；(f) golden envelope byte-compare（W1 前后同输入响应逐字节相同，可挂 `conflict_v2_contract_fixture.rs`）——这是「0.4.8 零影响」的直接证据。

**W2（若按 C3 缩水，则只剩客户端谓词复用）**
- 已列：等价性随机化、折叠缝矩阵、失配闭环、能力矩阵。
- 缺口（若 xor_v1 仍要做）：(a) **seed 化双侧等价**——Rust 侧：确定性 PRNG（如 `StdRng::seed_from_u64`）跑 10^4 ops 序列（insert live/tombstone/revive/update）×≥100 个固定 seed，断言 XOR 增量 == `compute_live_census` 全量；Kotlin 侧：同 seed 同 op 序列（共享 JSON 黄金 vector 文件提交进两仓测试资源）断言客户端折叠 == 服务端最终值——**两侧必须吃同一份序列文件**，否则等价性证明不闭合；(b) deferred 行场景的等价断言（design §3 已列，需落到 `pull_tests` + `ReplicaSyncEngineCensusReconcileTest` 各一条）；(c) 老短语并存（每 head 一次）正确性；(d) 新参数打到老服务器的 422 行为测试（服务端 `deny_unknown_fields` 路径 + 客户端能力门不发参数路径，双侧各一条）。
- 缩水版缺口：(a) 零应用谓词矩阵（任一子句为真 → 重算）；(b) 残余竞态行为测试（假失配 → 恰一次重走 → 无风暴）；(c) 复用条目在 generation 变化后失效。

**W3（tip-skip + keep-alive TTL + piggyback 节流）**
- 已列：AUDIT-20260801-P1-01 回归组。
- 缺口：(a) tip-skip 谓词一票否决矩阵（C8 五个缝各一条，挂 `RealSyncPortForegroundCycleTest`/`RealSyncPortHeartbeatLoopTest`）；(b) 心跳 kick 的 NeedsSync 轮**不**被 tip-skip 吞掉（设计文字已说，需测试钉住）；(c) 跳过轮不改变 fuse/`Syncing`/`lastServerHealthyAt` 可观察状态；(d) keep-alive：TTL 过期后 `disconnect` 被调用、身份（origin/SPKI）变化立即驱逐、心跳把手跨轮存活（扩展 `HttpSyncBackendKeepAliveTest`）；(e) piggyback 节流：心跳/续跑轮不发 `GET /v1/app-update`、真实触发与每小时定时仍发（挂 `RealSyncPortAppUpdateTest`）；(f) `DeterministicHttpsFaultProxy` 下 tip-skip 轮 0 数据请求的断言。

**W4（媒体并行）**
- 已列：仅「取消语义沿用既有 deadline 体系」一句。
- 缺口：(a) 并发下 `attempted ≤ 3` 与 `decodedBytes ≤ 8MiB` 上限仍被尊重；(b) 单 worker 非 404 失败 → 整轮失败语义保持；404 continue 并发安全；(c) cycle 预算耗尽时未完成媒体按既有 checkpoint 留队；(d) staging 并行版保持「全部字节先 staging 再 apply」合同（失败弃页、无 placeholder 行）；(e) `FakeSyncBackend`/`RecordingSyncBackend` 增加并发调用计数断言（≤2-3 并发上限）；(f) `MediaBranchPerformanceAcceptanceTest` 风格的墙钟对比测试（可选，LLM 非门槛）。

**W5（心跳放宽）**
- 已列：wire §1.5 与 sync-trusted-endpoint §7.1 数字同步；服务端限流默认无需改。
- 缺口：(a) `SyncHeartbeatPolicy` 常量/倍增 schedule 测试数字更新（`heartbeat/` 现有策略测试）；(b) **降级梯 30s/120s/600s 不动**的显式断言（防止顺手全改）；(c) 服务端 `DEFAULT_HEARTBEAT_RATE_LIMIT` 注释中的「30s 基线」假设文案更新（`lib.rs:66-73`，纯注释但属于测量假设的文档）；(d) D2 窗口 ≤ 退避顶一致性测试（与 W3 共享常量）。

### C12（P2）compat 矩阵 4 格的测试落点

| 格 | 落点与做法 |
|---|---|
| 新客户端 + 新服务端 | `IsolatedLeziSyncServer` 起 0.5 server + JVM `RealSyncPort` 真链路冒烟（现有 seam 测试模式），断言请求带 `census_algorithm` 且收敛判定走 O(1) 比对路径（可观测点：不发全量重算/不发 cursor-0 重走） |
| 老客户端 + 新服务端 | 纯 server 测试：无参数请求 → 旧短语、envelope 字节与 0.4.x golden 相同（`pull_tests.rs` 风格）；「老客户端」不需要真二进制 |
| 新客户端 + 老服务端 | `FakeSyncBackend` 模拟能力缺席（setup-status 无 `census_xor_v1`）→ D1 决策行为测试（不发参数 / 退化判定路径）；若要真 422 证据，对隔离实例旧版本二进制跑一次（可选） |
| 老客户端 + 老服务端 | 现状回归已被现有测试覆盖，不新增；以 golden envelope 锁面 |

### C13（P2）工作量粗估与排序（以本仓库既有 PR 风格为参照）

| 项 | 粗估 | 说明 |
|---|---|---|
| W0 | 小（1 PR） | 复用 `IsolatedLeziSyncServer` + RUST_LOG + 差值法；半天到一天 |
| W1 | 小-中（采纳 C1 简化后）/ 中（原案） | 失效枚举 + close 移位 + (e)(f) 测试；原案（XOR 折叠）翻倍 |
| W2 原案 | **大** | 双端 + wire + ADR + 矩阵 + 双侧随机化等价——0.5 最大超支源 |
| W2 缩水版（C3） | 小-中 | 谓词复用 + 测试 |
| W3 | 中，建议拆 2 PR | tip-skip+谓词一个 PR；keep-alive TTL+piggyback 节流一个 PR |
| W4 | 中 | 并发 worker + 预算/计数改造 + (a)-(e) 测试 |
| W5 | 小 | 常量 + 文档 + 数字测试 |

- 最可能超支：**W2 原案**（客户端折叠缝 + InvalidationTracker + wire 矩阵三件叠加）；次之 W1 若做 XOR 折叠版的失效枚举。
- **W1 独立先发对 0.4.8 客户端零影响——再论证**：(1) census 是同 head 纯读导出（wire `causal-sync-wire.md:139-141`），缓存命中与否不改变任何响应字节；(2) 响应键集与预算不变，0.4.8 的 fail-closed 面（`requireExactKeys`）无感；(3) `close_empty_open_conflicts` 移位 + 启动维护兜住后（C4），唯一可观察差异（残留 empty-open 的闭合时点）对客户端也不可观察；(4) 回滚 = revert，无 wire 痕迹。成立，可随任意服务端窗口先出。

---

## 2. 总评：最快落地路径与最可能翻车点

**最快落地路径**：W0（用现成 `IsolatedLeziSyncServer` + `RUST_LOG=debug` + census 开关差值法量出重建成本）→ 按 W0 数字裁决 C3 → W1 按 C1 简化版先发（`advance_rev` 单点失效 + 惰性重建 + close 移位进写路径与启动维护，golden envelope 锁零影响）→ W3 拆两 PR（tip-skip 补齐 C8 五缝谓词；keep-alive TTL 保身份驱逐）→ W4 先只并行历史缺图路径 → W5 常量 + 文档。若 W0 证实空页 pull 的 census 重算在 NAS 上 <5ms，则 xor_v1 与 InvalidationTracker 全部砍掉，0.5 缩为一个纯零 wire 版本，D1/D4 两个未决决策随之消失。

**最可能翻车点**：
1. W2 原案三件套（服务端 XOR 折叠 + 客户端折叠缝/InvalidationTracker + wire 能力矩阵）——复杂度买不到可测收益，且 InvalidationTracker 的异步派发窗口使「兜底」名不副实（C1/C2/C3）。
2. tip-skip 谓词漏掉 `pendingGenerationResync` / pending local-clear / reset receipt，把灾难恢复轮静默跳掉（C8）——这是 C 轴唯一的「用户可感知倒退」候选。
3. W1 失效枚举漏写路径导致假失配重走风暴——用「`advance_rev` 单点失效」把枚举问题消解成单点问题，而不是靠测试追全清单（C1/C11）。
