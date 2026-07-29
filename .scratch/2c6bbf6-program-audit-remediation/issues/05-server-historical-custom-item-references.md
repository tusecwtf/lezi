# 05 — NAS 接受 tombstone 定义的历史引用

**What to build:** 让 NAS 把“可供新建选择的目录项”和“可证明历史引用合法的目录项”分开校验，使 tombstone 定义继续支撑既有 Record、CarePlan 与履行写入，而不被重新展示或复活。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

**Size:** M

## Acceptance criteria

- [ ] 未删除的自定义定义仍可用于新建 Record/CarePlan；tombstone 定义不会重新出现在可选目录中。
- [ ] 已存在且属于同一家庭的 tombstone 定义可作为历史 Record/CarePlan 的引用完整性依据。
- [ ] 编辑或删除引用 tombstone 定义的历史 Record，以及履行已有计划，不再因“定义不存在”被拒绝。
- [ ] 客户端不能借历史引用创建新的普通事实或计划来绕过 tombstone 的不可选择规则。
- [ ] 未知定义、其他家庭定义、无权限成员和伪造引用继续被拒绝，既有 ACL 不放宽。
- [ ] 普通推送与 0–3 照片原子包使用同一引用规则；服务重启后结果一致。
- [ ] Rust 回归测试覆盖 active、tombstone、unknown、cross-family、历史编辑/删除和履行场景。

## Validation

运行 NAS server 全量测试与 lint，并以 current client/server 做历史自定义记录和计划的 API 集成验证。

## Documentation Gate

更新 wire/数据模型文档，明确 selectable catalog 与 referential existence 是两个不同契约。
