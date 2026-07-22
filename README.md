# 乐记（lezi）

家庭育儿日志 · Android · Kotlin + Jetpack Compose  
包名：`com.lezi.babylog` · minSdk 26 · targetSdk 35

产品规格见 [`docs/prd/`](docs/prd/)。

## 环境

- JDK 17+（推荐 21，已用 `JAVA_HOME`）
- Android SDK：`ANDROID_HOME` 指向 SDK（本机默认 `~/Android/Sdk`）
- 可选模拟器 AVD：`lezi_api35`（`lezi-emu start`）

## 构建

```bash
./gradlew :app:assembleDebug
# APK
# app/build/outputs/apk/debug/app-debug.apk
```

安装到已连接设备 / 模拟器：

```bash
./gradlew :app:installDebug
adb shell am start -n com.lezi.babylog.debug/.MainActivity
```

单测：

```bash
./gradlew test
```

## 模块

```
app
core/{model,common,database,datastore,ui}
designsystem
domain
sync
feature/{onboarding,log,timer,family,settings,summary,growth,export,search,widget}
```

依赖方向：`app → feature → domain → core`；`sync` 提供 `SyncPort`（V1 = `NoOpSyncPort`）。

## Agent 调试

```bash
lezi-emu start          # 或真机 adb
lezi-emu shot           # 截图
lezi-emu dump           # UI 树
```
