---
title: 0.5 设计对抗审查·轴 A:收敛正确性(review-A-convergence)
date: 2026-09-06
reviewer: 对抗审查员 A(收敛正确性轴)
reviewed: .scratch/0.5-sync-optimization/design.md(draft-v1)+ research.md
status: complete(只读审查;本文为唯一产出)
evidence-basis: 全部 path:line 为 2026-09-06 tree 实读
---

# 对抗审查 A:收敛正确性

被审核心主张:活集 census 从「每轮全量重算 `count + SHA-256(sorted keys join "\n")`」
(`tools/lezi-sync/src/store/pull.rs:913-949`、`sync/src/main/kotlin/com/lezi/babylog/sync/backend/SyncBackend.kt:108-114`)
改为「两侧增量维护的 XOR 多重集摘要 + rev 键控缓存」。

## 0. 先说站得住的部分(攻击前的基线)

- **同键双行不可能性成立,多重集=集合**:服务端 `entities` 主键 `(family_id, entity_type, client_uuid)`
  (`tools/lezi-sync/src/store/schema.rs:198-210`);客户端 7 表全部有 `Index(clientUuid, unique=true)`
  (`core/database/.../Entities.kt:41,96,149,221,277`、`CustomItemEntities.kt:22`、`causal/CausalEntities.kt:18`)。
  XOR 自逆代数在唯一键约束下精确等价于集合对称差,无重数陷阱。
- **失序不敏感、O(delta) 维护**的代数性质成立;现状短语确实不可增量(design §1.1 判断正确)。
- **覆盖语义等价**:XOR 与现状排序摘要同样只覆盖活集键存在性,不覆盖内容漂移——「同义短语」
  主张成立(research §2.3 的内容收敛机制不动)。
- **覆盖语义等价(续)**:服务端 census 计 deferred 行(`compute_live_census` 的 SQL 无 deferred
  过滤,`pull.rs:913-921`)而 pull 扣发(`is_deferred_fulfillment`,覆盖 care_plan 与其 media,
  `pull.rs:634-664,984`),XOR 版保持同行为是可实现的(见 A13)。
- **服务端并发模型可支撑 rev 键控缓存**:全部 family 写与 pull 都持 per-family 异步互斥锁
  (`handlers/sync.rs:224,595,699,760,902,971,1008,1212`),缓存读写可约束在锁内。

以下为攻击清单。严重度:P0 致命 / P1 必须修 / P2 应修 / P3 记录。

## 1. 攻击表

