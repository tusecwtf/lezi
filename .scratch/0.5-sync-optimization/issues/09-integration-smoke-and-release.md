# 09: 集成冒烟与 0.5.0 发布

**What to build:** 全部工单落地后的端到端验证与发布:先在开发者隔离实例做双端全链联调
(证书测试隔离规则适用),数字与 01 基线对照入档;然后在 P2-7 操作者窗口解除后,按
propose-then-confirm 流程走 NAS 发布与前后端冒烟。本工单不自行执行 NAS 部署。

**Blocked by:** 02, 03, 04, 05, 06, 07, 08

**Status:** done(隔离实例 + NAS 0.5.0 普通 CD 均已入档)

- [x] 隔离实例双端联调实跑并记录:安静前台返回轮 0 数据请求、census 每 head 命中、缺图 2 并行、心跳 60/300 节奏、更新检查节流(观察方式与引用见落地记录 §2:真线观测覆盖安静轮线成本、census 每 head 恰一次重建、心跳端点三键 no-change 形态;缺图 2 并行与更新检查节流为 port 级行为,引用既有 fake-backend 契约测试,未伪造端到端数字)
- [x] 与 01 基线的前后对照表(空页 pull 服务端耗时、整轮墙钟、探针数)入档 design §7
- [x] Rust 三件套 + 全部 JVM 测试 + lint 最终全绿记录(见落地记录 §4)
- [x] P2-7(操作者 docker 权限窗口)解除后:propose CD 并等 owner 确认,不自行执行 —— owner 于 2026-09-06 确认执行 0.5.0 CD;见 §8
- [x] NAS 发布前后证书 SHA-256 与 SPKI 逐一比对(TLS 身份不变量)—— 见 §8
- [x] 冒烟报告:门禁结果、部署版本/包、协议、客户端 smoke 路径(或明确阻塞项)—— 隔离实例见 §2;NAS 见 §8
- [x] 0.4.8→0.5.0 升级路径冒烟:预期行为(含 generation 热恢复语义不变)(佐证测试点名见落地记录 §5)

## 落地记录(2026-09-06,收官轨)

### 1. 原子版本定名包(commit `946636ce`)

按 08 落地记录的清单单一 commit 落地 0.5.0 版本身份,与 R 轨描述核对后**以实际代码为准**
执行(出入见 §6):

- `app/build.gradle.kts` versionCode 29→30、versionName "0.4.8"→"0.5.0";
- `config/android-release-compatibility.json`:0.4.8/29(room 29、contract 6)入
  `released_versions`,`upgrade_target` → 0.5.0/30(room 29、contract 6 不变,零 schema 移动);
- pin 测试随 catalog 新值更新:`sync` 侧 `LocalDataContractSixCatalogTest`
  (upgrade_target 钉 30/0.5.0,上一发布钉 29/0.4.8,回滚源 28/0.4.7 保留)、
  `app` 侧 androidTest `LocalDataContractMigrationDeviceTest`(6..29、target 30);
- `tools/lezi-sync/Cargo.toml` + `Cargo.lock` version → 0.5.0;
- `deploy/app-update.json` 0.5.0 条目:version_code 30、version_name 0.5.0、
  min_supported 21、真实签名 APK sha256 `99da420de73de2a8b085b…4fa4c4228`。

**真实产物核对**:`./gradlew :app:assembleRelease` 成功(preBuild 的
`validateAndroidReleaseCompatibilityCatalog` / `validateAndroidAppUpdateMetadataCompatibility`
fail-closed 门全绿),apkanalyzer 实读签名件
`android:versionCode="30" android:versionName="0.5.0"`,即「以实际产物为准」的最终核验。

### 2. 隔离实例双端联调(真实 0.5.0 服务端二进制 × 真实客户端栈)

- 服务端:`cargo build --release --locked` 产物(`LEZI_SYNC_BIN` 指向),
  mktemp 数据根 + loopback 自签证书(证书测试隔离合规,零 NAS/零 ssh/零 docker);
- 客户端:真实 `ReplicaSyncEngine` + `HttpSyncBackend`;观察仪 = 记录每个转发请求的
  loopback TLS 代理;服务端逐请求延迟行经 `RUST_LOG=debug`(tower-http)落 server.log;
- 新增测试:`sync/src/test/.../RealServerQuietRoundSmokeTest.kt`;
  既有真实服务端 seam 族同二进制全部通过:
  `RealServerMediaReceiptFaultSeamTest`(sync)、
  `CareLogRealServerSeamTest` / `CareLogRealServerSeamNwayMatrixTest` /
  `CareLogRealServerSeamDeleteRestoreAclTest` / `CareLogRealServerSeamResourceSaturationTest`(domain)。

