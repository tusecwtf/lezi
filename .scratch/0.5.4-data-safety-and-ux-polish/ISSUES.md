# 0.5.4-data-safety-and-ux-polish · Issues

| # | 标题 | Blocked by | Status |
|---|---|---|---|
| 01 | dismiss 耐久化：台账键 + 应用门 + 普查投影 + 收件箱投影 + 文案（S1） | — | done |
| 02 | 宝宝合并前废弃冻结信封（S2） | — | done |
| 03 | 退出设备「还有 N 条未同步」披露 + 被移除清理收据（S3） | — | done |
| 04 | 强更 APK 流式落盘（S4） | — | done |
| 05 | 落库范围校验下沉（S5） | — | done |
| 06 | 测试门禁修复：FreshDatabaseTest 跟随 VERSION + 三处 JVM 失败还清（S6） | — | done |
| 07 | 文档修正三处：server.md 退役标注 / data-model 决胜规则 / wire §16 注记（S7） | — | done |
| 08 | 冲突收件箱相位建模：首帧不闪假空态、去「待加载」工程词、错误可重试、顶部心智解释（T1） | 01 | done |
| 09 | 汇总空周期空态卡（T2） | — | done |
| 10 | 导出错误按因归因、0 记录空态、生成进度与成功/取消终态（T3） | — | done |
| 11 | RecordRow 异常「!」进语义树（T4） | — | done |
| 12 | 计时器通知：暂停/结束 action、chronometer 走秒、自有单色图标、状态词（T5） | — | done |
| 13 | surfaceContainer 系七槽入四套 scheme + 契约测试 + 死 token 清理（M1） | — | done |
| 14 | 收编 motion-polish 票 02/03/04：LeziEasing、游离时长归一、push 过渡升档（M2） | — | done |
| 15 | 触觉工具 + 四个情绪点接入（M3） | — | done |
| 16 | Typography 补三槽 + 计时器数字 Metric 档（M4） | — | done |
| 17 | Glance 主题接 LeziColors（M5） | — | done |
| 18 | 0.5.4 发版：版本身份 0.5.4/34、compat 账本一行、UX 文档漂移两处顺带修（M6：`architecture.md:143`、`product.md:336`）、两台真机冒烟覆盖两流验证点 | 01–17 | done（真机冒烟待人工执行，见 SMOKE-CHECKLIST.md） |
| 19 | 交互与同步审查修复：家庭身份阻断、拉取墓碑与媒体删除、多图一次发布、睡眠醒来观察、汇总下界、时间轴午夜与手势、搜索跟随库变化。协议 0.4.0 不动，crate 仍 0.5.3，不执行 NAS CD | — | done（本机模块测试与模拟器走查；隔离双端与完整 Clippy 未跑；NAS 不替换） |
| 20 | 0.5.4 协同维护窗：Cargo 抬 0.5.4 + 渠道刷 0.5.4/34 + 0.5.4 签名 APK + 0.5.4 打包身份三件套（package-nas / validate-nas-package / test-nas-release-identity），NAS 普通 CD 上线树内全部未发版服务端修复 | 18, 19 | done（2026-10-01 CD 落地，恢复路径；记录见下） |

## 20 落地记录（2026-10-01 协同 CD）

- 版本协同：crate `0.5.4`（Cargo.toml/lock）；`app-update.json` → 0.5.4/34，
  APK sha256 `9a8c319c4e8d25e15813ffd4db75b7edb7028e4e17a0ba9adf2c168e2824ffb2`，
  签名者摘要与 `config/release-apk-signer-sha256.txt` 一致；0.5.4 身份钉
  schema 13 / floor 21 / 回滚源 0.5.3+schema13。
- 门禁：Rust 三件套（fmt / test --locked 362+204+1+5 / clippy -D warnings）；
  `test-package-nas-app-update.sh`、`test-nas-release-identity.sh`（0.5.4/0.5.3/0.5.2
  三代）；隔离双端（树内 0.5.4 二进制，domain 侧 `--rerun-tasks` 强制重跑）。
- CD（普通路径起，容器病死后转 `LEZI_ALLOW_SECRET_RECOVERY=1` 恢复路径）：
  镜像 `lezi-sync:0.5.4` linux/amd64（tar 内二进制 sha256 `162ee4f2…` 与本地
  构建比特一致）；secret 从字节校验持久文件恢复；pre/post 证书 SHA-256
  `75023c71…` 与 SPKI `bd07d864…` 相等（线上出示 DER 指纹 `9a34ba5b…` 即同一张
  家族证书）；health/ready `0.5.4` / schema 13；LAN 装机渠道验 APK `9a8c319c…`；
  包 promote 至 `/tmp/lezi-sync-releases/lezi-sync-0.5.4-nas`；age 备份两份
  （20260930T161141Z 预替换 + 20261001T020425Z 恢复后）。
- 事故与处置（Zspace containerd 不稳定，一周两次崩溃 09-25 / 10-01 08:10）：
  docker 组成员资格被系统抹掉（操作者 usermod 修复）；containerd 死后 runc
  task 残留（`mkdir file exists` → 清 `/run/containerd/...moby`）与 runc 状态
  残留（`container with id exists` → 清 `/run/docker/runtime-runc/moby/<id>`）；
  旧容器进程孤儿化并与新容器抢占网桥 IP（杀孤儿进程）；daemon 陈旧 running
  记录 + 幽灵网络端点（rm 容器 + disconnect -f + 删 `lezi_default` 重建）。
  根因待操作者查 journalctl；未解决前每次 CD/证书探测前先探 `docker ps`/exec。
