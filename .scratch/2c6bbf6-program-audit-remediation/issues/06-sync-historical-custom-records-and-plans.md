# 06 — 历史自定义记录与计划完整同步

**What to build:** 在 Android 端完成 tombstone 自定义定义下历史 Record/CarePlan 的编辑、删除与履行同步闭环，确保合法操作不会长期滞留 Outbox。

**Blocked by:** 05 — NAS 接受 tombstone 定义的历史引用

**Status:** complete

**Size:** M

## Acceptance criteria

- [x] 删除自定义定义后，历史记录仍显示保存时的名称/图标快照并可按权限编辑或删除。
- [x] 删除自定义定义后，既有计划仍可显式履行，产生的事实保留历史可解释快照。
- [x] 上述操作在 current client/server 组合中成功 push、收到确认并从 Outbox 排空。
- [x] 暂态网络/5xx 失败时，本地事实、计划状态与重试意图不丢失，恢复连接后按有界退避重试并可收敛。
- [x] 终态 4xx、ACL 或未知引用拒绝不会无限热重试或长期无提示卡住 Outbox；用户能看到可处置错误，且本地历史数据不被静默删除。
- [x] 另一台家庭设备 pull 后得到相同的历史标签、删除状态和计划履行结果。
- [x] UI 不把 tombstone 项重新提供给新的普通 Record/CarePlan 选择器。
- [x] 集成测试覆盖无照片与含照片根、进程重启、失败重试及双客户端最终一致。

## Validation evidence

- TDD red/green：Android 原先先提交履行 Record，当前 NAS 会因无法证明 tombstone 引用而
  拒绝；顺序改为 completed CarePlan → Record → FulfillmentCandidate 后，0/2 照片用例转绿。
- `:sync:testDebugUnitTest` 全量 279/279 与 `:sync:lintDebug` 通过；覆盖 503 保留并恢复、
  422 终止错误不热重试、历史编辑/删除 Outbox 排空和 tombstone 定义不进入 live selector。
- `:domain:testDebugUnitTest --tests '*CareLogTest.tombstonedCustomDefinition*'` 2/2 通过，覆盖
  保存时名称/详情/图标快照、编辑、删除、计划履行与两张照片。
- 当前 Rust 源码以同一 SQLite 重启后完成 current-wire 双凭据 smoke：零照片历史根和两照片
  履行根均提交；peer 从 cursor 0 得到相同标签、记录 tombstone、completed 计划、候选与
  2 个媒体实体。该证据是 wire/API peer，不冒充真实第二台 Android 设备。
- 固定 `e6742f1` 客户端/服务端组合完成两台 API 35 Android smoke：DeviceA 编辑历史记录并
  显式履行历史计划，服务端按 CarePlan → Record → FulfillmentCandidate 修订，Room 与
  Outbox 全部 clean；DeviceB 经正式邀请加入后从 cursor 0 得到相同 tombstone、保存快照、
  completed 链和 adopted 候选，冷启动仍可见。详见
  `../evidence/06/two-android-device-smoke.md`。

## Validation

运行 Android 自定义目录、Record/CarePlan、Outbox 与同步集成测试，完成 current-wire 双客户端
smoke，并在两台 API 35 Android 上完成 push/ack/drain、邀请加入、full pull 与冷启动验收。

## Documentation Gate

同步更新自定义项目生命周期与家庭同步验收说明。
