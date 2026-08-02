# 乐记（lezi）

家庭育儿日志 · Android · Kotlin + Jetpack Compose
包名：`com.lezi.babylog` · minSdk 26 · targetSdk 35 · 显示名「乐记」 · version `0.3.4`

产品规格：[`docs/prd/`](docs/prd/) · 领域术语：[`CONTEXT.md`](CONTEXT.md) · 文档索引：[`docs/README.md`](docs/README.md) · 安全：[`SECURITY.md`](SECURITY.md)
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

CI（GitHub Actions）：push/PR 上跑 unit test；`tools/lezi-sync` 变更另跑 cargo test + clippy。

## 同步服务（V2）

规格（可信 endpoint、身份、前台策略与 API）：[`docs/prd/sync-trusted-endpoint.md`](docs/prd/sync-trusted-endpoint.md)

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

### 服务端发版（生产 NAS）

**权威步骤与脚本**：[`tools/lezi-sync/deploy/DEPLOY.md`](tools/lezi-sync/deploy/DEPLOY.md)

约定：

| 项 | 选择 |
|---|---|
| 构建位置 | 开发机（`linux/amd64` 镜像），**不在 NAS 上 cargo/docker build** |
| 编排引擎 | 极空间 **zdocker** 自带 Compose v2（`/zspace/.../zdocker/bin/docker-compose`）；不要求系统安装 `docker compose` |
| 发布触发 | 本机一键：`package` → `scp` → SSH `remote-deploy` |
| Bootstrap | 从现网 `lezi-sync` 容器 env **继承**；secret **不进 git** |
| 数据卷 | 宿主 bind（默认路径见下），stop/rm 容器不删数据 |
| TLS 身份 | 普通 CD/回滚/重启必须复用数据卷内原证书与私钥；仅经确认的全新空数据根可首次生成 |

```bash
cd tools/lezi-sync

# 1) 质量门
cargo test --locked
cargo clippy --all-targets --all-features -- -D warnings

# 2) 构建镜像（版本默认读 Cargo.toml）
./build-image.sh
# → lezi-sync:<version>

# 3) 打包 + 推 NAS + 替换现网容器
./deploy/push-and-deploy.sh
# 产出：dist/lezi-sync-<version>-nas/（gitignored）
# 远端默认：/tmp/lezi-sync-releases/lezi-sync-<version>-nas
# （Zspace SSH 用户 HOME 常为 /home/ 不可写，故不用 ~）

# 4) 验收（本机或 NAS）
curl --cacert /path/to/data/tls/server.crt -fsS https://192.168.50.4:8765/health
curl --cacert /path/to/data/tls/server.crt -fsS https://192.168.50.4:8765/ready
```

**现网证书是发布硬边界：**普通 CD 不承担证书轮换，不得覆盖、删除、改名或重新生成已有
`/data/tls/server.crt` 与 `/data/tls/server.key`。部署前后必须只读计算 SPKI 并要求完全一致；
证书缺半、无效/过期、私钥不匹配、容器内不可读或指纹变化都应停止发布，而不是自动修复。
只有已单独确认的全新空数据根、且两个文件都不存在时，才允许首次生成 TLS 身份。

证书生成、替换、过期、错配、TOFU 变化和证书重连测试禁止使用真实家庭 NAS、现网容器或其
数据 bind；必须在开发者自行部署的隔离 `lezi-sync` 服务上进行，使用 `mktemp` 临时数据根和
非生产端口。真实家庭 NAS 只做证书元数据、SAN、公开指纹与部署前后完整证书 SHA-256、SPKI
的只读核验。

常用环境变量：

| 变量 | 默认 / 含义 |
|---|---|
| `NAS_SSH` | `13096920600@192.168.50.4` |
| `NAS_SSH_PORT` | `10000` |
| `NAS_REMOTE_DIR` | `/tmp/lezi-sync-releases/lezi-sync-<ver>-nas` |
| `LEZI_DATA_HOST_PATH` | `/tmp/zfsv3/sata1/13096920600/data/Docker/lezi/data` |
| `LEZI_TLS_HOST` | `192.168.50.4`；首次证书 SAN 使用的 NAS DNS 名或 IP |
| `LEZI_FORCE_PACKAGE=1` | 强制重打包 |
| `LEZI_SKIP_PACKAGE=1` | 仅 scp+部署已有 `dist/` 包 |
| `LEZI_BOOTSTRAP_SECRET` | 仅无现网容器可继承时手动提供 |
| `LEZI_RELEASE_APK` | 打进 NAS 包的 signed release APK（默认 `app/build/outputs/apk/release/app-release.apk`） |
| `LEZI_APP_UPDATE_JSON` | 更新元数据（默认 `tools/lezi-sync/deploy/app-update.json`；sha256 须与 APK 一致） |

