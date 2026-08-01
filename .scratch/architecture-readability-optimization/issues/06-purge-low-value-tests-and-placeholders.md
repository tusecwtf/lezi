# 06 — 清低价值测试与 UiPlaceholders

**What to build:** 测试列表与 core UI 源码中不再出现「测 fake 自身」、反射/字符串改名守卫、以及无调用方的占位常量 API；审查只碰到守合同的测试与真实 UI 常量。

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] FakeSyncBackendTest 删除或改写为有 SyncBackend 合同价值的断言
- [ ] RecordSettingsMenuTest 去掉反射/源码 rename-detector；保留真菜单行为合同（若有）
- [ ] UiPlaceholders 无调用则删除，有调用则内联到唯一使用点并去掉噪音 API
- [ ] 无残留坏引用；相关模块测试通过
