# 05 — 合同写回与端到端验收收口

**What to build:** 将自托管应用内更新合同写回 PRD/技术说明与部署 runbook，并按 spec 完成可选装包、强制升级、打包 fail-closed、无残留等可重复验收与证据，关闭本 tracker 或标明残留边界。

**Blocked by:** 02 — 可选更新下载安装与无残留；03 — 强制升级 minSupported；04 — 握手横幅

**Status:** done

## Acceptance criteria

- [x] docs/prd（tech 及同步相关必要段落）与 DEPLOY/runbook 写明：自托管通道、资格、双档、打包带 APK、无残留、非 Play In-App Updates
- [x] 打包：合法 APK+元数据成功；缺 APK 失败（证据）
- [x] 可选路径：已加入 → 检查 → 确认 → 下载 → 系统安装 UI 可调起（环境限制须写明边界）
- [x] 强制路径：抬高 minSupported 后全屏 + 同步须升级语义
- [x] 升级相关应用私有目录无 APK 残留
- [x] evidence/ 可复述命令与结果；spec 与实现无未决矛盾；.scratch/README 可标 complete（合入后）

## Comments

- PRD: `docs/prd/tech.md` §4.2; `sync-trusted-endpoint.md` §7.4 + failure row + API duties; `ui.md` 关于/强制/横幅; README in/out of scope.
- Runbook: `tools/lezi-sync/deploy/DEPLOY.md` env + fail-closed checks; root `README.md` LEZI_RELEASE_APK / LEZI_APP_UPDATE_JSON.
- Gates: cargo `app_update` (7) + `client_update` (2) + clippy; Gradle RealSyncPortTest + HttpSyncBackendTest + OptionalAppUpdateBannerPolicyTest; assembleDebug; package missing-APK exit 1; package success with release APK.
- Boundaries: full device PackageInstaller UI needs joined release + newer server APK; `run-as` residual check blocked on non-debuggable release install; force/minSupported covered by automated HTTP + SyncPort mapping. Evidence: `evidence/05/validation.md`.
