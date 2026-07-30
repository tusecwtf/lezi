# 04 — 轮换设备会话并安全处理凭证丢失

**What to build:** 让有效设备用短 access 和长期 rotating refresh 静默保持登录；复制或重放某台设备的 refresh 时只隔离该设备。普通凭证失效时保留所有本地家庭数据并引导重新申请，而不是误判为被删除。

**Blocked by:** 03 — 根密码创建家庭和管理员设备会话

**Status:** ready-for-agent

- [ ] access token 目标有效期为 15 分钟且只在内存持有；refresh 每次成功使用都轮换，没有时间或 inactivity 自动过期。
- [ ] refresh rotation lineage 检测旧 token 重放；replay 只撤销该 Device，不撤销同 membership 其它设备、普通成员或整个家庭。
- [ ] membership ID、device ID、家庭称呼和设备称呼都不能换取 token；受保护 API 不信任请求体自报身份。
- [ ] access 到期自动 refresh 并只重试原请求一次；invalid、missing 或 replay 后清该设备凭证，但保留 Room、Outbox、media 与可信 endpoint。
- [ ] UI 区分 `ReauthRequired` 与明确删除，普通凭证问题显示重新登录/申请路径，不显示「设备已删除」。
- [ ] refresh token 使用 Android 安全存储且不进入系统备份；日志捕获测试证明 access、refresh 和 Authorization 均未输出。
- [ ] 服务端并发/重放黑盒测试和 Android 进程恢复测试覆盖轮换、单设备隔离及本地保留合同。
