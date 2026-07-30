# 01 — 通过 HTTPS 探测家庭服务器

**What to build:** 让尚未登录家庭的用户输入一个 System-PKI HTTPS 地址，安全确认它是不是兼容的乐记家庭服务器，再由服务器的真实状态决定进入「新建家庭」或「加入家庭」。连接失败、取消或暂不连接时，用户仍可保持离线记录，错误地址不会覆盖已有可信状态。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

- [ ] 生产连接只接受通过系统主机名、有效期和证书链校验的 HTTPS；显式 HTTP、TLS 错误和重定向到不可信 origin 均不得继续或发送秘密。
- [ ] 可信连接后的最小 setup probe 只返回协议、capabilities 和 `empty|configured`，不泄露家庭名、ID、成员、设备或其它家庭信息；liveness 与 readiness 保持独立职责。
- [ ] 「连接家庭服务器」根据 probe 结果只显示对应的「新建家庭」或「加入家庭」，并分别处理不可达、非 Lezi、版本不兼容和维护中。
- [ ] 任一状态都可「暂不连接，保持离线」；失败或取消不清 Room、Outbox、media、已有可信 endpoint 或有效 session。
- [ ] 未验证地址仅作为草稿；通过 trust + probe 的未登录 endpoint 可继续或忘记，但不能同步或读取家庭数据。
- [ ] Rust Router 黑盒测试和 Android 共享状态机测试证明无元数据泄漏、状态路由、草稿保护及 no-secret-before-trust。
