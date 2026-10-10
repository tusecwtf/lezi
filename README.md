# 乐记（lezi）

家庭育儿日志 · Android · Kotlin + Jetpack Compose
包名：`com.lezi.babylog` · minSdk 26 · targetSdk 35 · 显示名「乐记」 · version 以 `app/build.gradle.kts` + `config/android-release-compatibility.json` 为准

产品规格：[`docs/spec/`](docs/spec/) · 领域术语：[`CONTEXT.md`](CONTEXT.md) · 文档索引：[`docs/README.md`](docs/README.md) · 安全：[`SECURITY.md`](SECURITY.md)
许可：[MIT](LICENSE) · 贡献 / PR：[`CONTRIBUTING.md`](CONTRIBUTING.md)

## 环境

- **JDK 21**（不要用 JDK 25+：AGP 会失败）。可选在 `~/.gradle/gradle.properties` 设置
  `org.gradle.java.home=/path/to/jdk-21`；仓库内 `gradle.properties` **不**钉死本机路径
- Android SDK：本地创建 `local.properties`，写入 `sdk.dir=/path/to/Android/Sdk`（已 gitignore）
- 签名：`keystore.properties` + `*.jks` 仅本机（已 gitignore，见 `SECURITY.md`）
- 可选模拟器 AVD：`lezi_api35`（`lezi-emu start --window`）

## Git remotes

| Remote | 用途 |
|--------|------|
| `origin` | 局域网 Gitea（日常备份/内网） |
| `github` | GitHub `tusecwtf/lezi`（private 镜像） |

推送时显式指定 remote，例如 `git push github master` / `git push origin master`。
`master` 的 upstream 以本机 `git branch -vv` 为准。

## 构建

```bash
./gradlew :app:assembleDebug
# APK → app/build/outputs/apk/debug/app-debug.apk
# debug applicationId = com.lezi.babylog.debug
```

安装并启动：

```bash
./gradlew :app:installDebug
adb shell am start -n com.lezi.babylog.debug/com.lezi.babylog.MainActivity
```

单测：

```bash
./gradlew test
```

CI（GitHub Actions）：push/PR 上跑 unit test；`tools/lezi-sync` 变更另跑 rustfmt、cargo test、
clippy，以及隔离的 NAS 发布/凭据回归。

## 同步服务（V2）

规格（可信 endpoint、身份、前台策略与 API）：[`docs/spec/contracts/sync-trusted-endpoint.md`](docs/spec/contracts/sync-trusted-endpoint.md)

交付实现：Android `:sync` / `:feature:family` +
[`tools/lezi-sync`](tools/lezi-sync/)（Rust、Axum、Tokio、SQLite、Docker
单卷）。客户端不内置服务器地址；须在账户页填写可信 HTTPS 地址，或扫描管理员 App
生成的普通成员单次登录 QR。

### 本机开发与测试

```bash
cd tools/lezi-sync
cargo test --locked
cargo clippy --all-targets --all-features -- -D warnings
# 可选：本机起 API（非生产）
# LEZI_DATA_DIR=/tmp/lezi-sync-data cargo run --release
```

### 服务端部署范围

VPS 部署工具链已按 owner 决定删除（2026-09-06，先于 2026-09-05 暂停）；脚本只存于
git 历史，不作为执行指引。现网家庭仍在 NAS。以下 NAS 回滚和维护操作必须另行确认窗口；修复操作者权限不等于授权替换容器。

### 服务端发版（NAS 冻结回滚）

**权威步骤与脚本**：[`tools/lezi-sync/deploy/DEPLOY.md`](tools/lezi-sync/deploy/DEPLOY.md)

NAS 脚本家族的真实目标保存在未跟踪的 `tools/lezi-sync/deploy/env.local`（`NAS_SSH` 等，
合成示例 `nas-operator@192.168.77.10:10000`），只作回滚和只读
copy-out。不能靠改 `NAS_SSH` 把同一套脚本切到 VPS。

