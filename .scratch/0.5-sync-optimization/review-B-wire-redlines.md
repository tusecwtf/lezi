---
title: 0.5 对抗审查 B 轴:wire 兼容与冻结红线
date: 2026-09-06
status: review-only(只读审查;被审对象 design.md draft-v1 / research.md 2026-09-06)
reviewer: 对抗审查员 B(wire 兼容与冻结红线)
---

# 0.5 流式同步设计 — B 轴攻击报告(wire 兼容与冻结红线)

被审版本:`.scratch/0.5-sync-optimization/design.md`(draft-v1,2026-09-06)。核对基准:wire 合同
`docs/spec/contracts/causal-sync-wire.md`(0.4.0 冻结 + 0.4.7/0.4.8 加性注记)、ADR-0018/0019/0020/0022/0023、
`docs/spec/platform.md` §4.2/§4.2.1、`docs/spec/contracts/sync-trusted-endpoint.md` §7.x、AGENTS.md 冻结条款,
以及 2026-09-06 tree 实读代码(path:line 均为当日 tree)。

## 结论速览

设计在**能力广播通道、closed-key、schema fresh-only、TLS、ADR 语义、floor** 六个方面守住了红线
(核对见文末「已验证不违规项」);真正的攻击集中在**参数协商的失败模式没写全**(B1/B2)、
**NAS 发布前提是假的**(B3)、以及 **W1/W3 若干合同细节未声明**(B6-B10)。

## 攻击表

