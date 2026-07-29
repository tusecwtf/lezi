# 05 — 照片 reconcile/tombstone 按所有者参数化

**Parent:** [../spec.md](../spec.md)

**What to build:** 护理记录与护理计划的附件 reconcile / soft-delete 使用同一算法，仅所有者外键（记录 vs 计划）参数化。附件上限、dirty 戳、按路径 revive 行为两边一致，且不打破 ADR-0003 所有权 XOR。

**Blocked by:** None — can start immediately

**Status:** complete

**Size:** M  
**Theme:** C（R8）  
**Seams:** CareLog 附件写入

## Acceptance criteria

- [x] 记录与计划照片写入/替换/清空走共享实现（参数化 owner），无两份复制算法体
- [x] 既有照片上限（每条最多三张）、tombstone、syncDirty 行为有回归
- [x] 禁止出现同时挂 record 与 carePlan 的歧义附件行
- [x] domain 目标测试通过

## Implementation notes

- `PhotoAttachmentReconciler` 是 Record 与 CarePlan 附件 reconcile/tombstone 的唯一算法；
  `PhotoAttachmentOwner.Record` / `.CarePlan` 以正本地 ID 构造，在类型层表达 owner XOR。
- seam 内统一 trim、去空、稳定去重后才执行 `MAX_RECORD_PHOTOS` 上限；保留活跃行、按
  规范化路径 revive 既有 tombstone（保留 clientUuid）、只为真正新行生成 UUID，并以
  单调 `updatedAt` + `syncDirty=true` 写入 revive/tombstone。
- CareLog 的 Record/CarePlan 创建、编辑、履行、转换、删除调用点直接使用 typed owner；
  原 nullable FK wrappers、两套 normalize 入口和旧私有算法体已删除。CarePlan 只替换或
  清空照片、其他字段不变时也会形成同一事务内的原子 bundle mutation。
- reconciler 不另开事务；CareLog 继续拥有根实体与附件的 domain transaction。故障注入
  覆盖 revive 成功、新行插入后 tombstone 失败，断言全部媒体写回滚且根行不变。

## Validation evidence

- TDD 保存 public seam 缺失、replace/revive 未实现、tombstone API 缺失及 CarePlan
  photo-only 更新未接线的四次有效 RED，随后逐 slice GREEN。
- `./gradlew :domain:testDebugUnitTest :domain:lintDebug :app:assembleDebug --no-daemon`：通过。
- 普通路径、Record/CarePlan 共享物理路径、正 owner ID、规范化后 ≤3、四条唯一路径拒绝、
  替换、显式清空、revive UUID 保留、两 owner tombstone、单调时间与事务失败均有回归。
- 完整回执见 `../evidence/05/validation.md`。

## Out of scope

- 预览 UI（03）
- 原子推送管道（04）
- 整文件拆分 CareLog（08，可依赖本票结果）
