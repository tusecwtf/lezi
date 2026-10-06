---
title: 0.4.8 前台心跳同步可行性研究（并入 0.4.8 发布）
tracker: .scratch（本仓库工单目录；AGENTS.md 指定 GitHub Issues 非跟踪器）
---

# 0.4.8 前台心跳同步可行性研究（并入 0.4.8 发布）

## 0. 版本考古与前提纠正

提问者的原始前提「最近两笔提交 = 0.4.8 相对 0.4.7」需要纠正：

- HEAD `d7d1650d`（feat(sync): name pull deferrals and live census，2026-08-30）**本身就是 0.4.7 的发布提交**（树内 versionName 0.4.7 / versionCode 28 / Cargo 0.4.7 / schema 13 / contract 6）。
- **0.4.8（versionCode 29）只存在于未提交的工作区**（63 文件，+2065/−355），含三条流：
  `0.4.8-auto-near-neighbor`（服务端提交时自动写 canonical source relations）、
  `0.4.8-generation-hot-resume`（服务端把 install generation 持久化到
  `{LEZI_DATA_DIR}/generation`，NAS 重启不再强制 409 全量；前台未收敛轮静默续跑 + 零进度熔断）、
  `ui-copy-hardening`（FailureKind/FamilySyncError 文案）。9 张 0.4.7 票的 done 标记也未提交。
- 结论（2026-09-05 用户定稿）：心跳**并入 0.4.8 发布**——与 auto-near-neighbor、
  generation-hot-resume、ui-copy-hardening 三条在飞流同版本上线；工单仍独立成树
  （`.scratch/0.4.8-sync-heartbeat/`）管理，避免四流混在一张票面上。

## 1. 问题：前台期间没有对端变更感知

当前同步触发清单（全部事件驱动，无任何周期检查）：

| 触发 | 位置 |
|---|---|
| 回前台 | `app/src/main/kotlin/com/lezi/babylog/LeziApp.kt:102` `requestSync(Foreground)` |
| 网络恢复（前台，30s 去抖） | `LeziApp.kt:39` `notifyNetworkRecovered()` |
| 本地写（只推不拉，wire §13） | `domain/src/main/kotlin/com/lezi/babylog/domain/CareLog.kt:1279` |
| 下拉刷新 / 向导 / 冲突解决后 | `LogViewModel.kt:564` 等，`SyncTrigger.PullToRefresh` |
| 未收敛前台自动续跑（0.4.8 在飞） | `sync/.../RealSyncPort.kt:1392-1404` + `shouldAutoContinueForeground`（:2331-2353） |

时间轴是 Room Flow 投影，只随本地写或拉取应用而变。**两台设备同时开着 app 时，一台记录喂养，
另一台在对方重新回前台或手动下拉之前永远看不到**——这是「用户可感知的同步缺口」，
也是心跳要补的唯一缺口。后台同步被 docs 明文禁止
（`docs/prd/sync-trusted-endpoint.md` §2/§7.1「不做：后台轮询、FCM、永久前台服务」；
「进入后台停止探测和同步」；`docs/prd/tech.md` §3），本设计不越线。

## 2. 已埋好的伏笔（为什么「几乎零成本」）

- 服务端每个家庭已有单调 `family_meta(family_id, rev)`，所有提交路径都会 bump
  （`tools/lezi-sync/src/store/causal.rs:1020`、`causal_media_staging.rs:633`、
  `source_relations.rs:964`（+N）、`bundles.rs:2301`/`restore.rs:117`（置绝对值)）——
  它就是 pull cursor 的源头。**心跳信号 = 把这个整数变成只读探针**。
- install generation（restore/换代探测）与 `directory_generation`（成员/设备摘要）均已存在，
  后者在握手响应中已可比对（`ReplicaSyncEngine.kt:228-238`）。
