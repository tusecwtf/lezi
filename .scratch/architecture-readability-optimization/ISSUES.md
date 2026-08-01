# 架构可读性优化 · 票索引

Spec: [spec.md](./spec.md)  
**Status:** ready-for-agent  
Review HTML: `/var/tmp/zhangtianshu-tmp/architecture-review-20260801180255.html`

## Dependency graph

```text
01 删除 StructureTest ─────────────────────┐
02 QR → 家庭向导 ──► 03 Family 三缝 ───────┤
04 SyncPort 死表面（coord audit-16） ──────┼─► complete
05 tech.md 边（coord audit-17） ───────────┤
06 低价值测试 + UiPlaceholders ────────────┘
```

## Frontier

**01、02、04、05、06** → 然后 **03**（blocked by 02）

## Tickets

| ID | Title | Blocked by | Status |
|----|-------|------------|--------|
| [01](./issues/01-delete-structure-tests.md) | 删除无契约 StructureTest | — | ready-for-agent |
| [02](./issues/02-sink-qr-into-family-wizard-controller.md) | QR 登录进家庭向导并统一错误文案 | — | ready-for-agent |
| [03](./issues/03-split-family-hosts-by-flow.md) | 账户按 Overview / MembersDevices / Wizard 三缝切开 | 02 | ready-for-agent |
| [04](./issues/04-prune-dead-syncport-surface.md) | 修剪 SyncPort 死表面 | — | ready-for-agent |
| [05](./issues/05-align-tech-md-dependency-edges.md) | tech.md 依赖边与 Gradle 对齐 | — | ready-for-agent |
| [06](./issues/06-purge-low-value-tests-and-placeholders.md) | 清低价值测试与 UiPlaceholders | — | ready-for-agent |

## Coordination

- **04** ↔ audit **16** — once-only  
- **05** ↔ audit **17** — once-only  
- audit **18**（SyncPort 能力大拆）— **不做**

## Execution

Work the frontier with `/implement`, one ticket per fresh context. Prefer parallel 01/02/04/05/06 when agents don’t collide on the same files; serialize 02→03.
