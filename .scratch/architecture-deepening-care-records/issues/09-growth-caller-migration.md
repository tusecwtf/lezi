# 09 — Growth / Record Composer 测量 caller 并轨

**Parent:** [../spec.md](../spec.md)

**What to build:** 完成 Growth Measurement epic：让 Record Composer 的 Weight/Height/Head 路径与 Growth UI 跨同一 seam，删除两处重复单位/校验/测量转换，并清理 GrowthViewModel 的 refresh 与 raw Record 编排。

**Blocked by:** [08-growth-reference-facts.md](./08-growth-reference-facts.md)

**Status:** done

**Dependency category:** local-substitutable

## Seam and deletion test

- Growth UI 与 Record Composer 是两个真实 caller，证明 measurement seam。
- Composer 保留通用草稿/session interface；测量含义由 Growth Measurement module 提供。
- 删除新 module 时，测量规则会同时回流到两个 caller。

## Acceptance criteria

- [x] Growth UI 与 Record Composer 对同一 Weight/Height/Head 输入产生相同记录含义
- [x] 两个 caller 的单位、非正数、未来时刻、note 与宝宝隔离行为一致
- [x] Composer 新增/编辑测量后，Growth UI 通过观察结果更新
- [x] GrowthViewModel 不再维护 refresh-only counter，也不直接调用通用 Record CRUD 拼装测量
- [x] QuickRecordDraft/GrowthScreen 中重复的 measurement conversion 与 validation 被删除
- [x] `RecordComposerSessionGate`、其它 RecordType 草稿和 Growth 曲线 UI 保持
- [x] interface 测试覆盖两个 caller 的等价路径，而不是分别断言 JSON 字符串

## Validation

- [x] Targeted：feature:growth、feature:log、Growth Measurement
- [x] Full Growth closure：`./gradlew test assembleDebug`
- [x] Static：`git diff --check`
- [ ] UI smoke：从 Growth 与 Composer 各写一条测量，列表/曲线一致

## Out of scope

- Search/Export/payload 全局 cleanup（Ticket 10）
- Growth 新功能或新曲线
- Record Time 迁移

## Comments

- Growth 与 Composer 共用 lifecycle `validationError`/typed measurement facts；Growth 保存 rejection 会回显，观察流替代 refresh counter，raw Record CRUD/重复单位转换已删除。
- 验证：Growth lifecycle、Growth/QuickRecordDraft 双 caller 等价测试及 `./gradlew test assembleDebug -q`（exit 0）；`git diff --check` 通过。设备 UI smoke 未运行。
- Documentation: N/A；测量可见行为与持久化单位保持。