| 项 | 选择 |
|---|---|
| 构建位置 | 开发机（`linux/amd64` 镜像），**不在 NAS 上 cargo/docker build** |
| 编排引擎 | 极空间 **zdocker** 自带 Compose v2（`/zspace/.../zdocker/bin/docker-compose`）；不要求系统安装 `docker compose` |
| 发布触发 | 本机一键：`package` → `scp` → SSH `remote-deploy` |
| Bootstrap | 普通 CD 以现网容器为权威，并与 NAS 持久 `config/lezi-sync.env` 逐字节核对；secret **不进 git** |
| 数据卷 | 宿主 bind（默认路径见下），stop/rm 容器不删数据 |
| TLS 身份 | 普通 CD/回滚/重启必须复用数据卷内原证书与私钥；仅经确认的全新空数据根可首次生成 |
| 凭据灾备 | 替换前将根密码 + TLS pair 直接流式写入开发机仓库外的 `age` 密文；失败则普通 CD 不停容器 |
| 并发/包完整性 | 单一、按数据 bind 固定的 NAS lease 覆盖全新 700 staging→备份→替换→稳定路径提升；包必须证明 linux/amd64、完整 image id、乐记 APK 公共签名证书、精确 inventory/SHA |

```bash
cd tools/lezi-sync

# 0) 一次性准备 age 公钥收件人；私钥保存在密码管理器/离线介质
# 默认读取 ~/.config/lezi/age-recipients.txt

# 1) 质量门
cargo fmt --all -- --check
cargo test --locked
cargo clippy --all-targets --all-features -- -D warnings

# 2) 构建镜像（版本默认读 Cargo.toml）
./build-image.sh
# → lezi-sync:<version>

# 3) 打包 + 推 NAS + 替换现网容器
./deploy/push-and-deploy.sh
# 产出：dist/lezi-sync-<version>-nas/（gitignored）
# 远端成功后稳定路径：/tmp/lezi-sync-releases/lezi-sync-<version>-nas
# 传输先进入全新的 .incoming-<nonce> 目录，校验/部署成功后才提升
# （Zspace SSH 用户 HOME 常为 /home/ 不可写，故不用 ~）

# 4) 验收（本机或 NAS）
curl --cacert /path/to/data/tls/server.crt -fsS https://192.168.77.10:8765/health
curl --cacert /path/to/data/tls/server.crt -fsS https://192.168.77.10:8765/ready
```

**现网证书是发布硬边界：**普通 CD 不承担证书轮换，不得覆盖、删除、改名或重新生成已有
`/data/tls/server.crt` 与 `/data/tls/server.key`。部署前后必须只读计算 SPKI 并要求完全一致；
证书缺半、无效/过期、私钥不匹配、容器内不可读或指纹变化都应停止发布，而不是自动修复。
只有已单独确认的全新空数据根、且两个文件都不存在时，才允许首次生成 TLS 身份。
生产 `.crt` 即使可公开读取，也不作为源码配置提交：它与私钥共同定义这台 NAS 的固定身份，
精确文件对进入仓库外 `age` 灾备，Git 中只记录独立核验过的证书 SHA-256 / SPKI 公共指纹。

证书生成、替换、过期、错配、TOFU 变化和证书重连测试禁止使用真实家庭 NAS、现网容器或其
数据 bind；必须在开发者自行部署的隔离 `lezi-sync` 服务上进行，使用 `mktemp` 临时数据根和
非生产端口。真实家庭 NAS 只做证书元数据、SAN、公开指纹与部署前后完整证书 SHA-256、SPKI
的只读核验。

常用环境变量：

