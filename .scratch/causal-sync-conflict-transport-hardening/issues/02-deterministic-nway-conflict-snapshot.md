# 02 — 生成确定性 N 方 ConflictSnapshot

**What to build:** 让服务端对完整 open branch set 一次生成与枚举顺序无关的无损 ConflictSnapshot，不再由 pairwise 顺序产生隐式赢家。

**Blocked by:** 01；[`external 17`](../../repository-dedup-algorithm-audit-20260809/issues/17-bounded-conflict-head-loader.md)、[`external 18`](../../repository-dedup-algorithm-audit-20260809/issues/18-conflict-snapshot-receipt-pagination.md)

**Status:** ready-for-agent

## Contract slice

Stable、每个 branch、root/media/deleted、version/base、provenance 都必须完整；conflict 与 auto-merged 路径不相交；snapshot candidate 暴露 opaque choice ID、typed outcome 与 source/provenance，只有 resolution input 只接受 choice ID。

## Implementation sequence

1. 通过有界批量读取 seam 装载完整头集合与因果 base。
2. 将各头 diff 归一为不重叠 typed outcomes。
3. 对所有 changed heads 计算 distinct outcome 集并分类 auto/conflict。
4. 以 canonical 顺序生成 semantic snapshot/candidate descriptors，并由 receipt seam 持久化 opaque choice IDs。

## Acceptance

- [ ] 2/3 方、到达/枚举 permutation 在排除随机 receipt/token 后产生字节等价 canonical payload
- [ ] `set(null)`、媒体、deletion 与 ancestry 都使用同一分类器
- [ ] 不完整/不可比较历史保守冲突或 fail closed
- [ ] 无 UUID、时间、作者或 Owner 隐式赢家

## Validation

- [ ] Rust table/property tests 覆盖所有 permutation
- [ ] Store/API snapshot corpus 通过；资源 query/page budget 只做一条 seam 接线回归

## Out of scope

不提交 resolution，不实现 Android persistence。
