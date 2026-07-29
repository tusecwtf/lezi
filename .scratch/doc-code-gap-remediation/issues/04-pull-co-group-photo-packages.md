# 04 — Pull 共组 record/care_plan↔log 媒体

**What to build:** 服务端 pull 依赖图在发出 live `record` / `care_plan` 时，**同页共组**其 live log 媒体（与 media→parent 对称）。客户端保持按页 stage 媒体再 apply，不引入跨页 hold。需 visit-mark 防止 parent↔media 递归。

**Blocked by:** None — can start immediately（与 02 可并行，但宜同 PR 或紧接合并）。

**Status:** ready-for-agent

- [ ] `collect_pull_entity_with_dependencies`：record/care_plan 分支 `append_log_media_for_parent`
- [ ] 提前 visit-mark，避免循环
- [ ] API/store 测试：根实体与照片不同 rev 时同页出现
- [ ] 不改变客户端 cursor 语义（仍按页完整 apply）
