# 05: 文案资源化——Tier A strings.xml + Tier B 常量收敛

**What to build:** 两层方案。Tier A：各 Android 模块建 `res/values/strings.xml`，跨模块共享
文案放 designsystem；Compose 层字面量迁 `stringResource()`，动态数量迁 `<plurals>`。顺序：
designsystem/core/ui → feature/log → family → settings → onboarding/timer/growth/search/
export/widget → app，每模块一提交。Tier B：`FailureCatalog`、`ShallowSyncStatus`、VM fallback
等非 UI 层、JVM 测试断言原文的文案收敛为模块级 Copy 常量对象（唯一源），暂不迁资源——
理由与边界记录于 spec.md（不引入 Robolectric，保住文案回归测试）。

**Blocked by:** 03/04（文案定稿后再机械迁移，避免双重 churn）

**Status:** partial

- [x] Tier B：FailureCatalog/ShallowSyncStatus/VM fallback 收敛 Copy 常量
- [x] Tier A 模式确立：模块自有 strings.xml + VM 状态化（见 feature/search）
- [ ] Tier A：feature/log（模式照 search，待做）
- [ ] Tier A：feature/family（待做）
- [ ] Tier A：feature/settings（待做）
- [x] Tier A：feature/search（示范迁移，VM errorMessage→failed 状态化）
- [ ] Tier A：onboarding/timer/growth/export/widget/app（待做）
- [x] 全量 test + lintDebug

## Comments

- 2026-09-05：Tier B 完成（LocalOpFailureCopy/WizardTrustCopy/SHALLOW_SYNC_RETRY_HINT 单源）。
  Tier A 以 feature/search 全量迁移确立模式：模块自建 `res/values/strings.xml`，
  Compose 层 `stringResource`，动态插值用 `%1$s` 位参，VM 不再持有文案（改 `failed` 状态，
  JVM 测试断言状态而非文本），semantics 里的文案在块外解析后传入。**调整**：跨模块共享
  文案不进 designsystem 资源（资源转发依赖别扭），维持 Tier B 常量单源——两个单源机制
  按受众分工：Compose 直渲染 → 模块 strings.xml；非 UI 层/JVM 断言 → 常量对象。
  剩余模块逐个照 search 模式迁移即可；工作区尚有 0.4.7 未提交改动，建议 0.4.7 合入后
  逐模块单独提交，避免机械迁移混入发布 diff。
