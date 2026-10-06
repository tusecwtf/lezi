# 03: 0.5.1 NAS 与 APK 同步升级

**What to build:** 家庭在同一维护窗用上 0.5.1：两台装渠道 APK，NAS 换成同号服务端。
新设备从目录装到 0.5.1。偶尔漏升的旧包仍能同步（最低 versionCode 不抬），但旧死锁合同
还在，靠手升。本票不自行执行 NAS 部署。

**Blocked by:** 01 开放睡眠等闭合后再自动收口、02 来源角色开睡不再卡住醒来

**Status:** done(NAS 0.5.1 普通 CD 已入档；等两台装 APK + 睡下/醒来冒烟)

- [x] 渠道目标 0.5.1 / versionCode 31；0.5.0 成为已发布升级源
- [x] 服务端同号 0.5.1；Room 与本地数据契约不移动；同步最低 versionCode 仍 21
- [x] 更新目录与真实签名 APK 对得上
- [x] 隔离实例验收：双开睡不收口、都醒来后收口、醒来卡可改两端时间
- [x] propose CD，等 owner 确认后再换 NAS；普通 CD 不替换 TLS
- [ ] 窗口内两台都装 0.5.1；推荐先 APK 后 NAS
- [ ] 冒烟：睡下/醒来底栏与时间轴一致，醒来卡能改睡下和醒来

## 落地记录

### 1. 原子版本定名包

- `app/build.gradle.kts` versionCode 30→31、versionName `"0.5.0"`→`"0.5.1"`
- `config/android-release-compatibility.json`：0.5.0/30（Room 29、contract 6）入
  `released_versions`；`upgrade_target` → 0.5.1/31（Room 29、contract 6 不变）
- pin：`LocalDataContractSixCatalogTest`（target 31/0.5.1，上一发布 30/0.5.0，
  回滚源 29/0.4.8）；device `LocalDataContractMigrationDeviceTest`（6..30、target 31）
- `tools/lezi-sync/Cargo.toml` + `Cargo.lock` → `0.5.1`
- NAS 打包身份：`package-nas.sh` / `validate-nas-package.sh` 增加 0.5.1/31/floor21/
  schema13，回滚源 0.5.0/13；`test-nas-release-identity.sh` 现身份 + 0.5.0 回归
- `deploy/app-update.json`：version_code 31、version_name 0.5.1、min_supported 21、
  真实签名 APK sha256 `a395bd12c3e49665dd4c9ed1195efae9c0719076e1da4254804f6698db623ece`
- 规格身份钉随 catalog 移到 0.5.1/31；协议代 / wire / floor 21 不动

**真实产物核对：** `./gradlew :app:assembleRelease` 成功；apkanalyzer 实读
`android:versionCode="31" android:versionName="0.5.1"`；signer digest 与
`config/release-apk-signer-sha256.txt` 一致。

### 2. 隔离实例（零 NAS / 零 ssh / 零家庭证书）

复用 01 store 夹具，不新造门面：

- `auto_align_does_not_group_two_nearby_open_sleeps`
- `auto_align_groups_two_nearby_sleeps_after_both_have_legal_wakes`
- `auto_align_does_not_group_when_only_one_nearby_sleep_has_woken`
- `auto_align_still_groups_two_already_closed_nearby_naps`

醒来卡两端可改由 02 JVM 缝证明（`CareLogRecordWriteTest` /
`QuickRecordDraftSleepIntervalTest`）；本票未重做 Compose 设备测。
既有 `CareLogRealServerSeamTest` 在 0.5.1 身份下仍绿。

### 3. 门禁

- Rust：`cargo fmt --all -- --check`、`cargo test --locked`、
  `cargo clippy --all-targets --all-features -- -D warnings`
- JVM：catalog pin + 01/02 缝 + `CareLogRealServerSeamTest`
- NAS 身份：`./deploy/test-package-nas-app-update.sh`、
  `./deploy/test-nas-release-identity.sh`
- 全套 `./gradlew test`：本票引入的 catalog/pin 绿。失败项与 02 收敛时相同、先于本票存在
  （`:app:compileReleaseUnitTestKotlin` `BabyMoveSurfaceTest` 缺 `babyLocalLayout`；
  `RealSyncPortCustomItemTest` 两条；`ReplicaSyncEngineLocalWriteNoPullTest.wakeLiveAndTombstoneCommitDirectlyButDanglingOrMediaRootsWait`）。未扩范围去修。

家庭两台安装与睡下/醒来冒烟仍待本维护窗收口。

### 4. NAS 普通 CD（2026-09-12，owner 确认后执行）

前置：Rust 三件套绿；签名 APK sha256 与 `app-update.json` 一致；`age` + recipients 就位；
`LEZI_ALLOW_TLS_BOOTSTRAP` / secret recovery/reseed/forward 均未设置。

- 替换前 live：`https://192.168.50.4:8765/health` → version `0.5.0` / schema 13
- `./build-image.sh` → `lezi-sync:0.5.1` linux/amd64
- `./deploy/push-and-deploy.sh` → 包 `dist/lezi-sync-0.5.1-nas`，scp
  `13096920600@192.168.50.4:10000`，age-backup secret+TLS，stop/rm + replace 容器
- 普通 CD 复用既有 TLS：pre/post certificate SHA-256
  `75023c71d8ca918a42fe4f058aab8faf85db3f02b9a69bfb6522951ce362da9e`、SPKI
  `bd07d8645ed3b7adead162eca454373aee4007b0a35aa7c62caf7d8ac0cb3215` 相等
- 替换后独立探测：LAN HTTPS `/health` `/ready` 均为 `0.5.1` / schema 13 /
  `causal_sync_v2`；容器 `lezi-sync:0.5.1` running
- 邀请安装通道 `http://192.168.50.4:8767/download/lezi.apk` HTTP 200，sha256 与
  签名件 `a395bd12…` 一致

两台家庭机尚未装 0.5.1 APK；睡下/醒来底栏与醒来卡冒烟等装完再做。
