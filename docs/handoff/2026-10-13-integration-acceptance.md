# 乐记 0.5.5 整合交接与验收

状态截点：2026-10-13，整合源码 `cf787d7a16dc6520a6fcab60b3e5221999c0d76c`。替换旧owned文件的持久回收修复已集成；尚需最终Android、双侧与设备证据后才能签收。以下未执行的命令是验收步骤，不表示已经发布、运行ADB或执行NAS操作。

## 当前结论

- 已实现：首批交互修补、搜索查询投影复用、摘要计算优化、固定容量双侧计时、不可变 schema 期望缓存、有界媒体流式落盘、blocking 工作持有原有锁、媒体路径 gate 回收。
- 已验证：整合 Rust `c5e541b595079fd915f44d0250417db3e10dd5de` 的 fmt、582 项测试和 warnings-as-errors Clippy；6 个常规忽略的计时测试另行执行。其 Rust 文件至上述整合源码不变。主机SQLite合同7项、最新owned-store21项（从cf787d7实际生产源码独立重跑）、probe8项、path-gate/ownership17项分别有证据；旧store+probe22项被21+8覆盖，不重复累加，不能合计为Android全套。
- 未完成：4项新增持久回收引擎回归实际执行、最终Android 全套/Room构建/同源双侧、完整制品清单、新发布与 CI、私有源回流、设备体验/PSS与 Android fsync 验收。
- 当前公开 master 仍为 `9e47fd6c9111eb5352cef585f4672a71fb578af4`；PR11 头 `c7461bcd8b2d52c482d14746aba380295626be25` 仅包含摘要首切片。该头 Android run 38349801373 的新 fixture 失败已有本地修复：整合提交 `bb52353c4ff31072248dfb7f375f13a3891e0137`，尚未发布/重获绿灯。
- 整合尚未发布，新CI尚未验证；私有回流未验证，必须保留私有未发布工作。没有 ADB/模拟器/NAS执行证据，未部署。

版本仍为 Android 0.5.5/code35、服务端0.5.5、wire0.4.0、Room29/本地合同6、server schema13/floor21。FULL、hash、鉴权/TLS、整页原子apply、媒体完整发布、本地优先和两模板等价合同不能为性能让步。

## 历史交接的适用范围

10/11 UX交接中的“11–14仅静态研究”是当时状态；10/13 observation中的367 library/4 ignored是观察切片状态，不是最新整合总数。摘要文档“不含完整流式”只描述首切片。server-hotpaths中的表格是schema-only实验。此处给出后续整合结果，旧数字不覆盖、不拼接成新结论。

[原15组验收表](2026-10-13-integration-checklist.md)逐项区分实现、测试、发布与设备待验收；[Rust整合报告和原始数据](../dev/evidence/integrated-2026-10-13/integrated-server-report.md)保留测量口径。

原1–6/8–9的生产与测试代码已在公开基线中核对存在；历史提交记录不能单独证明当前整树已测试。第7大字/IME、第10设备PSS、第15总交付仍开放。

## 性能证据与限制

同一Linux云主机、同工具链/profile，0/1000/10000合成记录，15轮交替基线/整合，共4500原始样本。每规模每版本为15个cold Store和15个cold init、600个warm Store、120个warm init。cold指进程内schema cache冷，OS页缓存未清空。

- warm Store中位下降76.7–77.8%，每规模15/15配对轮更快。
- 1000条cold server init：21.221→16.752ms，中位下降21.1%，15/15更快。
- 10000条cold init：105.234→103.251ms，仅8/15更快；p95/max 120.026→128.372ms，不声称稳定改善。15个cold样本的p95就是观测最大值，不是生产尾延迟估计。
- 首切片桌面JVM：32byte hex 7533.6→167.5ns；16MiB retained hash 14.780→12.956ms，分配16798392→9440B。
- 8MiB warm主机body→owned adopted file：12.229→9.849ms，分配16797360→20920B；基线为已摘要优化的c7461bc，包含候选marker/directory/file durability I/O，未包含网络/TLS、Room、路径gate、Android存储及后来旧文件replacement re-arm成本。

