# 提交后复审修复：聚焦模拟器回归

- 日期：2026-07-24
- 基线：`HEAD 5b4e7b8` + 当前未提交复审修复工作树
- 设备：`lezi_api35` / `emulator-5554` / Android 15 / 1080×2400
- APK：`app/build/outputs/apk/debug/app-debug.apk`
- 构建时间：`2026-07-24 03:19:26 +0800`
- SHA-256：`0ef497faa4b533e19b20f2e3b7d99411ee1c8fb7f5d081bba100265422e58d4c`
- 构建：`:app:assembleDebug` **PASS**
- 安装/冷启动：`adb install -r` 与 `MainActivity` 冷启动 **PASS**（966 ms）

## 结果

| 项目 | 结果 | 证据 |
|---|---|---|
| 强制深色：状态栏与三键导航栏同步深色 | **PASS** | `shots/01-dark-system-bars.png`；像素为 status `(38,39,39)`、header `(38,39,39)`、app nav `(38,38,38)`、system nav `(38,38,38)`。状态栏图标为白色，三键图标为可见灰色。 |
| 切回浅色：系统栏无回归 | **PASS** | `shots/02-light-system-bars.png`；像素为 status/header `(234,124,143)`、app/system nav `(249,247,248)`。状态栏与三键图标均为深色且清晰。 |
| 新增 Composer 打开/关闭 smoke | **PASS** | `shots/03-add-composer-smoke.png`、`dumps/03-add-composer-smoke.xml`；包含“补充信息后确认保存 / 尿尿”，无错误或陈旧编辑态。返回键正常关闭。 |
| 编辑 Composer 打开/关闭 smoke | **PASS** | `shots/04-edit-composer-smoke.png`、`dumps/04-edit-composer-smoke.xml`；包含“编辑记录 / 尿尿 / 删除”，正确载入既有记录时间 `7月24日 01:47`。返回键正常关闭。 |
| 回归会话无应用 FATAL | **PASS** | 清空 logcat 后覆盖冷启动、深浅主题切换、新增/编辑 Composer；`FATAL EXCEPTION`、`AndroidRuntime FATAL`、`Process: com.lezi.babylog.debug` 均为 0。见 `logcat-summary.txt`。 |
| 保存中操作禁用 hierarchy | **NOT RUN（不虚构）** | 本地 Room 保存完成太快，本次没有可控延迟或测试注入点，无法稳定捕获 saving 中间态；因此不把静态按钮状态冒充为设备证据。 |

## 结论

本轮聚焦范围内未发现回归。动态 `SystemBarStyle` 已解决先前深色模式下系统三键导航栏仍为浅色的问题；浅色模式切回也正常。Composer 请求代际修复后的新增/编辑基础打开与关闭路径均稳定，logcat FATAL 为 0。

本轮未修改产品代码；只写入本目录下的验收证据。
