# 17 — 签发流式媒体 preimage receipt

**What to build:** 服务端流式接收媒体，在 family/SQLite 写关键区外计算 hash/length，并以短事务签发 durable receipt。

**Blocked by:** 14、15

**Status:** ready-for-agent

## Contract slice

Receipt 绑定 family/principal/digest/length/TTL。此票负责 prepare 幂等、expiry 状态标记和一个可调用清理 seam；不负责 conflict metadata 或 Android spool GC。

## Implementation sequence

1. 冻结 upload limits、binding、TTL 与 receipt 状态。
2. 流式写 server-owned temp，避免整对象驻内存。
3. 在主写关键区外 hash/verify，再短事务持久 receipt。
4. 支持 lost prepare/duplicate，接入票 15 的 media-prepare retry budget，并暴露 expired/orphan cleanup seam。

## Acceptance

- [ ] 大 IO/hash 不持有 family lock/SQLite write transaction
- [ ] lost/duplicate prepare 返回同一可用语义
- [ ] wrong digest/length/family/principal fail closed
- [ ] 慢上传不阻塞并发小 commit

## Validation

- [ ] streaming/memory/lock/replay tests 通过
- [ ] Rust gates 与 isolated slow-upload smoke 通过

## Out of scope

不执行最终 media commit/GC policy。