真线观测(1200 实体发布后,详见 design §7「09 集成联调观察」表):

- **census 每 head 恰一次重建**:追赶轮新 head 首页 189–290ms(含 200 行传输+重建),
  同 head 后续页 1–6ms、稳定轮每轮 1 页且 1ms(命中);两次稳定头整轮 pull 页数 1==1、
  零 rewalk 零诊断 —— 双侧 census 缓存联合正确(漂移即 cursor=0 全量重走,页数必变);
- **安静轮线成本**:完全收敛后的 Foreground 轮线上恰为 `POST /v1/sync/handshake` +
  `GET /v1/pull`(0 bundle、0 媒体传输);port 层 tip-skip 将这 2 个请求也省去,
  由 `RealSyncPortTipSkipTest.quietForegroundReturnSkipsHandshakeAndPullWithZeroDataRequests`
  等一票否决矩阵钉住(0 数据请求);
- **心跳三键**:真实 `GET /v1/sync/heartbeat` 对收敛会话返回
  (generation, headRev, directoryGeneration) 与会话全等的 no-change 形态(tip-skip 证据臂);
  60/300 节奏常量与退避调度由 `SyncHeartbeatEngineTest` /
  `SyncHeartbeatPolicy` 断言 + `RealSyncPortHeartbeatLoopTest` 钉住
  (真服务器节拍观测按工单约定允许引用契约测试,不强求长时实跑);
- **缺图 2 并行 / 更新检查节流**:port/引擎级行为合同由 B 轨媒体测试
  (`attemptAndByteCapsHoldWithTwoWorkersDownloadingConcurrently`、
  `singleNon404FailureCancelsInFlightWorkerAndVoidsTheCycle`、
  `cycleBudgetExhaustedDuringBackfillFailsCycleAndLeavesMediaQueued` 等)与
  `RealSyncPortAppUpdateTest` 节流回归组钉住;未伪造端到端数字。

### 3. 终树复测(01/02 度量在 0.5.0 定名树上复跑)

- store 级:`census_rebuild_median_at_1k_rows` 502.8µs、`…at_10k_rows` 4.318ms
  (与 01/02 的 0.60–0.68ms / 4.4–5.5ms 同量级,机时波动);
- e2e:`pull_census_on_off_end_to_end_delta` rows=1001、51 对:
  census_on 608.1µs / off 591.4µs、**delta=16.7µs(噪声级)**、cold_first 1.884ms
  —— 与 02 的「差值≈0」结论一致;Tier 2 复活线(>50ms@1k)保持 ~100× 余量,维持挂起。

### 4. 终局门禁(2026-09-06,全绿)

1. `cargo fmt --all -- --check` — PASS;
2. `cargo test --locked` — exit 0(6 个测试二进制全 ok);
3. `cargo clippy --all-targets --all-features -- -D warnings` — 干净完成;
4. `./gradlew test` — BUILD SUCCESSFUL(含新增 smoke 与全部 JVM 契约测试);
5. `./gradlew lintDebug` — BUILD SUCCESSFUL。
另:`bash deploy/test-nas-release-identity.sh` exit 0(0.5.0/30 主身份 + 0.4.8/29 回归)、
`bash deploy/test-package-nas-app-update.sh` exit 0(要求 catalog upgrade_target ==
Cargo.toml version 的配对门在 0.5.0 上成立)。

### 5. 0.4.8→0.5.0 升级路径佐证(测试点名)

- 零 wire:`api.rs::pull_census_cache_keeps_envelopes_byte_identical`(golden envelope
  逐字节)、store 级 `census_cache_tests::cache_hit_serves_bytes_identical_to_full_recompute`;
- 升级链:catalog 移位后 `AndroidReleaseCompatibilityCatalogTest`
  (6..29 每个发布版独立可达最新 APK、floor 不变)、`LocalDataContractSixCatalogTest`
  (0.4.0 wire 身份与 sync floor 钉死不动);
- generation 热恢复语义不变:`RealSyncPortDisasterRestoreTest`、
  `RealSyncPortLocalWriteDeferFullResyncTest`、conflict-v2 契约 fixture
  (`conflict_v2_contract_fixture::shared_corpus_conforms_to_the_frozen_schema_and_cross_case_invariants`);
- 发布身份:`deploy/test-nas-release-identity.sh`(0.5.0/30/floor21/schema13 +
  回滚源 0.4.8/13,错配逐项 fail-closed)。

### 6. 与 R 轨清单的出入(以实际代码为准,已如实记录)

