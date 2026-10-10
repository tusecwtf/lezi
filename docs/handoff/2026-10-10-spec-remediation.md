# PR #6：rebase 后剩余验证与手动 ADB handoff

本文件是可执行的验收交接，不是“94/94 已通过”声明。ADB、模拟器、安装与设备测试由用户手动执行；本文件所列命令均为待执行步骤。本次文档准备没有执行它们。所有测试只使用合成数据与一次性隔离目标。

## 1. 版本、已通过证据和本次重验边界

- [Draft PR #6](https://github.com/tusecwtf/lezi/pull/6)，开发分支 `codex/lezi-spec-remediation-eee3749`。
- 本次目标基线为 master `1fc8d6be46229bb02b4f0a3d2cdb6cfb6dfc9fd1`；最终 rebase 后源码 SHA/tree 及 CI 链接以 PR 的实际提交与运行记录为准。执行人必须把它们记录到下面的验证登记中，不能把旧 HEAD 当 latest。
- Rebase 前公开提交 `89eb32614a6b801fdcd83ae52ccaaba69a030d83`，tree `a2afc28da7cd2241c53fb3c05f7f517051613246`。以下数字仅属于旧提交/同树检查：普通 Android **6,577 passing report entries**、零 fail/error/skip，门禁允许 Gradle cache，原普通 job 完整日志未取回，不能说全部 fresh；Rust **684 PASS / 6 intentionally ignored**；这 6 个既有诊断另外以 fresh release 二进制单跑 **8 次均 PASS**，不删除 ignore；必需双端 **34 fresh PASS**（Sync16/Domain18），有精确源码构建二进制及摘要。
- 同树本地 focused：4 个完整类 94 个 unique 方法、Debug/Release 合计188次；DNS/Seam/Haptics 10个控制方法，合计 **104 unique / 198 variant executions**，零失败/跳过。四个 async 方法另24次重复、IdentityClear另6次重复；重复与整类重叠，不相加到 CI 或 unique 数。源码/Hilt 编译及 TLS assets 已检查，但未打包这些设备测试 APK、未执行设备用例。
- 历史 CI：[Rust](https://github.com/tusecwtf/lezi/actions/runs/38055152770)、[Android 与 fresh paired](https://github.com/tusecwtf/lezi/actions/runs/38055152675)。旧通过与新 rebase 的受影响输入不可自动等同。
- 本次rebase核对：生产Kotlin/Rust及部署blob与旧89eb相同；变更包含15个Android测试文件的合成host `.10→.4`、镜像/ignore说明、历史scratch清除和两CI的setup-android v4及其合同。此差异说明不替代新CI；实际最终提交仍应核验。
- Rebase/conflict resolution 后，先对照 [AGENTS](../../AGENTS.md) 分类实际差异与 [CI 工作流](../../.github/workflows/)。受影响的 JVM/服务器/双端/编译/Hilt/lint/APK/R8 门须在精确新源码重验；不受影响的证据只能在注明路径/hash对比依据后引用。文档-only 修改不要求无理由叠加所有运行门，但不能把旧数字复制成新运行成绩。
- 使用 tracked `docs/handoff/`；上游已删除的历史 `.scratch/` 不恢复、不强制加入。产品依据仍是 [docs/spec](../spec/)、[CONTEXT](../../CONTEXT.md)、[ADR](../adr/)；不能把缺失历史票据链接当验收证据。

验证登记（每组单独填写，未填就是未验证）：

| 项目 | 实际值 |
|---|---|
| rebase 后冻结 SHA / tree | 待登记 |
| 对照 master SHA / merge-base | 待登记 |
| 新 CI run / job / artifact SHA256 / cache 范围 | 待登记 |
| 受影响检查、重新执行结果与未执行原因 | 待登记 |
| API26 每方法结果 / API35 每方法结果 | 待登记，不能预填 PASS |
| APK SHA256 / manifest runner / targetPackage / flags | 待登记 |

从新目录取源码并冻结精确提交；下面变量应来自 PR 本次交付实际值，不能填旧89eb：

```bash
set -euo pipefail
: "${EXPECTED_HEAD:?Set the reviewed post-rebase commit SHA}"
: "${EXPECTED_TREE:?Set its verified tree SHA}"
git clone --branch codex/lezi-spec-remediation-eee3749 --single-branch https://github.com/tusecwtf/lezi.git lezi-device-validation
cd lezi-device-validation
git checkout --detach "$EXPECTED_HEAD"
test "$(git rev-parse HEAD)" = "$EXPECTED_HEAD"
test "$(git rev-parse 'HEAD^{tree}')" = "$EXPECTED_TREE"
test -z "$(git status --porcelain)"
```

使用 JDK21、仓库 Gradle wrapper、compileSdk35/minSdk26 的 SDK；SDK 路径只放 ignored `local.properties`。不得加载 NAS 环境、生产凭据或 release 签名配置。Rebase 后先确认下文50个方法、source set与runner仍与源码一致；若变动，应先修订映射再构建。本次普通与配对 CI 的 setup-android 已统一 v4，须取得新 workflow/Node24 组合的 fresh CI 终态；手动 android-device.yml 仍为 v3，本次未触发也未随此改动，不能把两者混同。

## 2. Rebase 前源码与资产检查（历史证据）

截至 2026-10-10 UTC、rebase 前，下列检查均成功退出（exit 0），仅对应 rebase 前完整 tree `a2afc28da7cd2241c53fb3c05f7f517051613246` 的 Android 输入。阶段 008/009 在较早检查点完成；与rebase 前候选逐路径核对，仅后续 JVM 测试和 Rust 文件变化，Android 设备源码、生产依赖和 Gradle 配置未变。其余列直接核验rebase 前候选。这是源码/生成物证据，不能填作设备测试结果。

| 组与证据阶段 | 设备源码相关 Gradle 任务及 flag | 实際结果 |
|---|---|---|
| 默认五模块，009 | `:domain:compileDebugAndroidTestSources`、`:designsystem:compileDebugAndroidTestSources`、`:sync:compileDebugAndroidTestSources`、`:feature:log:compileDebugAndroidTestSources`、`:app:compileDebugAndroidTestSources`、`:app:hiltJavaCompileDebugAndroidTest` | Kotlin/Java source gate 和 app Hilt 通过；部分任务 UP-TO-DATE/FROM-CACHE，app Java/Hilt 实际执行。该批另含 JVM 编译，总耗时约 1584 秒。 |
| core:database，010 | `:core:database:compileDebugAndroidTestSources` | Kotlin 实际编译通过；Java NO-SOURCE，非“执行 Java 测试”。该批另含 JVM 编译，总耗时约 621 秒。 |
| optional widget，011 | `-PleziUiHostAcceptance=widget :app:compileDebugAndroidTestSources :app:hiltJavaCompileDebugAndroidTest` | Kotlin FROM-CACHE，Java/Hilt 实际执行并通过；整批约 97 秒。 |
| optional routes，012 | `-PleziUiHostAcceptance=routes :app:compileDebugAndroidTestSources :app:hiltJavaCompileDebugAndroidTest` | Kotlin FROM-CACHE，Java/Hilt 实际执行并通过；整批约 99 秒。 |
| optional transport source，008 | `-PleziAndroidTransport=true :sync:compileDebugAndroidTestSources` | Kotlin/Java source gate 通过，任务 UP-TO-DATE；整批约 103 秒。 |
| optional transport asset，013 | `-PleziAndroidTransport=true :sync:prepareAndroidTransportTls :sync:mergeDebugAndroidTestAssets :sync:mergeDebugAssets`，另含资产 source-set 检查任务 | 实际生成并合并测试 TLS asset，隔离检查通过；整批约 49 秒。 |

上述编译/资产阶段均没有执行设备用例，没有新的设备 JUnit XML，也没有打包测试 APK；不能据此声称可安装性、设备 TLS 或平台行为已通过。专用 startup runner 的源码属于默认 app 源码编译范围，但其专用 APK 仍须按下文构建并验证 manifest。仓库 preparation 文档保留各自历史“尚未编译”状态；本表补充后续编译证据，不把历史描述改成设备通过。

阶段 013 的公开证书元数据已核验：SAN 为 `localhost` / `127.0.0.1`，有效期 2026-10-10 13:42:08 至 2026-10-12 13:42:08 UTC（172800 秒）。生成的 p12 为 0600、gitignored、未跟踪，临时私钥 staging 目录已清理；androidTest merged assets 含该 p12，生产 merged assets 不含它，且只有 androidTest source set 引入该生成目录。证据未保存私钥字节。这证明测试资产生成与合并隔离，不是最终 APK 内容或设备握手证明；必须在本次设备执行前重新构建，取得新的两天证书；以上过期时间只是历史检查记录。

## 每个方法独立隔离

- 分别验证 API26 和 API35；每次只选一台你独立确认的新建一次性模拟器。真实业务手机不在本流程内。
- 每个方法使用独立的全新安装和 instrumentation 进程，尤其 widget/routes：Hilt 每次测试重建组件，但生产 DataStore/Room/sync 没有逐测试 shutdown，不能在一个进程连续跑多个方法，也不能沿用上一个方法的持久数据。
- 全新状态须由你确认，serial 前缀或 qemu 属性不能证明数据为空。已安装目标包、身份不明或 runner 拒绝非空存储时，停止并另外准备隔离环境；不清除、卸载或覆盖现有安装。
- 保持 SELinux Enforcing、正常 TLS 校验；启动门禁需要可用默认网络。不得登录、加入真实家庭、连接 NAS 或使用真实照片。
- fixture 自行清理它创建的合成数据、照片、widget ID 和 held read。不要为了让失败变绿，手工删库、改 gate、忽略 teardown 错误或清空真实数据。B 设备唯一副本保留策略未获批准，相关破坏性场景不纳入本次运行。

每次选择好新的隔离目标后，手动核验并保存输出：

```bash
set -euo pipefail
: "${ANDROID_SERIAL:?Set the verified fresh isolated emulator serial}"
: "${EXPECTED_API:?Set 26 or 35}"
case "$ANDROID_SERIAL" in emulator-*) ;; *) exit 1 ;; esac
case "$EXPECTED_API" in 26|35) ;; *) exit 1 ;; esac
adb -s "$ANDROID_SERIAL" get-state
test "$(adb -s "$ANDROID_SERIAL" shell getprop ro.kernel.qemu | tr -d '\r')" = 1
test "$(adb -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')" = "$EXPECTED_API"
test "$(adb -s "$ANDROID_SERIAL" shell getenforce | tr -d '\r')" = Enforcing
adb -s "$ANDROID_SERIAL" shell getprop ro.product.model
adb -s "$ANDROID_SERIAL" shell getprop ro.build.fingerprint
adb -s "$ANDROID_SERIAL" shell date
```

## 分开构建和保存产物

以下是五种独立构建选择，不是顺序一键执行脚本。一次只构建当前所选组；立即把该组 APK、SHA256、实际 manifest/runner/targetPackage 和构建日志保存到组名独立的仓库外目录。不同 app 组会覆盖相同输出路径，禁止把上一个组的测试 APK 配给下一组。

默认 production-host（真实 LeziApp，普通 runner）：

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest \
  :domain:assembleDebugAndroidTest :sync:assembleDebugAndroidTest \
  :feature:log:assembleDebugAndroidTest :designsystem:assembleDebugAndroidTest \
  :core:database:assembleDebugAndroidTest
```

专用 production startup（只运行该启动方法）：

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PleziStartupReadinessAcceptance=true
```

optional widget：

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PleziUiHostAcceptance=widget
```

optional routes：

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PleziUiHostAcceptance=routes
```

optional transport：

```bash
./gradlew :sync:assembleDebugAndroidTest -PleziAndroidTransport=true
```

widget/routes 使用 HiltTestApplication 和 IsolatedUiHostTestRunner，不能用于 ProductionAppFixture 或 production startup。widget 与 routes 不能混合；两者均不能与 startup flag 合并。默认测试 APK 不含 optional UI 组，普通 sync 测试配置排除 ManualAndroidTransport。

transport 的 assemble 会通过 mergeDebugAndroidTestAssets 依赖 `:sync:prepareAndroidTransportTls`。该 task 每次执行都会重新生成两天有效的 localhost/127.0.0.1 合成证书，asset 仅在 ignored `sync/build/generated/androidTransportAssets/transport-loopback.p12`，只打入 opt-in sync 测试 APK。过期、隔了两天或设备时钟变化时，重新执行上述 transport assemble 并重新记录 APK 哈希；不要复用旧 APK或关闭证书检查。若只需生成 asset，合法入口为 `./gradlew :sync:prepareAndroidTransportTls -PleziAndroidTransport=true`，但生成 asset 本身不会更新旧 APK。生产 APK不包含这个身份，不上传生成资产、私钥或 APK 到 GitHub。

## 安装和单方法执行

只安装当前组需要的 APK，使用新目标；不用 install -r。app 组需要 app-debug.apk 与匹配的 app-debug-androidTest.apk；domain/sync/log/designsystem/core:database 为各模块自身测试 APK（各模块 build/outputs/apk/androidTest/debug/ 下的对应 APK）。安装前以 `adb -s "$ANDROID_SERIAL" shell pm list packages` 核实相关包均不存在；安装后以 `adb -s "$ANDROID_SERIAL" shell pm list instrumentation` 对照实际 APK manifest。任何不一致即停止。

源码预期组件（仍须按实际产物核验）：

| 组 | instrumentation 组件 |
|---|---|
| 默认 app | com.lezi.babylog.debug.test/androidx.test.runner.AndroidJUnitRunner |
| startup | com.lezi.babylog.debug.test/com.lezi.babylog.validation.ProductionStartupTestRunner |
| widget/routes | com.lezi.babylog.debug.test/com.lezi.babylog.validation.host.IsolatedUiHostTestRunner |
| domain | com.lezi.babylog.domain.test/androidx.test.runner.AndroidJUnitRunner |
| sync/transport | com.lezi.babylog.sync.test/androidx.test.runner.AndroidJUnitRunner |
| log | com.lezi.babylog.feature.log.test/androidx.test.runner.AndroidJUnitRunner |
| designsystem | com.lezi.babylog.designsystem.test/androidx.test.runner.AndroidJUnitRunner |
| core:database | com.lezi.babylog.core.database.test/androidx.test.runner.AndroidJUnitRunner |

### 实际安装命令与 APK 身份

每组 build 完成立即在仓库外新建该组 `ARTIFACT_DIR`，从实际输出中复制匹配 APK，保留 build.log；用 SDK `apkanalyzer manifest print` 查看 applicationId、instrumentation name/targetPackage，不从文件名猜 runner。SDK 若未提供 apkanalyzer，先用本机已有的 APK manifest 查看工具核验；不能略过。每个 APK 均记录 `sha256sum`。app 文件名通常是以下路径，实际存在性和 manifest 为准：

```bash
: "${ARTIFACT_DIR:?New private directory for this build group}"
mkdir -p "$ARTIFACT_DIR"
cp app/build/outputs/apk/debug/app-debug.apk "$ARTIFACT_DIR/"
cp app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk "$ARTIFACT_DIR/"
sha256sum "$ARTIFACT_DIR/"*.apk > "$ARTIFACT_DIR/apks.sha256"
apkanalyzer manifest print "$ARTIFACT_DIR/app-debug.apk" > "$ARTIFACT_DIR/app-manifest.xml"
apkanalyzer manifest print "$ARTIFACT_DIR/app-debug-androidTest.apk" > "$ARTIFACT_DIR/test-manifest.xml"
git rev-parse HEAD 'HEAD^{tree}' > "$ARTIFACT_DIR/source.txt"
```

此复制块仅用于 app/default、startup、widget、routes。模块组使用自己的 `MODULE/build/outputs/apk/androidTest/debug/` 中实际 APK（MODULE 为 domain、sync、feature/log、designsystem、core/database），重复相同 manifest/hash 留档，不能顺便装 app。记录 Gradle 命令与 flag，确认默认/optional APK 中只包含预期组。transport 必须检查最终测试 APK 包含 `assets/transport-loopback.p12`，生产 APK 不含；merged assets 检查不能替代 APK 内容检查。用 `unzip -l` 保存两者清单，不导出或公开私钥资产。

新一次性设备上，每个方法单独选择新安装。以下 app 示例是在已人工确认 fresh 隔离目标的前提下执行，包已存在即停止：

```bash
: "${EVIDENCE:?New private evidence directory for this single method}"
mkdir -p "$EVIDENCE"
adb -s "$ANDROID_SERIAL" shell pm list packages | tr -d '\r' > "$EVIDENCE/packages-before.txt"
if grep -Eq '^package:com\.lezi\.babylog(\.debug(\.test)?)?$' "$EVIDENCE/packages-before.txt"; then
  echo 'Existing app installation: stop; choose a different fresh disposable target' >&2
  exit 1
fi
adb -s "$ANDROID_SERIAL" install "$ARTIFACT_DIR/app-debug.apk" > "$EVIDENCE/install-app.log" 2>&1
adb -s "$ANDROID_SERIAL" install "$ARTIFACT_DIR/app-debug-androidTest.apk" > "$EVIDENCE/install-test.log" 2>&1
adb -s "$ANDROID_SERIAL" shell pm list instrumentation > "$EVIDENCE/instrumentation-list.txt"
```

模块组则先将实际 manifest 中的 test/target 包分别设置为 `TEST_PACKAGE`、`TARGET_PACKAGE`，对两个包精确核验不存在（可能同名），并安装该组的唯一测试 APK：

```bash
: "${EVIDENCE:?New private evidence directory for this single method}"
mkdir -p "$EVIDENCE"
: "${TEST_PACKAGE:?Read from actual module test APK manifest}"
: "${TARGET_PACKAGE:?Read instrumentation targetPackage from manifest}"
: "${TEST_APK:?Absolute path of the copied module test APK}"
adb -s "$ANDROID_SERIAL" shell pm list packages | tr -d '\r' > "$EVIDENCE/packages-before.txt"
for p in "$TEST_PACKAGE" "$TARGET_PACKAGE"; do
  if grep -Fxq "package:$p" "$EVIDENCE/packages-before.txt"; then
    echo 'Existing target package: stop; choose a new isolated target' >&2; exit 1
  fi
done
adb -s "$ANDROID_SERIAL" install "$TEST_APK" > "$EVIDENCE/install-test.log" 2>&1
adb -s "$ANDROID_SERIAL" shell pm list instrumentation > "$EVIDENCE/instrumentation-list.txt"
```

安装成功后核对 instrumentation-list 中组件与 manifest 的 targetPackage 完全一致。禁止 `install -r`、`pm clear`、卸载现有包或擦除用户设备；不要先打开 app 造成 startup/host 的非空存储。每个方法结束后保留证据，下一方法另用新一次性目标，不复用持久状态或同 instrumentation 进程。切换组也重新确认 APK hash 与 runner。

从下一节选取一个完整 `包名.类名#方法名`，填入 SELECTOR。不能省略 #method，不能传整个类、包、逗号列表或运行全仓 connectedDebugAndroidTest。每个方法预期执行 1 例，零跳过；以实际 instrumentation 结果为准，ADB 退出码为 0 也不能单独证明测试通过。

普通 app 方法示例：

```bash
adb -s "$ANDROID_SERIAL" shell am instrument -w -r -e class "$SELECTOR" \
  com.lezi.babylog.debug.test/androidx.test.runner.AndroidJUnitRunner
```

startup 方法（保持原 45 秒 watchdog，不修改）：

```bash
adb -s "$ANDROID_SERIAL" shell am instrument -w -r -e class "$SELECTOR" \
  -e leziStartupGateAcceptance true \
  com.lezi.babylog.debug.test/com.lezi.babylog.validation.ProductionStartupTestRunner
```

widget 方法：

```bash
adb -s "$ANDROID_SERIAL" shell am instrument -w -r -e class "$SELECTOR" \
  -e leziUiHostAcceptance widget \
  com.lezi.babylog.debug.test/com.lezi.babylog.validation.host.IsolatedUiHostTestRunner
```

routes 方法：

```bash
adb -s "$ANDROID_SERIAL" shell am instrument -w -r -e class "$SELECTOR" \
  -e leziUiHostAcceptance routes \
  com.lezi.babylog.debug.test/com.lezi.babylog.validation.host.IsolatedUiHostTestRunner
```

transport 方法（只指向本机 loopback TLS，不需要 adb reverse）：

```bash
adb -s "$ANDROID_SERIAL" shell am instrument -w -r -e class "$SELECTOR" \
  -e leziTransportManual true \
  com.lezi.babylog.sync.test/androidx.test.runner.AndroidJUnitRunner
```

domain、普通 sync、log、designsystem、core:database 方法使用相应表中组件及 `-e class "$SELECTOR"`，不加上述三个专用参数。以下方法名单是本手册限定范围，不能扩为整个测试类或全部设备测试。

## 精确 selector 名单

本手册共 50 个方法：startup 1、默认 host 新增 4、既有默认模块 15、Main-safe/三照片测量/private-spool 6、widget 1、routes 9、transport 14。每 API 分开记结果，不以计划数量代替实际执行数量。下面逐项给出完整 class#method，可直接复制单项为 SELECTOR；不是批处理列表。

### startup 专用 1 例

`com.lezi.babylog.validation.ProductionStartupReadinessDeviceTest`

- `com.lezi.babylog.validation.ProductionStartupReadinessDeviceTest#realApplicationAndDiKeepCheckingBlockedAndStaleGenerationsInactiveUntilReady`

### 默认 production-host 新增 4 例

`com.lezi.babylog.validation.ProductionExternalComposerOwnershipDeviceTest`

- `com.lezi.babylog.validation.ProductionExternalComposerOwnershipDeviceTest#repeatedExternalConfirmationsAndActivityRecreationKeepExistingDraftAndOwnedPhoto`

`com.lezi.babylog.validation.ProductionLayoutCustomSaveDeviceTest`

- `com.lezi.babylog.validation.ProductionLayoutCustomSaveDeviceTest#pendingAddKeepsItsDraftThroughActivityRecreationAndCommitsOnlyOnce`
- `com.lezi.babylog.validation.ProductionLayoutCustomSaveDeviceTest#targetDeletedBeforeQueuedRenameKeepsEditIntentAfterActivityRecreation`

`com.lezi.babylog.validation.ProductionExportDraftRecreationDeviceTest`

- `com.lezi.babylog.validation.ProductionExportDraftRecreationDeviceTest#historicalNoPhotoDraftSurvivesActivityRecreationDuringQueuedExportAndCanRetry`

### 既有默认模块 15 例

`com.lezi.babylog.domain.carelog.CareLogSleepRoomAtomicityDeviceTest`

- `com.lezi.babylog.domain.carelog.CareLogSleepRoomAtomicityDeviceTest#backfilledSleepRollsBackOrCommitsAtEveryObservedWriteAndCancellationBoundary`
- `com.lezi.babylog.domain.carelog.CareLogSleepRoomAtomicityDeviceTest#wakeWithStartCorrectionAndPhotosRollsBackOrCommitsAtEveryObservedBoundary`

`com.lezi.babylog.domain.carelog.CareLogPlanRoomIntegrityDeviceTest`

- `com.lezi.babylog.domain.carelog.CareLogPlanRoomIntegrityDeviceTest#deletedBabyAfterPrecheckRejectsPlanAndLeavesNoMediaOrphan`
- `com.lezi.babylog.domain.carelog.CareLogPlanRoomIntegrityDeviceTest#deletedDefinitionAfterPrecheckRejectsPlanAndLeavesNoMediaOrphan`
- `com.lezi.babylog.domain.carelog.CareLogPlanRoomIntegrityDeviceTest#validParentAndDefinitionReplayRetainOnePlanAndItsOriginalPhotoIdentities`
- `com.lezi.babylog.domain.carelog.CareLogPlanRoomIntegrityDeviceTest#changedPlanPhotoRejectsStaleSnapshotThenCompletesAndReplaysWithCurrentPhoto`
- `com.lezi.babylog.domain.carelog.CareLogPlanRoomIntegrityDeviceTest#missingCurrentPlanFileCannotBecomeAnActiveCompletionAttachment`
- `com.lezi.babylog.domain.carelog.CareLogPlanRoomIntegrityDeviceTest#committedCompletionReplaySurvivesLaterLossOfItsOriginalFile`
- `com.lezi.babylog.domain.carelog.CareLogPlanRoomIntegrityDeviceTest#completionFactPhotosPlanAndCandidateRollbackAtEachWriteAndCancellationBoundary`

`com.lezi.babylog.sync.engine.WakeRepairRoomPersistenceDeviceTest`

- `com.lezi.babylog.sync.engine.WakeRepairRoomPersistenceDeviceTest#newerNotePhotoSelectionAndDeletionSurvivePausedRepairAndReopen`
- `com.lezi.babylog.sync.engine.WakeRepairRoomPersistenceDeviceTest#frozenEnvelopeReplaysExactlyBeforeNewerIntentAfterRepairAndReopen`

`com.lezi.babylog.feature.log.layout.LayoutCustomSaveDeviceTest`

- `com.lezi.babylog.feature.log.layout.LayoutCustomSaveDeviceTest#retainedSaveSurvivesRestorationAndReboundCallbackCannotClearTheNextDraft`
- `com.lezi.babylog.feature.log.layout.LayoutCustomSaveDeviceTest#retainedDeletedTargetFailurePreservesEditIntentAfterRestoration`
- `com.lezi.babylog.feature.log.layout.LayoutCustomSaveDeviceTest#deletedEditTargetKeepsItsDraftAndDoesNotBecomeAnAdd`
- `com.lezi.babylog.feature.log.layout.LayoutCustomSaveDeviceTest#pendingSaveLocksTheDraftAndCannotBeSubmittedAgain`

### Main-safe 三照片测量与 private spool 6 例

默认 domain 组：`com.lezi.babylog.domain.carelog.CareLogMainSafePhotoSaveDeviceTest`

- `com.lezi.babylog.domain.carelog.CareLogMainSafePhotoSaveDeviceTest#publicRecordAndPlanSavesHashThreeRealPhotosOffAndroidMainBeforeCommit`

在真实 Android Main 调用公开 addRecord/updateRecord/createCarePlan；三个合成文件通过真实 hash/Room，断言 hash 离开 Main 且先于事务，核对六条 active media digest。这是线程断言证据，不声称完整 StrictMode、所有图片路径竞态或三大图保存延迟测量。

默认 designsystem 组：`com.lezi.babylog.designsystem.LocalPhotoLoaderDeviceSmokeTest`

- `com.lezi.babylog.designsystem.LocalPhotoLoaderDeviceSmokeTest#recordFirstLoadFrameMetricsForThreePolicyLegalUncachedPhotos`

一个方法生成三张 6000×4000 合法 JPEG，以唯一文件路径测应用图片缓存的首次加载；不清 OS page cache。必须保存全部 instrumentation status 字段：`photo_frame_scope`、`frame_total_duration_ns`、`frame_p50_ns`、`frame_p95_ns`、`frame_max_ns`、`frame_sample_count`、`frame_reports_dropped`、`ready_draw_fence_ns`、`terminal_reported_vsync_ns`、`pss_before_kb`、`pss_after_kb`。终止报告必须达到最后一张 Ready 图片真实 draw 的时间栅栏，不能只看到 Loading 帧就结束。30 秒是缺失回调/加载失败保护，不是性能 SLO；没有自定帧耗时或 PSS 通过阈值。这与前一例的公开保存 Main-safe 证据分开，不能合称“完整 composer 三大图保存性能通过”。

默认 core:database 组：`com.lezi.babylog.core.database.PrivateSpoolPublicationRoomTest`

- `com.lezi.babylog.core.database.PrivateSpoolPublicationRoomTest#applicationProvidersShareFixedFilesDirGuardAndRejectEveryPublicationRoute`
- `com.lezi.babylog.core.database.PrivateSpoolPublicationRoomTest#legacyRowsRemainReadableAndAllExactPathEditsRemainUsable`
- `com.lezi.babylog.core.database.PrivateSpoolPublicationRoomTest#holderReplacementRejectsBeforeDeletingAnyLegacyHolder`
- `com.lezi.babylog.core.database.PrivateSpoolPublicationRoomTest#delegatedTransactionalBabyMethodsPreserveLegacyPath`

使用真实 DatabaseModule providers 和临时 in-memory Room，核对固定 filesDir spool guard、拒绝新发布、旧行原路径编辑和 holder 替换前拒绝；teardown 关闭该 fixture 数据库。这里保留的是已有合法组，不新增恢复/取消/退役/家庭删除并发，也不测试 B 设备唯一副本销毁。它不是真实用户数据库升级、跨进程或全部 private-spool 生命周期证明。

### widget 1 例

`com.lezi.babylog.validation.widget.CountingWidgetConfigurationDeviceTest`

- `com.lezi.babylog.validation.widget.CountingWidgetConfigurationDeviceTest#failedSaveThenOneRetrySurvivesRealActivityRecreationWithoutDuplicateWrites`

### routes 9 例

`com.lezi.babylog.validation.calendar.CalendarConversionRouteDeviceTest`

- `com.lezi.babylog.validation.calendar.CalendarConversionRouteDeviceTest#listRefreshFailureStaysCommittedAcrossActivityRecreation`
- `com.lezi.babylog.validation.calendar.CalendarConversionRouteDeviceTest#detailRefreshFailureStaysCommittedAcrossActivityRecreation`
- `com.lezi.babylog.validation.calendar.CalendarConversionRouteDeviceTest#closingDetailWhilePostCommitReadIsPendingDoesNotReopenIt`
- `com.lezi.babylog.validation.calendar.CalendarConversionRouteDeviceTest#newerDetailOwnsTheScreenWhenAnOlderPostCommitReadReturns`

`com.lezi.babylog.validation.composer.DelayedComposerNavigationDeviceTest`

- `com.lezi.babylog.validation.composer.DelayedComposerNavigationDeviceTest#latePlanLookupCannotReplaceOwnedDraftAfterActivityRecreation`
- `com.lezi.babylog.validation.composer.DelayedComposerNavigationDeviceTest#closedAndNewerComposerRequestsRejectLatePlanLookups`

`com.lezi.babylog.validation.export.ExportRouteFilesDeviceTest`

- `com.lezi.babylog.validation.export.ExportRouteFilesDeviceTest#historicalPdfRequestsFreezeOptionsAndKeepDistinctFiles`
- `com.lezi.babylog.validation.export.ExportRouteFilesDeviceTest#readFailurePreservesDraftAndExplicitRetryGeneratesFile`
- `com.lezi.babylog.validation.export.ExportRouteFilesDeviceTest#stubbedChooserReturnKeepsRouteUsableAndTxtReadable`

### transport 14 例

`com.lezi.babylog.sync.backend.transport.FamilyHttpPlatformTransportDeviceTest`

- `com.lezi.babylog.sync.backend.transport.FamilyHttpPlatformTransportDeviceTest#trustedLoopbackHealthUsesTheRealPlatformConnection`
- `com.lezi.babylog.sync.backend.transport.FamilyHttpPlatformTransportDeviceTest#cancellingSlowJsonReleasesTheExchangeAndPreservesCancellation`
- `com.lezi.babylog.sync.backend.transport.FamilyHttpPlatformTransportDeviceTest#cancellingSlowMediaReleasesTheExchangeAndPreservesCancellation`
- `com.lezi.babylog.sync.backend.transport.FamilyHttpPlatformTransportDeviceTest#cancellingSlowApkIntoPrivateFileClosesCallerOwnedFileAfterReturn`
- `com.lezi.babylog.sync.backend.transport.FamilyHttpPlatformTransportDeviceTest#cancellingARealBackpressuredUploadReleasesWriteAndSource`
- `com.lezi.babylog.sync.backend.transport.FamilyHttpPlatformTransportDeviceTest#slowJsonUsesOneRealElapsedParentBudget`
- `com.lezi.babylog.sync.backend.transport.FamilyHttpPlatformTransportDeviceTest#slowMediaUsesOneRealElapsedParentBudget`
- `com.lezi.babylog.sync.backend.transport.FamilyHttpPlatformTransportDeviceTest#slowApkIntoPrivateFileUsesTheSameElapsedParentBudget`
- `com.lezi.babylog.sync.backend.transport.FamilyHttpPlatformTransportDeviceTest#backpressuredUploadCannotOutliveTheElapsedParentBudget`
- `com.lezi.babylog.sync.backend.transport.FamilyHttpPlatformTransportDeviceTest#realTlsTimeAndTrickleJsonShareTheProductionEightSecondProbeBudget`
- `com.lezi.babylog.sync.backend.transport.FamilyHttpPlatformTransportDeviceTest#trickleSessionJsonCannotOutliveTheProductionTwelveSecondBudget`
- `com.lezi.babylog.sync.backend.transport.FamilyHttpPlatformTransportDeviceTest#readOnlyLostResponseRetriesAtMostTwiceOnRealHttps`
- `com.lezi.babylog.sync.backend.transport.FamilyHttpPlatformTransportDeviceTest#mutationWithoutExactReceiptNeverReplaysALostResponse`
- `com.lezi.babylog.sync.backend.transport.FamilyHttpPlatformTransportDeviceTest#receiptClassifiedRetryKeepsTheExactRequestIdentityAndBody`

## 留存证据及失败处理

每次执行前创建独立的仓库外证据目录，按 `API/组/类_方法/时间` 区分，记录：源码 SHA/tree、全部实际安装 APK SHA256、构建 flags、精确 selector、runner/参数、设备型号/API/镜像 fingerprint/serial、设备时间、隔离前提、开始结束时间和命令退出码。每条 instrumentation 命令分别保存 stdout/stderr；不要用未启用 pipefail 的管道掩盖错误。

可给上面的单条命令追加 `> "$EVIDENCE/instrumentation.stdout.log" 2> "$EVIDENCE/instrumentation.stderr.log"`；EVIDENCE 必须指向该方法新建的证据目录。失败时也保留这两个文件及退出码，不继续下一个方法。原始输出必须能看出正确方法执行 1 例、零 failures/errors/skips/assumption skips，无崩溃或 ANR。

只在本次测试目标上保存日志和截图，不清空日志：

```bash
adb -s "$ANDROID_SERIAL" logcat -d -v threadtime > "$EVIDENCE/logcat.txt"
adb -s "$ANDROID_SERIAL" exec-out screencap -p > "$EVIDENCE/screen.png"
```

- 如果本次用户选择的测试运行工具实际生成 JUnit XML/HTML，保存该次对应模块的 `build/outputs/androidTest-results/` 和 `build/reports/androidTests/`，与 selector 和时间核对；不要混入旧 XML。直接 `am instrument` 不承诺生成 host XML，没有就明确记“未生成”，保留完整原始结果，不能编造 XML 或借用历史报告。
- transport 额外保存 `LeziTransportProof` 的请求次数、caller completion、peer terminal、active/localClosed 等清理前计数；8 MiB 未形成背压是 fixture 条件失败，不是跳过或成功。日志中的 cleanup 释放不能补救此前超时。
- 失败先保存原始上下文、断言、截图和日志，再判断是装配/环境/fixture 还是产品行为。不要盲目 clear app data、删除真实照片、降低安全设置或重试覆盖唯一副本。
- 证据默认私下保存；公开 GitHub 只能放经人工核对脱敏的合成样本结果。不上传生产照片、家庭/健康记录、真实地址、令牌、私钥、签名材料或原始敏感日志。

## 本轮仍不证明什么

Activity recreation 不等于进程死亡。stubbed chooser return 不等于真实系统分享或第三方读 URI。transport 的 literal loopback 不证明真实 DNS 或 TCP SYN/connect 取消，不证明 durable server receipt，也不是 native client FD census。default startup 证据与 optional Hilt 测试证据分别记录。

不引入 US-065 当前不可执行的跨进程实验、US-086 新恢复/取消/退役/家庭删除并发组、US-092 observer 或 /proc 替代入口。本手册不执行 NAS、VPS、签名、发布、外部部署或自动触发设备 CI。

## 未纳入的其他设备源码变动

本轮新增的可手动设备方法已列在以上范围。`LocalDataContractMigrationDeviceTest` 的参数数组类型修正、`LayoutConfigurationRecreationDeviceTest` 的 composable 命名修正、`LayoutTargetRegistrationComposeTest` 的 offset 写法修正均没有新增方法；本手册不因此扩大成整个历史迁移/布局套件。共享 Room rig、transaction lease、runner、transport fixture/call helper 与 manifest 变更没有独立 @Test selector。LocalPhotoLoader 的既有快速滚动/全屏 smoke 也不冒充本轮新增的三图测量。以上未列历史测试不计入 50 例，也不声称已完成设备回归；若另行选择，仍需独立确认范围和隔离条件。

## 设备验证源码索引（相对链接随提交维护）

- [默认应用 host 边界](../../docs/verification/ui-host-preparation-2026-10-10.md)
- [专用 production startup](../../docs/testing/production-startup-readiness.md)
- [widget DI 和独立进程要求](../../docs/verification/ui-widget-test-di-preparation.md)
- [routes selector allowlist](../../app/src/uiHostAcceptance/kotlin/com/lezi/babylog/validation/host/IsolatedUiHostTestRunner.kt)
- [CalendarRoute DI](../../docs/verification/ui-calendar-test-di-preparation.md) / [late reads](../../docs/verification/ui-calendar-stale-read-preparation.md)
- [延迟 composer](../../docs/verification/ui-composer-delayed-read-preparation.md) / [export 文件与重试](../../docs/verification/ui-export-route-files-preparation.md)
- [transport 平台验证边界](../../docs/verification/android-transport-preparation-2026-10-10.md) / [两天测试 TLS 脚本](../../tools/testing/prepare-android-transport-tls.sh)
- [Main-safe 三照片公开保存](../../domain/src/androidTest/kotlin/com/lezi/babylog/domain/carelog/CareLogMainSafePhotoSaveDeviceTest.kt)
- [三张 JPEG 的 FrameMetrics 与 PSS](../../designsystem/src/androidTest/kotlin/com/lezi/babylog/designsystem/LocalPhotoLoaderDeviceSmokeTest.kt)
- [既有 private-spool Room 组](../../core/database/src/androidTest/kotlin/com/lezi/babylog/core/database/PrivateSpoolPublicationRoomTest.kt)

## 50 方法源码映射

同类方法共同对应以下文件；selector 已逐项对rebase后的源码 package/class/method 核验；后续若修改输入须再次核验。

- `com.lezi.babylog.validation.ProductionStartupReadinessDeviceTest` → [源码](../../app/src/androidTest/kotlin/com/lezi/babylog/validation/ProductionStartupReadinessDeviceTest.kt)
- `com.lezi.babylog.validation.ProductionExternalComposerOwnershipDeviceTest` → [源码](../../app/src/androidTest/kotlin/com/lezi/babylog/validation/ProductionExternalComposerOwnershipDeviceTest.kt)
- `com.lezi.babylog.validation.ProductionLayoutCustomSaveDeviceTest` → [源码](../../app/src/androidTest/kotlin/com/lezi/babylog/validation/ProductionLayoutCustomSaveDeviceTest.kt)
- `com.lezi.babylog.validation.ProductionExportDraftRecreationDeviceTest` → [源码](../../app/src/androidTest/kotlin/com/lezi/babylog/validation/ProductionExportDraftRecreationDeviceTest.kt)
- `com.lezi.babylog.domain.carelog.CareLogSleepRoomAtomicityDeviceTest` → [源码](../../domain/src/androidTest/kotlin/com/lezi/babylog/domain/carelog/CareLogSleepRoomAtomicityDeviceTest.kt)
- `com.lezi.babylog.domain.carelog.CareLogPlanRoomIntegrityDeviceTest` → [源码](../../domain/src/androidTest/kotlin/com/lezi/babylog/domain/carelog/CareLogPlanRoomIntegrityDeviceTest.kt)
- `com.lezi.babylog.sync.engine.WakeRepairRoomPersistenceDeviceTest` → [源码](../../sync/src/androidTest/kotlin/com/lezi/babylog/sync/engine/WakeRepairRoomPersistenceDeviceTest.kt)
- `com.lezi.babylog.feature.log.layout.LayoutCustomSaveDeviceTest` → [源码](../../feature/log/src/androidTest/kotlin/com/lezi/babylog/feature/log/layout/LayoutCustomSaveDeviceTest.kt)
- `com.lezi.babylog.domain.carelog.CareLogMainSafePhotoSaveDeviceTest` → [源码](../../domain/src/androidTest/kotlin/com/lezi/babylog/domain/carelog/CareLogMainSafePhotoSaveDeviceTest.kt)
- `com.lezi.babylog.designsystem.LocalPhotoLoaderDeviceSmokeTest` → [源码](../../designsystem/src/androidTest/kotlin/com/lezi/babylog/designsystem/LocalPhotoLoaderDeviceSmokeTest.kt)
- `com.lezi.babylog.core.database.PrivateSpoolPublicationRoomTest` → [源码](../../core/database/src/androidTest/kotlin/com/lezi/babylog/core/database/PrivateSpoolPublicationRoomTest.kt)
- `com.lezi.babylog.validation.widget.CountingWidgetConfigurationDeviceTest` → [源码](../../app/src/widgetConfigureAcceptance/kotlin/com/lezi/babylog/validation/widget/CountingWidgetConfigurationDeviceTest.kt)
- `com.lezi.babylog.validation.calendar.CalendarConversionRouteDeviceTest` → [源码](../../app/src/routeHostAcceptance/kotlin/com/lezi/babylog/validation/calendar/CalendarConversionRouteDeviceTest.kt)
- `com.lezi.babylog.validation.composer.DelayedComposerNavigationDeviceTest` → [源码](../../app/src/routeHostAcceptance/kotlin/com/lezi/babylog/validation/composer/DelayedComposerNavigationDeviceTest.kt)
- `com.lezi.babylog.validation.export.ExportRouteFilesDeviceTest` → [源码](../../app/src/routeHostAcceptance/kotlin/com/lezi/babylog/validation/export/ExportRouteFilesDeviceTest.kt)
- `com.lezi.babylog.sync.backend.transport.FamilyHttpPlatformTransportDeviceTest` → [源码](../../sync/src/androidTest/kotlin/com/lezi/babylog/sync/backend/transport/FamilyHttpPlatformTransportDeviceTest.kt)

## 剩余验收总表：有源码不等于可自动核销

下表是54条主要设备验收路由，不表示54条已有可运行自动化，更不表示每条只差按一次按钮。上面的50 selectors仅覆盖表中特定切片；每API均应执行这些方法，共100个计划方法/API单元，实际计数以输出为准。其余场景可能只有JVM seam、既有未列入的设备源码、或尚无完整fixture；不能擅自从全仓挑类全跑。先映射现行spec、现有source和安全隔离条件，确有缺口时另行准备/审查，不在本交接中新建危险测试。

所有可运行组均必须在 API26 与 API35 分别验收。共同通过标准：真实预期入口/对象被执行，指定断言全部成立，1方法/0 failure/error/skip（含 assumption skip），无崩溃/ANR，资源清理和失败保护断言成立；平台缺失、包未装、方法未发现、无XML或仅退出码0不算通过。每组保存源码身份、APK哈希、原始结果、日志与相关截图；只对实际覆盖的需求切片核销。下表“尚需”同时包括尚未运行和尚未形成完整源码的范围，不能将 fake/in-memory、Activity recreate 与真实平台/进程重启混同。

### A. 领域持久化、照片与恢复

| 需求 | 真实场景与尚需证明 |
|---|---|
| US-001 | 未执行真实 TXT/PDF 结束时刻、时长、记录数及合法照片对照。；跨日、撤回、有效观察切换和并发根/照片一致快照的完整导出矩阵尚未核销。 |
| US-002 | 真实 Room 两个矩阵须在 API26/API35 实跑，逐项核销根/观察/照片原子性、同操作幂等与仅提交后通知。；数据库关闭重开不等于真实进程死亡恢复；完整逐写入/取消/进程恢复条款仍需实际证据。 |
| US-007 | 空身份但本机待发布归属成立/不成立、管理员查看他人观察的页面动作与实际写 ACL 对照未完整执行。；身份代际变化后的页面能力刷新缺当前平台证据。 |
| US-008 | 当前 API 26/35 未运行 TimelineWindowInvalidationDeviceTest 的 10k/50k 空窗口、单根详情及跨窗口/真开放事实场景；不能用测试存在或返回行数代替读取量证明。 |
| US-011 | API26/API35 实跑预检暂停→删除父对象/定义→恢复提交的真实 Room 场景，确认拒绝、计划/媒体无孤儿；有效父对象与同操作重放仍完成。 |
| US-012 | API26/API35 实跑实际 Room 中当前照片快照、缺失文件、事实/完成态/候选证据完整性与幂等；JVM fake事务/真实文件控制不替代此持久边界。 |
| US-016 | 记录/计划/头像/wake 的 PNG/JPEG 真实 codec→发布→另一 Android 下载→仅改文字链尚未完整实现和执行。；应保留 UUID/hash/长度，换图才变身份；恒等 fake 或填充低分辨率 JPEG 不能代替该要求。 |
| US-017 | 三张照片第二次 PUT 失败，后续编辑并实际冷启动恢复的 Room/codec 完整链仍未执行。；必须进一步核销最新根/照片一致与旧不可变证据合法收口，不能靠永久跳过或换 mutation 身份。 |
| US-031 | 恢复设备与新接收设备的真实 Android持久化因果基线，以及照片/wake/计划关联、receipt重放/重启全图仍缺 Room平台执行；不能用服务端11项推断全部客户端接纳。 |
| US-035 | 完整 member删除→admin edit/resolve/replay/pull/restart 后的所有传播/显示/导出面仍未逐项核销，真实 Room/页面未执行。；不可变版本/hash/精确receipt兼容性应引用已批准 ADR 设计并与端到端证据一起核销，不能只凭当前投影清除关闭。 |
| US-049 | 执行真实编辑入口的完整日期、跨日、DST overlap 原 offset 与零改动 timestamp；验证 gap 拒绝、写成功才关闭和失败保留草稿。 |
| US-067 | 公开 CareLog 多根 Room 事务及失败/取消/进程恢复窗口未完整证明；补齐目标身份、附件保留/清空/替换及旧入口同业务实现的逐项映射，不能以存储层事务替代整个公开命令。 |
| US-068 | 同一边界/DST/不确定性合成图经时间轴、汇总、搜索、TXT/PDF、小组件全部核对；执行 Room 失效与身份代次切换、真实 widget，证明范围差异明确且旧家庭快照不外发。 |
| US-078 | R1真实文件先删/journal回滚 seam、R2跨协程交接时取消（不是普通timeout）、R3灾备快照/续传照片变化须分别给真实实验窗口/合同/处置；缺证时不得把风险计为确认缺陷。未知唯一副本处置仍须B，调查本身不能被B替代。 |
| US-080 | 有效/暂定Wake两种图、备注/附件、新身份/归属来源、原图/候选/采纳不变以及逐写入失败全回滚仍须明确完整映射；公开命令真实Room失败窗口未执行。 |
| US-081 | API26/API35 实跑真实 Room 暂停旧读交错与重开，确认新内容/单调content epoch、冻结信封和新合法发表意图；同时保留公开 SyncPortRoomRaceDeviceTest 所要求的实际Room latch证据。 |
| US-093 | 真实Room的来源捕获与护理根同事务、捕获后新选择、远端回应落地epoch/evidence CAS、持久中断/重启/旧未知批次及host接纳矩阵仍缺；fake/in-memory正确不等于持久化全验收。 |

### B. 会话、信任、取消与平台网络

| 需求 | 真实场景与尚需证明 |
|---|---|
| US-013 | 须完整覆盖生产等价 logout/leave/self-revoke/family-delete/heartbeat removal 的受控收口、单次 barrier 和新会话建立。；持久 pending-clear 真正重启续做及 barrier 跨协程取消仍缺当前 Room/平台执行。 |
| US-021 | 真实 Android 慢 pull/PUT/commit 的后台切换尚未执行，PUT/commit 阻塞取消与有界耐久收尾/未知结果证据保留的完整矩阵不足。 |
| US-023 | 两管理员普通重连后另一台仍有效、探测临时资源退休、明确 takeover 两次确认及取消无副作用需同链完整核销；UI 确认当前未设备执行。 |
| US-026 | 结果待确认/恢复动作的实际入口与相关 grant 创建全部审查和执行未闭合；当前 JVM transport seam 不能证明页面恢复呈现。 |
| US-027 | 已有上列transport慢JSON/media/APK sink/背压upload的14方法源码及历史编译，API26/35执行待补。验证取消传播、caller完成、peer终止与真实清理前计数；不能将fixture最终cleanup当及时释放。未覆盖真实DNS阻塞或SYN连接取消。 |
| US-029 | 上列transport覆盖真实TLS/trickle共用8秒探针、12秒会话及传入parent budget；DNS/connect/write/最终返回JVM seam不替代Android平台链。真正DNS、平台二次解析、延迟SYN暂无此50方法中的可执行完整source，需要合法隔离网络，不能用NAS/任意公网/关闭TLS或改系统权限代替。 |
| US-066 | 执行 Android 解析器并与 JVM 对照：IPv6恰一层括号、loopback、默认/非默认端口、DNS大小写、非法authority、幂等；不扩大明文邀请 URL 的 IPv4/DNS 边界。 |
| US-069 | 生产等价装配的取消/重启/再次调用与跨协程 barrier 移交取消；实际 lifecycle/慢网络资源停止；确认内部顺序、无伪造持锁参数、无网络/慢文件包入数据库长事务。 |
| US-070 | 真实 Android keystore/DataStore 凭据写入、nonce/journal/epoch 崩溃/跨进程窗口仍缺；完成 rename/checkpoint/refresh各自字段及期望身份 CAS 的持久化证明。 |
| US-071 | 上列transport现有14方法验证真实HTTPS读写/取消/parent budget与read-only最多两次、无精确receipt的mutation不重放、有receipt原请求身份及body不变。真实DNS/SYN、native FD census、durable server receipt全链不在其证明内；生产APK下载/installer的预算须单独核验。 |

### C. 页面、输入、恢复与系统集成

| 需求 | 真实场景与尚需证明 |
|---|---|
| US-039 | CertificateApproval、Ready owner/member、PendingMember、Empty 在最窄/矮屏/长辈字体下完整可见、可聚焦/点击、不重叠及CTA可达，全部待当前平台执行。 |
| US-040 | 项目锁定Compose版本实际遮罩/拖动/系统及预测返回不能Hidden、失败草稿/成功关闭和真实写一次仍未当前设备执行。 |
| US-041 | 滚动Column、LazyColumn、有限高容器 × l1–l3 × 明暗主题的非零随内容高度与选中正确性均待执行。 |
| US-042 | 十分钟无DB写、24h陈旧阈值、暂停停止/恢复首帧追平及历史闭合窗口不重查全库的实际页面+查询量完整矩阵未执行。 |
| US-043 | 真实Activity慢改名/添加成员重建后再点一次、结果准确、切新target不被旧结果覆盖和秘密不入可保存UI状态仍需完整平台矩阵。 |
| US-044 | 头像文件失败与资料更新失败两路径的真实UI已创建警告、重试同一宝宝、取消不删已提交资料仍未逐项执行；现有selection failure不是头像两类失败的替代。 |
| US-045 | 真实confirm/close/权限撤销/provider错误/重建的busy-success-failure、部分步骤已完成说明及可达retry未执行。 |
| US-047 | 已有CountingWidgetConfigurationDeviceTest单方法可观测写次数，覆盖保存失败→一次显式重试→真实Activity重建且不重复写。API26/35执行待补；加载失败、连点、返回/取消、系统成功结果等其余矩阵仍须逐项映射，不能用按钮disabled代替写次数。 |
| US-048 | 真实 Activity 上执行 journal、l3、系统亮/应用强制深色；核对文本、背景、系统栏与可达性。 |
| US-050 | 两至三个同类型/同剂量来源的按钮与读屏目标唯一；改名显示当前称呼、删除显示家人，无裸 membership ID；必要时稳定编号。 |
| US-051 | 在真实手势中验证超阈值取消、权限改变、指针丢失、第二手势中断回调为零；正常松手一次，旧 settle 不迟到回调且保留删除确认。 |
| US-052 | 通过真实应用启动，执行整数、小数、边界新增/编辑往返与垃圾输入拒绝；证明系统德/法语言未被误作应用 Locale。回归守卫仍有效，不能记 N/A。 |
| US-053 | 实际分页、逐图、输出慢阶段取消后不得开始下一单元，关闭资源/删除半产物；明确不可中断平台边界，并分别验证原 10 秒要求与独立 30 秒生产边界，必要时先决定有界方案。 |
| US-054 | 冻结旧/新中间帧，核对标题和内容各自一致；执行 A→B→C、减少动态 0×，普通内容刷新不得触发整页转场。 |
| US-056 | 已开 Composer、计时完成、家庭弹窗中注入强制状态；触摸/返回/TalkBack/键盘不得保存或导航普通业务，保留草稿、已提交收尾及有界会话恢复例外。 |
| US-074 | 跨实际 Composer/Family/Timer/Widget/Installer/Export 入口执行重建、取消、部分失败、未知远端及一次性消费；真实持久恢复请求/目标/阶段/结果且不持久化秘密。 |
| US-075 | 实际 UI 区分重连/接管且取消无破坏副作用；执行来源选择、冲突、未来事实转计划、清空/恢复的现行确认与可辨认来源动作。 |
| US-088 | 新增default ProductionExternalComposerOwnershipDeviceTest已有真实外部确认、备注/拥有照片、重复确认和Activity recreate；optional routes两方法已有延迟lookup、关闭/较新请求和recreate。分别用正确runner执行，核对draft/target/照片所有权不被晚结果替换；不能把两种host冒称同一production启动证据。 |
| US-089 | 默认log四方法与production host两方法已列：慢新增/删除目标/retained草稿/Activity recreate。实跑确认A旧结果不清空B/C、一次写入、失败保留编辑意图且不变新增；组件restoration与真实Activity证据分账。 |
| US-090 | 默认production export方法与optional routes三方法源码已准备，后者成功/失败两方法加入慢生成期间Activity recreate。核对历史跨月、photo关闭、冻结参数与请求、文件不同名、一次读取/重试；stubbed chooser不证明系统分享，Activity recreate不证明进程死亡。真正系统chooser/接收端读取、进程恢复、PDF实际取消仍开放。 |
| US-091 | 已有optional routes Calendar四方法，分别列表/详情commit后刷新失败、recreate、关闭和较新详情。API26/35实跑已转换+可恢复警告不误报未转换，retry仅刷新不重建事实；Hilt fake边界不代替Room域幂等。 |

### D. Main、性能与发布/启动平台门

| 需求 | 真实场景与尚需证明 |
|---|---|
| US-046 | 已有公开addRecord/updateRecord/createCarePlan从Android Main调用、三合成文件hash离开Main且先于事务、六条active digest的device源码；另有三JPEG首次加载frame/PSS源码。分别运行并保存线程与指标证据；不声称覆盖全部StrictMode、路径竞态或完整composer保存性能。 |
| US-064 | 新rebase精确源码的清洁clone普通CI、debug APK、lint及公开unsigned R8须实际重验；API26/35 Room/关键UI/安装门待用户执行。签名release、安装升级/回滚与设备边界是独立授权/验证，不把unsigned R8当签名发布。 |
| US-065 | PackageInstaller创建后open/write/fsync/intent/commit前错误的abandon与原始错误保留、commit后不误abandon仍需合法平台证据。当前缺少安全验证入口的跨进程观察保持 BLOCKED，不提供替代命令、不新建observer、不通过其他UID或/proc绕过；不能为了完成该故事擅自扩大现有50方法。 |
| US-076 | 按rebase最终source核对新旧API/消费者/fake的同一实现及持久化/wire变更；精确新HEAD的远端CI待完成，旧89eb已经建立CI不得再说从无CI。API26/35 Room/应用与安装器平台门仍未跑，不外推任意新旧手机兼容。 |
| US-077 | 运行已列Main-safe与独立三图first-load FrameMetrics/PSS方法，保留所有指标、最后Ready draw栅栏和drop计数。没有预设frame/PSS性能SLO，30秒只是fixture失败保护。真实慢网络、PDF取消、逐类候选/页/字节/线程/句柄预算仍需证据；JVM RSS、对象数、应用缓存首次加载不等于native句柄/峰值PSS/冷OS缓存。 |
| US-082 | 专用production runner单方法，在全新API26/35、正常SELinux Enforcing和默认网络执行；保留真实LeziApp/Hilt及45秒watchdog。Checking/Blocked、旧generation回调不得开启业务Room/session/网络，Ready只激活一次。optional HiltTestApplication不能替代。 |

### 关键未覆盖场景的操作边界与判定

- **真实 DNS / SYN**：当前loopback测试用literal地址，TLS/trickle是真网络但不是DNS或TCP连接卡住。只有经批准的隔离网络/端点才能补观察；先报告source与环境缺口，不给iptables、权限绕过、任意外网故障或NAS实验命令。通过需同一绝对budget下晚成功拒绝、取消/资源终止且不无限补线程；DNS worker最多2活跃+2排队的JVM证据不等于中断系统resolver。
- **Room/spool/应用恢复**：现有4个PrivateSpoolPublicationRoomTest使用真实providers及in-memory Room，证明固定filesDir guard、旧行原路径编辑和holder删除前拒绝；不是磁盘升级或跨进程恢复。domain Room两睡眠/七计划方法、wake repair两方法有具体持久边界；不能推导进程被杀、Keystore/DataStore断电恢复、US093整条来源CAS或所有spool生命周期。完整缺口仍开放，不指示杀真实业务进程或清真实库。
- **PDF/分享**：须用合成护理记录/图片对照TXT/PDF范围、结束/时长/数量、分页和合法附件；逐阶段取消时不启动下一单元、资源关闭、半产物删除，分别记录原10秒要求与现有30秒生产边界，不能放宽预算作通过。真实chooser必须由用户明确选择用于验证的接收对象/应用并确认分享内容；未批准不得自动发送、上传或将数据交给未知第三方。取消chooser不能发出任何实际分享。仅URI可读或stub return不证明接收方成功读取。
- **安装与系统边界**：上述 `adb install` 只安装合成debug测试目标，不等于产品PackageInstaller流程、签名校验、更新确认、回滚或OEM行为。系统日历权限撤销/provider失败、widget宿主回传、通知/生命周期、语言/字体/辅助功能都要真实界面证据。缺平台能力时填BLOCKED及具体原因；不降低权限、TLS、SELinux或签名规则。
- **证据隐私**：所有记录、照片、昵称、family ID均人工合成。截图先检查通知栏、账号、家庭记录和相册；logcat可能含设备/网络识别信息，公开前人工脱敏。不要把真实家庭凭据/图片、令牌、私钥、签名材料、未脱敏整份日志或私人备份上传GitHub。

## 非设备缺口、待决定政策与独立维护

### 非设备证据的保留/重验范围

旧94条台账的11 closed/73 partial/7 unverified/3 decision是历史快照，不能继续当新计数；83未核销不等于83个bug。也不能用50设备方法覆盖所有94故事。以下已补云端证据按新rebase影响重新审查：

- US003/004/005/006/009/010：domain时间/DST/观察/目录clock矩阵；数学和fake证明不推导Room/UI。
- US014/018/020/079：真实Rust/production client媒体、TTL、Owner/wake与百组静默hash；保留成功限流等待与旧失败，不能假造历史Retry-After。
- US015/022/024/025/028/029：refresh CAS、名称、commit401、heartbeat真实loop、HTTP budget/有界DNS；对象计数不等于native FD。
- US030/033：恢复与迁移既有安全合成测试；in-process Store重建不等于OS杀进程。restore媒体墓碑直接导入为422拒绝，不能写成允许直接导入成功。
- US034/063/083：owner root预算共享、版本文档、离线版本合同；mock不能替代真实Docker/Compose build、镜像身份及health。
- US058/060：新精确HEAD clean CI、workflow path filters、source-built server与receipt-clock digest、新action工具链；每job终态及artifact要对应本轮。
- US087：原功能矩阵与后来完整Rust通过已补齐功能证据；旧1秒connect/5秒resolve失败原因未确定，保留为独立可靠性调查，不称预算校准修复了性能。
- US092：原18次记录仍为16 PASS / 1 FAILED / 1 UNRUN；原PID、最终exit、首commit回包遗失，后来成功不能重建旧因果。120秒是诊断观察窗，不是新SLA。
- US032/037/038/055/059/061/062/073/084/085/094为历史已核销集合；有受影响输入再重验，没有新增执行不计新增关闭。

### 明确停止边界

| 范围 | 当前状态与下一步 |
|---|---|
| B：US019 / US072 | 同一个尚待用户决定的业务政策：恢复终态未知、离线/失去凭据时唯一媒体副本如何保留/隔离、可见性与允许删除条件。保持现行行为；rebase授权不授权删除，测试不得销毁唯一副本或用清空数据制造成功。 |
| C：US057 | 当前源码清理已批准并完成，不再列待批准C。该事实不代表公开旧history/cache/未知副本已消失；不得整体公开私有历史备份。 |
| US036 | 独立UID观察的环境/许可不足，保持未证明，不改权限或换路径绕过。 |
| US065 / US086 / US092 | 当前不可执行的跨进程观察、新恢复/取消/退役/家庭删除并发组、observer或/proc替代入口不执行、不重建、不改名绕过。缺证保留，不以“修完所有项”扩大授权。 |
| Docker / NAS / 签名 / 发布 | 真实Docker build+health、NAS维护/迁移/容器替换、release签名/分发与master合并均需分别授权及具备环境；本手册不执行。无真实NAS状态结论，版本号接近不是部署理由。VPS路线继续不恢复。 |

### 警告与技术债不是设备失败，也不是已清零

[警告处置与后续验证](2026-10-10-warning-followup.md)保留旧89eb完整分类：96条模块记录 / 27规则位置 / 38精确诊断 / 0 errors，其中17个依赖工具位置和10个非版本位置。rebase后的实际数量要从新XML重新核定，不能预做减法；普通编译日志缺失时不声称全项目warning总数。

后续独立维护包括 target/大屏竖屏合同与配套工具链迁移；依赖整组兼容；两处UsableSpace预留/ENOSPC原数据保护；中文打包策略；SPKI/TOFU自签名信任回归；Keystore-backed Security Crypto旧格式、原子切换与重启兼容迁移；5个legacy presentation桥；4处opaque测试访问基础设施。不得为清warning改明文存储、公开生产能力构造器、关闭Deprecated、扩大Suppress/baseline或trust-all。

## 完成登记与交付判定

1. 固定新源码与master基线，核对差异分类、受影响检查终态、artifact及server/helper/APK摘要；失败保留原始日志并在修复后新source重新验证。
2. 50方法按API/组逐一记录PASS/FAIL/BLOCKED/UNRUN，不能以预定100单元代替已执行100。未发现测试或skip直接作为未通过处理。每次fresh隔离，不串跑Hilt/DataStore单进程。
3. 对54故事逐条记录本次具体切片、未覆盖场景、来源方法与证据；没有source或平台能力就明确保留，不能用已有fake补空格。
4. 分享仅限脱敏结果；人工核验日志与截图不含真实家庭资料。实际外发/签名/部署/唯一副本政策保持各自授权门。
5. 只有实际条款与证据满足才关闭对应项；无设备结果时本PR只能声明源码及准确列明的非设备门通过，不能声明全验收、可正式发布或94/94。

## 尚无完整可运行覆盖的场景：先准备再执行

以下不是额外自动化清单，也不是允许新建测试的授权。执行责任：用户操作设备；工程准备者先在允许范围内核对source/可观察断言和隔离fixture，缺少入口时报告缺口。只有平台/目标/数据与接收对象获得必要许可后才能推进；受限场景保持 BLOCKED。每行都要把API26/35分开记账。本文50方法外的测试不得未经范围审查自行批量运行。

| 缺口/关联需求 | 执行前还需准备或阻塞 | 用户可验操作/触发 | PASS / FAIL判定 | 必留证据 |
|---|---|---|---|---|
| 真DNS/重复解析/SYN，027/029/071/077 | 50方法没有对应完整source；需已授权可控隔离DNS和连接端点、被测生产调用及可信时间观测；环境未具备则BLOCKED | 在该隔离环境分别触发慢解析、解析后连接停滞和取消；不提供改网络权限/任意外网命令 | PASS：全阶段同一绝对预算、拒绝晚成功、资源有界；FAIL：续新预算、取消被吞、无界积压。没有阶段观测不能PASS | 端点fixture版本、时间线、原始异常/请求计数、caller与peer终止；无敏感抓包 |
| 完整Main保存/资源，046/077 | 已有Main-safe方法仅覆盖3个公开保存及hash/事务顺序；全StrictMode/路径竞态尚无完整fixture | 先执行已有方法；更宽入口按实际source另行设计合成路径租约/身份变化检查 | PASS只限已断言范围；新场景需要证明Main无慢文件工作、身份/事务顺序正确。不能以无卡顿主观感受通过 | 线程断言、调用入口、任务时序、资源统计；三图frame/PSS原始status分账 |
| 真磁盘Room/应用进程恢复，002/013/017/031/067/069/070/078/081/093 | in-memory或close/reopen不够；需安全的持久fixture、受支持恢复入口、故障窗口与只读状态检查；涉及受限跨进程观察时不执行 | 在获准的一次性安装中保存合成记录与待确认操作，经合法应用恢复流程回到同目标；不杀真实app或改系统权限 | PASS：同一mutation/快照续做、原子状态、旧结果不盖新epoch、唯一副本保留；FAIL：半事实、重复写、秘密入UI、误删。未知终态不得假成功 | 前后合成状态摘要、请求ID/epoch脱敏映射、恢复日志与屏幕；不导出真实DB |
| 真实Android双设备codec与媒体续传，016/017/031 | 需两台隔离设备、合成PNG/JPEG、独立非NAS测试服务及受控第二PUT失败fixture；50列表无完整双设备链 | A合法发布→B下载→只改文本；分别测试换图和三图中间上传失败后合法重试 | PASS：文字改动UUID/hash/长度不变，换图才换身份，无部分可见；FAIL：媒体冲突/错引用/新mutation掩盖未知 | 两端APK/hash/API、合成字节摘要、请求/receipt及界面证据 |
| PDF真正取消，001/053/077 | 需实际PdfDocument分页/图片/输出阶段可观测夹具；现有routes文件测试不完整覆盖取消 | 合成大范围逐阶段启动并按支持的取消动作中断；保留10秒验收与30秒生产边界独立结果 | PASS：下一单元不再开始、关闭所有资源/半产物删除、原内容保留；FAIL：继续生成/泄漏/超约定预算。不可中断边界必须如实记录 | 阶段时间线、文件清单/哈希、取消响应、资源关闭日志、TXT/PDF对照截图 |
| 真系统chooser与接收URI，090/074 | stub chooser不足；先由用户选测试接收应用/对象，确认只分享合成文件；没有接收授权则不发送 | 用户打开真实chooser，分别取消与发送合成TXT/PDF到已确认对象，再返回应用 | PASS：取消不发，接收方仅能读取授权文件，返回仍同draft/request且可重试；FAIL：错文件/不可读/参数丢失/重复生成。Activity recreate不替代进程恢复 | chooser与返回截图、接收应用/对象确认、授权URI范围与文件hash（公开前脱敏） |
| Export实际进程恢复，090 | 现有两类Activity recreate不足；需经审查的持久状态恢复协议和合法恢复入口，未具备则UNRUN/BLOCKED | 合成历史跨月、关闭照片，慢生成/失败后通过受支持恢复路径再打开 | PASS：冻结request与可编辑draft区分、原参数可明确重试、无秘密持久化；FAIL：错范围/重置选项/旧结果复活。本交接不提供额外跨进程观测入口 | 恢复前后参数/阶段、文件hash、截图和脱敏日志 |
| PackageInstaller，065/064/074/076 | 50方法不等于安装流程；需明确许可的debug fixture与系统入口。跨进程检查缺少安全入口时保持 BLOCKED | 仅获准范围的创建后阶段失败、commit前后返回/用户取消；不增加 observer 或 /proc 观察方案 | PASS：仅本次合法未提交session退休、commit后不误abandon、原始错误保留；FAIL：误清别的session/错误被吞。未观察范围不判PASS | 系统返回码、已获准session标识脱敏、错误链、用户界面；绝不变更真实安装 |
| widget完整宿主，047/048/068/074 | 计数方法已存在但加载失败/取消/系统result/主题字体全矩阵未齐；需可控widget宿主与合成状态 | 用户在隔离launcher中配置、失败重试、连点、重建、返回；journal/l3/强制深色 | PASS：只一次合法configure、错误可恢复、取消不写、结果回系统、布局可达；FAIL：重复写/永转圈/主题不继承 | configure计数、result、widget ID脱敏、明暗/字号截图与日志 |
| 系统日历/权限，045/068 | 需专用合成日历及用户同意的权限操作；无真实账号日历数据 | 真实confirm/close、权限撤销、provider失败后retry与recreate | PASS：保存成功才关闭、部分提交说明准确、错误保draft且不重复披露；FAIL：伪成功/真实隐私写入/无重试 | 权限状态、合成provider行变化、UI截图/异常链 |
| 身份与会话UI，007/023/026/035/039/043/044/056/070/075 | 现有JVM控制不代替全页面；需隔离成员角色、受控凭据代与慢请求；不得触真实家庭 | 用户核对重连与接管取消、晚改名/新目标、头像已创建后失败、强制更新覆盖已开业务窗口 | PASS：ACL/epoch正确、取消不破坏、已提交事实明确、旧结果不盖新状态、强制门拦业务；FAIL：越权/重复创建/草稿丢/秘密持久化 | 请求角色与代次脱敏摘要、忙态/警告/确认截图、写次数；凭据不入日志 |
| 布局/统计/手势/语言，040/041/042/049/050/051/052/054 | 需要实际Compose/系统输入/辅助功能与计数；现有截图或fake不覆盖全矩阵 | 按上方故事执行遮罩/拖动返回、容器×字号×主题、10分钟无写、DST/gap、取消手势、德法系统下中文输入、A→B→C转场 | PASS：条款逐项满足、取消零业务动作/正常一次、时间与内容同快照、非法输入拒绝且保draft；FAIL：任何错写/错时/不可达。视觉主观“看起来正常”不足 | 各矩阵前提与截图/必要录屏、业务回调数、查询量、固定clock与输入结果 |
| 大历史Room查询/轻量投影，008/042/068 | 需合成10k/50k图、真实Room读取量计数及无写时钟场景；50列表不含完整矩阵 | 空窗口/单根/跨窗/开放事实、暂停恢复和陈旧24h边界，跨时间轴/汇总/导出/widget对照 | PASS：相关范围工作量符合既有合同、旧家庭快照不外发、恢复首帧追平；FAIL：全历史扫描/错误范围。返回行数不等于读取量 | 数据规模、查询/候选计数、clock/identity变化、输出摘要/截图 |
| Docker/独立UID/受限恢复并发，036/083/086/092 | 真实Docker能力/单独授权缺失，UID/并发/observer场景缺少适用的隔离验证入口；本交接不提供替代实验 | 保留现有离线fixture与历史报告；需要新授权的仅报告请求，不自行运行 | mock/源码检查不能判真实build+health通过；受限条目保持 BLOCKED，原失败原样保留 | 能力/授权阻塞、旧失败与缺失证据清单；不得伪造新通过 |
