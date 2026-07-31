# 03 — 履行时剥离 next-feed 内部 note marker

**What to build:** 用户履行带「下次喂养」内部标记的护理计划后，产生的护理记录备注中**不再出现**内部协议前缀；只保留用户可见备注。不扫描或改写历史上已经脏掉的记录行，避免无意义家庭 LWW。

**Blocked by:** 02 — 事实写入关闭未来时间（同改履行落库，串行避免冲突）

**Status:** done

**Severity:** P1  
**Lane:** immediate

- [x] 履行落库时，若调用方未显式给 note，则从计划 note 推导用户可见部分，剥离内部 next-feed marker 后再写入护理记录。
- [x] 调用方显式传入的 note 不因本票被错误加上 marker；对 next-feed 计划 fail-closed：即使显式 note 仍带协议前缀，护理记录只保留用户可见部分。
- [x] 回归：安排 next-feed → 以 null/省略 note 履行 → 记录 note 不含 marker；有用户备注时只保留可见部分；完成后计划行仍保留 storage marker（不 scrub）。
- [x] **不**做历史数据扫库 scrub，**不**仅因剥离 marker 批量 mark syncDirty。
