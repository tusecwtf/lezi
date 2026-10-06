# 06: 0.5.2 NAS 与 APK 同步升级

**What to build:** 家庭在同一维护窗用上 0.5.2：两台直接装渠道 APK（跳过尚未安装的 0.5.1），
NAS 换成同号服务端。新设备从目录装到 0.5.2。同步最低 versionCode 不抬。本票不自行执行
NAS 部署。

**Blocked by:** 01 超时以失败离开、02 履行候选终态、03 永久媒体码终态、04 状态与锁收口、
05 服务端 readiness 与信封

**Status:** done(NAS 0.5.2 普通 CD 已入档；等两台装 APK + 冒烟)

- [x] 渠道目标 0.5.2 / versionCode 32；0.5.1 / 31 移入 `released_versions`
- [x] 服务端 Cargo 同号 0.5.2；Room 29、本地数据契约 6、server schema 13、同步 floor 21 不动
- [x] `package-nas.sh` / `validate-nas-package.sh` / `test-nas-release-identity.sh` 身份加 0.5.2/32，
      回滚源 0.5.1
- [x] `deploy/app-update.json` 与真实签名 APK sha256 对得上；`test-package-nas-app-update.sh`
- [x] 隔离实例验收：候选引用未被接受的记录 → 一轮后待同步归零；`media_uuid_conflict` → 终态；
      超时后信号仍被消费
- [x] propose CD，等 owner 确认后再换 NAS；普通 CD 不替换 TLS；pre/post 证书 SHA-256 与 SPKI
      相等
- [ ] 窗口内两台都装 0.5.2；推荐先 APK 后 NAS
- [ ] 冒烟：每台发一条带照片记录 + 完成一条计划，浅状态归零；切后台再回来不卡「正在同步」

## 落地记录

### 1. 原子版本定名包

- `app/build.gradle.kts` versionCode 31→32、versionName `"0.5.1"`→`"0.5.2"`
- `config/android-release-compatibility.json`：0.5.1/31（Room 29、contract 6）入
  `released_versions`；`upgrade_target` → 0.5.2/32（Room 29、contract 6 不变）
- pin：`LocalDataContractSixCatalogTest`（target 32/0.5.2，上一发布 31/0.5.1，
  回滚源 30/0.5.0）；device `LocalDataContractMigrationDeviceTest`（6..31、target 32）
- `tools/lezi-sync/Cargo.toml` + `Cargo.lock` → `0.5.2`
- NAS 打包身份：`package-nas.sh` / `validate-nas-package.sh` 增加 0.5.2/32/floor21/
  schema13，回滚源 0.5.1/13；`test-nas-release-identity.sh` 现身份 + 0.5.1 回归
- `deploy/app-update.json`：version_code 32、version_name 0.5.2、min_supported 21、
  真实签名 APK sha256 `809e18e8a07c586adef931d40f579d10a8e799b550149016b01d570bcc97e0c9`
- 规格身份钉随 catalog 移到 0.5.2/32；协议代 / wire / floor 21 不动

**真实产物核对：** `./gradlew :app:assembleRelease` 成功；apkanalyzer 实读
`com.lezi.babylog` / `android:versionCode="32"` / `android:versionName="0.5.2"`；
signer digest 与 `config/release-apk-signer-sha256.txt` 一致。

### 2. 隔离实例（零 NAS / 零 ssh / 零家庭证书）

复用 01–05 已有最高缝，不新造门面：

- `RealSyncPortCarePlanFulfillTest`：409 写 abandoned 回执、待同步归零、无卡片、同批续发、
  成员非权威、第二轮不再 stage
- `MediaReceiptFaultAcceptanceTest` / `RealServerMediaReceiptFaultSeamTest`：
  `media_uuid_conflict` 终态 + 第二轮不再 commit 同信封
- `RealSyncPortForegroundCycleTest.requestSyncForegroundTimeoutLeavesConsumerAliveForLocalWrite`：
  超时后信号仍被消费

P0-A IsolatedLeziSyncServer 真 409 缝仍推迟（票 02）；SyncRig 覆盖上述行为。

### 3. 门禁

- Rust：`cargo fmt --all -- --check`、`cargo test --locked`、
  `cargo clippy --all-targets --all-features -- -D warnings`
- JVM：catalog pin + 上列隔离缝
- NAS 身份：`./deploy/test-package-nas-app-update.sh`、
  `./deploy/test-nas-release-identity.sh`

### 4. NAS 普通 CD（2026-09-13，owner 确认后执行）

前置：Rust 三件套绿；签名 APK sha256 与 `app-update.json` 一致；`age` + recipients 就位；
`LEZI_ALLOW_TLS_BOOTSTRAP` / secret recovery/reseed/forward 均未设置。

- 替换前 live：`https://192.168.50.4:8765/health` → version `0.5.1` / schema 13
- `./build-image.sh` → `lezi-sync:0.5.2` linux/amd64
  （image config `sha256:5d6208646201dc53a66d5e919503eeee1120860e6cd8738cb0ad541678a64cc8`）
- `./deploy/push-and-deploy.sh` → 包 `dist/lezi-sync-0.5.2-nas`，scp
  `13096920600@192.168.50.4:10000`，age-backup secret+TLS，stop/rm + replace 容器
- 普通 CD 复用既有 TLS：pre/post certificate SHA-256
  `75023c71d8ca918a42fe4f058aab8faf85db3f02b9a69bfb6522951ce362da9e`、SPKI
  `bd07d8645ed3b7adead162eca454373aee4007b0a35aa7c62caf7d8ac0cb3215` 相等
- 替换后独立探测：LAN HTTPS `/health` `/ready` 均为 `0.5.2` / schema 13 /
  `causal_sync_v2`；容器 `lezi-sync:0.5.2` running
- 邀请安装通道 `http://192.168.50.4:8767/join` 展示 0.5.2；
  `/download/lezi.apk` HTTP 200，sha256 与签名件 `809e18e8…` 一致

两台家庭机尚未装 0.5.2 APK；带照片记录 + 完成计划 + 切后台冒烟等装完再做。
