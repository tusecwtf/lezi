# 14 — 建立认证同步握手

**What to build:** 在 endpoint trust 与家庭 session 后用一次认证握手取得 capability、readiness、principal/role、目录 generation、limits、compression 与 retry hints。

**Blocked by:** 12、13

**Status:** ready-for-agent

## Contract slice

普通同步不串行依赖 health/ready/setup；成员目录只在 generation 改变或显式刷新时读取。protocol mismatch 在 mutation 前 fail closed。

## Implementation sequence

1. 实现握手 wire 与 auth/not-ready/mismatch 错误。
2. 服务端从 session/ACL 权威派生 principal、role、limits。
3. 客户端每次 foreground sync 只消费一个 handshake result。
4. 以 directory generation 驱动刷新并保留 actor-ID fallback。

## Acceptance

- [ ] 正常 sync 只有一次握手，无运维探针链
- [ ] mismatch/not-ready/auth 在 mutation 前终结
- [ ] generation 不变不下载成员，显式刷新可强制读取
- [ ] trust/session/certificate 模型不改变

## Validation

- [ ] server/client contract/auth/directory tests 通过
- [ ] 隔离 TLS 服务 smoke 通过

## Out of scope

不删除运维 health endpoints 或改变身份模型。
