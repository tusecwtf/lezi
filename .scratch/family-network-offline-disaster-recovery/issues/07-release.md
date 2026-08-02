# 07 — 0.3.3 发版与验收

**Status:** in-progress — deployed; awaiting joined-device recovery smoke

## Acceptance criteria

- [x] Android 0.3.3 code 10；server 0.3.3；Room 24、本地契约 v1、server schema 不变。
- [x] Android unit/lint/debug/release、串行 device gates 与 Rust fmt/test/clippy 通过或明确设备阻断。
- [x] 0.3.2→0.3.3 原地升级 quick_check=ok 且数据保留。
- [x] 隔离空服务完成灾难恢复联调；现有家庭 NAS 不执行恢复测试。
- [x] 签名 APK 与 app-update metadata/hash 一致，`dist/release-0.3.3/` 完整。
- [x] Rust gates 后仅提议 NAS CD；用户确认前不执行容器 replace。
- [x] 经用户确认后部署 NAS 0.3.3；HTTPS health/ready、configured family、数据 bind、
  TLS 身份和自托管 APK 均通过部署后核验。
- [ ] 在一台已有家庭会话的真实设备完成账户网络设置、离线写入、Health 恢复自动补传与
  第二设备收敛 smoke。

## Local acceptance evidence

- Android：`test`、独立串行 `lintDebug`、Debug/Release 构建通过；最终固定点
  `connectedDebugAndroidTest` 在只读 API 35 AVD 上 3m09s 通过。
- 升级：真实签名 0.3.2 原地安装最终 0.3.3，Room `user_version=24`、
  `quick_check=ok`、业务哨兵保留，MainActivity 为 RESUMED 且无 AndroidRuntime fatal。
- Server：Rust fmt、138 unit、125 API、1 TLS、Clippy `-D warnings` 通过；当前
  `user_version=11` 未变。
- 隔离联调：真实本机 HTTPS 0.3.3 空服务完成 start/manifest/media/status/commit/pull，
  setup `empty → configured`、3 实体一次可见、历史作者归新 Owner、媒体 SHA-256 匹配、
  commit 重试幂等；临时服务和数据根已删除。

## NAS CD evidence

- 2026-08-02 部署前实际服务已是 HTTPS 8765 / 0.3.2；本轮没有发生明文协议切换。
- 构建并打包 `linux/amd64` `lezi-sync:0.3.3`；镜像 manifest ID
  `sha256:8765d57f13b87f80265e3b701d9e8528da718e165694353a0846f36a5f2df9e9`，
  tar SHA-256 `8b3e5ec85d87b01422c4cff8a9009c2a214fdbda65251f9da887136d08ab20a9`，
  包内校验全部通过。
- 上传到 `/tmp/lezi-sync-releases/lezi-sync-0.3.3-nas`，通过 zdocker Compose v2.33.0
  stop/remove 并替换 `lezi-sync`；根密钥从 live 容器继承且未打印，数据 bind 保留为
  `/tmp/zfsv3/sata1/13096920600/data/Docker/lezi/data`。
- LAN `https://192.168.50.4:8765/health`、`/ready` 返回 0.3.3；setup-status 为
  `configured`，容器为 healthy，内部 `lezi-sync healthcheck` 通过。
- 新持久 TLS 身份 SAN 包含 `192.168.50.4`；SPKI 指纹
  `C7:8F:A0:1A:FD:DE:C7:8D:78:1E:CB:37:EB:CB:A4:2F:FF:DF:97:A4:EB:1F:5A:FB:F2:00:EF:58:DC:7F:75:75`。
  NAS `/data/app-release.apk` SHA-256 与 release 元数据
  `95887649f251a191d6e38f06a6413e9a2ca9ba8bc4bb60131751fa7e53b2bce2` 一致。
- 只读 API 35 AVD 安装最终 Release APK，冷启动正常；候选地址探测识别 configured 家庭，
  UI 展示的 TOFU 指纹与 NAS 一致。离线模式经 Composer 写入尿尿记录，杀进程重启后
  Room 首屏仍显示该记录与 `1次` 汇总。
- AVD 没有已加入家庭会话；验收未读取根密码、旧 token 或创建成员申请。因此账户网络设置
  与 Health 恢复自动补传仍需一台真实已加入设备完成，现有家庭 NAS 未执行灾难恢复。
