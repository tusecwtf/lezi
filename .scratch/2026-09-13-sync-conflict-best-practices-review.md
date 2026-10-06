# 家庭数据前后端交互 · 多用户冲突处理 · 数据管理 —— 业界基准对照审查与更新方向

- 日期：2026-09-13
- 基线：tree 0.5.3 / versionCode 33 / Room 29 / 本地数据契约 6 / server schema 13 / 协议代 0.4.0（conflict-v2）/ 同步 floor 21，HEAD `dee1b927` + 工作树未提交发布定名改动
- 性质：**只读审查**，不改任何产品代码。本文档是唯一产物。
- 方法：9 个并行只读 agent —— 6 个分工审查（Rust 服务端+线协议 / Android 同步客户端 / 数据模型·存储·校验 / 冲突与多用户 UX / 业界最佳实践调研 / 当前迭代与路线），随后 3 个对抗式校验 agent（客户端论断证伪 / 服务端论断证伪 / 冻结决策与术语约束核对 + 遗漏扫描）。**下文每一条差距都经过了对抗校验；被证伪的论断在第 5 节单列，防止后续误报。**
- 证据行号以 2026-09-13 工作树为准，后续迭代可能漂移。

---

## 0. 结论（TL;DR）

1. **整体判断：这套同步/冲突设计在业界坐标里处于第一梯队，不是"追赶者"。** 它没有用裸墙钟 LWW（Dynamo/Cassandra 的经典坑），而是 commit-first CAS（`base_version`/`mutation_id`/`version_id`）+ 服务端字段级三方合并 + 真冲突才进人工收件箱——这正是 PowerSync「write_conflicts 人工裁决表」、Realm「四规则」、Ditto「字段级 register」等成熟引擎收敛到的形态，且幂等 receipt、cursor 依赖 co-group、冲突快照 HMAC 绑定、per-device 会话+轮换+吊销等工程细节完成度高于多数商业产品文档所承诺的水平。
2. **真正值得投入的差距不在"协议先进性"，而在五个具体缺口**：① 「本机去掉」（dismiss 未对齐项）对未来的服务器更新**不持久**，本机墓碑会被后续 pull 无声复活；② Owner 合并宝宝与已冻结因果信封存在**竞争窗口**，且无测试；③ 破坏性路径（退出这台设备 / 远端撤销设备）**没有待同步计数守卫与事前披露**；④ NAS 侧**没有 schema-13 级全量备份**（DEPLOY.md 自己承认），设备全量导出也缺失——这是家庭独份数据最大的存续风险；⑤ fresh-schema 门禁测试 `FreshDatabaseTest` 断言停在 26（当前 29），**fresh-only 策略的第一道回归防线已失效**。
3. 次级差距集中在：causal 路径 `updated_at` 无未来上界（legacy 路径有、causal 没有）、TOFU 证书无到期预警（到期=全设备同时失联待人工）、dismiss/冲突 UX 披露不足、更新 APK 整包进内存、测试基建缺 property/仿真/fuzz 三件套。
4. **不需要做的事**同样重要：document-CRDT（Automerge/Yjs）、vector clocks、后台轮询同步、OAuth 全家桶、E2EE relay、服务端"普通迁移"——要么过工程，要么违背已冻结决策（仅前台同步、封闭 wire、NAS-only 均为 owner 已裁决，见 §6）。

---

## 1. 现状定位：与业界基准逐项对照

