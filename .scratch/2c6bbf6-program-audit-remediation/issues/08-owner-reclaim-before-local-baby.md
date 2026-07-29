# 08 — 无宝宝状态直接接回 owner

**What to build:** fresh install 且本地没有宝宝时，允许 owner 通过既有“新建家庭”动作直接得到服务端 `reclaimed` 结果并恢复已有家庭；不得新增独立恢复入口，也不得先创建或上传额外本地宝宝。

**Blocked by:** 07 — 接回会话后立即执行全量 pull

**Status:** ready-for-agent

**Size:** M

## Acceptance criteria

- [ ] 无本地宝宝的首次流程继续只提供“新建家庭”和“加入家庭”两个主动作，不新增“接回/恢复管理员”入口。
- [ ] owner 在“新建家庭”表单提交部署 bootstrap 后，服务端返回 `reclaimed=true` 时直接进入 Ticket 07 的恢复流程，不先写入本地宝宝。
- [ ] 接回历史家庭后，远端原宝宝成为当前上下文，且 NAS 中不会多出临时创建的宝宝。
- [ ] 服务端返回 `reclaimed=false` 的首次建家继续进入合理的新建宝宝步骤；接回家庭若远端确实无宝宝，也不会停在空白或循环跳转状态。
- [ ] 验证失败、网络失败和凭据过期时保留已输入的非敏感表单状态，并给出可操作的重试/返回路径。
- [ ] 配置变化与进程重建不重复提交 create/reclaim；忙碌期间不能重复提交。
- [ ] 自动化测试覆盖 fresh install、历史家庭、空家庭、失败重试和“无额外宝宝上传”断言。

## Validation

运行 Onboarding、家庭会话与同步测试；在清空应用数据后分别 smoke 历史家庭接回与空 NAS 首次建家。

## Documentation Gate

更新首次使用和 owner 灾难恢复 PRD 流程图。
