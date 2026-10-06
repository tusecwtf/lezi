Status: ready-for-human
缓解: 已部署

# 0.4.5 同步卡死 — 诊断与修复计划

日期：2026-08-29 · 设备：成员手机（姥姥的设备，无线 ADB 192.168.50.x）
安装：`com.lezi.babylog` versionCode 26 / versionName 0.4.5（2026-08-24 安装，出自 0bdcf653）
家庭：某家庭 · NAS 源 `https://192.168.50.4:8765`（server 0.4.5，schema 13，health/ready 正常）

## 证据（全部实读，非推断）

**手机端（uiautomator 实读）**
1. 反复弹「网络问题：家里正在同步」；「家庭成员与设备：正在读取家人…」长期不结束、
   「暂时无法读取成员与设备，请重试」；本机成员「尚未同步」。
2. 状态行「已保存在本机 · 待同步 1 项」，下拉刷新后不收敛、无弹窗。
3. 时间轴 8/10–11 三条「睡眠 进行中 · 妈妈 · 重叠待确认」；今日本地睡眠「年年睡觉中」。
4. 睡眠合计 404h32m。

**服务端（docker logs + lezi.db 只读副本，分析后已删除副本）**
1. 日志零错误：无 invalid_stored_payload / sync store failure / commit 饱和；仅一条良性
   `fulfilled_record_deleted` WARN。容器 8-23、8-26 重启时权威图校验通过（443→497 实体）。
2. **服务端数据干净**：全部 100 条 sleep（除今日进行中一条）都有未撤回的 wake，且
   wake_timestamp >= sleep.timestamp——「醒来早于睡下」毒组假设**被证伪**。
3. 成员/设备：妈妈（成员手机，owner）今天 09:49 仍在正常发表（rev 752–756）；
   爸爸（成员手机）昨天活跃；**成员手机=姥姥，最后一次成功使用 8-26 12:36**。
4. 成员手机 历史：最后一次发表 8-19 11:38；8-20、8-21 被三次 `device_removed` 后重新登录
   （每次重登=强制全量重同步）；access token 8-26 12:51 过期，此后无任何成功轮次。
5. 家庭实体量 536+，服务端分页 200 实体/页 → 全量重同步必然 ≥3 页。

## 根因（修订后）

**RC1（主因）：0.4.5 拉取验证器把「跨页重复下发实体」当致命错误，全量重同步必然触发。**
0.4.5 `ReplicaSyncEngine`：
```kotlin
require(pageKeys.none(observedEntityKeys::contains)) { "家庭服务器在连续 pull 页重复返回实体" }
```
服务端按原子组规划分页，后页会幂等重发 Baby/Record/履行父实体（ebe26637 注释明确这是
wire 合同）。单页增量轮（妈妈、爸爸）永不触发；一旦客户端落后到多页——成员手机 被
device_removed 后重登触发全量重同步（536 实体 ≥3 页）——第二页的重发实体触发该 require，
整轮中止，cursor 冻结，且家庭数据只增不减，之后每一轮都更长、永远失败。已由
**ebe26637**（08-26）修复：改为仅同页重复才报错，跨页重发按幂等处理。

**RC2（卡死外溢）：0.4.5 周期在分类拉取失败后整轮终止、无终态回执。**
→ 「待同步 1 项」被扣（到不了发表）；对账锁被永续重试占住（名册/登录/批准全变
「家里正在同步」）；无放弃/回执机制。已由 **3a9d41d7**（08-27）修复（终态回执、放弃、
分类拉取失败仍 commit-first 发表、拉取诊断回执）。

**RC3（数据表现）：cursor 冻结在 8/10–11 的 wake 之前** → 本机看那些睡眠永远「进行中」，
睡眠合计被撑到 404h32m；本机新记的记录（今日睡眠等）只能「已保存在本机·待同步」。
服务端权威图本身完整健康——升级后拉通即自愈，无需服务端修数据。

**附带发现（独立）**：`invite.example.invalid` 公网 A 记录 NXDOMAIN（zone 08-26 变更），
VPS caddy TLS 失败——ticket 03 前置破坏，需先恢复 DNS。工作机 DNS 被本机代理 fake-ip
劫持，公网域名排查必须走 DoH。

