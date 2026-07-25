# 08 — Growth Measurement 参考事实扩展

**Parent:** [../spec.md](../spec.md)

**What to build:** 在 Ticket 07 的 Growth Measurement module 内加入 Height、Head、修正月龄、参考带解析、范围提示与曲线点事实，并把 Growth UI 的剩余测量读取迁移到同一 interface。

**Blocked by:** [07-growth-weight-lifecycle-tracer.md](./07-growth-weight-lifecycle-tracer.md)

**Status:** done

**Dependency category:** local-substitutable；曲线 asset 为内部 seam

## Seam and deletion test

- 扩展既有 Growth Measurement interface，不为每种指标创建平行 module。
- 曲线数据读取、插值和单位换算留在 implementation。
- 删除新 module 时，月龄、参考带和曲线事实会回流到 Growth UI。

## Acceptance criteria

- [x] Height/Head 的 cm 创建、读取、编辑、删除通过 Ticket 07 同一 lifecycle
- [x] birthday/due date 的普通月龄与修正月龄在固定日期下可重复
- [x] 参考带插值覆盖范围内、低于 P3、高于 P97 与无参考带
- [x] malformed/缺失 curve asset 安全降级，Head 不伪造百分位带
- [x] Growth UI 的 Weight/Height/Head points、历史列表、warning 和 corrected toggle 均消费 module 事实
- [x] GrowthRoute 不再直接打开曲线 JSON，也不直接解析 measurement payload
- [x] Ticket 07 标记的 Height/Head 旧路径被删除

## Validation

- [x] Targeted：Growth Measurement reference facts + feature:growth
- [x] Compile：feature:growth 与 app debug
- [x] Static：`git diff --check`
- [ ] UI smoke：三种指标、修正月龄、无参考带路径

## Out of scope

- Record Composer 测量并轨（Ticket 09）
- 新曲线数据或医疗解释
- Growth 视觉重设计

## Comments

- Weight/Height/Head、月龄、reference bands 与 warning 均由同一 lifecycle facts 返回；curve asset parser/cache 保持 implementation-internal，malformed/缺失安全降级。
- 验证：Growth lifecycle/reference/catalog 定向测试、feature:growth/app 编译及 `./gradlew test assembleDebug -q`（exit 0）。设备 UI smoke 未运行。
- Documentation: N/A；未增加医疗解释或新曲线合同。
