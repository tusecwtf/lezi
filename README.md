# 乐记（lezi）

家庭育儿日志 · Android · Kotlin + Jetpack Compose
包名：`com.lezi.babylog` · minSdk 26 · targetSdk 35 · 显示名「乐记」 · version `0.3.0`

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

规格（门闩、前台策略、NAS 选型与 API）：[`docs/prd/sync-home-lan.md`](docs/prd/sync-home-lan.md)

交付实现：Android `:sync` / `:feature:family` +
[`tools/lezi-sync`](tools/lezi-sync/)（Rust、Axum、Tokio、SQLite、Docker
单卷）。客户端不内置服务器地址；须在账户页填写家中 NAS 地址或扫描邀请 QR。

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
curl -fsS http://192.168.50.4:8765/health   # 期望 version 与发版一致
curl -fsS http://192.168.50.4:8765/ready
```

常用环境变量：

| 变量 | 默认 / 含义 |
|---|---|
| `NAS_SSH` | `13096920600@192.168.50.4` |
| `NAS_SSH_PORT` | `10000` |
| `NAS_REMOTE_DIR` | `/tmp/lezi-sync-releases/lezi-sync-<ver>-nas` |
| `LEZI_DATA_HOST_PATH` | `/tmp/zfsv3/sata1/13096920600/data/Docker/lezi/data` |
| `LEZI_FORCE_PACKAGE=1` | 强制重打包 |
| `LEZI_SKIP_PACKAGE=1` | 仅 scp+部署已有 `dist/` 包 |
| `LEZI_BOOTSTRAP_SECRET` | 仅无现网容器可继承时手动提供 |

**手机访问**：账户页填 `http://<NAS-LAN-IP>:8765`（当前生产映射 `0.0.0.0:8765`）；须用 NAS 防火墙禁止公网访问 8765。需要 HTTPS 时在 NAS 反代到本机 8765。

**首次空部署**（无现网容器）须自行设置 `LEZI_BOOTSTRAP_SECRET`（≥16 字符）后再 `push-and-deploy`，并在 App 建家时填同一口令。

仅打包不部署：`./deploy/package-nas.sh`。回滚：在 NAS 上进入旧版包目录再跑 `./remote-deploy.sh`。

开发机通用 Compose（变量/build，非 zdocker 专用）仍见
[`tools/lezi-sync/docker-compose.yml`](tools/lezi-sync/docker-compose.yml) 与
[`tools/lezi-sync/README.md`](tools/lezi-sync/README.md)。

Android 模拟器调试本机服务时，在账户页手动填写
`http://10.0.2.2:8765`；该地址只用于调试，不是任何 build type 的默认值。

## 已知限制

- **同步**：Rust 服务端、Android 实现与自动化已完成；发版路径为开发机构建镜像 +
  SSH/zdocker 部署（见上节）。物理双设备长期验收与备份策略仍按运维需要执行
- **同步策略**：仅家 Wi‑Fi + NAS 可达 + App 前台；无后台轮询和伴侣记录通知；
  设置/深色**不同步**
- **家网权限**：首次使用家庭同步时请求位置权限；Android 把当前 SSID 视为
  位置敏感字段。应用只在本机做 SSID 精确匹配，不读取坐标、不上传 SSID
- **成长曲线**：离线内置 WHO 0–24 月、分性别参考数据，**非医疗诊断**
- **Widget**：需手动添加到桌面
- **厂商杀后台**：母乳计时依赖 FGS + DataStore
- **无广告 / 无 IAP / 无强制登录**

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
同步契约变更对照 [`docs/prd/sync-home-lan.md`](docs/prd/sync-home-lan.md)。

## 许可

本仓库以 [MIT License](LICENSE) 发布（与 `tools/lezi-sync` 的 crate `license = "MIT"` 一致）。

## Agent / 模拟器

```bash
lezi-emu start --window
lezi-emu shot /tmp/lezi.png
lezi-emu dump /tmp/lezi-ui.xml
```