| 维度 | 业界最佳实践（2025–2026） | 乐记现状 | 判定 |
|---|---|---|---|
| 并发裁决 | 拒绝裸墙钟 LWW；HLC 或服务器提交序 + CAS | commit-first CAS：base==stable 快路径 accepted、可合并 merged（双父 DAG）、真冲突 branched；`updated_at` 仅审计/展示、明确"不是并发 token"（wire §4.0，`causal-sync-wire.md:290-297`） | **达到/超过** |
| 合并粒度 | 字段级优于行级；同字段真冲突才上抛 | 服务端按唯一直接 base 逐 path 三方合并，同 path 双 outcome 才 branched（`conflict_snapshots.rs`，wire §8） | **达到** |
| 删除语义 | 显式 tombstone + 全局一致 delete-wins | 服务端盖章墓碑、`stale_live_over_tombstone` 拒绝普通复活、restore 走显式授权路径（ADR-0018→0021） | **达到** |
| 人工裁决 | PowerSync write_conflicts 模式：冲突落表由人决定 | 待处理收件箱 + ConflictSnapshot v2（HMAC token + 页 digest + choice_id 一次性 nonce，部分页不可授权 resolution） | **超过** |
| 幂等/重试 | idempotency key + 精确 replay 返回原结果 | mutation receipt 主键 `(family,membership,entity,uuid,mutation_id)`，同 id 异内容 `content_drift`，丢失响应重放同一信封（`RetryLostResponseAcceptanceTest`） | **达到** |
| 增量拉取 | 单调 cursor + ack 同事务 + 依赖同页 | cursor 单漏斗 `advance_rev` + PullGroupCollector 依赖 co-group + 洞处理持久台账（3 次后永久 skip 保收敛） | **达到** |
| 多用户授权 | per-device token、可吊销、归属（attribution） | 每设备独立 access/refresh（轮换 + replay 检测只吊销该设备）、terminal denials、`created_by_membership_id` 服务端首写盖章不可变 | **达到**（"共享密钥"式批评不成立，见 §5-N1） |
| 限流/准入 | 匿名面 + 凭据面限流、写准入配额 | create/root-auth/member-request/heartbeat/commit-admission/media 上传全部限流；**但 pull、conflict detail、匿名 APK 下载、pending-secret claim 未限流** | **部分**（G11） |
| 数据存续 | 备份/恢复是一等公民 | NAS 无 schema-13 备份命令（DEPLOY.md:169-171 自认）；设备仅 TXT/PDF 文本导出，无全量导出 | **落后**（G4） |
| 版本偏斜 | 加性迁移 + 版本握手 + 强升通道 | floor 21 + capabilities 精确匹配 + 已验证更新通道强制升 + golden corpus 跨端钉死；本地 fresh-only + 契约门禁；服务端 ADR-0008/0013 门禁切割 | **达到**（但见 G7 测试门禁失效） |
| 设备静态加密 | SQLCipher/Keystore 整库加密；security-crypto 已废弃 | Room 明文；token 用 EncryptedSharedPreferences（1.1.0，类已 @Deprecated） | **落后**（G9，需 ADR） |
| 测试基建 | property 不变式 + 确定性仿真 + fuzz | 行为测试非常扎实（C1–C4 崩溃一致性、并发 resolve 服务端确定性测试），但无 property/仿真/fuzz；客户端双设备端到端裁决验收缺位 | **部分**（G10） |

---

## 2. 经对抗校验确认的差距（按优先级）

> 每条含：现状（证据）→ 业界基准 → 建议。术语遵循 CONTEXT.md（「待处理」「未对齐项」「本机去掉」「家庭管理员」「家庭权威宝宝」等）。

### G1【高·正确性】「本机去掉」对未来服务器更新不持久（dismiss 可复活）

- 现状：`LocalExtra`/`Rejected` 的 dismiss 走 `tombstoneLocallyWithoutPush`（`sync/.../engine/ReplicaSyncEngine.kt:2646-2693`），本机行置 `deletedAt=now, syncDirty=false, mutationId=null`，**不推送**；服务器仍持有活实体。pull 应用路径唯一门 `shouldApplyStablePull`（`core/database/.../causal/CausalSettlement.kt:520-547`）对非脏行返回 true；`dismissed-skip` 台账只用于抑制重复诊断（`ReplicaSyncEngine.kt:2519-2523`），**不在应用路径上**。对抗校验确认：无任何守卫阻止复活；且复活后行不带「家里没收下」角标（abandoned 回执被 `unacceptedFacts` 过滤，`RealSyncPort.kt:3193-3195`）。
- 可达性（校验修正）：`LocalExtra`（服务器活集无此键）基本不可复活；真实可达路径是 **`Rejected` 且被拒的是对已发布实体的更新**（服务器持旧活版本；终态码含 `permission_denied`、`outside_time_window` 等），此后家人任何编辑推进该实体 rev → 本机无声恢复该条已去掉的记录。现无测试覆盖此场景。
- 业界基准：人工裁决的结论必须持久（Realm "Deletes always win"、PowerSync write_conflicts 落定后不再翻案）；客户端"本机丢弃"至少要对抗服务器重放。
- 建议：在 `shouldApplyStablePull`/apply 路径尊重 dismissed 台账（按 clientUuid 查 `dismissed-skip`/新台账键，命中且来的是 live 版本则跳过应用；服务器端也变墓碑时正常收敛）。补一条「dismiss 后家人编辑该实体」的回归测试。文案沿用「只从这台手机去掉，不通知家里」口径（`UnresolvedInboxIds.kt:8`），可在确认弹窗补一句"若家人之后修改了这条，它不会再回到这台手机"（更准确描述新语义）。