- `deploy/app-update.json` 条目**无 size 字段**:R 轨清单提及「size 按产物实填」,但
  0.4.8 条目结构、`AppUpdateMetadata` 与 `package-nas.sh` 校验面均无 size 字段 ——
  按既有结构执行,不擅自加字段(打包门只认 package_name/version_code/version_name/
  min_supported_version_code/sha256/release_notes);
- pin 测试除 `LocalDataContractSixCatalogTest` 外,androidTest
  `LocalDataContractMigrationDeviceTest` 也钉 catalog(6..28→6..29、target 29→30),
  随本 commit 一并更新;
- `Cargo.lock` 的 `lezi-sync` 版本条目需与 Cargo.toml 同步(`--locked` 门),R 清单未列;
- 版本身份 bump 是**7 文件**原子单元(上两点 + gradle/catalog + app-update.json)。

### 7. NAS 发布提案(不执行;待 owner 确认 P2-7 操作者窗口)

NAS 发布需 owner 确认 P2-7(操作者 docker 权限窗口)后走 propose-then-confirm:
`./build-image.sh && ./deploy/push-and-deploy.sh`(在 `tools/lezi-sync/`)。该脚本会
构建 linux/amd64 镜像 `lezi-sync:0.5.0`、scp 到家庭 NAS(`ssh -p 10000
13096920600@192.168.50.4`)、age 备份活根 secret+TLS,并 **stop/rm + 替换**容器
`lezi-sync`(数据 bind 不动)。发布前后须逐一比对证书 SHA-256 与 SPKI
(`LEZI_EXPECTED_CERTIFICATE_SHA256` / `LEZI_EXPECTED_SPKI_SHA256` 生产 pin),TLS
身份不变量任何异常即中止;发布后联调按 AGENTS.md 流程:LAN HTTPS
`https://192.168.50.4:8765` 的 /health、/ready,客户端指向该 origin 做最小 smoke。
本工单不自行执行;NAS 相关 checklist 保持未勾,标注「待 owner 确认」。

### 8. NAS 0.5.0 普通 CD(2026-09-06,owner 确认后执行)

活清单从 `lezi-sync:0.4.7` / schema 13 替换为 `lezi-sync:0.5.0` / schema 13(零 schema,非 cutover)。HEAD `11d2bce2`。未设 TLS bootstrap / secret forward / recovery / reseed。

**门禁(开发机,HEAD `11d2bce2`):**
- `cargo fmt --all -- --check` PASS
- `cargo test --locked` PASS
- `cargo clippy --all-targets --all-features -- -D warnings` PASS
- `./gradlew :app:assembleRelease` BUILD SUCCESSFUL; apkanalyzer:`com.lezi.babylog` / `0.5.0` / versionCode `30`
- 镜像 `lezi-sync:0.5.0` linux/amd64,image config `sha256:111ac8c35495ad12f5c45d586b45e8834be269bb745d0f9ae9ad90bcdb46cf5f`

**APK:** 为对齐 HEAD 客户端,重签了 release APK。`app-update.json` sha256 `99da420d…` → `5e623d066ac04a49468d61996dc99518f4f2509d46e28d73560e3fb4c32452fd`(本提交入仓;LAN 通道已按该哈希发布)。

**TLS 身份(替换前后相等):**
- OpenSSL cert SHA-256 fingerprint `9A:34:BA:5B:BB:24:42:E4:5A:7D:9D:05:60:56:B2:85:03:C4:D6:7F:BB:37:32:E4:78:A4:52:D0:A5:1D:37:F0`
- deploy-script certificate SHA-256 `75023C71D8CA918A42FE4F058AAB8FAF85DB3F02B9A69BFB6522951CE362DA9E`
- SPKI SHA-256 `BD07D8645ED3B7ADEAD162ECA454373AEE4007B0A35AA7C62CAF7D8AC0CB3215`
- 备份:`~/.config/lezi/backups/lezi-sync-0.5.0-credentials-20260906T152154Z-1402920.age`

**部署后 LAN 冒烟:**
- 容器 `lezi-sync:0.5.0` healthy; HTTPS `https://192.168.50.4:8765/health` → version `0.5.0`、schema 13、`causal_sync_v2`; `/ready` → ready
- `GET /v1/sync/heartbeat` 与 `GET /v1/app-update` 无 token → 401
- `http://192.168.50.4:8767/join` 展示版本 0.5.0;`/download/lezi.apk` sha256 与 metadata 一致
- 本会话未操作家庭手机;已加入设备需打开 app 走认证更新通道(横幅最迟 1 小时)
