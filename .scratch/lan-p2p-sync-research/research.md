# 局域网去中心化副本同步：可行性调研

Status: research-only（不改 PRD / ADR；当前合同仍禁止 P2P 主路径）

日期：2026-08-31

问题：每台设备保存完整护理记录与计划副本；家庭同网或共处时自动发现并对账，从而不依赖远程服务器，面向没有 NAS/VPS 的家庭。

---

## 0. 结论

**能做，但不能用「去中心化 / 区块链」把现有家庭服务器换掉。**

更精确的拆解：

| 用户直觉 | 实际情况 |
|----------|----------|
| 「每人一份数据库」 | **已经成立。** Room 是本机真相源；加入家庭后每台设备都有完整护理副本（[`docs/spec/contracts/data-model.md`](../../docs/spec/contracts/data-model.md)、[`CONTEXT.md`](../../CONTEXT.md) 离线可用状态）。 |
| 「局域网碰到就自动对账」 | **工程上可行，但只在双方 App 都醒着、同网/共处、且已配对时。** Android 不允许安静的 24h 网格。 |
| 「因此可以没有服务器」 | **只对「同屋、都开着 App」成立。** 奶奶家 vs 家里、半夜只有一方在记、设备被撤、照片还没传完、管理员钥匙丢失——这些都需要一个**醒着的节点**或明确接受「几天不同步」。 |
| 「这像区块链」 | **问题域相反。** 区块链解决的是互不信任的陌生人如何对公共账本达成唯一历史；家庭是 2–5 台已配对设备、需要墓碑/restore、照片隐私、以及 choice-only 冲突。 |

当前产品已经是 **local-first + 可选中心协调者**：

- 本机写入永不阻塞网络（[`sync-trusted-endpoint.md`](../../docs/spec/contracts/sync-trusted-endpoint.md) §1、§7）。
- 家庭服务器只验证约束、盖章、CAS，**不裁决护理真相**（[ADR-0019](../../docs/adr/0019-server-validates-constraints-not-care-truth.md)）。
- 并发编辑走 `causal_sync_v2` commit-first + choice-only ConflictSnapshot（[ADR-0022](../../docs/adr/0022-commit-first-choice-only-conflict-snapshots.md)）。
- PRD 明确不做：P2P 主路径、多主复制、后台轮询、永久前台服务（[`docs/spec/product.md`](../../docs/spec/product.md) §4.7；[`sync-trusted-endpoint.md`](../../docs/spec/contracts/sync-trusted-endpoint.md) §2）。

若将来要服务「没有后端的家庭」，正确形态不是链，也不是把 Room 换成 Automerge，而是：

> **可选的共处/同网 gossip，搬运已经存在的冻结 mutation + 内容寻址照片；家庭服务器变成可缺席的邮箱/管理员钥匙盒，而不是日常记账门槛。**

这是新产品合同，不是小改。工作量按「重做身份 + 冲突权威 + 传输」，以月计，不是以周计。

---

## 1. 用户想法里其实有三个问题

1. **副本**：每台设备一份护理图。
2. **发现 + 传输**：同网或共处时自动对账。
3. **无远程权威**：没有 NAS/VPS 也能建家、加人、撤设备、冲突选择、发照片。

(1) 已交付。(2) 有现成 Android/开源栈，约束很大。(3) 才是硬问题，也是当前 `lezi-sync` 真正在做的事。

服务器今天承担、P2P 必须另找主人的职责（ADR-0019 §1 + `sync-trusted-endpoint` §3–6）：

- 认证、membership 分配、设备 token 轮换/吊销
- 唯一管理员（根密码，不在 App 里）
- ACL（宝宝档案只管理员写）
- 不可变盖章（作者、WakeObservation 观察者、履行候选）
- 媒体完整性与原子包
- 幂等 `mutation_id`、CAS、`version_id` 签发
- ConflictSnapshot **由服务端独占重建**（ADR-0022 §2：客户端不得提交 `resolved_root`）
- APK 门槛与自托管更新
- 空服务器上的灾难恢复（ADR-0014），禁止两个已配置家庭合并

把「探测到就广播」接上，解决不了上表。

---

## 2. 为什么不是区块链