- 0.4.7 live census 已建立「状态不一致 → 触发重走」先例（`ReplicaSyncEngine.kt:2290`）。
- 0.4.8 在飞的前台续跑循环 + 零进度熔断（≤3 次，`MAX_ZERO_PROGRESS_FOREGROUND_CONTINUES`）
  正是心跳触发轮可以直接复用的调度骨架。

## 3. 备选方案对比

| 方案 | 描述 | 判定 |
|---|---|---|
| A0 纯客户端定时轮 | 不改服务端，前台每 N 秒直接 `requestSync(Foreground)`（空轮=空页 pull） | 可行但浪费：每次空轮走完整 pull 页机制（gzip、信封、census 预算），且没有「信号」可言，无法区分「有变化」与「该查了」 |
| **A 专用探针端点（推荐）** | 新增 `GET /v1/sync/heartbeat` 返回 `{generation, head_rev, directory_generation}`；客户端比对后决定是否 `requestSync(Foreground)` | 服务端 family 锁下单行读（+目录摘要重算，家族规模几十行，可忽略）；响应 ~100 字节；信号语义精确对应 cursor diff；预留 `wait` 参数可平滑升级 B |
| B long-poll | 同端点 `wait=30..90`，服务端 per-family `tokio::sync::watch` 唤醒 | 技术可行（axum 0.8.8 + tokio 1.52 均备；公共路由 `TimeoutLayer` 300s 内；caddy 默认流式不缓冲），但客户端 H15 合同（单请求 3s/5s 预算、每轮后 `releaseForegroundKeepAlive()`）与「离散短探测」合同都要重谈——**v1 不做，`wait` 参数占位** |
| C SSE / WebSocket | 服务端推送 | axum 缺 `ws` feature；SSE 需流式生命周期 + gzip 缓冲豁免 + 保活帧；家族规模（个位数设备）完全撑不起这份复杂度。**拒绝** |
| FCM / 后台轮询 | — | docs 明文禁止；国内网络 FCM 不可用。**拒绝** |

## 4. 推荐设计（方案 A）

### 4.1 Wire（纯增量）

- 新增认证路由 `GET /v1/sync/heartbeat`（公共 router，Bearer 设备 token）。
  响应闭合三键：`{generation, head_rev, directory_generation}`。
  - `generation`：per-install 字符串（既有 `state.generation`）。
  - `head_rev`：`family_meta.rev` 整数。
  - `directory_generation`：既有成员/设备 SHA-256 摘要（按需重算，家族规模成本可忽略）。
- 能力广播**只**加在 `/v1/setup-status` 的增量 capabilities 列表（`handlers/identity.rs:56-65`）。
  **不可**加进握手：握手 capabilities 是冻结的精确键集匹配
  （wire §1；客户端 `HttpSyncBackend.kt:3114-3116` 逐键相等 + `requireExactKeys`），
  加新键会让所有老客户端握手失败。
- 预留可选 `wait` 查询参数（v1 服务端收到非零值一律按 0 处理并忽略），为 B 留升级缝。
- 信封演进遵循 live_census 先例（opt-in、增量、老端零感知）。

### 4.2 触发矩阵（客户端唯一动作 = 经熔断缝踢既有循环）
心跳**只决定是否踢一脚既有循环**，从不自己执行同步、不新增任何状态机出口：

| 比对结果 | 动作 |
|---|---|
| `head_rev != session.pullCursor`（**含 `<`**） | `requestSync(Foreground)`（conflation 去重） |
| `directory_generation != 缓存值` | 同上（前台轮的握手自然刷新目录） |
| `generation != session.pullGeneration` | 同上（轮内既有 409/full_resync 路径接管） |
| 401 终态码（device_removed 等） | 走既有 `RemoteDeviceRemovedException` 等映射，**绝不吞** |
| 全部相等 | 无动作，退避计时 |

关键修正（对抗审查发现）：**必须判 `!=` 而不是 `>`**——灾难恢复把实体按 `rev=1..n` 重插并把
`family_meta.rev` 置回 cursor（`store/restore.rs:91-119`），只判 `>` 的客户端会永久静默卡死，
只剩手动下拉才能撞上 `cursor_ahead → full_resync`。