## 修复计划

### Phase 1 — 出 0.4.6 客户端（HEAD 25ce6988 已含全部修复）
1. bump versionName 0.4.6 / versionCode 27（联动 upgrade-source 合同检查）。
2. 门禁：`./gradlew test`、`./gradlew lintDebug`；`cd tools/lezi-sync && cargo fmt --all -- --check && cargo test --locked && cargo clippy --all-targets --all-features -- -D warnings`。
3. 签名 release APK；app-update.json sha256 + signer 摘要合同；
   `LEZI_PACKAGE_APP_UPDATE_CHECK_ONLY=1 ./deploy/test-package-nas-app-update.sh` 冒烟。
4. 成员手机 最快路径：`adb install -r` 同签名升级（数据保留）——需用户确认窗口。
   NAS 更新渠道（/download/lezi.apk + app-update.json，现仍是 8-23 的 0.4.5 包）随包更新。

### Phase 2 — 成员手机 验证（红→绿判据）
- 首次前台/下拉 → 全量重同步走通（多页幂等重发不再中止）；
- 状态行「已同步 · 刚刚」，待同步归 0（本机待发项 commit-first 发表）；
- 「家庭成员与设备」列出妈妈/爸爸/姥姥+设备；不再弹「家里正在同步」；
- 8/10–11 三条睡眠闭合（服务端 wake 拉到即闭合）；睡眠合计回落到正常量级；
- 账户页 token 过期自动重登成功。

### Phase 3 — 全家庭核对
- 妈妈/爸爸设备各自升级（他们未卡，但同样在 0.4.5，同样有 RC1 风险——任何重登/换机/
  代际重置都会触发同一死法）；
- 互查：成员手机 本机待发项在妈妈设备可见；成员手机 时间轴与妈妈一致。

### Phase 4 — 环境修复（ticket 03 前置）
- 恢复 `invite.example.invalid` 公网 A 记录，再验 caddy TLS + 公网五项 gate。

## 遗留与不确定
- 成员手机 cursor 冻结点的精确 rev 与触发 marker（重复返回实体 vs 引用未就绪）在手机本地库里，
  release 包不可读；两类都已在 HEAD 修复，不改变处置。
- NAS /download 上的 0.4.5 APK（8-23）是更新渠道现状：0.4.6 出包后需按 fail-closed
  流程替换（APK+json+signer 三对齐）。

## 0.4.6 安装验证结果（2026-08-29 10:17 安装，10:18–11:10 验证）

**结论：0.4.6 原地升级未解除 成员手机 挂死。ebe26637/3a9d41d7 修复的拉取类死因不是本机当前的卡点。**

### 完成项
- 0.4.6/code 27 切版（app/catalog/Cargo/app-update/device-test/PRD 文档 9 处）；
  门禁全绿：`test`+`lintDebug`+`assembleRelease`（签名校验过，signer=ce1438c8… 与合同一致）、
  cargo fmt/test(310+190+5)/clippy 全过；`test-package-nas-app-update.sh` 冒烟过。
  期间一次 flaky：`RealSyncPortIdentityClearTest.confirmedMemberLeave…`（barrier 机制区域，隔离重跑过，未复现）。
- APK 6b274804…d6cf 已 `adb install -r` 到 成员手机（versionCode 27/0.4.6，数据保留）。
- NAS /download 渠道未动，仍服务 0.4.5 包（对全家无影响）。

### 成员手机 上 0.4.6 的实测（证据）
1. **全程零网络**：应用 UID 终身流量仅 3.5KB/1.8KB（手机 uptime 3.4 天）；
   NAS `ss` 多窗口采样（冷启动 30s、下拉刷新 12.5s、随机 48s）8765 端口 0 连接——
   装后从未发出任何 TCP，包括本应每 15 分钟一次的 token refresh。
2. 状态行恒为「已保存在本机 · 待同步 1 项」= ShallowSyncState.Pending ⇒ transportStatus=Idle
   （Syncing 在 barrier 内 gate 之后才置位）⇒ **同步轮从未到达 gate**，也从未失败（无 Error/Reauth）。
