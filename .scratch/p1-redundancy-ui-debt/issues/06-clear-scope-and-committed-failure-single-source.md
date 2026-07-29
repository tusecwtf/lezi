# 06 — 本机清理 scope 与 committed 失败单源

**Parent:** [../spec.md](../spec.md)

**What to build:** 本机「仅记录」与「全部本地数据」清理在 domain / sync / pending 存储之间使用**一层**业务 scope 与**一层**「提交后收尾失败」类型（或明确的 typealias + 单一构造点）。新增 scope 不必改 3–4 处手写 mapper。reminder pending 与 replica pending **分表可保留**，但 scope 枚举语义单源。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

**Size:** M  
**Theme:** D（R9）  
**Seams:** LocalDataClearCoordinator；LocalReplicaClearCoordinator；pending cleanup store

## Acceptance criteria

- [ ] UI/domain 捕获的 committed-clear 失败类型一致（无第二套同形异常类并存生产路径）
- [ ] records-only / all-local 在 domain→sync→pending 的映射有单一适配点；测试覆盖两端
- [ ] 家庭服务器数据保留语义（`familyServerRetained` 等）与现文案策略不回退
- [ ] 清理相关单元测试全绿；删除或合并冗余 mapper 测试后覆盖不降

## Out of scope

- 改清理产品文案策略本身（除非类型合并迫使同名）
- 合并 reminder cleanup 与 replica cleanup 两张表
