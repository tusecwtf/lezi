# 01 — 删除无契约 StructureTest

**What to build:** 维护者跑测试与重构时，不再被七个只检查源码字符串/行数/符号落点的 StructureTest 挡住；护理记录、布局编辑、Composer 等行为合同仍由既有行为测试保证。

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] 七个 StructureTest 全部删除，且未改写成新的结构守卫测试
- [ ] 仓库内无残留 StructureTest 引用
- [ ] CareLog / layout / composer 等既有行为测试仍在且通过相关模块测试
