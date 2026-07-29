# 06 — 历史自定义记录与计划完整同步

**What to build:** 在 Android 端完成 tombstone 自定义定义下历史 Record/CarePlan 的编辑、删除与履行同步闭环，确保合法操作不会长期滞留 Outbox。

**Blocked by:** 05 — NAS 接受 tombstone 定义的历史引用

**Status:** ready-for-agent

**Size:** M

## Acceptance criteria

- [ ] 删除自定义定义后，历史记录仍显示保存时的名称/图标快照并可按权限编辑或删除。
- [ ] 删除自定义定义后，既有计划仍可显式履行，产生的事实保留历史可解释快照。
- [ ] 上述操作在 current client/server 组合中成功 push、收到确认并从 Outbox 排空。
- [ ] 暂态网络/5xx 失败时，本地事实、计划状态与重试意图不丢失，恢复连接后按有界退避重试并可收敛。
- [ ] 终态 4xx、ACL 或未知引用拒绝不会无限热重试或长期无提示卡住 Outbox；用户能看到可处置错误，且本地历史数据不被静默删除。
- [ ] 另一台家庭设备 pull 后得到相同的历史标签、删除状态和计划履行结果。
- [ ] UI 不把 tombstone 项重新提供给新的普通 Record/CarePlan 选择器。
- [ ] 集成测试覆盖无照片与含照片根、进程重启、失败重试及双客户端最终一致。

## Validation

运行 Android 自定义目录、Record/CarePlan、Outbox 与同步集成测试，并完成 current-wire 双客户端 smoke。

## Documentation Gate

同步更新自定义项目生命周期与家庭同步验收说明。
