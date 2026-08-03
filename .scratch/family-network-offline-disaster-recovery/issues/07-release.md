# 07 — 0.3.3 发版与验收

**Status:** in-progress — live advanced to 0.3.5; awaiting joined-device recovery smoke

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
  `sha256:1a2c57dfc58655028aa3017e51e463f9c2384807da68339b540d36ec85fc95b9`，
  tar SHA-256 `37a8c0577c3e72e749ce36dc72fae749c8b49933044f0c6cc485732e7f7fde7d`，
  包固定提交 `da64fd84` 且包内校验全部通过。
- 上传到 `/tmp/lezi-sync-releases/lezi-sync-0.3.3-nas`，通过 zdocker Compose v2.33.0
  stop/remove 并替换 `lezi-sync`；根密钥从 live 容器继承且未打印，数据 bind 保留为
  `/tmp/zfsv3/sata1/13096920600/data/Docker/lezi/data`。
- LAN `https://192.168.50.4:8765/health` 返回 0.3.3，`/ready` 返回
  `{"ok":true,"status":"ready","version":"0.3.3"}`；setup-status 为 `configured`，
  容器为 healthy，内部 `lezi-sync healthcheck` 通过。
- readiness 联调发现服务端成功响应缺少 Android 已约定的 `status=ready`；`db57ba66` 以
  public-seam 回归修复。首次 hotfix CD 又暴露 mode-700 数据 bind 下宿主 `-f` 误判不存在、
  进而轮换 TLS 身份的部署缺陷；旧 C7 私钥未留在数据 bind 中，不能由手机保存的 SPKI 指纹
  还原。`da64fd84` 改为从 uid 10001 helper 容器检查证书/私钥，覆盖完整身份复用与部分身份
  fail-closed。随后重复 CD 明确输出 `reusing persistent TLS identity`，部署前后 SPKI 均为
  `BD:07:D8:64:5E:D3:B7:AD:EA:D1:62:EC:A4:54:37:3A:EE:40:07:B0:A3:5A:A7:C6:2C:AF:7D:8A:C0:CB:32:15`；
  SAN 仍包含 `192.168.50.4`。
- NAS `/data/app-release.apk` SHA-256 与 release 元数据
  `95887649f251a191d6e38f06a6413e9a2ca9ba8bc4bb60131751fa7e53b2bce2` 一致。
- 只读 API 35 AVD 安装最终 Release APK，冷启动正常；候选地址探测识别 configured 家庭，
  UI 展示的 TOFU 指纹与 NAS 一致。离线模式经 Composer 写入尿尿记录，杀进程重启后
  Room 首屏仍显示该记录与 `1次` 汇总。
- AVD 没有已加入家庭会话；验收未读取根密码、旧 token 或创建成员申请。因此账户网络设置
  与 Health 恢复自动补传仍需一台真实已加入设备完成，现有家庭 NAS 未执行灾难恢复。

## 0.3.5 follow-up CD

- 2026-08-03 经再次确认，live server 从 0.3.3 普通升级至 0.3.5。构建、强制打包、
  TLS/secret/data-bind 不变量、HTTPS health/ready、8767 邀请页与匿名 APK 下载证据记录在
  [hang-fidelity tracker](../../family-sync-hang-and-account-fidelity/ISSUES.md#nas-cd-evidence-035--2026-08-03)。
- API 35 AVD 的 signed 0.3.5 冷启动与本地数据保留通过，但仍没有 joined family session；
  因此本票唯一未勾的账户网络设置、离线写入、自动补传和第二设备收敛 smoke 没有被误关。
