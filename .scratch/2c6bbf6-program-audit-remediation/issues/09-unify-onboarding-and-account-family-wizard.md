# 09 — 统一 Onboarding 与账户家庭向导

**What to build:** 让首次使用页与账户页共享同一套家庭向导状态机：一条 create 流程按服务端 `reclaimed` 区分首次建家与 owner 接回，另一条 join 流程处理成员加入；统一验证、忙碌、错误恢复和权限引导。

**Blocked by:** 08 — 无宝宝状态直接接回 owner

**Status:** ready-for-agent

**Size:** M

## Acceptance criteria

- [ ] Onboarding 与账户入口呈现同一组适用动作和一致的字段验证、错误文案与完成结果。
- [ ] 权威状态机只有 create（结果为 created 或 reclaimed）与邀请码 join 两类流程；不维护第三条独立 owner 恢复流程。
- [ ] 提交期间所有入口显示一致忙碌状态并阻止重复请求；成功只导航一次。
- [ ] 配置变化和进程重建保留当前步骤与非敏感输入，不保存明文秘密超过完成流程所需范围。
- [ ] 网络、权限和服务端业务错误均留在可恢复步骤，用户可重试、修改输入或安全退出。
- [ ] 所需系统权限采用同一说明与跳转规则；拒绝权限不会破坏已经完成的家庭会话。
- [ ] UI/状态机测试从两个入口覆盖 create-created、create-reclaimed 与 join 三种结果，并证明同一输入得到相同请求与结果。

## Validation

运行 Onboarding、账户、家庭状态机与 Compose 导航测试，以及应用编译和静态检查；设备上各 smoke 一次首次使用与账户入口。

## Documentation Gate

合并 PRD 中重复的家庭向导描述，保留一个权威流程和入口差异说明。