| # | 严重度 | 攻击点 | 证据 | 推理 | 修复建议 |
|---|---|---|---|---|---|
| B1 | **P1** | **能力陈旧/服务端回滚的失败模式缺失**。兼容矩阵只有「能力缺席」一行,没有「能力曾见过、现在消失」(NAS 回滚是一等公民流程,AGENTS.md 冻结回滚入口) | design §1.3/§4;`tools/lezi-sync/src/handlers/sync.rs:1038-1050`(`PullQuery` 无 deny 语义,serde URL-encoded 默认忽略未知 query 键);`sync/.../engine/ReplicaSyncEngine.kt:2303-2319`(失配→cursor=0 全量重走→再失配→耐久诊断) | 服务端回滚(换容器必然重启→客户端必然经历探针失败→重连后重新观察 setup-status)把窗口收窄,但**不收窄到零**:长前台会话内回滚完成、探针尚未重跑时,客户端仍持陈旧 `census_xor_v1` 继续发 `census_algorithm=xor_v1`;老服务端静默忽略该参数并返回**旧短语**,客户端按 XOR 比对→必然失配→每轮一次全量重走+耐久诊断回执,直到下次 availability 探针改写能力位。这是自愈但有噪声、且产出用户可见警告的降级态,设计未列举 | (a) 能力位定为**进程内易失**,仅由 availability 探针的 setup-status 读刷新(先例 `RealSyncPort.kt:741-754`);(b) 失配自保:本轮若带过 xor 参数,先**不带参数重取末页**比对一次——旧短语吻合即判「能力陈旧」,撤销能力位、跳过重走;(c) 兼容矩阵补第 5 行「能力陈旧(服务端回滚)」 |
| B2 | **P1** | **新服务端对 `census_algorithm` 未知值的行为未定义**。设计只写了「参数缺席→旧短语」,没写「参数存在但值未知」 | design §1.3;wire §1.4 fail-closed 清单(`causal-sync-wire.md:155-158`);heartbeat `wait` 先例(`handlers/sync.rs:186-214`:已知占位参数的**非法值** 422,wire §1.5:193-194「非整数值按普通查询漂移以 422 拒绝」) | 若新服务端静默忽略未知**值**(与老服务端忽略未知**键**同款默认行为),未来 `xor_v2` 客户端在只认 `xor_v1` 的服务端上会静默拿到旧短语→按 v2 比对→失配重走循环,且**永久**而非过渡。协商参数必须值级 fail-closed,否则本设计的协商机制本身给下一代埋雷 | wire §1.4 增补条款写死:未知 `census_algorithm` 值 → 422 fail-closed;已知值才按短语切换;参数缺席 → 冻结的旧短语(0.4.7 定义逐字不变) |
| B3 | **P1** | **NAS 发布前提当前为假**:「W1 可先随服务端任意窗口出」「过渡期短(自托管 APK,自控 floor)」两句的前提都不成立 | `.scratch/0.4.8-sync-heartbeat/review-2026-09-05-p2-plus.md` §P2-4(`package-nas.sh` `case "${version}"` 最高 0.4.7;0.4.8/0.5.x 落 `*` 即 exit 1;`validate-nas-package.sh` 只钉 0.4.7 身份)、§P2-7(操作者不在 docker 组,`push-and-deploy.sh` 停在权限);research §4.1 自己也引用了这两条 | W1/W2 的服务端半区**今天无法到达家庭 NAS**;0.5 客户端 APK 分发同样走 NAS 打包 promote(app-update.json fail-closed pair,tech.md §4.2「CD 原子对发布」)。若不先把 P2-4/P2-7 列为 0.5 前置工单,D1 的「自控 floor、几天配对完成」变成无限期,D1a(跳过摘要比对)从过渡态变成家庭的**长期常态**,收敛网静默变薄 | design §4 增加显式「发布前置门」:0.5 任何服务端/客户端发布前必须 (i) 为 0.5 版本补 `package-nas.sh` stanza + `validate-nas-package.sh` 配对断言(解 P2-4);(ii) 取得能 docker 的操作者窗口(解 P2-7);(iii) 走 AGENTS.md propose-then-confirm CD。在此之前 W2 不得进入客户端发布 |
| B4 | P2 | **`include_live_census`/`key_digest` 定义属冻结条款,加「算法维度」必须走注记纪律而非原地改写** | wire `causal-sync-wire.md:122-145`(0.4.7 加性普查条款:「key_digest 是 lowercase hex SHA-256,输入为…排序…」为无条件定义);先例:0.4.7 普查与 0.4.8 §1.5 都以**日期化加性注记**入文,头部「当前 tree」行同步 | design W2 只写「wire 文档 §1.4 增补短语条款」,未声明冻结条款的处理方式。原地修改 0.4.7 段落会破坏「协议冻结合同」的修订纪律;正确形状是新增日期化注记并把旧定义显式 scope 到「`census_algorithm` 缺省时」 | §1.4 增补独立日期化注记(0.5),内容必须含:旧定义仅在参数缺席时适用、参数值级 fail-closed(B2)、不新增任何响应顶层键;同步更新文档头「当前 tree」行 |
| B5 | P2 | **「128bit 截断可选」与「响应形状不变」自相矛盾** | design §1.1(`H = SHA-256,128bit 截断可选`)vs §1.1(`响应形状不变!`)与 §1.3;wire `causal-sync-wire.md:130-145`(示例 64 位 hex;预算句「按满配七类(64 位 digest)把普查字节计入页预算」) | 截断到 128bit → 32 hex 值 → 即使在 opt-in 路径上,`key_digest` 的值形状也不再是 64 位 hex,破坏冻结字段形状与页预算常数;客户端若按 64-hex 校验还会 fail-closed | 删掉截断选项,固定完整 SHA-256 / 64 hex;「128bit 截断」从设计中移除或降级为「明确否决的选项」 |
| B6 | P2 | **W1 移动 `close_empty_open_conflicts` 的合同细节未声明**:它 bump rev 吗?移动后「拉取发现冲突已闭合」的投递信号由谁保证? | `store/pull.rs:1109-1115`(现状每次 pull 先闭合);`store/causal.rs:1561-1596`(闭合并 `bump_entity_rev_only`);`causal.rs:2058-2072` 原注释「**Branch-only write must advance pull rev so conflict_summary is discoverable**」——rev 推进就是 conflict_summary 消失的 wire 投递机制 | 设计只说「从读路径移到写路径」。若移动时丢了「bump head + entity rev → 实体重投递」语义,客户端将永远收不到「该实体 conflict_summary 已消失」的信号;若闭合只在 commit/resolve 事务内触发,由 crash/restore/import 产生、或升级时已存在的空分支 open 行将**永久滞留**(今天下一次 pull 就会治愈)。对「pull 是纯读」:wire 只有普查路径纯读条款(`causal-sync-wire.md:141-142`),移出闭合反而更合规,无冲突 | W1 明确三条:(i) 闭合仍按现语义 bump head + entity rev;(ii) 闭合挂在一切可能清空 branch 集的写事务(commit/resolve/withdraw)同一事务内,并为存量/恢复产生的滞留行保留一次性清理或回退路径;(iii) 文档写明「闭合通常 0 行、有界」 |
| B7 | P2 | **census 缓存一致性是「同一 head 每页相同」wire 条款的存续条件**;rev-only 变动与媒体 finalize 路径是折叠缝清单里最容易漏的两类 | wire `causal-sync-wire.md:140-141`;锁纪律实证:pull `handlers/sync.rs:1212-1218`、commit `:595-596`、resolve/withdraw `:699/:760/:902`、media staging `handlers/media.rs:155-164`、bundle commit `media.rs:522-523`(全部同一 family lock,锁内折叠因此可行);但 `bump_entity_rev_only`(causal.rs:2058-2072)是**活集不变的 rev 推进**;pull handler 注释 `sync.rs:1248-1251`「Finalization advances their revisions」指向媒体 finalize 的 rev 推进 | 漏一条缝 → 同一 head 的 census 不再确定 → 客户端假失配 → 每轮重走+诊断(wire 条款事实性失效)。另:水位回退后 rev 可能**重新到达**曾缓存过的数值(42→回退 40→新写入又到 42),仅按 `(family, rev)` 键存缓存会命中回退前的陈旧值;design §3 只写了「随 rev 回退失效重建」,未覆盖「rev 复用」 | W1/W2 验收清单强制:(i) 枚举全部 bump-rev 路径并逐一挂折叠/失效(含 closure、媒体 finalize、restore);(ii) 缓存键含代际标识(如 generation 或回退代计数),回退即整族失效;(iii)「同一 head 双页同值」与「回退后 rev 复用」进测试矩阵 |
| B8 | P2 | **design 自己的 V1 不变量表被 §1.2 的过渡项违反**:旧短语「每 head 至多重算一次」是表外新增的全量例外 | design §0 V1(例外仅 (a) 冷启动预热、(b) 失配重走)vs §1.2(「老短语…同缓存里并存一份『每 head 至多重算一次』的旧值」) | 过渡期只要有老客户端,每次 commit 推进 head 后的老客户端 pull 都触发一次全活集扫描——O(家庭)×有变轮频,正是 V1 要禁的形态。设计诚实标注了「窄化版」但没有把它写进不变量表,审查与实现都会失配 | V1 增补例外 (c):**过渡期旧短语服务**,每 head 至多重算一次,仅服务未升级客户端,随「下一个大版本撤旧短语」计划一并退役;在 §4 发布顺序里写明退役触发条件 |
| B9 | P2 | **W3「低频定时(如每小时)」必须钉死为「既有轮上的门控谓词」,否则撞前台-only 红线** | `docs/spec/contracts/sync-trusted-endpoint.md` §7.1:243(「**只保留以下触发器**」闭合清单)、:266-267(「不是…后台轮询。切到后台仍停止探测和同步」);`docs/spec/platform.md` §3:15(「**同步不做** WorkManager 后台轮询(规格:仅前台)」) | 若「每小时定时」实现为新的唤醒源,就是 §7.1 闭合触发器清单之外的新触发器+后台化,直接违规;若实现为「已发生的轮(心跳 kick)是否附带 app-update」的时间门控,则不新增触发器、且心跳本就仅前台,合规。安静期里心跳轮是唯一轮,不加时间门控则 app-update 发现会**永久静默**——所以门控语义是必需而非可选 | design W3 改写为:「piggyback 门控 = 用户真实触发 ∨ (距上次成功检查 > 1h 且本轮因其它原因发生)」;不新增任何唤醒源;同时点名两处文档目标:tech.md §4.2「触发」行 + sync-trusted-endpoint §7.5「发现」行;保留 AUDIT-20260801-P1-01 全部判据于仍 piggyback 的轮(仅成功后分类、失败不拆壳,`RealSyncPort.kt:1884-1902`) |
| B10 | P2 | **W3 tip-skip 的两个未声明代价**:(a) 心跳三键消费面扩宽需文档注记;(b) 安静轮的 census 比对被整段跳过,收敛网变薄 | wire `causal-sync-wire.md:205-206`(「探针的**唯一**客户端动作是比较三键并决定是否触发既有前台同步轮」);`ReplicaSyncEngine.kt:2290-2321`(现状每轮 head 处比对,含空页轮);design §W3-1 | (a) 字面上「决定不触发」仍在原句内,但「用 ≤D2 新鲜度的心跳证明可跳过握手+pull」是新的消费合同,应按 §7.6/§7.7 先例写实现注记(或 §1.5 注记),design 只写「文档注记」未指名;(b) 现状安静轮也会比对 census(空页也返回 census),tip-skip 后漂移检测退化为「仅在 head 移动的轮」;与 D1a(老服务端不比对)叠加,census 网只在有真实拉取的轮生效——这是 V5「用户无感」之外一个未声明的语义权衡 | (a) 注记落点写明(wire §1.5 行为合同或 sync-trusted-endpoint §7.6 式注记);(b) 在 §3 或 V 不变量中显式接受「安静轮不比对」的检测时延 trade,并交 A 轴评估其与本地缓存漂移兜底的组合 |
| B11 | P3 | **W5 文档目标定位偏差 + 限流余量确认** | wire §1.5 限流子弹(`causal-sync-wire.md:195-197`,含「30s 基线、120s 退避、降级期 30s/120s/600s」节奏数字——**必须**随 W5 更新);`sync-trusted-endpoint.md` §7.1:253 的数字(「30 秒租约;失败 30 秒/2 分钟/10 分钟退避」)属**匿名探针**,W5 不改它;`lib.rs:66-72` limiter 注释(30/60s ≈ 真实节奏一个数量级以上余量) | W5 后最坏节奏:60s 基线 ±20% 抖动 → 60s 窗内约 2 拍;降级梯不动仍 30s → 2 拍/窗。对 30 次/60s 限流余量 ~15×,服务端默认无需改(设计判断正确);但 design 把 §7.1 列为数字同步目标不准确,且漏了 wire §1.5 限流子弹里「合法前台节奏」字样的同步更新 | 修订 W5 文档清单:改 wire §1.5 限流子弹节奏数字;§7.1 仅核对心跳叙述不出现旧数字即可;可选在 `lib.rs` limiter 注记追加一句新节奏仍远低于上限 |
| B12 | P3 | **客户端能力观察缝未指名**;无心跳式 discovery 兜底(可接受但应写明) | wire §1:28(setup-status 增量、握手键集冻结,**不得**混入)+ `lib.rs:103-112`(`CAPABILITY_SYNC_HEARTBEAT_V1` 注释:handshake byte-exact);`RealSyncPort.kt:741-754`(setup-status 的唯一常驻观察点是 availability 探针);wire §1.2:43-44(setup-status **不是**普通周期前置) | census_xor_v1 的客户端知识 = 最近一次 availability 探针的快照,新鲜度受探针时机限制(正是 B1 的根源);与心跳不同,它没有 one-shot discovery 兜底,但**不需要**(缺能力=现状,安全方向)。握手无需任何存在性证明——请求参数即协商、响应在原信封内切换短语,这个设计判断是对的,应显式写进 design 以免实现票走样去碰握手键集 | design §1.3 补两句:观察缝 = availability 探针 setup-status 读;能力缺省/陈旧的安全方向声明(缺→旧短语;陈旧→B1 的失配自保) |
| B13 | P3 | **D1 的 wire 依据确认与推荐** | wire §1.4:144-145(「字段缺失 = 旧服务器,跳过比对不算错误」——老服务端跳过比对有明确 wire 授权) | D1a 不违规;但结合 B3(NAS CD 拖延则过渡期无限拉长)与 B10(安静轮已不比对),建议若过渡期可能变长则取 D1c(7 条 COUNT 比对,便宜、保留存在性网)作为默认,a 仅作过渡期短时的加速 | D1 决策建议:默认 c,「过渡期 ≤ 一个发布窗」成立时可用 a;把该条件与 B3 前置门挂钩 |

