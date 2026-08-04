# 18 — Wire current + capability + setup 探活

**What to build:** 客户端与服务端协商单一 current 与 capability；setup-status 区分空服/已配置；缺能力 fail closed。

**Blocked by:** 02 — 模块命名、库级默认与竖切骨架.

**Status:** ready-for-agent

- [ ] 协议世代/current 与 capability 可探测
- [ ] 缺客户端或服务端所需能力时 fail closed，不静默降级
- [ ] setup-status 能区分 empty 与 configured
- [ ] wire DTO 与领域类型分离的起点已建立
- [ ] 本机 18765；不默认 NAS