### G2【高·正确性】宝宝并入（Owner 方向）与已冻结因果信封存在竞争窗口

- 现状：`mergeBabyProfiles`（`domain/.../family/BabyFamilyProfileCoordinator.kt:436-470`）在事务内重绑记录（`babyId=target`、抬高 `updatedAt`、`syncDirty=true`），但**全程不调用 `causalSettlement.abandonMutation`**（对比：本机去掉路径有，`ReplicaSyncEngine.kt:2636-2637`）。若并入前已为某条 source 记录冻结了 mutation envelope（信封不可变，ADR-0022「恰一份不可变信封」），并入后该信封仍按旧内容/旧 `baby_client_uuid=source` replay：轻则 branched 把记录拉回已并掉的宝宝分支，重则内容漂移。merge 也不与 sync 互斥，LocalWrite 快路径可夹住同一行。测试缺口：`CareLogBabyProfileTest` 无任何 envelope/mutation 相关断言。成员方向（本机孤宝宝并入权威宝宝）因「不得上传」（CONTEXT.md:160）构造上安全。
- 建议：Owner 合并两个**家庭**宝宝前，先对 source 宝宝全部脏行做 abandon/重冻结（或合并事务内调用 `abandonMutation`），并让 merge 与 `sleepMutationMutex`/sync 互斥；补"合并前已有冻结信封"的回归测试。此为纯客户端修复，不触 wire。

### G3【高·数据丢失 UX】破坏性路径缺待同步守卫与事前披露

- 现状一（主动退出）：「退出这台设备」链路 `FamilyScreen.kt:595 → :1274-1290 → MembersDevicesDialogs.kt:234-260 → FamilySessionCoordinator.kt:677-681 → RealSyncPort.kt:2559-2566` 直接清库，**全链路无 `pendingPublishCount` 读取、无最终同步尝试**，只有文字警告。
- 现状二（被动撤销）：远端撤销设备 → `handleRemoteDeviceRemoved`（`RealSyncPort.kt:2570-2581`）自动清空本机家庭数据。清空本身是合同要求（`sync-trusted-endpoint.md:306`），**但没有任何"该设备还有 N 项待同步将被丢弃"的事前呈现**（离线设备更无法预知）。
- 业界基准：不可逆破坏操作前给出量化后果（"还有 N 条未同步"）并给最后一次挽救机会。
- 建议：① 主动退出确认框显示真实待发布计数，>0 时要求二次确认或先触发一次 push；② 被动撤销路径在清理前/后至少留存一条本地可见的"清理收据"（哪些实体、何时、为何），并考虑把未同步内容导出为本机文件再清。不触冻结决策（清空语义不变，改的是披露与时机）。

### G4【高·数据存续】没有 schema-13 级全量备份，设备全量导出缺失

- 现状：`deploy/DEPLOY.md:169-171` 明言历史 `copy-out-nas-data.sh` 是非原子热拷贝、"**不是 schema-13 备份命令**"；`:375-377` credential backup "不含 database/media backups or NAS snapshots"。设备侧仅有按宝宝+日期范围的 TXT/PDF 导出（`domain/export/ExportPort.kt:15-66`，排除照片字节与未采纳行）；灾备只支持"恢复到空服务器"（ADR-0014）。NAS 是家庭数据唯一真本，单盘故障 + Owner 设备同时丢失 = 数据不可恢复。
- 业界基准：3-2-1 备份原则的最小家庭版：至少一份离机/离机盘的、与 schema 版本绑定的原子备份 + 恢复演练。
- 建议（两档，可分期）：
  - NAS 侧（优先，无需动 App）：新增官方 `backup-nas-data.sh`——基于 SQLite `VACUUM INTO`/`backup API` 或停机窗口的原子快照 + 媒体目录校验和清单，输出与 `DATABASE_SCHEMA_VERSION` 绑定的备份包；DEPLOY.md 增加恢复 runbook（复用 offline-migrate 的门禁风格：恢复目标必须精确匹配 schema 13，fail-closed）。
  - 设备侧：全量导出（Room 事务一致性快照 + 媒体文件 + 版本元数据）作为后续 ADR 级功能（需更新 `platform.md` §4.1 数据保护表与 `data-model.md` §6.5）。

