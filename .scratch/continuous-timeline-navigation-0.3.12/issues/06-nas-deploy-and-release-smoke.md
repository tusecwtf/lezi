# 06 — NAS 部署与发布后联调

**What to build:** 在用户明确批准维护窗口后，通过受保护的 NAS 发布流程交付 0.3.12，在短暂替换服务的同时保留家庭数据、bootstrap secret 和既有 TLS 身份，并用真实更新、安装、同步和时间轴路径完成发布验收。

**Blocked by:** 05 — 执行真实历史版本升级与设备验收；执行前还必须取得用户对本次 NAS CD、stop/rm 和容器替换的明确确认。

**Status:** blocked — ticket 05 joined-client TLS/sync acceptance and fresh CD approval are missing

- [ ] CD 前说明将构建/封装、scp、创建 off-repo age 加密凭证备份并 stop/rm 替换 `lezi-sync`，确认数据 bind 保留、家庭同步会短暂中断以及 age recipients 已配置，再等待用户明确批准
- [ ] 普通 CD 前只读取得 live TLS certificate SHA-256 和 SPKI、镜像/协议/版本、bootstrap secret 来源与数据 bind 状态；任何证书半缺失、不可验证、secret 漂移、备份失败或协议异常都在替换前失败关闭
- [ ] 使用当前 guarded push/deploy 流程发布精确的 0.3.12 包，创建可验证的 off-repo age 凭证备份，保留 data bind、完整 TLS pair 和 bootstrap secret，不直接运行旧包中的 remote helper
- [ ] 部署后证明运行容器的完整 image identity、linux/amd64 平台、health/version 和 package manifest 一致，并验证 HTTPS `/health`、`/ready` 与容器内部 readiness 的实际协议
- [ ] 部署前后 certificate SHA-256 和 SPKI 必须完全相等；不得通过生成、复制、改权限或替换证书使检查通过，TLS bootstrap/rotation 不属于本票
- [ ] 验证认证 app-update metadata/APK 与 LAN `8767` 恢复下载指向同一个 signer/hash 匹配的 0.3.12 APK，并完成安装启动、正常 TLS/SPKI 校验、认证同步和本次时间轴 smoke
- [ ] 最终报告本地门禁、发布包/镜像/APK 身份、工作的 health URL 与协议、证书/SPKI 等值、更新下载、客户端联调结果及仍未通过的任何验收项
