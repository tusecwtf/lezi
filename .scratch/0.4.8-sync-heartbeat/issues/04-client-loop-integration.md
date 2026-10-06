# 04: 客户端：RealSyncPort 集成（变化信号踢循环与生命周期）

**What to build:** 端到端 tracer bullet 落地：两台设备同时开着 app，一台记录喂养，另一台的
时间轴最迟约一个心跳周期加一轮同步内自动出现该记录，全程零用户操作；app 退后台后网络面上
零探针流量。引擎（03）的「需要同步」信号接入既有 conflated 同步循环，生命周期完全遵守
「前台限定、取消式收尾」合同。

**Blocked by:** 03。

**Status:** done

- [x] 变化信号 → `requestSync(Foreground)`，经 conflated 通道合并去重
- [x] `Syncing` 期间跳过当拍（不排队不累积）；回前台首个探针 +8s 防抖；本地写完成/探到变化/回前台重置基线节拍
- [x] 后台**取消**（非跳过）循环；集成测试断言后台零探针；观察会话流，未加入/需重登即取消
- [x] 探针 401 终态码走既有设备移除/家庭删除异常映射（不吞）
- [x] 心跳触发轮计入零进度续跑熔断与 120s 单轮上限（复用 0.4.8 机制，不新增保险丝）
- [x] 文档落地：wire §13 回声说明（本地写后 ~2 个回声前台轮被明示接受）；tech.md §3 租约措辞对齐现实（租约只抑制冗余探活，不门控同步轮）
- [x] 集成级测试覆盖以上全部；`./gradlew test` 全绿

**Landed (0.4.8 stream, ticket 04):** capability 武装接受兼容矩阵（research §6）中的任一信号。
主路径是引擎的一次性**发现拍**（`SyncHeartbeatEngine.allowDiscoveryBeat`）：常驻条件成立
（前台+已加入+endpoint 已 pin）而 gate 仍 NotAdvertised 时，循环每拍请求一次发现许可——成功
即以探针证据武装 Active，404 即永久停用（对老服务端每进程恰好一次请求，此后零流量、不影响
availability），其余失败按该失败自身的降级梯子稍后重试且不算任何一方证据。加速路径保留
`RealSyncPort.probeServerAvailability` 的 `/v1/setup-status` Ready 响应
（`SetupProbeResult.Ready` 新增增量 `capabilities`；只对当前 verified endpoint 生效，重连候选
探测不会误武装；404 永久停用后迟到的广播不得重新武装）。回声说明落 wire §13；租约措辞修正落
tech.md §3。集成测试：`RealSyncPortHeartbeatLoopTest`（13 例，含 conflations、后台取消零探针、
+8s 防抖、Syncing 跳拍、会话失效取消、401 终态映射、纯发现拍老服务端单请求后永久静默、纯
发现拍新服务端零操作武装按节奏续拍、404 后迟到广播不可重新武装、LocalWrite 经端口钩子重置
暴露的基线 deadline、404 永久停用、availability 双向喂食、零进度熔断复用）。
