# 16 — 集中 structured-cancellation cause-chain policy

Status: implemented

Priority: P3

Audit baseline: `38cfbe7dbcf1f410237dce77b50ec1bd4854d8a6`

Blocked by: None.

## Findings

相同 cause walker 分别存在于：

- `domain/localdata/LocalDataClearCoordinator.kt:370-377`
- `feature/settings/BroadcastWorkBoundary.kt:27-34`
- `sync/LocalReplicaClearCoordinator.kt:204-210`

三处都判断 throwable cause chain 是否含 `CancellationException`，以决定原样 rethrow 或走普通失败。
规则复制会让以后处理循环 cause、结构化取消子类或错误包装时只修一处。

## Interface boundary

在三模块都可依赖的 `core:common` 放一个小型纯 cancellation policy。调用方直接调用该函数；不要为
每个 coordinator 增加一层 Manager/Adapter，也不要吞掉原始 cancellation instance。

## Acceptance

- [x] direct、nested、多层 wrapper、无 cancellation 与循环/异常 cause chain 都有确定结果
- [x] 三个私有 walker 删除，只剩一个规则 owner
- [x] 命中 cancellation 时原 instance/上下文按现合同 rethrow；普通异常仍走各自 UI/rollback 失败路径
- [x] 不把业务 timeout 一律误分类为 coroutine cancellation；现有 busy/feedback 状态不回归

## Validation

- [x] shared policy table tests 与三个调用点的行为 regression 通过
- [x] domain、settings、sync JVM tests及 app lint/assemble 通过

## Implementation evidence (validated worktree based on fixed HEAD `ab0d0383`)

- `core:common` 的 `cancellationCauseOrNull()` 是唯一 cause-chain 规则 owner：按对象 identity
  有界遍历，遇到损坏并抛错的 `cause` accessor 时停止检查并保留调用方原业务失败；命中时返回
  链上的原始 `CancellationException` instance。表测试覆盖 direct、nested、多层 wrapper、普通失败、
  `TimeoutException` / `SocketTimeoutException`、循环链与 throwing accessor。
- domain local-data clear、settings broadcast、sync local-replica clear 三处 finding walker 已删除并
  直接调用 shared policy。独立 Standards review 还发现 startup recovery 的同义 walker；本票一并
  删除，并以 nested wrapper regression 锁住原 cancellation instance 与 no-report 合同。全树扫描
  只剩 shared policy 一个 walker，不新增 Manager、Adapter 或测试专用生产 seam。
- 三个受影响 module 的完整 Debug JVM suites 通过（130 tasks）；post-review 全 Android `test`、
  `lintDebug`、`:app:assembleDebug`、带既有只读签名配置的 `:app:assembleRelease` 和
  `:app:compileDebugAndroidTestKotlin` 通过（1,741 tasks，2m26s；Release APK signature verified）。
  `git diff --check` 通过。独立串行 fixed-point review：Standards 0 hard / 0 judgement；
  Spec 0 hard / 0 scope / 0 judgement。
- `adb devices -l` 在允许主机访问后显示无连接设备；本票没有 UI、wire、Room schema 或 NAS/runtime
  server 变更，未运行设备 smoke，也未 build image、package、连接或部署家庭 NAS。
