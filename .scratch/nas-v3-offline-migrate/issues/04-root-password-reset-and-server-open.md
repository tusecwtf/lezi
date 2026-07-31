# 04 — 根密码重置与当前服务可启动

**What to build:** 迁移时由运维**设定新根密码**（不继承旧 membership 凭证）。产出的 data 在配置 TLS 的当前 lezi-sync 下能 `/ready`，家庭为 configured；owner 可用新根密码完成首台设备登录（测试或本地容器），且旧 token 一律无效。

**Blocked by:** 02 — 本机离线库迁移器 v3→当前

**Status:** ready-for-agent

## Acceptance criteria

- [ ] 迁移 CLI/工具接受运维提供的新根密码（或等价 bootstrap/root 输入），写入当前身份模型所需存储
- [ ] 旧 membership_credentials / invite 不出现在可用会话路径
- [ ] 当前二进制对产出 data：预检通过，ready 成功，setup-status（或等价）显示家庭已配置
- [ ] 集成/夹具：owner 使用**新**根密码可建立 device session；使用任意旧凭证失败
- [ ] 文档片段说明：家人须用新根密码/成员流程重登，无静默恢复

## Out of scope

- 真 NAS cutover（见 06/07）
- 成员审批 UI 改动