| # | 严重度 | 攻击 | 证据 | 修复建议 |
|---|---|---|---|---|
| A1 | **P0** | **「部分折叠 + rev 推进」产生永久假失配,design 的「自愈」主张错误。** 风险句「漏一条 → 摘要陈旧 → 客户端假失配一次重走,自愈」只对「整条路径漏掉(不折叠也不推进 rev)」成立——那种漏被 `缓存 rev == head` 判等拦下,退化为一次重建。真正致命的是**折叠了 delta 但漏了行改动(或 count/digest 折叠不一致)却把缓存 rev 推到新头**:rev 判等通过,错误 digest 以「新鲜」身份被持续返回;客户端重走一次后拿到的是同一个错误 digest → 再失配 → 耐久诊断,**每轮一次重走 + census 功能死亡,直到服务端重启,永不自愈**。写路径全集约 12 处(见 A4 列表),`project_stable_media` 单事务内含「media upsert + 墓碑」两个循环、`commit_*` 三分支、`resolve/withdraw/declare/resolve-group/auto-align/promote/restore/bundles/anonymize` 各自不同——部分折叠的实现概率不低。 | design.md:57,89;缓存键控 design.md:55;rev bump 站点:`causal.rs:1018`(advance_rev,7 调用点 1197/1215/2065/2675/2798/2944/3813)、`source_relations.rs:964`、`causal_media_staging.rs:631`、`restore.rs:117`、`bundles.rs:2301`、`identity/anonymize.rs:101`;锁模型 `handlers/sync.rs:1212-1218` | **把「折叠 digest」与「推进 cached rev」解耦**:写事务只可靠推进 cached rev 并把 digest 置 `STALE`;折叠例程成功完成才回填 digest。读路径仅在 `cached rev == head && digest != STALE` 时 O(1) 返回,否则惰性重建。这样任何遗漏的实现缺陷退化为「多一次全量重建」(正确性安全),永远不会把错值当新鲜值售卖。折叠优化在此安全底座上再叠加,并由 W2 的「随机活集变动序列 → XOR 增量 == 全量重算」性质测试把门。 |
| A2 | **P1** | **客户端本地摘要从「真值」变「缓存」,失配的语义与修复结局双退化。** 现状 `localLiveCensusEntries` 每次比较都从 Room 全量真算(`ReplicaSyncEngine.kt:2333-2349`),失配 = 真分歧的证明,重走(重投全部实体)后**必然**匹配,除非数据真错。XOR 缓存版失配可能是钩子 bug 造出的「本地谎言」;重走时 `applyRemote` 对绝大多数行命中幂等跳过(`shouldApplyStablePull`:remote `version_id` 未变即不应用,`CausalSettlement.kt:505-514`),若跳过路径不折叠,重走后缓存依旧错 → 每轮重走 + 诊断,而**数据其实已收敛**。design.md:64 称「与现状 census 失配同代价类」——代价类相同(一轮重走),但终局不同(现状可终止于匹配,XOR 缓存可能终止于永久假诊断)。 | `ReplicaSyncEngine.kt:2290-2321,2303-2319,2333-2349`;幂等跳过判据 `CausalSettlement.kt:505-514`;design.md:64,97 | 两条硬性规定写进设计:(1) **cursor=0 重走(失配修复、409、诊断路径)结束时无条件丢弃本地摘要缓存并全量重算一次**——重走本就是 V1 例外 (b) 的异常路径,多一次本地全量不破字面;(2) **幂等跳过路径的折叠行为必须显式定义**(见 A10)。 |
| A3 | **P1** | **InvalidationTracker 兜底按字面不可实现,或退化为「每轮全量=现状」使 W2 客户端侧收益归零。** 「比较时若『标脏但已知缝未折叠』→ 单次重算」要求把「脏」与「已折叠」对账。Room InvalidationTracker 的脏通知是**事务提交后异步派发**、只投递给注册观察者、且**应用无法在同一事务内 ack/清除**它。两个失败模式:(i) 派发延迟窗口内比较 → 漏检未折叠写;(ii) pull 折叠自身也会置脏 → 「标脏但已折叠」与「标脏且未折叠」不可区分 → 要么每次比较都重算(pulling 轮恰是比较发生的唯一场景 → 客户端零收益、V1 常规路径破),要么永不重算(兜底形同虚设)。 | design.md:61-64;`ReplicaSyncEngine.kt:2374-2397`(transport journal 用法可参照);Room InvalidationTracker 语义(异步派发、无 ack API) | 放弃「标脏→对账」方案,改为**事件驱动的自愈闭环**:兜底 = (a) A2 的「重走强制重建」(未钩写路径 → 本地谎言 → 假失配 → 重走 → 重建 → 匹配,自愈且有界);(b) 低频周期性本地全量校验(如每 N 轮或每次诊断时)作为最终真值锚。InvalidationTracker 降级为诊断信号(记录「有未折叠脏写」进回执),不参与比较判定。 |
| A4 | **P1** | **绝对 rev 赋值路径可与缓存 rev 碰撞:rev 相同、内容不同 → 错值当新鲜。** 灾难恢复的设计目的就是水位回退(`UPDATE family_meta SET rev = ?1`,restore 后 head 可以小于或**恰好等于**缓存中旧条目的 rev);`commit_bundle`、anonymize、offline_migrate 同为绝对赋值。若缓存失效机制是「rev 不等则重建」,等值碰撞时返回的是**事故前**的旧 census → 假失配 → 重走 → 缓存仍未失效 → 永久假诊断(同 A1 终局)。design.md:124「XOR 缓存随 rev 回退失效重建」未指明机制,等值碰撞是漏网情形。 | `restore.rs:95,117`(bulk INSERT entities + 绝对 rev)、`bundles.rs:2279,2301`、`identity/anonymize.rs:86,101`、`offline_migrate/causal.rs:246`;design.md:124 | 规定不变量:**任何非 `rev = rev + 1` 形态的 `family_meta` 写入必须无条件删除该 family 缓存条目**。四条绝对赋值路径(restore / commit_bundle / anonymize / offline_migrate 逐点列出进实现 checklist)+ 单测覆盖「回退到缓存已有 rev」场景。 |
| A5 | **P1** | **128bit 截断撞上客户端 64-hex 硬校验,fail closed。** `key_digest` 解析强制 `Regex("[0-9a-f]{64}")`(`HttpSyncBackend.kt:2907-2909,3231`);wire §1.4 也冻结「64 位小写 hex」(`docs/spec/contracts/causal-sync-wire.md:137-139`)。design.md:43「128bit 截断可选」若落地为 32 hex:新客户端必须按请求参数切换校验,且同一解析器还要兼容「新客户端+老服务端」回落的 64 hex(D1 场景)——两套长度 × 两种短语在同一解析路径交织,正是 closed-key fail-closed 文化最容易判错的地方。而截断收益为零(无对抗者,设计自认 family 服务器非对抗威胁模型)。 | design.md:43,70;`HttpSyncBackend.kt:2907-2909,3231`;`causal-sync-wire.md:129-141` | **删除「截断可选」,0.5 直接用完整 256bit XOR**:wire 字节形状、客户端正则、诊断快照全部零改动,强度只增不减。此一条把 wire 增量缩小到「短语定义 + 请求参数」两点。 |
| A6 | **P2** | **物理删除 + 墓碑行:钩子若按「事件类型」而非「live 态 diff」折叠,XOR 注入错误 delta 且不可自逆修复。** 客户端对 census 表存在物理 DELETE:`LocalDataClearCoordinator.kt:120-139`(records/candidates/plans/wake/media/babies 全家清)、`Daos.kt:1736-1745`(media 条件删);这些行可能是墓碑(本不在活集)。按「DELETE → XOR out」处理墓碑行会给摘要 XOR 进一个从未入集的哈希值——XOR 错值无法原地撤销,只能重建。Room `@Query("DELETE ...")` 不返回受影响行,拿不到 prior live 态。服务端同型风险在 restore/bundles 的批量插入。 | `LocalDataClearCoordinator.kt:120-139`;`Daos.kt:1736-1745`;`Entities.kt:58`(deletedAt 语义);design.md:123(「按行 live 态变化 XOR in/out」方向正确但未落实机制) | 实现规约:每个折叠钩子必须是「同事务内先 SELECT 受影响行 `(client_uuid, deleted_at)` → diff 出 live→live / live→tombstone / tombstone→live / tombstone→tombstone 四态 → 按变化折叠」。对 `deleteAll` 类全量路径直接选择「整段失效 + 下次比较重建」而非逐行 diff。 |
| A7 | **P2** | **事务回滚窗口的缓存漂移,双端均未定义。** 钩子在事务内计算 delta、缓存在进程内存:事务事后回滚(约束冲突、ElapsedBudget 到顶取消、协程取消、`busy_timeout` 超时)→ DB 未变而缓存已折叠 → 漂移 → 假失配。服务端若在 `tx.commit()` 前更新缓存,commit 失败同样漂移;若在 commit 后应用内存 delta,应用前的并发读看到 `cached rev < head` → 多一次重建(安全但浪费)。设计通篇未提回滚语义。 | design.md:57,61(只写「折叠钩子」,无回滚条款);客户端事务容器 `DatabaseTransactionRunner.kt` | 规定:**delta 只在事务成功提交的回调里应用于缓存;失败即丢弃**。接受「提交与应用缓存之间的极小窗口内并发读触发一次重建」(有 family 锁时服务端窗口为零,客户端单同步循环内天然串行)。 |
| A8 | **P2** | **W3 tip-skip 的「无缺图」条件被 404 永久破坏,或反向推迟补图。** 缺图集合 = `mediaDao.listMissingLocalBytes()`(`ReplicaSyncEngine.kt:3042`),而 404 是「isolated half-upload,stays queued for retry」**永不毕业**(`:3069-3072`)。一条永久 404 媒体让「无缺图」永假 → 该设备 tip-skip 永不生效(优化静默失效,且无告警);反之若实现者为了省事删掉该条件,丢图设备的补图重试(`downloadMissingMedia` 仅 Foreground/PullToRefresh 触发,`:295-298`)被 tip-skip 无限推迟——心跳 NoAction 不开轮,永远轮不到重试。 | `ReplicaSyncEngine.kt:295-298,3042,3069-3072,3522-3523`;design.md:102 | tip-skip 条件改为「缺图集合为空 **或** 全部为已确认 404」;404 确认态需耐久标记(causal_transport_journal 可承载,无 schema 变更)。W0 度量加「缺图查询每轮成本」。 |
| A9 | **P2** | **tip-skip + W5 复合改写 0.4.8 可见性验收,但设计未重述验收标准。** 心跳三键判 `!=`(`SyncHeartbeatEngine.kt:94-105`)给出 NeedsSync;tip-skip 则把「前台返回轮」的新鲜性上限从「回前台即拉」放宽为「最近一拍心跳(D2 窗,≤300s)+ 一轮」。新鲜度窗内对端 commit 的竞态是**设计接受的 staleness**,但 0.4.8 验收第 1 条(「前台对端写入最迟 ~基线间隔 + 一轮可见」)字面被破,须显式重写而非默认继承。另:tip-skip 前置的 `hasPendingPublishUnits()` 是 6 表扫描 + 回执对照(`ReplicaSyncEngine.kt:2418-2451`),安静轮省 RTT 的同时新增一次本地扫描成本,须进 W0。 | `SyncHeartbeatEngine.kt:94-105`;`ReplicaSyncEngine.kt:2418-2451`;design.md:102,124,143(D2) | 在 §7/W5 验收里重述:「对端写入可见性上限 = 心跳间隔 + 一轮」;W0 量 `hasPendingPublishUnits` 单次成本,必要时用心跳 kick 路径已有的信号复用。 |
| A10 | **P2** | **重走/崩溃重放/幂等跳过三者的折叠行为未定义,决定 A2 修复是否成立。** checkpoint 重放会二次应用同一页(wire §1.4「此前崩溃或拒绝会从上一 checkpoint 重放;重放依靠既有 UUID/upsert 幂等语义」);`shouldApplyStablePull` 跳过时不写行。折叠若挂在「INSERT/UPDATE 事件」上会双折叠;挂在「应用前后 live 态 diff」上则天然幂等且对跳过路径为 no-op。 | `causal-sync-wire.md` §1.4 尾段(重放合同);`CausalSettlement.kt:505-514`;design.md:96(测试矩阵未列重放/重走用例) | 折叠唯一合法入口 = live 态 diff(A6 同一规约);W2 折叠缝覆盖矩阵显式加两用例:「同页崩溃后重放两次」「cursor=0 重走整轮后摘要 == 全量重算」。 |
| A11 | **P2** | **老短语并存缓存是 V1 字面外的第三类全量例外,不变量表未更新。** 「老短语每 head 至多重算一次」(design.md:59)= 每 (family, head) 一次全家庭扫描+排序+SHA-256,发生在混合版本期的**常规 pull 路径**上。V1 判定标准只列例外 (a) 冷启动预热与 (b) 失配重走。 | design.md:24(V1),59 | V1 例外增补 (c):「为无 `census_xor_v1` 能力的老客户端按 (family, head, phrase) 至多一次的旧短语重算」,并注明过渡期边界;或把 V1 主句改写为「每 (family, head, phrase) 至多一次全量」。 |
| A12 | **P3** | **空集定义变化(全零 vs `e3b0c44…`)需同步 wire 文档示例。** 现状空集 digest 在 wire §1.4 文字与 JSON 示例中均为 `e3b0c442…`(`causal-sync-wire.md:131,139`);xor_v1 下变为 64 个 `'0'`。文档增补若漏改示例,后续测试/审查会把正确行为误判为失配。 | design.md:44;`causal-sync-wire.md:129-141` | W2 的 wire §1.4 增补条款显式写「xor_v1 空集 = hex 全零」并更新示例 JSON。 |
| A13 | **P3** | **deferred 语义等价可立,但必须把「折叠输入 = 表内容而非投递子集」写成实现规约。** 服务端 census 无 deferred 过滤(`pull.rs:913-921`),扣发谓词覆盖 care_plan 与其关联 media(`pull.rs:634-664`);`pull.rs:1085-1089` 到 head 时 `page_cursor = self.current` 使被扣发行在本头内不再投递 → 现状闭环(失配→一次重走→再失配→耐久诊断)确认存在(`ReplicaSyncEngine.kt:2303-2319`)。XOR 版若按「pull 交付的行」折叠会漏 deferred 键。 | `pull.rs:634-664,913-921,984,1085-1089`;`ReplicaSyncEngine.kt:2296-2321`;design.md:122 | design §3 已声明等价;补充一句实现规约「折叠钩子以 entities 行的活态为准,禁止以 pull/planner 输出为准」+ W2 用例含 completed-plan-缺-record 与其 media。 |
| A14 | **P3** | **W4 媒体并行对收敛无结构性风险;429 担忧不成立,真实约束是 staging 原子性与取消。** 服务端无媒体 GET 限流器(`rate_limit.rs` 无 media 条目;`lib.rs:69-80` 的 30 次/60s 是 heartbeat 专用限流)——design.md:111 要核的问题答案是「不计入」。收敛相关的唯一合同是「全部字节 staging 成功才进 Room 事务」(`ReplicaSyncEngine.kt:532-537,1479-1516`:任一失败抛异常 → 整页中止、cursor 不动、零行写入)。并行化必须保持:第 k 个失败时在途 worker 全部取消、已下载字节丢弃、apply 不开始。 | `ReplicaSyncEngine.kt:532-537,1479-1516,3037-3081,3522-3523`;`lib.rs:69-80`;design.md:110-111 | W4 测试加「3 并发中第 2 个失败 → 0 行写入、cursor 不动、无半包可见」;取消走既有 CancellationException + MediaGet/ElapsedBudget 体系,无需新语义。 |
| A15 | **P3** | **服务端重启预热窗口:可接受,但 W0 应量出数字。** 重启后缓存为空,每 family 首个 pull 在 family 锁内全量重建一次;同轮后续页 `cached rev == head` 命中 O(1);并发多设备同 family 由 family 锁串行,只重建一次。不破 V1 字面(例外 a)。generation 热接续(0.4.8)不与 census 缓存交互(gen 持久化只消 409 诱因)。 | `handlers/sync.rs:1212-1218`;`.scratch/0.4.8-generation-hot-resume/spec.md`;design.md:56-58 | W0 度量清单加「冷启动后每 family 首拉(含重建)耗时」。 |
| A16 | **P3** | **D1 倾向(a)(新客户端遇老服务端跳过摘要比对)使过渡期收敛网只剩 cursor+pending。** 存在性分歧(漏投递/缺行)在过渡期不被捕获,「媒体缺失类」用户可感问题恰好是 census 的强项。自托管配对升级窗口短,可接受,但应写明「过渡期风险自担 + 完成配对后恢复」;选项 (c) 仅 count 比对其实便宜且保底,建议重新权衡。 | design.md:142(D1) | D1 决策表补一句:「(c) 的成本 = 7 条 COUNT,可走既有索引估算或精确 COUNT;若 (a),须在 setup-status 看到能力后自动恢复比对」。 |