| 变量 | 默认 / 含义 |
|---|---|
| `NAS_SSH` | `nas-operator@192.168.77.10` |
| `NAS_SSH_PORT` | `10000` |
| `NAS_REMOTE_DIR` | `/tmp/lezi-sync-releases/lezi-sync-<ver>-nas` |
| `LEZI_DATA_HOST_PATH` | `/tmp/zfsv3/sata1/nas-account/data/Docker/lezi/data`；须为规范化 portable 绝对路径，拒绝 `/` 及 `/etc`、`/usr`、`/var`、`/home`、`/root`、`/tmp`、`/opt`、`/srv` 这些精确广域根路径，须使用产品专属子目录 |
| `LEZI_SECRET_FILE` | NAS 持久根密码文件；默认数据 bind 同级 `config/lezi-sync.env`（目录 700、文件 600） |
| `LEZI_ALLOW_SECRET_RECOVERY=1` | live 容器缺失时，显式授权从持久文件恢复；普通 CD 不设置 |
| `LEZI_ALLOW_SECRET_RESEED=1` | live 缺失的维护窗内显式替换冲突值；须同时转发新 secret，普通 CD 不设置 |
| `LEZI_ALLOW_TLS_BOOTSTRAP=1` | 仅已确认全新数据根首次建证书；须显式转发 secret 且远端无 live 容器 |
| `LEZI_TLS_HOST` | `192.168.77.10`；首次证书 SAN 使用的 NAS IPv4 或解析到 IPv4 的 DNS 名 |
| `LEZI_SKIP_PACKAGE=1` | 显式复用本地包；默认每次 push 都从当前可检查镜像重新打包。复用仍要求对应本地镜像 config digest/OS/架构一致，并重验 data/TLS/origin、inventory/helpers/hashes，以及 APK 签名者、application/version、本地数据合同和 metadata |
| `LEZI_BOOTSTRAP_SECRET` | 仅无现网容器可继承时手动提供 |
| `LEZI_FORWARD_BOOTSTRAP_SECRET=1` | 仅首次/切割时通过 SSH stdin 转发上述 secret；普通 CD 不设置 |
| `LEZI_AGE_RECIPIENTS_FILE` | age 公共 recipients 文件；默认 `~/.config/lezi/age-recipients.txt` |
| `LEZI_CREDENTIAL_BACKUP_DIR` | 仓库外密文目录；默认 `~/.config/lezi/backups/` |
| `LEZI_AGE_IDENTITY_FILE` | 仅恢复 staging 使用的离线私钥；须在仓库外、非软链、权限 600 |
| `LEZI_EXPECTED_CERTIFICATE_SHA256` / `LEZI_EXPECTED_SPKI_SHA256` | 恢复时必填的独立生产指纹；不能只信同一密文内自报值 |
| `LEZI_RELEASE_APK` | 打进 NAS 包的 signed release APK（默认 `app/build/outputs/apk/release/app-release.apk`） |
| `LEZI_APP_UPDATE_JSON` | 更新元数据（默认 `tools/lezi-sync/deploy/app-update.json`；sha256 须与 APK 一致） |
| `LEZI_APK_SIGNER` | 可选本机 `apksigner` 路径；默认从 Android SDK build-tools 发现 |
| `LEZI_LAN_APK_DOWNLOAD_ORIGIN` | 邀请首装页的 LAN HTTP origin；NAS 默认 `http://<LEZI_TLS_HOST>:8767`，本版仅支持 IPv4/DNS，禁止公网发布 |

家庭 NAS 首次使用加固 CD 前，须由管理员一次性创建数据 bind 同级
`/tmp/zfsv3/sata1/nas-account/data/Docker/lezi/config`，交给 SSH 用户并设为 mode `700`；
`/tmp/lezi-sync-releases` 也必须由 SSH 用户所有且为 mode `700`。不得递归修改数据 bind 或
`tls/` 权限。精确命令、遗留含 `.env` 发布目录的保留式迁移方式见
[`tools/lezi-sync/deploy/DEPLOY.md`](tools/lezi-sync/deploy/DEPLOY.md) § One-time NAS filesystem preflight。