## 已验证不违规项(设计守住的红线)

1. **能力广播纪律**:新能力只进 setup-status 增量清单(wire §1:28;先例 `sync_heartbeat_v1`,`identity.rs:54-68`),不碰握手字节精确键集(`ReplicaSyncEngine.kt:395-413` 精确匹配;`lib.rs:98` SOURCE_SYNC_HANDSHAKE_CAPABILITIES)。协商走请求参数+原信封切换,**无需**握手响应存在性证明——正确。
2. **closed-key**:响应不新增顶层键,「仅追加一个顶层键 live_census」条款(wire §1.4:125-126)在参数缺席时逐字节成立;0.4.5/0.4.6 exact-keys 客户端不受影响;无 echo 键。
3. **老服务端对未知 query 键**:serde URL-encoded 静默忽略(`PullQuery` 无 deny),新客户端误发参数不会 4xx,只会退化为 B1 所述的失配自愈路径——无崩溃面。
4. **schema fresh-only / Room 无迁移**:双端零新表(design §1.2,`schema.rs:544-558`、`DatabaseModule.kt` 由 LocalDataGate 把守);客户端 XOR 缓存进程内易失,**无 P2-5 式回滚雷**(回滚 APK 无耐久状态残留)。
5. **TLS 不变量/证书隔离**:0.5 不触 TLS;W0 明确只在隔离实例 + mktemp 数据根测量(AGENTS.md Certificate-test isolation)。
6. **ADR**:0018(墓碑胜出,行级语义不变)、0019(普查是读导出,不裁护理真相)、0020(bump rev 不改版本字节)、0022(闭合仍服务端独占,choice-only 不变)、0023(commit 事务内仅新增一条有界查询)——均未触碰语义;W1 把闭合移出 pull 使 pull 更贴近 §1.4 纯读精神(wire 现状并无「pull 全程纯读」总条款,只有普查路径纯读条款)。
7. **floor / tech.md §4.2.1**:加性+能力门控+参数缺省逐字节兼容,不落入 item 1 任何破坏性判据(无新实体型、老客户端可见的封闭键集不变、无 schema_version bump、无 allowlist 放宽),不抬 floor;撤旧短语留待下一大版本并须届时走 §4.2.1 步骤 2-4——与设计声明一致。
8. **前台-only**:W3/W4/W5 全部前台会话内(B9 钉死后);无后台轮询/FCM。

## 总评

**设计大体守住红线,但有条件。** 能力通道、closed-key、schema/TLS/floor/ADR 六项经核对全部合规;
未守/未写的集中在协商失败模式与发布前提:

**最小修改集(按序):**

1. **B1+B2**:在 §1.3/§4 补参数协商的完整失败模式——未知值 422 fail-closed(新服务端)、能力陈旧的失配自保(不带参数重验一次再决定是否重走)、兼容矩阵补「服务端回滚」行。
2. **B3**:§4 增加发布前置门——解 P2-4(为 0.5 版本补打包 stanza)与 P2-7(操作者 docker 权限)之前,W1/W2 不得发布,客户端 APK 亦依赖同一通道;D1 的「过渡期短」以此门为前提。
3. **B6+B7**:W1 验收清单写死闭合的 rev 投递语义、滞留行清理、全 bump-rev 路径折叠枚举(含媒体 finalize 与 closure 的 rev-only 推进)、缓存键防 rev 复用。
4. **B4+B5**:wire §1.4 走日期化加性注记并 scope 旧定义;删除 128bit 截断选项。
5. **B8-B10**:V1 补例外 (c);W3 定时钉为既有轮门控并点名两处文档目标;tip-skip 的「安静轮不比对」写为显式 trade。