Bitcoin 白皮书自己写的问题是：**互不信任的双方如何在没有金融机构的情况下防止 double-spend**。手段是公开广播全部交易、工作量证明时间戳链、最长链即历史（[bitcoin.org/bitcoin.pdf](https://bitcoin.org/bitcoin.pdf) Abstract、§2、§4、§12）。

家庭护理日志需要的全部相反：

| 区块链假设 | 乐记家庭 |
|------------|----------|
| 参与者匿名、可 Sybil | 已用 QR / 管理员批准配对的 2–5 台设备 |
| 必须有唯一全局历史 | 允许未解决冲突；choice-only，不静默选赢 |
| 公开账本 | 宝宝照片与护理事实；不能上公网 DHT |
| 不可逆 | 需要墓碑、显式 restore、成员硬删除匿名化 |
| 为防伪造烧 CPU/能源 | 夜喂设备要省电 |
| 出块延迟（分钟级） | 同屋应对秒级对账 |

「私有链 / 许可链」去掉了 PoW 之后，剩下的就是**带数字签名的复制日志**。乐记已经有更贴切的模型：`base_version` + `mutation_id` + N-way ConflictSnapshot。那是 Git 式 DAG，不是链。

Holochain / Ceramic / IPFS+链 同理：它们优化的是公网、不可信、内容寻址发布。家庭 Wi-Fi 上的 3 部手机不需要全球共识。Ink & Switch 的 local-first 论文把 CRDT/本地副本当基础，也**没有**把区块链列为实现路径（[inkandswitch.com/essay/local-first](https://www.inkandswitch.com/essay/local-first/)）。
PoW 的能耗与延迟对家庭 App 无意义；PoS 去掉能耗也去掉不了「全球验证者集合」这个前提。许可链删掉了区块链唯一的好处（无许可根），却留下共识成本。Holochain 自己也写全球共识「通常是故意做贵的」（[the basics](https://developer.holochain.org/concepts/1_the_basics/)）；2–5 台已知设备不需要随机见证，奶奶的手机也不该验证任何人。


---

## 3. 真正接近的模型：local-first，不是 Web3

Ink & Switch（Kleppmann 等，Onward! 2019）把 local-first 定义为：本机副本是主本，服务器若存在只是辅助同步；可用本机 Wi-Fi 或蓝牙与附近设备对账（同上，§3 “The network is optional”）。七条理想里，乐记已经满足「无 spinner / 离线可写 / 本机所有权」；缺的是「无云协作」。

他们同时写了：让普通用户 **self-host Web 应用不可行**。这正是本调研的产品动机。但他们给的技术答案是 **CRDT 自动合并**，不是家庭显式选择。
同一论文后半明确：**「网络通信仍是未解问题」**（NAT 随路由器/拓扑不可靠）；**云服务器仍有位置，但是当 cloud peer：发现、备份、突发计算，不是关键路径上的中央权威**。这几乎是对现有 `lezi-sync` 的定义。CRDT 对「同一对象同一属性并发改」可以任意选赢，论文认为对看板像素「怎样都行」——护理事实不行。


乐记已经拒绝全文 CRDT：

> 全文 CRDT（Automerge 等）过重，不消除「两条实体」语义，也不适合 NAS 有界运维。  
> — [ADR-0019](../../docs/adr/0019-server-validates-constraints-not-care-truth.md) 拒绝表；[ADR-0018](../../docs/adr/0018-neighbor-duplicate-records-and-tombstone-wins.md) 同样拒绝。

原因仍然成立：妈妈记一次喂奶、爸爸也记一次，是**两条事实**（疑似重复组），不是同一 JSON 文档的字段冲突。CRDT 会「干净地」留下两条或错误地合成一条；产品要的是展开来源 + 人来选展示版（ADR-0021）。

**更贴乐记的开源类比是 Git，不是 Automerge：** 每台设备提交不可变 mutation，分叉可见，合并是显式 choice。Automerge 自己也承认与 Git 的相似性，但默认自动 merge（[automerge.org/docs/hello](https://automerge.org/docs/hello/)）。

---

## 4. 拓扑：四种，只有两种适合乐记

### A. 现在：星型 + 常开协调者（已交付）

```
Phone A ──HTTPS/TOFU──► lezi-sync (NAS 或 VPS)
Phone B ──HTTPS/TOFU──►
```

优点：一方睡觉另一方仍能对账；管理员钥匙不在手机上；WAN（已有 Tailscale/公网）自然成立。  
代价：没有后端的家庭进不来——这就是用户想解的点。

### B. 纯共处 P2P（无常开节点）

```
Phone A ◄── Nearby / LAN 加密 ──► Phone B
         （双方 App 前台、已配对）
```

优点：零服务器、隐私最好。  
致命：双方必须同时醒着。Android Doze、熄屏、一方去奶奶家，副本分叉直到下次共处。照片大包更容易卡在「记录到了、图没到」——当前原子包合同会把整条藏起来。

产品上可以做成「回家打开账户页就跟家里的手机对一下」，但必须把「待同步」理解成**人对人邮箱**，不是家庭权威已收敛。

### C. 混合：共处 gossip + 可选枢纽（若做，应走这条）

```
有 NAS/VPS：行为与今天相同（枢纽是稳定邮箱）
无枢纽：  仅共处/同网对账
有枢纽但离线：本机照记，回来再 commit
```

这与 Ink & Switch「服务器是副本不是主本」一致，也与 Evolu 的盲中继、Iroh 的 relay-as-fallback 同类。  
**不**等于现在把 `lezi-sync` 删掉。

### D. 全自动多主 CRDT / 链

与 ADR-0019/0022 冲突。不建议。
Raft/etcd 明确：更新需要多数在线，永久失去法定人数是灾难（[etcd FAQ](https://etcd.io/docs/v3.5/faq/)）。三台家庭设备分成奶奶家/家里，多数不成立 = 不能记夜喂。这与「本机写入永不阻塞」直接相反。Gossip（Demers 1987 / Dynamo）适合当**运输**，不适合当真相模型。Dynamo 自己对无因果的两个版本是「都保留，交给客户端语义调和」——这就是 ConflictSnapshot，不是 CRDT 自动合成。


分区故事（必须写进任何 P2P 设计）：

> 妈妈在奶奶家记一夜喂养；爸爸在家改同一条计划并履行。两机都无共同 `base_version`。重逢时必须变成 ConflictSnapshot，而不是 LWW。若分区期间两边都做了 choice，choice 本身还要再冲突。

当前合同靠**单一 commit 权威**避免两边各自「重建结果」。P2P 若要保持 choice-only，每个 peer 必须跑**同一份** golden merge，且 choice 也是带 `mutation_id` 的一等 mutation——等于把 Store 的 conflict 模块搬进每一台手机，并删除「服务端独占重建」（ADR-0022 §2）。这是合同级变更。

---

## 5. 发现与传输（Android）

前台同步政策（回前台 / 下拉 / 写成功）与「自动广播」只能在**前台会话**里对齐。永久前台服务、后台轮询已被合同禁止。

### 5.1 Nearby Connections（能力最完整，但不宜当乐记主路径）

官方：完全离线的 P2P；BLE + 经典蓝牙 + Wi-Fi；连接加密；双方都要接受；Bytes（≤32k）/ File / Stream（[Overview](https://developers.google.com/nearby/connections/overview)）。

策略（[Strategies](https://developers.google.com/nearby/connections/strategies)，约 100m 无线电范围）：

| 策略 | 拓扑 | 带宽 | 乐记用法 |
|------|------|------|----------|
| `P2P_CLUSTER` | M:N 网状 | 较低 | 小 mutation 八卦 |
| `P2P_STAR` | 1:N | 较高 | 一台临时当枢纽发照片 |
| `P2P_POINT_TO_POINT` | 1:1 | 最高 | 大照片包 |

约束：

- 依赖 **Google Play Services**（[Get started](https://developers.google.com/nearby/connections/android/get-started)）。无 GMS 的国产 ROM 直接出局。
- 危险权限：位置 / 蓝牙扫描广告 / `NEARBY_WIFI_DEVICES` / Android 17 起 `ACCESS_LOCAL_NETWORK`。用户拒了就不能发现。
- SDK 收集发现/连接延迟、机型、国家、构建版本与包名（Overview “Data Collection”；可在系统「使用与诊断」关）。与自托管、少依赖 Google 的姿态冲突。
- 双方必须在无线电范围内**同时** advertising/discovery。锁屏后不可靠。
- 连接是对称认证流：适合「看到家人设备、点接受」，不适合静默吃掉局域网里所有乐记进程。

结论：能力上最像「碰到就传」，但对乐记应降为可选加速，不当发现/传输主路径。

### 5.2 NSD / DNS-SD（同 Wi-Fi 服务发现）

官方 NSD 用 DNS-SD 在局域网广播服务名与端口（[Use NSD](https://developer.android.com/develop/connectivity/wifi/use-nsd)）。适合「家里 Wi-Fi 上找另一台乐记」。

Android 16 起局域网访问开始设闸；**targetSdk 37 / Android 17 强制 `ACCESS_LOCAL_NETWORK`**：未授权时 TCP 超时、UDP `EPERM`，`NsdManager`、原始套接字、`.local` 解析全部覆盖。无权限路径只剩系统 `NsdManager` picker（`DiscoveryRequest.FLAG_SHOW_PICKER`）（[Local network permission](https://developer.android.com/privacy-and-security/local-network-permission)）。

LocalSend 协议 v2：组播 `224.0.0.167:53317`，组播失败则向网段逐 IP `POST /register`；HTTPS 证书设备内生成，指纹 SHA-256，可选 PIN（[localsend/protocol](https://github.com/localsend/protocol)）。README 要求关掉路由器 **AP isolation**，访客网络经常默认打开。OpenWrt 官方 zeroconf 文档写明 LAN↔WLAN 桥和托管交换机默认可拦组播，常需关 IGMP snooping（[OpenWrt zeroconf](https://openwrt.org/docs/guide-user/network/zeroconfig/zeroconf)）。国内 Mesh / 访客 Wi-Fi 会让 NSD **静默失败**。

NSD **不是**传输层，只是发现。后面仍要 TOFU/Noise/预共享家庭密钥，不能因为同 SSID 就信（ADR-0010：身份不是网络名）。组播公告本身会泄露「谁在家、什么手机」——未配对前应静默或把名字用 QR 派生密钥加密。
### 5.3 Wi-Fi Aware (NAN)

Android 8+ 可无 AP 直连，官方举例就是照片分享（[Wi-Fi Aware overview](https://developer.android.com/develop/connectivity/wifi/wifi-aware)）。但：

- `PackageManager.FEATURE_WIFI_AWARE` **硬件可选**；Wi-Fi Direct / SoftAP / 热点占用时可能不可用。
- 可用性随时变，收到 `ACTION_WIFI_AWARE_STATE_CHANGED` 必须丢弃会话。
- Android 13 要 `NEARBY_WIFI_DEVICES`。

不能当唯一发现路径。

### 5.4 Iroh（Rust P2P 栈，有官方 Kotlin/Android）

[What is iroh](https://docs.iroh.computer/what-is-iroh)：用加密 QUIC 连 EndpointID（密钥，不是 IP）；同网可直连；否则 **relay 回退**（官方：约 9/10 网络能打洞，[NAT traversal](https://docs.iroh.computer/concepts/nat-traversal)）。官方 Kotlin 绑定覆盖 Android aarch64/armv7，**须从源码编 NDK**，Maven 工件不含 Android（[Languages/Kotlin](https://docs.iroh.computer/languages/kotlin)）。文档规定：进后台 `ep.shutdown()`，回前台再 bind；要后台保活必须 FGS，否则系统杀套接字——与乐记前台合同同构。

mDNS 局域网发现是独立 crate，默认关闭（[local address lookup](https://docs.iroh.computer/connecting/local-address-lookup)）。公共 relay 只适合开发；生产应自建（[relays](https://docs.iroh.computer/concepts/relays)），流量 E2E。blobs 适合照片。

所以 Iroh 把「无服务器」变成「无应用服务器，但仍有发现/打洞中继」。家庭自建 relay 可以和 `lezi-sync` 同机；纯 LAN 可关 relay。公共 relay 会把元数据路径放到第三方。

### 5.5 双方都睡着时发生什么

什么都不发生。这不是实现缺陷，是手机 OS 合同。

- Doze：灭屏后网络受限，任务推迟到维护窗；待机桶 *rare/restricted* 直接禁网。前台进程无此限制（[power-details](https://developer.android.com/topic/performance/power/power-details)）。
- Android 14+ 前台服务必须声明类型。枚举里**没有** `nearbyMessaging`；最接近的是 `connectedDevice` 或 `dataSync`。Android 15：`dataSync` FGS 每 24h 合计 6 小时上限，不能从 `BOOT_COMPLETED` 拉起（[FGS types](https://developer.android.com/develop/background-work/services/fgs/service-types)、[Android 15 behavior](https://developer.android.com/about/versions/15/behavior-changes-15)）。这与合同「禁止永久前台服务」一致，也堵死了「后台网格」捷径。
- 系统认可的存在唤醒几乎只有 Companion Device Manager：在 BLE 范围内才绑定 `CompanionDeviceService`，面向配件配对，不是家庭手机网格（[CDM](https://developer.android.com/develop/connectivity/bluetooth/companion-device-pairing)）。
- Apple Multipeer Connectivity 是最完整的 OS 级 P2P，**进后台就停止 advertising/browsing 并断开会话**（[Apple MPC](https://developer.apple.com/documentation/multipeerconnectivity)）。平台自己都把 P2P 做成前台作用域。

Syncthing 桌面能「自动」是因为进程常驻，且默认仍用 `discovery.syncthing.net` 与 `relays.syncthing.net`（[Getting Started](https://docs.syncthing.net/intro/getting-started.html)）。BEP 用 TLS 1.3，Device ID = 证书 SHA-256（[bep-v1](https://docs.syncthing.net/specs/bep-v1.html)）；局域网发现是 UDP 广播 `:21027`，不是 mDNS（[localdisco-v4](https://docs.syncthing.net/specs/localdisco-v4.html)）。**Syncthing-Android 已于 2024-12 停更**，官方理由含 Play 发布（[syncthing-android](https://github.com/syncthing/syncthing-android)）。手机上要前台服务 + 电池白名单，与乐记合同冲突。

---

## 6. 数据层技术栈对照

目标：因果版本、choice-only、原子媒体、可吊销 membership、前台-only、2–5 设备、可无 NAS。

| 栈 | 冲突模型 | 常开节点 | Android | 媒体 | 与乐记 |
|----|----------|----------|---------|------|--------|
| **现有 `causal_sync_v2` + 自研 LAN 传输** | 已有 choice-only | 可选 | 已有 Room | 已有原子包 | **唯一不推翻领域合同的路径** |
| Automerge / automerge-repo | 自动 CRDT merge；网络无关（[hello](https://automerge.org/docs/hello/)） | 可选 sync-server | 需 JNI/WASM，非 Kotlin 一等 | 文档型，照片需外置 | ADR 已拒自动 merge |
| Yjs | 自动 merge；y-webrtc 偏浏览器 | 常要 signaling | 非 Room | 同左 | Web 优先，不贴 |
| Loro | LWW Map；官方建议数据非 JSON 或已有 client–server 时换方案（[loro.dev/docs](https://loro.dev/docs)） | 自管传输 | Rust/JS/Swift；Kotlin 需 loro-ffi | 文档型 | 自己把乐记排除在适用面外 |
| cr-sqlite / vlcn | v1：**自动 merge，无手动合并**；v2 历史路径未发布（[README](https://github.com/vlcn-io/cr-sqlite)） | 自管 `crsql_changes` | 无官方 Android 构建；近休眠（v0.16.3） | 非文件包 | 插入约 2.5×；会静默覆盖护理字段 |
| Jazz | 字段 LWW + 自有 sync server | **需要 Jazz 服务器** | JS 生态 | 自有 | 换中心，不是去中心 |
| Evolu | E2E，盲 relay | **需要 relay**；官方写明客户端 P2P「在路线图，等待蓝牙/Wi-Fi/LAN 传输」（[relay](https://www.evolu.dev/docs/relay)） | SQLite/JS | 加密 blob | 连专门做 local-first 的项目都还没做出手机 LAN |
| ElectricSQL / PowerSync | 中心 Postgres | **必须有** | PowerSync 有 Kotlin SDK | 行复制 | 更好的 `lezi-sync` 实现，不是替代 |
| Couchbase Lite P2P | 可自定义 conflict resolver；Multipeer = Wi-Fi DNS-SD + BLE（CBL 4.1） | 不必 | 官方 Android | 文档附件 | **最完整的手机网格**；**Enterprise Edition**，换存储引擎 |
| Ditto | delta-state CRDT 自动 merge；仍有「trusted peer」服务器 | 商业 Small Peer 网格 | 有 | 自有 | 闭源企业；静默 merge + 销售门 |
| Willow / Earthstar | 同意后才收数据；真删除；Meadowcap 无中心能力系统（[willowprotocol.org](https://willowprotocol.org)） | 规范级 | 无 Android 实现；TS beta | Drop/sneakernet | 哲学最贴，工程为零 |
| Syncthing (BEP) | 文件级，冲突变 `conflicted copy` | 实质上要常驻 | Android 包装已停更 | 整文件 | 错层：文件不是护理图；默认全球发现/中继 |
| LocalSend | 无副本，一次性文件 | 前台 | 有 | 文件 | 发现/TOFU 可抄，不是同步 |
| libp2p | 传输/发现积木 | 公网仍要 bootstrap/relay | Go/Rust FFI | 自管 | 比 Iroh 更底层、更重 |

**不要换数据库来换拓扑。** 贵的是身份、冲突权威、媒体原子性；这些已经在 Room + wire 里。P2P 只应增加一种 **SyncBackend 传输**，而不是第二套事实。

---

## 7. 开源与产品对照

### 7.1 育儿日志

| 产品 | 同步 | 开源 | 说明 |
|------|------|------|------|
| **乐记 + lezi-sync** | 可信 HTTPS 家庭服务器 | 本仓库 | 已是 self-host 中心 |
| **LiaLog**（闭源） | Play 页宣称家庭 Wi-Fi 无云同步 | 否 | 能找到的少有 LAN P2P 育儿 App；靠**持续前台服务**保活，正好是乐记禁止的路径 |
| **Anytype / any-sync** | 本地优先 + P2P CRDT DAG；文件仍外置；自述区块链不适合实时 E2E 文档（[any-sync](https://github.com/anyproto/any-sync)） | 是 | 架构近亲；护理事实仍不能静默 CRDT；本地-only 模式官方标实验性 |
| **Baby Buddy** | Django + 中心 DB；手机是客户端（[babybuddy/babybuddy](https://github.com/babybuddy/babybuddy)，BSD-2） | 是 | 成熟 self-host，**不是 P2P**；Android 伴侣仍要服务器 |
| **Vaava** | 官网：「QR 配对，家庭 Wi-Fi 点对点共享；数据不上传服务器。跨网/备份走付费 Family Data Sharing。」（[vaava.app](https://vaava.app/)） | 否 | **市场上最接近用户描述的消费级实现**；闭源，冲突/吊销不透明 |
| PiyoLog / Glow | 厂商云账号分享 | 否 | 隐私与「无后端」目标相反 |

公开可核的 OSS 育儿应用里，**没有**可复用的「手机多主护理图 + 显式冲突」实现。要么中心 self-host（Baby Buddy / 乐记），要么闭源 LAN（Vaava）。

### 7.2 通用 P2P / local-first（可借零件，不可借产品）

- **Iroh** MIT/Apache，Rust + Kotlin：传输 + blobs。仍要 relay 做 WAN。
- **Automerge** MIT：文档 CRDT。语义不贴。
- **cr-sqlite** MIT：SQLite 多主。自动 merge 不贴；Android 扩展难。
- **Syncthing** MPL：设备 ID 互信 + 可关全球发现。文件层。Android 包装停更。
- **LocalSend** Apache-2：LAN HTTPS、自签、组播 + 逐 IP 回退。一次性。发现/TOFU 可抄。
- **Couchbase Lite** 专有：Android 上最完整的 P2P 数据库，含 Multipeer。许可与锁定不可接受则排除。
- **Briar / Cwtch**：对抗性威胁模型的消息，不是结构化护理图。
- **SSB / Manyverse**：append-only 公网 gossip，不适合宝宝照片。

---

## 8. 没有服务器时，身份与吊销

当前：`LEZI_BOOTSTRAP_SECRET` 只存在部署环境；管理员设备全丢可由运维改根密码恢复（`sync-trusted-endpoint` §3.1）。普通成员不能选举管理员。

纯 P2P 必须回答：

1. **谁是第一台？** 建家设备生成家庭签名根。丢了这台 = 丢家，除非有纸质/age 备份（乐记 CD 已用 age，但是给 NAS 密钥的）。
2. **加人？** QR 交换设备证书 + 家庭根签名（比今天的 10 分钟 grant 更像 Syncthing device ID 互加）。同网自动出现的未配对设备必须当敌意 STA。
3. **撤设备？** 八卦 membership epoch / CRL。被撤设备若是**唯一带着最新照片**的节点，吊销 = 丢图。中心服务器至少还握着权威副本。
4. **远程擦除？** 合同已承认离线设备不能保证即时擦除。P2P 更弱：被撤设备只要不再遇见家人，本地副本永远在。
5. **两个「新家庭」？** 两台离线手机都点「新建家庭」→ 两个根。ADR-0014 禁止合并已配置家庭。没有 probe `empty|configured` 的中心，这个分叉无法机械消除。

这些不是传输问题，是**家庭身份单主**问题。用户要的「推广给没有后端的家庭」必须接受：管理员钥匙在某台手机或一张纸上，丢失即无法治理。

---

## 9. 可行性判断

| 问题 | 判断 |
|------|------|
| 每台设备一份护理副本 | **已有** |
| 前台、已配对、同网/共处时交换 mutation + 照片 | **可行**（优先 NSD/组播 + 钉指纹 TLS；Nearby 仅 GMS 设备可选） |
| 完全消灭任何枢纽仍保持现在的身份/冲突/APK/WAN | **不可行** |
| 用区块链降低复杂度 | **负相关** |
| 用 Automerge/cr-sqlite 换掉因果合同 | **与已接受 ADR 冲突** |
| 混合：无 NAS 则共处对账，有 NAS 则仍走 `lezi-sync` | **可行，但是新合同**，工作量 ≈ 新同步代 |

**对「大多数没有后端服务器的家庭」：** 纯 P2P 能覆盖「两台手机晚上都在家、都打开过 App」；覆盖不了「一方长期不在同一 Wi-Fi」。消费级对标 Vaava 自己也为此卖了跨网同步。Ink & Switch 也没声称可以没有辅助副本。

推广路径上，**树莓派/NAS 上的 `lezi-sync`（或 Baby Buddy 那种中心）** 仍然比手机网格更符合「一份家庭日志」的产品句。P2P 是降低门槛的**附加**，不是替代。

---

## 10. 若仍要探索：最小不毁合同的实验

不要开主路径。若做原型，守这些边界：

1. **不改 Room 事实模型、不引入 CRDT 自动 merge。** 只把已冻结的 `causal_sync_v2` mutation + 媒体 spool 换一种运输。
2. **发现必须配对。** QR / 已有 membership 证书；禁止「同 Wi-Fi 就收」。
3. **只在前台。** 与现触发器相同；不申请常驻 FGS。
4. **无 GMS 降级。** Nearby 失败则 NSD+自签 TLS，再失败则保持离线。
5. **冲突仍 choice-only。** 任一侧不得本地重建 root 当权威；无枢纽时只能交换分支并显示「待双方都在场时选择」，或把现有 conflict 模块变成纯函数并冻结进 golden（这步一旦做，就是新 ADR）。
6. **枢纽缺席是可见状态**，不是静默多主。文案要能说「家里还没有家庭服务器，只有两台手机对着传」。
7. **不碰生产 NAS/VPS。** 证书测试隔离规则仍然适用。

建议的技术零件（若做）：

- **发现 + 传输主路径：** 前台注册 `_lezi._tcp`（或 LocalSend 式组播 + 逐 IP 回退）+ 设备自签 TLS、指纹钉死（与现有 TOFU/SPKI 同构）。Android 17 要处理 `ACCESS_LOCAL_NETWORK`。
- **不要**把 Nearby Connections 当主路径（GMS + 遥测 + 无 GMS ROM）。
- **不要** Wi-Fi Direct / Aware 当唯一路径（权限、OEM、与热点互斥）。
- Iroh 仅在关掉公共 relay、只走 LAN 时才考虑。
- 身份：QR 交换设备证书 + 家庭根；复用现有邀请信封形状。家庭根 age 备份（已有备份目录约定）。
- 明确非目标：区块链、Automerge、cr-sqlite、Syncthing 文件夹、后台网格、静默同网收包

---

## 11. 来源

### 本仓库

- [`CONTEXT.md`](../../CONTEXT.md) — 离线模式 / 离线可用 / 家庭对账 / 因果基线
- [`docs/spec/product.md`](../../docs/spec/product.md) §4.7 — 中心化默认同步；不做 P2P 主路径
- [`docs/spec/contracts/sync-trusted-endpoint.md`](../../docs/spec/contracts/sync-trusted-endpoint.md) — 单写入权威、前台、禁止多主
- [`docs/spec/contracts/causal-sync-wire.md`](../../docs/spec/contracts/causal-sync-wire.md) — `causal_sync_v2`
- [ADR-0019](../../docs/adr/0019-server-validates-constraints-not-care-truth.md)、[ADR-0022](../../docs/adr/0022-commit-first-choice-only-conflict-snapshots.md)、[ADR-0018](../../docs/adr/0018-neighbor-duplicate-records-and-tombstone-wins.md)、[ADR-0010](../../docs/adr/0010-trust-server-identity-not-network-name.md)、[ADR-0014](../../docs/adr/0014-owner-device-restores-only-empty-family-servers.md)

### 一级外部

- Martin Kleppmann et al., *Local-first software*, Onward! 2019 — https://www.inkandswitch.com/essay/local-first/
- Satoshi Nakamoto, *Bitcoin: A Peer-to-Peer Electronic Cash System* — https://bitcoin.org/bitcoin.pdf
- Automerge — https://automerge.org/docs/hello/
- Iroh — https://docs.iroh.computer/what-is-iroh · https://docs.iroh.computer/languages
- cr-sqlite — https://github.com/vlcn-io/cr-sqlite
- Nearby Connections — https://developers.google.com/nearby/connections/overview · strategies · android/get-started
- Android NSD — https://developer.android.com/develop/connectivity/wifi/use-nsd
- Android 16/17 局域网权限 — https://developer.android.com/privacy-and-security/local-network-permission
- Android Doze / 待机桶 — https://developer.android.com/topic/performance/power/power-details
- Android FGS 类型 — https://developer.android.com/develop/background-work/services/fgs/service-types
- Android 15 `dataSync` FGS 时限 — https://developer.android.com/about/versions/15/behavior-changes-15
- Companion Device Manager — https://developer.android.com/develop/connectivity/bluetooth/companion-device-pairing
- Android Wi-Fi Aware — https://developer.android.com/develop/connectivity/wifi/wifi-aware
- Apple Multipeer Connectivity（对照）— https://developer.apple.com/documentation/multipeerconnectivity
- Couchbase Lite Android P2P — https://docs.couchbase.com/couchbase-lite/current/android/p2psync-websocket.html
- Syncthing BEP / 局域网发现 — https://docs.syncthing.net/specs/bep-v1.html · https://docs.syncthing.net/specs/localdisco-v4.html
- Syncthing-Android（停更）— https://github.com/syncthing/syncthing-android
- LocalSend 应用与协议 — https://github.com/localsend/localsend · https://github.com/localsend/protocol
- OpenWrt zeroconf / 组播 — https://openwrt.org/docs/guide-user/network/zeroconfig/zeroconf
- Baby Buddy — https://github.com/babybuddy/babybuddy · https://docs.baby-buddy.net/
- Vaava（闭源对标，营销页）— https://vaava.app/
- Evolu Relay — https://www.evolu.dev/docs/relay
- Holochain basics — https://developer.holochain.org/concepts/1_the_basics/
- etcd quorum FAQ — https://etcd.io/docs/v3.5/faq/
- any-sync — https://github.com/anyproto/any-sync
- Iroh NAT / relay / mDNS / Kotlin — https://docs.iroh.computer/concepts/nat-traversal · https://docs.iroh.computer/concepts/relays · https://docs.iroh.computer/connecting/local-address-lookup · https://docs.iroh.computer/languages/kotlin
- Loro docs — https://loro.dev/docs
- Couchbase Lite Multipeer — https://docs.couchbase.com/couchbase-lite/current/android/p2psync-multipeer.html
- Willow — https://willowprotocol.org
- IPFS content addressing — https://docs.ipfs.tech/concepts/content-addressing/
- Jazz — https://jazz.tools/docs
- ElectricSQL architecture — https://legacy.electric-sql.com/docs/reference/architecture
- PowerSync Service — https://docs.powersync.com/architecture/powersync-service
