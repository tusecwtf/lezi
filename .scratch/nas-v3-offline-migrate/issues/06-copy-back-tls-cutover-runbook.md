# 06 — 拷回 NAS 与 TLS cutover runbook

**What to build:** 可执行的维护窗 runbook：停现网容器 → 再次确认 NAS 侧备份 → 将本机**已校验**的 out/ **拷回** NAS 数据路径 → 按当前 CD 启 TLS 镜像 → 健康检查清单 → 失败时用拷出备份恢复 v3 服务。家人通知：HTTPS endpoint、TOFU、新根密码、全员重登。

**Blocked by:** 05 — 拷出、dry-run、校验 CLI

**Status:** ready-for-agent

## Acceptance criteria

- [ ] Runbook 步骤顺序固定：停服 → NAS/本地双备份确认 → 拷回升级 data → 启当前 TLS 部署 → health/ready（按实际协议探测）
- [ ] 写明默认路径：NAS data bind、SSH、LAN `https://<NAS>:8765`、回滚命令要点
- [ ] 回滚：恢复拷出的 v3 data + 旧镜像/旧启动方式，服务回到维护前可用态
- [ ] 清单含：根密码已轮换告知、旧 APK/旧 HTTP 不可用说明、成员重登路径
- [ ] 不在未执行 07 前声称现网已切成功

## Out of scope

- 实际点维护窗执行（见 07）
- 修改通用产品 ADR 为自动迁移
