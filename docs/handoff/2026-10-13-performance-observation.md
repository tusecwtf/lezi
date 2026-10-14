# 0.5.5 云端性能观察与取消边界

当前整合状态与验收边界见[2026-10-13 整合交接](2026-10-13-integration-acceptance.md)；本文保留其历史切片的范围与测量结果。

接续 [10/11/14 原交接](2026-10-11-055-ux-followup.md)，基线公开源码
`9e47fd6`。本轮仅助手云端、合成 fixture；没有唤醒用户电脑、ADB、模拟器或 NAS。
wire、schema、SQLite FULL、哈希与媒体完整发布规则均不变。

## 按需服务器阶段采样

`ServerConfig.performance_probe` 默认 `None`，未启用时不读取采样时钟、不分配
collector、不自动输出日志。隔离 harness 显式设置
`Some(Arc::new(diagnostics::PerformanceProbe::default()))`，保留同一个 Arc，再调用
`snapshot()` 读取数字副本；没有 HTTP 端点，也没有新增环境变量或后台线程。

固定阶段：HeartbeatRead、PullRead、PullEncode、CommitDurable、CommitPublish、
MediaStage、MediaCommit、FamilyCreate、RestoreStart。
HeartbeatRead、PullRead、CommitDurable 另记录 FamilyLockWait/FamilyLockHold；每个已观察 blocking 阶段
分别记录 BlockingQueue/BlockingWork。MediaStage/MediaCommit 指已有 bundle 路由，
不是客户端照片压缩或整个媒体 PUT。

snapshot 包含固定阶段计数、最多 256 个原始 duration 和 overwritten_samples。
不接收动态标签、ID、路径、正文或异常详情。queued/running/awaiting 独立计数；
await_abandoned 表示调用方停止等待，finished_after_await_abandoned 表示工作仍完成，
都不把 HTTP 请求取消或超时解释为 SQLite/文件操作停止。错误或 panic 也会结束工作计数。
FamilyLockWait 样本还可能是取消时终止的等待，不代表每次都成功取得锁。

可重复 sampler 在 `tools/lezi-sync/tests/api/performance_observation.rs`：

```bash
cd tools/lezi-sync
cargo test --release --locked --test api sync_stage_timing -- --ignored --nocapture
```

固定一条合成 baby、无媒体，同一进程的真实 Router/鉴权/Store，heartbeat 和包含
live census 的空页 pull；预热 5 次，再保留 30 次 CSV 原始 wall 与阶段纳秒值。
比较两版时用相同测试源码、Rust 版本、release profile、fixture、冷热顺序和环境，
交错运行并避免并行构建争用。锁、队列和工作阶段会重叠，不能把它们简单相加当总耗时。
这不是网络/TLS、手机、NAS 或稳定 P95/P99 证据；debug 测试时间也不是性能结论。

## 已证实的取消互斥边界

旧行为在真实 Router + 临时 SQLite 数据根中复现：预热鉴权，隔离观察连接持有
BEGIN IMMEDIATE，待 CommitDurable worker 开始后取消请求；同家庭 pull 已能成功返回，
旧 worker 仍在执行。释放观察连接后，该取消请求的变更仍实际持久化。
这证明锁早于工作释放，没有把它宣称为已经复现的数据损坏。

仅让阻塞工作拥有原来的锁，不加新锁或新数据库事务：获取 owned guard，移入现有
blocking closure；成功把 guard 随结果带回，保持原正常释放位置；错误、panic、请求
取消时随真实工作/未消费结果销毁释放。commit 的媒体 promotion、冲突 retention、
media GET 的文件打开与流式发送仍在原锁外。

15 个原有 async 锁点：
- sync：heartbeat、causal_commit、conflict_detail、resolve_conflict、
  withdraw_conflict_branches、declare_source_relation、resolve_source_relation_group、pull_entities
- media：get_media、stage_bundle、get_bundle、commit_bundle
- identity：create_family（provisioning）、delete_family
- disaster_restore：start（provisioning）

已有 media prepare 的 blocking_lock_owned、restore batch journal lease 不重复修改。
已有不取得 family lock 的成员管理/retention 路径不在此次所有权修复中，不能把本轮表述
为给服务器新增了全面串行事务或所有跨路径一致性保证。

## 仍缺少的原计划证据

- 10：本地照片缓存仅已有按需 entries/pins/estimatedDecodedBytes；仍未获得真机
  五路径、三图进入/快滑/退出后 5s/30s 的 PSS 与 pin 配对样本。解码字节估计不是 PSS。
- 11：服务器上述阶段和可重复 sampler 已接通；Android 本地写入、照片解码/压缩、
  URI 读取/文件 hash、网络传输、Room apply 等端到端分段仍未实测。
- 14：取消/排队/运行中工作可明确区分，已有锁所有权缺口已定位；没有通过观测改变
  HTTP timeout 或强行终止已开始的 blocking 工作。未对真实家庭执行并发实验。

没有凭这些观察调整缓存容量、媒体并行度、超时、保留/删除规则或两模板交互。

## 本轮验证记录

代码切片：`0ba0e88`（按需观察）、`cc023fe`（15 个锁所有权点与取消回归）。
云端最终 Rust 门禁通过：fmt；`cargo test --locked` 的 367 个 library、208 个 API、
1 个 golden 和 5 个 TLS 测试；clippy 全 targets/features、warnings-as-errors。
4 个探索计时测试保持 ignored，不把普通门禁耗时冒充性能样本。

新增证据覆盖默认关闭、256 上限、成功/错误结果透明、排队取消、运行中取消、panic
释放；实际 Router 验证取消 causal commit 后 pull 串行、取消 bundle stage/commit 后
status 串行、取消 family create 后精确重放串行，并检查旧 worker 的持久化结果。
本轮未在真机/NAS测量，也未运行 Android 隔离双侧；后者由整合后的同源二进制和
当前 Android 测试门禁继续核验。此记录不是上线或部署授权。
