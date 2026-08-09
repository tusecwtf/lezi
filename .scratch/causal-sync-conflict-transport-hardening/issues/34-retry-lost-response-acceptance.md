# 34 — 验收 timeout、丢响应与退避

**What to build:** 用确定性 fault proxy 证明 connect/response timeout、commit/resolution lost response、429/503、Retry-After 与 full jitter 遵守票 15 预算且不重复事实。

**Blocked by:** 15、31

**Status:** ready-for-agent

## Contract slice

只覆盖请求/重试层；使用固定 clock/random seed，分别验证 handshake、commit、resolution 与 media-prepare 预算类别。

## Implementation sequence

1. 脚本化 timeout、post-durable disconnect、429/503 faults。
2. 运行 commit/resolution lost-response replay。
3. 运行合法/非法 Retry-After 与 jitter boundary。
4. 验证 budget exhaustion 后 pending/terminal 状态。

## Acceptance

- [ ] durable result replay 无 duplicate/version drift
- [ ] delay/attempt/elapsed 符合预算表
- [ ] non-idempotent 不盲重试
- [ ] UI/telemetry 诚实且不泄漏内容

## Validation

- [ ] deterministic fault matrix 通过并记录 seed
- [ ] Android/Rust relevant tests 通过

## Out of scope

不覆盖 gzip/page/resource saturation。
