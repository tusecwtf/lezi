---
title: 0.5 同步优化调查：传输速度、校验效率、冲突解决精度（现状测量 + 升级选项）
date: 2026-09-06
status: research-only（只读调查；不改 PRD / ADR / wire；唯一产出是本文）
question: 0.5 版本升级：优化传输速度和校验效率，做到高精准度快速前后端同步和冲突解决，用户无感；心跳间隔可放宽作为交换。
---

# 0.5 同步优化调查（2026-09-06）

> 本文是**研究文档**，不是 spec。每条代码结论都给 `路径:行号`；测量值与推断分开标注
> （**实测** = smoke/文档里的实测数字；**代码事实** = 从代码读出的确定行为；**推断** = 由代码
> 推出但没有 live 性能数据的判断）。版本参照系：当前 tree 0.4.8 / versionCode 29 / Room 29 /
> server schema 13 / 协议代 0.4.0 conflict-v2（`docs/spec/platform.md` §1、`docs/spec/contracts/causal-sync-wire.md`
> 头注）。

---

## 1. 现状 wire 协议与传输模型（从代码测量）

### 1.1 服务端端点全集（`tools/lezi-sync`）

公共 router 挂载在 `tools/lezi-sync/src/lib.rs:819-959`，与本调查相关的端点：

| 端点 | 作用 | handler |
|---|---|---|
| `POST /v1/sync/handshake` | 每个同步周期恰一次的认证预检 | `handlers/sync.rs:50-117` |
| `GET /v1/pull` | 增量游标分页拉取 | `handlers/sync.rs:1186-1351` |
| `POST /v1/causal/commit` | 批量因果提交（commit-first） | `handlers/sync.rs:543`，`store/causal.rs:3131` |
| `GET /v1/sync/heartbeat` | 0.4.8 前台心跳探针（三键） | `handlers/sync.rs:204-253` |
| `PUT /v1/causal/media/{uuid}` | 媒体 preimage staging | `sync/backend/HttpSyncBackend.kt:1005-1027` |
| `GET/POST /v1/conflicts/{id}[/resolve|/withdraw]` | 冲突详情/解决/撤回 | `handlers/sync.rs:676,743,885` |
| `POST /v1/source-relations/declare|resolve-group` | 疑似重复来源关系 | `handlers/sync.rs:956,987` |
| `GET /v1/media/{uuid}` | 媒体字节下载 | `HttpSyncBackend.kt:1340-1347` |
| `GET /v1/app-update[/apk]` | 自托管更新（含 piggyback） | `lib.rs:823-824` |
| `GET /v1/family/members` | 成员目录 | `lib.rs:890-893` |

握手响应是 closed result（`sync.rs:92-116`）：`protocol_version / server_version / ready /
capabilities / principal / directory_generation / limits / compression / retry_hints`。
`limits` 实测常量（`lib.rs:118-122`）：**200 实体/页、8 MiB decoded、9 MiB encoded、500 页/轮、
commit ≤ 64 units**（`store/causal.rs:46`；客户端镜像 `HttpSyncBackend.kt:2239`）、commit 请求体
≤ 1 MiB（`handlers/sync.rs:31`）。

### 1.2 客户端一轮同步的精确顺序

`ReplicaSyncEngine.synchronizeCycle`（`sync/engine/ReplicaSyncEngine.kt:212-393`）：

1. **握手**：`POST /v1/sync/handshake`（:225），精确键集能力匹配 fail closed（:395-413）。
2. **目录收敛**：`directory_generation` 变了才 `GET /v1/family/members`（:228-238）。
3. **增量分页 pull**：`pullAllPages`（:2017-2275）。每页 `GET
   /v1/pull?cursor=&generation=&page_index=&include_live_census=true` —— **客户端每一页都带
   live census 请求**（`HttpSyncBackend.kt:704-710`，`include_live_census=true` 是硬编码常驻参数）。
   页预算校验 + Room 单事务整页应用 + checkpoint 耐久推进（:2102-2156）。
4. **head 处 census 比对**（:2268-2270，详见 §2）。
5. **历史缺图队列**：`downloadMissingMedia`（:296-298，有界重试 3 次、8 MiB decoded 上限，
   :3522-3523）。
6. **冻结 → commit-first 推送**：`captureLocalChanges` → `settleAndPublish`（:299-375, 453-513）
   → `CausalSettlement.settle` → `POST /v1/causal/commit` 批量（≤64 units/请求）；有新媒体先逐个
   `PUT /v1/causal/media/{uuid}` 取 receipt。依赖未就绪时最多 8 轮重排（`MAX_AUTHORITY_SETTLEMENT_PASSES`，
   :3521）。
7. **piggyback 更新发现**：每次**成功的非 LocalWrite 轮**后追加 `GET /v1/app-update`
   （`RealSyncPort.kt:1884-1902`）。注意：**心跳触发的轮也走这条**（心跳 kick 经
   `kickHeartbeatRound` → `pullRequested=true` + `syncSignal` → `sync(Foreground)`，
   `RealSyncPort.kt:1075-1079, 440-450`，piggyback 条件只排除 `LocalWrite`）。
8. **LocalWrite 触发**（`SyncPort.kt:73-80`、wire §13）：只 commit 不 pull、不推进 cursor；
   回声成本由心跳接管（wire §13 回声说明，0.4.8 已明示接受 ~2 轮/写批）。

