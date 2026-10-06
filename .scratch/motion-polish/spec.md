# motion-polish · 动画过渡优化

**触发**：用户报告 APK 开屏页面闪屏。顺带完成动画全量盘点、母婴 App 动效时长研究，
形成整体优化计划。

**产物**：

- [inventory.md](inventory.md) —— 全量动画清单 + 统计（58 处 Compose 动画，无 XML/Lottie/View 动画；
  `LeziMotion` 三档时长 token 已存在，缺缓动 token，约 15 处时长游离 token 外）。
- [research.md](research.md) —— M3 token 表（本地 material-1.12.0 AAR 核验）+ NN/g 100–500ms 共识 +
  冷启动首帧对齐最佳实践 + 母婴场景约束，落成乐记动效规格表。
- [issues/](issues/) —— 4 张票：01 开屏闪屏修复（本次落地）；02 缓动 token；03 游离时长迁移；
  04 页面级过渡调优。

**核心决策**（2026-09-10，用户确认）：

1. 闪屏修复口径 = **首帧对齐 + core-splashscreen 桥接**：仅在本地数据门禁 `Checking`
   期间按住系统启动窗（Android 12+ 本来就有），不新增品牌启动页、无人为延迟——
   CONTEXT.md「本地数据升级门禁 Avoid: 启动页」仍然有效，按住系统窗不属于「启动页」。
2. `LeziMotion` 三档数值 150/200/300 恰为 M3 short3/short4/medium2，**不改数值、不立第二套
   时长表**；补的是缓动 token（票 02）与页面级档位（票 04）。
3. 本次执行范围 = 文档 + 计划票 + 票 01 落地；02/03/04 留待按需执行。

**边界**：纯客户端 UI 改动，不动 wire/server，无需 NAS 联调。
