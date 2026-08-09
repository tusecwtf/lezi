# 37 — 验收媒体来源与 spool 故障

**What to build:** 证明来源只读一次，URI 消失/失权、copy 中断、容量不足和 Android 进程死亡后 immutable spool 仍保留精确可重试 bytes。

**Blocked by:** 18、31

**Status:** ready-for-agent

## Contract slice

Cases：source changes after freeze、URI disappears、permission revoked、partial temp、post-promote/pre-Room crash、pending restart、capacity pressure。

## Implementation sequence

1. 用可计数/可变 source 冻结并记录 digest。
2. 在 copy/promote/Room commit 注入 faults。
3. 重启并恢复 referenced manifests/清理 orphan temp。
4. 从 spool 重传并比较 bytes。

## Acceptance

- [ ] source consume count=1，retry digest 不变
- [ ] partial/orphan 不成为可发表 mutation
- [ ] pending/branched 不被时间/容量清除
- [ ] 容量不足只阻止新发表

## Validation

- [ ] Android instrumented storage fault matrix 通过
- [ ] 记录 source count/digest/cleanup evidence

## Out of scope

不覆盖 server receipt/commit disconnect。
