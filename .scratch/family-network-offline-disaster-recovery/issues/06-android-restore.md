# 06 — Android Owner 灾难恢复客户端

**Status:** complete

## Acceptance criteria

- [x] 只有旧 Owner + empty 候选出现恢复入口；普通成员不能进入。
- [x] 摘要覆盖宝宝/记录/计划/履行/自定义项/照片/本机待发布修改，排除身份与设置。
- [x] 根密码不持久化；恢复 token 进入安全凭证存储，支持续传/取消/commit 状态查询。
- [x] 成功后在 sync mutex 下切 session、目录、Outbox/回执；失败保留旧环境。