## 2. 已排查、未构成攻击的点(记录以免重复劳动)

- **服务端写路径清单已闭环**:对 `entities` 的全部写只有 8 处源码位置
  (`source_relations.rs:979`、`bundles.rs:2279`、`restore.rs:95`、`causal_media_staging.rs:705,711`、
  `causal.rs:1047,1218,2067`、`identity/anonymize.rs:86`),外加 `offline_migrate`(离线 CLI)。
  其中 rev-only(`bump_entity_rev_only`、`bump_relation_member_revisions`、promote 的 rev 刷新)
  不改活集、只要求推进 cached rev(A1 的解耦机制覆盖);改活集的是 commit 三分支、
  `project_stable_media` 墓碑循环、resolve、restore、bundles。
- **`promote_consumed_causal_media` 的调用点**全部在 family 锁内(commit/resolve/withdraw,
  `causal.rs:3323,3602,3913`)或启动期路由前(`lib.rs:684`),缓存折叠可安全挂钩。
- **`close_empty_open_conflicts`**(现挂 pull 读路径,`pull.rs:1109-1115` → `causal.rs:1561-1608`)
  只 bump rev 不改活集;W1 把它移到写路径对 census 无影响,且顺带满足 wire「普查路径不得改写实体行」。
- **客户端进程模型**:单进程(两份 AndroidManifest 均无 `android:process`),无跨进程缓存失效问题;
  `don't-keep-activities`/进程死亡只损失内存缓存 → 下次比较一次重建(V1 例外 a),无漂移窗口。
