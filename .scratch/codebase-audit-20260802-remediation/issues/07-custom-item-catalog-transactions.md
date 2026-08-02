# 07 · CustomItemCatalog update/move transactions

Status: ready-for-agent

## Findings

- `domain/src/main/kotlin/com/lezi/babylog/domain/catalog/CustomItemCatalog.kt:58-67`
  `updateCustomItem`:查重(read `listAll`)+ `update` 无事务;两个并发改名同撞名可同时通过。
  `addCustomItem`(39-55)已有事务与注释说明该并发约束。
- 同文件 91-106 `moveCustomItem`:N 次独立 `update(sortOrder)` 无事务,中途失败/并发
  会留下重复/不一致 sortOrder。

## Fix

- `updateCustomItem` 查重+写入、`moveCustomItem` 全部 sortOrder 更新分别包进
  `transactionRunner.run`,风格对齐 `addCustomItem`。

## Validation

- `./gradlew :domain:test`;回归:并发 update 同名 → 其一失败;move 中途失败不留半截
  (可用内存/fake DAO 模拟)。
