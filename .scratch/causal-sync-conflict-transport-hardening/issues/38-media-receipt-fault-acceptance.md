# 38 — 验收媒体 prepare 与 commit 故障

**What to build:** 证明 prepare/commit disconnect、receipt replay/expiry/binding 错误和 Android settlement 在真实隔离服务上不重传或重复版本。

**Blocked by:** 19、20、31

**Status:** ready-for-agent

## Contract slice

Cases：lost prepare response、lost commit response、duplicate prepare、expired receipt、wrong family/principal/digest/length、restart before settlement。

## Implementation sequence

1. 在 prepare durable 前后注入 disconnect。
2. 在 commit durable 前后注入 disconnect。
3. 运行 binding/expiry/replay cases。
4. 重启 Android 并比较 upload count/version/spool state。

## Acceptance

- [ ] lost response 不 re-read URI、不 duplicate upload/version
- [ ] wrong/expired binding fail closed 且 pending 诚实
- [ ] terminal 后 cleanup，unknown/pending 保留

## Validation

- [ ] isolated receipt fault case table 通过
- [ ] 记录 upload count/hash/version/manifest evidence

## Out of scope

不覆盖 media branch resolution 或 slow upload。
