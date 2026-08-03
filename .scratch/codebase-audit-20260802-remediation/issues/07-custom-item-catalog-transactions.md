# 07 · CustomItemCatalog update/move transactions

Status: complete — implementation `6b278242`; regression accepted on `d160fe68`

## Findings

- `domain/src/main/kotlin/com/lezi/babylog/domain/catalog/CustomItemCatalog.kt:58-67`
  `updateCustomItem`:查重(read `listAll`)+ `update` 无事务;两个并发改名同撞名可同时通过。
  `addCustomItem`(39-55)已有事务与注释说明该并发约束。
- 同文件 91-106 `moveCustomItem`:N 次独立 `update(sortOrder)` 无事务,中途失败/并发
  会留下重复/不一致 sortOrder。

## Fix

- [x] `updateCustomItem` 查重+写入、`moveCustomItem` 全部 sortOrder 更新分别包进
  `transactionRunner.run`,风格对齐 `addCustomItem`。

## Validation

- [x] `./gradlew :domain:test`;move 中途失败不留半截的 fake DAO 回归通过。
- [x] `d160fe68` 补齐并通过并发 update 同名 → 其一失败的回归。
