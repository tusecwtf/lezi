# 15 — 实现 Retry-After 与有界 full jitter

**What to build:** 建立一个可注入 clock/random 的 typed retry policy，优先合法 Retry-After，否则执行 capped exponential full jitter，并按唯一预算表终结。

**Blocked by:** 14

**Status:** ready-for-agent

## Contract slice

预算表：handshake/detail `3s connect, 10s response, 3 attempts, 30s elapsed`；pull/commit/resolution `3s, 20s, 3, 60s`；预留 media prepare `5s, 90s, 3, 240s`。本票接入已存在操作；media prepare 由票 17 接入。stale/expired 终止当前 resolution request 并转票 09 refresh，不终止事实。

## Implementation sequence

1. 实现服务端错误类别与 Retry-After 解析。
2. 实现 deterministic full-jitter 与上述预算表。
3. 将策略接入现有 handshake/detail/pull/commit/resolution；只有幂等请求可自动重试。
4. 映射诚实 pending/terminal UI 与无内容 telemetry。

## Acceptance

- [ ] 合法 Retry-After 优先，非法值回退 jitter
- [ ] delay/attempt/elapsed/timeout 均符合表中硬上限
- [ ] auth/capability/ACL/canonical 不盲重试
- [ ] stale fact 保留并进入 refresh，不标永久失败

## Validation

- [ ] deterministic clock/random/error matrix tests 通过
- [ ] 429/503/timeout 隔离 fault smoke 通过

## Out of scope

不引入后台调度。