**自托管应用内更新**：`package-nas.sh` **fail-closed** — 缺少 release APK、`app-update.json`
不合法、sha256 不匹配、签名无效或签名证书不等于仓库追踪的公共指纹
`config/release-apk-signer-sha256.txt` 都会失败；私钥/keystore 仍不进 Git。成功包含
`app-update/app-release.apk` 与元数据；部署后
写入数据卷，由 lezi-sync 鉴权提供 `GET /v1/app-update` 与 `GET /v1/app-update/apk`。已加入家庭
的客户端可检查/下载并经系统 `PackageInstaller` 安装；**不是** Google Play In-App Updates。
抬高 `min_supported_version_code` 可强制旧客户端升级并拒绝权威同步（仍放行更新下载）。详见
[`docs/spec/platform.md`](docs/spec/platform.md) §4.2 与
[`tools/lezi-sync/deploy/DEPLOY.md`](tools/lezi-sync/deploy/DEPLOY.md)。

**邀请首装**：启用其中一个邀请 origin 后，同一成员登录 QR 可被系统相机打开到安装页；
页面匿名提供同一个已校验 Release APK。`LEZI_LAN_APK_DOWNLOAD_ORIGIN` 仍走独立 8767；
`LEZI_INVITE_INSTALL_ORIGIN` 挂在 HTTPS public 面且不听 8767。首次安装后须回到乐记重扫二维码；
历史正式版本原地升级会保留本机数据与家庭配置，不因同步最低版本门禁失去下载出口。
8767 不提供家庭 API，并且只允许可信家庭 LAN；明文链路的页面/APK 替换与 grant 盗用风险见
[ADR-0015](docs/adr/0015-isolate-lan-invite-install-distribution.md)。

**手机访问**：首次连接或已加入后的「家庭网络设置」填写
`https://<NAS-LAN-IP>:8765`。首次会显示部署脚本打印的 SPKI 指纹，确认后固定；后续公钥
变化会硬阻断并要求重新信任/登录，旧凭证不会发送到候选地址。须用 NAS 防火墙禁止公网访问
8765/8767。旧 Owner 可把本机完整护理副本恢复到符合当前合同的空服务器；不支持两个已配置家庭合并。

**首次空部署**（无现网容器、且已确认数据根全新无 TLS identity）须同时设置
`LEZI_BOOTSTRAP_SECRET`（≥16 字符）、`LEZI_FORWARD_BOOTSTRAP_SECRET=1` 和
`LEZI_ALLOW_TLS_BOOTSTRAP=1` 后再 `push-and-deploy`，并在 App 建家时填同一口令。TLS bootstrap
必须同时显式转发 secret，且远端检测到 live 容器存在时必定中止。
普通 CD 必须让 `LEZI_ALLOW_TLS_BOOTSTRAP` 保持未设置；脚本会在停容器前及启动后核对完整
证书文件 SHA-256 与 SPKI，任何变化都失败。首次/灾备路径因为没有 live 容器可做部署前
导出，只能在上述显式授权下继续，并在新容器健康后立即生成首份 `age` 密文；普通 CD 则必须
先完成密文备份才会替换容器。完整 secret 状态矩阵、age 初始化与恢复 staging 步骤见权威
[`DEPLOY.md`](tools/lezi-sync/deploy/DEPLOY.md) § Secret handling / Credential backup and restore。

仅打包不部署：`./deploy/package-nas.sh`。普通同 schema 镜像回滚不得直跑旧包脚本：先用当前
审计过的 deploy helpers 重新封装指定旧镜像，再经 `push-and-deploy.sh` 执行；该受保护入口会
在替换前强制生成新的凭据密文备份。pre-TLS/schema 切割回滚是例外，不能用当前 TLS harness；
其权威 runbook 当前明确把尚未提供的可执行 exact-recreation helper 作为开窗 blocker。