跨拍熔断（review 2026-09-05 P2-6 修复，落实 §9.6）：NeedsSync 经 `ForegroundRoundFuse`
（heartbeat 子包内部调度状态，唯一所有者）缝踢既有 conflated 循环——同一会话/端点 × 同一
观测三键信号在零进度预算耗尽后熔断，后续同信号拍照常探针、照常喂 availability，但绝不再
开数据轮；三键任一不同（回退也算）、真实回前台/本地写/下拉/网络恢复、成功或耐久进度解除
熔断并重置计数；身份（family/device/endpoint/membership）切换整体作废。心跳的踢与内部续跑
各走专用缝（`kickHeartbeatRound` / 续跑 re-kick），不经 `requestSync` 真实触发缝，避免被当成
真实回前台清熔断。

### 4.3 客户端循环（`sync/` 模块，RealSyncPort conflation 循环旁）

- 生命周期：前台 + `session.isJoined` + 端点已 TOFU 固定时启动；**后台取消（非跳过）**
  （现有 syncSignal 循环是 `continue` 跳过，`RealSyncPort.kt:360-361`——心跳必须更严格，
  对齐「进入后台停止探测」合同）；观察 `preferences.session`，unjoined/reauthRequired 即取消。
- 节奏：**30s 基线，无变化按 ±20% 抖动退避至 120s**；本地写完成、探到变化、回前台时重置基线；
  回前台首个探针 **+8s 防抖**（避开 onStart 全量同步与 30s 网络恢复去抖的突发）。
  `currentStatus == Syncing` 时跳过当拍（探针不排队、不累积）。
- 预算：复用 `FamilyHttpOperation.Probe`（3s 连接 / 5s 响应 / 1 次尝试 / 8s 总计，
  `FamilyHttpBudget.kt:32-41`）——不要发明新的，也不要误用 Handshake 的重试表。
- 失败/活性语义（第二轮 grill 用户定稿：**心跳与 availability 统一模型**）：心跳是
  `FamilyServerAvailability` 的常驻喂食者——成功即复健 Available 并刷新 `lastServerHealthyAt`；
  失败按既有 `isAvailabilityTransportFailure`（`RealSyncPort.kt:2466-2471`）分类降级，
  **单次失败即降级、无新迟滞**（与既有喂食者完全一致）；降级期心跳节奏切换为既有重试梯
  30s/120s/600s（`FamilyServerAvailabilityPolicy`），恢复探测自动化。仍不写
  `currentFailureKind`、不进同步失败 UI——availability 的唯一 UI 消费者是网络设置页，
  主时间轴/账户状态行不采集，无感知不破。TOFU `SpkiPinMismatchException → TrustChanged`
  照常浮现（匿名探针与心跳同走 TOFU 后端）。事实依据（2026-09-05 复核）：「健康租约门」
  在代码中不存在——`shouldProbe` 的租约只抑制冗余探活，`sync()`/引擎不检查 availability，
  tech.md §3 的租约条款是愿景性描述（其「协调器私有网络门闩」注记）——统一模型零提交
  路径风险；同时修掉存量缺口「降级后无人自动重探、可用性烂在设置页直到手动刷新」。
- 探针永不持有前台 keep-alive（它不是同步轮，不参与
  `releaseForegroundKeepAlive()` 契约），永不把状态行翻成 Syncing。
- 与 0.4.8 续跑熔断的关系：心跳触发轮就是普通 Foreground 轮，零进度续跑上限（≤3）天然封顶
  任何「探针→空轮→再探针」的循环。

### 4.4 服务端（`tools/lezi-sync`）

- handler：认证（既有 `authenticate`）→ family 锁 → 单行读 `family_meta.rev` + 目录摘要 →
  释放锁再返回（镜像 `sync.rs:512` 的锁纪律；**绝不能驻留 family 锁**，否则探针会阻塞提交）。