### G5【高·测试门禁失效】fresh-schema 冒烟测试断言过期

- 现状：`app/src/androidTest/.../FreshDatabaseTest.kt:80` 断言 `sqlite.version == 26`，而 `LeziDatabase.kt:44` 为 29；无 @Ignore，自 2026-08-09（版本 26 过期）起该测试在当前代码上必失败——说明 connectedAndroidTest 已有一段时间未跑，**fresh-only 策略唯一在真实 Room 层的回归防线处于失效状态**。
- 建议：立即修为 29 并加"从 `LeziDatabase.VERSION` 读取"消除硬编码漂移；把 `connectedDebugAndroidTest` 中 FreshDatabaseTest/LocalDataContractMigrationDeviceTest 两个门禁纳入 0.5.x 发布票的必勾项。

### G6【中高·正确性】causal 路径 `updated_at` 无未来上界

- 现状：`model.rs:398-402,458-461` 对 causal root `updated_at` 只验非负 i64；24h 偏移常量 `MAX_ENTITY_FUTURE_SKEW_MILLIS`（`lib.rs:126`）只用于 legacy bundle 路径（`handlers/media.rs:641-649`、`store/bundles.rs:2129-2134`）。`max()` 归一化是 wire §4.0 冻结语义（`causal.rs:2814-2817`、`causal_merge.rs:355-367`，golden 例 A 钉死），因此一旦某台设备时钟狂跳写入极远未来值，审计/展示时间被永久抬走且无法被后续合法编辑拉回（时间轴排序用 `timestamp` 字段，不受影响——影响面是审计时间与归一化水位）。
- 业界基准：Dynamo/Cassandra 的教训——对客户端时间戳做入站界；CockroachDB 甚至让偏差过大的节点自杀。约束性校验是 ADR-0019 明文允许的服务器职责（"强制协议有界"）。
- 建议：在 `validate_causal_root` 镜像 legacy 的 24h 未来偏移检查。**注意路径**：拒绝旧客户端当前合法 shape 属 wire 破坏性收紧，须走 `platform.md` §4.2.1 wire-break checklist（抬 floor）或以「实现注记 + 新客户端拒绝、旧客户端容忍 max() 水位」的过渡语义立项，需一张 ADR/票，不宜夹带在普通 CD 里。

### G7【中·一致性】dismiss 拉取洞后留下无救济的永久 census 不一致状态

- 现状：拉取洞经 3 次重试永久跳过后（`ReplicaSyncEngine.kt:2113-2119`），实体仍活在服务器；用户 dismiss 洞只删诊断 + 写台账（`:2620-2628`），本机活集永远少这一条 → `live_census_mismatch` 浅状态「家庭活集与服务器不一致」（`ShallowSyncStatus.kt:152`）**永久驻留**。`4a50406b` 的 known-snapshot 抑制只防重复全量 rewalk，不清状态；`localCensusExtraUuids`（`:2577-2584`）只枚举 local-extra 方向，server-extra 方向没有 per-key 处理或「本机去掉」通道。census 不进「待处理」徽章，所以表现为状态行永久污染。
- 建议：给 server-extra 方向一个显式终局——或 dismiss 时同时写"接受此洞"的 census 抑制台账（按 uuid，代价是本机永久缺该条并明示），或把「家庭活集与服务器不一致」挂进待处理收件箱给用户一个处理入口。任一方案都需要先写清产品语义（涉及 CONTEXT「待处理」定义边界），建议出票讨论而非直接改。

