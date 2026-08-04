---
status: partially superseded by ADR-0017
---

# 先对账，再从 Room 临时规划家庭发布

Android 家庭同步的长期本机事实是 Room 中的护理实体、媒体行与文件；每个 Owner 或 Member
同步周期先把远端权威对账进 Room，再从仍为待发布的实体临时生成发布计划，并继续通过现有
atomic bundle 协议提交。发布成功只以修订 CAS 写回发布回执并清除待发布标记；失败或进程死亡
只丢弃内存计划，Room 内容和待发布标记保留到下次重新规划。

不再用跨进程 outbox 表保存第二份 payload/epoch 真相。升级时必须先把旧 outbox 身份交接为
对应 Room 实体的待发布资格，再移除该表；浅层「待同步」数量也只统计仍需发布的 Room 实体。
这一选择最初保留增量 pull、现有 LWW/履行裁决与 atomic bundle wire，并拒绝新增
head-by-UUID、全家庭快照或服务端协议。ADR-0017 保留先对账、Room 长期事实、临时计划与
atomic commit，但取代该协议限制和“任意 dirty 即待发布”的状态边界。
