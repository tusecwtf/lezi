# 05 — 合同写回与端到端验收收口

**What to build:** 将自托管应用内更新合同写回 PRD/技术说明与部署 runbook，并按 spec 完成可选装包、强制升级、打包 fail-closed、无残留等可重复验收与证据，关闭本 tracker 或标明残留边界。

**Blocked by:** 02 — 可选更新下载安装与无残留；03 — 强制升级 minSupported；04 — 握手横幅

**Status:** ready-for-agent

## Acceptance criteria

- [ ] docs/prd（tech 及同步相关必要段落）与 DEPLOY/runbook 写明：自托管通道、资格、双档、打包带 APK、无残留、非 Play In-App Updates
- [ ] 打包：合法 APK+元数据成功；缺 APK 失败（证据）
- [ ] 可选路径：已加入 → 检查 → 确认 → 下载 → 系统安装 UI 可调起（环境限制须写明边界）
- [ ] 强制路径：抬高 minSupported 后全屏 + 同步须升级语义
- [ ] 升级相关应用私有目录无 APK 残留
- [ ] evidence/ 可复述命令与结果；spec 与实现无未决矛盾；.scratch/README 可标 complete（合入后）

## Comments

-
