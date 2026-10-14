# 乐记 0.5.5 整合验收核对表

审计时间：2026-10-13。当前运行时基点为 `cf787d7a16dc6520a6fcab60b3e5221999c0d76c`。这是验收状态清单，不是已完成声明；后续修复须更新源码和证据绑定。

## 总结

结构方面已存在可核对的生产实现：搜索共用一次投影上下文、固定容量计时、不可变 schema 期望缓存、媒体有界流式落盘、已有锁随 blocking 工作持有、媒体路径锁引用计数回收。性能方面有主机范围的可重放数据。正确性方面 Rust 整合门禁通过，Android 整树、双侧、Room、设备和私有回流尚未闭环。旧 owned 文件替换后的持久恢复修复已集成，主机store21项通过；4项新引擎回归尚未执行，不能写“只剩真机”。

## 提交与发布边界

- 公开 master 基线：`9e47fd6c9111eb5352cef585f4672a71fb578af4`。旧基线双 CI 为绿，不能归给新代码。
- 已发布 PR 11 头：`c7461bcd8b2d52c482d14746aba380295626be25`，只有媒体摘要首切片。Android run `38349801373` 失败于新重复下载 fixture，新整合尚无发布后的CI结果。
- fixture 修复整合提交 `bb52353c4ff31072248dfb7f375f13a3891e0137`，改用真实文件元数据。修复存在不等于 CI 已过，尚未发布。
- Rust 验证基点：`c5e541b595079fd915f44d0250417db3e10dd5de`；本次验证至 cf787d7 的 `tools/lezi-sync` diff 为空。
- 替换旧媒体回收：`d0183767a37db3e5a235dd8a10808c61e11a78a8`；媒体路径锁回收：`69775678b92dbcbe9fd4123bf6ec18dfae383306`。后续 `cf787d7a16dc6520a6fcab60b3e5221999c0d76c` 在提交前为旧owned路径重新建立持久marker，清理失败/进程重启仍可恢复；21项store测试通过，4项新增引擎回归未执行。路径gate单独主机回归不替代Room/Android验证。
- 私有源回流尚未验证；必须保留私有未发布工作，核对内容后回流，不能把公开整合视作已回流私有。
- 整合尚未发布，新 CI 尚未验证。未部署 NAS，未运行 ADB。

## 原 15 组逐项验收

“代码在基线”通过检查公开基线文件与当前目录差异确认；仅证明实现/测试源码存在，不证明当前整树测试成功。

| 组 | 内容与源码证据 | 已验证范围 | 发布与剩余验收 |
|---|---|---|---|
| 1 | 出生体重：BabyProfileConstraints、BirthWeightInputTest、BabyProfileValidationDeviceTest；代码在基线 | 解析/字段回归源码存在；本次未跑 Android | 公开基线已有；当前 JVM、非法原文保留和字段独立错误设备验收待跑 |
| 2 | CTA 角色、busy、禁用原因：ActionStateComponents 及 DeviceTest；代码在基线 | 语义测试源码存在 | 两模板点击/焦点/读屏及忙态实际验收待跑 |
| 3 | 相机拒绝、恢复、owned capture：CameraPermissionRecovery、OwnedCameraCaptureSessionsTest 和入口 DeviceTest；代码在基线 | 取消/所有权及权限恢复测试源码存在 | 真权限拒绝、设置往返、取消不新建文件、不自动拍照待设备验证 |
| 4 | 切宝宝/申请反馈：CurrentBabySwitchActionTest、CurrentBabySwitchFeedbackDeviceTest、MemberApprovalWaitingDeviceTest；代码在基线 | 错误范围与有效期相关测试存在 | 失败保留原宝宝、可重试、未知截止时间及服务端裁决待设备验证 |
| 5 | 睡眠低动态：SleepComposerMotionDeviceTest；代码在基线 | 静态/动画路径测试源码存在 | 系统动画 0/0.5/1、两模板确认/焦点/可见变体待设备验证 |
| 6 | 脏草稿/配置重建/偏好：BabyEditDraft、JVM 和 DeviceTest；代码在基线 | 净/脏、保存锁、4015g 边界源码存在 | 当前 JVM、旋转重建、成功仅消费一次待验证；不能外推进程死亡 |
| 7 | 大字/窄屏/IME | 历史交接仅静态风险，无截断或重叠实证 | 未完成；360dp、短视口、IME、warm/journal×明暗×l1/l2/l3 设备核验，发现问题再修 |
| 8 | 旧查询/宝宝/ACL 失败只读；另有本轮睡眠查询投影优化 | 当前 Python SQLite 合同 7/7 本次重跑通过；129/1024 根读取从 387/16384 降为 129/1024，查询计划走 UUID/adoption 索引 | 基线 UX 已公开；新 SQL/共享投影未发布。当前 JVM、KSP/Room 构建、真实 Room 查询计数和 UI 权限恢复待验证 |
| 9 | 图表逐日无障碍：SummaryChartAccessibilityTest、SummaryChartDailyAccessibilityDeviceTest；代码在基线 | 首末日、缺失/真实零值等源码存在 | 公开基线已有；TalkBack 逐日、单位、无重复合并描述待设备验证 |
| 10 | localPhotoCacheSnapshot：entries/pinnedEntries/totalPins/estimatedDecodedBytes；代码在基线 | 按需数字快照和 pin/LRU 回归源码存在 | 五路径/三图实测未完成；进入、快滑、退出 5s/30s 的 PSS+pin 配对待做；估计解码字节不是 PSS |
| 11 | Rust 固定阶段探针、Android 九阶段 process-wide 探针 | Rust sampler 5 warmup+30；主机probe8项通过；最新store21项另列第13组，不重复累加旧22项；Android sampler 尚无整合 CI 成功结果 | 新探针未发布；真实引擎双侧阶段样本及照片解码/压缩、URI/hash、Room 等未全部实测 |
| 12 | 不可变 schema 期望缓存 d206490；实际每库验证保留 | Rust 582 全套、fmt、Clippy；整合 15 轮/4,500 样本 | 未发布；主机已有 DB 初始化实证，不代表首次建库、NAS或 HTTP 吞吐 |
| 13 | 流式 transport→exclusive owned file→receipt→page apply；95ff109 等；d018376及cf787d7持久回收 | 最新21项owned-store主机测试；body→file微基准不含后来replacement re-arm成本；4项新增引擎回归及整合Android未执行 | 未发布；持久旧文件回收已实现；引擎失败/重启回归、整合全套和Android fsync行为待验证 |
| 14 | 15 个已有 async 锁由 blocking 工作持有；440da164 | 整合 Rust 582 包含取消/排队/panic及实际 Router 回归 | 未发布；没有新增全面锁覆盖，不强停 SQLite 工作；冻结的真实家庭并发实验未运行 |
| 15 | 总整合、交接、私有回流、设备验收 | 本清单及整合 Rust 证据可核对 | 未完成：持久修复已集成→冻结SHA→全Android/双侧→发布→核对新 CI→私有 portable patch 回流验证→设备验收。NAS另需窗口 |

