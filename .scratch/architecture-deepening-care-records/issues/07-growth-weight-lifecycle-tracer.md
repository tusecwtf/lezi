# 07 — Growth Measurement Weight 生命周期 tracer

**Parent:** [../spec.md](../spec.md)

**What to build:** 建立 deep Growth Measurement module 的第一条端到端 tracer：以 Weight 为唯一测量类型，覆盖创建、观察、编辑、删除、kg↔g 转换与 Growth UI 更新。Height/Head、修正月龄和参考带暂留旧路径。

**Blocked by:** [05-care-payload-composer-writes.md](./05-care-payload-composer-writes.md)

**Status:** done

**Dependency category:** local-substitutable；测试使用 fake DAO 或 in-memory Room adapter

## Seam and deletion test

- interface 表达 Weight 测量生命周期，不暴露 DAO、Record 或 raw payload。
- UI caller 通过与测试相同的 seam 观察变化。
- 删除新 module 时，单位、校验和持久化编排会回流到 GrowthViewModel。

## Acceptance criteria

- [x] 比较至少两个 lifecycle interface 形状，以 depth、可观察性与测试成本选择一个并记录理由
- [x] local adapter 测试完成 Weight create → observe/read → update → delete
- [x] kg 输入与 g 持久化含义往返一致，非正数与未来测量时刻不写入
- [x] Weight 的 note、timestamp、软删与当前宝宝隔离保持
- [x] Growth UI 的 Weight tab 通过新 module 加载和保存，不直接读取 raw Record/payload
- [x] Weight 新增、编辑、删除后通过观察结果更新，不使用 caller-owned refresh counter
- [x] Height/Head 旧路径有明确临时标记，并由 Ticket 08 移除；不创建第二个外部 seam

## Validation

- [x] Targeted：Growth Measurement Weight interface + feature:growth Weight 行为
- [x] Compile：feature:growth、domain 与 app debug
- [x] Static：`git diff --check`

## Out of scope

- Height/Head、修正月龄、参考带
- Record Composer 测量并轨
- Record Time 迁移

## Comments

- 选择 observable lifecycle facts + `save/delete` decision，而不是 CRUD repository 或 caller refresh；生产 CareLog adapter 与 fake store 共用同一 interface 测试。
- Weight create/observe/update/delete、kg↔g、未来/非正数、跨宝宝/跨指标 mutation 自动化通过；`./gradlew test assembleDebug -q`（exit 0）。
- Documentation: N/A；Height/Head 临时旧路径已在 Ticket 08 删除。