**自托管应用内更新**：`package-nas.sh` **fail-closed** — 缺少 release APK 或 `app-update.json`
不合法（含 sha256 不匹配）则打包失败。成功包含 `app-update/app-release.apk` 与元数据；部署后
写入数据卷，由 lezi-sync 鉴权提供 `GET /v1/app-update` 与 `GET /v1/app-update/apk`。已加入家庭
的客户端可检查/下载并经系统 `PackageInstaller` 安装；**不是** Google Play In-App Updates。
抬高 `min_supported_version_code` 可强制旧客户端升级并拒绝权威同步（仍放行更新下载）。详见
[`docs/prd/tech.md`](docs/prd/tech.md) §4.2 与
[`tools/lezi-sync/deploy/DEPLOY.md`](tools/lezi-sync/deploy/DEPLOY.md)。

**手机访问**：首次连接或已加入后的「家庭网络设置」填写
`https://<NAS-LAN-IP>:8765`。首次会显示部署脚本打印的 SPKI 指纹，确认后固定；后续公钥
变化会硬阻断并要求重新信任/登录，旧凭证不会发送到候选地址。须用 NAS 防火墙禁止公网访问
8765。旧 Owner 可把本机完整护理副本恢复到空 0.3.3 服务器；不支持两个已配置家庭合并。

**首次空部署**（无现网容器、且已确认数据根全新无 TLS identity）须同时设置
`LEZI_BOOTSTRAP_SECRET`（≥16 字符）、`LEZI_FORWARD_BOOTSTRAP_SECRET=1` 和
`LEZI_ALLOW_TLS_BOOTSTRAP=1` 后再 `push-and-deploy`，并在 App 建家时填同一口令。
普通 CD 必须让 `LEZI_ALLOW_TLS_BOOTSTRAP` 保持未设置；脚本会在停容器前及启动后核对完整
证书文件 SHA-256 与 SPKI，任何变化都失败。

仅打包不部署：`./deploy/package-nas.sh`。回滚：在 NAS 上进入旧版包目录再跑 `./remote-deploy.sh`。

**离线 v3→current 切割（`offline-migrate`）**：仅已授权**维护窗**使用的离线切割 CLI，
**不是**服务启动自动迁移，也**不**构成一般滚动 schema 兼容。日常 NAS 仍
fresh-current / fail-closed（[ADR-0008](docs/adr/0008-support-only-fresh-current-product-contracts.md)）；
边界见 [ADR-0013](docs/adr/0013-offline-migrate-is-maintenance-window-cutover.md)。
**普通 CD 不执行**该子命令。权威步骤：
[`tools/lezi-sync/deploy/copy-back-tls-cutover-runbook.md`](tools/lezi-sync/deploy/copy-back-tls-cutover-runbook.md)；
摘要：[`tools/lezi-sync/README.md`](tools/lezi-sync/README.md) § 离线切割、
[`tools/lezi-sync/deploy/DEPLOY.md`](tools/lezi-sync/deploy/DEPLOY.md) § offline-migrate。

开发机通用 Compose（变量/build，非 zdocker 专用）仍见
[`tools/lezi-sync/docker-compose.yml`](tools/lezi-sync/docker-compose.yml) 与
[`tools/lezi-sync/README.md`](tools/lezi-sync/README.md)。

Android 模拟器调试本机服务时，在账户页手动填写
`https://10.0.2.2:8765`；证书需包含 `10.0.2.2` SAN。该地址只用于调试，不是任何 build type 的默认值。

## 已知限制

- **同步**：Rust 服务端、Android 实现与自动化已完成；发版路径为开发机构建镜像 +
  SSH/zdocker 部署（见上节）。物理双设备长期验收与备份策略仍按运维需要执行
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
同步契约变更对照 [`docs/prd/sync-trusted-endpoint.md`](docs/prd/sync-trusted-endpoint.md)。

## 许可

本仓库以 [MIT License](LICENSE) 发布（与 `tools/lezi-sync` 的 crate `license = "MIT"` 一致）。

## Agent / 模拟器

```bash
lezi-emu start --window
lezi-emu shot /tmp/lezi.png
lezi-emu dump /tmp/lezi-ui.xml
```
