# 26 — 修正账户与设置页错误可供性

**What to build:** 让账户与设置页只对真实可执行的项目显示点击/箭头暗示，并把同步状态入口提升到可访问的最小触控目标。

**Blocked by:** 09 — 统一 Onboarding 与账户家庭向导

**Status:** complete

**Size:** S

## Acceptance criteria

- [x] 当前没有任何动作的宝宝信息行不再显示 chevron、按压反馈或可点击语义。
- [x] 若未来/现有宝宝行确有动作，则整行、箭头、标签和 TalkBack 描述都指向同一明确结果。
- [x] 同步状态入口的有效点击区域至少为 48dp，视觉布局可保持紧凑但不能只点击小图标。
- [x] 同步状态入口具有名称、当前状态和动作语义；禁用/离线状态不只依赖颜色。
- [x] 家庭向导入口继续使用 Ticket 09 的权威流程，不从设置页恢复平行弹窗或分支。
- [x] Compose 语义测试断言真假点击能力、最小触控范围、焦点顺序和操作结果。

## Validation

运行账户/设置 Compose 测试、应用编译和静态检查，并完成 TalkBack 与触控设备 smoke。

已通过：

- `SettingsAffordanceSemanticsTest`：3/3（API 35 AVD）。
- `FamilyAccountAffordanceSemanticsTest`：2/2（API 35 AVD）。
- `:feature:settings:testDebugUnitTest`、`:feature:family:testDebugUnitTest`。
- `:feature:settings:lintDebug`、`:feature:family:lintDebug`、`:app:assembleDebug`。

设备限制：共享 API 35 AVD 未安装 TalkBack；当前 connected tests 已覆盖 TalkBack-facing
name/role/state/action/focus/bounds，并通过真实输入注入验证点击结果，但不冒充 spoken TalkBack
smoke。固定 APK 的完整应用触控 smoke 已验证只读宝宝行、可操作本机设置行和同步状态整行；
完整证据见 [`../evidence/26/validation.md`](../evidence/26/validation.md)。

## Documentation Gate

如设置页信息架构或入口文案变化，同步更新相应 UI 规格。

Disposition：无需新增产品规格；实现保持 [`docs/prd/ui.md`](../../../docs/prd/ui.md) §5.7
“同步状态整句可点 → 网络设置”与 §5.8“member 仅保留切换与本机主题/排序”的既有权威，
未新增家庭向导入口。