- 日志 `DEBUG` 级（公共路由挂 TraceLayer，INFO 级在 10 设备 × 15s 下会是 ~5.7 万行/天）。
- 专属限流 scope（当前 sync 端点全部无限流，高频探针端点不应裸奔）。
- `authenticate` 的 `last_used_at` 写已有 60s 去抖（`store/identity/session.rs:565-581`），
  30s 探针 ≈ 每设备每分钟 ≤1 次 UPDATE，可忽略。
- 顺带修复存量盲区：**家庭改名既不 bump `family_meta.rev` 也不进 `directory_generation`**
  （`identity.rs:655` → `session.rs:550-558`）——今天安静期改名就传播不出去，与心跳无关也是洞。
  本 spec 一并定为：改名 bump rev。

### 4.5 语义变化（记录在案，接受）

- 成员「最近同步」派生自设备 `last_used_at` 最大值
  （`tools/lezi-sync/src/store/identity/session.rs:88-94`，`family_directory_snapshot`）——
  心跳会让「app 开着」的设备持续显示「刚刚」。判定：**诚实语义**（设备确实在线），接受并写进
  wire 文档注记；不做只读认证变体（多一条路径不值）。
- 本地写后回声：LocalWrite 只推不拉（§13），写后 `head_rev > cursor` 必然成立，心跳最迟一拍后
  补一次前台轮把自己的实体幂等拉回（version_id/`updated_at` 跳过，wire §12.3），有界 ~2 轮/写批，
  且顺带收敛对端变更与 census。**接受**，§13 增补说明「前台期免拉经济性由心跳节奏接管」。

### 4.6 探针形态统一（第二轮 grill 定稿）

- 已加入 + 前台：认证心跳是活性与变化的**唯一真相源**——同一次调用同时喂变化信号与
  availability。
- 设置页手动刷新：已加入时改发**单次认证心跳**（拿到家族级真相：水位/目录摘要/活性），
  替代匿名 `/health`+`/ready`+setup 三连；匿名三连保留给未加入/无 token 场景
  （还没加入也要能看服务器脸色）。
- 文档修订清单在 §4.1 基础上追加：tech.md §3 的租约措辞对齐现实（租约只抑制冗余探活，
  不门控同步轮）。

## 5. 对抗审查判定汇总（两个独立审查 agent）

| 攻击面 | 判定 | 处置 |
|---|---|---|
| 自己写的回声轮 | risky-but-manageable | 接受（§4.5），spec 明说不是免费的 |
| 同步轮进行中探针 | fine | `Syncing` 期跳过当拍 + conflation 兜底 |
| **restore 后 rev 回退盲区** | **broken** | 触发条件改 `!=`（§4.2） |
| **401 终态被静默吞掉** | **broken** | 终态码走既有映射（§4.2） |
| `last_used_at` 每探针写库 | 误报 | 已有 60s 去抖，非问题 |
| **能力加进握手键集** | **broken（如按初稿）** | 只在 setup-status 广播（§4.1） |
| 目录变更/改名不 bump rev | broken（存量） | 探针响应带 `directory_generation` + 改名 bump rev（§4.2/§4.4） |
| 多设备同时探针/拉取 | fine | per-family mutex 串行 |
| 分页中途探针 | 浪费但无害 | `Syncing` 期跳过覆盖 |
| 15s 探针无线电功耗 | **broken @15s** | 30s 基线 + 120s 退避 + 抖动（§4.3） |
| H15/keep-alive 合同 | manageable | 复用 Probe 预算类；探针不持 keep-alive（§4.3） |
| 失败喂 availability | 统一模型（第二轮 grill 用户定稿，推翻初版纯静默推荐） | 成功复健/失败按既有分类降级/降级期走既有梯子；经复核租约门不存在、UI 仅设置页（§4.3） |
| 可用性降级后无人自动重探（存量缺口） | broken（存量） | 心跳兼任恢复探测器（§4.3） |
| caddy gzip / NAS 30s grace / WAL 写放大 / 流量 | fine | 逐一核实过（§4.4） |
| 会话中途 unjoined/reauth | manageable | 循环观察 session 流即取消（§4.3） |
| 快速前后台切换突发 | manageable | +8s 首拍防抖（§4.3） |

