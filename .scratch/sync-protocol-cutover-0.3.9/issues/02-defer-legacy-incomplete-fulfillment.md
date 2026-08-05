# 02 — 隔离旧协议不完整履行并恢复全家庭 pull

**What to build:** 把旧协议留下的“completed CarePlan 已存在但关联 Record 缺失”识别为耐久、不可见的延后履行，使它不再让 pull 返回 500，同时保留足够证据等待原 Record 回补。其它不能安全分类的损坏继续阻止服务器 ready。

**Blocked by:** 01

**Status:** complete — accepted on `ff80edbf` and live NAS startup

- [x] 启动校验可将具有规范计划 payload、不可变履行绑定和家庭归属证据的旧半套履行分类为延后履行，不删除、不改写也不伪造 Record。
- [x] 延后履行不进入公开 pull 图；新成员和旧成员均能继续拉取全部无关 Baby、Record、CarePlan、CustomItem、候选和媒体，且不再收到该历史 500。
- [x] 客户端在延后期间完成的 pull 不会永久跨过该单元；关联 Record 到达并完成解析后，完整关系仍能被已同步过的客户端获取。
- [x] 无效 payload、跨家庭引用、证据不足、存储故障等未知损坏保持 fail closed，并阻止 ready；不得用通用异常吞掉所有悬空引用。
- [x] 记录有界、脱敏、结构化的校验摘要、延后数量、解析结果和 fatal reason code；日志不得包含凭据、姓名、备注、完整 payload 或媒体内容。
- [x] 普通升级不执行手工 SQLite 修复、部署脚本数据改写或服务器 schema 迁移。
