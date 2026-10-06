---
title: 0.5 流式同步结构设计(workstream 分解 + 不变量 + 兼容矩阵)
date: 2026-09-06
status: final(三路对抗审查已整合;D2'/D4'/D5'/D6 已于 2026-09-06 由 owner 拍板,spec 化见同目录 spec.md)
inputs: research.md(同目录);review-A-convergence.md;review-B-wire-redlines.md;review-C-impl-tests.md
docs-note: 2026-09-06 起 docs/prd/ 退役,合同权威为 docs/spec/(contracts/causal-sync-wire.md、platform.md、contracts/sync-trusted-endpoint.md);本文一律引用新路径,research.md 中的 prd 路径以本注为准
owner-constraints:
  - 校验侧常规路径不得出现全量校验(全家庭/全表扫描式 digest)
  - 传输侧常规路径不得出现全量传输;全量重走仅限证明性失配后的有界修复
  - 校验与传输尽可能流式(增量、按 delta 比例付费)
  - 并行:大字节路径允许有界并发;对抗审查并行执行
  - 用户无感;心跳间隔可放宽作为交换
---

# 0.5 流式同步结构设计(draft-v2)

> v1 的核心主张「XOR 多重集摘要 + 双端增量折叠」经三路对抗审查后被**结构性简化**:
> 审查 C1/C2/C3 证明,真实家庭规模(~770 行)下「服务端按 head 惰性缓存 + `advance_rev`
> 单点失效」与「客户端零应用谓词复用 + 否则单次重算」已满足全部约束,XOR wire 增量
> 的剩余收益低于测量噪声,而其风险面(A1 P0 等)真实存在。v2 因此改为**两档**:
> **Tier 1(零 wire)= 0.5 主体**;**Tier 2(census_xor_v1)= 挂起的复活包**,触发条件写死。
> 审查 A 对 v1 折叠方案的 P0/P1 修复条款完整保留在附录 A,供 Tier 2 复活时使用。

## 0. 设计不变量(v2 修订)

| # | 不变量 | 判定标准 |
|---|---|---|
| V1 | **校验侧免每轮全量**:服务端 census 重算从「每页一次」降为「每 (family, head) 至多一次」(每 datum 每变更一次,与轮频/页数解耦);客户端从「每轮一次」降为「仅本轮有应用或本地写时一次」。允许例外:(a) 进程冷启动后每 family 至多一次惰性预热;(b) 证明性失配后的至多一次全量重走(现状语义);(c) [仅 Tier 2] 过渡期为老客户端的旧短语每 head 重算 | 代码路径审阅 + W0 数字 |
| V2 | **传输侧零全量**:增量游标是唯一常规传输;全量重走仅由失配/409 触发且至多一次 | 现状语义保持 |
| V3 | **流式付费**:校验成本 ∝ 变更量(head 推进数 / 本轮应用数),不得 ∝ 家庭规模 × 轮频 | W0 前后对照 |
| V4 | **并行有界**:仅媒体字节路径并行(Semaphore(2)),因果路径串行 | 实现约束 |
| V5 | **用户无感**:无新 UI;更新横幅钝化可接受并写文档 | 验收 |
| V6 | **红线继承**:ADR-0018/0019/0020/0022/0023 语义、closed-key fail-closed、能力只经 setup-status 增量广播、schema fresh-only、TLS 身份不变量、仅前台同步(审查 B 已逐项核对 v1 全合规;v2 是 v1 的严格子集,只减不增) | review-B「已验证不违规项」 |

## 1. 架构(v2)

### 1.1 服务端:per-head census 缓存,`advance_rev` 单点失效(采纳 C1)

- 结构:`AppState` 内 `std::sync::Mutex<HashMap<family_id, CacheEntry>>`,`CacheEntry = {epoch, head_rev, census}`;临界区 O(1);读写都发生在既有 family tokio 锁内的 `run_blocking` 位置,无新锁序(审查 C5)。
- **失效只有两条规则,不做逐路径折叠**(这正是消解 v1 P0/A1 的办法——「部分折叠却推进 rev」这种状态在结构上不存在):
  1. `advance_rev`(`causal.rs:1020-1028`,全部 `rev=rev+1` 形态的唯一通道)推进后删除该 family 条目;
  2. **非自增 rev 写入无条件删除条目**:`restore.rs:117`、`bundles.rs:2301`、`identity/anonymize.rs:101`、`offline_migrate`(A4;防「回退后 rev 复用撞缓存旧值」)。
- 缓存键含 `epoch`(generation 或回退代计数),回退整族失效(B7);`read_only` store 不写缓存(C11-W1d)。
- miss → 惰性重建一次(V1 例外 a;≈1-3ms@770 行,C1 量化),同轮后续页与同 head 后续轮全部 O(1) 命中。
- **没有写路径折叠、没有 XOR、没有双短语并存缓存**(C1/C5:旧短语是唯一短语)。

### 1.2 客户端:零应用谓词复用 + 否则单次重算(采纳 C2;放弃 InvalidationTracker)

- 一轮 pull 满足「全部页 entities 空 ∧ deferred 未解决集空 ∧ cursor 未动 ∧ !hasPendingPublishUnits() ∧ !hasPendingGenerationResync()」→ 活集必然未变,复用上次 (generation, census);否则单次 `localLiveCensusEntries()` 重算(10-30ms@770 行,纯本地 CPU,无 RTT)。
- **不引入 InvalidationTracker**(A3/C2:Room 脏通知是事务提交后异步派发、无 ack,「标脏→对账」要么失效要么退化每轮全量;全仓库零使用先例,新基建不值)。
- **重走强制重建**(A2):任何 cursor=0 重走(失配修复、409、诊断路径)结束时无条件丢弃缓存全量重算一次——保证比对值是真值而非缓存谎言;漏缝漂移因此有界自愈(恰一次重走,终局可匹配)。
- 残余竞态(pull 进行中 UI 线程本地写)记为已知有界风险:假失配 → 恰一次重走 → 无风暴,测试钉住(C2)。
- 比较纪律不变:仅 cursor==head 且无 pending 时比对;失配 → 置 0 一次重走 + 耐久诊断(`ReplicaSyncEngine.kt:2290-2319`)。

### 1.3 为什么 XOR 摘要降级为挂起项(C1+C2+C3 联合结论)

- 服务端残余成本 = 每活跃 head 一次 <3ms 扫描;客户端残余 = 每变更轮 10-30ms 本地重算。均与轮频解耦(V3 达标),远低于用户可感阈值。
- XOR 的账单:`census_xor_v1` 能力广播 + 4 格 compat 矩阵 + 双侧 seed 化等价测试 + wire §1.4 日期化注记 + ADR-0024 + A1/A2/A5/A6/A7/A10 全套失败安全条款;且 `PullQuery` 带 `deny_unknown_fields`(已实核 `sync.rs:1036`),能力门是**硬门**(老服务器对参数 422),协商失败面真实。
- **复活触发条件(写死)**:W0 store 级测试显示 census 重建中位数 >50ms@1k 行,或家庭规模增长到 >10⁴ 活行,或「每 head 一次」在测量中占比显著。触发前不开发。

## 2. Workstream(v2 修订)

> 排序:P2-4(代码)→ W0 → W1(服务端窗口)→ W2/W3/W4/W5(客户端,可并行开发)。

### W0 度量基线(先行;C10 方案)

隔离实例 harness 现成:`sync/src/test/.../IsolatedLeziSyncServer.kt`(mktemp 数据根 + 直供证书,天然合规证书隔离)。
1. **store 级主证据**:`tools/lezi-sync` 加 ignored `#[test]`,1k/10k 行种子循环 `compute_live_census` 取中位数(直接产出 §1.3 裁决数字,无需网络);
2. census 开关差值法:同请求 `include_live_census` true/false 各 N 次端到端差值;
3. `RUST_LOG=lezi_sync=debug`(tower-http 延迟行)拿每请求服务端耗时,零代码;
4. 冷启动后每 family 首拉(含重建)耗时(A15);`hasPendingPublishUnits` 单次成本(A9);缺图查询每轮成本(A8);
5. 媒体串行 vs Semaphore(2) 并发轮墙钟。
产出注明测量环境(开发机 vs NAS ARM,不可混用),写入 §7。

### W1 服务端 per-head 缓存 + 读路径减负(零 wire;采纳 C1/C4/B6/B7)

- §1.1 全部;外加 `close_empty_open_conflicts` 从 pull 读路径移位,三条款(B6/C4):
  (i) 闭合仍按现语义 bump head + entity rev(`causal.rs:2058-2072` 注释:rev 推进就是 conflict_summary 消失的投递信号,不可丢);
  (ii) 落点 = commit dispatch 事务末尾 + resolve/withdraw 事务内(拆「接受 &Transaction 的内部函数」),并为存量/恢复残留的 empty-open 行挂**启动/周期维护**(先例 `CAUSAL_MEDIA_GC_MAINTENANCE_INTERVAL_SECONDS`,`lib.rs:75`)——否则只 pull 不写的设备永远收不到闭合;
  (iii) 文档写明「闭合通常 0 行、有界」。
- 测试(C11-W1):失效路径黄金测试(store 全部公共写 API 逐个调用后断言缓存 rev==family_meta.rev 且 census==全量重算黄金值)、重启惰性重建、多 family 隔离、read_only 分支、存量残留+只 pull 设备、**golden envelope 逐字节比对**(W1 前后同输入响应相同——对 0.4.8 客户端零影响的直接证据)。
- 工作量:小-中;可随任意服务端窗口先发(C13 再论证:纯内部优化,revert 无痕)。

### W2 客户端 census 谓词复用(纯客户端;采纳 C2)

- §1.2 全部。测试(C11-W2 缩水版):零应用谓词矩阵(任一子句真→重算)、残余竞态行为(假失配→恰一次重走→无风暴)、复用条目在 generation 变化后失效、重走后强制重建。
- 工作量:小-中。

### W3 安静轮瘦身(tip-skip + keep-alive TTL + piggyback 节流;采纳 C6/C8/C9/A8/B9/B10)

1. **tip-skip 谓词(C8 全集,一票否决制)**:
   `!hasPendingGenerationResync() ∧ 无 pending local-clear(设备/成员/家庭删除恢复) ∧ resetReceiptJournal 空 ∧ !hasPendingPublishUnits() ∧ 无缺图(receipt 过滤后;**全部缺图为已确认 404 也放行**,A8——404 是永不毕业的驻留态,不得钝化 tip-skip;404 确认态耐久标记走 causal_transport_journal,无 schema 变更) ∧ 心跳新鲜证明 ∧ 自上次成功整轮 ≤ D2' 窗`。
   新鲜度锚点 = **上次成功整轮完成时刻**(C9:心跳只做「head 未动」负向证明,不做能力证明);`RealSyncPort` 加 `@Volatile lastQuietProof(generation, headRev, directoryGeneration, fullCycleCompletedAtMillis, atMillis)`。
   安全论证(写进实现票):跳过握手期间版本 floor 仍由心跳端点强制(`sync.rs:214` `require_supported_client`);能力精确匹配缺失以 D2' 窗为界。
   声明 trade(B10):tip-skip 轮不做 census 比对,漂移检测退化为「仅 head 移动的轮」——与 0.4.8 验收第 1 条的改写(A9)一并写进 W5 文档。
   跳过轮不得弄脏 fuse/`Syncing`/`lastServerHealthyAt` 可观察状态(测试矩阵)。
2. **keep-alive TTL(C6 裁决 D3)**:轮末 `releaseForegroundKeepAlive()` 全拆改 TTL 90s 空闲释放(< 平台池 ~5min 超时,不双花;空闲连接无包,无无线电成本)。**保留** `prepareKeepAlive` 的 origin+SPKI 身份立即驱逐语义(TOFU 安全缝,非性能件);`RealSyncPort` 5 处冲突路径 finally 保留现状拆除(稀有路径)。收益真实但小(每轮省 TCP+TLS 重付 ~5-15ms)。
3. **piggyback 节流(B9 钉死)**:门控 = 「用户真实触发(回前台/下拉)∨(距上次成功 app-update 检查 >1h ∧ 本轮因其它原因已发生)」;**不新增任何唤醒源**(仅前台红线,`sync-trusted-endpoint.md` §7.1 闭合触发器清单);AUDIT-20260801-P1-01 判据只应用在仍 piggyback 的轮。
- 文档落点点名:wire §1.5 行为注记(tip-skip 消费心跳三键的新合同)+ `platform.md` §4.2 触发行 + `sync-trusted-endpoint.md` §7.5 发现行。
- 测试(C11-W3):谓词一票否决矩阵(五缝各一条)、心跳 kick 的 NeedsSync 轮不被吞、跳过轮状态不变脏、TTL 过期 disconnect/身份变化立即驱逐/心跳把手跨轮存活(扩 `HttpSyncBackendKeepAliveTest`)、节流行为(挂 `RealSyncPortAppUpdateTest`)、`DeterministicHttpsFaultProxy` 下跳过轮 0 数据请求。
- 拆 2 PR:tip-skip 一个;keep-alive TTL + piggyback 节流一个。工作量:中。

### W4 媒体 GET 有界并行(采纳 C7;一期只做历史缺图路径)

- **已核**:媒体 GET 无任何 limiter(`handlers/media.rs` 无限流;30/60s 是 heartbeat 专用),429 担忧关闭;真实约束 = staging 原子合同、3×10MiB 堆峰值(写进设计)、共享计数原子性。
- 一期:`downloadMissingMedia`(`ReplicaSyncEngine.kt:3020-3081`,仅 Foreground/PullToRefresh 触发)并行化,Semaphore(2);`attempted≤3`/`decodedBytes≤8MiB` 改原子;404-per-media continue 语义按 media 独立保留;任一 worker 非 404 失败 → 整页弃(0 行写入、cursor 不动,既有语义)。
- 二期(可选):`stageLogMediaDownloads` staging 段并行——**staged map 组装与 Room apply 保持单线程**,原子接收合同(`:529-537`)不变。
- 测试(C11-W4):并发上限断言(≤2)、预算/计数上限并发下仍被尊重、单 worker 失败整轮语义、预算耗尽 checkpoint 留队、staging 失败弃页无 placeholder。
- 工作量:中。

### W5 心跳放宽 + 验收改写

- `SyncHeartbeatPolicy` 基线 30s→60s、退避顶 120s→300s;**降级梯 30s/120s/600s 不动**(显式断言测试,防顺手全改,C11-W5)。
- 文档目标(B11 修正):wire §1.5 限流子弹的节奏数字**必须**同步;`sync-trusted-endpoint.md` §7.1 是匿名探针的数字,**不改**,仅核对心跳叙述不出现旧数;`lib.rs:66-73` limiter 注释的「30s 基线」假设文案更新;D2' 窗常量与退避顶共享来源 + 一致性测试(C9)。
- **验收改写(A9)**:「对端写入可见性上限 = 心跳间隔 + 一轮」(替换 0.4.8 验收第 1 条的 30s 表述)。
- 工作量:小。

## 3. 语义保持专节(v2)

- **deferred 行**:行为逐字节保持——服务端 census 计 deferred 行、pull 扣发、cursor 到 head 失配→一次重走→诊断停的闭环原样(v2 无短语变化,天然等价;A13「折叠输入以 entities 行活态为准」规约仅 Tier 2 适用)。
- **墓碑/物理删除**:客户端谓词方案不做折叠,无 A6 风险面;重算天然以活态为准。
- **灾难恢复/水位回退**:心跳判 `!=` 不判 `>` 保持;服务端缓存 epoch 键控 + 非自增 rev 无条件失效(A4/B7);客户端复用条目在 generation 变化后失效。
- **媒体履行/晋升**:服务端不折叠,rev 推进→条目失效→下次重建,无漏缝面。
- **冲突路径**:不动(commit-first、choice-only、懒加载详情、CAS resolve 全部原样)。

## 4. 兼容与发布门(v2)

Tier 1 零 wire → 兼容矩阵坍缩为「全组合 = 现状行为,响应逐字节不变」(golden envelope 测试锁面)。Tier 2 矩阵见附录 A。

**发布前置门(B3,显式)**:
1. **P2-4**:`package-nas.sh` 的版本 stanza 目前最高 0.4.7(0.4.8/0.5.x 落 `*` 即 exit 1)、`validate-nas-package.sh` 只钉 0.4.7 身份——0.5 任何发布(服务端镜像或 APK 通道,APK 自更新同走 NAS promote)前必须补 stanza + 配对断言。这是仓内代码修复,工单化即可。
2. **P2-7**:操作者(用户)不在 docker 组,`push-and-deploy.sh` 停在权限——需要一次操作者授权窗口。
3. 前两条未解前,W1 不上线 NAS、0.5 APK 不走 OTA(侧载不受此限但仍建议先解 P2-4)。

## 5. 决策记录(2026-09-06 owner 交互式审查拍板;已固化进 spec.md)

| # | 决策 | **拍板结果** |
|---|---|---|
| D4' | 0.5 范围 | **Tier 1 全套**(P2-4+W0-W5),Tier 2 挂起(触发条件 §1.3);设计升级为 spec |
| D5' | 心跳档位 | **60s 基线 / 300s 退避顶**(降级梯不动;A9 验收改写随行) |
| D2' | tip-skip 新鲜度窗 | **= 心跳退避顶**(60/300 档即 300s;接受与心跳档位耦合,常量共享来源+一致性测试) |
| D6 | P2-7 操作者窗口 | **现在排期**:P2-4 由维护侧随 0.5 版本定名落地(0.4.8 的同款缺口经实核已在 2026-09-05 评审后的提交中解决,stanza 与配对断言均在),P2-7 由 owner 约操作者窗口 |
| ~~D1~~ | ~~新客户端遇老服务端回退~~ | v2 已消解:零 wire 无参数无回退面(原决策归档附录 A 条款 6) |
| ~~D3~~ | ~~keep-alive 策略~~ | C6 已裁决:90s TTL、保留身份驱逐、冲突路径不动 |

## 6. 对抗审查记录(2026-09-06,三路并行)

| 路 | 文件 | 结果 |
|---|---|---|
| A 收敛正确性 | review-A-convergence.md | 16 攻击:P0×1(A1)、P1×4(A2-A5)、P2×6、P3×5。总评:v1 方向可立、字面不可立 |
| B wire 红线 | review-B-wire-redlines.md | 13 攻击:P1×3(B1-B3)、P2×7、P3×3;六项红线核对全合规 |
| C 实现与测试 | review-C-impl-tests.md | 13 攻击:P1×4(C1-C3/C8)、P2×9;含工作量估算与最快路径 |

**交叉勘误(主 agent 实核代码后修正,审查文件本身不改)**:
1. B1 机制说反:`PullQuery` **带** `#[serde(deny_unknown_fields)]`(`sync.rs:1036`,实读确认;与 C3 一致)——老服务端对新参数是**硬 422**,不是静默忽略旧短语。后果:失败面更安全(fail-closed 触发 H15 重试),但能力门因此是硬前置;「服务端回滚窗口」失败模式改写为 422 自保(附录 A 条款 5 修订版)。
2. C11 声称 `FakeCausalClearDaos` 不存在——误;它在 `domain/src/test/kotlin/com/lezi/babylog/domain/carelog/FakeCausalClearDaos.kt`(实存,估计只 grep 了 sync/)。
3. 审查引用的 `docs/spec/contracts/` 路径正确(2026-09-06 重构后权威);v1 与 research.md 的 `docs/prd/` 引用已过时,以本注为准。

## 7. 整合结论(v2)

**采纳并改入正文**:A1(消解折叠面)、A2(重走强制重建)、A3(放弃 InvalidationTracker)、A4(非自增 rev 无条件失效)、A8(404 缺图放行)、A9(验收改写)、B3(发布前置门)、B6/C4(闭合移位三条款)、B7(epoch 键控)、B8(V1 重写)、B9(piggyback 时间门控)、B10(安静轮不比对显式 trade)、C1/C2/C3(架构简化)、C5(缓存落点)、C6(keep-alive 裁决)、C7(限流担忧关闭)、C8(谓词五缝)、C9(QuietProof)、C10(度量方案)、C11(测试矩阵)、C13(工作量)。
**未采纳但记录**:A5/B5(禁截断,归附录 A)、A6/A7/A10/A12/A13(仅 Tier 2 适用)、B1 原文机制(被勘误 1 替换)、B11 §7.1 定位(修正进 W5)、B12/B13(仅 Tier 2,D1 归档)。
**挂起**:Tier 2 = census_xor_v1,复活包附录 A,触发条件 §1.3。

### W0 度量数字(2026-09-06,多轨合并时编排者统一入档)

**测量环境**:服务端数字 = 开发机 AMD Ryzen 7 7800X3D / Linux 7.2.2 / rustc 1.95.0
/release profile(`opt-level=z`, `lto=true`,进程内 HTTP Rig + TempDir 数据根,合规隔离);
媒体墙钟 = 同机 JVM 测试 harness(runBlocking 事件循环,80ms/GET 注入,7 样本中位)。
NAS ARM 数字未测,不与上混用。原始数据与复测命令:`issues/notes/01-measurements.md`、
`issues/notes/02-after-numbers.md`、`issues/notes/2026-09-06-b-track-w3-w4-landing-notes.md`。

| 指标 | before(0.4.8 现状) | after(0.5) |
|---|---|---|
| `compute_live_census` 全量重算中位(store 级) | 0.60–0.68ms@1k 行;4.4–5.5ms@10k 行(≈0.6–0.7µs/活行) | 不变(缓存 miss 时惰性重建用同一函数) |
| quiet-round pull 端到端,census 开 | 1.13–1.44ms | 0.37–0.50ms |
| quiet-round pull 端到端,census 关 | 0.62–0.78ms | 0.38–0.50ms(读路径移除 empty-open 闭合扫描,惠及所有 pull) |
| census 开/关差值(census 纯增量) | 0.51–0.68ms | **≈0(0–3µs,噪声级)** |
| 冷启动后每 family 首拉(含重建) | 1.9–2.8ms(现状每 pull 皆如此) | 1.9–2.2ms,此后每 (family, head) 至多一次 |
| 媒体串行 vs Semaphore(2) 补图墙钟 | 串行 970ms(12 张 × 4 轮) | **648ms(1.50×,−33%)**;结构上限 = 每轮 ≤3 次 GET × 2 并发 = 2 波/轮 |
| 媒体峰值堆 | 串行同量级 | ≈3×10MiB(两个在途响应 + 一个在 adopt) |

**golden 等价**:`api.rs::pull_census_cache_keeps_envelopes_byte_identical` 断言缓存冷/热、
census 开/关、写推进 head 前后的同请求响应**逐字节相同**(仅差 `live_census` 键)——
零 wire 的直接证据;store 级另有 hit/rebuild/全量重算三路字节比对。

**Tier 2(census_xor_v1)复活裁决**:复活条件为 census 重建中位 >50ms@1k 行;实测
0.60–0.68ms,**距阈值 ~70× 余量,不触发,Tier 2 维持挂起**(design §1.3 条件继续有效)。

**缺口如实记录**:`hasPendingPublishUnits` 单次成本、缺图查询单次成本两项 01 子项未单独
实测(S 轨无 JVM Room 运行时,委派后各轨均未接);两者均为纯本地查询、有界 O(pending)
扫描,风险低,留 09 联调补测或在验收观察中复核。09 联调(下节)在 JVM 隔离实例上为
内存假件运行时,无 Room 运行时,故此两项仍维持「未单独实测」,移入验收观察清单。

**合并期修复(各轨独立测试未暴露)**:W4 并行 adopt 与 W2/W3 的 port 级 IO 线程并发交汇,
暴露 `LocalMediaEditGuard` 共享快照 map 的生产竞态(已加粗粒度锁)与测试内存假件的
`ConcurrentModificationException`(已换 CopyOnWriteArrayList);详见合并提交 b28d6e9f。

### 09 集成联调观察(2026-09-06,收官轨;隔离实例,零 NAS/零 ssh)

**环境**:真实 `lezi-sync` 0.5.0 release 二进制(`cargo build --release --locked`,
`opt-level=z/lto`)+ 真实 Android 客户端栈(ReplicaSyncEngine / HttpSyncBackend),
loopback TLS + mktemp 数据根(证书测试隔离合规);观察仪 = 记录每个转发请求的
loopback TLS 代理。新增测试:`sync/.../RealServerQuietRoundSmokeTest.kt`
(`LEZI_SYNC_BIN` 指向 release 产物运行);既有真实服务端 seam 族
(`RealServerMediaReceiptFaultSeamTest`、domain 侧 `CareLogRealServerSeam*` 四件)
同二进制全部通过。

| 指标 | 0.4.8 现状(理论/实测) | 0.5(联调实测) |
|---|---|---|
| 安静前台返回轮请求数 | 3(握手 + 空 pull + 更新检查) | 引擎层实测 = 2(握手 + 空 pull,0 写 0 媒体);port 层 tip-skip 生效时 = 0 数据请求(契约测试钉住) |
| census 重算次数(head 1200 行) | 每 pull 页一次 | 真线观测:新 head 首页 189–290ms(含 200 行传输+重建),同 head 后续页/后续轮 1–6ms、稳定轮 1ms(命中)——每 (family, head) 恰一次重建 |
| 心跳探针节奏 | 30s 基线 / 120s 退避顶:安静稳态 ≤30 次/小时 | 60s 基线 / 300s 退避顶:安静稳态 ≤12 次/小时(−60%,基线减半);常量与一致性由 `SyncHeartbeatPolicy` 断言测试钉住 |
| 心跳三键答案 | — | 真端点对收敛会话返回 (generation, headRev, directoryGeneration) 与会话全等(no-change 形态,tip-skip 证据臂) |
| 1.2k 行两次稳定头整轮 pull | — | 页数 1 == 1,零 rewalk、零诊断——双侧 census 缓存在真线上联合正确(漂移会触发 cursor=0 全量重走) |

**升级路径佐证(0.4.8→0.5.0 零感知)**:零 wire、零 schema(Room 29 / contract 6 不动);
golden envelope 逐字节等价由 `api.rs::pull_census_cache_keeps_envelopes_byte_identical`
与 store 级 `census_cache_tests::cache_hit_serves_bytes_identical_to_full_recompute` 钉住;
generation 热恢复语义不变由既有 `RealSyncPortDisasterRestoreTest`、
`ReplicaSyncEngine` generation 重对账测试族与 conflict-v2 契约 fixture
(`conflict_v2_contract_fixture`)覆盖;发布身份配对由
`deploy/test-nas-release-identity.sh`(0.5.0/30 + 0.4.8/29 回归)与
`deploy/test-package-nas-app-update.sh` 双冒烟 exit 0 验证。

---

## 附录 A:Tier 2(census_xor_v1)复活包(挂起)

**机制**(v1 §1.1 原案):XOR 多重集摘要(完整 256bit,**禁止截断**,A5/B5;空集 = 64 个 '0',A12)+ `census_algorithm=xor_v1` 请求参数选短语 + `census_xor_v1` 能力只经 setup-status 广播(B12:客户端知识 = availability 探针快照)+ 4 格 compat 矩阵 + wire §1.4 日期化加性注记(B4)+ ADR-0024。

**硬性修复条款**(复活时全部强制):
1. 服务端失败安全底座(A1):折叠与 cached rev 推进解耦;digest 可处 STALE;仅 `cached rev==head ∧ digest≠STALE` 才 O(1) 返回。任何实现缺陷退化为「多一次重建」而非错值当新鲜。
2. 客户端重走强制重建(A2)+ 幂等跳过路径折叠规约(A10:唯一合法折叠入口 = 应用前后 live 态 diff,天然幂等,重放/跳过均 no-op 或恰一次)。
3. 事务回滚窗口(A7):delta 只在事务成功提交的回调应用于缓存,失败即丢弃。
4. 物理 DELETE/墓碑(A6):折叠钩子同事务先 SELECT 受影响行 diff 四态(live→live/live→墓碑/墓碑→live/墓碑→墓碑);deleteAll 类整段失效重建。
5. 参数协商失败模式(勘误 1 修订):未知 `census_algorithm` 值 → 新服务端 422 fail-closed(B2);老服务端 422 硬拒 → 客户端自保 = 丢弃能力位 + 免参数重试一次再定性(B1 修订);能力位进程内易失,仅 availability 探针 setup-status 刷新。
6. D1(归档):过渡期回退默认取 c(7 条 COUNT 比对,便宜保底;B13);仅当配对升级 ≤ 一个发布窗时可用 a(跳过比对,wire §1.4:144-145 有明确授权)。
7. 测试:双侧 seed 化等价(同一份 JSON op 序列文件提交进两仓测试资源)、deferred 场景等价、随机活集变动序列 XOR 增量==全量重算(C11-W2)。

**收益前提**(§1.3 触发条件不满足则永不开发):家庭规模 10×-100× 增长后,per-head 重建与每变更轮客户端重算成为可测量成本。