不把累计线程分配当作peak heap/RSS/PSS，不外推整App/完整同步/真机/NAS改善。rate limiter生产算法未改，两个被否定的实验不交付。Android quiet sampler仅为合成无照片的真实引擎+loopback TLS+服务端、内存DAO观察；不是Room或手机照片路径。

## 最终源码与制品冻结

以下运行时基点已经包含持久回收修复，4项新增引擎回归尚未执行。正式验收时记录实际完整40位SHA，确认干净树，再执行下列命令。所有日志、XML、基准raw、APK及服务端SHA必须归属于该SHA。若后续仅文档变化，注明运行时文件哈希等价；若运行时变化，补跑受影响门禁。

```bash
set -euo pipefail
test -z "$(git status --porcelain)"
export LEZI_SYNC_SOURCE_SHA="$(git rev-parse HEAD)"
printf 'SOURCE_SHA=%s\n' "$LEZI_SYNC_SOURCE_SHA"
java -version
./gradlew --version
rustc --version
cargo --version
openssl version
curl --version
sqlite3 --version
python3 domain/src/test/python/search_sql_contract_test.py
(cd tools/lezi-sync && cargo fmt --all -- --check)
(cd tools/lezi-sync && cargo test --locked)
(cd tools/lezi-sync && cargo clippy --all-targets --all-features -- -D warnings)
cargo build --manifest-path tools/lezi-sync/Cargo.toml --locked --bin lezi-sync \
  --target-dir "$PWD/tools/lezi-sync/target"
export LEZI_SYNC_BIN="$PWD/tools/lezi-sync/target/debug/lezi-sync"
sha256sum "$LEZI_SYNC_BIN"
./gradlew test --stacktrace
./gradlew :sync:testDebugUnitTest --rerun-tasks \
  --tests com.lezi.babylog.sync.RealServerQuietRoundSmokeTest \
  --tests com.lezi.babylog.sync.media.RealServerMediaReceiptFaultSeamTest
./gradlew :domain:testDebugUnitTest --rerun-tasks \
  --tests 'com.lezi.babylog.domain.CareLogRealServerSeam*'
LEZI_SYNC_PERF_SAMPLE=1 ./gradlew --rerun-tasks :sync:testDebugUnitTest \
  --tests com.lezi.babylog.sync.performance.RealServerSyncPerformanceSampleTest
./gradlew :app:assembleDebug assembleDebugAndroidTest --max-workers=2
find app core designsystem domain feature -path '*/build/outputs/apk/*' -name '*.apk' \
  -type f -exec sha256sum {} +
```

不要设置NAS环境变量；isolated harness仅使用合成临时数据根。full Gradle与分组重跑XML可能覆盖，应在每组后分别归档。核对每组实际test count/failures/errors/skipped；缺二进制/openssl造成跳过必须补齐重跑。采样打印的source SHA应等于真实checkout，PR merge-ref的GITHUB_SHA另记，不能替换。CI制品当前保留3天，应及时下载保全。

根目录不带项目路径的assembleDebugAndroidTest会选择各子项目中存在的同名任务，仅编译/打包instrumentation，不运行或安装到设备。后续本地提交 `5631ec1721103c217834581330f9aa49a67cbb27` 已配置CI执行 `./gradlew test :app:assembleDebug assembleDebugAndroidTest`，尚未发布/执行，仍保留原workers和45分钟上限，需观察内存与耗时。手动命令中的max-workers=2只是低并发选择，不冒称CI配置。设备测试APK必须记录实际manifest package/targetPackage、runner、SHA；app debug为com.lezi.babylog.debug，库测试APK不能当产品包。以上构建命令未证明instrumentation运行成功，也不证明已签名release可部署。

## 用户手动设备验收

只在用户自行选择的API26+隔离测试机上手动执行，不能包含真实家庭数据。不自动挑选设备，以下步骤没有执行记录。先确认最终APK/source hash、设备型号/API/刷新率/屏幕dp/字体、synthetic记录数、三张合成图片各字节数及SHA；先记录原设置，测试后恢复。

用户可手动用ADB选择设备和读取基础信息：

