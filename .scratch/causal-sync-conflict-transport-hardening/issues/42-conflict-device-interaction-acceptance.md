# 42 — 验收冲突设备交互

**What to build:** 在设备/模拟器上验收家庭 badge/inbox、五类根、Record 上下文、分页、offline/freshness、ACL 与可访问提交状态。

**Blocked by:** 07、09、41

**Status:** ready-for-agent

## Contract slice

这里的“旋转”仅指 Android configuration change/屏幕旋转，不是 TLS certificate rotation。UI 使用隔离服务/fixture，不连接家庭 NAS。

## Implementation sequence

1. 安装当前 debug/release candidate 并注入五类根/tombstone conflicts。
2. 遍历家庭 badge/inbox 与 Record contextual route。
3. 验证分页、offline、stale refresh、configuration change。
4. 验证 author/Owner/other ACL、labels、focus 与 disabled submit。

## Acceptance

- [ ] badge/count/list/detail/choice 流程可操作
- [ ] provenance/media/deleted/freshness/error 文案可访问
- [ ] offline/incomplete/stale/unauthorized 不可提交
- [ ] configuration change/process recreation 不丢合法状态

## Validation

- [ ] Compose connected/device tests 与截图/交互 receipts 完整
- [ ] 记录设备/API level/APK hash；未运行物理双端则明确

## Out of scope

不做视觉重设计、TLS rotation 或生产 smoke。
