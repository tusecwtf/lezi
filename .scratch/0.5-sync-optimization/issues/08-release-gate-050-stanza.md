# 08: 发布门——0.5.0 打包 stanza + validate 配对断言

**What to build:** 0.5.0(版本串已由 owner 定名)能走完 NAS 打包与校验流水线而不撞
exit 1:打包脚本补 0.5.0 stanza(服务端 schema 仍 13、回滚源 0.4.8/13),校验脚本钉死
0.5.0 发布身份配对断言,发布身份测试覆盖新版本。0.4.8 及更早 stanza 不受影响。

**Blocked by:** None(can start immediately;发布身份中的 APK versionCode 按 0.4.8=29 顺延预期为 30,落地时与实际构建产物核对)

**Status:** ready-for-agent

- [x] 打包脚本新增 0.5.0 stanza:server schema 13、rollback source 0.4.8/schema 13,覆盖变量只接受该组合
- [x] 校验脚本新增 0.5.0 配对断言:schema 13、APK versionCode(预期 30,以实际产物为准)、floor 21、rollback 0.4.8/13
- [x] 发布身份测试(既有惯例)覆盖 0.5.0:正确身份通过、错配身份逐项 fail-closed
- [x] 0.4.8/0.4.7 及更早 stanza 与配对断言行为不变(回归)
- [x] 打包冒烟路径(APK+app-update 校验专用开关)对 0.5.0 可跑通

## 落地记录(2026-09-06,R 轨)

**落地内容(全部 fixture 驱动,零网络、零远端、未触碰活清单 `deploy/app-update.json`):**

1. `deploy/package-nas.sh`:新增 `0.5.0` stanza(置于 `0.4.8` 之前)——默认 server schema 13、rollback source `0.4.8`/schema 13;`LEZI_PACKAGE_SERVER_SCHEMA`、`LEZI_PACKAGE_ROLLBACK_SOURCE_VERSION`、`LEZI_PACKAGE_ROLLBACK_SOURCE_SERVER_SCHEMA` 三个覆盖变量任一偏离该组合即 `exit 1`(照抄 0.4.8 stanza 的三变量全查防护形态)。`0.4.8` 及更早 stanza 原文未动。
2. `deploy/validate-nas-package.sh`:新增 `0.5.0` 配对断言——`0.5.0/code30/floor21/schema13 with 0.4.8/13 rollback source`,五字段任一错配即 `fail`(逐项 fail-closed)。`0.4.8`/`0.4.7` 及更早断言原样保留。
3. `deploy/test-nas-release-identity.sh`(CI 步骤「NAS release and rollback identity」):重构为参数化 `verify_release_identity`,先后跑 `0.5.0 30 21 13 0.4.8 13 0.4.7` 与 `0.4.8 29 21 13 0.4.7 13 0.4.6`(回归)。每个版本覆盖:无覆盖全量打包成功且 MANIFEST 身份逐字段正确;validator 对五个身份字段逐一变异(重新封装 SHA256SUMS)后逐项拒绝;打包脚本对 schema / rollback source / rollback schema 三个覆盖变量单独错配逐一拒绝;`LEZI_PACKAGE_APP_UPDATE_CHECK_ONLY=1` 冒烟对配对 fixture 通过(校验 early-exit 标记、无 docker save),并拒绝陈旧回滚形态(rollback source 版本串)的 app-update.json(`APK versionCode does not match app-update.json`)。
4. 本地验证:`bash -n` 三脚本通过;`bash deploy/test-nas-release-identity.sh` exit 0;`bash deploy/test-package-nas-app-update.sh` exit 0;CI 接线的其余 deploy 门(`test-remote-deploy-app-update-atomic.sh`、`test-init-tls-read-only.sh`、`test-init-tls.sh`、`test-remote-deploy-tls-guard.sh`、`test-remote-deploy-tls-inspection-read-only.sh`、`test-schema-cutover.sh`)全部 PASS。

**版本 bump(app/build.gradle.kts 29→30 / 0.4.8→0.5.0)——经 owner 裁决留待 09 原子落地,不在本工单:**
两行 bump 无法单独存在:Gradle `validateAndroidReleaseCompatibilityCatalog`(preDebugBuild/preReleaseBuild 前置)fail-closed 要求构建身份 ∈ {released_versions.last(), upgrade_target};解除需改 `config/android-release-compatibility.json`(upgrade_target→30/0.5.0、追加 released 29/0.4.8),而该 catalog 被 `sync/src/test/.../LocalDataContractSixCatalogTest` 钉死在 (29,"0.4.8"),且 deploy CI 冒烟要求 catalog upgrade_target == Cargo.toml version。因此 **09 发布窗口需单一原子 commit**:
- `app/build.gradle.kts` versionCode 29→30、versionName "0.4.8"→"0.5.0";
- `config/android-release-compatibility.json` 移 catalog(29/0.4.8 入 released_versions,upgrade_target→30/0.5.0,room_schema 29、local_data_contract 6);
- 同步更新 `sync` 测试 pin(LocalDataContractSixCatalogTest 等)与 `tools/lezi-sync/Cargo.toml` version→0.5.0;
- `deploy/app-update.json` 发布 0.5.0 条目(version_code 30、min_supported 21、真实签名 APK sha256);
- 以真实 `:app:assembleRelease` 产物核对 APK versionCode==30(即「以实际产物为准」的最终核验)。

**顺带发现(不属本工单,未改动):** `deploy/test-package-nas-lan-apk-download.sh`(不在 CI)在 HEAD 上已失败——其 fixture 以 `LEZI_SYNC_VERSION=9.9.9` 打包 version_name `0.3.5` 的 APK,被后加的「app-update version_name must match the package version」门拒绝;属陈旧脚本,与本次改动无关。