### G8【中·可用性/安全】TOFU 单 pin 无到期预警；到期 = 全设备同时失联

- 现状：`PinnedSpkiTrustManager` 单 pin（`sync/.../session/TrustedEndpoint.kt:525-541`），过期仅 `checkValidity` 抛错；全仓无到期预警 UI；`/ready` 与 app-update 元数据均不带证书信息（`readiness.rs:95`）。缓解：pin 轮换有响应式「服务器证书需要确认」面板（`FamilyNetworkSettingsScreen.kt:167-178,247-251`），所以到期伴随换证时用户可恢复——但到期当刻所有同步静默失败，需用户主动发现。
- 建议：客户端在握手/`/ready` 响应里带回（或从 pin 的证书对象直接读）`not_after`，在家庭网络设置页显示剩余有效期，<30 天出温和提醒。纯加性，不触 AGENTS.md TLS 不变量（不替换、不自动生成）；NAS 侧续期仍是独立授权维护窗（ADR-0011）。

### G9【中·安全，需 ADR】设备静态加密缺失 + 依赖已废弃 API

- 现状：Room 库明文（全仓无 SQLCipher）；token/凭据用 Jetpack security-crypto 1.1.0 的 `EncryptedSharedPreferences`（`libs.versions.toml:27,69`；`SecureRefreshTokenStore.kt:142-153`），该类在 1.1.0 stable 带类级 `@Deprecated`（对抗校验用 javap 核实）。现有 fail-closed 校验（`:83-85,132-134`）是好底子。
- 建议：中期立项「本机静态加密」ADR：SQLCipher（或 SQLCipher 备选 + Keystore 包裹 passphrase）整库加密 + Keystore 直读/Tink 替换 security-crypto。必须过本地数据升级门禁链（ADR-0012 相邻迁移、快照、失败不清库）并定义 passphrase 丢失语义；同时更新 `platform.md` §1 技术栈表与 §4.1。儿童照护数据的设备遗失/借用场景值得这笔投入。

### G10【中·工程】同步测试基建缺三件套；双设备裁决缺端到端验收

- 现状：行为测试非常密集（进程死亡 C1–C4、重试语义、心跳、TOFU、golden corpus），但：① 无 property-based 测试（收敛/幂等/不丢/删除一致四不变式）；② 无确定性"2 客户端 + 1 服务器"仿真 harness（中途杀进程/乱序/丢包 seed 复现）；③ 无 cargo-fuzz 目标（wire 解析是纯解析面，不触证书隔离条款）；④ 双端并发裁决只有服务端确定性测试（`causal_tests.rs:1945`：恰 1 accepted + 恰 1 `snapshot_stale`）与客户端映射级测试（`ConflictResolutionCoordinator.kt:240,263` → RefreshRequired），缺「第二台设备收到 stale → 刷新快照 → 新 choice_id 重提」的离线到在线验收流。
- 建议：第一批四条 property（JVM，jqwik 或手写生成器）+ 一个 ~200 行级 Kotlin 仿真 harness（把「双设备并发裁决」作为首批场景）+ `parse_sync_request`/pull query 两个 fuzz target。全部为增量，不触行为合同（AGENTS.md：行为测试即合同，无 StructureTest）。

### G11【中低·服务端】限流不对称与 pull 持锁放大

- 现状（对抗校验确认）：`GET /v1/pull`、`GET /v1/conflicts/{id}`、resolve/withdraw、session/refresh 无限流；`/v1/app-update{,/apk}` 与 `/download/lezi.apk`、`/join` 是**匿名**端点且无限流（大 APK 下载是比已认证 pull 更大的带宽面）；pending-secret 的 status/cancel/claim 三个匿名端点无限流（256-bit secret 在线爆破不可行，属策略不对称而非可利用洞）。此外 pull 在整个分页序列化期间持有 per-family mutex（`sync.rs:1214-1222`），已认证客户端可用并发 pull 拖慢同家庭全部 commit——未限流端点直接变成同家庭写饥饿杠杆。
- 建议：给匿名下载面加简单令牌桶（按 IP 即可）；pull 加每设备频率上限；评估 pull 规划是否可把序列化/压缩移出 family lock（gzip 已锁外，页规划仍锁内）。单家庭 LAN 威胁模型下不紧急，成本低值得做。

