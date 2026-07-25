# Spec: 照护记录架构深化

Status: done
Feature: architecture-deepening-care-records
Product: 乐记 (`com.lezi.babylog`)
Source: architecture review 2026-07-25 · 用户确认“全部 to ticket”

---

## Goal

围绕近期最频繁变化的照护记录、汇总、成长测量与记录时刻链路，把重复规则从多个 shallow module 收回到少量 deep module：

- caller 通过更小的 interface 获得更高 leverage；
- schema、时间窗口、测量单位与时区规则获得 locality；
- interface 成为 caller 与测试共用的 test surface；
- 迁移完成后删除旧的 shallow helper 和平行 implementation，而不是在旧路径外再叠一层。

本 spec 不锁定类名、方法名或参数形状。实现者必须先根据现有 caller 选择 seam，再以 depth、locality、leverage 和 deletion test 说明 interface 取舍。

## Candidates

| # | Deep module 方向 | Strength | Dependency category | 核心摩擦 |
|---|------------------|----------|---------------------|----------|
| 01 | Care Summary | Strong / Top | in-process | 日、周、汇总页分别解释窗口与睡眠，语义已分叉 |
| 02 | Care Record Payload | Strong | in-process；存储为 local-substitutable | JSON 字段、单位、默认值和兼容规则泄漏到六类 caller |
| 03 | Growth Measurement | Strong | local-substitutable | Growth 与 Record Composer 两条真实路径重复测量语义 |
| 04 | Record Time | Worth exploring | in-process | designsystem 与 caller 共同拥有 DST、日期、未来时刻和区间规则 |

## Positive baseline

`RecordComposer` 已通过 deletion test，是本轮保留的 deep module：

- 新增与编辑通过同一 `RecordComposerRequest` / `RecordComposerHost` seam；
- 加载、保存、删除、睡眠并发与 session gate 位于 implementation 内；
- 记录页与搜索页都从该 seam 获得 leverage。

本轮不得恢复 `RecordEditScreen`，不得重新拆出平行新增/编辑路径，也不得把 `RecordComposerSessionGate` 暴露为外部 interface。

## Fixed constraints

### Behaviour compatibility

- 记录新增、编辑、删除、睡下/醒来、搜索后编辑、汇总图、成长曲线、日历选时与时钟交互保持当前可见行为。
- 跨午夜睡眠、进行中睡眠、软删、多宝宝隔离、护理记录单位和未知 JSON 字段不得在迁移中丢失。
- 默认不改 Room schema、同步协议、outbox wire format 或导出格式；若实现证明不可避免，必须停止该 ticket 并先记录迁移风险与兼容方案。

### Seam discipline

- 01 与 04 的主体为 in-process，不为“以后可能变化”创建 port。
- 02 与 03 可使用现有 fake DAO / in-memory Room 作为 local adapter；测试 seam 保持在 deep module 内部。
- 一个生产 adapter 加一个测试 adapter 才能证明真实 seam；单一 adapter 不得成为额外间接层的理由。
- 只有 caller 需要知道的约束进入 interface；解析 helper、DAO、JSON、时钟替身和曲线文件读取应留在 implementation 或内部 seam。

### Testing

- interface 是 test surface：测试通过 caller 同样使用的 interface 断言可观察结果。
- 迁移采用“先证明行为等价，再切 caller，最后删除旧路径”。
- deep module 的 interface 测试建立后，删除只绑定 shallow helper 内部结构的旧测试；不保留两套同义测试长期并行。
- 每张票执行 targeted test 与受影响 module 的编译；每个 epic 的 closure ticket（03、09、10、13）执行 `./gradlew test assembleDebug`。
- 失败时区分代码问题与 Gradle 缓存/权限环境问题。

### Documentation update gate

- 若用户可见行为、持久化/同步/导出合同发生变化，更新对应文档。
- 若产生必须长期保留的新领域术语或架构决策，再新增 `CONTEXT.md` 或 ADR；没有此类变化时，在 ticket Comments 写明 `Documentation: N/A`。

## Delivery graph

```text
initial frontier
├── 01 Summary: daily Log tracer
│    └── 02 Summary: rolling Summary
│         └── 03 Summary: Widget + cleanup
├── 04 Payload: compatibility tracer
│    └── 05 Payload: Composer writes
│         ├── 06 Payload: Summary + Timeline readers ← 03
│         └── 07 Growth: Weight lifecycle
│              └── 08 Growth: reference facts
│                   └── 09 Growth: caller migration
│                        └── 10 Payload: remaining readers + cleanup ← 06
└── 11 Time: decision contract
     └── 12 Time: Clock + Log + Composer ← 05, 09
          └── 13 Time: Growth + Calendar cleanup ← 09
```

依赖同时表达语义顺序与文件冲突：

- 01、04、11 是三个初始 tracer，可独立建立 seam 与 test surface；
- 06 等 03 完成后再迁移已经统一的 Care Summary caller；
- 07–09 在 05 的测量 payload 写路径稳定后深化 Growth Measurement；
- 10 等 06 与 09 完成后删除最后的原始 payload reader；
- 12 等 05、09、11 完成，避免与 Composer/Growth 的其它迁移并发冲突；
- 13 在 Growth 与 Clock 主路径稳定后清理剩余时间 helper。

## Completion gates

- [x] 13 张 ticket 均为 `done`
- [x] 旧聚合、payload parser、测量转换和时间转发路径按各票要求删除
- [x] `RecordComposer` 单一路径与 session gate 保持
- [x] `./gradlew test assembleDebug` 成功
- [x] `git diff --check` 成功
- [x] 未引入无第二 adapter 的 port
- [x] 每张票的 Comments 记录验证命令与 Documentation gate 结论

## Current evidence

- Care Summary：`domain/DailySummary.kt`、`domain/WeekSummary.kt`、`feature/summary/SummaryAggregation.kt`
- Care Record Payload：`feature/log/QuickRecordDraft.kt`、`core/ui/RecordPresentation.kt`、`domain/RecordSearch.kt`
- Growth Measurement：`feature/growth/GrowthScreen.kt` 与 `QuickRecordDraft.kt` 的两条测量路径
- Record Time：`designsystem/ClockDial.kt`、`feature/log/RecordTime.kt`、Growth 与 Calendar caller

## Out of scope

- 新产品功能、视觉重设计、新图表类型或新测量类型
- 真同步能力扩展、服务器改造、数据库 schema 升级
- WHO/医疗解释、诊断建议或新参考曲线数据
- 重写 `RecordComposer`、恢复旧编辑页面
- 仅为了目录整齐而移动无关文件

## Comments

- 2026-07-25: 从 architecture review 的 4 个候选发布；未在票面预设具体方法签名。
- 2026-07-25: 用户确认粒度复核；4 张 broad issue 改为 13 张 tracer ticket，开放 3 个初始 frontier。
- 2026-07-25: 13 张 tracer 完成；Care Summary、Care Record Payload、Growth Measurement、Record Time 四个 deep module 及 caller migration 通过 targeted 自动化。
- 2026-07-25: 完整门禁 `./gradlew test assembleDebug -q` exit 0，`git diff --check` 通过；设备 UI smoke 未运行，未据此声称设备级验证。
- 2026-07-25: Documentation: N/A；本 feature 未改变用户可见行为、Room schema、同步/outbox wire 或导出合同。
