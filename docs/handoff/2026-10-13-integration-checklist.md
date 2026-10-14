# 乐记 0.5.5 整合验收核对表

审计时间：2026-10-14。已核验公开头 `199b131624d54dce5e75a76b44c2ccb3d1d8d940`，tree `9bde7bb2b326550e799f8bfc28ca2b0462de5749`。完整整合已发布至 [PR 11](https://github.com/tusecwtf/lezi/pull/11)，双 CI 成功，尚未合并。状态与计数仅绑定该头，后续提交须另记证据。

## 总结

搜索共享投影、固定容量计时、不可变 schema 期望缓存、媒体有界流式落盘、原有锁随 blocking 工作持有、媒体路径锁回收及旧 owned 文件的持久回收均已实现并发布。Android JVM 全套、同源 isolated 双侧、opt-in sampler、app debug 和全部 debug instrumentation 编译/打包通过；设备上的 Room/界面执行、PSS、fsync、私有源回流与 NAS 尚未闭环。

## 提交与发布边界

- 公开 master 基线仍为 `9e47fd6c9111eb5352cef585f4672a71fb578af4`；PR 头为上列完整整合，不再仅有 `c7461bc` 摘要首切片。
- [Android run 38536562501](https://github.com/tusecwtf/lezi/actions/runs/38536562501)：802份XML、5,832项JVM测试，0 failures/errors/skipped；所有选中的有测试JVM任务均新执行，app debug和各模块instrumentation APK编译/打包成功。
- [Rust run 38536562564](https://github.com/tusecwtf/lezi/actions/runs/38536562564)：582项通过、0失败、6项按设计忽略的timing-only测试；Clippy和隔离打包/TLS smoke通过。6项计时测试的另行执行证据保留在历史整合报告，不冒称由本次CI执行。
- PR merge-ref checkout `057d65172bb47aa4d4963422f0953d4f634e29ae` 与公开头tree一致；服务二进制及实际测试明细见[公开CI证据](../dev/evidence/integrated-2026-10-14/ci-report.md)。
- 32处安全Kotlin warning修复已由该头验证；11处既有加密存储弃用保留。该次instrumentation编译另外暴露两处reified-intersection warning，已补显式`arrayOf<Any>`，待后续头CI核验，不能计为既有绿灯已覆盖。
- 最终交付头、合并后master结果、最新便携补丁与私有源回流仍须单独核验。未运行ADB/设备instrumentation，未部署NAS。

## 原 15 组逐项验收

下表中的“JVM通过”“编译通过”均指上述运行；编译出来的设备测试不等于设备上执行通过。

| 组 | 内容与源码证据 | 已验证范围 | 发布与剩余验收 |
|---|---|---|---|
| 1 | 出生体重：BabyProfileConstraints、BirthWeightInputTest、BabyProfileValidationDeviceTest | JVM解析/字段回归通过，设备测试编译通过 | 已发布；非法原文保留、字段独立错误等设备验收待跑 |
| 2 | CTA角色、busy、禁用原因：ActionStateComponents及DeviceTest | 相关JVM全套通过，设备语义测试编译通过 | 已发布；两模板点击/焦点/读屏和忙态待设备验收 |
| 3 | 相机拒绝、恢复、owned capture：CameraPermissionRecovery、OwnedCameraCaptureSessionsTest及入口DeviceTest | 所有权JVM回归通过，权限恢复测试编译通过 | 已发布；真权限拒绝、设置往返、取消不新建文件、不自动拍照待设备验证 |
| 4 | 切宝宝/申请反馈：CurrentBabySwitchActionTest、CurrentBabySwitchFeedbackDeviceTest、MemberApprovalWaitingDeviceTest | JVM行为回归与设备测试编译通过 | 已发布；失败保留原宝宝、可重试、未知截止时间及服务端裁决待设备验证 |
| 5 | 睡眠低动态：SleepComposerMotionDeviceTest | 静态/动画路径测试编译通过 | 已发布；系统动画0/0.5/1、两模板确认/焦点/可见变体待设备验证 |
| 6 | 脏草稿/配置重建/偏好：BabyEditDraft、JVM和DeviceTest | 净/脏、保存锁、4015g边界JVM回归通过，设备测试编译通过 | 已发布；旋转重建、成功仅消费一次待设备验证；不外推进程死亡 |
| 7 | 大字/窄屏/IME | 历史静态风险仍无设备截断/重叠实证 | 未完成；360dp、短视口、IME、warm/journal×明暗×l1/l2/l3核验，发现问题再修 |
| 8 | 旧查询/宝宝/ACL失败只读；睡眠查询共享投影 | Python SQLite合同7/7、JVM及KSP/Room编译通过；主机129/1024根读取从387/16384降为129/1024，计划走UUID/adoption索引 | 已发布；真实Room查询计数、UI权限恢复待设备执行 |
| 9 | 图表逐日无障碍：SummaryChartAccessibilityTest、SummaryChartDailyAccessibilityDeviceTest | 首末日、缺失/真实零值等JVM回归通过，设备测试编译通过 | 已发布；TalkBack逐日、单位、无重复合并描述待设备验证 |
| 10 | localPhotoCacheSnapshot：entries/pinnedEntries/totalPins/estimatedDecodedBytes | pin/LRU相关JVM回归通过，按需快照入口存在 | 已发布；五路径/三图进入、快滑、退出5s/30s的PSS+pin配对未做；估计解码字节不是PSS |
| 11 | Rust固定阶段探针、Android九阶段process-wide探针 | Rust历史采样及主机probe；本次Android opt-in真实引擎+loopback双侧quiet sampler实际通过且未跳过 | 已发布；合成无照片/内存DAO结果不覆盖照片解码/压缩、真实Room或手机性能 |
| 12 | 不可变schema期望缓存；每库实际验证保留 | Rust 582全套、Clippy；历史fmt及整合15轮/4,500样本 | 已发布；主机已有DB初始化实证不代表首次建库、NAS或HTTP吞吐 |
| 13 | 流式transport→exclusive owned file→receipt→page apply；替换旧文件持久回收 | JVM store/engine/transport及双侧通过，包括4项新增持久回收回归；阻塞I/O归文件边界，历史空媒体重试策略保留 | 已发布；Android fsync、真实Room与崩溃/重启/断电行为仍待设备验证 |
| 14 | 15个已有async锁由blocking工作持有 | Rust全套含取消/排队/panic及实际Router回归 | 已发布；没有新增全面锁覆盖，不强停SQLite；真实家庭并发实验未运行 |
| 15 | 总整合、交接、私有回流、设备验收 | 完整PR头双CI及源码绑定已核验，交接已更新 | 未完成：最终头及合并后CI、交付制品、最新portable patch校验、私有回流、设备验收；NAS另需窗口 |

## 三目标的可接受结论

1. 结构：减少重复投影/散列及payload大小数组，增加有界诊断和明确资源所有权；路径gate已有主机压力回归与整合JVM证据。不声称整App体验已完整验收。
2. 性能：整合主机warm Store中位下降76.7–77.8%，每规模15/15配对轮更快；1k cold server init 21.221→16.752ms，下降21.1%，15/15更快。10k cold init 105.234→103.251ms，仅8/15更快，p95/max 120.026→128.372ms，不声称稳定改善。
3. 正确性：该公开头的5,832项JVM测试和582项Rust测试通过、Android全debug编译通过；设备instrumentation、NAS部署、私有master同步、后续头与合并结果都不能由此推定。

历史性能范围不变：首切片desktop JVM 32-byte hex 7,533.6→167.5ns；16MiB retained hash 14.780→12.956ms、分配16,798,392→9,440B。8MiB warm host body→adopted file为12.229→9.849ms、分配16,797,360→20,920B，以已完成摘要优化的c7461bc为基线，不重复计收益；包括marker/directory/file fsync，但不含后来replacement re-arm、HTTP/TLS、Room、path gate或Android。累计分配不是peak heap/PSS。

## 已关闭与仍开放的非设备门禁

已关闭（仅上列公开头）：4项新增旧owned-file持久回收引擎回归；全`./gradlew test`；app debug及全模块debug instrumentation编译；同树service的sync/domain isolated seams；实际执行的opt-in sampler。源码checkout和binary哈希均已记录，XML无跳过。

仍开放：

- 两处新instrumentation类型修复及最终交付头的对应CI；合并后另核对master，不能预写未来SHA或结果。
- app及设备测试APK的完整manifest package/targetPackage、runner、签名/哈希交付清单；现有CI成功打包，但诊断artifact不包含完整APK清单。CI诊断保留3天，应及时保全。
- 按最终树重生便携补丁、公开干净基线应用/逐blob一致性复验；私有源基线与未发布变更对账、隔离应用及适配后同等门禁。不能直接合并不相干公开历史或丢弃私有工作。
- 设备Room/界面/PSS/fsync验收以及另行安排的NAS验收。详细命令保留在[整合交接](2026-10-13-integration-acceptance.md)。

## 历史文档的适用范围

10/11交接“11–14仅静态研究”、10/13 observation的367 library/4 ignored、摘要首切片“不含完整流式”、流式文档“引擎仅解析未在本地运行”均描述当时范围，不能覆盖本次已核验CI。server-hotpaths是schema-only实验，其22.2%不与整合21.1%混用；6项历史timing测试另行执行不等于本次CI取消了ignore。保留历史原始数据，不拼接成最新整App指标。

## 证据入口

- [当前公开CI证据与修复边界](../dev/evidence/integrated-2026-10-14/ci-report.md)
- [整合交接与完整手动验收命令](2026-10-13-integration-acceptance.md)
- [历史Rust整合报告](../dev/evidence/integrated-2026-10-13/integrated-server-report.md)
- [4,500原始样本](../dev/evidence/integrated-2026-10-13/samples.csv)、[汇总](../dev/evidence/integrated-2026-10-13/summary.json)、[元数据](../dev/evidence/integrated-2026-10-13/metadata.json)
