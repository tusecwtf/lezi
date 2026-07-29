# 09 — 统一 Onboarding 与账户家庭向导

**What to build:** 让首次使用页与账户页共享同一套家庭向导状态机：一条 create 流程按服务端 `reclaimed` 区分首次建家与 owner 接回，另一条 join 流程处理成员加入；统一验证、忙碌、错误恢复和权限引导。

**Blocked by:** 08 — 无宝宝状态直接接回 owner

**Status:** complete

**Size:** M

## Acceptance criteria

- [x] Onboarding 与账户入口呈现同一组适用动作和一致的字段验证、错误文案与完成结果。
- [x] 权威状态机只有 create（结果为 created 或 reclaimed）与邀请码 join 两类流程；不维护第三条独立 owner 恢复流程。
- [x] 提交期间所有入口显示一致忙碌状态并阻止重复请求；成功只导航一次。
- [x] 配置变化和进程重建保留当前步骤与非敏感输入，不保存明文秘密超过完成流程所需范围。
- [x] 网络、权限和服务端业务错误均留在可恢复步骤，用户可重试、修改输入或安全退出。
- [x] 所需系统权限采用同一说明与跳转规则；拒绝权限不会破坏已经完成的家庭会话。
- [x] UI/状态机测试从两个入口覆盖 create-created、create-reclaimed 与 join 三种结果，并证明同一输入得到相同请求与结果。

## Validation

运行 Onboarding、账户、家庭状态机与 Compose 导航测试，以及应用编译和静态检查；设备上各 smoke 一次首次使用与账户入口。

## Documentation Gate

合并 PRD 中重复的家庭向导描述，保留一个权威流程和入口差异说明。

## Implementation evidence

- 验证基线父提交：`5a3a5b2833d2d82675610ea3a86714ca43e74b10`；下列证据均针对其上的 Ticket 09 变更。
- `FamilyWizardController` 是唯一 create/join 状态机；create 只通过返回的 `reclaimed`
  区分 created/reclaimed，Ticket 07 恢复失败只重试 pull，不重跑 create。单个 mutex 阻止重复提交，
  完成结果用 consume-once 事件投影；状态快照不含 bootstrap secret。
- Onboarding 与账户 ViewModel 均构造 `SyncFamilyWizardGateway`，UI 仅投影入口差异；旧的
  `OnboardingOwnerEntryController`、账户 `join/createFamily` 提交路径及重复 create 结果文案已删除。
- 两入口都由 `HomeWifiAccessGuideDialog` 提供相同权限说明、按钮文案和系统设置路由；权限拒绝或
  UI restore 不会覆盖 `Submitting/Completed`，非敏感草稿使用 saveable 状态，secret 只在当前内存表单。
- PRD `docs/prd/ui.md` §5.1 已合并为一个权威家庭向导流程，并单列 Onboarding/账户完成投影差异。

### TDD red / green

- Red：新增 domain 测试首先因 `FamilyWizardEntry/Controller/State` 尚不存在而编译失败；共享权限
  文案测试首先因 `HomeWifiAccessGuideCopy` 尚不存在而失败；随后新增“完成后可重新 begin”与
  “join 抛出网络异常可重试”用例，分别先因缺少 `begin` 和异常直接逃逸失败。
- Green：`FamilyWizardControllerTest`、`HomeWifiAccessGuideTest`、Onboarding 与账户 adapter 测试
  均通过；两入口分别覆盖 Created/Reclaimed/Joined 投影，共享 domain 测试证明相同输入得到相同
  create/join 请求和结果，并覆盖 busy 去重、consume-once、错误重试与已提交接回会话保护。

### Automated validation

- `./gradlew --no-daemon -Pksp.incremental=false :domain:testDebugUnitTest --tests com.lezi.babylog.domain.FamilyWizardControllerTest :core:ui:testDebugUnitTest --tests com.lezi.babylog.core.ui.HomeWifiAccessGuideTest :feature:onboarding:testDebugUnitTest :feature:family:testDebugUnitTest` — PASS。
- `./gradlew --no-daemon -Pksp.incremental=false :core:ui:lintDebug :domain:lintDebug :feature:onboarding:lintDebug :feature:family:lintDebug :app:testDebugUnitTest :app:compileDebugKotlin :app:assembleDebug` — PASS（607 tasks；Debug APK assembled）。
- 仓库没有覆盖这两个入口的 Compose instrumentation/navigation 测试；`:app:testDebugUnitTest` 覆盖
  root routing policy，但不冒充设备导航证据。
- API35 固定 APK 已分别完成两入口 smoke：首次使用入口在空 NAS 上走完 create-created，账户入口
  在保留本机宝宝、清除隔离测试家庭会话后同时显示 `新建家庭` / `加入家庭`，两者均打开同一
  `配置家庭网络` 步骤与相同 host/port/Wi-Fi 字段；join 仅按模式增加扫码邀请入口。固定 APK、
  页面语义与隔离测试数据处置见
  [`../evidence/09/shared-wizard-device-smoke.md`](../evidence/09/shared-wizard-device-smoke.md)。