3. 名册「正在读取家人…」永久：0.4.6 静默失败路径（loading=false/error=null/loaded=false 仍渲染同一文案）
   或调用本身挂起；0.4.5 时代的「家里正在同步」弹窗不再出现（被静默化）。
4. 匿名健康探测正常（端点设置页「可连接 · 10:36:45」）⇒ HttpURLConnection/TLS/路由均好；
   端点确认是 NAS `https://192.168.50.4:8765`，非公网域名。
5. 进程内渐进劣化：约 1 小时后账户页主体塌缩（家庭/宝宝/管理段全部消失），重启即恢复。
6. 睡眠合计失控增长：404h32m(10:00,0.4.5)→422h→429h→431h→455h→457h(11:10)，
   增速≈每小时数十小时 ⇒ 本地近全部睡眠被投影为「开放至今」。0.4.5 时代为 404h 稳定值
   ⇒ ebe26637 的 wake 投影改动与「本地缺全部 wake（cursor 冻结）」状态交互后行为改变。
   健康设备（有真实 wake）不受影响，但该状态下的投影需在 0.4.7 复核。

### 定性
成员手机 的挂死在「任何网络动作之前」：轮停在 barrier 获取后、gate 前的区间
（`recoverPendingLocalClearLocked`→DataStore/Keystore 持久化路径，或信号/前台状态不触发轮）。
release 包无日志、无 root 无法取协程栈；模拟器侧（debug 包+本地 dev 服务器+JDWP）
复现尝试因向导密码框位于对话框内滚动折叠区而未完成 join，未取得栈。
8-21 重登后曾健康同步 5 天、8-26 12:36-12:51（NAS 容器重启窗口+token 过期）后永久挂死
⇒ 触发器疑似「NAS 重启窗口内被打断的一次 refresh」。

### 下一步（二选一，需用户决定）
A. **快速解封（推荐先做）**：成员手机 退出这台设备 → 妈妈的 OPPO 生成成员二维码 → 成员手机 重新扫码加入
   → 0.4.6 全量重同步（多页幂等重发已放开）→ 顺带验证 Phase 2 全部判据。
   历史先例：8-21 重登后同步恢复了 5 天。风险：若挂死与登录态无关可能复发，届时即有新证据。
B. **继续根因**：出 0.4.7 debug 诊断版（协程 dump/关键路径日志）装到 成员手机 复现取栈。
   成本：多一轮构建+安装；收益：根治。A 无效时必走此路。

## 全卸载重装验证（2026-08-29 23:14–00:05，附 jdb 实锤）

**结论：清数据 + 重装 + 重新扫码加入后，0.4.6 同步引擎本身工作正常（握手/拉取/名册/发表/token 刷新全部恢复），
但拉取无法收敛到此家庭当前 head（756+）：在 cursor 283 处确定性失败，且伴随两个次生问题。修复需 0.4.7 代码变更。**

### 过程
- `pm uninstall` 全清 → 浏览器装 NAS 0.4.5（MIUI「USB 安装」限制绕过路径：单次安装授权+指纹验证）→ `install -r` 升 0.4.6。
- 用户自行扫码完成成员加入（姥姥 membership 2181bc38 重新绑定，device 4d7b0f4d）。
- 期间用临时 debuggable+无混淆 release 包（已恢复配置并重装正式包 flags=0x0）做 JDWP 断点/异常捕获取证。

### 实锤证据链（jdb 断点逐行 + 异常捕获 + DataStore/Room 直接读取）
1. sync()→syncMutex→startup recovery→引擎轮(215/216/218/219)→handshake(743)→媒体下载(getMedia 1803)：全链路畅通，
   之前「零 TCP」部分是采样方式问题（只看 established、1–2s 间隔），部分是旧安装真实挂死。
2. **拉取死循环**：`ReplicaSyncEngine.kt:622` 抛 `IllegalArgumentException("同步数据引用尚未就绪[类型:uuid]")`
   ——pull 页中存在依赖不满足的实体 → 整页事务回滚 → cursor 保留「以便重试」→ 每轮同一页再失败 → 永不推进。
   cursor 实测钉死在 283（正是 8/11 13:10 那组睡眠/wake 的 rev 区间），服务端 head 756+。
   且 putPullDiagnostic 回执写在同一回滚事务里 → 诊断信息也永远丢失（bug）。