```bash
adb devices -l
# 将序列号替换为你确认的隔离测试机；不要直接复制占位符。
export ANDROID_SERIAL='填写已确认的测试设备序列号'
adb -s "$ANDROID_SERIAL" shell getprop ro.product.model
adb -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk
adb -s "$ANDROID_SERIAL" shell wm size
adb -s "$ANDROID_SERIAL" shell wm density
adb -s "$ANDROID_SERIAL" shell settings get system font_scale
adb -s "$ANDROID_SERIAL" shell settings get global animator_duration_scale
adb -s "$ANDROID_SERIAL" shell pm list instrumentation
```

只在确认签名/包名/无需删除数据后选择安装；不要为了通过测试清除真实应用数据或卸载真实产品。Gradle连接测试会安装目标/测试APK，用户需先确认这一点。按下列精确类执行并保存XML，失败保留原始证据：

| 模块 | class参数 |
|---|---|
| core:ui | com.lezi.babylog.core.ui.CameraCaptureRegistryDeviceTest |
| core:ui | com.lezi.babylog.core.ui.camera.CameraPermissionRecoveryDialogDeviceTest |
| designsystem | com.lezi.babylog.designsystem.ActionStateComponentsDeviceTest |
| feature:family | com.lezi.babylog.feature.family.baby.BabyProfileValidationDeviceTest |
| feature:family | com.lezi.babylog.feature.family.baby.AvatarCameraPermissionRecoveryDeviceTest |
| feature:family | com.lezi.babylog.feature.family.baby.OwnedCameraAvatarEntrypointsDeviceTest |
| feature:family | com.lezi.babylog.feature.family.CurrentBabySwitchFeedbackDeviceTest |
| feature:family | com.lezi.babylog.feature.family.MemberApprovalWaitingDeviceTest |
| feature:family | com.lezi.babylog.feature.family.baby.BabyEditDraftDeviceTest |
| feature:log | com.lezi.babylog.feature.log.composer.RecordComposerDiscardDeviceTest |
| feature:log | com.lezi.babylog.feature.log.composer.SleepComposerMotionDeviceTest |
| feature:search | com.lezi.babylog.feature.search.SearchRefreshStateDeviceTest |
| feature:summary | com.lezi.babylog.feature.summary.SummaryChartDailyAccessibilityDeviceTest |
| domain | com.lezi.babylog.domain.CareLogSearchRoomDeviceTest |

例如（其他行替换模块及完整class）：

```bash
./gradlew --max-workers=2 :feature:family:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.lezi.babylog.feature.family.baby.BabyEditDraftDeviceTest
./gradlew --max-workers=2 :domain:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.lezi.babylog.domain.CareLogSearchRoomDeviceTest
```

手工验收标准：

1. warm/journal×明暗×l1/l2/l3，360dp与短视口/IME开闭，文字不截断关键动作、CTA可达且有完整读屏语义。
2. 非法出生体重原文保留、独立字段错误不互相清除；脏草稿有确认，净草稿直关，保存不可重入，旋转后状态正确且成功只消费一次。
3. 相机首次拒绝/再次拒绝/设置往返/相册恢复，不自动再拍照，不丢草稿；取消不分配捕获文件，owned capture不泄漏或误删。
4. 宝宝切换失败仍是原范围且能重试；旧查询、延迟宝宝/ACL结果不污染新范围，刷新失败旧结果只读，重试恢复新权限。
5. 系统动画scale0/0.5/1；scale0静态且焦点/确认正常。TalkBack逐日图表导航、首末日/单位/缺失与真实零值无歧义。
6. 三图进入、快滑、退出后5s/30s并列记录PSS与缓存snapshot；先明确五条目标路径的逐条操作脚本，不能临时任选五条冒充原计划。每路径先5次预热、至少30次探索样本，多轮检查退出pins归零、unpin后缓存回原约束、PSS不无界上升。

PSS用户读取示例：

```bash
adb -s "$ANDROID_SERIAL" shell dumpsys meminfo com.lezi.babylog.debug
```

缓存snapshot是同模块internal按需入口，无已交付公开按钮或ADB命令；需经review的debug/test harness明确采集，不能假称dumpsys能得到pin。保存原始PSS而非只截总数。动态布局/屏幕录像可辅助，但不替代数值与实际操作结果。