## 6. 兼容矩阵

| 组合 | 行为 |
|---|---|
| 新 APK × 新服务端（VPS/NAS 同版本对，CD 合同保证配对发布） | 心跳生效 |
| 新 APK × 老服务端（无 `sync_heartbeat_v1` capability，或 404） | 循环永久停用，现状不变；404 同样视为永久停用，**不**走 `sync()` 失败路径（不得触发 availability 降级或 app-update 搭车逻辑，`RealSyncPort.kt:1434-1445`） |
| 老 APK × 新服务端 | 服务端纯增量路由，零感知 |
| NAS 回滚（旧版本容器 + 旧 APK 配对） | 心跳随版本对一起回滚，无交叉态 |

## 7. 风险表

| 风险 | 缓解 |
|---|---|
| 探针循环在后台泄漏（合同违约 + 耗电） | 取消式生命周期 + 集成测试断言后台 0 探针 |
| 心跳失败路径误入同步失败 UI | 探针独立于 `sync()` 的失败处理；测试覆盖超时/DNS/TLS 不产生失败 UI（可用性照实降级，仅设置页可见） |
| 空轮风暴（探针 bug 反复触发） | conflation + 120s 轮上限合同 + 零进度熔断 + 服务端限流 scope 四层封顶 |
| 老服务端 404 误触发重试 | 404 = 永久停用，不退避重试 |
| `directory_generation` 按需重算成本 | 家族规模几十行哈希，30s 节奏可忽略；如未来需要可缓存失效于写路径 |
| wire 文档漂移 | §1（capabilities 增量语义）、§7.1（认证探针携带家庭元数据的许可）、§13（回声说明）三处修订随代码同票落地 |

## 8. 已定决策（两轮 grill，用户 2026-09-05 确认全部）

1. **新鲜度/电池**：30s 基线 → 120s 退避（±20% 抖动），回前台 +8s 防抖。夜间喂奶被动亮屏场景优先。
2. **失败耦合**：**统一模型**（第二轮用户确认，推翻初版纯静默推荐）——成功复健+刷最近
   健康时间、失败按既有分类降级（无迟滞）、降级期走既有 30s/120s/600s 梯子；已加入态
   设置页手动刷新改发单次心跳，匿名三连保留给未加入场景（§4.3/§4.6）。
3. **回声成本**：接受 ~2 轮/写批，修订 §13 说明。
4. **版本落点**：并入 0.4.8 发布（2026-09-05 用户二次定稿，推翻首版「独立 0.4.9」）——
   四条流同版本上线；版本参照系相应变化：兼容矩阵中的「老服务端」指当前线上的 0.4.7。

## 9. 验收标准草案（供 spec 引用）

1. 前台期对端写入 → 本机时间轴最迟 ~基线间隔 + 一轮同步内出现该记录，无任何用户操作。
2. app 退后台后（含被动亮屏锁屏），网络面上零探针流量（tcpdump/日志断言）。
3. 心跳超时/不可达时：主时间轴/账户状态行与本地写推送行为与无心跳完全一致；可用性照实
   降级（仅设置页可见）并按梯子自动重探，恢复后自动复健。
4. 灾难恢复后（rev 回退），客户端在下一个探针周期内自动进入 full_resync，无需手动下拉。
5. 老服务端（无 capability / 404）下，新 APK 功能等价于现状，无重复探针。
6. 心跳触发轮计入零进度熔断与 120s 轮上限；熔断后探针继续但不再触发轮（直到出现变化信号）。
7. 服务端：探针 handler 不驻留 family 锁；DEBUG 日志；专属限流；旧客户端无任何行为变化。
8. 家庭改名在安静期最迟一个探针周期 + 一轮内传播到所有前台设备。
9. 已加入态打开网络设置页手动刷新：仅发一次认证心跳，结果与后台心跳同源。