### G12【低·客户端】更新 APK 整包进内存

- 现状：`downloadAppUpdateApk` 返回 `ByteArray`（`HttpSyncBackend.kt:1406-1415`），100MiB 上限，瞬时内存可达约 2 倍（缓冲倍增），低端机 OOM 会中断强制更新流程。
- 建议：改为流式写 staging 文件 + 边写边算 sha256。小改动。

### G13【低·UX】待处理入口只在家庭 tab；未对齐项只有 UUID 片段可辨认

- 现状：`onOpenConflictInbox` 只挂 `TopDest.Family.route`（`MainActivity.kt:1468`）；Summary/Growth 无入口；未对齐项标题是 UUID 前 8 位（`UnresolvedInboxIds.kt:56-57`），处理页无时间、无内容预览、无「去时间轴查看」。单条 rejected 可从 Log 行角标进入处理（`MainActivity.kt:1655-1667`），但完整列表入口确实只有家庭 tab。
- 建议（受术语/规格红线约束，见 §6-R7）：处理页为未对齐项补充类型+时间+内容摘要（本机数据可得，离线也可渲染）；Log 首页增加待处理入口/红点需同步修订 `ui.md:358` 与 CONTEXT「同步状态（概览）」条目，作为规格变更出票。

### G14【低·文档漂移】两处规格/文档与实现不一致

- `docs/spec/layers/server.md:45-46` 把「已退役仍挂载」标注在了 `/v1/pull` 上；实际退役的是 `/v1/push`（422）与 `/v1/media` PUT，`/v1/pull` 是活跃实现（`lib.rs:979-980`、wire §1.4）。spec 是产品权威，应修正。
- 时间轴同 timestamp 决胜规则（`ORDER BY timestamp DESC, clientUuid DESC`，`TimelineWindowDao.kt:428`）无任何 spec 承诺；建议写进 `data-model.md`/`ui.md`，并作为 G10 排序不变式的一部分。
- 另：非 sleep 记录缺 `end_timestamp` 被服务端补 null（`model.rs:469-473`）按字面与 wire §9.1 相抵，但该行为已被 golden corpus 以 `end_timestamp_null_injected` 钉死为 canonical 形状、当前语义等价（非 sleep 类型恒 null）。**不建议改行为**，建议按 wire §16 先例补一条实现注记把归一化写明，防止未来引入区间类型时"缺键=清空"的误解释。

### G15【低·卫生】杂项清理清单（可并入任何一批）

- payload 数值范围校验下沉：生长测量已在域层强制（`GrowthMeasurements.kt:139-141`），残余缺口是 `careLog.addRecord`/`insertRecord` 直连调用方——把 `RecordPayloadCodec.validate` 挪进 `insertRecord`（`RecordMutationCoordinator.kt:938-964`），避免非 UI 路径写入越界行→永久 422 pending。
- measurement `unit` 收闭枚举（g|cm）：服务端现为任意非空字符串（`model.rs:1314`）；合法客户端只写 g/cm，无现实误读路径，收枚举需带 stored-version reload 加性兼容。
- memberships 双身份死脚手架：仅 `ensureFamily` 写一次 owner、权限判定全走 session（`BabyFamilyProfileCoordinator.kt:680-697`；`MembershipDao.listForFamily` 零调用）；清理=相邻迁移+契约账本，按 ADR-0012 流程出票。
- REPLACE + 无外键的潜在孤儿：全 DAO upsert 用 `REPLACE`，record→baby 无 DB 级约束（仅 media 归属有触发器）；当前所有路径先 `getByClientUuid` 复用 row id，属潜在而非活跃 bug，可加外键或 ABORT 防御。
- 收件箱排序/横幅用设备墙钟 `recordedAt`（`RealSyncPort.kt:566-598`）——仅影响展示顺序，业务判定全用 contentEpoch/versionId；可低优先改为按诊断类型分层排序摆脱钟依赖。
- EncryptedSharedPreferences 迁移（并入 G9）。

---

## 3. 建议更新方向（路线图草案）

