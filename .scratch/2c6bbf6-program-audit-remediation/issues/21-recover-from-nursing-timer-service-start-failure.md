# 21 — 计时前台服务启动失败可恢复

**What to build:** 捕获护理计时前台服务启动受限、权限或系统异常，在不闪退且不伪造“正在运行”的前提下回到明确可重试状态。

**Blocked by:** None — can start immediately

**Status:** implemented-awaiting-device-smoke

**Size:** S–M

## Acceptance criteria

- [x] 前台服务启动入口捕获平台可能抛出的受限启动、权限和运行时异常，不让异常逃出到主线程。
- [x] 启动失败后持久化/UI 状态不会显示一个实际不存在的运行中计时器。
- [x] 若计时意图可安全保留，则回到明确的暂停/待启动状态并提供重试；否则说明失败且不丢失已输入信息。
- [x] 服务成功启动后才发布运行确认；通知创建失败也走同一安全恢复契约。
- [x] 应用重启能区分真实存活计时、可恢复待启动状态与已失败请求，不自动制造重复计时。
- [x] 自动化测试覆盖成功、每类启动异常、重复点击、初始化恢复和重试成功。

## Validation

运行护理计时状态机、Service 与通知测试，以及应用编译和静态检查；在受限后台启动环境做设备 smoke。

## Documentation Gate

更新计时器状态与错误反馈说明，不承诺系统未确认的“正在计时”。

## Evidence

- [实现与自动化证据](../evidence/21/timer-service-start-recovery.md)
- 受限后台启动环境的设备 smoke 尚未执行，因此本票不得标记 `complete`。
