# 01 — 成员申请本地放弃与重复加入

Status: complete

## Acceptance criteria

- [x] 已有本机宝宝/护理数据不阻断发送成员申请。
- [x] 发送申请超时或失败后退出 busy，文案可处理且可重试。
- [x] 等待期 NAS 断网时仍可放弃本机申请；本地宝宝、记录、endpoint 与家庭外的
  离线使用能力保留。
- [x] 本机放弃后可立即重走加入流程，旧 0.3.3 server pending 仅按既有 TTL 过期。
- [x] 0.3.3 已完整持久的 pending request + secure secret 升级后继续恢复等待、查询、
  放弃和领取；不清理兼容输入来「修复」卡死。
- [x] 回归固定为既有 0.3.3 server wire，不新增 endpoint、request 字段、capability 或
  server schema。