### 第一批：0.5.4 候选（小步、不触 wire、可随普通节奏出票）
1. **G5** FreshDatabaseTest 门禁修复 + 发布票必勾 connectedAndroidTest 门禁（半天）。
2. **G1** dismiss 耐久化 + 回归测试（客户端单侧改动）。
3. **G2** 宝宝合并与信封竞争修复 + 回归测试（客户端单侧改动）。
4. **G3** 主动退出设备的待发布计数守卫 + 被动撤销的清理收据（客户端）。
5. **G12** APK 流式落盘（客户端）。
6. **G15** insert 范围校验下沉（客户端）。
7. **G14** 三处文档修正（server.md 退役标注、同 timestamp 决胜规则、wire §16 注记 end_timestamp null 归一化）。

### 第二批：需要小规格/独立票（多为 NAS 侧或加性协议）
8. **G4-NAS** schema-13 原子备份脚本 + 恢复 runbook（含恢复演练；恢复目标 schema 精确匹配 fail-closed）。
9. **G8** 证书有效期透出 + <30 天温和提醒（客户端读已 pin 证书对象即可起步；NAS 侧不加字段也可实现）。
10. **G11** 匿名下载面令牌桶 + pull 每设备频率上限。
11. **G7** census 不一致的 server-extra 终局语义（先出产品语义讨论票）。
12. **G10** 四条 property 不变式 + 双设备仿真 harness 首批场景（并发双端裁决、dismiss 复活、宝宝合并竞争）。

### 第三批：ADR 级方向变更（先写 ADR 再动工）
13. **G6** causal `updated_at` 未来上界（wire-break checklist 或过渡语义 ADR）。
14. **G9** 本机静态加密 + security-crypto 替换（平台技术栈表 + 升级门禁链）。
15. **G13** Log 首页待处理入口 / 常驻离线指示（需修订 `ui.md:358` 与 CONTEXT「同步状态（概览）」）。
16. **G15** memberships 清理、外键防御（契约迁移链）。

### 明确不建议（过工程或违背冻结决策）
- document-CRDT（Automerge/Yjs）、vector clocks、逐字符文本合并：现合并语义已覆盖需求，CRDT 带来的历史膨胀与查询损失不划算。
- WorkManager 后台轮询同步：`sync-trusted-endpoint.md:36,39,254,271` 冻结「仅前台」；若产品方向真要改，先推翻该决策再谈。
- per-device token 体系重建 / OAuth 服务器：已存在且完善。
- E2EE relay、正式 CA、Keycloak/RBAC：家庭自托管场景收益趋零。
- 服务端"普通在线迁移"：ADR-0008/0013 的门禁切割是刻意设计且配套 runbook，不要动。

---

## 4. 与当前迭代（0.5.1–0.5.3）的衔接

- 0.5.1/0.5.2/0.5.3 的发布票均只剩「两台家庭机装 APK + 冒烟」人工步，本审查**没有发现阻塞这三个发布的事实**；0.5.2 的同步卡死四根因与 0.5.1 的自动对齐收口经本次客户端/服务端双侧复核均确认闭合。
- 第一批建议（§3.1）与 0.5.3 同为"零 schema / 零 wire"节奏兼容，可作为 0.5.4 的候选票池；其中 G1/G2/G3 直接强化 `unresolved-local-dismiss` 与 `0.5.2-sync-stuck-hardening` 刚建立的用户信任面。
- 已知预存失败测试（0.5.1 票 03 记录的 BabyMoveSurfaceTest 等 JVM 失败）之外，本次新增发现 FreshDatabaseTest（G5）这一仪器测试门禁失效，建议与上述票一并偿还。
- `.scratch/log-home-timeline-and-layout-edit/issues/08-release-053.md` 等 0.5.3 文档与本次审查互不冲突。

---

## 5. 被对抗校验否决或降级的论断（防误报清单）

> 这些是第一阶段审查或业界调研中出现过、但对抗校验**不成立或需大幅修正**的说法。后续迭代讨论请勿再引用。