- **崩溃窗口**:客户端缓存纯进程内、pull 应用耐久 —— 「应用成功但折叠没做」不可能跨进程存续;
  唯一窗口是同进程内回滚不回滚缓存(A7,已给修复)。
- **心跳触发的轮**:verdict NeedsSync ⇒ 至少一键 `!=` ⇒ 必有可拉或需换目录,tip-skip 不作用于它(design §W3.1 正确)。

## 3. 总评:该设计在收敛正确性上是否可立

**方向可立,draft-v1 字面不可立。** XOR 多重集短语的代数等价性、唯一索引前提、覆盖语义与
deferred/墓碑的行为保持全部成立;双端的 rev/锁模型也足以承载增量缓存。但设计有两处**错误的安全
论证**必须先纠正:其一,「漏一条折叠 → 假失配一次重走 → 自愈」(A1)只对整路径漏成立,「部分折叠 +
rev 推进」会造出**以新鲜身份永续售卖的错误摘要**,必须用「折叠/推进解耦、digest 可为 STALE」的
失败安全结构替换;其二,InvalidationTracker「标脏→对账」兜底(A3)在 Room 的异步无 ack 语义下
要么失效要么退化为每轮全量,必须改为「重走强制重建 + 低频真值锚」的自愈闭环。其余 P1(重走重建、
绝对 rev 碰撞、128bit 截断)均有小而确定的修复。上述 P0/P1 修复条款并入 design §1.2/§3 与 W2
测试矩阵后,本审查者认为该设计可以进入 spec 阶段。