**离线 schema 11/12→13 准备（`offline-migrate`）**：开发机上的显式只读 copy-out CLI，
**不是**服务启动自动迁移，也**不**构成一般滚动 schema 兼容。日常 NAS 仍
fresh-current / fail-closed（[ADR-0008](docs/adr/0008-support-only-fresh-current-product-contracts.md)）；
边界见 [ADR-0013](docs/adr/0013-offline-migrate-is-maintenance-window-cutover.md)。
**普通 CD 不执行**该子命令；schema 13 只由 H29 的独立、显式授权
`tools/lezi-sync/deploy/schema-cutover.sh` 编排。H30 已在回环 Docker 与临时数据根完成 schema
11/12 的成功和八阶段 rollback rehearsal；该证据不构成生产授权。release ticket 09 的重新
确认仍是生产维护窗前置门。权威边界：
[`tools/lezi-sync/deploy/copy-back-tls-cutover-runbook.md`](tools/lezi-sync/deploy/copy-back-tls-cutover-runbook.md)；
摘要：[`tools/lezi-sync/README.md`](tools/lezi-sync/README.md) § 离线切割、
[`tools/lezi-sync/deploy/DEPLOY.md`](tools/lezi-sync/deploy/DEPLOY.md) § offline-migrate。

开发机通用 Compose（变量/build，非 zdocker 专用）仍见
[`tools/lezi-sync/docker-compose.yml`](tools/lezi-sync/docker-compose.yml) 与
[`tools/lezi-sync/README.md`](tools/lezi-sync/README.md)。

Android 模拟器调试本机服务时，在账户页手动填写
`https://10.0.2.2:8765`；证书需包含 `10.0.2.2` SAN。该地址只用于调试，不是任何 build type 的默认值。

## 已知限制

- **同步**：Rust 服务端、Android 实现与自动化已完成；VPS 部署线已删除，
  NAS 回滚与维护仍须按上节确认窗口，不自动执行。
- **同步策略**：仅 App 前台连接用户确认的可信 HTTPS endpoint；无后台轮询和伴侣记录
  通知；设置/深色**不同步**
- **网络权限**：不读取网络名称，不申请仅为家庭同步存在的位置或 Wi-Fi 权限；连接失败不
  影响本地记账
- **成长曲线**：离线内置中国 `WS/T 423—2022` 7 岁以下、分性别 P3/P50/P97 参考带，**非医疗诊断**
- **Widget**：需手动添加到桌面
- **厂商杀后台**：母乳计时依赖 FGS + DataStore
- **无广告 / 无 IAP / 无强制登录**
- **应用内更新**：仅 release `com.lezi.babylog` + 已加入家庭会话；debug 后缀包不承诺自更新；
  安装 UI 与「未知应用来源」授权依赖系统；不承诺清除 PackageInstaller 系统缓存

## 设计系统

| 层 | 路径 |
|----|------|
| Compose tokens | `designsystem/Tokens.kt` · `Theme.kt` |
| 通用组件 | `designsystem/Components.kt` |
| Preview 基线 | `designsystem/ComponentPreviews.kt`（390×844） |

主 seam：`domain/.../CareLog.kt`（feature 禁止直注 DAO）。

## 模块

```
app
core/{model,common,database,datastore,ui}
designsystem
domain
sync
feature/{onboarding,log,timer,family,settings,summary,growth,export,search,widget}
tools/lezi-sync
```

## 票单与协作

| 类型 | 真源 |
|------|------|
| 功能票 / spec | 本地 [`.scratch/`](.scratch/)（[`docs/agents/issue-tracker.md`](docs/agents/issue-tracker.md)） |
| 代码评审与合入 | **GitHub Pull Request** → `master`（[`CONTRIBUTING.md`](CONTRIBUTING.md)、[`docs/agents/pull-requests.md`](docs/agents/pull-requests.md)） |
| GitHub Issues | **不是**工作流入口 |

开 PR：`git push -u github HEAD` 后 `gh pr create --base master`；模板见
[`.github/pull_request_template.md`](.github/pull_request_template.md)。
同步契约变更对照 [`docs/spec/contracts/sync-trusted-endpoint.md`](docs/spec/contracts/sync-trusted-endpoint.md)。

## 许可

本仓库以 [MIT License](LICENSE) 发布（与 `tools/lezi-sync` 的 crate `license = "MIT"` 一致）。

## Agent / 模拟器

```bash
lezi-emu start --window
lezi-emu shot /tmp/lezi.png
lezi-emu dump /tmp/lezi-ui.xml
```