- **N1「共享 bootstrap secret 丢失归属与吊销能力」——不成立。** bootstrap secret 只是家庭管理员根密码（`/v1/family/create`、Owner 登录、删家、灾备四处使用，per-IP 限流）；成员设备经 pending-secret/QR grant 换取**每设备独立 token**（`device_sessions` UNIQUE hash + 轮换 + replay 检测只吊销该设备 + terminal denials），归属由 `created_by_membership_id` 服务端首写盖章（`causal.rs:2290-2296`）。
- **N2「服务端 fresh-only 无迁移是朴素做法」——不成立。** ADR-0008 fail-closed 启动 + ADR-0013 授权维护窗 offline-migrate（rehearsal 脚本 8 个失败/回滚用例）+ DEPLOY runbook，是刻意的门禁设计。
- **N3「拒绝码折叠成 invalid_domain 是缺陷」——不成立。** wire §9.5 闭集本就不含那些内部码，且明文将对应情形判为 invalid_domain；需要区分行为的媒体码全部逐字保留在白名单，瞬态路径走独立的 429 admission，客户端重试逻辑无损。
- **N4「/v1/bundles 是应退役的 legacy」——不成立。** 它是媒体原子包发布第二阶段，`HttpSyncBackend.kt:1431,1444` 与 `EphemeralPublishPipeline.kt:57-66` 活跃调用，wire §16 以它为前提。
- **N5「kg 单位造成 1000 倍体重误读」——不可经正常同步达。** 合法客户端只写 g/cm（`GrowthMeasurementFacts.kt:24-39`）；第三方写 `unit="kg"` 的 5000 会被服务端 100 上界 422 拦下。服务端 unit 校验宽松仍值得收紧（G15），但无现实误读。
- **N6「CarePlanStatus 未知值静默回退 PENDING 是现实风险」——降级为理论缺陷。** 客户端 wire 解码（`CarePlanRootWire.kt:134-137`）与服务端枚举（`model.rs:1089-1092`）双侧 fail-closed，未知值入库路径不存在。
- **N7「auth 缓存无界增长」——不成立。** 每次 token 签发/轮换/吊销路径结尾全清缓存，access TTL 15 分钟，规模有界（个位数条目）。
- **N8「GET /v1/family/members 带写副作用」——引用端点有误。** 真正带 sweep UPDATE 的是 owner 的 member/rename requests 两个 GET；`/v1/family/members` 是纯只读。实际影响可忽略。
- **N9「无后台同步是缺陷」——是冻结产品决策**（`sync-trusted-endpoint.md` §2），不是缺口；列入 §3"明确不建议"。
- **N10「收件箱墙钟排序会错删数据」——降级。** 仅影响列表顺序/横幅选择，业务判定全部基于 contentEpoch/versionId。
- **N11 PullHole 类 dismiss「可复活」——不成立。** 洞类 dismiss 写持久台账且 journalReferenceUnready 尊重之；不持久的只有 LocalExtra/Rejected 的本机墓碑方向（G1）。

---

## 6. 本文档遵守的冻结决策与术语红线（供后续票直接引用）

- 仅前台同步、不做后台轮询/FCM/P2P（`sync-trusted-endpoint.md:36,39,254,271`）。
- 封闭 wire：不做 dual-read/skip-unknown；破坏性变更先抬 floor + 发布已验证 APK（`platform.md:146-156`）；协议代 0.4.0 冻结。
- 服务端启动只接受精确 current schema，fail-closed（ADR-0008）；历史切割仅限授权维护窗（ADR-0013）；Android 本地数据契约永久原地保留（ADR-0012）。
- 普通 CD 不替换 TLS 身份；证书生成仅限全新空数据根（AGENTS.md TLS 安全不变量）。
- 冲突语义：commit-first + choice-only ConflictSnapshot（ADR-0022）；服务器验证约束不裁决护理真相（ADR-0019）；近邻自动来源关系 + 投影开放睡不进候选（ADR-0023 + 0.5.1 修订）；墓碑永胜（ADR-0018→0021）。
- 术语红线：「待处理」= 未解决同步冲突 + 本机未对齐项的**单一徽章与同一底栏**，未对齐项不伪造 `conflict_id`（CONTEXT.md:308-310）——UX 分级只能在徽章/底栏内部做视觉区分；「本机去掉」永远不得表述为家庭删除；UI 不得出现 "Owner"（用「家庭管理员」，协议常量除外）；近邻时间窗 30 分钟含边界、不可配置（CONTEXT.md:39-41）。
