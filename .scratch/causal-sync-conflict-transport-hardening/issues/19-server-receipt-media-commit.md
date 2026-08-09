# 19 — 实现 server receipt-bound media commit

**What to build:** 让服务端 media commit 只引用 durable receipt 与 canonical metadata，并原子产生 accepted/merged/branched 结果。

**Blocked by:** 17

**Status:** ready-for-agent

## Contract slice

短 transaction 验证 receipt binding/metadata，不在主锁重读大对象。lost response 用相同 mutation/hash/receipt 返回原终态加 replay marker。

## Implementation sequence

1. 冻结 receipt reference 与 media metadata validation。
2. 原子 claim/attach receipt 并执行 commit merge/branch。
3. 持久 commit result/request hash 供 replay。
4. 暴露 consumed/expired/orphan 状态给清理 seam。

## Acceptance

- [ ] accepted/merged/branched 引用精确上传 bytes
- [ ] wrong/expired/foreign receipt fail closed
- [ ] lost response replay 不重复版本或上传
- [ ] branch 保留完整 media identity/bytes

## Validation

- [ ] Store/API receipt/binding/replay tests 通过
- [ ] Rust gates 与 isolated byte-equality smoke 通过

## Out of scope

不实现 Android settlement 或清理 job。
