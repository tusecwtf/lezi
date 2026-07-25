# 12 — V1 APK 交付与冒烟

**Parent:** [.scratch/v1-delivery/spec.md](../spec.md)

## What to build

可复现打出 **debug** APK；根 **README** 写构建命令与已知限制；按 PRD §6.1 / spec 冒烟清单在模拟器全绿。

## Blocked by

02–11 与 **13**。

## Status

done

## 交付物

- debug APK：`app/build/outputs/apk/debug/app-debug.apk`
- 应用名「乐记」；applicationId `com.lezi.babylog`（debug 后缀 `.debug`）
- README 含构建、启动组件、已知限制

## 验收标准

### Must

- [x] **assembleDebug** exit 0
- [x] 包名 `com.lezi.babylog`；minSdk 26
- [x] README 含 assembleDebug + 同步未开通限制
- [x] 仅 debug 交付（README 已说明）

### 冒烟（模拟器 lezi_api35）

- [x] 创建宝宝（昵称/性别/生日/主题色）→ 记录
- [x] 配方奶 / 尿 / 睡下醒来 / 计时入口
- [x] 尿尿默认中档；便便编辑页三行芯片
- [x] 配方奶步进默认 5；设置 5/10/15
- [x] 母乳左右计时 + FGS + completeNursing
- [x] 时间轴 + 编辑删除 + 日汇总 feedMl
- [x] 三轨 24h 时间轴 + 日期切换
- [x] 深色 + 第二宝宝
- [x] 下次喂奶调度器 + BOOT receiver
- [x] 账户 Stub「家庭同步将在后续版本开放」
- [x] force-stop 保宝宝（Room）
- [x] 中文主路径；无广告购买

## Comments

- 2026-07-23: OD Today 纵向切片对齐（摘要5格 + 三轨时间轴 + 最近记录 + 快捷入口 + 陶土 FAB）
- 截图：`/tmp/lezi-od-today*.png` · `/tmp/lezi-v1-*.png`
