# 09 — 双机前台同步验收（家网）

**Parent:** [../spec.md](../spec.md) · PRD [`sync-home-lan.md`](../../../docs/prd/sync-home-lan.md) §5.4
**Status:** partial

## 已验证基线

- 本机 rootless Docker：镜像、健康检查、单数据根与 HTTP/媒体接口曾验证；~~旧数据兼容~~为 **superseded 历史 receipt**。
- 双模拟器本机路径：A formula 120ml → B 前台可见；B pee → A 前台可见。
- 严格 live 路径（2026-07-25）：两台模拟器均使用
  `http://192.168.50.4:8765`；服务端清空后 A 建家、B 输入邀请码加入；
  formula/pee 双向协议记录通过；头像 owner 写入成功、member 被 403 拒绝。
- 严格 live 汇总：11 pass、0 fail、4 env-blocked；token 未进入仓库。

这些结果不等于物理 NAS 生产部署、双真机或相机扫码已经验收。
它们也不等于 fresh-current 最终候选已通过；旧 NAS/旧库兼容不再验收。

## 剩余 Must

- [ ] 空 Docker data root 直接创建 current schema；health/current API/媒体/原子包通过，写入后同版本容器重启仍持久
- [ ] current ordinary push 与 atomic commit canonical author ack 通过；pre-join 本机 Record 首次上传后 A 不标自己、B 显示 A 当前称呼
- [ ] 负向检查无旧 DB migration、旧 NAS capability/soft-parse、legacy author/device fallback；不得用历史兼容绿灯替代

- [ ] A 建家，B 在同家 Wi‑Fi 上用相机扫描 QR 加入；当前只验证了输入邀请码。
- [x] A 前台记 formula，B 回前台或下拉后可见。
- [x] B 前台记 pee，A 回前台或下拉后可见。
- [ ] 日志图在一端添加，另一端前台同步后在 UI 可见；协议字节链路已有自动化支撑。
- [x] 头像仅 owner 可改；member 写入被拒。
- [ ] 蜂窝网络不上传，回到已绑定家庭 Wi‑Fi 后 Outbox 冲刷。
- [ ] 伴侣新记录不产生系统通知。
- [ ] 深色等本机设置在两端保持独立。
- [ ] 在目标物理 NAS 与双真机环境可用时，以空数据根复跑上述路径并记录版本与结果；该环境缺失不恢复旧 NAS 兼容门禁。

## 复验命令

自动化支撑：

```bash
./gradlew :sync:test :feature:family:testDebugUnitTest
cargo test --manifest-path tools/lezi-sync/Cargo.toml --locked
```

设备复验必须记录 App commit、APK SHA-256、服务端版本、两台设备、SSID 与目标地址；
不得记录 family token、Authorization 或未脱敏的邀请凭证。设备环境无法提供时保持
`partial`，不要用单元测试替代未执行的 Must。

## 明确不验收

- 息屏后台 60 秒自动对齐。
- P2P、公网强制云、伴侣逐条推送。
