# 24 — 护理计划原子家庭同步与本机投影

**What to build:** 在 Android 端完整接入护理计划原子同步；计划与全部照片一次性对家庭可见，收到完整计划后才建立当前设备自己的提醒或系统日历副本。

**Blocked by:** 08 — 自定义项目家庭同步与服务端 ACL; 11 — 记录照片编辑、删除与恢复; 15 — 全部非状态型具体项目可安排; 16 — 母乳计划履行语义; 17 — 睡眠计划履行语义; 18 — 计划三张照片与履行带入; 19 — 护理记录显式转为护理计划; 22 — 系统日历三级披露与副本生命周期; 23 — 护理计划、履行候选与服务端 ACL 契约.

**Status:** done

- [x] 新建 CarePlan 先上传全部计划照片再 commit；接收端完整下载并在单事务应用后才显示
- [x] 编辑、跳过、删除和 Record 转计划以版本化原子包收敛，不暴露半次更新或无主照片
- [x] 包未完整应用时不出现在待履行区/乐记日历，也不安排任何提醒或系统日历副本
- [x] 创建者本机立即保留完整计划；发布前显示琥珀状态并说明其他成员暂不可见、不会提醒
- [x] 支持上传/下载/应用失败、响应丢失、手动重试和下次前台重试；编辑失败时远端保留旧完整版本
- [x] 被动收到计划绝不申请日历权限；已配置设备使用本机披露/目标/提醒偏好建立投影
- [x] 具体自定义项目计划在定义已就绪后应用；布局和本机开关不随计划包传播
- [x] 本机关闭家庭提醒或系统日历不会修改家庭 CarePlan
- [x] SyncPort 集成测试覆盖各具体项目、0–3 张照片、tombstone、失败不可见和本机投影时机

Notes (care-w12):
- CarePlanEntity.syncDirty + Room v14 migration (dirty-all existing plans for first publish).
- RealSyncPort mirrors record atomic bundles for care_plan roots (stage/put/commit); media wire XOR care_plan_client_uuid vs record_client_uuid.
- CarePlanFamilyAppliedListener (domain Lazy binding) projects/cancels after full apply only.
- Log pending plan rows show amber 仅本机 chrome via localCarePlanPublishLabel/Detail.
- Tests: RealSyncPortTest care_plan atomic create/pull/fail/custom wait/tombstone; CareLog dirty+requestSync + passive projection; schema migration 13→14.
