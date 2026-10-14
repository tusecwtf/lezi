# 私有源回流完成交接（2026-10-14）

状态：已完成。公开整合（[PR #11](https://github.com/tusecwtf/lezi/pull/11)，squash 合并 `a14e211f`）已于当日回流私有 master，并经 pre-push 隐私过滤重新发布为快照 `d359de61`。本文只记公开可核对事实；私有侧完整 SHA、逐文件裁决与门禁日志保留在私有追踪。

## 回流与发布核验

- 便携补丁按公开基线 `9e47fd6` 生成，在独立干净基线 worktree 重放后与 `a14e211f` 树逐文件一致（0 差异）；补丁不含真实地址、账号、凭据或本机绝对路径。
- 私有调和树经完整门禁：Rust fmt/test（368+208+5 通过、6 ignored）/clippy，9 项部署脚本隔离测试，Android 全量 JVM 测试 + debug 与全模块 instrumentation APK 构建，同源 isolated 双侧 seam、opt-in 采样器与搜索 SQL 合同（7/7）均通过。
- 脱敏快照 `d359de61` 相对 `a14e211f` 的差异恰为预期集合：本地附加的诊断/采样层（CareLatency、照片生命周期采样、因果结算阶段追踪）与饱和度测试 sqlite 忙等移植；无任何计划外文件变化。
- 回流后公开 CI：[Android run 38560114152](https://github.com/tusecwtf/lezi/actions/runs/38560114152) 成功。快照未触碰服务端源码，Rust CI 按 paths 过滤未触发，`a14e211f` 的 Rust run 38542278423 仍然有效。

## 调和裁决摘要

- 本地与云端重复实现同一批性能票据的两套代码，以公开整合为主线：媒体下载改由 OwnedMediaDownloadStore/MediaDownloadReceipt 承载，服务端观测由 PerformanceProbe 承载；被覆盖的本地重复实现已按等价覆盖记录退役，未静默丢弃。
- 本地独有且公开侧无对应层的附加能力保留并随快照发布：CareLatency 应用层阶段追踪（domain/composer/因果结算生产点）与照片生命周期 opt-in 采样。
- 一处本地引擎守卫排序修复经评估不适用于公开引擎（其修复的前置租约检查机制在公开实现中不存在，守卫先行/延迟合同已由公开实现满足），未移植。
- 已知取舍：本地 Rust SQLite connect 成本归因仪表无公开对应物，属未采样的研究代码，未保留；如确有需要应基于 PerformanceProbe 重做。

## 仍开放（不变）

- 设备验收（36 方法、TalkBack 按日导航、真实权限往返、五路径/三图 PSS+pin 采样）与 NAS 验收仍由用户手动执行；本次未运行 ADB/设备/NAS。
- 服务端运行时代码较 NAS 现网 0.5.4 已有变化；如需部署另行确认窗口，走既有 push-and-deploy 保护入口。
