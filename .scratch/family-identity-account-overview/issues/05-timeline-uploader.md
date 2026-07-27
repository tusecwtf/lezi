# 05 — 时间轴记录上传者

**What to build:** 已加入家庭时，时间轴上**别人记的**护理记录在次要信息显示其**当前家庭称呼**；自己写的不标；未加入家庭时整轴不显示上传者；对方改称呼后历史展示跟当前名（不写快照）。不向用户展示 device id。

**Blocked by:** 01 — 家庭称呼可认（解析源）

**Status:** done

**Size:** M  
**Seams:** S3

- [x] 非本机记录显示上传者当前家庭称呼
- [x] 本机记录不显示上传者行
- [x] 未加入家庭时不显示上传者
- [x] 改称呼后历史记录展示跟随新称呼
- [x] 无法解析时用「家人」或角色兜底，不裸奔设备标识
- [x] 不改全局顶栏；不做写入时名称快照
- [x] S3 展示解析单测覆盖上述分支

Fresh-deployment override（2026-07-27）：原勾选只证明用户可见行为，不证明 current-only identity surface。

- [ ] 最终候选证明上传者只按 `createdByMembershipId` 解析，源码/wire/schema/APK 中不存在 `createdByUserId`/`created_by_device_id` author fallback 或 device resolver
