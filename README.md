# 乐记（lezi）

家庭育儿日志 · Android · Kotlin + Jetpack Compose
包名：`com.lezi.babylog` · minSdk 26 · targetSdk 35 · 显示名「乐记」 · version `0.2.0-offline-v2-beta`

产品规格：[`docs/prd/`](docs/prd/)
设计同步：[`design/README.md`](design/README.md) · 原型快照 [`prototype/`](prototype/)
Open Design 项目：`leji-prd-prototype`（禁止 `nuanya-baby-tracker`）

## 环境

- **JDK 21**（`gradle.properties` 已钉 `org.gradle.java.home`；JDK 25 会触发 AGP 失败）
- Android SDK：`local.properties` → `sdk.dir`
- 可选模拟器 AVD：`lezi_api35`（`lezi-emu start --window`）

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

## 同步服务（V2）

```bash
python3 tools/sync-server/server.py
# health: curl http://127.0.0.1:8765/health
# 模拟器访问宿主机：BuildConfig.SYNC_BASE_URL = http://10.0.2.2:8765
```

## 已知限制

- **同步**：本地 stdlib 服务；设置 / 深色模式**不同步**；双端 SLA 依赖前台 pull
- **成长曲线**：离线内置 WHO 0–24 月、分性别参考数据，**非医疗诊断**
- **Widget**：需手动添加到桌面
- **厂商杀后台**：母乳计时依赖 FGS + DataStore
- **无广告 / 无 IAP / 无强制登录**

## 设计系统

| 层 | 路径 |
|----|------|
| Tokens JSON | `design/tokens.json` |
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
tools/sync-server
```

## Agent / 模拟器

```bash
lezi-emu start --window
lezi-emu shot /tmp/lezi.png
lezi-emu dump /tmp/lezi-ui.xml
```
