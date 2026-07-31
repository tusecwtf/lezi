# 07 — 维护窗实切与本地 APK 联调

**What to build:** 在约定维护窗按 06 执行真实 cutover：拷回升级 data、启动当前 TLS 后端；用本地/调试 APK 指向 `https://<NAS-LAN-IP>:8765`，TOFU 后以**新根密码**完成 owner 设备会话，确认历史权威记录与媒体可见，并新记一条可同步。证据写入本 tracker `evidence/`。

**Blocked by:** 06 — 拷回 NAS 与 TLS cutover runbook

**Status:** ready-for-agent

## Acceptance criteria

- [ ] 维护窗按 runbook 执行；现网 health/ready 为当前版本且 HTTPS（或记录实际探测 URL）
- [ ] 本地 APK（debug 或约定构建）完成 endpoint 信任与 owner 登录
- [ ] 迁移前存在的权威记录（抽样）在客户端可见；若有媒体则抽样可见
- [ ] 新写一条记录可同步回家庭（第二设备可选，非必须）
- [ ] `evidence/07/`（或约定路径）记录版本、时间窗、结果；失败则记录回滚结果

## Out of scope

- 全量双设备矩阵（除非维护窗有余力）
- 把私有脚本产品化为通用升级器