## 三目标的可接受结论

1. 结构：可说“减少重复投影/散列与 payload 大小数组，新增 bounded 诊断和明确资源所有权”；不能说“整 App 架构已完整验收”。路径 gate 13 新测试+4 既有测试共 17 通过，新增套件重复 20 轮；使用注解 stub 的主机编译不是 Room 执行。
2. 性能：可说“整合主机 warm Store 中位下降 76.7–77.8%，每规模 15/15 paired round 更快；1k cold server init 21.221→16.752ms，21.1%，15/15 更快”。10k cold init 105.234→103.251ms，仅 8/15 更快，p95/max 120.026→128.372ms，不能宣称稳定改善。
3. 正确性：可说“c5e541b Rust 368 library+208 API+1 golden+5 TLS=582，0失败，6 timing-only ignored 均另行执行”；不能说“当前整合Android全过”“PR11 已修好/已合并”“私有 master 已同步”“仅差真机”。

补充性能范围：首切片 desktop JVM 32-byte hex 7,533.6→167.5ns，16MiB retained hash 14.780→12.956ms、分配16,798,392→9,440B。后续 8MiB warm host body→adopted file 12.229→9.849ms、分配16,797,360→20,920B，以c7461bc摘要已优化版本为基线，不重复计首切片收益；不含后来旧文件replacement re-arm成本。后者包含 marker/directory/file fsync，但不含 HTTP/TLS、Room、path gate 或 Android；均非 peak heap/PSS。

## 还需关闭的非设备缺口

- 在最终Android构建中执行4项新引擎回归，核对旧owned文件清理失败、lease release失败、precommit取消、共享旧路径去重。持久marker修复及21项主机store回归已完成，不能替代引擎/Room和Android存储验证。
- 在最终 SHA 上运行全 `./gradlew test`、相关 instrumentation 源码构建和 app debug 构建。后续本地提交 `5631ec1721103c217834581330f9aa49a67cbb27` 已将CI配置为全模块assembleDebugAndroidTest加app debug构建；尚未执行/发布，不能声称UI模块APK全已产出。
- 同树 service 二进制+sync/domain 两组 isolated seam；检查 JUnit XML skipped=0，不能以探针默认跳过或缺 openssl/二进制算通过。
- RealServerSyncPerformanceSampleTest 必须 opt-in 实际执行，保留 source SHA、binary SHA、checkout/GITHUB_SHA 区别、原始阶段数据；这不是手机性能。
- 更新交接和冻结制品 SHA；发布及新CI结果；保留 CI 短期制品（workflow 当前为 3 天）。
- 私有源基线/未发布提交/变更树对账和可携带补丁应用验证，不能直接合并不相干公开历史。

## 文档中容易造成误读的陈述

- 10/11 交接的“11–14 只有静态研究、没有 collector”属于历史状态，现已追加当前整合入口，不能继续当当前结论。
- 10/13 observation 的 367/208/1/5 与 4 ignored 是旧切片；最新整合为 368/208/1/5 与 6 ignored。当前整合对应562e5c2/440da164，不应误作发布头。
- media-digest-performance 的“完整流式不包含”仅限首切片；当前生产路径已流式。当前整合交接已标注其历史scope，并与流式结果区分。
- server-hotpaths 文档明确是 schema-only 数字；不要混用其 22.2% 与整合的 21.1%，应另列整合表。
- media-streaming-ownership 列出的引擎/transport 测试“存在”不等于“全运行通过”；持久回收修复已集成，仍不能说“全部Android引擎回归通过”。

## 证据入口

- [当前整合交接与手动验收](2026-10-13-integration-acceptance.md)
- [Rust整合报告](../dev/evidence/integrated-2026-10-13/integrated-server-report.md)
- [4500原始样本](../dev/evidence/integrated-2026-10-13/samples.csv)、[汇总](../dev/evidence/integrated-2026-10-13/summary.json)、[元数据](../dev/evidence/integrated-2026-10-13/metadata.json)
- 仓库对应生产/测试源码、两份历史交接、Android计时/摘要/流式/服务器hotpath文档。

后续本地CI配置提交 `5631ec1721103c217834581330f9aa49a67cbb27` 已集成：任务选择为 `./gradlew test :app:assembleDebug assembleDebugAndroidTest`，保留45分钟和原workers设置，尚未执行/发布。Android和设备测试源码存在不等于实际通过。
