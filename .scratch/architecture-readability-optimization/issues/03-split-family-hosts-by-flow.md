# 03 — 账户按 Overview / MembersDevices / Wizard 三缝切开

**What to build:** 打开账户概览时只面对概览与同步状态一句、可选更新入口；进入「家庭成员与设备」时只加载成员/设备/审批命令；连接家庭/扫码仍走共享家庭向导。维护者改成员页不必读完整五流 God 表面；用户侧账户概览信息架构不回退。

**Blocked by:** 02 — QR 登录进家庭向导并统一错误文案

**Status:** ready-for-agent

- [ ] 三条调用流 host：AccountOverview、MembersDevices、Wizard（含 02 的 QR）职责分离
- [ ] 未新增只转发原成员命令的浅 manager
- [ ] 概览仍符合账户概览：家庭名、本人称呼、同步状态一句、成员与设备入口；无技术凭证堆砌
- [ ] 宝宝档案与自托管更新保持薄委托，未新造 AppUpdate 独立 port
- [ ] feature 模块之间仍无互相依赖；相关测试与 debug 组装通过
