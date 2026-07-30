# 02 — 确认自签名证书并固定 SPKI

**What to build:** 让家庭 NAS 以稳定的自签名 HTTPS endpoint 对外服务；首次连接者看见指纹并明确点击「信任此证书」后固定 SPKI，不需要额外校验码。以后证书公钥变化时停止连接且不发送任何凭证。

**Blocked by:** 01 — 通过 HTTPS 探测家庭服务器

**Status:** ready-for-agent

- [ ] NAS 交付路径提供持久自签名 HTTPS endpoint，容器替换和普通重启保持同一 SPKI；私钥不进入镜像、Git、日志或发布 manifest。
- [ ] 首次连接在角色选择、setup probe 和发送根密码之前展示完整指纹、风险说明及「信任此证书」；当前连接者确认后立即固定 SPKI。
- [ ] 不要求校验码或配对码，也不把管理员登录改成 QR 登录；TOFU 首连风险在产品文案中如实表达。
- [ ] 已固定 SPKI 不一致时硬阻断，没有「忽略并继续」；用户只能忘记服务器后重新连接和确认。
- [ ] 证书拒绝、页面退出和进程重建不会留下半可信 profile；根密码、token、grant 和家庭数据发送次数为零。
- [ ] Android trust 测试、TLS 黑盒测试及 NAS 包装/重启 smoke 证明首次固定、持久性和 mismatch 负向路径。
