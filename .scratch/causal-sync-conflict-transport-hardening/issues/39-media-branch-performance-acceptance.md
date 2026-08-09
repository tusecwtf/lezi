# 39 — 验收媒体分支、慢上传与清理

**What to build:** 证明 media add merge、delete/edit branch、choice resolution、slow upload 并发和终态 cleanup 保持精确 bytes 且不阻塞家庭小提交。

**Blocked by:** 21、22、23、24、31、37、38

**Status:** ready-for-agent

## Contract slice

Cases：独立 add/add、同 media delete/edit、选择各 branch、slow large upload + concurrent small commit、resolved/abandoned cleanup。

## Implementation sequence

1. 构造 Record/Baby/CarePlan media branches 并记录 digest。
2. 经 choice-only resolver 选择各类 media candidate。
3. 慢传大对象同时提交一个小无媒体 Record。
4. 核对 client spool/server receipt/conflict metadata cleanup。

## Acceptance

- [ ] selected bytes 与原 digest 完全相同
- [ ] delete/edit 不静默丢媒体
- [ ] 小 commit 在慢上传期间可前进
- [ ] terminal cleanup 不删 pending/branched evidence

## Validation

- [ ] isolated media branch/performance matrix 通过
- [ ] 记录 timing/digest/cleanup receipts

## Out of scope

不运行家庭 NAS 或真实家庭照片。
