# 08 — 无宝宝状态直接接回 owner

**What to build:** fresh install 且本地没有宝宝时，允许 owner 通过既有“新建家庭”动作直接得到服务端 `reclaimed` 结果并恢复已有家庭；不得新增独立恢复入口，也不得先创建或上传额外本地宝宝。

**Blocked by:** 07 — 接回会话后立即执行全量 pull

**Status:** implemented-awaiting-device-smoke

**Size:** M

## Acceptance criteria

- [x] 无本地宝宝的首次流程继续只提供“新建家庭”和“加入家庭”两个主动作，不新增“接回/恢复管理员”入口。
- [x] owner 在“新建家庭”表单提交部署 bootstrap 后，服务端返回 `reclaimed=true` 时直接进入 Ticket 07 的恢复流程，不先写入本地宝宝。
- [x] 接回历史家庭后，远端原宝宝成为当前上下文，且 NAS 中不会多出临时创建的宝宝。
- [x] 服务端返回 `reclaimed=false` 的首次建家继续进入合理的新建宝宝步骤；接回家庭若远端确实无宝宝，也不会停在空白或循环跳转状态。
- [x] 验证失败、网络失败和凭据过期时保留已输入的非敏感表单状态，并给出可操作的重试/返回路径。
- [x] 配置变化与进程重建不重复提交 create/reclaim；忙碌期间不能重复提交。
- [x] 自动化测试覆盖 fresh install、历史家庭、空家庭、失败重试和“无额外宝宝上传”路由断言。

## Validation

运行 Onboarding、家庭会话与同步测试；在清空应用数据后分别 smoke 历史家庭接回与空 NAS 首次建家。

## Documentation Gate

更新首次使用和 owner 灾难恢复 PRD 流程图。

## Implementation evidence

- `OnboardingOwnerEntryController` 只复用 `SyncPort.createFamily` 与 Ticket 07 的同步恢复；
  bootstrap secret 不进入状态，非敏感表单由 saveable 状态保留。
- `OnboardingOwnerEntryControllerTest` 覆盖双主动作、created/reclaimed 分流、空家庭 fallback、
  验证/网络失败重试、busy/重建去重，以及恢复失败时禁止进入宝宝上传步骤。
- 自动化 gate：`:feature:onboarding:testDebugUnitTest`；另需按 Validation 执行历史 NAS 与空 NAS
  清数据 device smoke 后才能将本票改为 `complete`。
- API35 历史 NAS fresh-install smoke 已通过：首屏仅两个权威动作，同一 create/reclaim 提交
  直接恢复 `历史宝宝`；服务端与本机均只有一个 Baby，Room receipt 与强停重启证据见
  [`../evidence/07/fresh-install-current-head.md`](../evidence/07/fresh-install-current-head.md)。
- 空 NAS 首次建家 device smoke 尚未执行，因此本票继续保持
  `implemented-awaiting-device-smoke`，不提前标为 complete。
