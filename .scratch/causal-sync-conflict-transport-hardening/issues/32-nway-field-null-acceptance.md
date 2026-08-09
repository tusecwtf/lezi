# 32 — 验收 N 方字段与 null 矩阵

**What to build:** 在主缝上以有限 case table 证明不同字段 merge、同字段 2/3 方 conflict 与 `set(null)` 不受 arrival/enumeration 顺序影响。

**Blocked by:** 31

**Status:** ready-for-agent

## Contract slice

Cases：A/B 不同 leaf；A/B 同 leaf 两值；A/B/C 同 leaf 三值；两 changed heads 同值加一 unchanged；concrete 对 null；ancestor-null 对 descendant edit。每例运行正序与反序，3 方运行三种 cyclic order。

## Implementation sequence

1. 建立上述固定 branch/arrival fixtures。
2. 固定输入 mutation IDs，并比较 semantic digest：归一 server-generated version/branch IDs、receipt/token、接收时间等实例字段，保留 outcome/path/deleted/media、来源对应关系与因果结构。
3. 经 choice-only resolution 选择候选。
4. 全 clients 再 pull 并比较 stable fact/version。

## Acceptance

- [ ] 所列 case/order 的 semantic digest 一致且无静默赢家
- [ ] auto/conflict path disjoint，provenance 完整
- [ ] resolution 后全端收敛

## Validation

- [ ] isolated main-seam matrix 与 property oracle 一致
- [ ] 记录 case IDs/order/HEAD

## Out of scope

不覆盖 delete/restore/ACL 或 transport faults。
