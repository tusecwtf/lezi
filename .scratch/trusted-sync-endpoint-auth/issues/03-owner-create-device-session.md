# 03 — 根密码创建家庭和管理员设备会话

**What to build:** 让部署好空服务器的用户通过根密码创建唯一家庭和唯一 Owner membership，并把当前 Android 设备绑定为第一台管理员设备；成功后持久保存设备 refresh session，再独立完成首次家庭数据同步。

**Blocked by:** 02 — 确认自签名证书并固定 SPKI

**Status:** ready-for-agent

- [ ] fresh-current 服务端模型明确分离 Family、Membership、Device 和 DeviceSession；一个 membership 可有多台设备，旧 schema 继续 fail closed 而不编造迁移。
- [ ] setup 状态为 `empty` 时才允许根密码建家；并发第二次创建不能产生第二家庭或第二 Owner membership。
- [ ] 建家要求家庭名、管理员家庭称呼、设备称呼和根密码；设备称呼默认优先使用 Android 设备名并允许提交前编辑。
- [ ] 根密码只验证本次 Owner 操作并换取该设备的 opaque access/refresh session，不作为日常 Bearer token，也不写入数据库会话、QR、日志、SavedState、备份或 analytics。
- [ ] session 先耐久保存再执行首次完整 pull；首次同步失败只重试同步，不重跑建家，并允许用户继续离线。
- [ ] 受保护 API 从服务端 credential 解析 canonical family、membership、device 和 role，拒绝客户端自报 ID 或 role 冒充。
- [ ] Rust Router 与 Android coordinator/UI 测试覆盖幂等建家、错误根密码、秘密清理、进程重建和首次 pull 重试。
