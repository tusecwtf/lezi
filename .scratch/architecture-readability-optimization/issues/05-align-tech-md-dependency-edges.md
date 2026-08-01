# 05 — tech.md 依赖边与 Gradle 对齐

**What to build:** 读技术 PRD 的人看到的模块→sync 有意边与真实 Gradle 一致（含 onboarding、settings、summary、growth 的合法用途说明），且不再把不存在的 core:image 写成已交付模块。与全库审计票 17 once-only。

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] tech.md 列出全部实际依赖 sync 的 feature，并简述用途
- [ ] 文档中无幽灵 core:image 已交付表述
- [ ] 未静默删改 Gradle 边（本票以文档对齐真图为主）
- [ ] 与审计 tracker 17 协调为只做一次
