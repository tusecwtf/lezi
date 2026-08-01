# 29 — Onboarding 拆壳、向导步和 QR UI

**What to build:** `OnboardingScreen.kt` 不再同时承载全部连接、信任、建家/加入、离线和扫码 UI；
同模块内形成导航壳、wizard 步态与 QR UI 文件，业务逻辑复用家庭向导 seam。

**Source:** merged directory C5
**Blocked by:** 23 — 成员登录 QR 逻辑先归一 (done)
**Status:** done
**Size:** M

## Acceptance criteria

- [x] `OnboardingScreen` 只保留导航壳/总状态收集，wizard 各步和 QR 扫码 UI 进入职责明确文件/子包。
- [x] 过重 host 状态可进入薄 ViewModel，但不得复制 `FamilyWizardController` 状态机。
- [x] 连接服务器、验证信任、新建/加入家庭、保持离线的产品顺序不变。
- [x] 未验证 endpoint 不覆盖上一次可信连接；QR 失败继续使用 Ticket 23 的统一错误映射。
- [x] 不新增 Gradle module 或 feature→feature 依赖；diff 不混入视觉重设计。

## Validation

运行 onboarding/domain/family/sync 相关 tests、`:feature:onboarding:compileDebugKotlin`、
`:app:assembleDebug`、`lintDebug`；设备 smoke 家庭向导、离线和扫码入口。

### Evidence

- `:feature:onboarding:testDebugUnitTest` green (adapter + join config + QR scan parse + host surface)
- `:feature:onboarding:compileDebugKotlin` / `lintDebug` green
- `:feature:family:testDebugUnitTest` green
- `:domain:testDebugUnitTest` green
- `:app:assembleDebug` green
- `:app:testDebugUnitTest` — `ContractSupersededSurfacesTest` updated for ViewModel path; green

## Implementation notes

Public seams (self-confirmed design notes):

- `OnboardingViewModel` — thin lifecycle over `FamilyWizardController` + local `createBaby`
- `OnboardingRoute` — navigation shell: step/dialog visibility + wiring only
- `onboardingFamilyWizardTransition` / snapshot helpers — pure outcome → step projection
- `parseOnboardingMemberLoginQrScan` — Ticket-23 unified scan error mapping (blank/invalid/expired)

Directories (same feature module, no new Gradle edge):

| Package / file | Responsibility |
|---|---|
| `OnboardingScreen.kt` | Shell: collect state, `when(step)`, wire dialogs |
| `OnboardingViewModel.kt` | Thin FamilyWizard host |
| `OnboardingModels.kt` | Steps, copy, transitions, date helpers |
| `steps/OnboardingWizardSteps.kt` | Choose / Connect / CreateFamily / CreateBaby / Recovery UIs |
| `wizard/OnboardingSessionDialogs.kt` | Join role, owner login/takeover, member request, waiting |
| `qr/OnboardingMemberLoginQrUi.kt` | Scan parse + confirm surface |

Regression: existing `OnboardingFamilyWizardAdapterTest` / `OnboardingJoinConfigTest`; new
`OnboardingMemberLoginQrScanTest`, `OnboardingHostCommandSurfaceTest`.

## Documentation Gate

只有调用流对用户可见步骤造成文档落点变化时才更新 PRD；纯文件拆分不写新 ADR。

## Out of scope

不改变家庭向导产品决策，不重做 onboarding 视觉，不变更 QR payload。