3. **本机事实被服务端终态拒绝**：新录的醒来观察 123a8efb 反复「家里没收下」（badGroup 类终态回执）。
4. **投影膨胀**：睡眠合计 404h→967h 持续增长（本地缺失全部 wake 的睡眠被投影为「开放至今」，每小时加 N 小时）。
   健康设备不受影响（有真实 wake），但历史缺口设备上数值失控。
5. 正常项：名册 4 位家人可读、无 busy 弹窗、预算熔断（120s/轮）以「同步时间太长」正确浮出 UI、
   冷启动跨小时会话存活（token 15 分钟 TTL 期间多次成功操作）。

### 0.4.7 需要的修复（客户端）
1. `reference_unready` 不再整轮死循环：对无法满足引用的实体改隔离/跳过+记录（或按依赖序重排页内应用），
   保证 cursor 可推进；pull 诊断回执移出回滚事务。
2. 开放睡眠投影加兜底（缺 wake 的老睡眠不按「至今」累计，或上限截断）。
3. 服务端侧需用 sudo 查：8/11 组为何客户端引用不满足 + 为何拒绝 123a8efb（下次窗口）。

### 仓库/设备状态
- HEAD 工作树含 0.4.6 切版（未提交）；app-update.json sha256=2ec68721…（诊断构建后重新出包的干净正式包，
  签名 ce1438c8… 合同一致，`test-package-nas-app-update.sh` 通过）。NAS /download 渠道仍未动。
- 成员手机 现装正式 0.4.6（非 debuggable），姥姥已重新加入，可正常本机记录；全家其余设备仍 0.4.5 未动。

## 正式 0.4.6 修复出包（2026-08-30 02:00–03:30）

**修复内容（全部客户端，随 0.4.6 一次性出包）：**
1. **引用未就绪有界重试**（ReplicaSyncEngine）：页内改「可解析子集提交」——未解析实体不抛异常、
   不回滚整页；同轮跨页二次机会（按 pullStallKey 去重、取最新 rev）；仍失败进入持久重试台账
   （transport journal 单键 JSON），checkpoint 回持到最老未解析 rev 前，最多重投递 3 次
   （MAX_PULL_STALL_ATTEMPTS），超限永久跳过并留 `reference_unready` 诊断回执。
   **安全发布顺序**：有未解析页的 cursor 只在内存累积，post-loop 二次机会/台账/回持全部完成后
   单点发布——后续页传输失败不会把未解析实体甩到已发布 cursor 之后（专项测试覆盖）。
2. **诊断回执持久化**：写入移出页事务；每轮拉取前清陈旧回执、本轮新洞存活；creator-ack 只对已应用实体清算。
3. **relation sidecar 跟随 Record 可解析性**（§12.3）：applyRecord 引用门（custom item + baby）
   前置于 applySourceRelationSummary；未解析 Record 不再提交 source-role sidecar。
4. **投影兜底**（CareAggregation）：开放 >24h 无 wake 的睡眠零时长累计（不再「至今」膨胀），
   但仍计 1 段（物理入睡事实）；未来起始不计数；窗口边界改严格 <。
5. **发布链**：catalog 26→released、target 27/0.4.6；package-nas.sh/validate-nas-package.sh 新增
   0.4.6/code27/floor21/schema13/rollback-0.4.5 case；PRD（wire §1.4/§12.3、tech.md、README 等）同步。

**门禁与评审：** `test`+`lintDebug` 全绿（含 8 个契约测试改写 + 7 个新测试）；cargo
fmt/test(196)/clippy 全绿；`test-package-nas-app-update.sh` 过。code-review 双轴：Standards 0 硬违规
（4 条 smell，采纳去重 1 条）；Spec 终审 correct（期间实抓 6 个真缺口全部修复：observedEntityKeys
丢失、跨页多计 attempts、台账陈旧键、后续页失败甩洞、sidecar 先于引用门、段数误除）。

**官方产物：** app-release.apk sha256 `c8e5d3b9f68c246e65743d1b4a58e1952f03efa45aa2e690fd04ba5844851b28`，
signer ce1438c8…（合同一致），非 debuggable；app-update.json 已对齐。已装 成员手机（同签名原地升级）。