### 1.3 传输细节（keep-alive、压缩、序列化）

- **序列化**：全部 JSON。客户端 `kotlinx.serialization` 手工构建/逐键校验（closed envelope
  `requireExactKeys`，`HttpSyncBackend.kt:727-730`）；服务端 `serde_json` + `deny_unknown_fields`。
  pull 每页是完整 JSON envelope；commit 一次携带整批完整 root。
- **HTTP 客户端**：`HttpURLConnection`（非 OkHttp，`HttpSyncBackend.kt:101-103`），每个请求
  `open()` 新实例。keep-alive 策略是**「认证 2xx 交换不调 disconnect()，把 socket 还给平台
  持久连接池」**（:144-149 注释原文 "this is not a connection pool"；:1946-1968），并在**每轮
  结束时主动 `releaseForegroundKeepAlive()` 全部拆除**（`RealSyncPort.kt:1194`、
  `ReplicaSyncEngine.kt:197-206` 的 finally）。Java 平台合同：连接由底层透明共享，
  `disconnect()` 会关闭空闲的底层 socket（[Oracle javadoc](https://docs.oracle.com/javase/8/docs/api/java/net/HttpURLConnection.html)）。
  **推断**：因此每一轮的第一个请求大概率重付 TCP+TLS 握手（局域网约几十 ms 量级）。
- **服务端**：`axum_server::bind_rustls`（`main.rs:100`），crate 宣称 HTTP/1 与 HTTP/2 都支持
  （[axum-server docs](https://docs.rs/axum-server/latest/axum_server/)）；公共路由有
  `TimeoutLayer` 300s + TraceLayer INFO + `DefaultBodyLimit`（`lib.rs:967-973`，
  `HTTP_REQUEST_TIMEOUT_SECONDS=300` 于 :78）。**没有压缩中间件**；gzip 是 pull handler 内
  手工实现（`sync.rs:1314-1328`，`flate2::Compression::default()` —— 已核对 vendored crate
  源码 1.1.9 `src/lib.rs:249-253` 即 **level 6**）。客户端**恒定请求 gzip**
  （`SyncBackend.kt:586-591`；`Accept-Encoding` 头 `HttpSyncBackend.kt:1532`）；服务端按
  RFC 7231 风格 q 值协商（`sync.rs:1125-1183`）并回 `Vary`（:1342-1349）。
- **增量/游标**：pull **已经是**按 `rev > cursor AND rev <= family_meta.rev` 的增量游标分页
  （`store/pull.rs:971-980`），游标到 head 时空页短路径直接返回（`pull.rs:1136-1144`），
  cursor 领先回 409 `cursor_ahead/full_resync`（`handlers/sync.rs:1237-1245`）。generation 已
  持久化到 `{LEZI_DATA_DIR}/generation`（0.4.8 generation-hot-resume，`.scratch/0.4.8-generation-hot-resume/spec.md`），
  NAS 重启不再强迫全家 409 全量。
- **超时/重试（全部实测常量）**：
  - H15 预算（`sync/backend/retry/SyncRetryPolicy.kt:44-54`，wire §1.3 冻结）：handshake/detail
    3s/10s/3 次/30s；pull/commit/resolution 3s/20s/3 次/60s；media prepare 5s/90s/3 次/240s。
  - 无 H15 的操作走 `FamilyHttpBudget`（`deadline/FamilyHttpBudget.kt:28-100`）：Probe
    3s/5s/1 次/8s；Session 3s/8s/2 次/12s；MediaGet 3s/8s/1 次/30s。
  - 前台整轮上限 **120s**（`ForegroundSyncCycle.MAX_ELAPSED_MILLIS`，`FamilyHttpBudget.kt:98-100`），
    到顶保留 checkpoint 停下一页/下一文件。
  - 重试延迟：合法 Retry-After 优先，否则 1s 基、10s 顶的 exponential full jitter
    （`SyncRetryPolicy.kt:198-226, 305-306`）。
- **客户端响应上限**：JSON 16 MiB / 媒体 10 MiB / APK 100 MiB（`HttpSyncBackend.kt:83-86`）；
  上传写停滞看门狗 30s（媒体）/5s（JSON）（:88-89）。

### 1.4 一轮典型规模的量化（代码事实 + 实测参照）

- **安静心跳轮**（对端有变化 → NeedsSync）：握手 1 RTT + pull（通常 1 页）1 RTT +
  app-update piggyback 1 RTT = **≥3 RTT**；pull 空页时服务端仍要跑 census（§2）。
- **安静前台返回轮**（无任何变化）：同上 ≥3 RTT，其中 pull 是空页。
- **LocalWrite 轮**：commit 1 RTT（+每张新照片 1 个 preimage PUT）；无 piggyback。
- **数据量参照（实测）**：真实家庭库 ≈ 770 个 rev（0.4.5 事故重放页界 0→283→491→770，
  `.scratch/0.4.7-sync-hardening/spec.md:88`），即一轮全量 ≈ 4 页 × ≤200 实体。照片是字节
  大头：媒体 manifest 单文件 sha256+byte_size（wire §4.6），字节经独立 GET 逐个下载。
- wire §16（`causal-sync-wire.md:970`）官方列出的**未做**优化清单：「独立拉页媒体实体 sha256、
  **握手 tip 跳过空拉页**、**并行 GET**、OkHttp/h2 合同、首加快照包、容灾分块」——这就是 0.5
  候选空间的产品权威出处。

---

## 2. 现状校验 / checksum 模型

### 2.1 收敛判定 = 三个条件的合取（代码事实）

1. **pull cursor 到 head**：整轮 `has_more=false` 且返回 `cursor == family_meta.rev`
   （空页短路径 `store/pull.rs:1136-1144`）。
2. **live census 比对通过**：7 类实体的活集（`deleted_at IS NULL`）各自比对
   `count + SHA-256(按字典序排序的 client_uuid 以 \n 连接)`（wire §1.4，
   `causal-sync-wire.md:129-146`）。服务端算法 `store/pull.rs:913-949`；客户端同式
   `liveCensusKeyDigest`（`sync/backend/SyncBackend.kt:108`）+ 本地重算
   `localLiveCensusEntries`（`ReplicaSyncEngine.kt:2333-2349`）。
3. **无未终态待发布单元**：`hasPendingPublishUnits`（`ReplicaSyncEngine.kt:2417-2451`）
   扫 6 张表的 `listPendingSync()` 并对照终态回执。

### 2.2 census 的成本在哪（关键发现）

- **服务端在每一次 pull 请求都重算 census**：`include_live_census=true` 时
  `compute_live_census` 全家庭扫 `entities`（`pull.rs:1131-1135` 调 `pull.rs:913-942`）。
  SQL 是 `WHERE family_id=? AND deleted_at IS NULL ORDER BY entity_type, client_uuid` ——
  表上**只有** `entities_family_rev(family_id, rev)` 索引（`store/schema.rs:211`），没有
  `(family_id, deleted_at)` 或 `(entity_type, client_uuid)` 索引，所以是**每页一次的
  全活集扫描 + 排序 + 哈希，且发生在 family 写锁内的 `run_blocking` 里**
  （`handlers/sync.rs:1212-1218`）。N 页的一轮 = N 次全量 census 重算。wire 定义 census 是
  「同一 head 下每页相同」的纯读导出（`causal-sync-wire.md:140-141`）——这意味着按 head
  缓存是**语义等价的纯服务端优化**，不需要动 wire。
- **客户端每整轮在 head 处重算本地 census**：`localLiveCensusEntries` 对 7 张表各做一次
  `listAllIncludingDeleted()`（含墓碑）加载，过滤后排序 + SHA-256（`ReplicaSyncEngine.kt:2333-2349`）。
  **推断**：家庭规模（数百~数千行）下是几十 ms 级的 CPU + 一次全表读，不是最大项但也不免费。
- **census 失配的代价**：自动把 cursor 置 0 做**至多一次**全量重走（`ReplicaSyncEngine.kt:2303-2308`），
  再失配则写耐久诊断回执、本轮不再重走（:2309-2319，无风暴）。本机有未发布内容时跳过比对
  （:2300，防假阳性）。
- 另一个藏在每次 pull 里的写路径：`close_empty_open_conflicts`（`store/pull.rs:1109-1115` →
  `store/causal.rs:1564-1596`）在**读路径**上开事务清理空分支 open 行并 bump rev。行数通常
  为 0，但它是每次 pull 都要付的事务 + 查询（**推断**：成本小）。

### 2.3 内容级一致性靠什么（不是整库哈希）

- 内容收敛依赖**增量游标投递 + 幂等应用**：远端 `version_id`/`updated_at` 一致即跳过，
  open conflict 只在远端 stable `version_id` 变化时应用（`CausalSettlement.kt:512-514`；wire
  §12.3）。census 只覆盖**活集键（存在性）**，不覆盖内容漂移——内容错误由 commit 的
  canonical request hash / `mutation_receipts` 幂等账本（`store/causal.rs:24` 导入
  `mutation_content_hash`；wire §6 replay 语义）和三方合并的 canonical 校验兜住。
- 媒体完整性是**每文件** sha256+byte_size manifest（wire §4.6），不是整库校验（wire §16
  「SHA 是 tree 条目……不是整库校验和」）。
- **没有** Merkle 树、没有 generation 计数器哈希、没有滚动哈希；SQLite 核心**没有**内置
  sha256/哈希聚合函数（仅 `group_concat` 等，[sqlite.org/lang_aggfunc.html](https://www.sqlite.org/lang_aggfunc.html)），
  所以服务端 census 哈希必须在 Rust 侧物化（现状即如此）。

---

## 3. 现状冲突解决模型与「精准度」的来源

### 3.1 模型（按 ADR）

- **ADR-0022 commit-first + choice-only**：普通发表只发一次幂等 commit；ConflictSnapshot 是
  冲突读取/缓存/resolution 的唯一事实单元；客户端只提交 `{path, choice_id}`，服务端独占重建
  （`docs/adr/0022-commit-first-choice-only-conflict-snapshots.md` §1-§3；wire §8）。
- **ADR-0020**：不可变版本 + 稳定投影 + 耐久分支；`updated_at` 不做并发 token（部分被 0022 取代）。
- **ADR-0018**：疑似重复不 tombstone；墓碑胜出（同 base 并发删/改先到 accepted、后到 branched，
  wire 例 E/J）。
- **ADR-0019**：服务器只验证约束，不裁决护理真相；拒绝全文 CRDT。
- **ADR-0023**（0.4.8 auto-near-neighbor）：同 baby 同型 ±30 分钟 Record 由服务端在 commit
  事务内**自动**写 canonical source relation，减少人工 resolve-group 轮
  （`docs/adr/0023-auto-near-neighbor-source-relations.md` §1-§4）。

### 3.2 冲突路径上的实际往返（代码事实）

- **冲突的发现是 0 额外 RTT**：commit 响应单 unit 直接返回 `branched + conflict_id +
  branch_version_id`（wire §3.2/§6；客户端落库 `CausalSettlement.kt:321-395`）。
- **冲突详情是懒加载 + 分页**：用户打开冲突收件箱才 `GET /v1/conflicts/{id}` 逐页拉完整
  snapshot（`RealSyncPort.kt:1155-1196`；分页预算 `ConflictSnapshotPaging`；token 过期/stale
  时丢弃 staging 重拉一次，:1178-1191）。每页预算同 Handshake 档 3s/10s/3。
- **resolve / withdraw 各是单次 POST**（`RealSyncPort.kt:1198-1222`），CAS 失败返回最新
  summary 不改状态。
- **资源界**：每 root 64 条 open branch 上限（wire §6.1）、每页 conflict_summary ≤32
  （wire §7）、bounded loader / snapshot receipt 分页（wire §8.1）。
- **auto-near-neighbor 的时延位置**：在 commit 的**同一写事务**里对每个已提交 live Record 做
  邻域发现（`store/causal.rs:3267-3298` → `store/source_relations.rs:532+`），有界于
  256 候选 + 索引范围扫描（wire §12「Canonical / bounds」）。**推断**：给每次 record commit
  增加一次有界扫描的成本，换来未来更少的人工组处理轮。

### 3.3 「精准度」今天依赖什么（不可回退的精度机制）

1. 确定性 N-way 分类（对 stable + 全部 open branches 一次计算，wire §9.6）；
2. receipt 绑定**完整** branch set + choice 成员，`choice_id` opaque（wire §8.1）；
3. 心跳/水位比较判 `!=` 不判 `>`（灾难恢复 rev 回退，`SyncHeartbeatEngine.kt:70-105`）；
4. commit-first 的先到 accepted 顺序 + tombstone-wins（wire 例 E/J/F）;
5. closed-key envelope 全链 fail closed（`requireExactKeys`、`deny_unknown_fields`）；
6. 0.4.7 加固的具名拉取缺口 + 有界跳过 + 自愈（`.scratch/0.4.7-sync-hardening/spec.md`），
   OPPO 0.4.5 卡死事故的根因已拆（`.scratch/2026-09-04-oppo-sleep-conflict-handoff/handoff.md`）。

**时延藏在哪里**：冲突路径本身不在发表关键路径上（commit-first 的意义）；时延点在
(a) 用户打开冲突面板时的详情分页 + 过期重拉；(b) snapshot_stale/expired 后的 refresh 轮；
(c) 复杂分支下 resolution 前要重读完整 snapshot。冲突是**稀有事件**（choice-only 模型 +
auto-near-neighbor 前置消化了大量「两条事实」），所以冲突路径**不是**日常同步速度的主矛盾
（**推断**，依据：0.4.5/0.4.7 两份事故与审计里用户主诉都是拉取/发表，不是冲突面板）。

---

## 4. 现状心跳（0.4.8，已在 tree）

### 4.1 数字（全部实测常量 + smoke 实测）

| 项 | 值 | 出处 |
|---|---|---|
| 基线间隔 | 30s | `SyncHeartbeatEngine.kt:51` |
| 无变化退避 | 倍增封顶 120s，±20% 抖动 | `:52,54,57-65` |
| 回前台首拍防抖 | +8s | `:53` |
| 判定 | 三键 `generation / head_rev / directory_generation` 任一 `!=` 即 NeedsSync（含回退） | `:94-105`；wire §1.5 |
| 失败节奏 | 复用既有 availability 梯 30s/120s/600s，单次失败即降级 | `:44-49, 470-524` |
| 服务端限流 | 每设备 30 次/60s 窗（默认），超限 429 | `lib.rs:73,80`；`sync.rs:215-223` |
| 服务端锁纪律 | family 锁内仅水位单行读 + 目录摘要重算，释放后构造响应 | `sync.rs:224-241` |
| 认证成本 | `authenticate` 每请求读库；`last_used_at` 写有 60s 去抖 | `store/identity/session.rs:573-585` |
| 目录摘要成本 | memberships+devices 两查 + SHA-256（家族规模） | `session.rs:22-107` |

- 每拍成本 = 1 个认证 RTT（响应 ~100 字节级闭合三键），**不持前台 keep-alive、不翻状态行**
  （`SyncHeartbeatEngine.kt:206-244`）。
- 触发的同步动作只有一个：经跨拍熔断缝踢既有 conflated 前台循环
  （`RealSyncPort.kt:1075-1079`）；`Syncing` 期跳拍（:1024-1028）；零进度熔断 ≤3 次
  （`ForegroundRoundFuse.kt:25`），同信号熔断后照常探针但不再开数据轮。
- **实测 smoke（2026-09-05）**：节拍 27-49s 抖动爬升；后台 150s 零探针；对端改名 → 10s 内
  探到 → 同秒自动开轮；限流窗内 27×200 后 4×429（`.scratch/0.4.8-sync-heartbeat/smoke-2026-09-05.md`）。
- **发布窗门禁状态**：心跳已并入 0.4.8 发布流；交叉审核（`review-2026-09-05-p2-plus.md`）P2-6
  「熔断后心跳再开轮」已由 `ForegroundRoundFuse` 修复落地；NAS 侧发布仍被 P2-4（打包 stanza
  无 0.4.8 配对）与 P2-7（操作者 docker 权限）挡住——0.5 若依赖 NAS CD 需先解这两条。

### 4.2 放宽心跳能省什么、风险是什么

- **能省**（**推断**，按代码成本结构）：每拍 1 RTT + 服务端目录摘要重算 + 无线电唤醒。
  放宽到基线 60s / 退避顶 300s，前台安静场景的探针请求数近似减半到减 60%。
- **风险 = 线性 staleness**：对端变化可见时延上限从「基线 30s + 一轮」变为「新基线 + 一轮」
  （0.4.8 验收标准第 1 条：前台对端写入最迟 ~基线间隔 + 一轮内可见，
  `.scratch/0.4.8-sync-heartbeat/research.md` §9）。用户无感性不受影响
  （心跳本就无 UI），但「两台机都开着 → 秒级互见」的体验会钝化。
- **次级耦合**：心跳是已加入态 availability 的常驻喂食者（0.4.8 统一模型），放宽节拍会把
  「服务器恢复 → 客户端复健」的探测时延同样拉长（降级期走的还是 30s/120s/600s 梯，不受
  健康期退避影响，`SyncHeartbeatEngine.kt:470-499`）。
- 放宽在 0.4.8 的两轮 grill 决策语境下是**可逆的纯常量改动**（`SyncHeartbeatPolicy` 三个
  常量 + wire/sync-trusted-endpoint 文档注记），owner 已明示允许这个交换。

---

## 5. 瓶颈分析（排序；标注实测/代码事实/推断）

按「一个前台轮的墙钟与资源」分解，从大到小：

| # | 成本 | 类型 | 证据 |
|---|---|---|---|
| 1 | **每请求固定开销 × 3-4 RTT**：安静轮 = 握手 + pull 空页 + app-update piggyback（+可选 members）。其中 piggyback 在心跳时代从「事件级」变成「每轮级」 | 代码事实（结构）；总量推断 | `ReplicaSyncEngine.kt:225,258`；`RealSyncPort.kt:1884-1902` |
| 2 | **每轮重建连接**：轮末主动拆光 keep-alive handle，下轮重付 TCP+TLS | 代码事实（拆除行为）+ 成本推断 | `HttpSyncBackend.kt:170-174,1970-1976`；`ReplicaSyncEngine.kt:197-206` |
| 3 | **服务端 census 每页全量重算**（扫描+排序+SHA-256，family 锁内），多页轮付 N 次 | 代码事实；相对权重推断 | `pull.rs:913-942,1131-1135`；`schema.rs:211` |
| 4 | **客户端 census 全表重算**（7 表含墓碑全载）+ `hasPendingPublishUnits` 6 表扫描，每整轮一次 | 代码事实；权重推断 | `ReplicaSyncEngine.kt:2333-2349,2417-2451` |
| 5 | **照片轮的串行媒体 GET**：每个媒体 1 次顺序 GET（MediaGet 预算 30s），照片多时是字节与串行延迟大头 | 代码事实；权重推断 | `ReplicaSyncEngine.kt:1480-1516,3037-3081`；`FamilyHttpBudget.kt:72-81` |
| 6 | **commit 写事务内的工作**：逐 unit canonical 校验 + 三方合并 + auto-near-neighbor 邻域扫描；媒体 promote 在锁外 | 代码事实；权重推断 | `causal.rs:3131-3305`；`source_relations.rs:532+` |
| 7 | **pull 读路径上的事务**：`close_empty_open_conflicts` 每次 pull 开一次事务 | 代码事实 | `pull.rs:1109-1115` |
| 8 | 冲突轮（详情分页 + stale 重拉 + resolve CAS）：存在但稀有 | 代码事实 + 稀有性推断 | `RealSyncPort.kt:1155-1222` |
| 9 | 心跳探针本身：~100 字节 × 每拍 | 代码事实 | wire §1.5 |

**校准数据（实测）**：真实家庭 ≈ 770 rev ≈ 4 页全量（`.scratch/0.4.7-sync-hardening/spec.md:88`）；
smoke 里探针→检测→开轮全链 ≤10s（`smoke-2026-09-05.md:30-33`）。仓库内**没有**服务端
microbenchmark 或轮时延的 live 数字——上表的相对权重是推断，0.5 spec 前建议在隔离实例上
用 `curl -w`/日志补 3 个数：空页 pull 服务端耗时（含 census）、握手耗时、4 页全量轮耗时。

**结论**：当前的主要矛盾不是 payload（JSON 小、gzip 已开、照片是必要的字节），而是
**「每轮固定 RTT 数 × 每轮重建连接」+「每页 census 重算」+「客户端每轮全表 census」**。
冲突路径与心跳 payload 都不是主矛盾。

---

## 6. 0.5 升级选项（逐项对照现状，已解决的明确剔除）

### 6.0 候选清单里「已经存在」的（从选项中剔除）

| 候选区 | 现状 | 证据 |
|---|---|---|
| 用 version/generation cursor 做 delta pull | **已是**普通路径：rev 游标增量分页；409 全量仅限证明失效；0.4.8 generation 持久化进一步消灭全量诱因 | `pull.rs:971-980,1126-1128`；`.scratch/0.4.8-generation-hot-resume/spec.md` |
| pull 压缩 | **已是**：客户端恒定 gzip，服务端手工 gzip（level 6） | `SyncBackend.kt:591`；`sync.rs:1314-1328` |
| commit 批量 | **已是**：单 POST ≤64 units / 1 MiB | `causal.rs:46`；`sync.rs:31` |
| HTTP keep-alive | **两端已有**（服务端 hyper 默认；客户端平台池 + 轮末拆除合同） | §1.3 |
| 心跳自适应退避 | **已是**：30s→120s ±20% + 降级梯 | `SyncHeartbeatEngine.kt:50-65` |
| 汇总型收敛检查 | **已是**：live census（count + SHA-256 digest），并带「失配→单次全量重走→耐久诊断」闭环 | §2 |

### 6.1 选项 A：服务端 census 按 (family, head) 缓存 + pull 读路径减负

- **机制**：`compute_live_census` 结果按 `(family_id, family_meta.rev)` 缓存（进程内即可），
  rev 不变直接复用；`close_empty_open_conflicts` 移到写路径（commit/resolve）而不是每次
  pull 的读路径。可选加 `(family_id, deleted_at)` 部分索引降低首次计算成本。
- **改善**：校验效率——N 页轮的 census 从 N 次全量扫描降为 1 次/头；读路径少一次事务。
- **成本**：纯服务端内部优化，**零 wire 变更**（wire 已定义 census 是同一 head 的纯读导出，
  `causal-sync-wire.md:140-141`）；缓存失效正确性要过单测（提交路径 bump rev 后必须失效）。
- **用户无感**：是。ADR：不需要（无行为/合同变化）。
- **风险**：低。缓存陈旧即 census 过期——但 census 键含 rev，语义安全。

### 6.2 选项 B：安静轮瘦身（纯客户端）

1. **app-update piggyback 节流**：0.4.8 后每次心跳触发的成功轮都多发一个
   `GET /v1/app-update`（§1.2 第 7 步）。改为只在真实用户触发（回前台/下拉）或低频定时
   （如每进程每小时一次）时 piggyback，心跳/续跑轮跳过。
2. **本地 census 复用**：本轮零实体应用且 cursor 未动且无 pending 时，本地活集未变，
   可复用上一轮的本地 census 计算（Room invalidation 或「本轮零应用」谓词判定）。
- **改善**：安静轮 -1 RTT、省一次客户端全表 digest。
- **成本**：客户端 only；piggyback 节流要注意「强制更新壳」的时效语义（`tech.md` §4.2 的
  piggyback/清壳规则有 AUDIT-20260801-P1-01 约束，改动需回归该组测试）。
- **用户无感**：是（更新横幅只是变钝一点，可接受并写文档）。
- **风险**：低-中（app-update 状态机测试面较大）。

### 6.3 选项 C：握手 tip-skip（wire §16 官方遗留项）

- **机制**：握手响应增量附带 `head_rev`（或 tip 游标）；客户端 cursor==tip 时跳过必为空页的
  pull 请求。走 wire §1 的**增量能力纪律**：新能力键只进 `/v1/setup-status` 增量 capabilities
  （先例 `sync_heartbeat_v1`），老客户端零感知；握手响应加键需按 live census 的 opt-in 先例
  或以新 capability 的响应代际处理（握手响应本身是 closed result，不能悄悄加键——0.4.5/0.4.6
  客户端对未知顶层键 fail closed，`causal-sync-wire.md:122-126`）。
- **改善**：安静前台返回轮 -1 RTT；与心跳三键里的 `head_rev` 同源，服务端实现极薄。
- **成本**：wire 增量（新 capability + 文档三处：§1 增量语义、§1.2 注记、§16 勾掉一项）；
  双端 + 兼容矩阵测试。
- **用户无感**：是。
- **风险**：低（模式已被心跳验证过一遍）。注意与选项 B 的收益重叠——安静轮只保握手 1 RTT 时
  这是仅剩的合法化路径（心跳 NeedsSync 轮本来就有东西可拉，skip 不掉）。

### 6.4 选项 D：媒体 GET 并行化

- **机制**：`stageLogMediaDownloads` / `downloadMissingMedia` 的逐媒体串行 GET 改为 2-3 并发
  worker（受同一 ElapsedBudget 与 MediaGet 预算约束），保持「全部字节先落 staging 再 Room
  应用」的原子接收合同（`ReplicaSyncEngine.kt:533-537` 注释）。
- **改善**：照片多的一轮墙钟近线性缩短（**推断**：LAN 上 3 张照片 ≈ 3×串行 RTT+字节 → 1×）。
- **成本**：客户端 only；不碰 wire。服务器是单 NAS，需留并发上限防自挤。
- **用户无感**：是。
- **风险**：低（预算与取消语义沿用既有 deadline 体系）。

### 6.5 选项 E：心跳放宽 + 自适应（owner 明示允许的交换）

- **机制**：`SyncHeartbeatPolicy` 基线 30s→60s、退避顶 120s→300s（或引入按安静时长分段的自适应
  表）；可选把「NeedsSync 重置基线」保留、仅放宽安静段。降级梯 30s/120s/600s 不动。
- **改善**：电池/无线电/服务端探针量；换取 staleness 上限变宽。
- **成本**：常量 + wire §1.5 与 `sync-trusted-endpoint.md` §7.1 的节奏数字文档同步；服务端
  限流默认（30 次/60s/设备）远高于新节奏，无需改。
- **用户无感**：是（心跳本来就静默）；体验代价是对端变化可见时延上限变大（§4.2）。
- **风险**：低。**不要**同时放宽降级梯（那是故障恢复时延，不是安静期开销）。

### 6.6 评估过、不建议做的

| 选项 | 为什么不做 |
|---|---|
| push+pull 单 RTT 融合端点 | 破坏 wire §5「commit 不推进 cursor、先证明再发表」的已冻结形状；收益与选项 B/C 重叠；属破坏性 wire，需抬 floor 全家配对升级（`tech.md` §4.2.1 checklist） |
| brotli/zstd（tower-http CompressionLayer） | 已核实 [tower-http](https://docs.rs/tower-http/latest/tower_http/compression/index.html) 支持 gzip/deflate/br/zstd 流式压缩，但本产品的大 body 只有 pull（已 gzip）与 APK（已压缩格式）；LAN 带宽非瓶颈（**推断**），br/zstd 还要给 Android 端引解压依赖 |
| 请求体压缩（commit JSON） | commit ≤1 MiB 且 LAN 上占比小；需双边 wire 变更，不值的破坏性变更 |
| 冲突 snapshot 预取 | 冲突稀有 + token 会过期失效（预取大概率白拉）；懒加载 + 单次 stale 重拉已是好形状 |
| 客户端 Merkle/增量维护 digest | 需要数据库级触发器维护有序键集哈希，SQLite 核心无哈希聚合（[sqlite.org](https://www.sqlite.org/lang_aggfunc.html)），复杂度远超收益；census 频率问题已被选项 A 消解 |
| OkHttp / HTTP/2 迁移 | wire §16 明列未做但换 HTTP 栈是大动干戈（超时/重试/TOFU/看门狗全要重验）；收益（h2 多路复用）在单用户家庭服务器上很小 |

---

## 7. 建议（按 用户可见收益 / (风险·工作量) 排序）

1. **选项 A：census 按 head 缓存 + pull 读路径减负**（服务端，零 wire）。
   攻击瓶颈 #3/#7：多页轮与全量轮的服务端成本近线性下降，是「校验效率」的直接解；
   实现小、语义安全（census 本就是按 head 的纯函数）。建议先在隔离实例量出空页/多页
   pull 的服务端耗时基线再定案。
2. **选项 B：安静轮瘦身**（客户端）。
   攻击瓶颈 #1/#4：心跳时代每轮的 piggyback RTT 是新增的纯开销，先掐掉；本地 census 复用
   随后。注意 app-update 状态机回归（AUDIT-20260801-P1-01 测试组）。
3. **选项 D：媒体 GET 并行化**（客户端）。
   攻击瓶颈 #5：照片是唯一的大字节路径，2-3 并发即显著缩短照片轮；原子接收合同不变。
4. **选项 E：心跳放宽**（客户端常量 + 文档）。
   owner 明示允许的交换：把安静期基线/退避顶放宽一档，用 staleness 换电与服务端噪声；
   与 1-3 组合后总轮数下降，进一步放大收益。

备选（若 0.5 允许一条增量 wire）：**选项 C 握手 tip-skip**，模式已被 `sync_heartbeat_v1`
验证，作为 0.5 的唯一 wire 增量、与 B 叠加把安静前台轮压到 1 RTT。

### 不可以拿来换速度的东西（红线，逐条对 AGENTS.md / ADR）

- **服务端只验约束不裁决护理真相**（ADR-0019）、**choice-only ConflictSnapshot、服务端独占重建**
  （ADR-0022）、**不可变版本/稳定投影**（ADR-0020）——任何「本地预合并」「LWW 抄近路」都出局。
- **tombstone wins 与删/改并发顺序语义**（ADR-0018，wire 例 E/J/F）。
- **closed-key envelope 全链 fail closed / exact-keys 解析**——任何加键必须走增量 capability +
  opt-in 纪律（`causal-sync-wire.md` §1；0.4.5/0.4.6 客户端对未知顶层键 fail closed）。
- **schema fresh-only、普通 CD 不迁移**；**破坏性 wire 先抬 `min_supported_version_code` 并发布
  已验证可安装包**（`tech.md` §4.2.1）。
- **TLS 身份不变量**：CD/回滚永不替换既有 `server.crt/key`；证书测试只在隔离实例
  （AGENTS.md「TLS safety invariant」「Certificate-test isolation」）。
- **仅前台同步、不做后台轮询/FCM/常驻服务**（`tech.md` §3；`sync-trusted-endpoint.md` §2）——
  任何「更快」的方案不得借后台化实现。
- 心跳判 `!=` 不判 `>`、能力只经 setup-status 广播两条对抗审查修正不可回退
  （`.scratch/0.4.8-sync-heartbeat/research.md` §5）。

---

## 来源

### 代码（path:line 均为 2026-09-06 tree 实读）

- `sync/src/main/kotlin/com/lezi/babylog/sync/RealSyncPort.kt`（心跳循环 :952-1118；kick :1075-1079；
  sync/piggyback :1807-1904；快照/冲突 :1155-1246；常量 :2913,2920-2927）
- `sync/src/main/kotlin/com/lezi/babylog/sync/engine/ReplicaSyncEngine.kt`（周期 :212-393；
  pullAllPages :2017-2275；census :2290-2349；常量 :3519-3531）
- `sync/src/main/kotlin/com/lezi/babylog/sync/backend/HttpSyncBackend.kt`（pull :696-732；握手 :746-774；
  commit :776-798,1029-1059；媒体 :1005-1027,1340-1347；心跳 :1360-1373；keep-alive :121-122,144-174,1811-1976；
  open/超时 :1996-2040；gzip 解码 :2068-2105；常量 :83-89,2239）
- `sync/src/main/kotlin/com/lezi/babylog/sync/backend/SyncBackend.kt`（census digest :108；
  pull 编码 :132-174,556-591；SyncHeartbeat :507-513）
- `sync/src/main/kotlin/com/lezi/babylog/sync/backend/retry/SyncRetryPolicy.kt`（预算 :44-54；
  jitter/终态 :198-226,305-329）
- `sync/src/main/kotlin/com/lezi/babylog/sync/backend/deadline/FamilyHttpBudget.kt`（:28-100）
- `sync/src/main/kotlin/com/lezi/babylog/sync/heartbeat/SyncHeartbeatEngine.kt`（策略 :50-65；
  判定 :94-105；门 :132-157；beat :367-499）
- `sync/src/main/kotlin/com/lezi/babylog/sync/heartbeat/ForegroundRoundFuse.kt`（:25,36-123）
- `sync/src/main/kotlin/com/lezi/babylog/sync/engine/CausalSettlement.kt`（结果应用 :321-395；
  stable 应用判据 :512-514）
- `sync/src/main/kotlin/com/lezi/babylog/sync/SyncPort.kt`（trigger→plan :71-80）
- `tools/lezi-sync/src/lib.rs`（路由 :819-959；常量 :63-122；超时/层 :78,967-988；
  heartbeat limiter :393,801；authenticate :1010-1053；版本门 :1067-1078）
- `tools/lezi-sync/src/handlers/sync.rs`（握手 :50-117；心跳 :127-253；commit :255-331,543；
  pull 编码协商/序列化 :1065-1183,1304-1351；pull handler :1186-1351）
- `tools/lezi-sync/src/store/pull.rs`（census :909-949；planner :951-1098；pull 入口 :1100-1155）
- `tools/lezi-sync/src/store/causal.rs`（MAX_CAUSAL_UNITS :46；close_empty_open_conflicts :1561-1596；
  durable commit + auto-align :3131-3305；publish :3307-3333）
- `tools/lezi-sync/src/store/source_relations.rs`（auto align :532-592）
- `tools/lezi-sync/src/store/identity/session.rs`（目录摘要 :16-107；authenticate 60s 去抖 :573-585）
- `tools/lezi-sync/src/store/schema.rs`（entities 索引 :198-211）
- `tools/lezi-sync/src/rate_limit.rs`（:23-124）
- `tools/lezi-sync/src/main.rs`（bind_rustls :100）
- flate2 1.1.9 vendored 源码 `~/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f/flate2-1.1.9/src/lib.rs:249-253`
  （`Compression::default()` = level 6）

### 文档 / 工单（本仓库）

- `docs/spec/contracts/causal-sync-wire.md`（§1-§16；census :104-166；心跳 :168-206；commit/冲突 :239-607；
  合并 :610-678；§13 回声 :918-934；§16 未做清单 :960-971）
- `docs/spec/platform.md`（§1 版本 :40；§3 写路径/租约 :139-199；§3.2 上限表 :201-222；§4.2/4.2.1 更新与 wire-break :334-398）
- `docs/adr/0017`, `0018`, `0019`, `0020`, `0022`, `0023`（各 ADR 全文）
- `.scratch/0.4.8-sync-heartbeat/research.md`、`spec.md`、`review-2026-09-05-p2-plus.md`、`smoke-2026-09-05.md`
- `.scratch/lan-p2p-sync-research/research.md`
- `.scratch/2026-09-04-oppo-sleep-conflict-handoff/handoff.md`、`type-duplicate-audit.md`
- `.scratch/0.4.7-sync-hardening/spec.md`（真实三页规模 :88）
- `.scratch/0.4.5-sync-stuck-remediation/plan.md`、`.scratch/0.4.8-generation-hot-resume/spec.md`

### 外部一级来源（已核对）

- Oracle Java `HttpURLConnection` javadoc（持久连接/disconnect 语义）—
  https://docs.oracle.com/javase/8/docs/api/java/net/HttpURLConnection.html
- tower-http compression 模块（gzip/deflate/br/zstd、feature 门、流式）—
  https://docs.rs/tower-http/latest/tower_http/compression/index.html
- axum-server（HTTP/1 + HTTP/2、rustls）— https://docs.rs/axum-server/latest/axum_server/
- SQLite 内建聚合函数清单（无 sha256；`group_concat` 存在）—
  https://www.sqlite.org/lang_aggfunc.html
- flate2 `Compression` 文档（0-9 刻度、fast/best）— https://docs.rs/flate2/latest/flate2/struct.Compression.html
