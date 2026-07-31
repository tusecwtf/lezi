# 04 — client_update_required 后强制 UI 兜底

**What to build:** 服务端已因 minSupported 拒绝权威同步时，若元数据暂时拉不到，用户**仍**看到不可用主同步、且有强制升级向的界面（重试检查/说明），而不是 `SyncStatus.Idle` + 无强制层、也无错误的「假正常」。

**Blocked by:** None — can start immediately

**Status:** done

## Acceptance criteria

- [x] `client_update_required` 处理后：元数据成功 → 现有强制态；元数据失败 → 仍有 forced/unknown 壳或等价非模糊状态，并提供重试检查更新
- [x] 不得把该情况映射为泛「同步遇到问题」除非确属传输层且文案可区分
- [x] 单测：模拟 gate + metadata failure 后 UI 可观察状态非「静默 Idle」
- [x] 强制全屏尽量消费返回键（避免系统 back 绕过视觉遮罩）；至少不引入「稍后」绕过主功能

## Comments

- Review: B5（correctness）；plan BackHandler suggestion
- Implement: `ForcedAppUpdateState` (`WithPackage` | `PackageUnknown`) on `availableForcedAppUpdate()`; `publishForceShellPreservingPackage` shared helper; only accept Forced classification after CUR; skip discover after CUR; root overlay above onboarding + `BackHandler` + pointer sink; settings/family `ForcedUpdatePackageUnknown`; PRD §4.2 / 7.3–7.4; evidence `evidence/04/validation.md`.
- Fix r1: helper extract, checkAppUpdate test, dual-model alignment, onboarding/hit-test, copy, evidence, typed install-permission flag.
- Fix r2: collapse CUR metadata recover into `resolveForceShellAfterClientUpdateRequired` (sync + checkAppUpdate); Family PackageUnknown retry mirrors Settings (`checkAppUpdate` + `checkingAppUpdate` busy). Shared secondary dialog extract left as intentional dual-feature copy (root shell authoritative).