Android目录FileChannel.force与崩溃/重启恢复另列验收：在隔离fixture中验证下载中断、写失败、页apply失败/成功后的回收、替换后清理失败再重启、活动引用/causal holder保留、所有者marker有界恢复。正常应用重启不等于电源丢失；没有经审定故障注入就记录“断电耐久未测”，不得破坏真实数据。

## NAS 验收与部署边界

本轮没有NAS数据。先完成同源isolated门禁，NAS不能用于排除协议/fixture问题。仅在用户选择并手动执行时，对既有目标做读操作；实际主机、端口、数据路径取已有私有配置，不使用文档示例地址碰运气，不输出secret/private key。

- 读取现有版本、/health、/ready、TLS证书SHA-256与SPKI，记时间和目标。TLS文件从容器uid10001视图读取；读不到则停止，不能chmod、重建、替换或重新信任来让测试通过。
- 客户端实际LAN HTTPS端口为8765；8766仅容器内部HTTP readiness，不能SSH curl主机8766当服务失效证据。证书验证按已有信任记录进行。
- 用户只想体验既有NAS时，不自动部署新服务、不进行证书生成/TOFU变更/过期/不匹配实验。那些实验留在独立临时harness。
- 如确需部署，单独确认窗口：会构建linux/amd64镜像、上传、新建加密secret+TLS备份、stop/rm并替换lezi-sync容器；数据bind不得删。先验证age及收件人、release APK签名/SHA/app-update元数据、镜像平台、完整package inventory，再走正式push-and-deploy入口。
- 部署前后证书SHA及SPKI必须完全一致；证书半缺、失效、key不匹配、备份失败、lease冲突、镜像/版本/签名漂移均中止。不得直接调用底层remote-deploy绕过外层保护，不恢复已撤销VPS路径。
- 部署后才记录包/镜像id、版本、health/readiness、TLS身份、受影响客户端smoke与失败回退结果；无结果记pending，不将CI绿灯等同部署成功。

完整操作合同以仓库AGENTS.md及tools/lezi-sync/deploy/DEPLOY.md为准。本交接中的设备与NAS操作均留给用户手动执行。

## 私有源回流和便携补丁验收

公开镜像是脱敏快照，与私有真历史不同，不能直接把公开branch merge到私有master。在确认私有源目标及发布安排后再执行：

1. 记录私有master完整SHA、dirty状态、所有未发布提交的完整SHA与内容；先保留恢复引用。检查历史UX与公开基线内容对应关系，不仅比较提交名。
2. 对最终公开整合SHA，基于公开9e47fd6生成文件级binary-capable diff及文件清单/哈希，在仓库外保留补丁SHA256、基线SHA、目标SHA与门禁结果。检查不含真实地址、账号、凭据、私有history或本机绝对路径。
3. 在新的隔离私有worktree先git apply --check；已有未发布提交或其它私有差异逐文件协调，禁止覆盖/丢弃为让补丁通过。分别记录等价应用、已存在、冲突与人工解决。
4. 公共可移植性：独立干净9e47fd6 worktree应用该补丁，git diff --check，逐文件blob/SHA与最终整合树核对，确保没有遗漏untracked源码或只依赖原工作目录的文件。
5. 私有回流后执行适配后的同等Android/Rust/双侧门禁；若需要通过原pre-push隐私过滤发布，先验证脱敏snapshot与预期公开运行时代码一致。不要普通push私有全历史到公开仓库。
6. 最终记录私有提交、公开快照、PR/merge SHA、两套CI run、服务二进制和APK哈希之间映射。未获完整可核对映射前，状态仍为“回流未验证”。

补丁应用成功仅是可携带性，不能代替编译、Room、双侧或设备验收。尚未完成私有回流或发布验证。

构建环境补充：当前AGP8.7.3没有显式buildToolsVersion，默认SDK Build Tools34.0.0，应确认预装或可自动下载；compileSdk35与Build Tools版本不是同一概念。无需设备镜像、NDK/CMake或release签名材料来完成上述debug构建。依据：[Gradle多项目task selector](https://docs.gradle.org/current/userguide/command_line_interface.html#sec:executing_tasks_in_multi_project_builds)、[AGP8.7兼容表](https://developer.android.com/build/releases/agp-8-7-0-release-notes?hl=en)。
