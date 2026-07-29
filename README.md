# 乐记（lezi）

家庭育儿日志 · Android · Kotlin + Jetpack Compose
包名：`com.lezi.babylog` · minSdk 26 · targetSdk 35 · 显示名「乐记」 · version `0.2.6`

产品规格：[`docs/prd/`](docs/prd/) · 领域术语：[`CONTEXT.md`](CONTEXT.md) · 安全：[`SECURITY.md`](SECURITY.md)  
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

```bash
# 本机运行 API 与自动化测试
cd tools/lezi-sync
cargo test --locked
cargo clippy --all-targets --all-features -- -D warnings

# 在有 Docker 的 NAS/主机上构建并启动
./build-image.sh
export LEZI_DATA_HOST_PATH=/volume1/docker/lezi
# Compose 必填；openssl rand -hex 24 生成 48 字符，满足 >=16 字符要求。
export LEZI_BOOTSTRAP_SECRET="$(openssl rand -hex 24)"

# 手机访问必须二选一：
# A. 仅在可信家庭 LAN 直连（同时用 NAS 防火墙禁止公网访问 8765）：
export LEZI_SYNC_PUBLISH=192.168.1.10:8765 # 替换为 NAS 的真实 LAN IP
# B. 或不设置 LEZI_SYNC_PUBLISH，保持默认 127.0.0.1:8765，
#    在 NAS 配置 HTTPS 反代：https://你的家庭域名 -> http://127.0.0.1:8765

docker compose up -d
curl -fsS http://127.0.0.1:8765/health
curl -fsS http://127.0.0.1:8765/ready
```

选择 A 时，账户页填写 `http://<NAS-LAN-IP>:8765`；选择 B 时填写 NAS 反代的
`https://` 地址。手机直连场景不能保留默认 loopback 后仍期待手机访问服务，且
不要把 8765 映射到公网。

Android 模拟器调试本机服务时，在账户页手动填写
`http://10.0.2.2:8765`；该地址只用于调试，不是任何 build type 的默认值。

## 已知限制

- **同步**：Rust 服务端、Android 实现与自动化已完成；本机 Docker 与双模拟器
  已有支撑验收，物理双设备与 NAS 生产部署仍待目标环境执行；未宣称已部署
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