**成员手机 验证（判据全达成，于同一修复链的 fdab8a59 构建 + 官方 c8e5d3b9 安装后）：**
「已同步 · 刚刚」；待同步 0；无网络问题弹窗；名册 4 位家人即读；睡眠合计 967h→21-22m 回落；
预算熔断与重试按设计运行。官方包装机后手机进入 PIN 锁屏，最后一次 UI 复核待解锁后由用户目视确认。

**遗留（本票缓解已部署，下列转出）：** ~~服务端 sudo 排查~~（已闭环，见下节结论：服务端无缺陷、Rust 代码无需修改、NAS 无需重部署）；
全家其余设备（妈妈 OPPO、爸爸 成员手机）升级、NAS /download 渠道、0.4.6 elder 重出与 VPS 窗口见 `.scratch/0.4.7-sync-hardening/plan.md`；仓库改动仍未提交。

**发布修订（2026-08-30 02:30 grill 定稿）：** elder 适配并入 **0.4.6 重出**（用户决定，`c8e5d3b9`
作废；成员手机=姥姥=elder 目标设备）；仅 JVM 门禁出包、成员手机 点检复验；序列与 0.4.7 加固范围见
`.scratch/0.4.7-sync-hardening/plan.md`。

## 服务端 sudo 排查结论（2026-08-30 02:00–02:40，DB 只读副本 + planner 重放）

**结论：服务端数据与分页无缺陷，两项遗留问题均闭环，无需服务端修复或 NAS 重新部署。**

方法：`ssh -p 10000` 取 `lezi.db` 只读副本（sha256 `c958f961…`，NAS 与本机一致，无 WAL 附属），
sqlite 只读分析 + 用仓库 Store 在副本上重放 pull 分页（临时测试已删，src 树无残留，pull_tests 8/8 过）。

1. **Q1 cursor 283 = 确定性分页边界，非数据洞。** 家庭 `6bd93992`（546 实体，head 770）从 0 起分页为
   0→283（200）/ 283→491（199）/ 491→770（190）——两次独立安装都冻在 283 正是第一页边界。
   逐页重放 + 全部客户端引用门静态模拟（baby/custom/sleep/plan/fulfilled-record/fc/media/wake-retarget，
   含 tombstone 行保留、flag-only 清权）：**三页全部可解析，页内零重复键**。仅 5 处引用指向页外父实体，
   全部由 planner 的依赖重发带入被引用方所在页（rev 192/201 重发入页 2；96eb24b0@573、a6794b7a@576
   重发入页 3）。页 283→491 内容与 8-29 逐字节等价（窗口内无行被再 rev）。
   ⇒ 8-29 的 `引用尚未就绪` 来自客户端设备内部状态（Room/CausalSettlement，release 包不可读），
   **不是服务端缺数据**；已部署的有界跳过修复已实证带 成员手机 走通同三页到 head。若复发，持久化
   `reference_unready` 回执会直接点名实体。
2. **Q2 123a8efb = 妈妈的事实，服务端从未拒绝。** membership `760ba8da`=妈妈(owner)：sleep `a6794b7a`
   8/19 22:45 发起、wake `123a8efb` 8/20 08:15 观察（wake_timestamp 正常，无时钟偏移）、8/21 10:41 sleep
   回填 `effective_wake`（rev 551/576）。mutation_receipts 6/6 accepted，零拒绝/分支。
   成员手机 上「家里没收下」是其 cursor 冻结期的客户端投影（卡在 283 时根本收不到 rev 551），非服务端拒收。
3. 三对撤回（01b43e10+74b9e136、87c19667+0c054548、9a1c8968+856add0d）sleep+wake 成对 tombstone，
   服务端图自洽；客户端 tombstone 走 upsert 保留行，正常可解析。
4. 副本已删（本机）；NAS 侧 `/tmp/lezi-forensic` 待用户 sudo 删除。NAS 仍 0.4.5/schema 13，本次未动。

## Comments

2026-08-30：标记缓解已部署。成员手机 已装 0.4.6（引用未就绪有界重试 + 睡眠投影兜底），卡死判据全绿；服务端无缺陷、NAS 未重部署。本票不再作为同步卡死开放项。
