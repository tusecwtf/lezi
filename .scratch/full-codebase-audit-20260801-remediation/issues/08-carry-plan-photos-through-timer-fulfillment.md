# 08 — 计时履行继承护理计划照片

**What to build:** `completeNursing(carePlanId=…)` 在履行事务内读取计划当前 active 照片，为新 Record 创建独立 MediaAsset 行；计划照片所有权和顺序保持不变。

**Source:** `AUDIT-20260801-P1-08`  
**Blocked by:** None — can start immediately  
**Status:** ready-for-agent  
**Size:** M

## Acceptance criteria

- [ ] 含 1–3 张照片的 nursing CarePlan 经 Timer 完成后，Record 获得同顺序、独立 client UUID 的 active media 行。
- [ ] 原 CarePlan 的 media 行、顺序和物理文件保持 active；Record/Plan 可安全共享 local path，删除任一方不误删另一方文件。
- [ ] 计划读取、Record 写入、media clone、plan complete 与 candidate 写入在一个事务中；任一步失败不产生半履行。
- [ ] `completionClientUuid` replay 返回同一 Record 且不重复 clone media；已完成到其它 Record 时 fail closed。
- [ ] 事务以当前 plan media 为准，不依赖打开 Composer 时的陈旧 UI 快照。
- [ ] 生成的 Record + 0–3 media 继续走完整 atomic bundle，接收端不会先看到零照片事实。
- [ ] 无照片计划和非计划 Timer 完成行为不变。

## Validation

运行 domain CareLog/Timer/Replica 回归、`:app:assembleDebug`、`lintDebug`，并设备 smoke“带图护理计划 → 开始计时 → 完成 → 时间轴查看照片”。

## Documentation Gate

更新计时履行与计划/事实照片所有权说明。

## Out of scope

Composer 新导入照片的导航交接由 Ticket 09 处理。
